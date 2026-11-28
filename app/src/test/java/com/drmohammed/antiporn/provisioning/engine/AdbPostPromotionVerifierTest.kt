package com.drmohammed.antiporn.provisioning.engine

import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import io.github.rhythmcache.adb.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdbPostPromotionVerifierTest {

    private lateinit var context: FakeProvisioningContext

    @Before
    fun setUp() {
        context = FakeProvisioningContext()
        ProvisioningFreezeRecoveryStore.resetForTesting()
        ProvisioningFreezeRecoveryStore.clearLedger(context)
    }

    @After
    fun tearDown() {
        BlackBoxRecorder.awaitPendingWritesBlocking()
        ProvisioningFreezeRecoveryStore.clearLedger(context)
        ProvisioningFreezeRecoveryStore.resetForTesting()
    }

    private fun shellOk(stdout: String = "", stderr: String = ""): ShellResult =
        ShellResult(stdout.toByteArray(), stderr.toByteArray(), 0)

    private fun shellFail(stdout: String = "", stderr: String = "", exitCode: Int = 1): ShellResult =
        ShellResult(stdout.toByteArray(), stderr.toByteArray(), exitCode)

    @Test
    fun postPromotionVerifier_whenDeviceOwnerConfirmed_repairsStateTriggersDnsGateAndDisablesWirelessAdbLast() = runBlocking {
        ProvisioningFreezeRecoveryStore.recordPendingFreeze(context, 0, listOf("com.google.android.gms"))
        var gmsDisabled = true
        ProvisioningFreezeRecoveryStore.disabledStateCheckerOverride = { _, pkg ->
            pkg == "com.google.android.gms" && gmsDisabled
        }

        var pollCount = 0
        var secureSettingsGranted = false
        var usageStatsGranted = false
        var dnsProvisioned = false
        val timeline = mutableListOf<String>()

        val result = AdbPostPromotionVerifier.verifyAndFinalizeWithShell(
            context = context,
            userId = 0,
            didSetupBypass = true,
            shellExecutor = { cmd ->
                timeline += "shell:$cmd"
                when (cmd) {
                    "settings --user 0 get secure user_setup_complete" -> {
                        val repaired = timeline.contains("shell:settings --user 0 put secure user_setup_complete 1")
                        shellOk(if (repaired) "1" else "0")
                    }
                    "settings --user 0 put secure user_setup_complete 1" -> shellOk("")
                    "pm enable --user 0 com.google.android.gms" -> {
                        gmsDisabled = false
                        shellOk("")
                    }
                    "pm list packages -d --user 0" -> shellOk(if (gmsDisabled) "package:com.google.android.gms" else "")
                    "rm -f /data/local/tmp/antiporn_freeze_0_*.lock" -> shellOk("")
                    "pm grant com.drmohammed.antiporn android.permission.WRITE_SECURE_SETTINGS" -> {
                        secureSettingsGranted = true
                        shellOk("")
                    }
                    "appops set com.drmohammed.antiporn ACCESS_RESTRICTED_SETTINGS allow" -> shellOk("")
                    "appops set com.drmohammed.antiporn GET_USAGE_STATS allow" -> {
                        usageStatsGranted = true
                        shellOk("")
                    }
                    "settings put global adb_wifi_enabled 0" -> shellOk("")
                    else -> error("Unexpected command: $cmd")
                }
            },
            isDeviceOwnerChecker = {
                pollCount++
                timeline += "do_poll:$pollCount"
                pollCount >= 2
            },
            canWriteSecureSettingsChecker = { secureSettingsGranted },
            hasUsageStatsChecker = { usageStatsGranted },
            privateDnsProvisioner = {
                dnsProvisioned = true
                timeline += "provision_dns"
            },
            applyEnforcement = {
                timeline += "apply_enforcement"
            },
            pollIntervalMs = 0L,
            sdkInt = 34
        )

        assertEquals(PromotionResult.Success, result)
        assertNull("Stranded frozen package ledger must be healed and cleared", ProvisioningFreezeRecoveryStore.getPendingLedger(context))
        assertTrue(secureSettingsGranted)
        assertTrue(usageStatsGranted)
        assertTrue("Private DNS gate must run on SDK >= 29", dnsProvisioned)

        val setupRepairIdx = timeline.indexOf("shell:settings --user 0 put secure user_setup_complete 1")
        val healEnableIdx = timeline.indexOf("shell:pm enable --user 0 com.google.android.gms")
        val grantSecureIdx = timeline.indexOf("shell:pm grant com.drmohammed.antiporn android.permission.WRITE_SECURE_SETTINGS")
        val grantRestrictedIdx = timeline.indexOf("shell:appops set com.drmohammed.antiporn ACCESS_RESTRICTED_SETTINGS allow")
        val grantUsageStatsIdx = timeline.indexOf("shell:appops set com.drmohammed.antiporn GET_USAGE_STATS allow")
        val dnsIdx = timeline.indexOf("provision_dns")
        val enforcementIdx = timeline.indexOf("apply_enforcement")
        val disableWifiAdbIdx = timeline.indexOf("shell:settings put global adb_wifi_enabled 0")

        assertTrue("ACCESS_RESTRICTED_SETTINGS retry must execute when usage stats is missing", grantRestrictedIdx >= 0)
        assertTrue("GET_USAGE_STATS retry must execute when permission is missing", grantUsageStatsIdx >= 0)
        assertTrue("ACCESS_RESTRICTED_SETTINGS must run before GET_USAGE_STATS", grantRestrictedIdx < grantUsageStatsIdx)
        assertTrue("DO confirmation polling must happen before repairs", timeline.indexOf("do_poll:2") < setupRepairIdx)
        assertTrue("user_setup_complete repair must run before freeze heal", setupRepairIdx < healEnableIdx)
        assertTrue("freeze heal must run before WRITE_SECURE_SETTINGS retry", healEnableIdx < grantSecureIdx)
        assertTrue("WRITE_SECURE_SETTINGS retry must run before ACCESS_RESTRICTED_SETTINGS retry", grantSecureIdx < grantRestrictedIdx)
        assertTrue("GET_USAGE_STATS retry must run before DNS check", grantUsageStatsIdx < dnsIdx)
        assertTrue("DNS check must run before enforcement", dnsIdx < enforcementIdx)
        assertTrue("enforcement must run before disabling Wireless ADB", enforcementIdx < disableWifiAdbIdx)
        assertEquals("Disabling adb_wifi_enabled must be the absolute final step", timeline.lastIndex, disableWifiAdbIdx)
    }

    @Test
    fun postPromotionVerifier_whenUsageStatsAlreadyGranted_skipsRestrictedSettingsAndUsageStatsRetry() = runBlocking {
        val executedCommands = mutableListOf<String>()
        var usageStatsChecks = 0

        val result = AdbPostPromotionVerifier.verifyAndFinalizeWithShell(
            context = context,
            userId = 0,
            didSetupBypass = false,
            shellExecutor = { cmd ->
                executedCommands += cmd
                shellOk("")
            },
            isDeviceOwnerChecker = { true },
            canWriteSecureSettingsChecker = { true },
            hasUsageStatsChecker = {
                usageStatsChecks++
                true
            },
            privateDnsProvisioner = {},
            applyEnforcement = {},
            pollIntervalMs = 0L,
            sdkInt = 34
        )

        assertEquals(PromotionResult.Success, result)
        assertEquals(1, usageStatsChecks)
        assertFalse(executedCommands.any { it.contains("ACCESS_RESTRICTED_SETTINGS") })
        assertFalse(executedCommands.any { it.contains("GET_USAGE_STATS") })
        assertEquals(listOf("settings put global adb_wifi_enabled 0"), executedCommands)
    }

    @Test
    fun postPromotionVerifier_whenUsageStatsMissingThenGranted_runsRestrictedSettingsThenUsageStatsAndReverifies() = runBlocking {
        val executedCommands = mutableListOf<String>()
        var usageStatsGranted = false
        var checkCalls = 0

        val result = AdbPostPromotionVerifier.verifyAndFinalizeWithShell(
            context = context,
            userId = 0,
            didSetupBypass = false,
            shellExecutor = { cmd ->
                executedCommands += cmd
                when (cmd) {
                    "appops set com.drmohammed.antiporn ACCESS_RESTRICTED_SETTINGS allow" -> shellOk("")
                    "appops set com.drmohammed.antiporn GET_USAGE_STATS allow" -> {
                        usageStatsGranted = true
                        shellOk("")
                    }
                    "settings put global adb_wifi_enabled 0" -> shellOk("")
                    else -> error("Unexpected command: $cmd")
                }
            },
            isDeviceOwnerChecker = { true },
            canWriteSecureSettingsChecker = { true },
            hasUsageStatsChecker = {
                checkCalls++
                usageStatsGranted
            },
            privateDnsProvisioner = {},
            applyEnforcement = {},
            pollIntervalMs = 0L,
            sdkInt = 34
        )

        assertEquals(PromotionResult.Success, result)
        assertTrue(usageStatsGranted)
        assertEquals(2, checkCalls)
        assertEquals(
            listOf(
                "appops set com.drmohammed.antiporn ACCESS_RESTRICTED_SETTINGS allow",
                "appops set com.drmohammed.antiporn GET_USAGE_STATS allow",
                "settings put global adb_wifi_enabled 0"
            ),
            executedCommands
        )
    }

    @Test
    fun postPromotionVerifier_whenStrandedPackagesRemainDisabled_keepsWirelessAdbOpenAndFails() = runBlocking {
        ProvisioningFreezeRecoveryStore.recordPendingFreeze(context, 0, listOf("com.google.android.gms"))
        ProvisioningFreezeRecoveryStore.disabledStateCheckerOverride = { _, _ -> true }
        val executedCommands = mutableListOf<String>()

        val result = AdbPostPromotionVerifier.verifyAndFinalizeWithShell(
            context = context,
            userId = 0,
            didSetupBypass = false,
            shellExecutor = { cmd ->
                executedCommands += cmd
                when (cmd) {
                    "pm enable --user 0 com.google.android.gms" -> shellFail("", "Failure", 1)
                    "pm list packages -d --user 0" -> shellOk("package:com.google.android.gms")
                    else -> shellOk("")
                }
            },
            isDeviceOwnerChecker = { true },
            canWriteSecureSettingsChecker = { true },
            hasUsageStatsChecker = { true },
            applyEnforcement = {},
            pollIntervalMs = 0L,
            sdkInt = 34
        )

        assertTrue(result is PromotionResult.Failure)
        assertEquals("heal_failed", (result as PromotionResult.Failure).message)
        assertFalse(
            "Wireless ADB must remain open when stranded packages still need recovery",
            executedCommands.contains("settings put global adb_wifi_enabled 0")
        )
    }

    @Test
    fun postPromotionVerifier_whenDeviceOwnerNeverConfirmed_failsWithoutDisablingWirelessAdb() = runBlocking {
        val executedCommands = mutableListOf<String>()
        var enforcementCalled = false

        val result = AdbPostPromotionVerifier.verifyAndFinalizeWithShell(
            context = context,
            userId = 0,
            didSetupBypass = true,
            shellExecutor = { cmd ->
                executedCommands += cmd
                shellOk("")
            },
            isDeviceOwnerChecker = { false },
            applyEnforcement = { enforcementCalled = true },
            pollIntervalMs = 0L
        )

        assertTrue(result is PromotionResult.Failure)
        assertEquals("post_verify_failed", (result as PromotionResult.Failure).message)
        assertFalse("Enforcement must not run when DO verification fails", enforcementCalled)
        assertTrue("Wireless ADB must remain untouched when DO verification fails", executedCommands.isEmpty())
    }

    @Test
    fun postPromotionVerifier_whenUsageStatsCannotBeGranted_keepsWirelessAdbOpenAndFails() = runBlocking {
        val executedCommands = mutableListOf<String>()

        val result = AdbPostPromotionVerifier.verifyAndFinalizeWithShell(
            context = context,
            userId = 0,
            didSetupBypass = false,
            shellExecutor = { cmd ->
                executedCommands += cmd
                shellOk("")
            },
            isDeviceOwnerChecker = { true },
            canWriteSecureSettingsChecker = { true },
            hasUsageStatsChecker = { false },
            privateDnsProvisioner = {},
            applyEnforcement = {},
            pollIntervalMs = 0L,
            sdkInt = 34
        )

        assertTrue(result is PromotionResult.Failure)
        assertTrue(
            "GET_USAGE_STATS retry must have been attempted",
            executedCommands.any { it.contains("GET_USAGE_STATS") }
        )
        assertFalse(
            "Wireless ADB must remain open when GET_USAGE_STATS is still missing",
            executedCommands.contains("settings put global adb_wifi_enabled 0")
        )
    }
}
