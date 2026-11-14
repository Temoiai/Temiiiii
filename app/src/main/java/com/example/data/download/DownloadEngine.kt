package com.example.data.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.example.data.local.DownloadDao
import com.example.data.model.DownloadItem
import com.example.data.model.DownloadStatus
import com.example.data.model.StreamCandidate
import com.example.data.network.LinkInspector
import com.example.data.preferences.UserPreferencesRepository
import com.example.service.DownloadForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadEngine(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val preferencesRepository: UserPreferencesRepository,
    private val linkInspector: LinkInspector
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<Long, Job>()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val downloadsDir: File by lazy {
        File(context.filesDir, "downloads").apply { mkdirs() }
    }

    init {
        scope.launch {
            downloadDao.pauseInterruptedDownloads()
        }
    }

    suspend fun enqueueDownload(candidate: StreamCandidate, customTitle: String? = null): Long {
        val rawFileName = linkInspector.deriveFileName(candidate.url, null, candidate.mimeType)
        val safeTitle = customTitle?.takeIf { it.isNotBlank() } ?: candidate.title
        val finalFileName = buildSafeTargetName(safeTitle, rawFileName, candidate.isHls)

        val item = DownloadItem(
            title = safeTitle,
            sourceUrl = candidate.url,
            resolvedUrl = candidate.url,
            fileName = finalFileName,
            mimeType = candidate.mimeType,
            isHls = candidate.isHls,
            status = DownloadStatus.QUEUED,
            totalBytes = candidate.fileSizeBytes,
            supportsResume = candidate.supportsResume
        )
        val id = downloadDao.insert(item)
        startOrQueueJob(id)
        return id
    }

    suspend fun saveLinkOnly(url: String, title: String?): Long {
        val fileName = linkInspector.deriveFileName(url, null, "video/mp4")
        val resolvedTitle = title?.takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.').ifBlank { url }
        val item = DownloadItem(
            title = resolvedTitle,
            sourceUrl = url,
            resolvedUrl = url,
            fileName = fileName,
            mimeType = "video/mp4",
            isHls = url.lowercase(Locale.US).contains(".m3u8"),
            status = DownloadStatus.SAVED
        )
        return downloadDao.insert(item)
    }

    fun startOrQueueJob(id: Long) {
        if (activeJobs[id]?.isActive == true) return
        val job = scope.launch {
            runDownload(id)
        }
        activeJobs[id] = job
        startForegroundServiceSafe()
    }

    fun pause(id: Long) {
        activeJobs.remove(id)?.cancel()
        scope.launch {
            downloadDao.updateStatus(id, DownloadStatus.PAUSED, null)
            stopForegroundServiceIfIdle()
        }
    }

    fun resume(id: Long) {
        startOrQueueJob(id)
    }

    fun delete(id: Long) {
        activeJobs.remove(id)?.cancel()
        scope.launch {
            val existing = downloadDao.getById(id)
            File(downloadsDir, "$id.part").delete()
            existing?.localFilePath?.let { path ->
                runCatching { File(path).delete() }
            }
            downloadDao.deleteById(id)
            stopForegroundServiceIfIdle()
        }
    }

    suspend fun calculateStorageUsedBytes(): Long = withContext(Dispatchers.IO) {
        downloadsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    suspend fun clearOrphanedTempFiles(): Long = withContext(Dispatchers.IO) {
        val allItems = downloadDao.observeAllDownloads().first()
        val activeIds = allItems
            .filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.QUEUED }
            .map { "${it.id}.part" }
            .toSet()

        var freed = 0L
        downloadsDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".part") && file.name !in activeIds) {
                freed += file.length()
                file.delete()
            }
        }
        freed
    }

    private suspend fun runDownload(id: Long) {
        val item = downloadDao.getById(id) ?: return
        val prefs = preferencesRepository.preferencesFlow.first()

        if (prefs.wifiOnly && !isOnWifiOrUnmetered()) {
            downloadDao.updateStatus(
                id,
                DownloadStatus.PAUSED,
                "ჩართულია მხოლოდ Wi-Fi რეჟიმი — დაუკავშირდით Wi-Fi-ს"
            )
            activeJobs.remove(id)
            stopForegroundServiceIfIdle()
            return
        }

        try {
            downloadDao.updateStatus(id, DownloadStatus.DOWNLOADING, null)
            if (item.isHls) {
                downloadHlsStream(item, prefs.autoSaveToGallery)
            } else {
                downloadProgressiveFile(item, prefs.autoSaveToGallery)
            }
        } catch (_: CancellationException) {
            // Cancelled intentionally by pause() or delete()
        } catch (e: Exception) {
            downloadDao.updateStatus(
                id,
                DownloadStatus.FAILED,
                e.localizedMessage ?: "გადმოწერა ვერ შესრულდა"
            )
        } finally {
            activeJobs.remove(id)
            stopForegroundServiceIfIdle()
        }
    }

    private suspend fun downloadProgressiveFile(item: DownloadItem, autoSaveToGallery: Boolean) {
        val partFile = File(downloadsDir, "${item.id}.part")
        var downloaded = if (partFile.exists() && item.supportsResume) partFile.length() else 0L
        if (!item.supportsResume && partFile.exists()) {
            partFile.delete()
            downloaded = 0L
        }

        val requestBuilder = Request.Builder()
            .url(item.resolvedUrl)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            )
        if (downloaded > 0L) {
            requestBuilder.header("Range", "bytes=$downloaded-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                if (response.code == 416 && item.totalBytes > 0 && downloaded >= item.totalBytes) {
                    finalizeDownload(item, partFile, downloaded, autoSaveToGallery)
                    return
                }
                throw IOException("სერვერის შეცდომა (${response.code})")
            }

            val isPartial = response.code == 206
            if (!isPartial && downloaded > 0L) {
                downloaded = 0L
                partFile.delete()
            }

            val contentLength = response.body?.contentLength() ?: -1L
            val totalBytes = when {
                isPartial && contentLength > 0L -> downloaded + contentLength
                !isPartial && contentLength > 0L -> contentLength
                else -> item.totalBytes
            }

            val body = response.body ?: throw IOException("ცარიელი პასუხი სერვერიდან")
            val buffer = ByteArray(64 * 1024)
            var lastReportTime = System.currentTimeMillis()
            var bytesSinceLastReport = 0L

            FileOutputStream(partFile, isPartial).use { out ->
                body.byteStream().use { input ->
                    while (true) {
                        coroutineContextCheck()
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        bytesSinceLastReport += read

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastReportTime
                        if (elapsed >= 350L) {
                            val speed = (bytesSinceLastReport * 1000L) / elapsed.coerceAtLeast(1L)
                            downloadDao.updateProgress(
                                id = item.id,
                                status = DownloadStatus.DOWNLOADING,
                                downloadedBytes = downloaded,
                                totalBytes = totalBytes,
                                completedSegments = 0,
                                totalSegments = 0,
                                speedBytesPerSec = speed
                            )
                            lastReportTime = now
                            bytesSinceLastReport = 0L
                        }
                    }
                    out.flush()
                }
            }

            finalizeDownload(item, partFile, downloaded, autoSaveToGallery)
        }
    }

    private suspend fun downloadHlsStream(item: DownloadItem, autoSaveToGallery: Boolean) {
        val playlistText = fetchText(item.resolvedUrl)
        val mediaPlaylistUrl = if (playlistText.contains("#EXT-X-STREAM-INF")) {
            pickBestVariantUrl(item.resolvedUrl, playlistText)
                ?: throw IOException("HLS ვარიანტების წაკითხვა ვერ მოხერხდა")
        } else {
            item.resolvedUrl
        }

        val mediaText = if (mediaPlaylistUrl == item.resolvedUrl) playlistText else fetchText(mediaPlaylistUrl)
        if (mediaText.contains("METHOD=AES-128", ignoreCase = true) ||
            mediaText.contains("METHOD=SAMPLE-AES", ignoreCase = true)
        ) {
            throw IOException("დაშიფრული HLS ნაკადის (AES/DRM) გადმოწერა შეზღუდულია")
        }

        val baseHttpUrl = mediaPlaylistUrl.toHttpUrlOrNull()
            ?: throw IOException("არასწორი HLS მისამართი")

        val segments = mediaText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line -> baseHttpUrl.resolve(line)?.toString() }

        if (segments.isEmpty()) {
            throw IOException("ვიდეო სეგმენტები ვერ მოიძებნა")
        }

        val partFile = File(downloadsDir, "${item.id}.part")
        val startSegment = item.completedSegments.coerceIn(0, segments.size)
        if (startSegment == 0 && partFile.exists()) {
            partFile.delete()
        }
        var downloaded = if (partFile.exists()) partFile.length() else 0L
        var lastReportTime = System.currentTimeMillis()
        var bytesSinceLastReport = 0L

        FileOutputStream(partFile, startSegment > 0).use { out ->
            for (index in startSegment until segments.size) {
                coroutineContextCheck()
                val segUrl = segments[index]
                val req = Request.Builder()
                    .url(segUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("სეგმენტის შეცდომა (${resp.code})")
                    }
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    out.write(bytes)
                    downloaded += bytes.size
                    bytesSinceLastReport += bytes.size
                }

                val now = System.currentTimeMillis()
                val elapsed = (now - lastReportTime).coerceAtLeast(1L)
                val speed = if (elapsed >= 300L) {
                    val s = (bytesSinceLastReport * 1000L) / elapsed
                    lastReportTime = now
                    bytesSinceLastReport = 0L
                    s
                } else {
                    item.speedBytesPerSec
                }

                downloadDao.updateProgress(
                    id = item.id,
                    status = DownloadStatus.DOWNLOADING,
                    downloadedBytes = downloaded,
                    totalBytes = -1L,
                    completedSegments = index + 1,
                    totalSegments = segments.size,
                    speedBytesPerSec = speed
                )
            }
            out.flush()
        }

        finalizeDownload(item, partFile, downloaded, autoSaveToGallery)
    }

    private suspend fun finalizeDownload(
        item: DownloadItem,
        partFile: File,
        totalDownloaded: Long,
        autoSaveToGallery: Boolean
    ) {
        val targetFile = uniqueTargetFile(item.fileName)
        if (!partFile.renameTo(targetFile)) {
            partFile.copyTo(targetFile, overwrite = true)
            partFile.delete()
        }

        val mediaUri = if (autoSaveToGallery) {
            exportToMediaStore(targetFile, item.mimeType)
        } else {
            null
        }

        downloadDao.markCompleted(
            id = item.id,
            status = DownloadStatus.COMPLETED,
            downloadedBytes = totalDownloaded,
            totalBytes = totalDownloaded,
            localFilePath = targetFile.absolutePath,
            mediaStoreUri = mediaUri
        )
    }

    private fun exportToMediaStore(sourceFile: File, mimeType: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, sourceFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/StreamVault")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun uniqueTargetFile(desiredName: String): File {
        var candidate = File(downloadsDir, desiredName)
        if (!candidate.exists()) return candidate
        val base = desiredName.substringBeforeLast('.')
        val ext = desiredName.substringAfterLast('.', "mp4")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(downloadsDir, "${base}_$counter.$ext")
            counter++
        }
        return candidate
    }

    private fun buildSafeTargetName(title: String, rawFileName: String, isHls: Boolean): String {
        val ext = if (isHls) "ts" else rawFileName.substringAfterLast('.', "mp4").lowercase(Locale.US)
        val cleanTitle = title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(60)
        return if (cleanTitle.isNotBlank()) "$cleanTitle.$ext" else rawFileName
    }

    private fun fetchText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    private fun pickBestVariantUrl(masterUrl: String, playlist: String): String? {
        val base = masterUrl.toHttpUrlOrNull() ?: return null
        var bestBandwidth = -1L
        var bestUrl: String? = null
        val lines = playlist.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
                if (next != null && bandwidth >= bestBandwidth) {
                    bestBandwidth = bandwidth
                    bestUrl = base.resolve(next)?.toString()
                }
            }
        }
        return bestUrl
    }

    private fun isOnWifiOrUnmetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private suspend fun coroutineContextCheck() {
        kotlin.coroutines.coroutineContext.ensureActive()
    }

    private fun startForegroundServiceSafe() {
        runCatching {
            val intent = Intent(context, DownloadForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private suspend fun stopForegroundServiceIfIdle() {
        if (downloadDao.countActiveDownloads() == 0 && activeJobs.isEmpty()) {
            runCatching {
                context.stopService(Intent(context, DownloadForegroundService::class.java))
            }
        }
    }
}
