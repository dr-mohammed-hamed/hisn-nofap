package com.drmohammed.antiporn.pageshield.registry

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * واجهة معيارية لاستراتيجيات كشف الصفحات داخل التطبيقات عبر خدمة الوصول.
 */
interface PageDetectorStrategy {
    /**
     * التحقق مما إذا كانت الصفحة المستهدفة معروضة حالياً على الشاشة.
     *
     * @param service مرجع خدمة الوصول للاستعلام عن الشجرة وعقد الواجهة.
     * @param event الحدث الحالي (إن وجد) للاستفادة من بيانات الحدث المباشرة.
     * @param screenWidth عرض الشاشة الحالي بالبكسل.
     * @param screenHeight ارتفاع الشاشة الحالي بالبكسل.
     */
    fun isPageDetected(
        service: AccessibilityService,
        event: AccessibilityEvent?,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean
}

/**
 * استراتيجية الكشف عبر مطابقة اسم الـ Activity (`event.className`).
 * الأسرع والأخف للتطبيقات ذات الـ Activities المنفصلة.
 */
class ActivityClassStrategy(
    private val targetClassNames: Set<String>
) : PageDetectorStrategy {

    override fun isPageDetected(
        service: AccessibilityService,
        event: AccessibilityEvent?,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        val className = event?.className?.toString() ?: return false
        return targetClassNames.any { target ->
            className.equals(target, ignoreCase = true) || className.endsWith(target, ignoreCase = true)
        }
    }
}

/**
 * استراتيجية الكشف عبر معرفات الموارد (Resource View IDs) مع حارس أبعاد الشاشة والظهور.
 */
@Suppress("DEPRECATION")
class ViewIdResourceStrategy(
    private val viewIdResourceNames: List<String>,
    private val minHeightRatio: Float = 0.60f,
    private val minWidthRatio: Float = 0.80f,
    private val requireVisibleToUser: Boolean = true
) : PageDetectorStrategy {

    override fun isPageDetected(
        service: AccessibilityService,
        event: AccessibilityEvent?,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        val rootNode = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to retrieve rootInActiveWindow: ${e.message}")
            null
        } ?: return false

        try {
            for (id in viewIdResourceNames) {
                val nodes = try {
                    rootNode.findAccessibilityNodeInfosByViewId(id)
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Error querying viewId $id: ${e.message}")
                    emptyList<AccessibilityNodeInfo>()
                }

                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        try {
                            if (requireVisibleToUser && !node.isVisibleToUser) {
                                continue
                            }

                            if (screenWidth > 0 && screenHeight > 0) {
                                val bounds = Rect()
                                node.getBoundsInScreen(bounds)
                                val widthRatio = bounds.width().toFloat() / screenWidth.toFloat()
                                val heightRatio = bounds.height().toFloat() / screenHeight.toFloat()

                                // Guard against PiP, Mini-players, and homepage shelves
                                if (widthRatio >= minWidthRatio && heightRatio >= minHeightRatio) {
                                    return true
                                }
                            } else {
                                return true
                            }
                        } finally {
                            try { node.recycle() } catch (_: Exception) {}
                        }
                    }
                }
            }
        } finally {
            try { rootNode.recycle() } catch (_: Exception) {}
        }

        return false
    }

    companion object {
        private const val TAG = "AntiPorn_ViewIdStrat"
    }
}

/**
 * استراتيجية الكشف عبر الدلالات وشجرة الوصول (التبويبات المحددة والأوصاف التوضيحية) مع استبعاد نصوص البحث.
 */
@Suppress("DEPRECATION")
class SemanticsAndTreeStrategy(
    private val targetKeywords: Set<String> = emptySet(),
    private val targetContentDescriptions: Set<String> = emptySet(),
    private val excludedKeywords: Set<String> = emptySet(),
    private val requireSelected: Boolean = false,
    private val maxDepth: Int = 8
) : PageDetectorStrategy {

    override fun isPageDetected(
        service: AccessibilityService,
        event: AccessibilityEvent?,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        val rootNode = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return false

        try {
            return traverse(rootNode, 0)
        } finally {
            try { rootNode.recycle() } catch (_: Exception) {}
        }
    }

    private fun traverse(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > maxDepth) return false

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        // Exclusions check
        for (ex in excludedKeywords) {
            if (text.contains(ex, ignoreCase = true) || desc.contains(ex, ignoreCase = true)) {
                return false
            }
        }

        val matchesText = targetKeywords.any { kw -> text.contains(kw, ignoreCase = true) }
        val matchesDesc = targetContentDescriptions.any { cd -> desc.contains(cd, ignoreCase = true) }

        if (matchesText || matchesDesc) {
            if (!requireSelected || node.isSelected) {
                if (node.isVisibleToUser) {
                    return true
                }
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue

            try {
                if (traverse(child, depth + 1)) {
                    return true
                }
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }

        return false
    }
}

/**
 * استراتيجية مركبة تتيح دمج أكثر من استراتيجية بشروط منطقية.
 */
enum class StrategyOperator {
    ANY,
    ALL,
    EXCLUDE
}

class CompositePageStrategy(
    private val primary: PageDetectorStrategy,
    private val secondary: PageDetectorStrategy,
    private val operator: StrategyOperator = StrategyOperator.ANY
) : PageDetectorStrategy {

    override fun isPageDetected(
        service: AccessibilityService,
        event: AccessibilityEvent?,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        return when (operator) {
            StrategyOperator.ANY -> {
                primary.isPageDetected(service, event, screenWidth, screenHeight) ||
                    secondary.isPageDetected(service, event, screenWidth, screenHeight)
            }
            StrategyOperator.ALL -> {
                primary.isPageDetected(service, event, screenWidth, screenHeight) &&
                    secondary.isPageDetected(service, event, screenWidth, screenHeight)
            }
            StrategyOperator.EXCLUDE -> {
                primary.isPageDetected(service, event, screenWidth, screenHeight) &&
                    !secondary.isPageDetected(service, event, screenWidth, screenHeight)
            }
        }
    }
}
