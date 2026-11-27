package com.papershift.platform

import com.papershift.core.BingImage
import com.papershift.core.BingPort
import com.papershift.core.BingRules
import com.papershift.core.TransientFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Bing archive + image download (PRD §5.3). The client has no Cache, so nothing is written
 * to disk; image bytes live only in memory (PRD §4.1).
 */
class BingClient(
    private val http: OkHttpClient = defaultClient(),
) : BingPort {

    override suspend fun fetchArchive(market: String): List<BingImage> = withContext(Dispatchers.IO) {
        val body = get(BingRules.archiveUrl(market)) { it.toString(Charsets.UTF_8) }
        try {
            parseArchive(body)
        } catch (e: JSONException) {
            throw TransientFailure("Unexpected Bing JSON", e) // EC-05
        }
    }

    override suspend fun download(image: BingImage): ByteArray = withContext(Dispatchers.IO) {
        var last: TransientFailure? = null
        for (url in BingRules.imageUrls(image)) {
            try {
                return@withContext get(url) { it }
            } catch (e: TransientFailure) {
                last = e
            }
        }
        throw last ?: TransientFailure("No image URL for ${image.startDate}")
    }

    /** GETs [url] fully into memory, verifying the length against Content-Length (EC-06). */
    private fun <T> get(url: String, convert: (ByteArray) -> T): T {
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw TransientFailure("HTTP ${response.code} for $url")
                val body = response.body
                val expected = body.contentLength()
                if (expected > MAX_BYTES) throw TransientFailure("Response too large: $expected bytes")
                val out = ByteArrayOutputStream(if (expected > 0) expected.toInt() else DEFAULT_BUFFER)
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw TransientFailure("Response exceeded $MAX_BYTES bytes")
                        out.write(buffer, 0, read)
                    }
                    if (expected >= 0 && total != expected) {
                        throw TransientFailure("Truncated download: $total of $expected bytes")
                    }
                }
                return convert(out.toByteArray())
            }
        } catch (e: IOException) {
            throw TransientFailure("Network error for $url", e) // EC-04
        }
    }

    companion object {
        /** PRD §4.1: image buffers are capped at 25 MB. */
        const val MAX_BYTES = 25L * 1024 * 1024
        private const val DEFAULT_BUFFER = 64 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Parses the HPImageArchive JSON, newest first. Images missing required fields are skipped. */
        fun parseArchive(json: String): List<BingImage> {
            val images = JSONObject(json).getJSONArray("images")
            return buildList {
                for (i in 0 until images.length()) {
                    val o = images.getJSONObject(i)
                    val startDate = o.optString("startdate")
                    val urlBase = o.optString("urlbase")
                    if (startDate.isBlank() || urlBase.isBlank()) continue
                    add(
                        BingImage(
                            startDate = startDate,
                            urlBase = urlBase,
                            copyright = o.optString("copyright"),
                            copyrightLink = o.optString("copyrightlink").takeIf { it.startsWith("https://") },
                        ),
                    )
                }
            }
        }
    }
}
