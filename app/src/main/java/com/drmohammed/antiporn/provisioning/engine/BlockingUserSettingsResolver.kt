package com.drmohammed.antiporn.provisioning.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.drmohammed.antiporn.core.engine.SecondaryProfileDetector

/**
 * Resolves the safest exported OEM/Android settings screen for managing or removing
 * blocking secondary OS users and profiles.
 */
internal object BlockingUserSettingsResolver {

    fun resolve(context: Context, users: List<BlockingSecondaryUser>): Intent {
        val pm = context.packageManager
        val candidates: List<Intent> = when (users.firstOrNull()?.category) {
            BlockingUserCategory.SECURE_FOLDER -> listOfNotNull(
                Intent().setComponent(
                    ComponentName(
                        "com.samsung.knox.securefolder",
                        "com.samsung.knox.securefolder.presentation.switcher.SwitchActivity"
                    )
                ),
                runCatching { pm?.getLaunchIntentForPackage("com.samsung.knox.securefolder") }.getOrNull(),
                Intent(Settings.ACTION_SECURITY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            BlockingUserCategory.DUAL_APP_CLONE -> return SecondaryProfileDetector.resolveCloneSettingsIntent(context)
            BlockingUserCategory.WORK_PROFILE -> listOf(
                Intent(Settings.ACTION_SYNC_SETTINGS),
                Intent(Settings.ACTION_SECURITY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            BlockingUserCategory.PRIVATE_SPACE -> listOf(
                Intent(Settings.ACTION_SECURITY_SETTINGS),
                Intent(Settings.ACTION_PRIVACY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            else -> listOf(
                Intent("android.settings.USER_SETTINGS"),
                Intent(Settings.ACTION_SYNC_SETTINGS),
                Intent(Settings.ACTION_SECURITY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
        }
        for (candidate in candidates) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pm != null && candidate.resolveActivityInfo(pm, 0)?.exported == true) {
                return candidate
            }
        }
        return Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
}
