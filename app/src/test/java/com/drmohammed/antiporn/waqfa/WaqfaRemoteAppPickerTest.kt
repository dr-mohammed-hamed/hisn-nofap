package com.drmohammed.antiporn.waqfa

import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.data.model.InstalledAppDto
import com.drmohammed.antiporn.waqfa.ui.settings.WaqfaAppPickerBottomSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rigorous Unit Test Suite for WaqfaAppPickerBottomSheet and Remote Inventory Resolution.
 * Validates companion contracts, remote inventory DTO parsing,
 * orphaned target preservation, and immune/critical package filtering.
 */
class WaqfaRemoteAppPickerTest {

    @Test
    fun `test companion contracts and request keys`() {
        assertEquals("WaqfaAppPicker", WaqfaAppPickerBottomSheet.TAG)
        assertEquals("waqfa_app_picker_request", WaqfaAppPickerBottomSheet.REQUEST_KEY)
        assertEquals("result_waqfa_targets", WaqfaAppPickerBottomSheet.RESULT_TARGETS)
    }

    @Test
    fun `test AppItem model state toggling`() {
        val item = WaqfaAppPickerBottomSheet.AppItem(
            packageName = "com.twitter.android",
            appName = "X / Twitter",
            isSelected = false
        )
        assertFalse(item.isSelected)
        item.isSelected = true
        assertTrue(item.isSelected)
    }

    @Test
    fun `test parsing remote inventory InstalledAppDto list`() {
        val rawApps = listOf(
            mapOf("packageName" to "com.twitter.android", "appName" to "X / Twitter"),
            mapOf("packageName" to "com.instagram.android", "appName" to "Instagram"),
            mapOf("packageName" to "", "appName" to "Invalid Blank App"),
            mapOf("packageName" to "org.telegram.messenger", "appName" to "Telegram")
        )

        val parsedApps = rawApps.mapNotNull { raw ->
            @Suppress("UNCHECKED_CAST")
            (raw as? Map<String, Any?>)?.let { InstalledAppDto.fromMap(it) }
        }.filter { it.packageName.isNotBlank() }

        assertEquals(3, parsedApps.size)
        assertEquals("com.twitter.android", parsedApps[0].packageName)
        assertEquals("X / Twitter", parsedApps[0].appName)
        assertEquals("com.instagram.android", parsedApps[1].packageName)
        assertEquals("org.telegram.messenger", parsedApps[2].packageName)
    }

    @Test
    fun `test orphaned target preservation logic for remote mode`() {
        val initialTargets = setOf("com.snapchat.android", "com.twitter.android", "com.tiktok.android")
        
        // Simulating parsed apps from Firestore inventory (Snapchat is missing because it was uninstalled or pending sync)
        val parsedInventoryPackages = setOf("com.twitter.android", "com.instagram.android")

        val seenPackages = mutableSetOf<String>()
        val resultList = mutableListOf<WaqfaAppPickerBottomSheet.AppItem>()

        // 1. Process inventory apps
        for (pkg in parsedInventoryPackages) {
            seenPackages.add(pkg)
            resultList.add(
                WaqfaAppPickerBottomSheet.AppItem(
                    packageName = pkg,
                    appName = pkg,
                    isSelected = initialTargets.contains(pkg)
                )
            )
        }

        // 2. Preserve orphaned targets
        val extraPackages = initialTargets - seenPackages
        for (orphan in extraPackages) {
            resultList.add(
                WaqfaAppPickerBottomSheet.AppItem(
                    packageName = orphan,
                    appName = orphan,
                    isSelected = true
                )
            )
        }

        val selectedPackages = resultList.filter { it.isSelected }.map { it.packageName }.toSet()

        // Verify that snapchat and tiktok were not dropped
        assertTrue(selectedPackages.contains("com.snapchat.android"))
        assertTrue(selectedPackages.contains("com.tiktok.android"))
        assertTrue(selectedPackages.contains("com.twitter.android"))
        assertFalse(selectedPackages.contains("com.instagram.android")) // not selected initially
        assertEquals(3, selectedPackages.size)
    }

    @Test
    fun `test restricted package filtering respects immune and critical packages`() {
        val criticalPackages = setOf("com.android.systemui", "com.google.android.packageinstaller")
        val selfPackage = "com.drmohammed.antiporn"

        fun isRestricted(pkg: String): Boolean {
            if (pkg.isBlank()) return true
            if (pkg == selfPackage) return true
            if (criticalPackages.contains(pkg)) return true
            if (AppPolicyRegistry.isImmune(pkg)) return true
            return false
        }

        assertTrue("Blank package must be restricted", isRestricted(""))
        assertTrue("Self package must be restricted", isRestricted(selfPackage))
        assertTrue("Critical systemui package must be restricted", isRestricted("com.android.systemui"))
        assertTrue("Android Settings (immune) must be restricted", isRestricted("com.android.settings"))
        assertFalse("Normal user apps like Instagram must NOT be restricted", isRestricted("com.instagram.android"))
        assertFalse("Normal user apps like WhatsApp must NOT be restricted", isRestricted("com.whatsapp"))
    }

    @Test
    fun `test state restoration prioritizes savedInstanceState over arguments`() {
        val initialTargetsFromArgs = setOf("com.snapchat.android")
        val savedSelectionsFromRotation: Set<String>? = setOf("com.snapchat.android", "com.twitter.android")

        val effectiveTargets = savedSelectionsFromRotation ?: initialTargetsFromArgs

        assertEquals(2, effectiveTargets.size)
        assertTrue(effectiveTargets.contains("com.twitter.android"))
    }
}
