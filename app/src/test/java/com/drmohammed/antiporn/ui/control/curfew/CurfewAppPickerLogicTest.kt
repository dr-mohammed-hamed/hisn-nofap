package com.drmohammed.antiporn.ui.control.curfew

import com.drmohammed.antiporn.ui.accountability.RemoteAppManagerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurfewAppPickerLogicTest {

    // =========================================================================
    // 1. CurfewAppPickerBottomSheet.buildOrphanedItems
    // =========================================================================

    @Test
    fun `buildOrphanedItems preserves unseen run-only and run-plus-net packages without duplicating seen packages`() {
        val initialWhitelist = setOf("com.seen.app", "com.orphan.runonly", "com.orphan.runandnet")
        val initialVpnBypass = setOf("com.seen.app", "com.orphan.runandnet")
        val seenPackages = setOf("com.seen.app")

        val items = CurfewAppPickerBottomSheet.buildOrphanedItems(
            initialWhitelist = initialWhitelist,
            initialVpnBypass = initialVpnBypass,
            seenPackages = seenPackages,
            isRestricted = { false }
        )

        val byPkg = items.associateBy { it.packageName }
        assertEquals(2, byPkg.size)
        assertFalse(byPkg.containsKey("com.seen.app"))

        val runOnly = byPkg.getValue("com.orphan.runonly")
        assertTrue(runOnly.isRunAllowed)
        assertFalse(runOnly.isNetAllowed)

        val runAndNet = byPkg.getValue("com.orphan.runandnet")
        assertTrue(runAndNet.isRunAllowed)
        assertTrue(runAndNet.isNetAllowed)
    }

    @Test
    fun `buildOrphanedItems excludes restricted packages`() {
        val restricted = setOf("com.android.vending", "com.blacklisted.app")
        val items = CurfewAppPickerBottomSheet.buildOrphanedItems(
            initialWhitelist = setOf("com.android.vending", "com.blacklisted.app", "com.allowed.orphan"),
            initialVpnBypass = setOf("com.blacklisted.app", "com.allowed.orphan"),
            seenPackages = emptySet(),
            isRestricted = { restricted.contains(it) }
        )

        assertEquals(1, items.size)
        assertEquals("com.allowed.orphan", items.first().packageName)
        assertTrue(items.first().isRunAllowed)
        assertTrue(items.first().isNetAllowed)
    }

    @Test
    fun `buildOrphanedItems enforces invariant that isNetAllowed is false when package is not in initialWhitelist`() {
        val items = CurfewAppPickerBottomSheet.buildOrphanedItems(
            initialWhitelist = emptySet(),
            initialVpnBypass = setOf("com.orphan.vpnonly"),
            seenPackages = emptySet(),
            isRestricted = { false }
        )

        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("com.orphan.vpnonly", item.packageName)
        assertFalse(item.isRunAllowed)
        assertFalse(item.isNetAllowed)
    }

    // =========================================================================
    // 2. RemoteAppManagerUiState.isSaveEnabled
    // =========================================================================

    @Test
    fun `RemoteAppManagerUiState isSaveEnabled is false when remote policy load failed even with unsaved changes`() {
        val failedRemoteState = RemoteAppManagerUiState(
            isLocalMode = false,
            isLoading = false,
            isPolicyLoadFailed = true,
            isSaving = false,
            isRepairing = false,
            initialBlacklist = emptySet(),
            customBlacklist = setOf("com.example.blocked")
        )

        assertTrue(failedRemoteState.hasUnsavedChanges)
        assertFalse(failedRemoteState.isSaveEnabled)
    }

    @Test
    fun `RemoteAppManagerUiState isSaveEnabled is true when remote policy loaded and changes are unsaved`() {
        val readyRemoteState = RemoteAppManagerUiState(
            isLocalMode = false,
            isLoading = false,
            isPolicyLoadFailed = false,
            isSaving = false,
            isRepairing = false,
            initialCurfewWhitelist = emptySet(),
            customCurfewWhitelist = setOf("com.whatsapp")
        )

        assertTrue(readyRemoteState.hasUnsavedChanges)
        assertTrue(readyRemoteState.isSaveEnabled)

        assertFalse(readyRemoteState.copy(isLoading = true).isSaveEnabled)
        assertFalse(readyRemoteState.copy(isSaving = true).isSaveEnabled)
        assertFalse(readyRemoteState.copy(isRepairing = true).isSaveEnabled)
        assertFalse(readyRemoteState.copy(customCurfewWhitelist = emptySet()).isSaveEnabled)
    }
}
