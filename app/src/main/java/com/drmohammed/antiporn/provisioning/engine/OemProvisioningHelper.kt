package com.drmohammed.antiporn.provisioning.engine

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.UserManager
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.SecondaryProfileDetector
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import timber.log.Timber
import java.util.Locale

/**
 * OEM-specific detection and guidance helper.
 *
 * Inspects device manufacturer and OEM configurations to present
 * tailored instructions for Xiaomi, Samsung, and Realme/Oppo devices.
 * Adheres strictly to idiomatic Kotlin, < 250 lines, zero external libraries.
 */
object OemProvisioningHelper {

    private const val TAG = "AntiPorn_OemHelper"

    val manufacturer: String get() = Build.MANUFACTURER?.lowercase(Locale.ROOT) ?: ""
    val brand: String get() = Build.BRAND?.lowercase(Locale.ROOT) ?: ""

    fun isXiaomi(): Boolean {
        val m = manufacturer
        val b = brand
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
            b.contains("xiaomi") || b.contains("redmi") || b.contains("poco")
        ) {
            return true
        }
        return try {
            val propClass = Class.forName("android.os.SystemProperties")
            val getMethod = propClass.getMethod("get", String::class.java)
            val miuiVersion = getMethod.invoke(null, "ro.miui.ui.version.name") as? String
            miuiVersion?.isNotBlank() == true
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "MIUI SystemProperties property check skipped or unavailable")
            false
        }
    }

    fun isSamsung(): Boolean = manufacturer.contains("samsung")

    fun isRealmeOrOppo(): Boolean =
        manufacturer.contains("realme") || manufacturer.contains("oppo") || manufacturer.contains("oneplus")

    /**
     * Checks if secondary users, work profiles, Samsung Knox Secure Folder,
     * or Android 15 Private Space are active on the device.
     */
    fun hasConflictingProfile(context: Context): Boolean {
        return try {
            val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            val profiles = userManager?.userProfiles ?: emptyList()
            if (profiles.size > 1) return true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                val launcherProfiles = launcherApps?.profiles ?: emptyList()
                if (launcherProfiles.size > 1) return true
            }

            if (SafeDpmGateway.isDeviceOwner(context)) {
                SecondaryProfileDetector.countSecondaryUsersAndProfiles(context).count > 0
            } else {
                false
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Failed inspecting user profiles: ${t.message}")
            false
        }
    }

    fun requiresPreActivationDangerAck(context: Context): Boolean =
        isSamsung() || hasConflictingProfile(context)

    fun checkSamsungMultiUser(context: Context): Boolean =
        isSamsung() && hasConflictingProfile(context)

    @StringRes
    fun getDevOptionsDialogDescription(): Int =
        if (isXiaomi()) R.string.dialog_enable_dev_desc_miui else R.string.dialog_enable_dev_desc

    data class OemWarning(
        @param:StringRes val titleRes: Int,
        @param:StringRes val descRes: Int,
        val isCritical: Boolean = false
    )

    data class DangerDialogCopy(
        @param:StringRes val titleRes: Int,
        @param:StringRes val descRes: Int,
        val showRecheckButton: Boolean
    )

    fun getDangerDialogStrings(context: Context): DangerDialogCopy = when {
        isSamsung() && hasConflictingProfile(context) -> DangerDialogCopy(
            R.string.oem_samsung_multiuser_title,
            R.string.oem_samsung_multiuser_desc,
            showRecheckButton = true
        )
        hasConflictingProfile(context) -> DangerDialogCopy(
            R.string.dialog_danger_private_space_title,
            R.string.dialog_danger_private_space_desc,
            showRecheckButton = true
        )
        isSamsung() -> DangerDialogCopy(
            R.string.dialog_danger_private_space_title,
            R.string.dialog_danger_samsung_preventive_desc,
            showRecheckButton = false
        )
        else -> DangerDialogCopy(
            R.string.dialog_danger_private_space_title,
            R.string.dialog_danger_private_space_desc,
            showRecheckButton = false
        )
    }

    /**
     * Returns all applicable OEM pre-flight warnings without letting conflicting-profile
     * warnings shadow OPPO/Realme or Xiaomi permission-monitoring requirements.
     */
    fun getAllOemWarnings(context: Context): List<OemWarning> {
        val hasConflict = hasConflictingProfile(context)
        val isOnlyClone = if (hasConflict) {
            try {
                val profiles = SecondaryProfileDetector.getSecondaryProfiles(context)
                profiles.isNotEmpty() && profiles.all { it.profileType == SecondaryProfileDetector.ProfileType.CLONE_APP }
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        return buildAllOemWarnings(
            hasConflictingProfile = hasConflict,
            isSamsung = isSamsung(),
            isXiaomi = isXiaomi(),
            isRealmeOrOppo = isRealmeOrOppo(),
            isOnlyCloneProfile = isOnlyClone
        )
    }

    @VisibleForTesting
    internal fun buildAllOemWarnings(
        hasConflictingProfile: Boolean,
        isSamsung: Boolean,
        isXiaomi: Boolean,
        isRealmeOrOppo: Boolean,
        isOnlyCloneProfile: Boolean = false
    ): List<OemWarning> {
        val warnings = mutableListOf<OemWarning>()
        if (hasConflictingProfile) {
            warnings.add(
                when {
                    isSamsung -> OemWarning(
                        R.string.oem_samsung_multiuser_title,
                        R.string.oem_samsung_multiuser_desc,
                        isCritical = true
                    )
                    isOnlyCloneProfile -> OemWarning(
                        R.string.oem_clone_profile_warning_title,
                        R.string.oem_clone_profile_warning_desc,
                        isCritical = false
                    )
                    else -> OemWarning(
                        R.string.dialog_danger_private_space_title,
                        R.string.dialog_danger_private_space_desc,
                        isCritical = true
                    )
                }
            )
        } else if (isSamsung) {
            warnings.add(
                OemWarning(
                    R.string.oem_samsung_preventive_title,
                    R.string.oem_samsung_preventive_desc,
                    isCritical = true
                )
            )
        }
        if (isXiaomi) {
            warnings.add(OemWarning(R.string.oem_xiaomi_title, R.string.oem_xiaomi_desc, isCritical = false))
        }
        if (isRealmeOrOppo) {
            warnings.add(OemWarning(R.string.oem_realme_oppo_title, R.string.oem_realme_oppo_desc, isCritical = false))
        }
        return warnings
    }

    /**
     * Returns the primary OEM-specific pre-flight guidance warning, if applicable.
     */
    fun getOemWarning(context: Context): OemWarning? = getAllOemWarnings(context).firstOrNull()
}
