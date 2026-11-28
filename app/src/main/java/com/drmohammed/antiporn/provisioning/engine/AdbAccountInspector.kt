package com.drmohammed.antiporn.provisioning.engine

import android.accounts.AccountManager
import android.content.Context
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.provisioning.engine.AdbShellValidation.PACKAGE_NAME_REGEX
import com.drmohammed.antiporn.provisioning.engine.AdbShellValidation.combinedOutput
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import io.github.rhythmcache.adb.ShellResult
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.Locale

internal enum class AccountAuditStatus { VERIFIED_ZERO, ACCOUNTS_DETECTED, UNKNOWN_OR_REDACTED }

internal data class AccountAuditReport(
    val status: AccountAuditStatus,
    val userId: Int,
    val accountTypes: List<String>,
    val accountCountsByType: Map<String, Int>,
    val targetPackages: List<String>,
    val hasAnyAccountEvidence: Boolean,
    val diagnosticsSummary: String,
    val packagesByType: Map<String, List<String>> = emptyMap()
)

internal data class DumpsysAccountSummary(
    val accountsHeaderCount: Int?,
    val accountTypes: Set<String>,
    val accountCountsByType: Map<String, Int>,
    val hasAnyAccountLine: Boolean
)

/**
 * Multi-signal ADB account auditor and authenticator-to-package resolver.
 * Never logs or persists PII (account names, emails, or usernames).
 */
internal object AdbAccountInspector {

    private const val TAG = "AntiPorn_AcctInspect"
    private val USER_INFO_REGEX = Regex("UserInfo\\{(\\d+):")
    private val CHECKIN_LINE_REGEX = Regex("^\\s*([a-zA-Z0-9_.-]+)\\s*,\\s*(\\d+)\\s*$")
    private val AUTH_DESC_REGEX = Regex("AuthenticatorDescription\\s*\\{type=([^}]+)\\}")
    private val COMPONENT_INFO_REGEX = Regex("ComponentInfo\\{([a-zA-Z0-9_.]+)/")
    private val PACKAGE_ATTR_REGEX = Regex("packageName=([a-zA-Z0-9_.]+)")
    private val ACCOUNTS_HEADER_REGEX = Regex("\\bAccounts:\\s*(\\d+)")
    private val ACCOUNT_TYPE_REGEX = Regex("Account\\s*\\{[^}]*type=([^},\\s]+)")
    private val ACCOUNT_LINE_START_REGEX = Regex("Account\\s*\\{")
    private val SHELL_ERROR_PREFIX_REGEX = Regex("(^|\\s)Error:", RegexOption.IGNORE_CASE)
    private val SHELL_EXCEPTION_LINE_REGEX = Regex("^\\s*(?:[a-zA-Z0-9_.]+\\.)?[A-Za-z0-9_]*Exception\\b", RegexOption.MULTILINE)

    private val KNOWN_ACCOUNT_TYPE_TO_PACKAGES: Map<String, List<String>> = mapOf(
        "com.google" to listOf("com.google.android.gms", "com.google.android.gsf"),
        "com.google.android.gms.auth.account" to listOf("com.google.android.gms", "com.google.android.gsf"),
        "com.google.android.apps.tachyon" to listOf("com.google.android.apps.tachyon"),
        "com.osp.app.signin" to listOf("com.osp.app.signin"), "com.samsung.account" to listOf("com.osp.app.signin"),
        "com.samsung.android.core.account" to listOf("com.osp.app.signin"),
        "com.samsung.android.email" to listOf("com.samsung.android.email.provider"),
        "com.samsung.android.mobileservice" to listOf("com.samsung.android.mobileservice"),
        "com.xiaomi" to listOf("com.xiaomi.account"),
        "com.xiaomi.account" to listOf("com.xiaomi.account"), "com.heytap.usercenter" to listOf("com.heytap.usercenter"),
        "com.heytap.account" to listOf("com.heytap.usercenter"), "com.oplus.account" to listOf("com.oplus.account"),
        "com.oneplus.account" to listOf("com.oneplus.account"), "com.oppo.account" to listOf("com.heytap.usercenter", "com.oplus.account"),
        "com.coloros.account" to listOf("com.heytap.usercenter", "com.oplus.account"), "com.huawei.hwid" to listOf("com.huawei.hwid"),
        "com.hihonor.id" to listOf("com.hihonor.id"), "com.bbk.account" to listOf("com.bbk.account"),
        "com.vivo.account" to listOf("com.bbk.account"), "com.transsion.account" to listOf("com.transsion.account"),
        "com.palm.account" to listOf("com.palm.account"), "com.whatsapp" to listOf("com.whatsapp"),
        "com.whatsapp.w4b" to listOf("com.whatsapp.w4b"), "org.telegram.messenger" to listOf("org.telegram.messenger"),
        "org.telegram.messenger.web" to listOf("org.telegram.messenger"), "com.facebook.auth.login" to listOf("com.facebook.katana"),
        "com.facebook.katana" to listOf("com.facebook.katana"), "com.facebook.orca" to listOf("com.facebook.orca"),
        "com.facebook.messenger" to listOf("com.facebook.orca"), "www.instagram.com" to listOf("com.instagram.android"),
        "com.truecaller.account" to listOf("com.truecaller"), "com.egyptianbanks.instapay" to listOf("com.egyptianbanks.instapay"),
        "cn.wps.moffice" to listOf("cn.wps.moffice_eng"),
        "com.adobe.creativesdk.foundation.auth.adobeid.dc" to listOf("com.adobe.reader"),
        "com.viber.voip" to listOf("com.viber.voip"), "com.microsoft.office.outlook" to listOf("com.microsoft.office.outlook"),
        "com.microsoft.workaccount" to listOf("com.microsoft.office.outlook"), "com.google.android.gm.exchange" to listOf("com.microsoft.office.outlook")
    )

