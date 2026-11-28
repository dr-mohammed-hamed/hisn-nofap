package com.drmohammed.antiporn.fortress.engine

import android.content.Context
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduleExt
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.fortress.model.ActiveModesSnapshot
import com.drmohammed.antiporn.fortress.model.EffectiveRestrictionProfile
import com.drmohammed.antiporn.fortress.model.FortressProfile
import java.security.MessageDigest
import java.util.Calendar

/**
 * Resolves the active protection modes and computes the effective restriction profile
 * using Strictest-Wins intersection across Night Curfew, Active Fortress, and Instant Kill.
 */
object EffectiveRestrictionResolver {

    private data class RestrictionLayer(
        val cutInternet: Boolean,
        val allowedApps: Set<String>,
        val vpnBypass: Set<String>
    )

    fun resolve(
        context: Context,
        calendar: Calendar? = null,
        policy: DevicePolicyBundle? = null
    ): EffectiveRestrictionProfile {
        val resolvedPolicy = policy ?: PolicyRepository.getInstance(context).getCurrentPolicy()
        val resolvedCalendar = resolveCalendar(context, calendar, resolvedPolicy, hasExplicitPolicy = policy != null)
        val snapshot = getActiveModesSnapshot(context, resolvedCalendar, resolvedPolicy)

        val activeLayers = mutableListOf<RestrictionLayer>()
        if (snapshot.isNightCurfewActive) {
            val vpnBlackhole = resolvedPolicy.isVpnBlackholeEnabled
            activeLayers.add(
                RestrictionLayer(
                    cutInternet = vpnBlackhole,
                    allowedApps = resolvedPolicy.customCurfewWhitelistPackages,
                    vpnBypass = if (vpnBlackhole) resolvedPolicy.curfewVpnBypassPackages else emptySet()
                )
            )
        }

        snapshot.activeFortress?.let { session ->
            val profile = (resolvedPolicy.fortressesConfig.findById(session.fortressId)
                ?: FortressProfile(
                    id = session.fortressId,
                    name = session.fortressName,
                    durationMinutes = session.durationMinutes,
                    isEmergency = session.isEmergency,
                    isInternetCutEnabled = true,
                    allowedPackages = emptySet(),
                    vpnBypassPackages = emptySet()
                )).normalized()

            activeLayers.add(
                RestrictionLayer(
                    cutInternet = profile.isInternetCutEnabled,
                    allowedApps = profile.allowedPackages,
                    vpnBypass = if (profile.isEmergency || !profile.isInternetCutEnabled) {
                        emptySet()
                    } else {
                        profile.vpnBypassPackages
                    }
                )
            )
        }

        val isBlockActive = activeLayers.isNotEmpty()
        val shouldCutInternet = resolvedPolicy.isInstantInternetKillEnabled || activeLayers.any { it.cutInternet }

        val effectiveAllowedUserApps = if (activeLayers.isEmpty()) {
            emptySet()
        } else {
            var intersection = activeLayers.first().allowedApps
            for (i in 1 until activeLayers.size) {
                intersection = intersection.intersect(activeLayers[i].allowedApps)
            }
            intersection.filterNot { pkg ->
                resolvedPolicy.customBlacklistPackages.contains(pkg) ||
                    AppPolicyRegistry.isRestrictedBrowser(pkg)
            }.toSet()
        }

        val effectiveVpnBypassPackages = when {
            !shouldCutInternet -> setOf(context.packageName)
            resolvedPolicy.isInstantInternetKillEnabled -> setOf(context.packageName)
            else -> {
                val cuttingLayers = activeLayers.filter { it.cutInternet }
                if (cuttingLayers.isEmpty()) {
                    setOf(context.packageName)
                } else {
                    var bypassIntersection = cuttingLayers.first().vpnBypass
                    for (i in 1 until cuttingLayers.size) {
                        bypassIntersection = bypassIntersection.intersect(cuttingLayers[i].vpnBypass)
                    }
                    bypassIntersection.filterNot { pkg ->
                        resolvedPolicy.customBlacklistPackages.contains(pkg) ||
                            AppPolicyRegistry.isRestrictedBrowser(pkg)
                    }.toSet() + context.packageName
                }
            }
        }

        val signature = computeSignature(
            isBlockActive = isBlockActive,
            shouldCutInternet = shouldCutInternet,
            allowedUserApps = effectiveAllowedUserApps,
            vpnBypassPackages = effectiveVpnBypassPackages
        )

        return EffectiveRestrictionProfile(
            isBlockActive = isBlockActive,
            shouldCutInternet = shouldCutInternet,
            effectiveAllowedUserApps = effectiveAllowedUserApps,
            effectiveVpnBypassPackages = effectiveVpnBypassPackages,
            isNightCurfewActive = snapshot.isNightCurfewActive,
            activeFortress = snapshot.activeFortress,
            isInstantInternetKillActive = resolvedPolicy.isInstantInternetKillEnabled,
            signature = signature
        )
    }

