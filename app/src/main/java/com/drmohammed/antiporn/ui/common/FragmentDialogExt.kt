package com.drmohammed.antiporn.ui.common

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager

/**
 * Safely displays a [DialogFragment] only when state has not been saved and no fragment
 * with [tag] is already attached to this [FragmentManager].
 */
fun FragmentManager.showSafely(tag: String, factory: () -> DialogFragment) {
    if (isStateSaved || findFragmentByTag(tag) != null) return
    factory().show(this, tag)
}
