package com.drmohammed.antiporn.banking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankingAppRegistryTest {

    @Test
    fun testAllowedPackagesContainKeyEgyptianAndArabBanks() {
        // Legacy aliases preserved for backward compatibility
        assertTrue("InstaPay must be allowed", BankingAppRegistry.isAllowed("com.egyptianbanks.instapay"))
        assertTrue("NBE legacy alias must be allowed", BankingAppRegistry.isAllowed("com.nbe.mobilebanking"))
        assertTrue("Banque Misr legacy alias must be allowed", BankingAppRegistry.isAllowed("com.banquemisr.bmonline"))
        assertTrue("CIB legacy alias must be allowed", BankingAppRegistry.isAllowed("com.cib.cibmobilebanking"))
        assertTrue("Vodafone Cash must be allowed", BankingAppRegistry.isAllowed("com.emeint.android.myservices"))
        assertTrue("Orange Cash legacy alias must be allowed", BankingAppRegistry.isAllowed("com.orange.orangecash"))
        assertTrue("Etisalat Cash legacy alias must be allowed", BankingAppRegistry.isAllowed("com.its.android.myetisalat"))
        assertTrue("WE Pay legacy alias must be allowed", BankingAppRegistry.isAllowed("com.te.mywe"))
        assertTrue("Telda must be allowed", BankingAppRegistry.isAllowed("com.telda.app"))
        assertTrue("myFawry must be allowed", BankingAppRegistry.isAllowed("com.fawry.myfawry"))
        assertTrue("Al Rajhi must be allowed", BankingAppRegistry.isAllowed("com.alrajhibank"))
        assertTrue("SNB AlAhli must be allowed", BankingAppRegistry.isAllowed("com.alahli.mobile.banking"))
    }

    @Test
    fun testNewlyAddedEgyptianBanksWalletsAndEgyptPostAreAllowed() {
        val newlyAddedPackages = listOf(
            // New & updated Egyptian bank Play Store IDs
            "com.nbe.mobile",
            "com.nbe.alahly",
            "com.BanqueMisr.MobileBanking",
            "com.banquemisr.bmtonline",
            "com.cib.mobilebanking",
            "com.cib.cibpay",
            "com.qnb.qnbegypt",
            "hr.asseco.android.intesa.isbd.mbanking.alex",
            "com.aaib.mobilebanking",
            "com.ca.egypt.mobile",
            "com.hsbc.hbegmobile",
            "com.scb.mobilebanking",
            "com.egbank.mobilebanking",
            "com.hdb.mobilebanking",
            "com.faisal.fibe",
            "com.emiratesnbd.egypt",
            "com.albaraka.egypt.mobile",
            "com.attijariwafa.egypt",
            "com.fabmisr.mobile",
            "com.mashreq.egypt.mb",
            "com.arabbank.arabilifegp",
            "com.nbk.egypt",
            "com.ebank.mobilebanking",
            "com.banknxt.mobile",
            "com.adcb.egypt.mobile",
            "com.midbank.mobilebanking",
            "com.ubc.mobilebanking",
            // Bank wallets, telecom wallets & Egypt Post
            "com.TE.WEWallet",
            "com.nbe.phonecash",
            "com.nbe.tapandpay",
            "com.banquemisr.wallet",
            "com.BDC.QaheraCash",
            "com.emeint.android.mwallet.smartwallet",
            "com.qnb.bebasata",
            "com.fabmisr.cash2go",
            "com.etisalat.flous",
            "com.orange.eg.smarthub",
            "com.orange.eg",
            "com.paysky.yalla",
            "com.efinance.easypay"
        )

        for (pkg in newlyAddedPackages) {
            assertTrue("Expected newly added banking/wallet package to be allowed: $pkg", BankingAppRegistry.isAllowed(pkg))
        }
    }

    @Test
    fun testMixedCasePackagesPreserveExactCasingAndValidateCaseInsensitively() {
        val mixedCasePackages = listOf(
            "com.TE.WEWallet",
            "com.BDC.QaheraCash",
            "com.BanqueMisr.MobileBanking"
        )

        for (exactPkg in mixedCasePackages) {
            assertTrue(
                "ALLOWED_BANKING_PACKAGES must preserve exact casing for PackageManager: $exactPkg",
                BankingAppRegistry.ALLOWED_BANKING_PACKAGES.contains(exactPkg)
            )
            assertTrue("Exact case must pass isAllowed(): $exactPkg", BankingAppRegistry.isAllowed(exactPkg))
            assertTrue("Lowercase variation must pass isAllowed(): ${exactPkg.lowercase()}", BankingAppRegistry.isAllowed(exactPkg.lowercase()))
            assertTrue("Uppercase variation must pass isAllowed(): ${exactPkg.uppercase()}", BankingAppRegistry.isAllowed(exactPkg.uppercase()))
            assertTrue("Trimmed mixed-case variation must pass isAllowed()", BankingAppRegistry.isAllowed("  $exactPkg  "))
        }
    }

    @Test
    fun testRejectsUnauthorizedAndDangerousPackages() {
        // Browsers
        assertFalse("Chrome must never be allowed as banking app", BankingAppRegistry.isAllowed("com.android.chrome"))
        assertFalse("Firefox must never be allowed as banking app", BankingAppRegistry.isAllowed("org.mozilla.firefox"))
        assertFalse("Edge must never be allowed as banking app", BankingAppRegistry.isAllowed("com.microsoft.emmx"))
        // Social & video apps
        assertFalse("YouTube must never be allowed as banking app", BankingAppRegistry.isAllowed("com.google.android.youtube"))
        assertFalse("Instagram must never be allowed as banking app", BankingAppRegistry.isAllowed("com.instagram.android"))
        assertFalse("TikTok must never be allowed as banking app", BankingAppRegistry.isAllowed("com.zhiliaoapp.musically"))
        assertFalse("Telegram must never be allowed as banking app", BankingAppRegistry.isAllowed("org.telegram.messenger"))
        // Untrusted / blank
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
