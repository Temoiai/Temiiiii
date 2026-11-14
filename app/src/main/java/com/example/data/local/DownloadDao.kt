package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.DownloadItem
import com.example.data.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAllDownloads(): Flow<List<DownloadItem>>

    @Query("SELECT * FROM downloads WHERE status IN (:statuses) ORDER BY createdAt DESC")
    fun observeByStatuses(statuses: List<DownloadStatus>): Flow<List<DownloadItem>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DownloadItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: DownloadItem): Long

    @Update
    suspend fun update(item: DownloadItem)

    @Query(
        "UPDATE downloads SET status = :status, downloadedBytes = :downloadedBytes, " +
            "totalBytes = :totalBytes, completedSegments = :completedSegments, " +
            "totalSegments = :totalSegments, speedBytesPerSec = :speedBytesPerSec, " +
            "updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateProgress(
        id: Long,
        status: DownloadStatus,
        downloadedBytes: Long,
        totalBytes: Long,
        completedSegments: Int,
        totalSegments: Int,
        speedBytesPerSec: Long,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE downloads SET status = :status, errorMessage = :errorMessage, " +
            "speedBytesPerSec = 0, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateStatus(
        id: Long,
        status: DownloadStatus,
        errorMessage: String? = null,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE downloads SET status = :status, downloadedBytes = :downloadedBytes, " +
            "totalBytes = :totalBytes, localFilePath = :localFilePath, " +
            "mediaStoreUri = :mediaStoreUri, speedBytesPerSec = 0, " +
            "errorMessage = NULL, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun markCompleted(
        id: Long,
        status: DownloadStatus = DownloadStatus.COMPLETED,
        downloadedBytes: Long,
        totalBytes: Long,
        localFilePath: String?,
        mediaStoreUri: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('DOWNLOADING', 'QUEUED')")
    suspend fun countActiveDownloads(): Int

    @Query("UPDATE downloads SET status = 'PAUSED', speedBytesPerSec = 0 WHERE status IN ('DOWNLOADING', 'QUEUED')")
    suspend fun pauseInterruptedDownloads()
}
