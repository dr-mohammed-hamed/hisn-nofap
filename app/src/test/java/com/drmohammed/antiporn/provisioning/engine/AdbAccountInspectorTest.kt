package com.drmohammed.antiporn.provisioning.engine

import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import io.github.rhythmcache.adb.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.Locale

class AdbAccountInspectorTest {

    private lateinit var context: FakeProvisioningContext
    private val capturedLogs = mutableListOf<String>()
    private val testTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            capturedLogs += "${tag.orEmpty()}: $message ${t?.message.orEmpty()}"
        }
    }

    @Before
    fun setUp() {
        context = FakeProvisioningContext()
        capturedLogs.clear()
        Timber.plant(testTree)
    }

    @After
    fun tearDown() {
        BlackBoxRecorder.awaitPendingWritesBlocking()
        Timber.uproot(testTree)
        capturedLogs.clear()
    }

    private fun shellOk(stdout: String = "", stderr: String = ""): ShellResult =
        ShellResult(stdout.toByteArray(), stderr.toByteArray(), 0)

    private fun shellFail(stdout: String = "", stderr: String = "", exitCode: Int = 1): ShellResult =
        ShellResult(stdout.toByteArray(), stderr.toByteArray(), exitCode)

    @Test
    fun parseCheckinOutput_handlesFlatUser0MultiUserZeroCountsAndMalformedLines() {
        val flatOutput = """
            com.google,2
            com.osp.app.signin,0
            com.whatsapp,1
            com.google,1
            malformed_line_without_comma
            com.bad,notanumber
            com.extra,1,2
            
        """.trimIndent()

        val flatUser0 = AdbAccountInspector.parseCheckinOutput(flatOutput, userId = 0)
        assertEquals(mapOf("com.google" to 3, "com.whatsapp" to 1), flatUser0)
        assertFalse(flatUser0.containsKey("com.osp.app.signin"))
        assertTrue(AdbAccountInspector.parseCheckinOutput(flatOutput, userId = 150).isEmpty())
        assertTrue(AdbAccountInspector.parseCheckinOutput("   \n  ", userId = 0).isEmpty())

        val multiUserOutput = """
            UserInfo{0:Owner:4c13}
              com.google,1
              com.osp.app.signin,1
              com.zero,0
            UserInfo{150:Secure Folder:10061030}
              com.samsung.knox,2
              com.whatsapp,1
              bad_line
        """.trimIndent()

        val user0Counts = AdbAccountInspector.parseCheckinOutput(multiUserOutput, userId = 0)
        assertEquals(mapOf("com.google" to 1, "com.osp.app.signin" to 1), user0Counts)

        val user150Counts = AdbAccountInspector.parseCheckinOutput(multiUserOutput, userId = 150)
        assertEquals(mapOf("com.samsung.knox" to 2, "com.whatsapp" to 1), user150Counts)

        assertTrue(AdbAccountInspector.parseCheckinOutput(multiUserOutput, userId = 10).isEmpty())
    }

    @Test
    fun parseRegisteredServicesMap_resolvesInlineMultilineStopsAtNextAuthAndRejectsInvalidPackages() {
        val dumpsysServices = """
            RegisteredServicesCache: 5 services
              ServiceInfo: AuthenticatorDescription {type=com.google}, ComponentInfo{com.google.android.gms/.auth.AccountService}, uid 10123
              ServiceInfo: AuthenticatorDescription {type=com.custom.multiline, labelRes=123}
                component=ComponentInfo{com.vendor.auth/.SyncService}
                uid 10200
              ServiceInfo: AuthenticatorDescription {type=com.custom.pkgattr}
                other=ignored
                packageName=com.vendor.pkgattr
              ServiceInfo: AuthenticatorDescription {type=com.orphaned}
              ServiceInfo: AuthenticatorDescription {type=com.next.entry}, ComponentInfo{com.next.pkg/.Auth}
              ServiceInfo: AuthenticatorDescription {type=com.bad.single}, ComponentInfo{singleword/.Auth}
              ServiceInfo: AuthenticatorDescription {type=com.bad.double}, packageName=com..double
              ServiceInfo: AuthenticatorDescription {type=com.too.far}
                line1
                line2
                line3
                packageName=com.too.far.pkg
        """.trimIndent()

        val resolved = AdbAccountInspector.parseRegisteredServicesMap(dumpsysServices)
        assertEquals("com.google.android.gms", resolved["com.google"])
        assertEquals("com.vendor.auth", resolved["com.custom.multiline"])
        assertEquals("com.vendor.pkgattr", resolved["com.custom.pkgattr"])
        assertEquals("com.next.pkg", resolved["com.next.entry"])
        assertFalse("Orphaned authenticator must not steal next authenticator's package", resolved.containsKey("com.orphaned"))
        assertFalse("Single-segment package name must be rejected", resolved.containsKey("com.bad.single"))
        assertFalse("Double-dot package name must be rejected", resolved.containsKey("com.bad.double"))
        assertFalse("Package beyond 3-line lookahead window must not attach", resolved.containsKey("com.too.far"))
    }

    @Test
    fun parseDumpsysAccountSummary_handlesZeroRedactedRelaxedNoSpaceAndMultiUser() {
        val zeroSummary = AdbAccountInspector.parseDumpsysAccountSummary(
            """
            User UserInfo{0:Owner:4c13}:
              Accounts: 0
            """.trimIndent(),
            userId = 0
        )
        assertEquals(0, zeroSummary.accountsHeaderCount)
        assertTrue(zeroSummary.accountTypes.isEmpty())
        assertFalse(zeroSummary.hasAnyAccountLine)

        val redactedSummary = AdbAccountInspector.parseDumpsysAccountSummary(
            """
            User UserInfo{0:Owner:4c13}:
              Accounts: 2
              RegisteredServicesCache: 10 services
            """.trimIndent(),
            userId = 0
        )
        assertEquals(2, redactedSummary.accountsHeaderCount)
        assertTrue(redactedSummary.accountTypes.isEmpty())
        assertFalse(redactedSummary.hasAnyAccountLine)

        val relaxedSummary = AdbAccountInspector.parseDumpsysAccountSummary(
            """
            User UserInfo{0:Owner:4c13}:
              Accounts: 2
                Account{name=user@gmail.com, type=com.google}
                Account{name=user, type=com.whatsapp}
            User UserInfo{150:Secure Folder:10061030}:
              Accounts: 1
                Account{name=knox, type=com.osp.app.signin}
            """.trimIndent(),
            userId = 0
        )
        assertEquals(2, relaxedSummary.accountsHeaderCount)
        assertTrue(relaxedSummary.hasAnyAccountLine)
        assertEquals(setOf("com.google", "com.whatsapp"), relaxedSummary.accountTypes)
        assertEquals(mapOf("com.google" to 1, "com.whatsapp" to 1), relaxedSummary.accountCountsByType)
    }

    @Test
    fun inspectAccountsWithShell_samsungAndroid11RedactedDumpsysWithCheckinDetectsGoogleGsfAndSamsung() = runBlocking {
        val report = AdbAccountInspector.inspectAccountsWithShell(
            context = context,
            userId = 0,
            shellExecutor = { cmd ->
                when (cmd) {
                    "dumpsys account --checkin" -> shellOk("com.google,1\ncom.osp.app.signin,1\n")
                    "dumpsys account" -> shellOk(
                        """
                        User UserInfo{0:Owner:c13}:
                          Accounts: 2
                        """.trimIndent()
                    )
                    else -> error("Unexpected command: $cmd")
                }
            }
        )

        assertEquals(AccountAuditStatus.ACCOUNTS_DETECTED, report.status)
        assertTrue(report.hasAnyAccountEvidence)
        assertEquals(listOf("com.google", "com.osp.app.signin"), report.accountTypes)
        assertTrue(report.targetPackages.contains("com.google.android.gms"))
        assertTrue(report.targetPackages.contains("com.google.android.gsf"))
        assertTrue(report.targetPackages.contains("com.osp.app.signin"))
    }

    @Test
    fun inspectAccountsWithShell_verifiedZeroRequiresBothCleanCheckinAndAccountsZero() = runBlocking {
        val report = AdbAccountInspector.inspectAccountsWithShell(
            context = context,
            userId = 0,
            shellExecutor = { cmd ->
                when (cmd) {
                    "dumpsys account --checkin" -> shellOk("")
                    "dumpsys account" -> shellOk(
                        """
                        User UserInfo{0:Owner:4c13}:
                          Accounts: 0
                        """.trimIndent()
                    )
                    else -> error("Unexpected command: $cmd")
                }
            }
        )

        assertEquals(AccountAuditStatus.VERIFIED_ZERO, report.status)
        assertFalse(report.hasAnyAccountEvidence)
        assertTrue(report.accountTypes.isEmpty())
        assertTrue(report.targetPackages.isEmpty())
    }

    @Test
    fun inspectAccountsWithShell_mixedCheckinFailureWithAccountsZero_neverReturnsVerifiedZero() = runBlocking {
        val report = AdbAccountInspector.inspectAccountsWithShell(
            context = context,
            userId = 0,
            shellExecutor = { cmd ->
                when (cmd) {
                    "dumpsys account --checkin" -> shellFail("", "Permission Denial: can't dump checkin")
                    "dumpsys account" -> shellOk("User UserInfo{0:Owner:4c13}:\n  Accounts: 0")
                    else -> error("Unexpected command: $cmd")
                }
            },
            recordAudit = false
        )

        assertEquals(AccountAuditStatus.UNKNOWN_OR_REDACTED, report.status)
        assertTrue(report.targetPackages.contains("com.google.android.gms"))
        assertTrue(report.targetPackages.contains("com.whatsapp"))
    }

    @Test
    fun inspectAccountsWithShell_redactedAccountsHeaderWithEmptyCheckin_resolvesSafeFallbacks() = runBlocking {
        val report = AdbAccountInspector.inspectAccountsWithShell(
            context = context,
            userId = 0,
            shellExecutor = { cmd ->
                when (cmd) {
                    "dumpsys account --checkin" -> shellOk("")
                    "dumpsys account" -> shellOk("User UserInfo{0:Owner:4c13}:\n  Accounts: 2\n  RegisteredServicesCache: 8 services")
                    else -> error("Unexpected command: $cmd")
                }
            },
            recordAudit = false
        )

        assertEquals(AccountAuditStatus.ACCOUNTS_DETECTED, report.status)
        assertTrue(report.hasAnyAccountEvidence)
        assertTrue(report.targetPackages.contains("com.google.android.gms"))
        assertTrue(report.targetPackages.contains("com.google.android.gsf"))
        assertTrue(report.targetPackages.contains("com.whatsapp"))
        assertTrue(report.targetPackages.contains("org.telegram.messenger"))
    }

    @Test
    fun inspectAccountsWithShell_resolvesCustomTypesViaLocalAndShellAuthenticatorMaps() = runBlocking {
        val report = AdbAccountInspector.inspectAccountsWithShell(
            context = context,
            userId = 0,
            shellExecutor = { cmd ->
                when (cmd) {
                    "dumpsys account --checkin" -> shellOk("com.custom.local,1\ncom.custom.shell,1")
                    "dumpsys account" -> shellOk(
                        """
                        Accounts: 2
                        RegisteredServicesCache: 1 services
                          ServiceInfo: AuthenticatorDescription {type=com.custom.shell}, ComponentInfo{com.vendor.shellauth/.Service}, uid 10100
                        """.trimIndent()
                    )
                    else -> error("Unexpected command: $cmd")
                }
            },
            localAuthenticatorMap = mapOf("com.custom.local" to "com.vendor.localauth"),
            recordAudit = false
        )

        assertEquals(AccountAuditStatus.ACCOUNTS_DETECTED, report.status)
        assertTrue(report.targetPackages.contains("com.vendor.localauth"))
        assertTrue(report.targetPackages.contains("com.vendor.shellauth"))
    }

    @Test
    fun inspectAccountsWithShell_redactedOrBlankDumpsysNeverReturnsVerifiedZeroAndSuppliesSafeFallbacks() = runBlocking {
        val payloads = listOf(
            shellOk("") to shellOk(""),
            shellOk("") to shellOk("RegisteredServicesCache: 12 services"),
            shellFail("", "Permission Denial: can't dump AccountManager") to shellFail("", "SecurityException: denied")
        )

        for ((checkinRes, dumpsysRes) in payloads) {
            val report = AdbAccountInspector.inspectAccountsWithShell(
                context = context,
                userId = 0,
                shellExecutor = { cmd ->
                    when (cmd) {
                        "dumpsys account --checkin" -> checkinRes
                        "dumpsys account" -> dumpsysRes
                        else -> error("Unexpected command: $cmd")
                    }
                }
            )
            assertEquals(AccountAuditStatus.UNKNOWN_OR_REDACTED, report.status)
            assertTrue(report.targetPackages.contains("com.google.android.gms"))
            assertTrue(report.targetPackages.contains("com.google.android.gsf"))
            assertTrue(report.targetPackages.contains("com.whatsapp"))
            assertTrue(report.targetPackages.contains("org.telegram.messenger"))
        }
    }

    @Test
    fun inspectAccountsWithShell_multiOemAndMessagingCoverage() = runBlocking {
        val expectedMappings = listOf(
            "com.xiaomi" to listOf("com.xiaomi.account"),
            "com.heytap.usercenter" to listOf("com.heytap.usercenter"),
            "com.oplus.account" to listOf("com.oplus.account"),
            "com.oneplus.account" to listOf("com.oneplus.account"),
            "com.huawei.hwid" to listOf("com.huawei.hwid"),
            "com.hihonor.id" to listOf("com.hihonor.id"),
            "com.bbk.account" to listOf("com.bbk.account"),
            "com.transsion.account" to listOf("com.transsion.account"),
            "com.whatsapp" to listOf("com.whatsapp"),
            "org.telegram.messenger" to listOf("org.telegram.messenger"),
            "com.microsoft.office.outlook" to listOf("com.microsoft.office.outlook")
        )

        for ((accountType, expectedPackages) in expectedMappings) {
            val report = AdbAccountInspector.inspectAccountsWithShell(
                context = context,
                userId = 0,
                shellExecutor = { cmd ->
                    when (cmd) {
                        "dumpsys account --checkin" -> shellOk("$accountType,1")
                        "dumpsys account" -> shellOk("Accounts: 1")
                        else -> error("Unexpected command: $cmd")
                    }
                },
                recordAudit = false
            )
            assertEquals("Expected ACCOUNTS_DETECTED for $accountType", AccountAuditStatus.ACCOUNTS_DETECTED, report.status)
            for (pkg in expectedPackages) {
                assertTrue("Expected $pkg for account type $accountType, got ${report.targetPackages}", pkg in report.targetPackages)
            }
        }
    }

    @Test
    fun inspectAccountsWithShell_zeroPiiInvariantExcludesEmailsAndUsernamesFromReportAndLogs() = runBlocking {
        val piiEmail = "secret_user@gmail.com"
        val piiPhone = "private_phone_999"

        val report = AdbAccountInspector.inspectAccountsWithShell(
            context = context,
            userId = 0,
            shellExecutor = { cmd ->
                when (cmd) {
                    "dumpsys account --checkin" -> shellOk("com.google,1\ncom.whatsapp,1")
                    "dumpsys account" -> shellOk(
                        """
                        User UserInfo{0:Owner:4c13}:
                          Accounts: 2
                            Account {name=$piiEmail, type=com.google}
                            Account {name=$piiPhone, type=com.whatsapp}
                        """.trimIndent()
                    )
                    else -> error("Unexpected command: $cmd")
                }
            },
            recordAudit = true
        )

        assertEquals(AccountAuditStatus.ACCOUNTS_DETECTED, report.status)
        assertFalse("diagnosticsSummary must never contain PII email", report.diagnosticsSummary.contains(piiEmail))
        assertFalse("diagnosticsSummary must never contain PII phone", report.diagnosticsSummary.contains(piiPhone))
        assertFalse(report.accountTypes.any { it.contains(piiEmail) || it.contains(piiPhone) })
        assertFalse(report.targetPackages.any { it.contains(piiEmail) || it.contains(piiPhone) })
        assertFalse(
            "Timber logs (including BlackBox fallback) must never contain PII",
            capturedLogs.any { it.contains(piiEmail) || it.contains(piiPhone) }
        )
    }

    @Test
    fun resolveFriendlyAccountLabels_supportsArabicEnglishDeduplicationAndNullContext() {
        val types = listOf(
            "com.google",
            "com.google.android.gms.auth.account",
            "com.osp.app.signin",
            "com.xiaomi",
            "com.heytap.usercenter",
            "com.huawei.hwid",
            "com.hihonor.id",
            "com.bbk.account",
            "com.transsion.account",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.facebook.orca",
            "com.facebook.katana",
            "com.microsoft.office.outlook",
            "com.viber.voip",
            "   ",
            "com.example.custom_sync"
        )

        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar"))
            val arabicLabels = AdbAccountInspector.resolveFriendlyAccountLabels(context, types)
            assertEquals(arabicLabels.distinct().size, arabicLabels.size)
            assertEquals(1, arabicLabels.count { it == "جوجل (Google)" })
            assertTrue(arabicLabels.contains("سامسونج (Samsung)"))
            assertTrue(arabicLabels.contains("شاومي (Xiaomi)"))
            assertTrue(arabicLabels.contains("أوبو / ريلمي / ون بلس"))
            assertTrue(arabicLabels.contains("هواوي (Huawei)"))
            assertTrue(arabicLabels.contains("هونر (Honor)"))
            assertTrue(arabicLabels.contains("فيفو (Vivo)"))
            assertTrue(arabicLabels.contains("ترانشن / تكنو / إنفينيكس"))
            assertTrue(arabicLabels.contains("واتساب (WhatsApp)"))
            assertTrue(arabicLabels.contains("واتساب للأعمال (WhatsApp Business)"))
            assertTrue(arabicLabels.contains("تيليجرام (Telegram)"))
            assertTrue(arabicLabels.contains("ماسنجر (Messenger)"))
            assertTrue(arabicLabels.contains("فيسبوك (Facebook)"))
            assertTrue(arabicLabels.contains("مايكروسوفت / أوتلوك (Microsoft)"))
            assertTrue(arabicLabels.contains("فايبر (Viber)"))
            assertTrue(arabicLabels.contains("Custom sync"))

            Locale.setDefault(Locale.ENGLISH)
            val englishLabels = AdbAccountInspector.resolveFriendlyAccountLabels(context, types)
            assertTrue(englishLabels.contains("Google"))
            assertTrue(englishLabels.contains("Samsung"))
            assertTrue(englishLabels.contains("Xiaomi"))
            assertTrue(englishLabels.contains("OPPO / Realme / OnePlus"))
            assertTrue(englishLabels.contains("Huawei"))
            assertTrue(englishLabels.contains("Honor"))
            assertTrue(englishLabels.contains("Vivo"))
            assertTrue(englishLabels.contains("Transsion / Tecno / Infinix"))
            assertTrue(englishLabels.contains("WhatsApp"))
            assertTrue(englishLabels.contains("WhatsApp Business"))
            assertTrue(englishLabels.contains("Telegram"))
            assertTrue(englishLabels.contains("Messenger"))
            assertTrue(englishLabels.contains("Facebook"))
            assertTrue(englishLabels.contains("Microsoft / Outlook"))
            assertTrue(englishLabels.contains("Viber"))
            assertTrue(englishLabels.contains("Custom sync"))

            val nullContextLabels = AdbAccountInspector.resolveFriendlyAccountLabels(null, listOf("com.google", "com.whatsapp"))
            assertEquals(listOf("Google", "WhatsApp"), nullContextLabels)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}
