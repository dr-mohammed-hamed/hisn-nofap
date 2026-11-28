package com.drmohammed.antiporn.core.model

/**
 * Pure 3-way merge helpers used to combine a user's local draft edits with the latest
 * policy snapshot without clobbering concurrent changes to unrelated fields.
 */
object PolicyMergeUtils {

    fun <T> pickIfChanged(latestVal: T, baseVal: T, draftVal: T): T =
        if (draftVal != baseVal) draftVal else latestVal

    fun mergeSet(
        latestVal: Set<String>,
        baseVal: Set<String>,
        draftVal: Set<String>
    ): Set<String> {
        if (draftVal == baseVal) return latestVal
        return (latestVal + (draftVal - baseVal)) - (baseVal - draftVal)
    }
}
