package com.drmohammed.antiporn.accessibility.util

import android.os.Build
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * Bounded, privacy-safe traversal and text-extraction utilities for [AccessibilityNodeInfo] trees
 * and [AccessibilityEvent] instances.
 */
object AccessibilityTreeWalker {

    private const val TAG = "AntiPorn_A11yWalker"

    /**
     * Returns true if [node] represents a password or sensitive credential field.
     * Returns false when [node] is null.
     */
    fun isSensitiveInputNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return try {
            isSensitiveInput(node.isPassword, node.inputType)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed inspecting AccessibilityNodeInfo sensitivity; treating as sensitive")
            true
        }
    }

    internal fun isSensitiveInput(isPassword: Boolean, inputType: Int): Boolean {
        if (isPassword) return true
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val isTextPassword = (inputClass == InputType.TYPE_CLASS_TEXT || inputClass == 0) &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val isNumberPassword = (inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == 0) &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return isTextPassword || isNumberPassword
    }

    /**
     * Extracts non-blank [AccessibilityEvent.getText] items joined by a single space without
     * accessing [AccessibilityEvent.getSource], returning empty string for password events.
     */
    fun extractSafeEventText(event: AccessibilityEvent): String {
        return try {
            extractSafeText(event.isPassword, event.text)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed extracting safe event text")
            ""
        }
    }

    internal fun extractSafeText(isPassword: Boolean, texts: List<CharSequence?>?): String {
        if (isPassword || texts.isNullOrEmpty()) return ""
        val builder = StringBuilder()
        for (item in texts) {
            val s = item?.toString()
            if (!s.isNullOrBlank()) {
                if (builder.isNotEmpty()) {
                    builder.append(' ')
                }
                builder.append(s)
            }
        }
        return builder.toString()
    }

    /**
     * Recycles [node] on pre-Android 14 devices where manual recycling avoids pool exhaustion,
     * ignoring stale/already-recycled errors safely.
     */
    fun safeRecycle(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                @Suppress("DEPRECATION")
                node.recycle()
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "Ignored recycle error")
            }
        }
    }

    /**
     * Traverses [root] depth-first up to [maxDepth] and [maxNodes], skipping sensitive nodes and
     * their subtrees when [skipSensitiveNodes] is true. Every fetched child node is recycled in a
     * `finally` block; [root] is never recycled by this method.
     *
     * @return true if [visitor] returned true to stop traversal early, false otherwise.
     */
    fun traverse(
        root: AccessibilityNodeInfo,
        maxDepth: Int = 10,
        maxNodes: Int = 150,
        skipSensitiveNodes: Boolean = true,
        visitor: (AccessibilityNodeInfo) -> Boolean
    ): Boolean {
        return traverseGeneric(
            root = root,
            maxDepth = maxDepth,
            maxNodes = maxNodes,
            skipSensitiveNodes = skipSensitiveNodes,
            isSensitive = { isSensitiveInputNode(it) },
            childCountOf = { node ->
                try {
                    node.childCount
                } catch (e: Exception) {
                    Timber.tag(TAG).d(e, "Failed reading AccessibilityNodeInfo.childCount")
                    0
                }
            },
            childAt = { node, index ->
                try {
                    node.getChild(index)
                } catch (e: Exception) {
                    Timber.tag(TAG).d(e, "Failed reading AccessibilityNodeInfo.getChild(%d)", index)
                    null
                }
            },
            recycle = { safeRecycle(it) },
            visitor = visitor
        )
    }

    internal fun <N> traverseGeneric(
        root: N,
        maxDepth: Int = 10,
        maxNodes: Int = 150,
        skipSensitiveNodes: Boolean = true,
        isSensitive: (N) -> Boolean,
        childCountOf: (N) -> Int,
        childAt: (N, Int) -> N?,
        recycle: (N) -> Unit,
        visitor: (N) -> Boolean
    ): Boolean {
        if (maxDepth < 0 || maxNodes <= 0) return false
        var visitedCount = 0

        fun visitNode(node: N, depth: Int): Boolean {
            if (visitedCount >= maxNodes) return false
            if (skipSensitiveNodes && isSensitive(node)) return false

            visitedCount++
            if (visitor(node)) return true

            if (depth >= maxDepth || visitedCount >= maxNodes) return false

            val count = childCountOf(node)
            for (i in 0 until count) {
                if (visitedCount >= maxNodes) break
                val child = childAt(node, i) ?: continue
                try {
                    if (visitNode(child, depth + 1)) {
                        return true
                    }
                } finally {
                    recycle(child)
                }
            }
            return false
        }

        return visitNode(root, 0)
    }
}
