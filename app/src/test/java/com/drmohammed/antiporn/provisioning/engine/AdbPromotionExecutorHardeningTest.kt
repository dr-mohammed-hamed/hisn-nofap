package com.drmohammed.antiporn.provisioning.engine

import com.drmohammed.antiporn.provisioning.service.AdbPairingPromptState
import com.drmohammed.antiporn.provisioning.ui.BlockingAccountsPayload
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import io.github.rhythmcache.adb.ShellResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class AdbPromotionExecutorHardeningTest {

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
    fun classifySetupBypassOutput_returnsSuccessOnlyOnCleanSuccessOutput() {
        val successes = listOf(
            "Success: Device owner set to package com.drmohammed.antiporn/.AdminReceiver",
            "Device owner set to package ComponentInfo{com.drmohammed.antiporn/com.drmohammed.antiporn.AdminReceiver}"
        )
        for (output in successes) {
            assertEquals(
                "Expected Success for: $output",
                SetupBypassOutcome.Success,
                AdbPromotionExecutor.classifySetupBypassOutput(context, output, isXiaomi = false, isRealmeOrOppo = false)
            )
        }

        val falseSuccessPayloads = listOf(
            "Success? java.lang.SecurityException: denied",
            "Error: Success check failed"
        )
        for (output in falseSuccessPayloads) {
            val outcome = AdbPromotionExecutor.classifySetupBypassOutput(context, output, isXiaomi = false, isRealmeOrOppo = false)
            assertFalse("Must not classify error/exception output as Success: $output", outcome is SetupBypassOutcome.Success)
        }
    }

    @Test
    fun classifySetupBypassOutput_returnsOemPermissionRequiredForXiaomiAndRealmeAndHardStopForGeneric() {
        val permissionPayloads = listOf(
            "java.lang.SecurityException: Neither user 2000 nor current process has android.permission.MANAGE_DEVICE_ADMINS",
            "Permission Denial: setDeviceOwner from pid=1234",
            "Please enable USB debugging (Security settings) in Developer options",
            "Error: permission monitoring is active"
        )

        for (payload in permissionPayloads) {
            val xiaomiOutcome = AdbPromotionExecutor.classifySetupBypassOutput(
                context, payload, isXiaomi = true, isRealmeOrOppo = false
            )
            assertTrue("Expected Xiaomi OemPermissionRequired for: $payload", xiaomiOutcome is SetupBypassOutcome.OemPermissionRequired)
            xiaomiOutcome as SetupBypassOutcome.OemPermissionRequired
            assertEquals(OemPermissionType.XIAOMI_SECURITY_SETTINGS, xiaomiOutcome.oemType)
            assertEquals("xiaomi_security_settings", xiaomiOutcome.message)

            val realmeOutcome = AdbPromotionExecutor.classifySetupBypassOutput(
                context, payload, isXiaomi = false, isRealmeOrOppo = true
            )
            assertTrue("Expected Realme/Oppo OemPermissionRequired for: $payload", realmeOutcome is SetupBypassOutcome.OemPermissionRequired)
            realmeOutcome as SetupBypassOutcome.OemPermissionRequired
            assertEquals(OemPermissionType.REALME_OPPO_PERMISSION_MONITORING, realmeOutcome.oemType)
            assertEquals("realme_permission_monitoring", realmeOutcome.message)

            val genericOutcome = AdbPromotionExecutor.classifySetupBypassOutput(
                context, payload, isXiaomi = false, isRealmeOrOppo = false
            )
            assertTrue("Expected HardStopFailure on non-Xiaomi/non-Realme device for: $payload", genericOutcome is SetupBypassOutcome.HardStopFailure)
            assertFalse(genericOutcome is SetupBypassOutcome.AccountsBlockedFailure)
        }
    }

    @Test
    fun classifySetupBypassOutput_doesNotMisclassifyAlreadyProvisionedAsAlreadyDeviceOwner() {
        val alreadyProvisioned = "java.lang.IllegalStateException: Trying to set the device owner, but device is already provisioned."
        val outcome = AdbPromotionExecutor.classifySetupBypassOutput(
            context, alreadyProvisioned, isXiaomi = false, isRealmeOrOppo = false
        )
        assertTrue(outcome is SetupBypassOutcome.HardStopFailure)
        assertEquals(alreadyProvisioned, (outcome as SetupBypassOutcome.HardStopFailure).message)
    }

    @Test
    fun classifySetupBypassOutput_returnsSecondaryUsersErrorAndNeverAccountsBlocked() {
        val secondaryUserPayloads = listOf(
            "java.lang.IllegalStateException: Not allowed to set the device owner because there are already several users on the device",
            "Error: cannot provision while user 150 exists",
            "Blocked by active clone profile user 95"
        )
        for (payload in secondaryUserPayloads) {
            val outcome = AdbPromotionExecutor.classifySetupBypassOutput(
                context, payload, isXiaomi = false, isRealmeOrOppo = false
            )
            assertTrue("Expected SecondaryUsersError for: $payload", outcome is SetupBypassOutcome.SecondaryUsersError)
            assertFalse("Secondary users error must NEVER be classified as AccountsBlockedFailure", outcome is SetupBypassOutcome.AccountsBlockedFailure)
        }
    }

    @Test
    fun classifySetupBypassOutput_returnsHardStopFailureOnOwnerConflictPrecondition99AndGenericErrors() {
        val cases = listOf(
            "java.lang.IllegalStateException: Trying to set the device owner, but device already has a device owner." to "already_device_owner",
            "Failure: precondition 99 check failed" to "realme_precondition",
            "Unknown binder transaction error" to "Unknown binder transaction error"
        )
        for ((payload, expectedMsg) in cases) {
            val outcome = AdbPromotionExecutor.classifySetupBypassOutput(
                context, payload, isXiaomi = true, isRealmeOrOppo = false
            )
            assertTrue("Expected HardStopFailure for: $payload", outcome is SetupBypassOutcome.HardStopFailure)
            assertFalse("HardStopFailure must NEVER be AccountsBlockedFailure", outcome is SetupBypassOutcome.AccountsBlockedFailure)
            assertEquals(expectedMsg, (outcome as SetupBypassOutcome.HardStopFailure).message)
        }
    }

    @Test
    fun classifySetupBypassOutput_returnsAccountsBlockedFailureOnlyOnAccountBlockers() {
        val accountPayloads = listOf(
            "java.lang.IllegalStateException: Not allowed to set the device owner because there are already some accounts on the device",
            "Failure: device has accounts",
            "Cannot set device owner: accounts on the device",
            "Error: account(s) on the device must be removed",
            "Blocked by existing accounts"
        )
        for (payload in accountPayloads) {
            val outcome = AdbPromotionExecutor.classifySetupBypassOutput(
                context, payload, isXiaomi = true, isRealmeOrOppo = false
            )
            assertTrue("Expected AccountsBlockedFailure for: $payload", outcome is SetupBypassOutcome.AccountsBlockedFailure)
        }
    }

    @Test
    fun executeZeroAccountPromotionWithShell_handlesNonXiaomiDenialRetryBypassAndUnhealedLedgerAbort() = runBlocking {
        // 1. Generic non-Xiaomi device denied user_setup_complete 0 must return HardStop Failure (not Xiaomi dialog)
        val genericResult = AdbPromotionExecutor.executeZeroAccountPromotionWithShell(
            context = context,
            adminComponent = "com.drmohammed.antiporn/.AdminReceiver",
            userId = 0,
            shellExecutor = { cmd ->
                when {
                    cmd.startsWith("getprop ") -> shellOk("34")
                    cmd.startsWith("pm grant ") -> shellOk("")
                    cmd.startsWith("appops set ") || cmd.startsWith("dumpsys deviceidle") -> shellOk("")
                    cmd.startsWith("settings ") -> shellFail("", "SecurityException: denied", 1)
                    cmd == "dumpsys account" -> shellOk("Account {name=a@gmail.com, type=com.google}")
                    cmd.startsWith("echo '") -> shellOk("")
                    cmd.startsWith("rm -f ") -> shellOk("")
                    else -> shellOk("")
                }
            },
            isDeviceOwnerChecker = { false },
            canWriteSecureSettingsChecker = { true },
            hasUsageStatsChecker = { true },
            blockingUsersInspector = { emptyList() },
            sdkVersionProvider = { 34 },
            isXiaomi = false,
            isRealmeOrOppo = false,
            postPromotionVerifier = { _, _, _ -> error("Verifier must not run on denied setup bypass") },
            packageFreezeRunner = { _, _, _ -> error("Freeze must not run on generic SecurityException") }
        )
        assertTrue(genericResult is PromotionResult.Failure)

        // 2. Retry when already Device Owner passes didSetupBypass = true to postPromotionVerifier
        var observedDidBypass: Boolean? = null
        val retryResult = AdbPromotionExecutor.executeZeroAccountPromotionWithShell(
            context = context,
            adminComponent = "com.drmohammed.antiporn/.AdminReceiver",
            userId = 0,
            shellExecutor = { shellOk("") },
            isDeviceOwnerChecker = { true },
            postPromotionVerifier = { _, _, didBypass ->
                observedDidBypass = didBypass
                PromotionResult.Success
            },
            packageFreezeRunner = { _, _, _ -> error("Unused") }
        )
        assertEquals(PromotionResult.Success, retryResult)
        assertEquals(true, observedDidBypass)

        // 3. Unhealed prior ledger aborts before freezing new packages
        ProvisioningFreezeRecoveryStore.recordPendingFreeze(context, 0, listOf("com.whatsapp"))
        val abortedResult = AdbPromotionExecutor.executeZeroAccountPromotionWithShell(
            context = context,
            adminComponent = "com.drmohammed.antiporn/.AdminReceiver",
            userId = 0,
            shellExecutor = { cmd ->
                when (cmd) {
                    "pm enable --user 0 com.whatsapp" -> shellOk("")
                    "pm list packages -d --user 0" -> shellOk("package:com.whatsapp")
                    else -> error("Must not run promotion commands when stranded ledger remains: $cmd")
                }
            },
            isDeviceOwnerChecker = { false },
            postPromotionVerifier = { _, _, _ -> error("Unused") },
            packageFreezeRunner = { _, _, _ -> error("Unused") }
        )
        assertTrue(abortedResult is PromotionResult.Failure)
        assertEquals("heal_failed", (abortedResult as PromotionResult.Failure).message)
    }

    @Test
    fun filterSafePackagesForFreeze_excludesCriticalSelfAlreadyDisabledAndShellInjection() {
        val candidates = listOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.providers.settings",
            "com.miui.home",
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
            "com.oppo.launcher",
            "com.drmohammed.antiporn",
            "com.whatsapp",
            "foo; rm -rf /",
            "com.bad && id",
            "$(whoami)",
            "singleword",
            "com..double",
            "   ",
            "  com.google.android.gms  ",
            "org.telegram.messenger",
            "org.telegram.messenger"
        )

        val safe = AdbPackageFreezeManager.filterSafePackagesForFreeze(
            packages = candidates,
            alreadyDisabledPackages = setOf("com.whatsapp"),
            selfPackageName = "com.drmohammed.antiporn"
        )

        assertEquals(listOf("com.google.android.gms", "org.telegram.messenger"), safe)
    }

    @Test
    fun buildWatchdogScript_usesPureMkshHupTrapAndChecksLockfileBeforeEnable() {
        val lockPath = "/data/local/tmp/antiporn_freeze_0.lock"
        val script = AdbPackageFreezeManager.buildWatchdogScript(
            lockPath = lockPath,
            userId = 0,
            safePackages = listOf("com.google.android.gms", "com.whatsapp"),
            delaySeconds = 20
        )

        assertFalse("Watchdog must not rely on nohup", script.contains("nohup"))
        assertTrue(script.startsWith("( trap '' HUP; exec </dev/null >/dev/null 2>&1; sleep 20; "))
        assertTrue(
            script.contains(
                "if [ -f \"$lockPath\" ]; then pm enable --user 0 com.google.android.gms; pm enable --user 0 com.whatsapp; rm -f \"$lockPath\"; fi ) &"
            )
        )

        val clampedLow = AdbPackageFreezeManager.buildWatchdogScript(lockPath, 0, listOf("com.whatsapp"), 0)
        assertTrue(clampedLow.contains("sleep 1;"))

        val clampedHigh = AdbPackageFreezeManager.buildWatchdogScript(lockPath, 0, listOf("com.whatsapp"), 999)
        assertTrue(clampedHigh.contains("sleep 120;"))
    }

    @Test
    fun buildAtomicFreezeAndDpmScript_trapsSignalsWithExitDisablesOnceHandlesUserFallbackAndRemovesLockfile() {
        val lockPath = "/data/local/tmp/antiporn_freeze_0_123.lock"
        val admin = "com.drmohammed.antiporn/.AdminReceiver"
        val packages = listOf("com.google.android.gms", "com.whatsapp")

        val script = AdbPackageFreezeManager.buildAtomicFreezeAndDpmScript(
            lockPath = lockPath,
            userId = 0,
            adminComponent = admin,
            safePackages = packages
        )

        assertTrue(script.startsWith("sh -c '"))
        assertTrue(script.contains("touch \"$lockPath\";"))
        assertTrue(
            script.contains(
                "restore() { trap - EXIT HUP INT TERM; pm enable --user 0 com.google.android.gms; pm enable --user 0 com.whatsapp; rm -f \"$lockPath\"; };"
            )
        )
        assertTrue(script.contains("trap restore EXIT; trap \"restore; exit 129\" HUP; trap \"restore; exit 130\" INT; trap \"restore; exit 143\" TERM;"))
        assertTrue(script.contains("dpm set-device-owner --user 0 $admin"))
        assertTrue(script.contains("unknown option: --user"))
        assertTrue(script.contains("dpm set-device-owner $admin"))

        for (pkg in packages) {
            val disableCmd = "pm disable-user --user 0 $pkg"
            val count = Regex(Regex.escape(disableCmd)).findAll(script).count()
            assertEquals("Expected $disableCmd exactly once in atomic script", 1, count)
        }
    }

    @Test
    fun scriptBuilders_rejectUnsafeInputsWithIllegalArgumentException() {
        val validLock = "/data/local/tmp/antiporn_freeze_0.lock"
        val validAdmin = "com.drmohammed.antiporn/.AdminReceiver"
        val validPkgs = listOf("com.google.android.gms")

        val invalidActions = listOf<() -> Unit>(
            { AdbPackageFreezeManager.buildWatchdogScript("/tmp/bad;id.lock", 0, validPkgs, 20) },
            { AdbPackageFreezeManager.buildWatchdogScript("/tmp/bad space.lock", 0, validPkgs, 20) },
            { AdbPackageFreezeManager.buildWatchdogScript(validLock, -1, validPkgs, 20) },
            { AdbPackageFreezeManager.buildWatchdogScript(validLock, 0, emptyList(), 20) },
            { AdbPackageFreezeManager.buildWatchdogScript(validLock, 0, listOf("foo; rm -rf /"), 20) },
            { AdbPackageFreezeManager.buildAtomicFreezeAndDpmScript("/tmp/bad;id.lock", 0, validAdmin, validPkgs) },
            { AdbPackageFreezeManager.buildAtomicFreezeAndDpmScript(validLock, 0, "com.bad;id/.Admin", validPkgs) },
            { AdbPackageFreezeManager.buildAtomicFreezeAndDpmScript(validLock, 0, "noslash", validPkgs) },
            { AdbPackageFreezeManager.buildAtomicFreezeAndDpmScript(validLock, -1, validAdmin, validPkgs) },
            { AdbPackageFreezeManager.buildAtomicFreezeAndDpmScript(validLock, 0, validAdmin, emptyList()) },
            { AdbPackageFreezeManager.buildAtomicFreezeAndDpmScript(validLock, 0, validAdmin, listOf("foo; rm -rf /")) }
        )

        for ((index, action) in invalidActions.withIndex()) {
            val result = runCatching(action)
            assertTrue("Case #$index must throw IllegalArgumentException", result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun executePackageFreezeWithShell_happyPath_recordsLedgerBeforeFreezeSkipsDisabledAndClearsLedger() = runBlocking {
        val executedCommands = mutableListOf<String>()
        var ledgerAtWatchdog: FreezeRecoveryLedger? = null
        var ledgerAtAtomic: FreezeRecoveryLedger? = null
        var listDisabledCalls = 0
        var gmsReEnabled = false
        var gsfReEnabled = false

        val result = AdbPackageFreezeManager.executePackageFreezeWithShell(
            context = context,
            userId = 0,
            adminComponent = "com.drmohammed.antiporn/.AdminReceiver",
            accountAuthenticatorMap = mapOf("com.whatsapp" to "com.whatsapp"),
            shellExecutor = { cmd ->
                executedCommands += cmd
                when {
                    cmd == "dumpsys account --checkin" -> shellOk("com.google,1\ncom.whatsapp,1")
                    cmd == "dumpsys account" -> shellOk(
                        """
                        Accounts: 2
                          Account {name=user@gmail.com, type=com.google}
                          Account {name=user, type=com.whatsapp}
                        """.trimIndent()
                    )
                    cmd == "pm list packages -d --user 0" -> {
                        listDisabledCalls++
                        when (listDisabledCalls) {
                            1 -> shellOk("package:com.whatsapp")
                            2 -> shellOk("package:com.whatsapp\npackage:com.google.android.gms\npackage:com.google.android.gsf")
                            else -> {
                                val disabled = buildList {
                                    add("package:com.whatsapp")
                                    if (!gmsReEnabled) add("package:com.google.android.gms")
                                    if (!gsfReEnabled) add("package:com.google.android.gsf")
                                }
                                shellOk(disabled.joinToString("\n"))
                            }
                        }
                    }
                    cmd.startsWith("( trap '' HUP;") -> {
                        ledgerAtWatchdog = ProvisioningFreezeRecoveryStore.getPendingLedger(context)
                        shellOk("")
                    }
                    cmd.startsWith("sh -c '") -> {
                        ledgerAtAtomic = ProvisioningFreezeRecoveryStore.getPendingLedger(context)
                        shellOk("Success: Device owner set to package com.drmohammed.antiporn/.AdminReceiver")
                    }
                    cmd == "pm enable --user 0 com.google.android.gms" -> {
                        gmsReEnabled = true
                        shellOk("Package com.google.android.gms new state: enabled")
                    }
                    cmd == "pm enable --user 0 com.google.android.gsf" -> {
                        gsfReEnabled = true
                        shellOk("Package com.google.android.gsf new state: enabled")
                    }
                    cmd.startsWith("rm -f ") -> shellOk("")
                    else -> error("Unexpected shell command: $cmd")
                }
            }
        )

        assertEquals(PromotionResult.Success, result)
        assertNotNull("Ledger must be persisted BEFORE launching watchdog", ledgerAtWatchdog)
        assertNotNull("Ledger must be persisted BEFORE launching atomic freeze", ledgerAtAtomic)
        assertEquals(listOf("com.google.android.gms", "com.google.android.gsf"), ledgerAtWatchdog!!.packages)
        assertEquals(listOf("com.google.android.gms", "com.google.android.gsf"), ledgerAtAtomic!!.packages)
        assertFalse("Already-disabled com.whatsapp must not be frozen or enabled", executedCommands.any { it.contains("com.whatsapp") })
        assertTrue(executedCommands.indexOfFirst { it.startsWith("( trap '' HUP;") } < executedCommands.indexOfFirst { it.startsWith("sh -c '") })
        assertTrue(executedCommands.contains("pm enable --user 0 com.google.android.gms"))
        assertTrue(executedCommands.contains("pm enable --user 0 com.google.android.gsf"))
        assertNull("Ledger must be cleared after verified restoration", ProvisioningFreezeRecoveryStore.getPendingLedger(context))
    }

    @Test
    fun executePackageFreezeWithShell_preservesPriorStrandedPackagesAndFallsBackToReconnectOnPrimaryTimeout() = runBlocking {
        ProvisioningFreezeRecoveryStore.recordPendingFreeze(context, 0, listOf("com.whatsapp"))
        var reconnectInvoked = false
        var atomicStarted = false

        val result = AdbPackageFreezeManager.executePackageFreezeWithShell(
            context = context,
            userId = 0,
            adminComponent = "com.drmohammed.antiporn/.AdminReceiver",
            accountAuthenticatorMap = mapOf("com.whatsapp" to "com.whatsapp"),
            shellExecutor = { cmd ->
                when {
                    cmd == "dumpsys account" -> shellOk("Account {name=a@gmail.com, type=com.google}\nAccount {name=b, type=com.whatsapp}")
                    cmd == "pm list packages -d --user 0" -> {
                        if (!atomicStarted) shellOk("package:com.whatsapp")
                        else {
                            delay(4_500L) // Exceeds primary finally timeout (3500ms) -> must fall through to reconnectAndHeal
                            shellOk("")
                        }
                    }
                    cmd.startsWith("( trap '' HUP;") -> shellOk("")
                    cmd.startsWith("sh -c '") -> {
                        atomicStarted = true
                        shellOk("Success: Device owner set to package com.drmohammed.antiporn/.AdminReceiver")
                    }
                    else -> shellOk("")
                }
            },
            reconnectAndHeal = { pkgs, _ ->
                reconnectInvoked = true
                assertEquals(listOf("com.google.android.gms", "com.google.android.gsf"), pkgs)
                emptySet()
            }
        )

        assertEquals(PromotionResult.Success, result)
        assertTrue("Primary finally timeout must still invoke reconnectAndHeal", reconnectInvoked)
        val remainingLedger = ProvisioningFreezeRecoveryStore.getPendingLedger(context)
        assertNotNull("Prior unhealed package must not be erased when new target heals", remainingLedger)
        assertEquals(listOf("com.whatsapp"), remainingLedger!!.packages)
    }

    @Test
    fun executePackageFreezeWithShell_zeroFreezeTargets_runsDirectDpmWithUserFallbackWithoutTouchingLedger() = runBlocking {
        val executedCommands = mutableListOf<String>()

        val result = AdbPackageFreezeManager.executePackageFreezeWithShell(
            context = context,
            userId = 0,
            adminComponent = "com.drmohammed.antiporn/.AdminReceiver",
            accountAuthenticatorMap = emptyMap(),
            shellExecutor = { cmd ->
                executedCommands += cmd
                when (cmd) {
                    "dumpsys account --checkin" -> shellOk("com.google,1")
                    "dumpsys account" -> shellOk("Accounts: 1\n  Account {name=a@gmail.com, type=com.google}")
                    "pm list packages -d --user 0" -> shellOk("package:com.google.android.gms\npackage:com.google.android.gsf")
                    "dpm set-device-owner --user 0 com.drmohammed.antiporn/.AdminReceiver" ->
                        shellFail("", "Error: unknown option: --user")
                    "dpm set-device-owner com.drmohammed.antiporn/.AdminReceiver" ->
                        shellOk("Success: Device owner set to package com.drmohammed.antiporn/.AdminReceiver")
                    else -> error("Unexpected shell command: $cmd")
                }
            }
        )

        assertEquals(PromotionResult.Success, result)
        assertFalse(executedCommands.any { it.startsWith("( trap '' HUP;") || it.startsWith("sh -c '") })
        assertNull(ProvisioningFreezeRecoveryStore.getPendingLedger(context))
    }

    @Test
    fun executePackageFreezeWithShell_midFreezeAdbDisconnect_invokesReconnectAndHealAndClearsLedger() = runBlocking {
        var reconnectCalledWith: Pair<List<String>, Int>? = null
        var atomicStarted = false

        val outcome = runCatching {
            AdbPackageFreezeManager.executePackageFreezeWithShell(
                context = context,
                userId = 0,
                adminComponent = "com.drmohammed.antiporn/.AdminReceiver",
                accountAuthenticatorMap = emptyMap(),
                shellExecutor = { cmd ->
                    when {
                        cmd == "dumpsys account --checkin" -> shellOk("com.google,1")
                        cmd == "dumpsys account" -> shellOk("Account {name=a@gmail.com, type=com.google}")
                        cmd == "pm list packages -d --user 0" -> {
                            if (!atomicStarted) shellOk("")
                            else throw IOException("ADB stream closed during finally check")
                        }
                        cmd.startsWith("( trap '' HUP;") -> shellOk("")
                        cmd.startsWith("sh -c '") -> {
                            atomicStarted = true
                            throw IOException("ADB connection dropped mid-freeze")
                        }
                        else -> error("Unexpected shell command: $cmd")
                    }
                },
                reconnectAndHeal = { pkgs, uid ->
                    reconnectCalledWith = pkgs to uid
                    emptySet()
                }
            )
        }

        assertTrue("Mid-freeze IOException should propagate after finally recovery", outcome.exceptionOrNull() is IOException)
        assertEquals(listOf("com.google.android.gms", "com.google.android.gsf") to 0, reconnectCalledWith)
        assertNull(
            "Ledger must be cleared when reconnectAndHeal confirms all packages enabled",
            ProvisioningFreezeRecoveryStore.getPendingLedger(context)
        )
    }

    @Test
    fun executePackageFreezeWithShell_totalDisconnect_preservesStrandedPackagesInLedger() = runBlocking {
        var reconnectAttempted = false
        var atomicStarted = false

        val outcome = runCatching {
            AdbPackageFreezeManager.executePackageFreezeWithShell(
                context = context,
                userId = 0,
                adminComponent = "com.drmohammed.antiporn/.AdminReceiver",
                accountAuthenticatorMap = mapOf("com.whatsapp" to "com.whatsapp"),
                shellExecutor = { cmd ->
                    when {
                        cmd == "dumpsys account --checkin" -> shellOk("com.google,1\ncom.whatsapp,1")
                        cmd == "dumpsys account" -> shellOk(
                            "Account {name=a@gmail.com, type=com.google}\nAccount {name=b, type=com.whatsapp}"
                        )
                        cmd == "pm list packages -d --user 0" -> {
                            if (!atomicStarted) shellOk("")
                            else throw IOException("Primary ADB dead")
                        }
                        cmd.startsWith("( trap '' HUP;") -> shellOk("")
                        cmd.startsWith("sh -c '") -> {
                            atomicStarted = true
                            throw IOException("ADB disconnected during atomic script")
                        }
                        else -> error("Unexpected shell command: $cmd")
                    }
                },
                reconnectAndHeal = { pkgs, _ ->
                    reconnectAttempted = true
                    pkgs.toSet()
                }
            )
        }

        assertTrue(outcome.exceptionOrNull() is IOException)
        assertTrue(reconnectAttempted)
        val ledger = ProvisioningFreezeRecoveryStore.getPendingLedger(context)
        assertNotNull("Stranded packages must remain persisted in ledger on total disconnect", ledger)
        assertEquals(0, ledger!!.userId)
        assertEquals(listOf("com.google.android.gms", "com.google.android.gsf", "com.whatsapp"), ledger.packages)
    }

    @Test
    fun healStrandedPackagesWithShell_handlesEmptyFullPartialAndFailedVerification() = runBlocking {
        var cmdCount = 0
        assertEquals(0, AdbPackageFreezeManager.healStrandedPackagesWithShell(context) { cmdCount++; shellOk("") })
        assertEquals(0, cmdCount)

        ProvisioningFreezeRecoveryStore.recordPendingFreeze(context, 0, listOf("com.google.android.gms", "com.whatsapp"))
        val partialCommands = mutableListOf<String>()
        val partialHealed = AdbPackageFreezeManager.healStrandedPackagesWithShell(context) { cmd ->
            partialCommands += cmd
            when (cmd) {
                "pm enable --user 0 com.google.android.gms",
                "pm enable --user 0 com.whatsapp" -> shellOk("")
                "pm list packages -d --user 0" -> shellOk("package:com.whatsapp")
                else -> error("Unexpected command: $cmd")
            }
        }
        assertEquals(1, partialHealed)
        assertTrue(partialCommands.contains("pm enable --user 0 com.google.android.gms"))
        assertTrue(partialCommands.contains("pm enable --user 0 com.whatsapp"))
        assertEquals(listOf("com.whatsapp"), ProvisioningFreezeRecoveryStore.getPendingLedger(context)?.packages)

        val failedVerifyHealed = AdbPackageFreezeManager.healStrandedPackagesWithShell(context) { cmd ->
            when (cmd) {
                "pm enable --user 0 com.whatsapp" -> shellOk("")
                "pm list packages -d --user 0" -> shellFail("", "SecurityException: denied", 1)
                else -> error("Unexpected command: $cmd")
            }
        }
        assertEquals(0, failedVerifyHealed)
        assertEquals(listOf("com.whatsapp"), ProvisioningFreezeRecoveryStore.getPendingLedger(context)?.packages)

        val fullHealed = AdbPackageFreezeManager.healStrandedPackagesWithShell(context) { cmd ->
            when (cmd) {
                "pm enable --user 0 com.whatsapp" -> shellOk("")
                "pm list packages -d --user 0" -> shellOk("")
                "rm -f /data/local/tmp/antiporn_freeze_0_*.lock" -> shellOk("")
                else -> error("Unexpected command: $cmd")
            }
        }
        assertEquals(1, fullHealed)
        assertNull(ProvisioningFreezeRecoveryStore.getPendingLedger(context))
    }

    @Test
    fun promptState_clearStickyOutcomeWithRefreshFalseSuppressesReprompt() {
        val state = AdbPairingPromptState()
        state.onPairingPortDiscovered(37123)
        state.markStickyOutcome(37123)

        val snap = state.clearStickyOutcome(isExternalPromotionActive = false, refreshPrompt = false)
        assertTrue(snap.cleared)
        assertFalse("clearStickyOutcome(refreshPrompt=false) must not re-post pairing prompt", snap.shouldRefreshPrompt)
    }

    @Test
    fun executeZeroAccountPromotionWithShell_samsungAndroid11RedactedDumpsysWithCheckin_skipsFastPathAndFreezesGoogleGsfAndSamsung() = runBlocking {
        val executedCommands = mutableListOf<String>()
        var atomicExecuted = false
        var verifierDidBypass: Boolean? = null

        val result = AdbPromotionExecutor.executeZeroAccountPromotionWithShell(
            context = context,
            adminComponent = "com.drmohammed.antiporn/.AdminReceiver",
            userId = 0,
            shellExecutor = { cmd ->
                executedCommands += cmd
                when {
                    cmd.startsWith("pm grant ") || cmd.startsWith("appops set ") || cmd.startsWith("dumpsys deviceidle") -> shellOk("")
                    cmd.startsWith("settings put global private_dns_") -> shellOk("")
                    cmd == "settings get global private_dns_specifier" -> shellOk("family.cloudflare-dns.com")
                    cmd == "settings get global private_dns_mode" -> shellOk("hostname")
                    cmd == "dumpsys account --checkin" -> shellOk("com.google,1\ncom.osp.app.signin,1\n")
                    cmd == "dumpsys account" -> shellOk(
                        """
                        User UserInfo{0:Owner:c13}:
                          Accounts: 2
                        """.trimIndent()
                    )
                    cmd == "pm list packages -d --user 0" -> {
                        if (!atomicExecuted) shellOk("")
                        else shellOk("")
                    }
                    cmd.startsWith("( trap '' HUP;") -> shellOk("")
                    cmd.startsWith("sh -c '") -> {
                        atomicExecuted = true
                        shellOk(
                            """
                            ${AdbShellValidation.DPM_BEGIN_MARKER}
                            Success: Device owner set to package com.drmohammed.antiporn/.AdminReceiver
                            ${AdbShellValidation.DPM_END_MARKER}
                            """.trimIndent()
                        )
                    }
                    cmd.startsWith("rm -f ") -> shellOk("")
                    else -> error("Unexpected shell command: $cmd")
                }
            },
            isDeviceOwnerChecker = { false },
            canWriteSecureSettingsChecker = { true },
            hasUsageStatsChecker = { true },
            blockingUsersInspector = { emptyList() },
            sdkVersionProvider = { 30 },
            isXiaomi = false,
            isRealmeOrOppo = false,
            postPromotionVerifier = { _, _, didBypass ->
                verifierDidBypass = didBypass
                PromotionResult.Success
            },
            packageFreezeRunner = { appCtx, comp, uid ->
                AdbPackageFreezeManager.executePackageFreezeWithShell(
                    context = appCtx,
                    userId = uid,
                    adminComponent = comp,
                    accountAuthenticatorMap = emptyMap(),
                    shellExecutor = { cmd ->
                        executedCommands += cmd
                        when {
                            cmd == "dumpsys account --checkin" -> shellOk("com.google,1\ncom.osp.app.signin,1\n")
                            cmd == "dumpsys account" -> shellOk("User UserInfo{0:Owner:c13}:\n  Accounts: 2")
                            cmd == "pm list packages -d --user 0" -> shellOk("")
                            cmd.startsWith("( trap '' HUP;") -> shellOk("")
                            cmd.startsWith("sh -c '") -> {
                                atomicExecuted = true
                                shellOk(
                                    """
                                    ${AdbShellValidation.DPM_BEGIN_MARKER}
                                    Success: Device owner set to package com.drmohammed.antiporn/.AdminReceiver
                                    ${AdbShellValidation.DPM_END_MARKER}
                                    """.trimIndent()
                                )
                            }
                            cmd.startsWith("rm -f ") -> shellOk("")
                            else -> error("Unexpected freeze shell command: $cmd")
                        }
                    }
                )
            }
        )

        assertEquals(PromotionResult.Success, result)
        assertEquals(false, verifierDidBypass)
        assertTrue(
            "ADB promotion must grant GET_USAGE_STATS appop",
            executedCommands.contains("appops set com.drmohammed.antiporn GET_USAGE_STATS allow")
        )
        assertTrue(
            "ADB promotion must grant ACCESS_RESTRICTED_SETTINGS appop",
            executedCommands.contains("appops set com.drmohammed.antiporn ACCESS_RESTRICTED_SETTINGS allow")
        )
        assertFalse(
            "FAST_PATH_ZERO_ACCOUNT_DPM must NOT fire direct dpm when --checkin detects accounts",
            executedCommands.any { it.startsWith("dpm set-device-owner") }
        )
        val atomicScript = executedCommands.firstOrNull { it.startsWith("sh -c '") }
        assertNotNull("Atomic freeze script must execute on SDK 30", atomicScript)
        assertTrue(atomicScript!!.contains("pm disable-user --user 0 com.google.android.gms"))
        assertTrue(atomicScript.contains("pm disable-user --user 0 com.google.android.gsf"))
        assertTrue(atomicScript.contains("pm disable-user --user 0 com.osp.app.signin"))
    }

    @Test
    fun executePackageFreezeWithShell_protectedPackageDisableFailure_returnsAccountsBlockingWithoutStackTraceLeak() = runBlocking {
        val result = AdbPackageFreezeManager.executePackageFreezeWithShell(
            context = context,
            userId = 0,
            adminComponent = "com.drmohammed.antiporn/.AdminReceiver",
            accountAuthenticatorMap = emptyMap(),
            shellExecutor = { cmd ->
                when {
                    cmd == "dumpsys account --checkin" -> shellOk("com.google,1\ncom.osp.app.signin,1\n")
                    cmd == "dumpsys account" -> shellOk("Accounts: 2")
                    cmd == "pm list packages -d --user 0" -> shellOk("")
                    cmd.startsWith("( trap '' HUP;") -> shellOk("")
                    cmd.startsWith("sh -c '") -> shellFail(
                        stdout = """
                            Package com.google.android.gms new state: disabled-user
                            java.lang.SecurityException: Shell cannot disable protected package com.osp.app.signin
                            ${AdbShellValidation.DPM_BEGIN_MARKER}
                            usage: dpm [subcommand] [options]
                            usage: dpm set-active-admin [ --user <USER_ID> | current ] <COMPONENT>
                            java.lang.IllegalStateException: Not allowed to set the device owner because there are already some accounts on the device
                            	at android.os.Parcel.createExceptionOrNull(Parcel.java:2373)
                            	at android.os.Parcel.createException(Parcel.java:2357)
                            	... 12 more
                            ${AdbShellValidation.DPM_END_MARKER}
                        """.trimIndent(),
                        exitCode = 1
                    )
                    cmd.startsWith("rm -f ") -> shellOk("")
                    else -> error("Unexpected shell command: $cmd")
                }
            }
        )

        assertTrue("Expected AccountsBlocking, got $result", result is PromotionResult.AccountsBlocking)
        val blocking = result as PromotionResult.AccountsBlocking
        assertTrue(blocking.accountTypes.contains("com.google"))
        assertTrue(blocking.accountTypes.contains("com.osp.app.signin"))
        assertTrue("accountLabels must be non-empty", blocking.accountLabels.isNotEmpty())
        assertEquals("accounts_blocked_generic", blocking.message)
        assertFalse("Stack trace must not leak into user-facing message", blocking.message.contains("at android.os.Parcel"))
        assertFalse("dpm usage block must not leak into user-facing message", blocking.message.contains("usage: dpm"))
        assertFalse("pm new state line must not leak into user-facing message", blocking.message.contains("new state:"))
    }

    @Test
    fun runDpmSetDeviceOwnerWithUserFallback_retriesOnAdminBeingRemovedAndSucceeds() = runBlocking {
        var attempts = 0
        val output = AdbPromotionExecutor.runDpmSetDeviceOwnerWithUserFallback(
            userId = 0,
            adminComponent = "com.drmohammed.antiporn/.AdminReceiver",
            shellExecutor = { cmd ->
                assertEquals("dpm set-device-owner --user 0 com.drmohammed.antiporn/.AdminReceiver", cmd)
                attempts++
                if (attempts == 1) {
                    shellFail(
                        stderr = "java.lang.IllegalStateException: Trying to set an admin which is being removed",
                        exitCode = 1
                    )
                } else {
                    shellOk("Success: Device owner set to package com.drmohammed.antiporn/.AdminReceiver")
                }
            }
        )

        assertEquals(2, attempts)
        assertTrue(AdbPromotionExecutor.isDpmOwnerSetSuccess(output))
    }

    @Test
    fun executeZeroAccountPromotionWithShell_android14SetupHelperFailure_writesReadOnlyDexRestoresSetupAndFallsBackToFreeze() = runBlocking {
        val executedCommands = mutableListOf<String>()
        var fallbackFreezeCalled = false
        var verifierDidBypass: Boolean? = null

        val result = AdbPromotionExecutor.executeZeroAccountPromotionWithShell(
            context = context,
            adminComponent = "com.drmohammed.antiporn/.AdminReceiver",
            userId = 0,
            shellExecutor = { cmd ->
                executedCommands += cmd
                when {
                    cmd.startsWith("pm grant ") || cmd.startsWith("appops set ") || cmd.startsWith("dumpsys deviceidle") -> shellOk("")
                    cmd.startsWith("settings put global private_dns_") -> shellOk("")
                    cmd == "settings get global private_dns_specifier" -> shellOk("family.cloudflare-dns.com")
                    cmd == "settings get global private_dns_mode" -> shellOk("hostname")
                    cmd == "dumpsys account --checkin" -> shellOk("com.google,1")
                    cmd == "dumpsys account" -> shellOk("Accounts: 1\n  Account {name=a@gmail.com, type=com.google}")
                    cmd.startsWith("echo '") -> shellOk("")
                    cmd == "settings --user 0 put secure user_setup_complete 0" -> shellOk("")
                    cmd == "CLASSPATH=/data/local/tmp/antiporn_helper.dex app_process /system/bin SetupStateHelper 0" ->
                        shellFail("", "ERR_SETUP_HELPER: SecurityException", 1)
                    cmd == "settings --user 0 put secure user_setup_complete 1" -> shellOk("")
                    cmd == "rm -f /data/local/tmp/antiporn_helper.dex" -> shellOk("")
                    else -> error("Unexpected shell command: $cmd")
                }
            },
            isDeviceOwnerChecker = { false },
            canWriteSecureSettingsChecker = { true },
            hasUsageStatsChecker = { true },
            blockingUsersInspector = { emptyList() },
            sdkVersionProvider = { 34 },
            isXiaomi = false,
            isRealmeOrOppo = false,
            postPromotionVerifier = { _, _, didBypass ->
                verifierDidBypass = didBypass
                PromotionResult.Success
            },
            packageFreezeRunner = { _, _, _ ->
                fallbackFreezeCalled = true
                PromotionResult.Success
            }
        )

        assertEquals(PromotionResult.Success, result)
        assertTrue("Fallback packageFreezeRunner must execute when SetupStateHelper fails", fallbackFreezeCalled)
        assertEquals(true, verifierDidBypass)
        assertTrue(
            "ADB promotion must grant GET_USAGE_STATS appop",
            executedCommands.contains("appops set com.drmohammed.antiporn GET_USAGE_STATS allow")
        )
        val rmBeforeWriteIdx = executedCommands.indexOfFirst { it == "rm -f /data/local/tmp/antiporn_helper.dex" }
        val dexWriteIdx = executedCommands.indexOfFirst { it.startsWith("echo '") }
        assertTrue("Stale helper DEX must be removed before writing read-only DEX", rmBeforeWriteIdx in 0 until dexWriteIdx)
        val dexWriteCmd = executedCommands[dexWriteIdx]
        assertTrue("Helper DEX write command must enforce W^X chmod 444", dexWriteCmd.contains("chmod 444 /data/local/tmp/antiporn_helper.dex"))
        assertFalse(
            "dpm set-device-owner must NOT be called by setup bypass when SETUP_STATE_SUCCESS is absent",
            executedCommands.any { it.startsWith("dpm set-device-owner") }
        )
        assertTrue(
            "user_setup_complete must be restored to 1 in finally",
            executedCommands.contains("settings --user 0 put secure user_setup_complete 1")
        )
        assertTrue(
            "Helper DEX must be cleaned up in finally",
            executedCommands.lastIndexOf("rm -f /data/local/tmp/antiporn_helper.dex") > dexWriteIdx
        )
    }

    @Test
    fun executeZeroAccountPromotionWithShell_android14SetupBypassAndFreezeBlocked_returnsAccountsBlockingEndToEnd() = runBlocking {
        val result = AdbPromotionExecutor.executeZeroAccountPromotionWithShell(
            context = context,
            adminComponent = "com.drmohammed.antiporn/.AdminReceiver",
            userId = 0,
            shellExecutor = { cmd ->
                when {
                    cmd.startsWith("pm grant ") || cmd.startsWith("appops set ") || cmd.startsWith("dumpsys deviceidle") -> shellOk("")
                    cmd.startsWith("settings put global private_dns_") -> shellOk("")
                    cmd == "settings get global private_dns_specifier" -> shellOk("family.cloudflare-dns.com")
                    cmd == "settings get global private_dns_mode" -> shellOk("hostname")
                    cmd == "dumpsys account --checkin" -> shellOk("com.google,1\ncom.osp.app.signin,1")
                    cmd == "dumpsys account" -> shellOk("Accounts: 2")
                    cmd == "rm -f /data/local/tmp/antiporn_helper.dex" -> shellOk("")
                    cmd.startsWith("echo '") -> shellOk("")
                    cmd == "settings --user 0 put secure user_setup_complete 0" -> shellOk("")
                    cmd == "CLASSPATH=/data/local/tmp/antiporn_helper.dex app_process /system/bin SetupStateHelper 0" ->
                        shellOk("SETUP_STATE_SUCCESS")
                    cmd.startsWith("dpm set-device-owner") ->
                        shellFail("", "java.lang.IllegalStateException: Not allowed to set the device owner because there are already some accounts on the device")
                    cmd == "settings --user 0 put secure user_setup_complete 1" -> shellOk("")
                    else -> error("Unexpected shell command: $cmd")
                }
            },
            isDeviceOwnerChecker = { false },
            canWriteSecureSettingsChecker = { true },
            hasUsageStatsChecker = { true },
            blockingUsersInspector = { emptyList() },
            sdkVersionProvider = { 34 },
            isXiaomi = false,
            isRealmeOrOppo = false,
            postPromotionVerifier = { _, _, _ -> PromotionResult.Success },
            packageFreezeRunner = { appCtx, comp, uid ->
                AdbPackageFreezeManager.executePackageFreezeWithShell(
                    context = appCtx,
                    userId = uid,
                    adminComponent = comp,
                    accountAuthenticatorMap = emptyMap(),
                    shellExecutor = { cmd ->
                        when {
                            cmd == "dumpsys account --checkin" -> shellOk("com.google,1\ncom.osp.app.signin,1")
                            cmd == "dumpsys account" -> shellOk("Accounts: 2")
                            cmd == "pm list packages -d --user 0" -> shellOk("")
                            cmd.startsWith("( trap '' HUP;") -> shellOk("")
                            cmd.startsWith("sh -c '") -> shellFail(
                                stdout = "${AdbShellValidation.DPM_BEGIN_MARKER}\njava.lang.IllegalStateException: already some accounts on the device\n${AdbShellValidation.DPM_END_MARKER}",
                                exitCode = 1
                            )
                            cmd.startsWith("rm -f ") -> shellOk("")
                            else -> error("Unexpected freeze command: $cmd")
                        }
                    }
                )
            }
        )

        assertTrue("Expected AccountsBlocking end-to-end, got $result", result is PromotionResult.AccountsBlocking)
        val blocking = result as PromotionResult.AccountsBlocking
        assertEquals(listOf("com.google", "com.osp.app.signin"), blocking.accountTypes)
        assertTrue(blocking.accountLabels.any { it.contains("Google") })
        assertTrue(blocking.accountLabels.any { it.contains("Samsung") })
    }

    @Test
    fun shellValidationAndBlockingAccountsPayload_sanitizeExtractAndJsonRoundTrip() {
        val rawDpmWithNoise = """
            Package com.google.android.gms new state: disabled-user
            ${AdbShellValidation.DPM_BEGIN_MARKER}
            usage: dpm [subcommand] [options]
            usage: dpm set-device-owner [ --user <USER_ID> | current ] <COMPONENT>
            java.lang.IllegalStateException: Not allowed to set the device owner because there are already some accounts on the device
            	at android.os.Parcel.createExceptionOrNull(Parcel.java:2373)
            	Caused by: java.lang.RuntimeException: inner cause
            	... 8 more
            ${AdbShellValidation.DPM_END_MARKER}
            Package com.google.android.gms new state: enabled
        """.trimIndent()

        val extracted = AdbShellValidation.extractDelimitedDpmOutput(rawDpmWithNoise)
        assertFalse(extracted.contains("new state:"))
        assertTrue(extracted.contains("Not allowed to set the device owner"))
        assertEquals("plain error", AdbShellValidation.extractDelimitedDpmOutput("  plain error  "))

        val sanitized = AdbShellValidation.sanitizeUserFacingError(rawDpmWithNoise, maxLength = 300)
        assertEquals(
            "java.lang.IllegalStateException: Not allowed to set the device owner because there are already some accounts on the device",
            sanitized
        )
        assertFalse(sanitized.contains("usage: dpm"))
        assertFalse(sanitized.contains("new state:"))
        assertFalse(sanitized.contains("at android.os.Parcel"))
        assertFalse(sanitized.contains("Caused by:"))
        assertFalse(sanitized.contains("... 8 more"))

        val truncated = AdbShellValidation.sanitizeUserFacingError("A very long error message that must be truncated cleanly", maxLength = 20)
        assertTrue(truncated.length <= 20)
        assertTrue(truncated.endsWith("..."))
        assertEquals("", AdbShellValidation.sanitizeUserFacingError("   ", maxLength = 100))

        val payload = BlockingAccountsPayload(
            accountLabels = listOf("Google", "Samsung"),
            accountTypes = listOf("com.google", "com.osp.app.signin"),
            message = "Active accounts on the device are preventing automatic Device Owner activation."
        )
        val roundTripped = BlockingAccountsPayload.fromJson(payload.toJson())
        assertEquals(payload, roundTripped)
        assertNull(BlockingAccountsPayload.fromJson(null))
        assertNull(BlockingAccountsPayload.fromJson("   "))
        assertNull(BlockingAccountsPayload.fromJson("{}"))
        assertNull(BlockingAccountsPayload.fromJson("{not-valid-json"))
    }
}
