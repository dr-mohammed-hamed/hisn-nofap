package com.drmohammed.antiporn.ui.common

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Configures a [BottomSheetDialogFragment] to expand immediately in landscape/compact windows,
 * skip the half-collapsed state, and optionally resize when the software keyboard (IME) opens.
 */
fun BottomSheetDialogFragment.configureLandscapeFriendlySheet(enableImeResize: Boolean = true) {
    val bottomSheetDialog = dialog as? BottomSheetDialog ?: return
    if (enableImeResize) {
        @Suppress("DEPRECATION")
        bottomSheetDialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    bottomSheetDialog.behavior.apply {
        state = BottomSheetBehavior.STATE_EXPANDED
        skipCollapsed = true
    }
    bottomSheetDialog.setOnShowListener { shownDialog ->
        (shownDialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }
}

/**
 * Applies navigation bar, display cutout, and optional IME bottom insets to [rootView]
 * while preserving its initial XML padding values.
 */
fun applyBottomSheetInsets(
    rootView: View,
    includeIme: Boolean = false,
    onImeVisibilityChanged: ((Boolean) -> Unit)? = null
) {
    if (rootView is ViewGroup) {
        rootView.clipToPadding = false
    }
    val baseLeft = rootView.paddingLeft
    val baseTop = rootView.paddingTop
    val baseRight = rootView.paddingRight
    val baseBottom = rootView.paddingBottom

    val typeMask = if (includeIme) {
        WindowInsetsCompat.Type.navigationBars() or
            WindowInsetsCompat.Type.displayCutout() or
            WindowInsetsCompat.Type.ime()
    } else {
        WindowInsetsCompat.Type.navigationBars() or
            WindowInsetsCompat.Type.displayCutout()
    }

    ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
        val insets = windowInsets.getInsets(typeMask)
        onImeVisibilityChanged?.invoke(windowInsets.isVisible(WindowInsetsCompat.Type.ime()))
        view.setPadding(
            baseLeft + insets.left,
            baseTop,
            baseRight + insets.right,
            baseBottom + insets.bottom
        )
        windowInsets
    }
    ViewCompat.requestApplyInsets(rootView)
}