    fun getActiveModesSnapshot(
        context: Context,
        calendar: Calendar? = null,
        policy: DevicePolicyBundle? = null
    ): ActiveModesSnapshot {
        val resolvedPolicy = policy ?: PolicyRepository.getInstance(context).getCurrentPolicy()
        val resolvedCalendar = resolveCalendar(context, calendar, resolvedPolicy, hasExplicitPolicy = policy != null)
        val isScheduleWindow = resolvedPolicy.isCurfewEnabled && CurfewEnforcer.isScheduleWindowActive(
            calendar = resolvedCalendar,
            startHour = resolvedPolicy.curfewStartHour,
            startMinute = resolvedPolicy.curfewStartMinute,
            endHour = resolvedPolicy.curfewEndHour,
            endMinute = resolvedPolicy.curfewEndMinute,
            isWeekendCurfewEnabled = resolvedPolicy.isWeekendCurfewEnabled,
            weekendStartHour = resolvedPolicy.weekendCurfewStartHour,
            weekendStartMinute = resolvedPolicy.weekendCurfewStartMinute,
            weekendEndHour = resolvedPolicy.weekendCurfewEndHour,
            weekendEndMinute = resolvedPolicy.weekendCurfewEndMinute
        )
        val isManualTest = resolvedPolicy.isCurfewEnabled && CurfewEnforcer.isManualTestActive(context)
        val isWindowActiveNow = isScheduleWindow || isManualTest
        val isSnoozed = CurfewEnforcer.isSnoozeActive(context)
        val nowWallMs = TrustedTimeProvider.currentTimeMillis(context)
        val isOverridden = CurfewWindowOverrideStore.isWindowOverridden(context, nowWallMs)
        val isNightCurfewActive = isWindowActiveNow && !isSnoozed && !isOverridden
        val windowEndWallMs = if (isScheduleWindow) computeNextCurfewEndWallMs(resolvedCalendar, resolvedPolicy) else 0L
        val activeFortress = FortressSessionController.getActiveSession(context)

        return ActiveModesSnapshot(
            isNightCurfewActive = isNightCurfewActive,
            isNightCurfewWindowActiveNow = isWindowActiveNow,
            isNightCurfewOverriddenForWindow = isOverridden,
            nightCurfewWindowEndWallMs = windowEndWallMs,
            activeFortress = activeFortress,
            isInstantInternetKillEnabled = resolvedPolicy.isInstantInternetKillEnabled
        )
    }

    private fun resolveCalendar(
        context: Context,
        explicitCalendar: Calendar?,
        resolvedPolicy: DevicePolicyBundle,
        hasExplicitPolicy: Boolean
    ): Calendar {
        if (explicitCalendar != null) return explicitCalendar
        if (!hasExplicitPolicy || resolvedPolicy.timeZoneId.isBlank()) {
            return CurfewConfig.getCurfewCalendar(context)
        }
        val tz = java.util.TimeZone.getTimeZone(resolvedPolicy.timeZoneId)
        return Calendar.getInstance(tz).apply {
            timeInMillis = TrustedTimeProvider.currentTimeMillis(context)
        }
    }

    private fun computeNextCurfewEndWallMs(calendar: Calendar, policy: DevicePolicyBundle): Long {
        for (dayOffset in 0..7) {
            val dayBase = (calendar.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            val dayOfWeek = dayBase.get(Calendar.DAY_OF_WEEK)
            val endTimes = CurfewScheduleExt.getCurfewEndTimesForDay(
                dayOfWeek = dayOfWeek,
                startHour = policy.curfewStartHour,
                startMinute = policy.curfewStartMinute,
                endHour = policy.curfewEndHour,
                endMinute = policy.curfewEndMinute,
                isWeekendCurfewEnabled = policy.isWeekendCurfewEnabled,
                weekendStartHour = policy.weekendCurfewStartHour,
                weekendStartMinute = policy.weekendCurfewStartMinute,
                weekendEndHour = policy.weekendCurfewEndHour,
                weekendEndMinute = policy.weekendCurfewEndMinute
            )
            for ((hour, minute) in endTimes) {
                val candidate = (dayBase.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (candidate.timeInMillis > calendar.timeInMillis) {
                    return candidate.timeInMillis
                }
            }
        }
        return 0L
    }

    private fun computeSignature(
        isBlockActive: Boolean,
        shouldCutInternet: Boolean,
        allowedUserApps: Set<String>,
        vpnBypassPackages: Set<String>
    ): String {
        val canonical = buildString {
            append(isBlockActive)
            append('|')
            append(shouldCutInternet)
            append('|')
            append(allowedUserApps.sorted().joinToString(","))
            append('|')
            append(vpnBypassPackages.sorted().joinToString(","))
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            canonical
        }
    }
}
