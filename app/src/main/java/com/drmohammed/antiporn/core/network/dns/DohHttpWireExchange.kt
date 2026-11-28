package com.drmohammed.antiporn.core.network.dns

import android.net.VpnService
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Low-level RFC 8484 HTTP/1.1-over-TLS wire exchange helper for [DohUpstreamClient].
 * Uses [DohConnectionPool] for HTTP/1.1 keep-alive connection reuse, enforces cumulative
 * read/handshake deadlines, and retries once on a fresh socket if a reused connection hits EOF/I/O error.
 */
internal class DohHttpWireExchange(
    private val vpnService: VpnService,
    private val clockMs: () -> Long,
    private val handshakeWatchdog: ScheduledExecutorService,
    private val pool: DohConnectionPool,
    private val connectionFactoryProvider: (() -> ((DohUpstreamClient.DohEndpoint, Int) -> DohSocketConnection)?)? = null
) {

    companion object {
        private const val TAG = "AntiPorn_DohWire"
        private const val DOH_PORT = 443
        private const val MAX_HTTP_HEADER_BYTES = 8_192
        private const val MAX_DOH_RESPONSE_BYTES = 4_096
        private const val MIN_RETRY_BUDGET_MS = 250L
    }

    fun execute(endpoint: DohUpstreamClient.DohEndpoint, rawPayload: ByteArray, timeoutMs: Int): ByteArray {
        val deadlineMs = clockMs() + timeoutMs.coerceAtLeast(1)
        val poolKey = pool.poolKey(endpoint)
        var forceFresh = false

        while (true) {
            val borrowed = pool.borrowOrPrepareHandle(poolKey, clockMs(), forceFresh)
            val wasReused = borrowed.conn != null
            var conn: DohSocketConnection? = borrowed.conn
            val generation = borrowed.generation
            val handle = borrowed.handle
            var keepAlive = false

            try {
                val activeConn = conn ?: openConnection(endpoint, deadlineMs, handle).also {
                    conn = it
                }

                val remainingBeforeExchange = (deadlineMs - clockMs()).toInt()
                if (remainingBeforeExchange <= 0) {
                    throw SocketTimeoutException("DoH deadline exceeded before exchange")
                }
                activeConn.setReadTimeout(remainingBeforeExchange)

                val requestHeader = "POST ${endpoint.path} HTTP/1.1\r\n" +
                    "Host: ${endpoint.host}\r\n" +
                    "User-Agent: AntiPorn-DoH\r\n" +
                    "Accept: application/dns-message\r\n" +
                    "Content-Type: application/dns-message\r\n" +
                    "Content-Length: ${rawPayload.size}\r\n" +
                    "Connection: keep-alive\r\n\r\n"

                activeConn.outStream.write(requestHeader.toByteArray(Charsets.US_ASCII))
                activeConn.outStream.write(rawPayload)
                activeConn.outStream.flush()

                val statusLine = readLineAscii(activeConn.ssl, activeConn.inStream, deadlineMs)
                    ?: throw EOFException("Empty HTTP response from DoH server")
                val statusParts = statusLine.split(" ")
                if (statusParts.size < 2 || statusParts[1] != "200") {
                    throw IOException("DoH server returned non-200 HTTP status: $statusLine")
                }

                var contentLength: Int? = null
                var invalidContentLength = false
                var isChunked = false
                var serverRequestedClose = false
                while (true) {
                    val headerLine = readLineAscii(activeConn.ssl, activeConn.inStream, deadlineMs)
                        ?: throw EOFException("Unexpected EOF reading DoH HTTP headers")
                    if (headerLine.isEmpty()) break
                    val colonIdx = headerLine.indexOf(':')
                    if (colonIdx > 0) {
                        val name = headerLine.substring(0, colonIdx).trim().lowercase()
                        val value = headerLine.substring(colonIdx + 1).trim()
                        when (name) {
                            "content-length" -> {
                                val parsed = value.toIntOrNull()
                                if (parsed == null || parsed < 0 || (contentLength != null && contentLength != parsed)) {
                                    invalidContentLength = true
                                } else {
                                    contentLength = parsed
                                }
                            }
                            "transfer-encoding" -> isChunked = value.contains("chunked", ignoreCase = true)
                            "connection" -> if (value.contains("close", ignoreCase = true)) {
                                serverRequestedClose = true
                            }
                        }
                    }
                }
                if (invalidContentLength) {
                    throw IOException("Invalid DoH Content-Length header")
                }
                if (isChunked && contentLength != null) {
                    throw IOException("Ambiguous DoH response framing (chunked + Content-Length)")
                }

                var streamFramingAllowsReuse = true
                val cl = contentLength
                val responseBody = when {
                    isChunked -> readChunkedBody(activeConn.ssl, activeConn.inStream, deadlineMs)
                    cl != null && cl > MAX_DOH_RESPONSE_BYTES -> {
                        throw IOException("DoH response body too large: $cl bytes")
                    }
                    cl != null -> {
                        val buf = ByteArray(cl)
                        readFullyWithDeadline(activeConn.ssl, activeConn.inStream, buf, deadlineMs)
                        buf
                    }
                    else -> {
                        streamFramingAllowsReuse = false
                        readBodyUntilEof(activeConn.ssl, activeConn.inStream, deadlineMs)
                    }
                }

                if (responseBody.size < DnsPacketParser.DNS_HEADER_LEN) {
                    throw IOException("DoH response body too short: ${responseBody.size} bytes")
                }
                if (!DnsWireValidator.isValidDnsResponse(responseBody, responseBody.size, rawPayload)) {
                    throw IOException("DoH response failed wire validation for ${endpoint.host}")
                }
                keepAlive = streamFramingAllowsReuse && !serverRequestedClose
                return responseBody
            } catch (e: IOException) {
                val isCurrentGen = generation == pool.currentGeneration()
                val remainingAfterError = deadlineMs - clockMs()
                if (isCurrentGen && wasReused && !forceFresh && remainingAfterError >= MIN_RETRY_BUDGET_MS) {
                    forceFresh = true
                } else {
                    throw e
                }
            } finally {
                pool.releaseConnection(poolKey, conn, generation, handle, keepAlive)
            }
        }
    }

    private fun openConnection(
        endpoint: DohUpstreamClient.DohEndpoint,
        deadlineMs: Long,
        activeHandle: ActiveHandle
    ): DohSocketConnection {
        val customFactory = connectionFactoryProvider?.invoke()
        if (customFactory != null) {
            val remaining = (deadlineMs - clockMs()).toInt()
            if (remaining <= 0) {
                throw SocketTimeoutException("DoH deadline exceeded before connect")
            }
            val customConn = customFactory.invoke(endpoint, remaining)
            if (!activeHandle.attach(customConn)) {
                throw IOException("DoH connection invalidated by network change")
            }
            return customConn
        }
        return openProductionConnection(endpoint, deadlineMs, activeHandle)
    }

    private fun openProductionConnection(
        endpoint: DohUpstreamClient.DohEndpoint,
        deadlineMs: Long,
        activeHandle: ActiveHandle
    ): DohSocketConnection {
        val connectTimeout = (deadlineMs - clockMs()).toInt()
        if (connectTimeout <= 0) {
            throw SocketTimeoutException("DoH deadline exceeded before connect")
        }

        val raw = Socket()
        if (!activeHandle.attach(raw)) {
            throw IOException("DoH TCP socket invalidated before connect")
        }
        raw.tcpNoDelay = true
        try {
            raw.bind(null)
        } catch (_: Exception) {
            // Best-effort pre-protect bind; some OEMs do not require explicit bind before protect()
        }
        if (!vpnService.protect(raw)) {
            throw IOException("VpnService.protect() failed for DoH socket to ${endpoint.ip}")
        }

        val resolvedIp = try {
            InetAddress.getByName(endpoint.ip)
        } catch (e: Exception) {
            timber.log.Timber.tag(TAG).d(e, "Failed parsing endpoint IP ${endpoint.ip}; falling back to InetSocketAddress(host, port)")
            null
        }
        raw.soTimeout = connectTimeout
        val targetAddr = if (resolvedIp != null) {
            InetSocketAddress(resolvedIp, DOH_PORT)
        } else {
            InetSocketAddress(endpoint.ip, DOH_PORT)
        }
        raw.connect(targetAddr, connectTimeout)

        val sslFactory = SSLSocketFactory.getDefault() as? SSLSocketFactory
            ?: throw IOException("Default SSLSocketFactory unavailable")
        val wrapped = sslFactory.createSocket(raw, endpoint.host, DOH_PORT, true) as? SSLSocket
            ?: throw IOException("Failed to wrap socket with SSLSocket")
        if (!activeHandle.attach(wrapped)) {
            throw IOException("DoH TLS socket invalidated before handshake")
        }

        val sslParams = wrapped.sslParameters
        sslParams.serverNames = listOf(SNIHostName(endpoint.host))
        sslParams.endpointIdentificationAlgorithm = "HTTPS"
        wrapped.sslParameters = sslParams

        val handshakeTimeout = (deadlineMs - clockMs()).toInt()
        if (handshakeTimeout <= 0) {
            throw SocketTimeoutException("DoH deadline exceeded before TLS handshake")
        }
        wrapped.soTimeout = handshakeTimeout

        val handshakeDeadlineTask = handshakeWatchdog.schedule(
            {
                closeQuietly(wrapped)
                closeQuietly(raw)
            },
            handshakeTimeout.toLong(),
            TimeUnit.MILLISECONDS
        )
        try {
            wrapped.startHandshake()
        } finally {
            handshakeDeadlineTask.cancel(false)
        }

        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(endpoint.host, wrapped.session)) {
            throw IOException("TLS certificate hostname verification failed for ${endpoint.host}")
        }

        return DohSocketConnection(
            raw = raw,
            ssl = wrapped,
            outStream = DataOutputStream(wrapped.outputStream),
            inStream = DataInputStream(wrapped.inputStream)
        )
    }

    private fun readLineAscii(socket: SSLSocket, inStream: DataInputStream, deadlineMs: Long): String? {
        val sb = StringBuilder(64)
        while (sb.length < MAX_HTTP_HEADER_BYTES) {
            val remaining = (deadlineMs - clockMs()).toInt()
            if (remaining <= 0) throw SocketTimeoutException("DoH header read deadline exceeded")
            setSoTimeoutQuietly(socket, remaining)
            val b = inStream.read()
            if (b == -1) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            val c = b.toChar()
            if (c == '\n') {
                return sb.toString().trimEnd('\r')
            }
            sb.append(c)
        }
        throw IOException("HTTP header line exceeded maximum length")
    }

    private fun readChunkedBody(socket: SSLSocket, inStream: DataInputStream, deadlineMs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val hexLenStr = readLineAscii(socket, inStream, deadlineMs)
                ?: throw EOFException("Unexpected EOF reading chunk length")
            val cleanHex = hexLenStr.substringBefore(';').trim()
            val chunkSize = cleanHex.toIntOrNull(16) ?: throw IOException("Invalid chunk length: $hexLenStr")
            if (chunkSize < 0) {
                throw IOException("Negative chunk length: $chunkSize")
            }
            if (chunkSize == 0) {
                while (true) {
                    val trailerLine = readLineAscii(socket, inStream, deadlineMs)
                        ?: throw EOFException("Unexpected EOF reading chunked trailers")
                    if (trailerLine.isEmpty()) break
                }
                break
            }
            if (chunkSize > MAX_DOH_RESPONSE_BYTES - out.size()) {
                throw IOException("Chunked DoH body exceeded maximum size")
            }
            val chunkBuf = ByteArray(chunkSize)
            readFullyWithDeadline(socket, inStream, chunkBuf, deadlineMs)
            out.write(chunkBuf)
            val chunkTerminator = readLineAscii(socket, inStream, deadlineMs)
                ?: throw EOFException("Unexpected EOF after chunk payload")
            if (chunkTerminator.isNotEmpty()) {
                throw IOException("Malformed chunk terminator")
            }
        }
        return out.toByteArray()
    }

    private fun readBodyUntilEof(socket: SSLSocket, inStream: DataInputStream, deadlineMs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (true) {
            val remaining = (deadlineMs - clockMs()).toInt()
            if (remaining <= 0) throw SocketTimeoutException("DoH response read deadline exceeded")
            setSoTimeoutQuietly(socket, remaining)
            val count = inStream.read(buf)
            if (count == -1) break
            if (out.size() + count > MAX_DOH_RESPONSE_BYTES) {
                throw IOException("DoH response body exceeded maximum size ($MAX_DOH_RESPONSE_BYTES bytes)")
            }
            out.write(buf, 0, count)
        }
        return out.toByteArray()
    }

    private fun readFullyWithDeadline(socket: SSLSocket, inStream: DataInputStream, dst: ByteArray, deadlineMs: Long) {
        var offset = 0
        while (offset < dst.size) {
            val remaining = (deadlineMs - clockMs()).toInt()
            if (remaining <= 0) throw SocketTimeoutException("DoH body read deadline exceeded")
            setSoTimeoutQuietly(socket, remaining)
            val n = inStream.read(dst, offset, dst.size - offset)
            if (n == -1) throw EOFException("Unexpected EOF reading DoH body")
            offset += n
        }
    }

    private fun setSoTimeoutQuietly(socket: SSLSocket, timeoutMs: Int) {
        try {
            socket.soTimeout = timeoutMs.coerceAtLeast(1)
        } catch (_: Exception) {
        }
    }

    internal fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }
}
