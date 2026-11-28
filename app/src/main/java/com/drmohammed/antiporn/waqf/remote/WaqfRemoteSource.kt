package com.drmohammed.antiporn.waqf.remote

import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.BuildConfig
import com.drmohammed.antiporn.waqf.model.WaqfActionValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Remote data source for fetching `waqf_config.json` from Cloudflare R2 distribution.
 *
 * Uses strict tight timeouts (3s connect / 3.5s read) and bounded response streaming on
 * [Dispatchers.IO] so network hiccups never block or exhaust memory.
 */
object WaqfRemoteSource {

    private const val TAG = "AntiPorn_WaqfRemote"

    const val CONNECT_TIMEOUT_MS = 3_000
    const val READ_TIMEOUT_MS = 3_500
    const val MAX_RESPONSE_BYTES = 256 * 1024 // 256 KB safety limit
    private const val BUFFER_SIZE = 8 * 1024

    val DEFAULT_WAQF_CONFIG_URL: String
        get() = "${BuildConfig.OTA_BASE_URL.trimEnd('/')}/waqf_config.json"

    @VisibleForTesting
    @Volatile
    internal var fetchJsonOverride: (suspend (String) -> Result<String>)? = null

    /**
     * Fetches the raw `waqf_config.json` payload from [url].
     * Enforces HTTPS, HTTP 200, tight timeouts, and a 256 KB maximum payload size.
     */
    suspend fun fetchWaqfConfigJson(url: String = DEFAULT_WAQF_CONFIG_URL): Result<String> =
        withContext(Dispatchers.IO) {
            fetchJsonOverride?.let { override ->
                return@withContext try {
                    override(url)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Result.failure(t)
                }
            }

            if (!WaqfActionValidator.isSafeHttpsUrl(url)) {
                return@withContext Result.failure(
                    IOException("Rejected non-HTTPS or invalid Waqf config URL: $url")
                )
            }

            var connection: HttpURLConnection? = null
            try {
                val parsedUri = URI(url.trim())
                connection = (parsedUri.toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("User-Agent", "Hisn-Waqf-Client")
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val errorMsg = "HTTP $responseCode: ${connection.responseMessage}"
                    Timber.tag(TAG).w("Waqf config fetch failed: %s", errorMsg)
                    return@withContext Result.failure(IOException("Failed to fetch Waqf config ($errorMsg)"))
                }

                val declaredLength = connection.contentLengthLong
                if (declaredLength > MAX_RESPONSE_BYTES) {
                    return@withContext Result.failure(
                        IOException("Waqf config payload exceeds max size ($declaredLength bytes)")
                    )
                }

                val payloadBytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var totalRead = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        totalRead += bytesRead
                        if (totalRead > MAX_RESPONSE_BYTES) {
                            throw IOException("Waqf config payload exceeded max size ($MAX_RESPONSE_BYTES bytes)")
                        }
                        output.write(buffer, 0, bytesRead)
                    }
                    output.toByteArray()
                }

                val jsonText = String(payloadBytes, Charsets.UTF_8)
                if (jsonText.isBlank()) {
                    return@withContext Result.failure(IOException("Empty Waqf config response from $url"))
                }

                Result.success(jsonText)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Error fetching Waqf config from %s", url)
                Result.failure(t)
            } finally {
                try {
                    connection?.disconnect()
                } catch (ignored: Exception) {
                    Timber.tag(TAG).w(ignored, "Error disconnecting Waqf HTTP connection")
                }
            }
        }
}
