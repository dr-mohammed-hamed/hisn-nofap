package com.drmohammed.antiporn.core.engine

import android.os.UserHandle
import com.drmohammed.antiporn.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class SecondaryProfileDetectorTest {

    private fun createUserHandle(): UserHandle {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null) as Unsafe
        return unsafe.allocateInstance(UserHandle::class.java) as UserHandle
    }

    @Test
    fun testExtractUserId_parsesDigitsFromString() {
        val user0 = "UserHandle{0}"
        val parsed0 = Regex("""\d+""").find(user0)?.value?.toIntOrNull()
        assertEquals(0, parsed0)

        val user999 = "UserHandle{999}"
        val parsed999 = Regex("""\d+""").find(user999)?.value?.toIntOrNull()
        assertEquals(999, parsed999)

        val user95 = "UserHandle{95}"
        val parsed95 = Regex("""\d+""").find(user95)?.value?.toIntOrNull()
        assertEquals(95, parsed95)
    }

    @Test
    fun testUnaffiliatedProfileInfo_classification() {
        val fakeHandle = createUserHandle()
        val infoClone = SecondaryProfileDetector.UnaffiliatedProfileInfo(
            userHandle = fakeHandle,
            userId = 999,
            profileType = SecondaryProfileDetector.ProfileType.CLONE_APP,
            clonedAppNames = listOf("WhatsApp", "Telegram"),
            clonedPackageNames = listOf("com.whatsapp", "org.telegram.messenger")
        )
        assertEquals(SecondaryProfileDetector.ProfileType.CLONE_APP, infoClone.profileType)
        assertEquals(2, infoClone.clonedAppNames.size)
        assertTrue(infoClone.clonedAppNames.contains("WhatsApp"))
        assertEquals(999, infoClone.userId)

        val fakeSamsung = createUserHandle()
        val infoSamsung = SecondaryProfileDetector.UnaffiliatedProfileInfo(
            userHandle = fakeSamsung,
            userId = 95,
            profileType = SecondaryProfileDetector.ProfileType.CLONE_APP,
            clonedAppNames = listOf("WhatsApp"),
            clonedPackageNames = listOf("com.whatsapp")
        )
        assertEquals(SecondaryProfileDetector.ProfileType.CLONE_APP, infoSamsung.profileType)
        assertEquals(95, infoSamsung.userId)
    }

    @Test
    fun testProfileTypesEnum() {
        val types = SecondaryProfileDetector.ProfileType.values()
        assertEquals(4, types.size)
        assertTrue(types.contains(SecondaryProfileDetector.ProfileType.CLONE_APP))
        assertTrue(types.contains(SecondaryProfileDetector.ProfileType.WORK_PROFILE))
        assertTrue(types.contains(SecondaryProfileDetector.ProfileType.PRIVATE_SPACE))
        assertTrue(types.contains(SecondaryProfileDetector.ProfileType.SECONDARY_USER))
    }

    @Test
    fun testSecondaryCountResult_deduplicatesAcrossUserProfilesAndDpm() {
        val result = SecondaryProfileDetector.aggregateSecondaryUserCount(
            userProfileIds = listOf(0, 999, 10),
            userProfilesOk = true,
            dpmSecondaryUserIds = listOf(0, 10, 11),
            secondaryUsersOk = true
        )
        assertEquals(
            "Primary user 0 must be excluded and overlapping userId 10 must be deduplicated",
            SecondaryProfileDetector.SecondaryCountResult(count = 3, reliable = true),
            result
        )
    }

    @Test
    fun testSecondaryCountResult_reliabilityReflectsQueryFailures() {
        val reliableEmpty = SecondaryProfileDetector.aggregateSecondaryUserCount(
            userProfileIds = listOf(0),
            userProfilesOk = true,
            dpmSecondaryUserIds = emptyList(),
            secondaryUsersOk = true
        )
        assertEquals(0, reliableEmpty.count)
        assertTrue(reliableEmpty.reliable)

        val dpmFailed = SecondaryProfileDetector.aggregateSecondaryUserCount(
            userProfileIds = listOf(0),
            userProfilesOk = true,
            dpmSecondaryUserIds = emptyList(),
            secondaryUsersOk = false
        )
        assertEquals(0, dpmFailed.count)
        assertFalse("DPM getSecondaryUsers failure must mark count unreliable", dpmFailed.reliable)

        val profilesFailed = SecondaryProfileDetector.aggregateSecondaryUserCount(
            userProfileIds = emptyList(),
            userProfilesOk = false,
            dpmSecondaryUserIds = listOf(10),
            secondaryUsersOk = true
        )
        assertEquals(1, profilesFailed.count)
        assertFalse("UserManager.userProfiles failure must mark count unreliable", profilesFailed.reliable)
    }

    @Test
    fun testMergeSecondaryUserSources_deduplicatesAndPreservesSourceTag() {
        val merged = SecondaryProfileDetector.mergeSecondaryUserSources(
            userProfileIds = listOf(0, 999, 10),
            dpmSecondaryUserIds = listOf(0, 10, 12)
        )
        assertEquals(listOf(999, 10, 12), merged.keys.toList())
        assertEquals(false, merged[999])
        assertEquals("IDs seen in both sources must retain userProfiles origin (false)", false, merged[10])
        assertEquals("IDs seen only via DPM getSecondaryUsers must be marked true", true, merged[12])
    }

    @Test
    fun testClassifySecondaryProfileType_secondaryUserAndProfileVariants() {
        // DPM-only users (e.g., ColorOS System Cloner full secondary user 10/11) classify as SECONDARY_USER
        assertEquals(
            SecondaryProfileDetector.ProfileType.SECONDARY_USER,
            SecondaryProfileDetector.classifySecondaryProfileType(userId = 10, isFromDpmOnly = true)
        )
        assertEquals(
            SecondaryProfileDetector.ProfileType.SECONDARY_USER,
            SecondaryProfileDetector.classifySecondaryProfileType(userId = 11, isFromDpmOnly = true)
        )

        // Regular secondary user from userProfiles (not clone, not managed, not private space)
        assertEquals(
            SecondaryProfileDetector.ProfileType.SECONDARY_USER,
            SecondaryProfileDetector.classifySecondaryProfileType(
                userId = 12,
                isFromDpmOnly = false,
                isManagedProfile = false,
                isPrivateSpace = false
            )
        )

        // Clone apps, work profiles, and private space remain classified accurately
        assertEquals(
            SecondaryProfileDetector.ProfileType.CLONE_APP,
            SecondaryProfileDetector.classifySecondaryProfileType(userId = 999, isFromDpmOnly = false)
        )
        assertEquals(
            SecondaryProfileDetector.ProfileType.WORK_PROFILE,
            SecondaryProfileDetector.classifySecondaryProfileType(
                userId = 10,
                isFromDpmOnly = false,
                isManagedProfile = true
            )
        )
        assertEquals(
            SecondaryProfileDetector.ProfileType.PRIVATE_SPACE,
            SecondaryProfileDetector.classifySecondaryProfileType(
                userId = 10,
                isFromDpmOnly = false,
                isManagedProfile = false,
                isPrivateSpace = true
            )
        )
    }

    @Test
    fun testIsRemovableSecondaryUserId_rejectsPrimaryAndInvalidIds() {
        assertFalse(SecondaryProfileDetector.isRemovableSecondaryUserId(0))
        assertFalse(SecondaryProfileDetector.isRemovableSecondaryUserId(-1))
        assertFalse(SecondaryProfileDetector.isRemovableSecondaryUserId(10, primaryUserId = 10))
        assertTrue(SecondaryProfileDetector.isRemovableSecondaryUserId(999))
        assertTrue(SecondaryProfileDetector.isRemovableSecondaryUserId(95))
        assertTrue(SecondaryProfileDetector.isRemovableSecondaryUserId(10))
    }

    @Test
    fun testGetProfileTypeLabelRes_mapsEveryProfileTypeDistinctly() {
        val labels = SecondaryProfileDetector.ProfileType.values()
            .map { SecondaryProfileDetector.getProfileTypeLabelRes(it) }
            .toSet()
        assertEquals(4, labels.size)
        assertTrue(labels.contains(R.string.profile_type_clone_app))
        assertTrue(labels.contains(R.string.profile_type_work_profile))
        assertTrue(labels.contains(R.string.profile_type_private_space))
        assertTrue(labels.contains(R.string.profile_type_secondary_user))
    }
}
