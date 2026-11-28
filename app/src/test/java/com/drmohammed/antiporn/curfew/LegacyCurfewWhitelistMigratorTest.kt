package com.drmohammed.antiporn.curfew

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.testing.InMemoryTestSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCurfewWhitelistMigratorTest {

    private val selfPackage = "com.drmohammed.antiporn"

    @Test
    fun `computeMigratedPolicy adds installed legacy exempt apps while preserving existing whitelist and vpn bypass`() {
        val existingWhitelist = setOf("com.whatsapp", "com.google.android.gm")
        val existingVpnBypass = setOf("com.whatsapp")
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            customCurfewWhitelistPackages = existingWhitelist,
            curfewVpnBypassPackages = existingVpnBypass,
            customBlacklistPackages = emptySet()
        )

        val legacyExemptApps = setOf(
            "com.athanotify",
            "com.coloros.calculator",
            "com.coloros.soundrecorder",
            "org.schabi.newpipe.debug",
            "com.mxtech.videoplayer.ad",
            "com.egyptianbanks.instapay"
        )
        val nonExemptApps = setOf(
            "com.instagram.android",
            "com.facebook.katana"
        )
        val installedPackages = existingWhitelist + legacyExemptApps + nonExemptApps

        val result = LegacyCurfewWhitelistMigrator.computeMigratedPolicy(
            policy = policy,
            installedPackages = installedPackages,
            selfPackageName = selfPackage,
            alreadyMigrated = false,
            hasPersistedPolicy = true
        )

        assertTrue(result.migrated)
        assertEquals(legacyExemptApps, result.addedPackages)
        assertEquals(existingWhitelist + legacyExemptApps, result.policy.customCurfewWhitelistPackages)
        assertEquals(existingVpnBypass, result.policy.curfewVpnBypassPackages)
        assertFalse(result.policy.customCurfewWhitelistPackages.contains("com.instagram.android"))
        assertFalse(result.policy.customCurfewWhitelistPackages.contains("com.facebook.katana"))
    }

    @Test
    fun `computeMigratedPolicy strictly excludes blacklisted apps browsers stores search loopholes and self package`() {
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            customCurfewWhitelistPackages = setOf("com.whatsapp"),
            curfewVpnBypassPackages = setOf("com.whatsapp"),
            customBlacklistPackages = setOf("com.athanotify", "com.mxtech.videoplayer.ad")
        )

        val installedPackages = setOf(
            "com.athanotify",
            "com.mxtech.videoplayer.ad",
            "com.coloros.calculator",
            "com.android.chrome",
            "com.android.vending",
            "com.google.android.apps.searchlite",
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.gms",
            selfPackage,
            ""
        )

        val result = LegacyCurfewWhitelistMigrator.computeMigratedPolicy(
            policy = policy,
            installedPackages = installedPackages,
            selfPackageName = selfPackage,
            alreadyMigrated = false,
            hasPersistedPolicy = true
        )

        assertTrue(result.migrated)
        assertEquals(setOf("com.coloros.calculator"), result.addedPackages)
        assertEquals(setOf("com.whatsapp", "com.coloros.calculator"), result.policy.customCurfewWhitelistPackages)
        assertFalse(result.policy.customCurfewWhitelistPackages.contains("com.athanotify"))
        assertFalse(result.policy.customCurfewWhitelistPackages.contains("com.mxtech.videoplayer.ad"))
        assertFalse(result.policy.customCurfewWhitelistPackages.contains("com.android.chrome"))
        assertFalse(result.policy.customCurfewWhitelistPackages.contains("com.android.vending"))
        assertFalse(result.policy.customCurfewWhitelistPackages.contains("com.google.android.apps.searchlite"))
        assertFalse(result.policy.customCurfewWhitelistPackages.contains("android"))
        assertFalse(result.policy.customCurfewWhitelistPackages.contains("com.android.systemui"))
        assertFalse(result.policy.customCurfewWhitelistPackages.contains("com.android.settings"))
        assertFalse(result.policy.customCurfewWhitelistPackages.contains("com.google.android.gms"))
        assertFalse(result.policy.customCurfewWhitelistPackages.contains(selfPackage))
    }

    @Test
    fun `computeMigratedPolicy skips migration on fresh install without persisted policy`() {
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            customCurfewWhitelistPackages = emptySet(),
            curfewVpnBypassPackages = emptySet()
        )
        val installedPackages = setOf(
            "com.athanotify",
            "com.coloros.calculator",
            "com.egyptianbanks.instapay"
        )

        val result = LegacyCurfewWhitelistMigrator.computeMigratedPolicy(
            policy = policy,
            installedPackages = installedPackages,
            selfPackageName = selfPackage,
            alreadyMigrated = false,
            hasPersistedPolicy = false
        )

        assertFalse(result.migrated)
        assertEquals(emptySet<String>(), result.addedPackages)
        assertEquals(policy, result.policy)
    }

    @Test
    fun `computeMigratedPolicy is idempotent and never re-adds user-removed apps once alreadyMigrated is true`() {
        val initialPolicy = DevicePolicyBundle.defaultPolicy().copy(
            customCurfewWhitelistPackages = setOf("com.whatsapp"),
            curfewVpnBypassPackages = setOf("com.whatsapp")
        )
        val installedPackages = setOf(
            "com.whatsapp",
            "com.coloros.calculator",
            "com.athanotify"
        )

        val firstMigration = LegacyCurfewWhitelistMigrator.computeMigratedPolicy(
            policy = initialPolicy,
            installedPackages = installedPackages,
            selfPackageName = selfPackage,
            alreadyMigrated = false,
            hasPersistedPolicy = true
        )
        assertTrue(firstMigration.migrated)
        assertTrue(firstMigration.policy.customCurfewWhitelistPackages.contains("com.coloros.calculator"))

        // User explicitly removes com.coloros.calculator after migration
        val afterUserRemoval = firstMigration.policy.copy(
            customCurfewWhitelistPackages = firstMigration.policy.customCurfewWhitelistPackages - "com.coloros.calculator"
        )

        val rerun = LegacyCurfewWhitelistMigrator.computeMigratedPolicy(
            policy = afterUserRemoval,
            installedPackages = installedPackages,
            selfPackageName = selfPackage,
            alreadyMigrated = true,
            hasPersistedPolicy = true
        )

        assertFalse(rerun.migrated)
        assertEquals(emptySet<String>(), rerun.addedPackages)
        assertEquals(afterUserRemoval, rerun.policy)
        assertFalse(rerun.policy.customCurfewWhitelistPackages.contains("com.coloros.calculator"))
    }

    @Test
    fun `getPendingMigratedExemptPackages filters exempt apps by cloud version and clears on newer version or explicit clear`() {
        val context = FakeMigrationContext()
        val prefs = context.getSharedPreferences(LegacyCurfewWhitelistMigrator.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet(
                LegacyCurfewWhitelistMigrator.KEY_MIGRATED_EXEMPT_PACKAGES,
                setOf("com.athanotify", "com.coloros.calculator", "com.instagram.android")
            )
            .putLong(LegacyCurfewWhitelistMigrator.KEY_MIGRATED_AT_POLICY_VERSION, 10L)
            .commit()

        // Equal version returns only curfew-exempt packages
        val atEqualVersion = LegacyCurfewWhitelistMigrator.getPendingMigratedExemptPackages(context, cloudVersion = 10L)
        assertEquals(setOf("com.athanotify", "com.coloros.calculator"), atEqualVersion)

        // Explicit clear removes persisted pending entries
        LegacyCurfewWhitelistMigrator.clearPendingMigratedExemptPackages(context)
        assertEquals(
            emptySet<String>(),
            LegacyCurfewWhitelistMigrator.getPendingMigratedExemptPackages(context, cloudVersion = 10L)
        )

        // Re-populate and verify newer cloudVersion clears and returns empty
        prefs.edit()
            .putStringSet(LegacyCurfewWhitelistMigrator.KEY_MIGRATED_EXEMPT_PACKAGES, setOf("com.athanotify"))
            .putLong(LegacyCurfewWhitelistMigrator.KEY_MIGRATED_AT_POLICY_VERSION, 10L)
            .commit()

        assertEquals(
            emptySet<String>(),
            LegacyCurfewWhitelistMigrator.getPendingMigratedExemptPackages(context, cloudVersion = 11L)
        )
        assertFalse(prefs.contains(LegacyCurfewWhitelistMigrator.KEY_MIGRATED_EXEMPT_PACKAGES))
        assertFalse(prefs.contains(LegacyCurfewWhitelistMigrator.KEY_MIGRATED_AT_POLICY_VERSION))
    }

    private class FakeMigrationContext : Application() {
        private val prefsByName = mutableMapOf<String, InMemoryTestSharedPreferences>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefsByName.getOrPut(name ?: "default") { InMemoryTestSharedPreferences() }
        }
    }
}