    fun buildLocalAuthenticatorMap(context: Context?): Map<String, String> {
        val ctx = context?.applicationContext ?: context ?: return emptyMap()
        return try {
            AccountManager.get(ctx)?.authenticatorTypes.orEmpty().mapNotNull { info ->
                val type = info?.type?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val pkg = info.packageName?.trim()?.takeIf { PACKAGE_NAME_REGEX.matches(it) } ?: return@mapNotNull null
                type to pkg
            }.toMap()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed reading local AccountManager authenticators")
            emptyMap()
        }
    }

    fun parseCheckinOutput(output: String, userId: Int): Map<String, Int> {
        if (output.isBlank()) return emptyMap()
        val hasUserHeaders = USER_INFO_REGEX.containsMatchIn(output)
        if (!hasUserHeaders && userId != 0) return emptyMap()
        val counts = linkedMapOf<String, Int>()
        var currentSectionUserId: Int? = if (hasUserHeaders) null else 0
        for (line in output.lineSequence()) {
            if (hasUserHeaders) {
                USER_INFO_REGEX.find(line)?.let { currentSectionUserId = it.groupValues[1].toIntOrNull(); continue }
            }
            if (currentSectionUserId != userId) continue
            val match = CHECKIN_LINE_REGEX.matchEntire(line) ?: continue
            val count = match.groupValues[2].toIntOrNull() ?: 0
            if (count > 0) {
                val type = match.groupValues[1]
                counts[type] = (counts[type] ?: 0) + count
            }
        }
        return counts
    }

    fun parseRegisteredServicesMap(dumpsysOutput: String): Map<String, String> {
        if (dumpsysOutput.isBlank()) return emptyMap()
        val lines = dumpsysOutput.lines()
        val result = linkedMapOf<String, String>()
        for (i in lines.indices) {
            val authMatch = AUTH_DESC_REGEX.find(lines[i]) ?: continue
            val rawType = authMatch.groupValues[1].substringBefore(',').trim()
            if (rawType.isEmpty()) continue
            var resolvedPkg = extractPackageFromLine(lines[i].substring(authMatch.range.last + 1))
            var offset = 1
            while (resolvedPkg == null && offset <= 3 && i + offset < lines.size) {
                val nextLine = lines[i + offset]
                if (AUTH_DESC_REGEX.containsMatchIn(nextLine)) break
                resolvedPkg = extractPackageFromLine(nextLine)
                offset++
            }
            if (resolvedPkg != null && PACKAGE_NAME_REGEX.matches(resolvedPkg)) result[rawType] = resolvedPkg
        }
        return result
    }

    private fun extractPackageFromLine(line: String): String? =
        COMPONENT_INFO_REGEX.find(line)?.groupValues?.get(1) ?: PACKAGE_ATTR_REGEX.find(line)?.groupValues?.get(1)

