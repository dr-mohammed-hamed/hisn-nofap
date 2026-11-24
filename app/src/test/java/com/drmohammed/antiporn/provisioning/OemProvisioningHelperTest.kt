package com.drmohammed.antiporn.provisioning

import android.content.Context
import android.os.TestUserManager
import android.os.UserHandle
import android.os.UserManager
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.provisioning.engine.OemProvisioningHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class OemProvisioningHelperTest {

    private fun createUserHandle(): UserHandle {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null) as Unsafe
        return unsafe.allocateInstance(UserHandle::class.java) as UserHandle
    }

    private class TestContext(
        private val userManager: UserManager?
    ) : android.content.ContextWrapper(null) {
        override fun getSystemService(name: String): Any? {
            return if (name == Context.USER_SERVICE) userManager else null
        }
    }

    @Test
    fun testHasConflictingProfile_nullUserManager() {
        val context = TestContext(userManager = null)
        assertFalse(OemProvisioningHelper.hasConflictingProfile(context))
    }

    @Test
    fun testHasConflictingProfile_singleUserProfile() {
        val userHandle = createUserHandle()
        val userManager = TestUserManager(listOf(userHandle))
        val context = TestContext(userManager)
        assertFalse("Single user profile should not be considered conflicting", OemProvisioningHelper.hasConflictingProfile(context))
    }

    @Test
    fun testHasConflictingProfile_multipleUserProfiles() {
        val user1 = createUserHandle()
        val user2 = createUserHandle()
        val userManager = TestUserManager(listOf(user1, user2))
        val context = TestContext(userManager)
        assertTrue("Multiple user profiles should be flagged as conflicting", OemProvisioningHelper.hasConflictingProfile(context))
    }

    @Test
    fun testHasConflictingProfile_emptyUserProfiles() {
        val userManager = TestUserManager(emptyList())
        val context = TestContext(userManager)
        assertFalse("Empty user profile list should not be conflicting", OemProvisioningHelper.hasConflictingProfile(context))
    }

    @Test
    fun testHasConflictingProfile_exceptionHandling() {
        val context = object : android.content.ContextWrapper(null) {
            override fun getSystemService(name: String): Any? {
                throw RuntimeException("Simulated security/binder error")
            }
        }
        assertFalse("Exceptions should be caught and return false safely", OemProvisioningHelper.hasConflictingProfile(context))
    }

    @Test
    fun testGetOemWarning_withConflictingProfile() {
        val user1 = createUserHandle()
        val user2 = createUserHandle()
        val userManager = TestUserManager(listOf(user1, user2))
        val context = TestContext(userManager)

        val warning = OemProvisioningHelper.getOemWarning(context)
        assertNotNull("Warning should not be null when conflicting profiles exist", warning)
        if (OemProvisioningHelper.isSamsung()) {
            assertEquals(R.string.oem_samsung_multiuser_title, warning?.titleRes)
            assertEquals(R.string.oem_samsung_multiuser_desc, warning?.descRes)
            assertEquals(true, warning?.isCritical)
        } else {
            assertEquals(R.string.dialog_danger_private_space_title, warning?.titleRes)
            assertEquals(R.string.dialog_danger_private_space_desc, warning?.descRes)
            assertEquals(true, warning?.isCritical)
        }
    }

    @Test
    fun testGetOemWarning_withoutConflictingProfile() {
        val userHandle = createUserHandle()
        val userManager = TestUserManager(listOf(userHandle))
        val context = TestContext(userManager)

        val warning = OemProvisioningHelper.getOemWarning(context)
        if (OemProvisioningHelper.isSamsung()) {
            assertNotNull("Samsung devices should show preventive warning", warning)
            assertEquals(R.string.oem_samsung_preventive_title, warning?.titleRes)
            assertEquals(R.string.oem_samsung_preventive_desc, warning?.descRes)
            assertEquals(true, warning?.isCritical)
        } else if (!OemProvisioningHelper.isXiaomi() && !OemProvisioningHelper.isRealmeOrOppo()) {
            assertNull("Warning should be null on standard devices with no conflicting profiles", warning)
        }
    }

    @Test
    fun testCheckSamsungMultiUser() {
        val user1 = createUserHandle()
        val user2 = createUserHandle()
        val userManager = TestUserManager(listOf(user1, user2))
        val context = TestContext(userManager)

        val result = OemProvisioningHelper.checkSamsungMultiUser(context)
        if (OemProvisioningHelper.isSamsung()) {
            assertTrue(result)
        } else {
            assertFalse(result)
        }
    }

    @Test
    fun testBuildAllOemWarnings_samsungWithConflictingProfile() {
        val warnings = OemProvisioningHelper.buildAllOemWarnings(
            hasConflictingProfile = true,
            isSamsung = true,
            isXiaomi = false,
            isRealmeOrOppo = false
        )
        assertEquals(1, warnings.size)
        assertEquals(R.string.oem_samsung_multiuser_title, warnings[0].titleRes)
        assertEquals(R.string.oem_samsung_multiuser_desc, warnings[0].descRes)
        assertTrue("Samsung detected conflict warning must be critical", warnings[0].isCritical)
    }

    @Test
    fun testBuildAllOemWarnings_samsungWithoutConflictingProfileShowsPreventiveWarning() {
        val warnings = OemProvisioningHelper.buildAllOemWarnings(
            hasConflictingProfile = false,
            isSamsung = true,
            isXiaomi = false,
            isRealmeOrOppo = false
        )
        assertEquals(1, warnings.size)
        assertEquals(R.string.oem_samsung_preventive_title, warnings[0].titleRes)
        assertEquals(R.string.oem_samsung_preventive_desc, warnings[0].descRes)
        assertTrue("Samsung preventive warning must be critical", warnings[0].isCritical)
    }

    @Test
    fun testBuildAllOemWarnings_nonSamsungWithCloneProfile() {
        val warnings = OemProvisioningHelper.buildAllOemWarnings(
            hasConflictingProfile = true,
            isSamsung = false,
            isXiaomi = false,
            isRealmeOrOppo = true,
            isOnlyCloneProfile = true
        )
        assertEquals(2, warnings.size)
        assertEquals(R.string.oem_clone_profile_warning_title, warnings[0].titleRes)
        assertEquals(R.string.oem_clone_profile_warning_desc, warnings[0].descRes)
        assertFalse("Clone profile warning should not be critical", warnings[0].isCritical)
        assertEquals(R.string.oem_realme_oppo_title, warnings[1].titleRes)
        assertFalse("OPPO warning should not be critical", warnings[1].isCritical)
    }

    @Test
    fun testBuildAllOemWarnings_nonSamsungWithPrivateSpace() {
        val warnings = OemProvisioningHelper.buildAllOemWarnings(
            hasConflictingProfile = true,
            isSamsung = false,
            isXiaomi = true,
            isRealmeOrOppo = false,
            isOnlyCloneProfile = false
        )
        assertEquals(2, warnings.size)
        assertEquals(R.string.dialog_danger_private_space_title, warnings[0].titleRes)
        assertEquals(R.string.dialog_danger_private_space_desc, warnings[0].descRes)
        assertTrue("Private space warning must be critical", warnings[0].isCritical)
        assertEquals(R.string.oem_xiaomi_title, warnings[1].titleRes)
        assertFalse("Xiaomi warning should not be critical", warnings[1].isCritical)
    }

    @Test
    fun testRequiresPreActivationDangerAck_withConflictingProfile() {
        val user1 = createUserHandle()
        val user2 = createUserHandle()
        val context = TestContext(TestUserManager(listOf(user1, user2)))
        assertTrue(
            "Conflicting profiles must always require danger acknowledgment",
            OemProvisioningHelper.requiresPreActivationDangerAck(context)
        )
    }

    @Test
    fun testRequiresPreActivationDangerAck_withoutConflictingProfile() {
        val user1 = createUserHandle()
        val context = TestContext(TestUserManager(listOf(user1)))
        val required = OemProvisioningHelper.requiresPreActivationDangerAck(context)
        if (OemProvisioningHelper.isSamsung()) {
            assertTrue("Samsung devices must always require danger acknowledgment even without active profiles", required)
        } else {
            assertFalse("Non-Samsung devices without conflicting profiles should not require danger ack", required)
        }
    }

    @Test
    fun testGetDangerDialogStrings_withConflictingProfile() {
        val user1 = createUserHandle()
        val user2 = createUserHandle()
        val context = TestContext(TestUserManager(listOf(user1, user2)))
        val copy = OemProvisioningHelper.getDangerDialogStrings(context)
        if (OemProvisioningHelper.isSamsung()) {
            assertEquals(R.string.oem_samsung_multiuser_title, copy.titleRes)
            assertEquals(R.string.oem_samsung_multiuser_desc, copy.descRes)
        } else {
            assertEquals(R.string.dialog_danger_private_space_title, copy.titleRes)
            assertEquals(R.string.dialog_danger_private_space_desc, copy.descRes)
        }
        assertTrue("Recheck button must be enabled when conflicting profiles are detected", copy.showRecheckButton)
    }

    @Test
    fun testGetDangerDialogStrings_withoutConflictingProfile() {
        val user1 = createUserHandle()
        val context = TestContext(TestUserManager(listOf(user1)))
        val copy = OemProvisioningHelper.getDangerDialogStrings(context)
        assertEquals(R.string.dialog_danger_private_space_title, copy.titleRes)
        if (OemProvisioningHelper.isSamsung()) {
            assertEquals(R.string.dialog_danger_samsung_preventive_desc, copy.descRes)
        } else {
            assertEquals(R.string.dialog_danger_private_space_desc, copy.descRes)
        }
        assertFalse("Recheck button is disabled for preventive warnings without active conflict", copy.showRecheckButton)
    }
}
