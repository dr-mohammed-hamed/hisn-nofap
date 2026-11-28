package com.drmohammed.antiporn.curfew

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.fortress.engine.EffectiveRestrictionResolver
import com.drmohammed.antiporn.fortress.model.EffectiveRestrictionProfile
import timber.log.Timber

/**
 * Resolves packages to suspend during active curfew/fortress windows while safeguarding
 * essential device services (launcher, dialer, SMS, IME, camera, contacts, clock) and
 * combining them with the non-cached user allowances from [EffectiveRestrictionResolver].
 */
object CurfewAppResolver {

    private const val TAG = "AntiPorn_CurfewResolver"
    private val TOKEN_DELIMITER_REGEX = Regex("[\\s_.,;:!?/()\\[\\]\\-]+")

    private data class PackageCacheEntry(
        val packages: Set<String>,
        val cachedAtElapsedMs: Long
    )

    @Volatile
    private var coreCriticalCache: PackageCacheEntry? = null
    private const val CORE_CRITICAL_CACHE_TTL_MS = 60_000L

    @Volatile
    private var essentialImmuneCache: PackageCacheEntry? = null
    private const val ESSENTIAL_IMMUNE_CACHE_TTL_MS = 30_000L

    @VisibleForTesting
    internal var elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() }

    @Synchronized
    fun invalidateCache() {
        coreCriticalCache = null
        essentialImmuneCache = null
    }

    fun isIslamicOrQuranApp(pm: PackageManager, appInfo: ApplicationInfo): Boolean {
        val pkg = appInfo.packageName.lowercase()
        val keywords = listOf(
            "quran", "moshaf", "mushaf", "koran", "furqan", "surah", "tilawat",
            "athkar", "azkar", "athan", "adthan", "islam", "tafsir", "hadith",
            "sunnah", "muslim", "alroqia", "hisn", "zekr", "dhikr", "salat", "namaz"
        )
        if (keywords.any { pkg.contains(it) }) return true
        val label = readAppLabelLower(pm, appInfo)
        val tokens = label.split(TOKEN_DELIMITER_REGEX).filter { it.isNotBlank() }
        val islamicTokens = setOf(
            "قرآن", "قران", "مصحف", "آية", "اية", "أذكار", "اذكار", "تفسير",
            "أذان", "اذان", "صلاة", "quran", "mushaf", "ayah", "athan", "azkar",
            "athkar", "tafsir", "hadith", "islamic", "muslim"
        )
        if (tokens.any { islamicTokens.contains(it) }) return true
        return listOf("حصن المسلم", "القرآن الكريم", "المصحف الشريف", "تفسير القرآن").any { label.contains(it) }
    }

    fun isAudiobookOrPodcastApp(pm: PackageManager, appInfo: ApplicationInfo): Boolean {
        val pkg = appInfo.packageName.lowercase()
        val keywords = listOf(
            "audiobook", "podcast", "podcasts", "antennapod", "castbox",
            "pocketcasts", "storytel", "wajeez", "akhdar", "abjjad", "rufoof", "dhad"
        )
        if (keywords.any { pkg.contains(it) }) return true
        val label = readAppLabelLower(pm, appInfo)
        val tokens = label.split(TOKEN_DELIMITER_REGEX).filter { it.isNotBlank() }
        val audioTokens = setOf("بودكاست", "audiobook", "podcast", "podcasts", "audiobooks")
        if (tokens.any { audioTokens.contains(it) }) return true
        return listOf("كتب صوتية", "كتب صوتيه", "كتاب صوتي").any { label.contains(it) }
    }

    fun isMedicalOrStudyApp(pm: PackageManager, appInfo: ApplicationInfo): Boolean {
        val pkg = appInfo.packageName.lowercase()
        val keywords = listOf(
            "pedidose", "pedi_dose", "pediatric", "medscape", "uptodate",
            "lexicomp", "sanford", "pharma", "clinical", "medical"
        )
        if (keywords.any { pkg.contains(it) }) return true
        val label = readAppLabelLower(pm, appInfo)
        val tokens = label.split(TOKEN_DELIMITER_REGEX).filter { it.isNotBlank() }
        val exactTokens = setOf(
            "جرعات", "أدوية", "ادوية", "طبي", "طبية", "صيدلة",
            "medical", "pharma", "pediatric", "pedidose"
        )
        if (tokens.any { exactTokens.contains(it) }) return true
        return listOf("جرعات الأطفال", "مرجع طبي", "معجم طبي").any { label.contains(it) }
    }

    fun isMediaOrAlternativePlayerApp(pm: PackageManager, appInfo: ApplicationInfo): Boolean {
        val pkg = appInfo.packageName.lowercase()
        val keywords = listOf("newpipe", "puretube", "tubular", "musicolet", "mxtech", "mxplayer")
        if (keywords.any { pkg.contains(it) }) return true
        val label = readAppLabelLower(pm, appInfo)
        val tokens = label.split(TOKEN_DELIMITER_REGEX).filter { it.isNotBlank() }
        if (tokens.any { setOf("newpipe", "puretube", "tubular", "mxplayer").contains(it) }) return true
        return listOf("mx player", "مشغل mx").any { label.contains(it) }
    }

    private fun readAppLabelLower(pm: PackageManager, appInfo: ApplicationInfo): String = try {
        pm.getApplicationLabel(appInfo).toString().lowercase()
    } catch (_: Exception) {
        ""
    }

    /**
     * Resolves and caches ONLY essential device packages (system critical, self, launcher,
     * keyboards, dialer, SMS, camera excluding galleries, contacts, clocks & alarms),
     * minus custom blacklist, known app stores, and curfew search loophole apps.
     */
    fun resolveEssentialImmunePackages(context: Context, forceRefresh: Boolean = false): Set<String> {
        val essentials = getOrComputeRawEssentialPackages(context, forceRefresh)
        val customBlacklist = readCustomBlacklistSafely(context)
        return (essentials - customBlacklist - CurfewConfig.KNOWN_APP_STORES - CurfewConfig.CURFEW_SEARCH_APPS)
    }

    /**
     * Combines cached essential device exemptions with instantaneous (uncached) user allowances
     * from [EffectiveRestrictionResolver.resolve].
     */
    fun resolveImmunePackages(context: Context, forceRefresh: Boolean = false): Set<String> =
        resolveImmunePackages(context, EffectiveRestrictionResolver.resolve(context), forceRefresh)

    /**
     * Overload accepting a precomputed [EffectiveRestrictionProfile] to avoid duplicate policy resolution.
     */
    fun resolveImmunePackages(
        context: Context,
        effectiveProfile: EffectiveRestrictionProfile,
        forceRefresh: Boolean = false
    ): Set<String> {
        val essentials = resolveEssentialImmunePackages(context, forceRefresh)
        val customBlacklist = readCustomBlacklistSafely(context)
        return (essentials + effectiveProfile.effectiveAllowedUserApps) -
            customBlacklist -
            CurfewConfig.KNOWN_APP_STORES -
            CurfewConfig.CURFEW_SEARCH_APPS
    }

    private fun getOrComputeRawEssentialPackages(context: Context, forceRefresh: Boolean): Set<String> {
        val now = elapsedRealtimeProvider()
        val entry = essentialImmuneCache
        if (!forceRefresh && entry != null && (now - entry.cachedAtElapsedMs < ESSENTIAL_IMMUNE_CACHE_TTL_MS)) {
            return entry.packages
        }

        val immune = mutableSetOf<String>()
        val pm = context.packageManager

        immune.addAll(CurfewConfig.SYSTEM_CRITICAL_PACKAGES)
        immune.add(context.packageName)

        // Default Home Launcher
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { immune.add(it) }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error resolving home launcher")
        }

        // Input Method Editors (Keyboards)
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.inputMethodList?.forEach { immune.add(it.packageName) }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error resolving input methods")
        }

        // Default Dialer (Phone)
        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.defaultDialerPackage?.let { immune.add(it) }
            pm.resolveActivity(Intent(Intent.ACTION_DIAL), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { immune.add(it) }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error resolving dialer")
        }

        // Default SMS / Messages
        try {
            Telephony.Sms.getDefaultSmsPackage(context)?.let { immune.add(it) }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error resolving SMS package")
        }

        // Camera Apps (strictly excluding Gallery apps)
        try {
            val cameraActivities = queryIntentActivitiesCompat(pm, Intent(MediaStore.ACTION_IMAGE_CAPTURE))
            for (resolveInfo in cameraActivities) {
                val pkg = resolveInfo.activityInfo.packageName
                if (!CurfewConfig.EXCLUDED_GALLERY_PACKAGES.contains(pkg)) {
                    immune.add(pkg)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error resolving camera packages")
        }

        // Contacts Provider / App
        try {
            val contactsIntent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
            for (resolveInfo in queryIntentActivitiesCompat(pm, contactsIntent)) {
                immune.add(resolveInfo.activityInfo.packageName)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error resolving contacts packages")
        }

        // Clocks & Alarms
        try {
            immune.addAll(CurfewConfig.KNOWN_CLOCK_PACKAGES)
            val clockResolve = resolveActivityCompat(pm, Intent(AlarmClock.ACTION_SHOW_ALARMS))
            clockResolve?.activityInfo?.packageName?.let { immune.add(it) }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error resolving clock packages")
        }

        val result = immune.toSet()
        essentialImmuneCache = PackageCacheEntry(result, elapsedRealtimeProvider())
        return result
    }

    private fun readCustomBlacklistSafely(context: Context): Set<String> = try {
        PolicyRepository.getInstance(context).getCurrentPolicy().customBlacklistPackages
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Error querying customBlacklistPackages")
        emptySet()
    }

    private fun queryIntentActivitiesCompat(pm: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

    private fun resolveActivityCompat(pm: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0)
        }

    /**
     * Determines all installed user applications that must be suspended under [effectiveProfile].
     */
    fun getCurfewTargetPackages(
        context: Context,
        effectiveProfile: EffectiveRestrictionProfile = EffectiveRestrictionResolver.resolve(context)
    ): Set<String> {
        val pm = context.packageManager
        val immunePackages = resolveImmunePackages(context, effectiveProfile)
        val targets = mutableSetOf<String>()

        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            targets.addAll(queryIntentActivitiesCompat(pm, mainIntent).map { it.activityInfo.packageName })
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error querying launchable activities")
        }

        try {
            val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            for (app in installedApps) {
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                if (!isSystem || isUpdatedSystem) {
                    targets.add(app.packageName)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error querying installed applications")
        }

        targets.addAll(CurfewConfig.OEM_APP_STORES)
        targets.addAll(CurfewConfig.CURFEW_SEARCH_APPS)

        val finalTargets = targets
            .filterNot { immunePackages.contains(it) }
            .filterNot { it == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE }
            .filterNot { it == CurfewConfig.GOOGLE_SEARCH_PACKAGE }
            .toSet()
        Timber.tag(TAG).i("Curfew target resolution: ${finalTargets.size} packages targeted")
        return finalTargets
    }

    fun resolveCoreSystemCriticalPackages(context: Context, forceRefresh: Boolean = false): Set<String> {
        val now = elapsedRealtimeProvider()
        val entry = coreCriticalCache
        if (!forceRefresh && entry != null && (now - entry.cachedAtElapsedMs < CORE_CRITICAL_CACHE_TTL_MS)) {
            return entry.packages
        }

        val critical = mutableSetOf<String>()
        val pm = context.packageManager
        critical.add(context.packageName)
        critical.add("android")
        critical.add("com.android.systemui")
        critical.addAll(CurfewConfig.SYSTEM_CRITICAL_PACKAGES)
        critical.addAll(AppPolicyRegistry.getSystemCriticalPackages())
        critical.addAll(AppPolicyRegistry.getImmunePackages())

        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { critical.add(it) }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error resolving home launcher for critical whitelist")
        }

        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.inputMethodList?.forEach { critical.add(it.packageName) }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error resolving input methods for critical whitelist")
        }

        try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.defaultDialerPackage?.let { critical.add(it) }
            pm.resolveActivity(Intent(Intent.ACTION_DIAL), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { critical.add(it) }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error resolving dialer for critical whitelist")
        }

        try {
            Telephony.Sms.getDefaultSmsPackage(context)?.let { critical.add(it) }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error resolving SMS for critical whitelist")
        }

        val result = critical.toSet()
        coreCriticalCache = PackageCacheEntry(result, elapsedRealtimeProvider())
        return result
    }

    fun isSystemCriticalOrImmune(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return true
        if (packageName == context.packageName || packageName == "android" || packageName == "com.android.systemui") return true
        if (AppPolicyRegistry.isImmune(packageName)) return true
        if (AppPolicyRegistry.isAppStore(packageName)) return true
        if (AppPolicyRegistry.getSystemCriticalPackages().contains(packageName)) return true
        if (AppPolicyRegistry.getImmunePackages().contains(packageName)) return true
        return resolveCoreSystemCriticalPackages(context).contains(packageName)
    }
}
