package com.drmohammed.antiporn.curfew

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.fortress.engine.FortressSessionController
import timber.log.Timber
import java.util.Calendar

/**
 * Schedules exact, Doze-exempt alarms via AlarmManager for curfew start/end and fortress end times,
 * dynamically supporting distinct weekday and weekend schedules.
 */
object CurfewScheduler {

    private const val TAG = "AntiPorn_CurfewScheduler"
    const val ACTION_FORTRESS_END = "com.drmohammed.antiporn.ACTION_FORTRESS_END"
    internal const val LEGACY_ACTION_NAP_END = "com.drmohammed.antiporn.ACTION_NAP_END"
    private const val REQUEST_CODE_PRE_WARNING = 3000
    private const val REQUEST_CODE_START = 3001
    private const val REQUEST_CODE_END = 3002
    const val REQUEST_CODE_FORTRESS_END_RTC = 3003
    const val REQUEST_CODE_FORTRESS_END_ELAPSED = 3004

    /**
     * Returns the curfew start hour and minute for a specific day of the week,
     * accounting for weekend curfew schedules when enabled.
     */
    fun getCurfewStartTimeForDay(context: Context, dayOfWeek: Int): Pair<Int, Int> {
        return if (CurfewConfig.isWeekendCurfewEnabled(context) &&
            (dayOfWeek == Calendar.THURSDAY || dayOfWeek == Calendar.FRIDAY || dayOfWeek == Calendar.SATURDAY)
        ) {
            CurfewConfig.getWeekendCurfewStartHour(context) to CurfewConfig.getWeekendCurfewStartMinute(context)
        } else {
            CurfewConfig.getCurfewStartHour(context) to CurfewConfig.getCurfewStartMinute(context)
        }
    }

    /**
     * Returns the curfew end hour and minute for a specific day of the week,
     * accounting for weekend curfew schedules when enabled.
     */
    fun getCurfewEndTimeForDay(context: Context, dayOfWeek: Int): Pair<Int, Int> {
        if (!CurfewConfig.isWeekendCurfewEnabled(context)) {
            return CurfewConfig.getCurfewEndHour(context) to CurfewConfig.getCurfewEndMinute(context)
        }
        if (dayOfWeek == Calendar.FRIDAY || dayOfWeek == Calendar.SATURDAY) {
            return CurfewConfig.getWeekendCurfewEndHour(context) to CurfewConfig.getWeekendCurfewEndMinute(context)
        }
        if (dayOfWeek == Calendar.THURSDAY) {
            val weekdayStartMin = CurfewConfig.getCurfewStartHour(context) * 60 + CurfewConfig.getCurfewStartMinute(context)
            val weekdayEndH = CurfewConfig.getCurfewEndHour(context)
            val weekdayEndM = CurfewConfig.getCurfewEndMinute(context)
            val weekdayEndMin = weekdayEndH * 60 + weekdayEndM
            val weekendStartMin = CurfewConfig.getWeekendCurfewStartHour(context) * 60 + CurfewConfig.getWeekendCurfewStartMinute(context)
            val weekendEndH = CurfewConfig.getWeekendCurfewEndHour(context)
            val weekendEndM = CurfewConfig.getWeekendCurfewEndMinute(context)
            val weekendEndMin = weekendEndH * 60 + weekendEndM
            if (weekdayStartMin <= weekdayEndMin && weekendStartMin < weekendEndMin) {
                return weekendEndH to weekendEndM
            }
        }
        return CurfewConfig.getCurfewEndHour(context) to CurfewConfig.getCurfewEndMinute(context)
    }

    /**
     * Calculates the next calendar occurrence for curfew start, dynamically evaluating
     * day-specific and weekend-specific schedules across upcoming days.
     */
    fun getNextCurfewStart(context: Context, now: Calendar = CurfewConfig.getCurfewCalendar(context)): Calendar {
        for (dayOffset in 0..7) {
            val candidate = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
                val dayOfWeek = get(Calendar.DAY_OF_WEEK)
                val (hour, minute) = getCurfewStartTimeForDay(context, dayOfWeek)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (candidate.timeInMillis > now.timeInMillis) {
                return candidate
            }
        }
        return getNextOccurrence(CurfewConfig.getCurfewStartHour(context), CurfewConfig.getCurfewStartMinute(context), now)
    }

