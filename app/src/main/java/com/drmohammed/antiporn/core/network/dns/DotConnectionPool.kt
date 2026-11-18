package com.drmohammed.antiporn.core.network.dns

import android.os.Looper
import kotlinx.coroutines.Dispatchers
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import kotlin.coroutines.EmptyCoroutineContext

internal interface DotConnection : AutoCloseable {
    val isUsable: Boolean
    fun exchange(framedQuery: ByteArray, readTimeoutMs: Int): ByteArray
    override fun close()
}

/**
 * RFC 7858 framed DoT connection over a connected [SSLSocket].
 * Enforces a single unified deadline across the 2-byte length prefix and DNS payload reads
 * so split TLS records or slow/trickling peers cannot multiply or bypass [readTimeoutMs].
 */
internal class SocketDotConnection(
    private val sslSocket: SSLSocket,
    private val clockMs: () -> Long
) : DotConnection {
    private val inStream = sslSocket.inputStream
    private val outStream = sslSocket.outputStream

    override val isUsable: Boolean
        get() = !sslSocket.isClosed &&
            sslSocket.isConnected &&
            !sslSocket.isInputShutdown &&
            !sslSocket.isOutputShutdown

    override fun exchange(framedQuery: ByteArray, readTimeoutMs: Int): ByteArray {
        val boundedTimeout = readTimeoutMs.coerceAtLeast(1)
        val deadlineMs = clockMs() + boundedTimeout
        sslSocket.soTimeout = boundedTimeout
        outStream.write(framedQuery)
        outStream.flush()

        val lenBuf = ByteArray(2)
        readExactWithDeadline(lenBuf, deadlineMs)
        val respLen = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
        if (respLen < DnsPacketParser.DNS_HEADER_LEN || respLen > MAX_DOT_RESPONSE_BYTES) {
            throw IOException("Invalid DoT response length: $respLen")
        }
        val dnsReply = ByteArray(respLen)
        readExactWithDeadline(dnsReply, deadlineMs)
        return dnsReply
    }

    private fun readExactWithDeadline(dest: ByteArray, deadlineMs: Long) {
        var offset = 0
        while (offset < dest.size) {
            val remaining = (deadlineMs - clockMs()).toInt()
            if (remaining <= 0) {
                throw SocketTimeoutException("DoT frame read deadline exceeded")
            }
            sslSocket.soTimeout = remaining
            val bytesRead = inStream.read(dest, offset, dest.size - offset)
            if (bytesRead < 0) {
                throw EOFException("Unexpected EOF while reading DoT response")
            }
            offset += bytesRead
        }
    }

    override fun close() {
        try {
            sslSocket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val MAX_DOT_RESPONSE_BYTES = 4096
    }
}

/**
 * Tracks live socket resources for a single in-flight DoT query so network handoffs
 * can close blocking TCP/TLS sockets immediately without races.
 */
internal class ActiveHandle(val generation: Long) : AutoCloseable {
    @Volatile
    private var closed = false
    private val resources = ArrayList<AutoCloseable>(2)

    fun attach(resource: AutoCloseable): Boolean {
        val shouldCloseImmediately: Boolean
        synchronized(this) {
            if (closed) {
                shouldCloseImmediately = true
            } else {
                resources.add(resource)
                shouldCloseImmediately = false
            }
        }
        if (shouldCloseImmediately) {
            try {
                resource.close()
            } catch (_: Exception) {
            }
            return false
        }
        return true
    }

    override fun close() {
        val toClose: List<AutoCloseable>
        synchronized(this) {
            if (closed) return
            closed = true
            toClose = resources.toList()
            resources.clear()
        }
        for (res in toClose) {
            try {
                res.close()
            } catch (_: Exception) {
            }
        }
    }
}

internal data class BorrowedSlot(
    val conn: DotConnection?,
    val generation: Long,
    val handle: ActiveHandle
)

/**
 * Bounded per-host LIFO TLS connection pool with generation fencing, active-socket
 * tracking, head-expiration pruning, and off-main-thread teardown.
 */
internal class DotConnectionPool(
    private val clockMs: () -> Long,
    private val maxPoolSize: Int = DotUpstreamClient.MAX_POOL_SIZE,
    private val idleTimeoutMs: Long = DotUpstreamClient.POOL_IDLE_TIMEOUT_MS
) {
    private data class PooledDotConnection(
        val conn: DotConnection,
        val generation: Long,
        val lastUsedMs: Long
    )

    private val poolLock = Any()
    private val poolGeneration = AtomicLong(0L)
    private val idlePools = HashMap<String, ArrayDeque<PooledDotConnection>>()
    private val activeHandles = HashSet<ActiveHandle>()

    fun currentGeneration(): Long = poolGeneration.get()

    fun borrowOrPrepareHandle(poolKey: String, nowMs: Long, forceFresh: Boolean): BorrowedSlot {
        val expiredToClose = ArrayList<DotConnection>()
        val result: BorrowedSlot
        synchronized(poolLock) {
            val currentGen = poolGeneration.get()
            var reused: DotConnection? = null
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
            result = BorrowedSlot(reused, currentGen, handle)
        }
        closeAllSafely(expiredToClose)
        return result
    }

    fun releaseConnection(
        poolKey: String,
        conn: DotConnection?,
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
                deque.addLast(PooledDotConnection(conn, generation, nowMs))
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
     * Socket closure is dispatched off Android's Main Thread when invoked from UI callbacks
     * (e.g. `VpnService.onRevoke()` or `Service.onDestroy()`).
     */
    fun invalidateAll() {
        closeDetached(detachAllForInvalidation())
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
}
