package com.drmohammed.antiporn.core.network.dns

import android.os.Looper
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Pooled TLS socket connection for RFC 8484 HTTP/1.1 keep-alive DoH exchanges.
 * Tracks both the underlying [Socket] and wrapped [SSLSocket] with pre-bound streams.
 */
internal class DohSocketConnection(
    val raw: Socket,
    val ssl: SSLSocket,
    val outStream: DataOutputStream,
    val inStream: DataInputStream
) : AutoCloseable {

    val isUsable: Boolean
        get() = !raw.isClosed &&
            !ssl.isClosed &&
            ssl.isConnected &&
            !ssl.isInputShutdown &&
            !ssl.isOutputShutdown

    fun setReadTimeout(timeoutMs: Int) {
        try {
            ssl.soTimeout = timeoutMs.coerceAtLeast(1)
        } catch (_: Exception) {
            // Mock or loopback socket implementations in JVM unit tests may ignore soTimeout updates
        }
    }

    override fun close() {
        try {
            ssl.close()
        } catch (_: Exception) {
        }
        try {
            raw.close()
        } catch (_: Exception) {
        }
    }
}

internal data class DohBorrowedSlot(
    val conn: DohSocketConnection?,
    val generation: Long,
    val handle: ActiveHandle
)

/**
 * Bounded per-endpoint LIFO TLS connection pool with generation fencing, active-socket
 * tracking, head-expiration pruning, and off-main-thread teardown for [DohUpstreamClient].
 */
internal class DohConnectionPool(
    private val clockMs: () -> Long,
    private val maxPoolSize: Int = MAX_POOL_SIZE,
    private val idleTimeoutMs: Long = POOL_IDLE_TIMEOUT_MS
) {
    private data class PooledDohConnection(
        val conn: DohSocketConnection,
        val generation: Long,
        val lastUsedMs: Long
    )

    private val poolLock = Any()
    private val poolGeneration = AtomicLong(0L)
    private val idlePools = HashMap<String, ArrayDeque<PooledDohConnection>>()
    private val activeHandles = HashSet<ActiveHandle>()

    fun poolKey(endpoint: DohUpstreamClient.DohEndpoint): String = "${endpoint.ip}|${endpoint.host}"

    fun currentGeneration(): Long = poolGeneration.get()

    fun borrowOrPrepareHandle(poolKey: String, nowMs: Long, forceFresh: Boolean): DohBorrowedSlot {
        val expiredToClose = ArrayList<DohSocketConnection>()
        val result: DohBorrowedSlot
        synchronized(poolLock) {
            val currentGen = poolGeneration.get()
            var reused: DohSocketConnection? = null
            if (!forceFresh) {
                val deque = idlePools[poolKey]
                if (deque != null) {
                    while (deque.isNotEmpty()) {
                        val entry = deque.removeLast()
                        val isExpired = (nowMs - entry.lastUsedMs) >= idleTimeoutMs
                        if (entry.generation != currentGen || isExpired || !entry.conn.isUsable) {
                            expiredToClose.add(entry.conn)
                        } else {
                            reused = entry.conn
                            break
                        }
                    }
                    if (deque.isEmpty()) {
                        idlePools.remove(poolKey)
                    }
                }
            }
            val handle = ActiveHandle(currentGen)
            if (reused != null) {
                handle.attach(reused)
            }
            activeHandles.add(handle)
            result = DohBorrowedSlot(reused, currentGen, handle)
        }
        closeAllSafely(expiredToClose)
        return result
    }

    fun releaseConnection(
        poolKey: String,
        conn: DohSocketConnection?,
        generation: Long,
        handle: ActiveHandle,
        keepAlive: Boolean
    ) {
        val nowMs = clockMs()
        val staleToClose = ArrayList<AutoCloseable>()
        var shouldCloseCurrent = !keepAlive || conn == null
        synchronized(poolLock) {
            activeHandles.remove(handle)
            val currentGen = poolGeneration.get()
            if (!shouldCloseCurrent && conn != null && generation == currentGen && conn.isUsable) {
                pruneExpiredPoolsLocked(nowMs, currentGen, staleToClose)
                val deque = idlePools.getOrPut(poolKey) { ArrayDeque() }
                if (deque.size >= maxPoolSize) {
                    staleToClose.add(deque.removeFirst().conn)
                }
                deque.addLast(PooledDohConnection(conn, generation, nowMs))
            } else {
                shouldCloseCurrent = true
            }
        }
        closeAllSafely(staleToClose)
        if (shouldCloseCurrent) {
            if (conn != null) {
                try {
                    conn.close()
                } catch (_: Exception) {
                }
            }
            handle.close()
        }
    }

    fun detachAllForInvalidation(): List<AutoCloseable> {
        val toClose = ArrayList<AutoCloseable>()
        synchronized(poolLock) {
            poolGeneration.incrementAndGet()
            for (deque in idlePools.values) {
                while (deque.isNotEmpty()) {
                    toClose.add(deque.removeFirst().conn)
                }
            }
            idlePools.clear()
            toClose.addAll(activeHandles)
            activeHandles.clear()
        }
        return toClose
    }

    fun closeDetached(toClose: List<AutoCloseable>) {
        if (toClose.isEmpty()) return
        val closeTask = Runnable { closeAllSafely(toClose) }
        val isMainThread = try {
            Looper.myLooper() == Looper.getMainLooper()
        } catch (_: RuntimeException) {
            false
        }
        if (isMainThread) {
            Dispatchers.IO.dispatch(EmptyCoroutineContext, closeTask)
        } else {
            closeTask.run()
        }
    }

    /**
     * Atomically bumps generation and detaches all idle and active sockets under [poolLock].
     * Socket closure is dispatched off Android's Main Thread when invoked from UI/network callbacks.
     */
    fun invalidateAll() {
        closeDetached(detachAllForInvalidation())
    }

    @VisibleForTesting
    internal fun idleCountForTest(poolKey: String): Int = synchronized(poolLock) {
        idlePools[poolKey]?.size ?: 0
    }

    @VisibleForTesting
    internal fun totalIdleCountForTest(): Int = synchronized(poolLock) {
        idlePools.values.sumOf { it.size }
    }

    private fun pruneExpiredPoolsLocked(
        nowMs: Long,
        currentGen: Long,
        staleToClose: MutableList<AutoCloseable>
    ) {
        val iter = idlePools.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val deque = entry.value
            while (deque.isNotEmpty()) {
                val head = deque.first()
                val expired = (nowMs - head.lastUsedMs) >= idleTimeoutMs
                if (head.generation != currentGen || expired || !head.conn.isUsable) {
                    staleToClose.add(deque.removeFirst().conn)
                } else {
                    break
                }
            }
            if (deque.isEmpty()) {
                iter.remove()
            }
        }
    }

    private fun closeAllSafely(items: List<AutoCloseable>) {
        for (item in items) {
            try {
                item.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        internal const val MAX_POOL_SIZE = 4
        internal const val POOL_IDLE_TIMEOUT_MS = 20_000L
    }
}