    /**
     * Calculates the next calendar occurrence for curfew end, dynamically evaluating
     * day-specific and weekend-specific schedules across upcoming days.
     */
    fun getNextCurfewEnd(context: Context, now: Calendar = CurfewConfig.getCurfewCalendar(context)): Calendar {
        val startH = CurfewConfig.getCurfewStartHour(context)
        val startM = CurfewConfig.getCurfewStartMinute(context)
        val endH = CurfewConfig.getCurfewEndHour(context)
        val endM = CurfewConfig.getCurfewEndMinute(context)
        val isWeekend = CurfewConfig.isWeekendCurfewEnabled(context)
        val weekendStartH = CurfewConfig.getWeekendCurfewStartHour(context)
        val weekendStartM = CurfewConfig.getWeekendCurfewStartMinute(context)
        val weekendEndH = CurfewConfig.getWeekendCurfewEndHour(context)
        val weekendEndM = CurfewConfig.getWeekendCurfewEndMinute(context)

        for (dayOffset in 0..7) {
            val dayBase = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            val dayOfWeek = dayBase.get(Calendar.DAY_OF_WEEK)
            val dayEndTimes = CurfewScheduleExt.getCurfewEndTimesForDay(
                dayOfWeek = dayOfWeek,
                startHour = startH,
                startMinute = startM,
                endHour = endH,
                endMinute = endM,
                isWeekendCurfewEnabled = isWeekend,
                weekendStartHour = weekendStartH,
                weekendStartMinute = weekendStartM,
                weekendEndHour = weekendEndH,
                weekendEndMinute = weekendEndM
            )
            for ((hour, minute) in dayEndTimes) {
                val candidate = (dayBase.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (candidate.timeInMillis > now.timeInMillis) {
                    return candidate
                }
            }
        }
        return getNextOccurrence(endH, endM, now)
    }

    /**
     * Calculates next calendar occurrence for a given hour and minute.
     * If the time today has already passed, schedules for tomorrow.
     */
    fun getNextOccurrence(hour: Int, minute: Int, now: Calendar = CurfewConfig.getCurfewCalendar()): Calendar {
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target
    }

    private fun createCurfewIntent(context: Context, action: String): Intent =
        Intent(context, CurfewAlarmReceiver::class.java).apply {
            this.action = action
        }

    private fun buildCurfewPendingIntent(context: Context, requestCode: Int, action: String): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, createCurfewIntent(context, action), flags)
    }

    /**
     * Cancels all scheduled night curfew alarms (pre-warning, start, and end),
     * leaving nap and snooze alarms untouched.
     */
    fun cancelCurfewAlarms(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            val curfewAlarms = listOf(
                REQUEST_CODE_PRE_WARNING to CurfewAlarmReceiver.ACTION_CURFEW_PRE_WARNING,
                REQUEST_CODE_START to CurfewAlarmReceiver.ACTION_CURFEW_START,
                REQUEST_CODE_END to CurfewAlarmReceiver.ACTION_CURFEW_END
            )
            var cancelledCount = 0
            for ((requestCode, action) in curfewAlarms) {
                val pi = PendingIntent.getBroadcast(context, requestCode, createCurfewIntent(context, action), flags)
                if (pi != null) {
                    alarmManager.cancel(pi)
                    pi.cancel()
                    cancelledCount++
                }
            }
            Timber.tag(TAG).i("Cancelled $cancelledCount night curfew alarm(s)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to cancel curfew alarms")
        }
    }

    /**
     * Schedules the next exact alarms for Curfew Start and Curfew End,
     * properly accounting for weekday vs weekend timings.
     */
    fun scheduleNextAlarms(context: Context) {
        if (!CurfewConfig.isCurfewEnabled(context)) {
            Timber.tag(TAG).i("Night curfew is disabled in policy; cancelling curfew alarms and skipping scheduling")
            cancelCurfewAlarms(context)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            Timber.tag(TAG).e("AlarmManager not available")
            return
        }

        val now = CurfewConfig.getCurfewCalendar(context)
        val nextStart = getNextCurfewStart(context, now)
        val nextEnd = getNextCurfewEnd(context, now)

        val startPendingIntent = buildCurfewPendingIntent(context, REQUEST_CODE_START, CurfewAlarmReceiver.ACTION_CURFEW_START)
        val endPendingIntent = buildCurfewPendingIntent(context, REQUEST_CODE_END, CurfewAlarmReceiver.ACTION_CURFEW_END)

        try {
            val startElapsed = TrustedTimeProvider.computeElapsedTrigger(nextStart.timeInMillis, context)
            val endElapsed = TrustedTimeProvider.computeElapsedTrigger(nextEnd.timeInMillis, context)
            setExactCompat(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, startElapsed, startPendingIntent)
            setExactCompat(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, endElapsed, endPendingIntent)

            // Pre-Curfew 15-Minute Warning Alarm (Strictly guarded against retroactive triggers)
            val nowMs = TrustedTimeProvider.currentTimeMillis(context)
            val preWarningMs = nextStart.timeInMillis - 15 * 60 * 1000L
            val preWarningPendingIntent = buildCurfewPendingIntent(
                context,
                REQUEST_CODE_PRE_WARNING,
                CurfewAlarmReceiver.ACTION_CURFEW_PRE_WARNING
            )
            if (preWarningMs > nowMs) {
                val preElapsed = TrustedTimeProvider.computeElapsedTrigger(preWarningMs, context)
                setExactCompat(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, preElapsed, preWarningPendingIntent)
                Timber.tag(TAG).i("Scheduled pre-curfew 15-minute warning alarm at $preWarningMs (elapsed=$preElapsed)")
            } else {
                alarmManager.cancel(preWarningPendingIntent)
                preWarningPendingIntent.cancel()
                Timber.tag(TAG).d("Cancelled/skipped stale pre-curfew warning alarm because remaining time is less than 15 minutes")
            }

            Timber.tag(TAG).i("Scheduled exact curfew alarms via ELAPSED_REALTIME: Start at ${nextStart.time} (elapsed=$startElapsed), End at ${nextEnd.time} (elapsed=$endElapsed)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to schedule exact alarms via AlarmManager")
        }
    }

    private fun buildFortressEndPendingIntent(
        context: Context,
        requestCode: Int = REQUEST_CODE_FORTRESS_END_ELAPSED,
        action: String = ACTION_FORTRESS_END
    ): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val fortressIntent = Intent(context, CurfewAlarmReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(context, requestCode, fortressIntent, flags)
    }

    private fun setExactCompat(alarmManager: AlarmManager, type: Int, triggerMs: Long, pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(type, triggerMs, pi)
                } else {
                    alarmManager.setAndAllowWhileIdle(type, triggerMs, pi)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(type, triggerMs, pi)
            }
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "Exact alarm permission restricted; falling back to inexact alarm")
            alarmManager.setAndAllowWhileIdle(type, triggerMs, pi)
        }
    }

    /**
     * Schedules the fortress-end alarm via ELAPSED_REALTIME_WAKEUP computed from monotonic hardware elapsed time.
     */
    fun scheduleFortressEndAlarm(context: Context, triggerWallMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            Timber.tag(TAG).e("AlarmManager not available for fortress end")
            return
        }
        try {
            val remaining = FortressSessionController.remainingMs(context)
            if (remaining <= 0L) {
                cancelFortressEndAlarm(context)
                return
            }
            val piElapsed = buildFortressEndPendingIntent(context, REQUEST_CODE_FORTRESS_END_ELAPSED)
            val elapsedTrigger = SystemClock.elapsedRealtime() + remaining
            setExactCompat(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, piElapsed)
            Timber.tag(TAG).i("Scheduled fortress end alarm via ELAPSED_REALTIME: wall=%d remainingMs=%d elapsedTrigger=%d", triggerWallMs, remaining, elapsedTrigger)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to schedule fortress end alarms")
        }
    }

    /**
     * Cancels fortress-end alarms (both current and legacy in-flight Nap alarm slots).
     */
    fun cancelFortressEndAlarm(context: Context) {
        if (FortressSessionController.isFortressActive(context)) {
            Timber.tag(TAG).w("Skipped cancelFortressEndAlarm: an active Fortress session exists")
            return
        }
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val slots = listOf(
                REQUEST_CODE_FORTRESS_END_ELAPSED to ACTION_FORTRESS_END,
                REQUEST_CODE_FORTRESS_END_RTC to ACTION_FORTRESS_END,
                REQUEST_CODE_FORTRESS_END_ELAPSED to LEGACY_ACTION_NAP_END,
                REQUEST_CODE_FORTRESS_END_RTC to LEGACY_ACTION_NAP_END
            )
            for ((code, act) in slots) {
                val pi = buildFortressEndPendingIntent(context, code, act)
                alarmManager.cancel(pi)
                pi.cancel()
            }
            Timber.tag(TAG).i("Cancelled fortress end alarms")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to cancel fortress end alarms")
        }
    }

    private const val REQUEST_CODE_SNOOZE = 3005

    fun scheduleCurfewSnoozeAlarm(context: Context, triggerWallMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            val nowMs = TrustedTimeProvider.currentTimeMillis(context)
            if (triggerWallMs <= nowMs) {
                cancelCurfewSnoozeAlarm(context)
                Timber.tag(TAG).d("Skipped past curfew snooze alarm ($triggerWallMs <= $nowMs)")
                return
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val intent = Intent(context, CurfewAlarmReceiver::class.java).apply {
                action = CurfewConfig.ACTION_CURFEW_SNOOZE_END
            }
            val pi = PendingIntent.getBroadcast(context, REQUEST_CODE_SNOOZE, intent, flags)
            val elapsedTrigger = TrustedTimeProvider.computeElapsedTrigger(triggerWallMs, context)
            setExactCompat(alarmManager, AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, pi)
            Timber.tag(TAG).i("Scheduled snooze expiration alarm via ELAPSED_REALTIME at $triggerWallMs (elapsed=$elapsedTrigger)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to schedule curfew snooze alarm")
        }
    }

    fun cancelCurfewSnoozeAlarm(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val intent = Intent(context, CurfewAlarmReceiver::class.java).apply {
                action = CurfewConfig.ACTION_CURFEW_SNOOZE_END
            }
            val pi = PendingIntent.getBroadcast(context, REQUEST_CODE_SNOOZE, intent, flags)
            alarmManager.cancel(pi)
            pi.cancel()
            Timber.tag(TAG).i("Cancelled curfew snooze alarm")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to cancel curfew snooze alarm")
        }
    }
}
