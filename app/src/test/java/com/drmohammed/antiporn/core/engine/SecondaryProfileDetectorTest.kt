package com.drmohammed.antiporn.core.engine

import android.os.UserHandle
import org.junit.Assert.assertEquals
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
}
