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
 * Enforces cumulative read/handshake deadlines and tracks active sockets for immediate reset cancellation.
 */
internal class DohHttpWireExchange(
    private val vpnService: VpnService,
    private val clockMs: () -> Long,
    private val handshakeWatchdog: ScheduledExecutorService,
    private val activeSockets: MutableSet<Socket>
) {

    companion object {
        private const val DOH_PORT = 443
        private const val MAX_HTTP_HEADER_BYTES = 8_192
        private const val MAX_DOH_RESPONSE_BYTES = 4_096
    }

    fun execute(endpoint: DohUpstreamClient.DohEndpoint, rawPayload: ByteArray, timeoutMs: Int): ByteArray {
        var baseSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        val deadlineMs = clockMs() + timeoutMs

        try {
            val raw = Socket()
            baseSocket = raw
            activeSockets.add(raw)
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
            } catch (_: Exception) {
                null
            }
            val connectTimeout = (deadlineMs - clockMs()).toInt().coerceIn(100, timeoutMs)
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
            sslSocket = wrapped
            activeSockets.add(wrapped)

            val sslParams = wrapped.sslParameters
            sslParams.serverNames = listOf(SNIHostName(endpoint.host))
            sslParams.endpointIdentificationAlgorithm = "HTTPS"
            wrapped.sslParameters = sslParams

            val handshakeTimeout = (deadlineMs - clockMs()).toInt().coerceIn(100, timeoutMs)
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

            wrapped.soTimeout = (deadlineMs - clockMs()).toInt().coerceAtLeast(1)

            val outStream = DataOutputStream(wrapped.outputStream)
            val inStream = DataInputStream(wrapped.inputStream)

            val requestHeader = "POST ${endpoint.path} HTTP/1.1\r\n" +
                "Host: ${endpoint.host}\r\n" +
                "User-Agent: AntiPorn-DoH\r\n" +
                "Accept: application/dns-message\r\n" +
                "Content-Type: application/dns-message\r\n" +
                "Content-Length: ${rawPayload.size}\r\n" +
                "Connection: close\r\n\r\n"

            outStream.write(requestHeader.toByteArray(Charsets.US_ASCII))
            outStream.write(rawPayload)
            outStream.flush()

            val statusLine = readLineAscii(wrapped, inStream, deadlineMs)
                ?: throw IOException("Empty HTTP response from DoH server")
            val statusParts = statusLine.split(" ")
            if (statusParts.size < 2 || statusParts[1] != "200") {
                throw IOException("DoH server returned non-200 HTTP status: $statusLine")
            }

            var contentLength = -1
            var isChunked = false
            while (true) {
                val headerLine = readLineAscii(wrapped, inStream, deadlineMs) ?: break
                if (headerLine.isEmpty()) break
                val colonIdx = headerLine.indexOf(':')
                if (colonIdx > 0) {
                    val name = headerLine.substring(0, colonIdx).trim().lowercase()
                    val value = headerLine.substring(colonIdx + 1).trim()
                    when (name) {
                        "content-length" -> contentLength = value.toIntOrNull() ?: -1
                        "transfer-encoding" -> isChunked = value.contains("chunked", ignoreCase = true)
                    }
                }
            }

            val responseBody = when {
                contentLength in 0..MAX_DOH_RESPONSE_BYTES -> {
                    val buf = ByteArray(contentLength)
                    readFullyWithDeadline(wrapped, inStream, buf, deadlineMs)
                    buf
                }
                isChunked -> readChunkedBody(wrapped, inStream, deadlineMs)
                contentLength > MAX_DOH_RESPONSE_BYTES -> {
                    throw IOException("DoH response body too large: $contentLength bytes")
                }
                else -> readBodyUntilEof(wrapped, inStream, deadlineMs)
            }

            if (responseBody.size < DnsPacketParser.DNS_HEADER_LEN) {
                throw IOException("DoH response body too short: ${responseBody.size} bytes")
            }
            if (!DnsWireValidator.isValidDnsResponse(responseBody, responseBody.size, rawPayload)) {
                throw IOException("DoH response failed wire validation for ${endpoint.host}")
            }
            return responseBody
        } finally {
            sslSocket?.let {
                activeSockets.remove(it)
                closeQuietly(it)
            }
            baseSocket?.let {
                activeSockets.remove(it)
                closeQuietly(it)
            }
        }
    }

    private fun readLineAscii(socket: SSLSocket, inStream: DataInputStream, deadlineMs: Long): String? {
        val sb = StringBuilder(64)
        while (sb.length < MAX_HTTP_HEADER_BYTES) {
            val remaining = (deadlineMs - clockMs()).toInt()
            if (remaining <= 0) throw SocketTimeoutException("DoH header read deadline exceeded")
            socket.soTimeout = remaining.coerceAtLeast(1)
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
            if (chunkSize <= 0) {
                readLineAscii(socket, inStream, deadlineMs)
                break
            }
            if (out.size() + chunkSize > MAX_DOH_RESPONSE_BYTES) {
                throw IOException("Chunked DoH body exceeded maximum size")
            }
            val chunkBuf = ByteArray(chunkSize)
            readFullyWithDeadline(socket, inStream, chunkBuf, deadlineMs)
            out.write(chunkBuf)
            readLineAscii(socket, inStream, deadlineMs)
        }
        return out.toByteArray()
    }

    private fun readBodyUntilEof(socket: SSLSocket, inStream: DataInputStream, deadlineMs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (true) {
            val remaining = (deadlineMs - clockMs()).toInt()
            if (remaining <= 0) throw SocketTimeoutException("DoH response read deadline exceeded")
            socket.soTimeout = remaining.coerceAtLeast(1)
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
            socket.soTimeout = remaining.coerceAtLeast(1)
            val n = inStream.read(dst, offset, dst.size - offset)
            if (n == -1) throw EOFException("Unexpected EOF reading DoH body")
            offset += n
        }
    }

    internal fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }
}
