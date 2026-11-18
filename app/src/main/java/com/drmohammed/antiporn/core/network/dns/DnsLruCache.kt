package com.drmohammed.antiporn.core.network.dns

/**
 * High-performance, thread-safe in-memory Micro LRU response cache for DNS queries.
 * Reduces socket contention, descriptor churn, and delivers 0ms responses for repeated queries.
 * Supports atomic generation fencing so network/policy transitions never serve or store stale answers.
 */
class DnsLruCache(
    private val maxEntries: Int = 512,
    private val clockMs: () -> Long = {
        try {
            android.os.SystemClock.elapsedRealtime()
        } catch (_: RuntimeException) {
            System.nanoTime() / 1_000_000L
        }
    }
) {

    data class CachedResponse(
        val rawDnsPayload: ByteArray,
        val expiresAtMs: Long,
        val generation: Long = 0L
    ) {
        fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CachedResponse) return false
            return expiresAtMs == other.expiresAtMs &&
                generation == other.generation &&
                rawDnsPayload.contentEquals(other.rawDnsPayload)
        }

        override fun hashCode(): Int {
            var result = rawDnsPayload.contentHashCode()
            result = 31 * result + expiresAtMs.hashCode()
            result = 31 * result + generation.hashCode()
            return result
        }
    }

    private val lock = Any()
    private val lruMap = object : LinkedHashMap<String, CachedResponse>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedResponse>?): Boolean {
            return size > maxEntries
        }
    }

    /**
     * Looks up a cached DNS response for given QNAME and QTYPE.
     * When [expectedGeneration] is provided, entries from older generations are ignored and evicted.
     */
    fun get(qName: String, qType: Int, expectedGeneration: Long? = null): ByteArray? {
        val key = "$qName#$qType"
        val now = clockMs()
        synchronized(lock) {
            val entry = lruMap[key] ?: return null
            if (entry.isExpired(now) || (expectedGeneration != null && entry.generation != expectedGeneration)) {
                lruMap.remove(key)
                return null
            }
            return entry.rawDnsPayload.copyOf()
        }
    }

    /**
     * Stores a raw DNS response payload with a clamped TTL (5s <= TTL <= 120s).
     */
    fun put(qName: String, qType: Int, payload: ByteArray, ttlSeconds: Long = 60L, generation: Long = 0L) {
        val key = "$qName#$qType"
        val safeTtl = ttlSeconds.coerceIn(5L, 120L)
        val expiresAt = clockMs() + safeTtl * 1000L
        synchronized(lock) {
            lruMap[key] = CachedResponse(payload.copyOf(), expiresAt, generation)
        }
    }

    /**
     * Atomically stores a response only if [currentGenerationProvider] still equals [expectedGeneration].
     * Prevents TOCTOU cache poisoning when `clear()` runs concurrently with an in-flight upstream query.
     */
    fun putIfGenerationMatches(
        qName: String,
        qType: Int,
        payload: ByteArray,
        ttlSeconds: Long,
        expectedGeneration: Long,
        currentGenerationProvider: () -> Long
    ): Boolean {
        val key = "$qName#$qType"
        val safeTtl = ttlSeconds.coerceIn(5L, 120L)
        val expiresAt = clockMs() + safeTtl * 1000L
        synchronized(lock) {
            if (currentGenerationProvider() != expectedGeneration) return false
            lruMap[key] = CachedResponse(payload.copyOf(), expiresAt, expectedGeneration)
            return true
        }
    }

    /**
     * Clears all cached DNS responses.
     */
    fun clear() {
        synchronized(lock) {
            lruMap.clear()
        }
    }

    /**
     * Current size of the cache.
     */
    val size: Int
        get() = synchronized(lock) { lruMap.size }
}
