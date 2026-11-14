package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Locale

enum class DownloadStatus {
    SAVED,
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceUrl: String,
    val resolvedUrl: String,
    val fileName: String,
    val mimeType: String,
    val isHls: Boolean = false,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val completedSegments: Int = 0,
    val totalSegments: Int = 0,
    val speedBytesPerSec: Long = 0L,
    val supportsResume: Boolean = false,
    val localFilePath: String? = null,
    val mediaStoreUri: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = when {
            status == DownloadStatus.COMPLETED -> 1f
            isHls && totalSegments > 0 -> (completedSegments.toFloat() / totalSegments.toFloat()).coerceIn(0f, 1f)
            totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }

    val playableUri: String?
        get() = mediaStoreUri ?: localFilePath?.let { "file://$it" }
}

data class StreamCandidate(
    val url: String,
    val title: String,
    val mimeType: String,
    val formatLabel: String,
    val fileSizeBytes: Long = -1L,
    val resolutionLabel: String? = null,
    val isHls: Boolean = false,
    val supportsResume: Boolean = false
)

sealed class LinkInspectionResult {
    data class DirectMedia(val candidate: StreamCandidate) : LinkInspectionResult()
    data class ExtractedStreams(
        val pageTitle: String?,
        val pageUrl: String,
        val streams: List<StreamCandidate>
    ) : LinkInspectionResult()
    data class HlsMaster(
        val masterUrl: String,
        val title: String,
        val variants: List<StreamCandidate>
    ) : LinkInspectionResult()
    data class UnsupportedOrProtected(val reason: String) : LinkInspectionResult()
    data class Error(val message: String) : LinkInspectionResult()
}

object FormatUtils {
    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "უცნობი"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return ""
        return "${formatBytes(bytesPerSec)}/წმ"
    }

    fun formatEta(downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long): String {
        if (totalBytes <= 0L || speedBytesPerSec <= 0L || downloadedBytes >= totalBytes) return ""
        val remainingSeconds = (totalBytes - downloadedBytes) / speedBytesPerSec
        return when {
            remainingSeconds < 60L -> "${remainingSeconds}წმ"
            remainingSeconds < 3600L -> "${remainingSeconds / 60L}წთ ${remainingSeconds % 60L}წმ"
            else -> "${remainingSeconds / 3600L}სთ ${(remainingSeconds % 3600L) / 60L}წთ"
        }
    }

    fun extractHost(url: String): String {
        return try {
            java.net.URI(url).host?.removePrefix("www.") ?: url
        } catch (_: Exception) {
            url
        }
    }
}