    fun parseDumpsysAccountSummary(output: String, userId: Int): DumpsysAccountSummary {
        if (output.isBlank()) return DumpsysAccountSummary(null, emptySet(), emptyMap(), hasAnyAccountLine = false)
        val hasUserHeaders = USER_INFO_REGEX.containsMatchIn(output)
        var currentUserId: Int? = if (hasUserHeaders) null else userId
        var headerCount: Int? = null
        val types = linkedSetOf<String>()
        val countsByType = linkedMapOf<String, Int>()
        var hasAnyAccountLine = false
        for (line in output.lineSequence()) {
            if (hasUserHeaders) {
                USER_INFO_REGEX.find(line)?.let { currentUserId = it.groupValues[1].toIntOrNull(); continue }
            }
            if (currentUserId != userId) continue
            if (headerCount == null) headerCount = ACCOUNTS_HEADER_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()
            if (ACCOUNT_LINE_START_REGEX.containsMatchIn(line)) hasAnyAccountLine = true
            for (m in ACCOUNT_TYPE_REGEX.findAll(line)) {
                val type = m.groupValues[1].trim()
                if (type.isNotEmpty()) {
                    types.add(type)
                    countsByType[type] = (countsByType[type] ?: 0) + 1
                }
            }
        }
        return DumpsysAccountSummary(headerCount, types, countsByType, hasAnyAccountLine)
    }

    suspend fun inspectAccountsWithShell(
        context: Context?,
        userId: Int,
        shellExecutor: suspend (String) -> ShellResult,
        localAuthenticatorMap: Map<String, String> = emptyMap(),
        recordAudit: Boolean = true
    ): AccountAuditReport {
        val appContext = context?.applicationContext ?: context
        val checkinResult = safeShell("dumpsys account --checkin", shellExecutor)
        val validCheckin = checkinResult?.takeIf { it.isSuccess && !hasShellError(it.combinedOutput()) }
        val checkinOk = validCheckin != null
        val checkinCounts = validCheckin?.let { parseCheckinOutput(it.stdoutText, userId) } ?: emptyMap()
        val checkinHasAccountLine = checkinResult != null && ACCOUNT_LINE_START_REGEX.containsMatchIn(checkinResult.stdoutText)

        val dumpsysResult = safeShell("dumpsys account", shellExecutor)
        val validDumpsys = dumpsysResult?.takeIf { isValidDumpsysAccountResult(it) }
        val dumpsysOk = validDumpsys != null
        val dumpsysSummary = validDumpsys?.let { parseDumpsysAccountSummary(it.stdoutText, userId) }
            ?: DumpsysAccountSummary(null, emptySet(), emptyMap(), hasAnyAccountLine = false)
        val shellAuthenticatorMap = validDumpsys?.let { parseRegisteredServicesMap(it.stdoutText) } ?: emptyMap()

        val mergedTypes = (checkinCounts.keys + dumpsysSummary.accountTypes).toList()
        val mergedCounts = linkedMapOf<String, Int>().apply {
            putAll(checkinCounts)
            dumpsysSummary.accountCountsByType.forEach { (type, count) -> if (!containsKey(type)) put(type, count) }
        }
        val hasExplicitZeroCount = dumpsysOk && dumpsysSummary.accountsHeaderCount == 0 &&
            !dumpsysSummary.hasAnyAccountLine && checkinOk && checkinCounts.isEmpty() && !checkinHasAccountLine

        val status = when {
            mergedTypes.isNotEmpty() || (dumpsysSummary.accountsHeaderCount != null && dumpsysSummary.accountsHeaderCount > 0) ->
                AccountAuditStatus.ACCOUNTS_DETECTED
            hasExplicitZeroCount -> AccountAuditStatus.VERIFIED_ZERO
            else -> AccountAuditStatus.UNKNOWN_OR_REDACTED
        }
        val (targetPackages, packagesByType, unmappedTypes) = resolveTargetPackagesWithDetails(
            status, mergedTypes, dumpsysSummary.accountsHeaderCount, shellAuthenticatorMap, localAuthenticatorMap
        )
        val hasEvidence = mergedTypes.isNotEmpty() || (dumpsysSummary.accountsHeaderCount ?: 0) > 0 ||
            dumpsysSummary.hasAnyAccountLine || checkinHasAccountLine
        val summary = "status=${status.name}, userId=$userId, headerCount=${dumpsysSummary.accountsHeaderCount}, " +
            "dumpsysOk=$dumpsysOk, shellAuth=${shellAuthenticatorMap.size}, localAuth=${localAuthenticatorMap.size}, " +
            "unmapped=${unmappedTypes.size}, types=${mergedTypes.sorted().joinToString(",")}, targetPkgs=${targetPackages.size}"

        if (recordAudit && appContext != null) {
            BlackBoxRecorder.recordPairing(appContext, "ADB_ACCOUNT_AUDIT", isSuccess = status != AccountAuditStatus.UNKNOWN_OR_REDACTED, details = summary)
        }
        return AccountAuditReport(status, userId, mergedTypes, mergedCounts, targetPackages, hasEvidence, summary, packagesByType)
    }

