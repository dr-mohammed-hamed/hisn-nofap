package com.drmohammed.antiporn.pageshield.registry

import com.drmohammed.antiporn.R

/**
 * تعريف صفحة محمية داخل تطبيق معين.
 */
data class ProtectedPageDefinition(
    val pageId: String,
    val titleResId: Int,
    val descriptionResId: Int,
    val detectorStrategy: PageDetectorStrategy
)

/**
 * تعريف تطبيق محمي في سجل درع الصفحات.
 */
data class ProtectedAppDefinition(
    val packageName: String,
    val appNameResId: Int,
    val appIconResId: Int,
    val pages: List<ProtectedPageDefinition>
)

/**
 * سجل التطبيقات والصفحات المدعومة في «درع الصفحات» (Open/Closed Registry).
 * إضافة أي تطبيق أو صفحة جديدة مستقبلاً يتم بمجرد إضافة كائن تعريفي هنا دون المساس بالمحرك.
 */
object PageShieldRegistry {

    const val PACKAGE_YOUTUBE = "com.google.android.youtube"
    const val PAGE_YOUTUBE_SHORTS = "youtube_shorts"

    /**
     * قائمة التطبيقات المعرفة برمجياً في السجل.
     */
    val registeredApps: List<ProtectedAppDefinition> by lazy {
        listOf(
            createYouTubeDefinition()
        )
    }

    fun findApp(packageName: String): ProtectedAppDefinition? {
        return registeredApps.firstOrNull { it.packageName.equals(packageName, ignoreCase = true) }
    }

    fun findPage(packageName: String, pageId: String): ProtectedPageDefinition? {
        return findApp(packageName)?.pages?.firstOrNull { it.pageId == pageId }
    }

    private fun createYouTubeDefinition(): ProtectedAppDefinition {
        // 1. Primary Strategy: Match specific full-screen Reel/Shorts containers
        val primaryStrategy = ViewIdResourceStrategy(
            viewIdResourceNames = listOf(
                "com.google.android.youtube:id/reel_watch_fragment_root",
                "com.google.android.youtube:id/reel_player_page_container",
                "com.google.android.youtube:id/shorts_player_container",
                "com.google.android.youtube:id/reel_recycler",
                "com.google.android.youtube:id/reel_progress_bar"
            ),
            minHeightRatio = 0.60f,
            minWidthRatio = 0.80f,
            requireVisibleToUser = true
        )

        // 2. Secondary/Fallback Strategy: Selected Shorts tab or Reel action semantics with exclusions
        val secondaryStrategy = SemanticsAndTreeStrategy(
            targetContentDescriptions = setOf("Shorts", "شورتس"),
            excludedKeywords = setOf("بحث", "Search", "نتائج", "Results"),
            requireSelected = true,
            maxDepth = 8
        )

        // 3. Composite Strategy (ANY): Guarantees resilience against YouTube resource ID obfuscations
        val compositeStrategy = CompositePageStrategy(
            primary = primaryStrategy,
            secondary = secondaryStrategy,
            operator = StrategyOperator.ANY
        )

        val shortsPage = ProtectedPageDefinition(
            pageId = PAGE_YOUTUBE_SHORTS,
            titleResId = R.string.page_shield_yt_shorts_title,
            descriptionResId = R.string.page_shield_yt_shorts_desc,
            detectorStrategy = compositeStrategy
        )

        return ProtectedAppDefinition(
            packageName = PACKAGE_YOUTUBE,
            appNameResId = R.string.page_shield_app_youtube,
            appIconResId = R.drawable.ic_shield_page,
            pages = listOf(shortsPage)
        )
    }
}
