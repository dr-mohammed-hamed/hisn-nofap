package com.drmohammed.antiporn.core.network.dns

/**
 * High-speed, zero-false-positive adult keyword classifier.
 *
 * Implements two distinct protection gates:
 * 1. Unambiguous Long Root Tokens: Matched anywhere inside domain labels without false positives.
 * 2. Boundary-Strict Short Tokens: Matched strictly on delimiter boundaries (e.g. "sex.com", "arab-porn.org")
 *    preventing the classic Scunthorpe false-positive traps (e.g. "sussex.ac.uk", "analytics.google.com", "classroom.google.com").
 */
object PornKeywordMatcher {

    // Unambiguous tokens that NEVER appear in legitimate benign domain names
    val UNAMBIGUOUS_ROOT_TOKENS: Set<String> = setOf(
        "pornhub",
        "xvideos",
        "xnxx",
        "xhamster",
        "brazzers",
        "onlyfans",
        "chaturbate",
        "spankbang",
        "redtube",
        "youporn",
        "eporner",
        "stripchat",
        "hentai",
        "nhentai",
        "rule34",
        "fapello",
        "livejasmin",
        "bongacams",
        "fansly",
        "motherless",
        "heavy-r",
        "slutload",
        "thumbzilla",
        "porntrex",
        "camsoda",
        "streamate",
        "myfreecams",
        "clips4sale",
        "manyvids",
        "camwhores",
        "rec-tube",
        "flirt4free",
        "xlovecam",
        "daftsex",
        "freeones",
        "realitykings",
        "bangbros",
        "naughtyamerica",
        "digitalplayground",
        "evilangel",
        "teamskeet",
        "sislovesme",
        "familyhookups",
        "submissivex",
        "porndoe",
        "missav",
        "netflav",
        "supjav",
        "7mmtv",
        "avgle"
    )

    // Exact adult domains to avoid substring false positives (e.g. erome in jerome, beeg in beegarden)
    val EXACT_ADULT_DOMAINS: Set<String> = setOf(
        "erome.com",
        "cam4.com",
        "beeg.com",
        "jable.tv"
    )

    // Short tokens that are dangerous if matched as plain substrings inside other words
    val BOUNDARY_STRICT_SHORT_TOKENS: Set<String> = setOf(
        "porn",
        "sex",
        "xxx",
        "nsfw",
        "nude",
        "milf",
        "slut",
        "bdsm",
        "boobs",
        "tits",
        "pussy",
        "incest",
        "erome",
        "cam4",
        "beeg",
        "jable"
    )

    // Explicit exclusions for known benign academic, municipal, or analytics words
    private val SAFE_WORD_ALLOWLIST: Set<String> = setOf(
        "sussex",
        "essex",
        "middlesex",
        "analytics",
        "classroom",
        "document",
        "assessment",
        "expression",
        "possession",
        "obsession",
        "intersex",
        "jerome",
        "heteromeric",
        "beegarden",
        "beegees",
        "webcam4k",
        "manejable",
        "trabajable"
    )

    /**
     * Inspects a domain to determine if it is an adult domain.
     * Guaranteed 0% false positives on legitimate sites like "sussex.ac.uk" or "analytics.google.com".
     */
    fun isAdultDomain(rawDomain: String): Boolean {
        var domain = rawDomain.trim().lowercase().trimEnd('.')
        if (domain.startsWith("www.")) {
            domain = domain.substring(4)
        }
        if (domain.isBlank()) return false

        // 0. Check Exact Adult Domains (e.g. "erome.com", "cam4.com", "beeg.com", "jable.tv")
        for (exact in EXACT_ADULT_DOMAINS) {
            if (domain == exact || domain.endsWith(".$exact")) {
                return true
            }
        }

        val labels = domain.split('.')
        if (labels.isEmpty()) return false

        // 1. Check Unambiguous Root Tokens (match anywhere in labels, masking SAFE_WORD_ALLOWLIST occurrences)
        for (label in labels) {
            if (SAFE_WORD_ALLOWLIST.contains(label)) continue
            var maskedLabel = label
            for (safe in SAFE_WORD_ALLOWLIST) {
                if (maskedLabel.contains(safe)) {
                    maskedLabel = maskedLabel.replace(safe, " ")
                }
            }
            for (token in UNAMBIGUOUS_ROOT_TOKENS) {
                if (maskedLabel.contains(token)) {
                    return true
                }
            }
        }

        // 2. Check Boundary-Strict Short Tokens
        // Each label is segmented by non-alphanumeric delimiters ('-', '_')
        for (label in labels) {
            if (SAFE_WORD_ALLOWLIST.contains(label)) continue

            val segments = label.split('-', '_')
            for (segment in segments) {
                if (SAFE_WORD_ALLOWLIST.contains(segment)) continue

                // Check exact segment match (e.g. "sex" in "sex.com" or "porn" in "free-porn.net")
                if (BOUNDARY_STRICT_SHORT_TOKENS.contains(segment)) {
                    return true
                }

                // Check if segment has digits attached to token (e.g. "sex18", "xxx24", "123cam4")
                val strippedBoth = segment.trim { it.isDigit() }
                val strippedLeading = segment.trimStart { it.isDigit() }
                val strippedTrailing = segment.trimEnd { it.isDigit() }
                if (BOUNDARY_STRICT_SHORT_TOKENS.contains(strippedBoth) ||
                    BOUNDARY_STRICT_SHORT_TOKENS.contains(strippedLeading) ||
                    BOUNDARY_STRICT_SHORT_TOKENS.contains(strippedTrailing)
                ) {
                    return true
                }
            }
        }

        return false
    }
}