    private suspend fun safeShell(cmd: String, shellExecutor: suspend (String) -> ShellResult): ShellResult? = try {
        shellExecutor(cmd)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Timber.tag(TAG).w(e, "Shell command failed during account audit: $cmd"); null
    }

    /**
     * Validates `dumpsys account` output without false-positive rejection when `dumpDebugTable`
     * or `Active Sessions` inside a healthy dump contains historical `Error:` / `*Exception` lines.
     */
    internal fun isValidDumpsysAccountResult(result: ShellResult): Boolean {
        if (!result.isSuccess) return false
        if (hasShellError(result.stderrText)) return false
        val stdout = result.stdoutText
        val hasStructure = USER_INFO_REGEX.containsMatchIn(stdout) ||
            ACCOUNTS_HEADER_REGEX.containsMatchIn(stdout) ||
            AUTH_DESC_REGEX.containsMatchIn(stdout) ||
            stdout.contains("RegisteredServicesCache")
        if (!hasStructure) return !hasShellError(stdout)
        val leadingLines = stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString("\n")
        return !hasShellError(leadingLines)
    }

    private fun hasShellError(combined: String): Boolean =
        combined.contains("Permission Denial", ignoreCase = true) || combined.contains("SecurityException", ignoreCase = true) ||
            combined.contains("Unknown command", ignoreCase = true) || combined.contains("Can't find service", ignoreCase = true) ||
            SHELL_ERROR_PREFIX_REGEX.containsMatchIn(combined) || SHELL_EXCEPTION_LINE_REGEX.containsMatchIn(combined)

    private fun resolveTargetPackagesWithDetails(
        status: AccountAuditStatus,
        accountTypes: List<String>,
        accountsHeaderCount: Int?,
        shellAuthMap: Map<String, String>,
        localAuthMap: Map<String, String>
    ): Triple<List<String>, Map<String, List<String>>, List<String>> {
        val packages = linkedSetOf<String>()
        val packagesByType = linkedMapOf<String, List<String>>()
        val unmapped = mutableListOf<String>()
        var hasGoogle = false
        for (type in accountTypes) {
            if (type.contains("google", ignoreCase = true)) hasGoogle = true
            val resolvedForType = linkedSetOf<String>()
            shellAuthMap[type]?.let { resolvedForType.add(it) }
            localAuthMap[type]?.let { resolvedForType.add(it) }
            KNOWN_ACCOUNT_TYPE_TO_PACKAGES[type.lowercase(Locale.ROOT)]?.let { resolvedForType.addAll(it) }
            if (resolvedForType.isEmpty()) resolvedForType.addAll(heuristicPackagesForType(type))
            val validForType = resolvedForType.filter { PACKAGE_NAME_REGEX.matches(it) }
            if (validForType.isEmpty()) {
                unmapped.add(type)
            } else {
                packagesByType[type] = validForType
                packages.addAll(validForType)
            }
        }
        if (hasGoogle || "com.google.android.gms" in packages) {
            packages.add("com.google.android.gms")
            packages.add("com.google.android.gsf")
        }
        if (status != AccountAuditStatus.VERIFIED_ZERO && packages.isEmpty()) {
            packages.addAll(listOf("com.google.android.gms", "com.google.android.gsf"))
            packages.addAll(oemFallbackAccountPackages())
            if (status == AccountAuditStatus.UNKNOWN_OR_REDACTED || ((accountsHeaderCount ?: 0) > 0 && accountTypes.isEmpty())) {
                packages.addAll(listOf("com.whatsapp", "org.telegram.messenger"))
            }
        }
        return Triple(packages.filter { PACKAGE_NAME_REGEX.matches(it) }, packagesByType, unmapped)
    }

