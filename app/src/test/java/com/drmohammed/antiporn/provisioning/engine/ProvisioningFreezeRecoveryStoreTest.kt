package com.drmohammed.antiporn.provisioning.engine

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProvisioningFreezeRecoveryStoreTest {

    private lateinit var context: FakeProvisioningContext

    @Before
    fun setUp() {
        context = FakeProvisioningContext()
        ProvisioningFreezeRecoveryStore.resetForTesting()
        ProvisioningFreezeRecoveryStore.clearLedger(context)
    }

    @After
    fun tearDown() {
        ProvisioningFreezeRecoveryStore.clearLedger(context)
        ProvisioningFreezeRecoveryStore.resetForTesting()
    }

    @Test
    fun recordPendingFreeze_persistsValidPackagesAndFiltersInvalidOrDuplicateNames() {
        ProvisioningFreezeRecoveryStore.recordPendingFreeze(
            context = context,
            userId = 0,
            packages = listOf(
                "com.google.android.gms",
                "com.whatsapp",
                "com.google.android.gms",
                "com.bad; rm -rf /",
                "singleword",
                ""
            )
        )

        val ledger = ProvisioningFreezeRecoveryStore.getPendingLedger(context)
        assertNotNull(ledger)
        assertEquals(0, ledger!!.userId)
        assertEquals(listOf("com.google.android.gms", "com.whatsapp"), ledger.packages)
        assertTrue(ledger.recordedAtMs > 0L)
    }

    @Test
    fun mergePendingFreeze_preservesPriorSameUserPackages() {
        ProvisioningFreezeRecoveryStore.recordPendingFreeze(context, 0, listOf("com.whatsapp"))
        ProvisioningFreezeRecoveryStore.mergePendingFreeze(context, 0, listOf("com.google.android.gms", "com.whatsapp"))

        val ledger = ProvisioningFreezeRecoveryStore.getPendingLedger(context)
        assertNotNull(ledger)
        assertEquals(listOf("com.whatsapp", "com.google.android.gms"), ledger!!.packages)
    }

    @Test
    fun recordPendingFreeze_clearsLedgerWhenUserIdInvalidOrNoValidPackagesRemain() {
        ProvisioningFreezeRecoveryStore.recordPendingFreeze(context, 0, listOf("com.google.android.gms"))
        assertNotNull(ProvisioningFreezeRecoveryStore.getPendingLedger(context))

        ProvisioningFreezeRecoveryStore.recordPendingFreeze(context, -1, listOf("com.google.android.gms"))
        assertNull("Negative userId must clear existing ledger", ProvisioningFreezeRecoveryStore.getPendingLedger(context))

        ProvisioningFreezeRecoveryStore.recordPendingFreeze(context, 0, listOf("com.google.android.gms"))
        ProvisioningFreezeRecoveryStore.recordPendingFreeze(context, 0, listOf("bad; id", "singleword", "  "))
        assertNull("Empty valid package set must clear existing ledger", ProvisioningFreezeRecoveryStore.getPendingLedger(context))
    }

    @Test
    fun getPendingLedger_handlesMalformedJsonAndFiltersPersistedInvalidEntries() {
        val prefs = context.getSharedPreferences(ProvisioningFreezeRecoveryStore.PREFS_NAME, Context.MODE_PRIVATE)

        prefs.edit().putString("freeze_recovery_ledger_json", "{not-valid-json").commit()
        assertNull(ProvisioningFreezeRecoveryStore.getPendingLedger(context))

        prefs.edit().putString(
            "freeze_recovery_ledger_json",
            """{"userId":0,"recordedAtMs":12345,"packages":["com.whatsapp","bad; id",null,"singleword","com.whatsapp"]}"""
        ).commit()
        val parsed = ProvisioningFreezeRecoveryStore.getPendingLedger(context)
        assertNotNull(parsed)
        assertEquals(listOf("com.whatsapp"), parsed!!.packages)
        assertEquals(12345L, parsed.recordedAtMs)

        prefs.edit().putString(
            "freeze_recovery_ledger_json",
            """{"userId":-1,"recordedAtMs":12345,"packages":["com.whatsapp"]}"""
        ).commit()
        assertNull(ProvisioningFreezeRecoveryStore.getPendingLedger(context))
    }

    @Test
    fun findStillDisabledTrackedPackages_returnsResolvedLabelsAndPrunesEnabledSubset() {
        ProvisioningFreezeRecoveryStore.recordPendingFreeze(
            context = context,
            userId = 10,
            packages = listOf("com.google.android.gms", "com.whatsapp", "org.telegram.messenger")
        )
        val initialTimestamp = ProvisioningFreezeRecoveryStore.getPendingLedger(context)!!.recordedAtMs

        ProvisioningFreezeRecoveryStore.disabledStateCheckerOverride = { _, pkg ->
            pkg != "com.google.android.gms"
        }
        ProvisioningFreezeRecoveryStore.appLabelResolverOverride = { _, pkg ->
            when (pkg) {
                "com.whatsapp" -> "WhatsApp"
                "org.telegram.messenger" -> "Telegram"
                else -> pkg
            }
        }

        val stillDisabled = ProvisioningFreezeRecoveryStore.findStillDisabledTrackedPackages(context)

        assertEquals(
            listOf(
                RecoveryPackageInfo("com.whatsapp", "WhatsApp"),
                RecoveryPackageInfo("org.telegram.messenger", "Telegram")
            ),
            stillDisabled
        )

        val prunedLedger = ProvisioningFreezeRecoveryStore.getPendingLedger(context)
        assertNotNull(prunedLedger)
        assertEquals(10, prunedLedger!!.userId)
        assertEquals(initialTimestamp, prunedLedger.recordedAtMs)
        assertEquals(listOf("com.whatsapp", "org.telegram.messenger"), prunedLedger.packages)
    }

    @Test
    fun findStillDisabledTrackedPackages_preservesCrossUserLedgerWithoutLocalPackageManagerPruning() {
        ProvisioningFreezeRecoveryStore.processUserIdOverride = { 0 }
        ProvisioningFreezeRecoveryStore.recordPendingFreeze(
            context = context,
            userId = 999,
            packages = listOf("com.whatsapp", "com.google.android.gms")
        )

        val stillDisabled = ProvisioningFreezeRecoveryStore.findStillDisabledTrackedPackages(context)
        assertEquals(
            listOf(
                RecoveryPackageInfo("com.whatsapp", "com.whatsapp"),
                RecoveryPackageInfo("com.google.android.gms", "com.google.android.gms")
            ),
            stillDisabled
        )
        assertNotNull(ProvisioningFreezeRecoveryStore.getPendingLedger(context))

        // Also preserve userId=0 ledger when the calling process runs in secondary userId=10
        ProvisioningFreezeRecoveryStore.processUserIdOverride = { 10 }
        ProvisioningFreezeRecoveryStore.recordPendingFreeze(context, 0, listOf("com.whatsapp"))
        assertEquals(listOf(RecoveryPackageInfo("com.whatsapp", "com.whatsapp")), ProvisioningFreezeRecoveryStore.findStillDisabledTrackedPackages(context))
        assertNotNull(ProvisioningFreezeRecoveryStore.getPendingLedger(context))
    }

    @Test
    fun findStillDisabledTrackedPackages_clearsLedgerWhenAllTrackedPackagesAreEnabled() {
        ProvisioningFreezeRecoveryStore.recordPendingFreeze(
            context = context,
            userId = 0,
            packages = listOf("com.google.android.gms", "com.whatsapp")
        )
        ProvisioningFreezeRecoveryStore.disabledStateCheckerOverride = { _, _ -> false }

        val stillDisabled = ProvisioningFreezeRecoveryStore.findStillDisabledTrackedPackages(context)

        assertTrue(stillDisabled.isEmpty())
        assertNull("Ledger must be cleared once all tracked packages are enabled", ProvisioningFreezeRecoveryStore.getPendingLedger(context))
    }

    @Test
    fun findStillDisabledTrackedPackages_fallsBackToPackageNameWhenPackageManagerUnavailable() {
        ProvisioningFreezeRecoveryStore.recordPendingFreeze(context, 0, listOf("com.whatsapp"))
        ProvisioningFreezeRecoveryStore.disabledStateCheckerOverride = { _, _ -> true }

        val stillDisabled = ProvisioningFreezeRecoveryStore.findStillDisabledTrackedPackages(context)
        assertEquals(listOf(RecoveryPackageInfo("com.whatsapp", "com.whatsapp")), stillDisabled)
    }
}
