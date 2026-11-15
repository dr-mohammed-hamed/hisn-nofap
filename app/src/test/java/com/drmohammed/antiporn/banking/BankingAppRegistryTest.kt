package com.drmohammed.antiporn.banking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankingAppRegistryTest {

    @Test
    fun testAllowedPackagesContainKeyEgyptianAndArabBanks() {
        assertTrue("InstaPay must be allowed", BankingAppRegistry.isAllowed("com.egyptianbanks.instapay"))
        assertTrue("NBE must be allowed", BankingAppRegistry.isAllowed("com.nbe.mobilebanking"))
        assertTrue("Banque Misr must be allowed", BankingAppRegistry.isAllowed("com.banquemisr.bmonline"))
        assertTrue("CIB must be allowed", BankingAppRegistry.isAllowed("com.cib.cibmobilebanking"))
        assertTrue("Vodafone Cash must be allowed", BankingAppRegistry.isAllowed("com.emeint.android.myservices"))
        assertTrue("Telda must be allowed", BankingAppRegistry.isAllowed("com.telda.app"))
        assertTrue("myFawry must be allowed", BankingAppRegistry.isAllowed("com.fawry.myfawry"))
        assertTrue("Al Rajhi must be allowed", BankingAppRegistry.isAllowed("com.alrajhibank"))
        assertTrue("SNB AlAhli must be allowed", BankingAppRegistry.isAllowed("com.alahli.mobile.banking"))
    }

    @Test
    fun testRejectsUnauthorizedAndDangerousPackages() {
        assertFalse("Chrome must never be allowed as banking app", BankingAppRegistry.isAllowed("com.android.chrome"))
        assertFalse("YouTube must never be allowed as banking app", BankingAppRegistry.isAllowed("com.google.android.youtube"))
        assertFalse("Random package must be rejected", BankingAppRegistry.isAllowed("com.random.untrusted.app"))
        assertFalse("Null must be rejected", BankingAppRegistry.isAllowed(null))
        assertFalse("Empty string must be rejected", BankingAppRegistry.isAllowed(""))
        assertFalse("Whitespace must be rejected", BankingAppRegistry.isAllowed("   "))
    }

    @Test
    fun testCaseInsensitiveAndTrimmingValidation() {
        assertTrue("Case insensitive check", BankingAppRegistry.isAllowed("COM.EGYPTIANBANKS.INSTAPAY"))
        assertTrue("Trimming check", BankingAppRegistry.isAllowed("  com.egyptianbanks.instapay  "))
    }
}
