package com.example.data.network

import com.example.data.model.LinkInspectionResult
import com.example.data.model.StreamCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class LinkInspector {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "m4v", "avi", "3gp", "flv", "ts"
        )

        private val URL_IN_TEXT_REGEX = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        private val TITLE_REGEX = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val OG_TITLE_REGEX = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val META_VIDEO_REGEX = Regex(
            """<meta[^>]+(?:property|name)=["'](?:og:video(?::url|:secure_url)?|twitter:player:stream)["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        private val META_VIDEO_REV_REGEX = Regex(
            """<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["'](?:og:video(?::url|:secure_url)?|twitter:player:stream)["']""",
            RegexOption.IGNORE_CASE
        )
        private val VIDEO_SRC_REGEX = Regex(
            """<(?:video|source)[^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        private val JSON_CONTENT_URL_REGEX = Regex(
            """"(?:contentUrl|videoUrl|file|src|url)"\s*:\s*"(https?:[^"]+\.(?:mp4|mkv|webm|mov|m4v|m3u8)[^"]*)"""",
            RegexOption.IGNORE_CASE
        )
        private val DIRECT_MEDIA_IN_HTML_REGEX = Regex(
            """(https?://[^\s"'<>\\]+\.(?:mp4|mkv|webm|mov|m4v|m3u8)(?:\?[^\s"'<>\\]*)?)""",
            RegexOption.IGNORE_CASE
        )
    }

    fun normalizeUrl(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        val extracted = URL_IN_TEXT_REGEX.find(trimmed)?.value ?: trimmed
        val candidate = if (
            extracted.startsWith("http://", ignoreCase = true) ||
            extracted.startsWith("https://", ignoreCase = true)
        ) {
            extracted
        } else if (!extracted.contains("://") && extracted.contains(".")) {
            "https://$extracted"
        } else {
            return null
        }

        val httpUrl = candidate.toHttpUrlOrNull() ?: return null
        return httpUrl.toString()
    }

    suspend fun inspect(rawInput: String, customTitle: String? = null): LinkInspectionResult =
        withContext(Dispatchers.IO) {
            val normalizedUrl = normalizeUrl(rawInput)
                ?: return@withContext LinkInspectionResult.Error("გთხოვთ შეიყვანოთ სწორი http:// ან https:// ლინკი")

            try {
                probeUrl(normalizedUrl, customTitle)
            } catch (e: IOException) {
                LinkInspectionResult.Error("კავშირის შეცდომა: ${e.localizedMessage ?: "სერვერთან დაკავშირება ვერ მოხერხდა"}")
            } catch (e: Exception) {
                LinkInspectionResult.Error("ლინკის შემოწმება ვერ მოხერხდა: ${e.localizedMessage ?: "უცნობი შეცდომა"}")
            }
        }

    private fun probeUrl(url: String, customTitle: String?): LinkInspectionResult {
        val headReq = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(headReq).execute().use { response ->
            if (response.isSuccessful) {
                val handled = evaluateHeaders(response, customTitle)
                if (handled != null) return handled
            }
        }

        val getReq = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()

        client.newCall(getReq).execute().use { response ->
            if (!response.isSuccessful) {
                return LinkInspectionResult.Error("სერვერმა დააბრუნა კოდი ${response.code}")
            }

            val finalUrl = response.request.url.toString()
            val contentType = response.header("Content-Type")?.lowercase(Locale.US).orEmpty()
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
            val acceptRanges = response.header("Accept-Ranges")?.lowercase(Locale.US) == "bytes"
            val disposition = response.header("Content-Disposition")

            if (isHls(finalUrl, contentType)) {
                val bodyText = readLimitedBody(response, 512 * 1024)
                return parseHlsPlaylist(finalUrl, bodyText, customTitle)
            }

            if (isDirectVideo(finalUrl, contentType, disposition)) {
                val fileName = deriveFileName(finalUrl, disposition, contentType)
                val format = deriveFormatLabel(fileName, contentType)
                return LinkInspectionResult.DirectMedia(
                    StreamCandidate(
                        url = finalUrl,
                        title = customTitle?.takeIf { it.isNotBlank() } ?: cleanTitleFromFilename(fileName),
                        mimeType = normalizeVideoMime(contentType, fileName),
                        formatLabel = format,
                        fileSizeBytes = contentLength,
                        isHls = false,
                        supportsResume = acceptRanges
                    )
                )
            }

            if (contentType.contains("text/html") || contentType.contains("xhtml") || contentType.isBlank()) {
                val html = readLimitedBody(response, 1_500_000)
                return parseHtmlForVideos(response.request.url, html, customTitle)
            }

            return LinkInspectionResult.UnsupportedOrProtected(
                "ეს ლინკი აბრუნებს '$contentType' ტიპს და არ არის პირდაპირი ვიდეო ფაილი."
            )
        }
    }

    private fun evaluateHeaders(response: Response, customTitle: String?): LinkInspectionResult? {
        val finalUrl = response.request.url.toString()
        val contentType = response.header("Content-Type")?.lowercase(Locale.US).orEmpty()
        val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
        val acceptRanges = response.header("Accept-Ranges")?.lowercase(Locale.US) == "bytes"
        val disposition = response.header("Content-Disposition")

        if (isHls(finalUrl, contentType)) {
            return null // Need GET to parse m3u8 content
        }
        if (contentType.contains("text/html") || contentType.contains("xhtml")) {
            return null // Need GET to inspect HTML
        }
        if (isDirectVideo(finalUrl, contentType, disposition)) {
            val fileName = deriveFileName(finalUrl, disposition, contentType)
            val format = deriveFormatLabel(fileName, contentType)
            return LinkInspectionResult.DirectMedia(
                StreamCandidate(
                    url = finalUrl,
                    title = customTitle?.takeIf { it.isNotBlank() } ?: cleanTitleFromFilename(fileName),
                    mimeType = normalizeVideoMime(contentType, fileName),
                    formatLabel = format,
                    fileSizeBytes = contentLength,
                    isHls = false,
                    supportsResume = acceptRanges
                )
            )
        }
        return null
    }

    private fun parseHlsPlaylist(
        playlistUrl: String,
        body: String,
        customTitle: String?
    ): LinkInspectionResult {
        if (!body.contains("#EXTM3U")) {
            return LinkInspectionResult.UnsupportedOrProtected("M3U8 ფაილი არასწორი ფორმატისაა ან დაცულია.")
        }
        if (body.contains("METHOD=SAMPLE-AES", ignoreCase = true) || body.contains("skd://", ignoreCase = true)) {
            return LinkInspectionResult.UnsupportedOrProtected(
                "ეს HLS ნაკადი დაშიფრულია DRM-ით (SAMPLE-AES/FairPlay) და მისი გადმოწერა შეზღუდულია."
            )
        }

        val baseHttpUrl = playlistUrl.toHttpUrlOrNull()
        val baseTitle = customTitle?.takeIf { it.isNotBlank() }
            ?: cleanTitleFromFilename(deriveFileName(playlistUrl, null, "application/vnd.apple.mpegurl"))

        if (body.contains("#EXT-X-STREAM-INF")) {
            val lines = body.lines()
            val variants = mutableListOf<StreamCandidate>()
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val nextLine = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
                        ?: continue
                    val resolved = baseHttpUrl?.resolve(nextLine)?.toString() ?: nextLine
                    val resolution = Regex("""RESOLUTION=(\d+x\d+)""").find(line)?.groupValues?.get(1)
                    val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull()
                    val qualityLabel = resolution?.let {
                        val height = it.substringAfter("x")
                        "${height}p"
                    } ?: bandwidth?.let { "${it / 1000} kbps" } ?: "Auto"

                    variants.add(
                        StreamCandidate(
                            url = resolved,
                            title = "$baseTitle ($qualityLabel)",
                            mimeType = "video/mp2t",
                            formatLabel = "HLS $qualityLabel",
                            resolutionLabel = qualityLabel,
                            isHls = true,
                            supportsResume = true
                        )
                    )
                }
            }
            if (variants.isNotEmpty()) {
                return LinkInspectionResult.HlsMaster(
                    masterUrl = playlistUrl,
                    title = baseTitle,
                    variants = variants.distinctBy { it.url }
                )
            }
        }

        return LinkInspectionResult.DirectMedia(
            StreamCandidate(
                url = playlistUrl,
                title = baseTitle,
                mimeType = "video/mp2t",
                formatLabel = "HLS",
                isHls = true,
                supportsResume = true
            )
        )
    }

    private fun parseHtmlForVideos(
        pageUrl: HttpUrl,
        html: String,
        customTitle: String?
    ): LinkInspectionResult {
        val pageTitle = customTitle?.takeIf { it.isNotBlank() }
            ?: OG_TITLE_REGEX.find(html)?.groupValues?.getOrNull(1)?.decodeHtmlEntities()?.trim()
            ?: TITLE_REGEX.find(html)?.groupValues?.getOrNull(1)?.decodeHtmlEntities()?.trim()
            ?: pageUrl.host

        val foundRawUrls = linkedSetOf<String>()

        fun addMatch(raw: String?) {
            val cleaned = raw?.trim()
                ?.replace("\\/", "/")
                ?.replace("&amp;", "&")
                ?: return
            if (cleaned.startsWith("blob:", ignoreCase = true) || cleaned.startsWith("data:", ignoreCase = true)) {
                return
            }
            val resolved = pageUrl.resolve(cleaned)?.toString() ?: return
            foundRawUrls.add(resolved)
        }

        META_VIDEO_REGEX.findAll(html).forEach { addMatch(it.groupValues.getOrNull(1)) }
        META_VIDEO_REV_REGEX.findAll(html).forEach { addMatch(it.groupValues.getOrNull(1)) }
        VIDEO_SRC_REGEX.findAll(html).forEach { addMatch(it.groupValues.getOrNull(1)) }
        JSON_CONTENT_URL_REGEX.findAll(html).forEach { addMatch(it.groupValues.getOrNull(1)) }
        DIRECT_MEDIA_IN_HTML_REGEX.findAll(html).forEach { addMatch(it.groupValues.getOrNull(1)) }

        val candidates = foundRawUrls
            .filter { url ->
                val lower = url.lowercase(Locale.US)
                !lower.endsWith(".jpg") && !lower.endsWith(".png") && !lower.endsWith(".webp") && !lower.endsWith(".css") && !lower.endsWith(".js")
            }
            .take(12)
            .map { url ->
                val isHlsStream = url.lowercase(Locale.US).contains(".m3u8")
                val fileName = deriveFileName(url, null, if (isHlsStream) "application/vnd.apple.mpegurl" else "video/mp4")
                val format = if (isHlsStream) "HLS" else deriveFormatLabel(fileName, "")
                StreamCandidate(
                    url = url,
                    title = pageTitle,
                    mimeType = if (isHlsStream) "video/mp2t" else normalizeVideoMime("", fileName),
                    formatLabel = format,
                    isHls = isHlsStream,
                    supportsResume = true
                )
            }

        if (candidates.isEmpty()) {
            return LinkInspectionResult.UnsupportedOrProtected(
                "ამ გვერდზე პირდაპირი ვიდეო ფაილი ვერ მოიძებნა. შესაძლოა პლეერი იტვირთება JavaScript-ით ან დაცულია DRM-ით. სცადეთ პირდაპირი .mp4 / .mkv / .m3u8 ლინკი."
            )
        }

        return LinkInspectionResult.ExtractedStreams(
            pageTitle = pageTitle,
            pageUrl = pageUrl.toString(),
            streams = candidates
        )
    }

    private fun readLimitedBody(response: Response, maxBytes: Int): String {
        val body = response.body ?: return ""
        val source = body.source()
        source.request(maxBytes.toLong())
        val buffer = source.buffer.clone()
        val count = minOf(buffer.size, maxBytes.toLong())
        return buffer.readString(count, Charsets.UTF_8)
    }

    private fun isHls(url: String, contentType: String): Boolean {
        val path = url.substringBefore("?").lowercase(Locale.US)
        return path.endsWith(".m3u8") ||
            contentType.contains("mpegurl") ||
            contentType.contains("vnd.apple.mpegurl")
    }

    private fun isDirectVideo(url: String, contentType: String, disposition: String?): Boolean {
        if (contentType.startsWith("video/")) return true
        val ext = extractExtension(url, disposition)
        if (ext in VIDEO_EXTENSIONS) return true
        if (contentType.contains("application/octet-stream") || contentType.contains("binary/octet-stream")) {
            return ext.isEmpty() || ext in VIDEO_EXTENSIONS
        }
        return false
    }

    private fun extractExtension(url: String, disposition: String?): String {
        disposition?.let {
            val match = Regex("""filename\*?=(?:UTF-8'')?["']?([^"';]+)["']?""", RegexOption.IGNORE_CASE).find(it)
            val dispName = match?.groupValues?.getOrNull(1)
            val dispExt = dispName?.substringAfterLast('.', "")?.lowercase(Locale.US)
            if (!dispExt.isNullOrBlank()) return dispExt
        }
        val cleanPath = url.substringBefore("?").substringBefore("#")
        val lastSegment = cleanPath.substringAfterLast('/', "")
        return if (lastSegment.contains('.')) {
            lastSegment.substringAfterLast('.', "").lowercase(Locale.US)
        } else {
            ""
        }
    }

    fun deriveFileName(url: String, disposition: String?, contentType: String): String {
        disposition?.let {
            val match = Regex("""filename\*?=(?:UTF-8'')?["']?([^"';]+)["']?""", RegexOption.IGNORE_CASE).find(it)
            val raw = match?.groupValues?.getOrNull(1)?.trim()
            if (!raw.isNullOrBlank()) {
                return sanitizeFileName(URLDecoder.decode(raw, "UTF-8"))
            }
        }

        val cleanPath = url.substringBefore("?").substringBefore("#")
        var segment = cleanPath.substringAfterLast('/', "").trim()
        segment = runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)

        if (segment.isBlank() || !segment.contains('.')) {
            val ext = when {
                contentType.contains("webm") -> "webm"
                contentType.contains("matroska") || contentType.contains("mkv") -> "mkv"
                contentType.contains("quicktime") -> "mov"
                contentType.contains("mpegurl") -> "ts"
                else -> "mp4"
            }
            val base = segment.ifBlank { "video_${System.currentTimeMillis() / 1000}" }
            return sanitizeFileName("$base.$ext")
        }
        return sanitizeFileName(segment)
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)
    }

    private fun cleanTitleFromFilename(fileName: String): String {
        return fileName.substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .ifBlank { "ვიდეო" }
    }

    private fun deriveFormatLabel(fileName: String, contentType: String): String {
        val ext = fileName.substringAfterLast('.', "").uppercase(Locale.US)
        if (ext in setOf("MP4", "MKV", "WEBM", "MOV", "M4V", "AVI", "3GP", "TS")) return ext
        return when {
            contentType.contains("webm") -> "WEBM"
            contentType.contains("matroska") -> "MKV"
            contentType.contains("quicktime") -> "MOV"
            else -> "MP4"
        }
    }

    private fun normalizeVideoMime(contentType: String, fileName: String): String {
        if (contentType.startsWith("video/")) return contentType.substringBefore(";")
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            "ts" -> "video/mp2t"
            else -> "video/mp4"
        }
    }

    private fun String.decodeHtmlEntities(): String {
        return this
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