    private fun heuristicPackagesForType(type: String): List<String> {
        val lower = type.lowercase(Locale.ROOT)
        return when {
            lower.contains("google") -> listOf("com.google.android.gms", "com.google.android.gsf")
            lower.contains("samsung") || lower.contains("osp.app.signin") -> listOf("com.osp.app.signin")
            lower.contains("xiaomi") || lower.contains("miui") -> listOf("com.xiaomi.account")
            lower.contains("heytap") || lower.contains("oplus") || lower.contains("oppo") ||
                lower.contains("realme") || lower.contains("coloros") -> listOf("com.heytap.usercenter", "com.oplus.account")
            lower.contains("oneplus") -> listOf("com.oneplus.account")
            lower.contains("huawei") -> listOf("com.huawei.hwid")
            lower.contains("hihonor") || lower.contains("honor") -> listOf("com.hihonor.id")
            lower.contains("bbk") || lower.contains("vivo") -> listOf("com.bbk.account")
            lower.contains("transsion") -> listOf("com.transsion.account")
            lower.contains("palm.account") -> listOf("com.palm.account")
            lower.contains("whatsapp.w4b") -> listOf("com.whatsapp.w4b")
            lower.contains("whatsapp") -> listOf("com.whatsapp")
            lower.contains("telegram") -> listOf("org.telegram.messenger")
            lower.contains("facebook.orca") || lower.contains("messenger") -> listOf("com.facebook.orca")
            lower.contains("facebook") -> listOf("com.facebook.katana")
            lower.contains("microsoft") || lower.contains("outlook") || lower.contains("exchange") -> listOf("com.microsoft.office.outlook")
            lower.contains("viber") -> listOf("com.viber.voip")
            else -> emptyList()
        }
    }

    private fun oemFallbackAccountPackages(): List<String> {
        val m = OemProvisioningHelper.manufacturer
        return buildList {
            if (OemProvisioningHelper.isSamsung()) add("com.osp.app.signin")
            if (OemProvisioningHelper.isXiaomi()) add("com.xiaomi.account")
            if (OemProvisioningHelper.isRealmeOrOppo()) addAll(listOf("com.heytap.usercenter", "com.oplus.account", "com.oneplus.account"))
            if (m.contains("huawei")) add("com.huawei.hwid")
            if (m.contains("honor")) add("com.hihonor.id")
            if (m.contains("vivo") || m.contains("iqoo")) add("com.bbk.account")
            if (m.contains("transsion") || m.contains("tecno") || m.contains("infinix") || m.contains("itel")) {
                addAll(listOf("com.transsion.account", "com.palm.account"))
            }
        }
    }

    fun resolveFriendlyAccountLabels(context: Context?, accountTypes: Collection<String>): List<String> {
        fun label(resId: Int, fallback: String): String = context?.safeGetString(resId) ?: fallback
        return accountTypes.mapNotNull { raw ->
            val type = raw.trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val l = type.lowercase(Locale.ROOT)
            when {
                l == "com.google" || l.contains("google") ->
                    label(R.string.provisioning_account_label_google, "Google")
                l == "com.osp.app.signin" || l.contains("samsung") ->
                    label(R.string.provisioning_account_label_samsung, "Samsung")
                l.contains("xiaomi") || l.contains("miui") ->
                    label(R.string.provisioning_account_label_xiaomi, "Xiaomi")
                l.contains("heytap") || l.contains("oplus") || l.contains("oppo") || l.contains("realme") || l.contains("oneplus") ->
                    label(R.string.provisioning_account_label_oppo_group, "OPPO / Realme / OnePlus")
                l.contains("huawei") ->
                    label(R.string.provisioning_account_label_huawei, "Huawei")
                l.contains("hihonor") || l.contains("honor") ->
                    label(R.string.provisioning_account_label_honor, "Honor")
                l.contains("bbk") || l.contains("vivo") ->
                    label(R.string.provisioning_account_label_vivo, "Vivo")
                l.contains("transsion") || l.contains("palm.account") ->
                    label(R.string.provisioning_account_label_transsion, "Transsion / Tecno / Infinix")
                l.contains("whatsapp.w4b") ->
                    label(R.string.provisioning_account_label_whatsapp_business, "WhatsApp Business")
                l.contains("whatsapp") ->
                    label(R.string.provisioning_account_label_whatsapp, "WhatsApp")
                l.contains("telegram") ->
                    label(R.string.provisioning_account_label_telegram, "Telegram")
                l.contains("facebook.orca") || l.contains("messenger") ->
                    label(R.string.provisioning_account_label_messenger, "Messenger")
                l.contains("facebook") ->
                    label(R.string.provisioning_account_label_facebook, "Facebook")
                l.contains("microsoft") || l.contains("outlook") || l.contains("exchange") ->
                    label(R.string.provisioning_account_label_microsoft, "Microsoft / Outlook")
                l.contains("viber") ->
                    label(R.string.provisioning_account_label_viber, "Viber")
                l.contains("instagram") -> "Instagram"
                l.contains("truecaller") -> "Truecaller"
                l.contains("instapay") -> "InstaPay"
                l.contains("yandex") -> "Yandex"
                l.contains("adobe") -> "Adobe"
                l.contains("wps") -> "WPS Office"
                else -> type.substringAfterLast('.').replace('_', ' ').replace('-', ' ').trim()
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }.ifBlank { type }
            }
        }.distinct()
    }
}
