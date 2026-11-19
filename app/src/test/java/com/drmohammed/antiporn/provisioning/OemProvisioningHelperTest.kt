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
            assertEquals(R.string.oem_samsung_multiuser_title, warning!!.titleRes)
            assertEquals(R.string.oem_samsung_multiuser_desc, warning.descRes)
        } else {
            assertEquals(R.string.dialog_danger_private_space_title, warning!!.titleRes)
            assertEquals(R.string.dialog_danger_private_space_desc, warning.descRes)
        }
    }

    @Test
    fun testGetOemWarning_withoutConflictingProfile() {
        val userHandle = createUserHandle()
        val userManager = TestUserManager(listOf(userHandle))
        val context = TestContext(userManager)

        val warning = OemProvisioningHelper.getOemWarning(context)
        if (!OemProvisioningHelper.isXiaomi() && !OemProvisioningHelper.isRealmeOrOppo()) {
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
}
