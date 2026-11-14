package com.example.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.DownloadItem
import com.example.data.model.DownloadStatus
import com.example.data.model.FormatUtils
import com.example.ui.viewmodel.DownloadFilter

@Composable
fun DownloadsScreen(
    items: List<DownloadItem>,
    selectedFilter: DownloadFilter,
    onSelectFilter: (DownloadFilter) -> Unit,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onStartSaved: (DownloadItem) -> Unit,
    onPlay: (DownloadItem) -> Unit,
    onShare: (DownloadItem) -> Unit,
    onDelete: (Long) -> Unit,
    onGoToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredItems = items.filter { item ->
        when (selectedFilter) {
            DownloadFilter.ALL -> true
            DownloadFilter.ACTIVE -> item.status == DownloadStatus.DOWNLOADING ||
                item.status == DownloadStatus.QUEUED ||
                item.status == DownloadStatus.PAUSED
            DownloadFilter.COMPLETED -> item.status == DownloadStatus.COMPLETED
            DownloadFilter.SAVED -> item.status == DownloadStatus.SAVED
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.downloads_title),
                    style = MaterialTheme.typography.headlineSmall
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedFilter == DownloadFilter.ALL,
                            onClick = { onSelectFilter(DownloadFilter.ALL) },
                            label = { Text("${stringResource(R.string.filter_all)} (${items.size})") },
                            modifier = Modifier.testTag("filter_all")
                        )
                    }
                    item {
                        val activeCount = items.count {
                            it.status == DownloadStatus.DOWNLOADING ||
                                it.status == DownloadStatus.QUEUED ||
                                it.status == DownloadStatus.PAUSED
                        }
                        FilterChip(
                            selected = selectedFilter == DownloadFilter.ACTIVE,
                            onClick = { onSelectFilter(DownloadFilter.ACTIVE) },
                            label = { Text("${stringResource(R.string.filter_active)} ($activeCount)") },
                            modifier = Modifier.testTag("filter_active")
                        )
                    }
                    item {
                        val completedCount = items.count { it.status == DownloadStatus.COMPLETED }
                        FilterChip(
                            selected = selectedFilter == DownloadFilter.COMPLETED,
                            onClick = { onSelectFilter(DownloadFilter.COMPLETED) },
                            label = { Text("${stringResource(R.string.filter_completed)} ($completedCount)") },
                            modifier = Modifier.testTag("filter_completed")
                        )
                    }
                    item {
                        val savedCount = items.count { it.status == DownloadStatus.SAVED }
                        FilterChip(
                            selected = selectedFilter == DownloadFilter.SAVED,
                            onClick = { onSelectFilter(DownloadFilter.SAVED) },
                            label = { Text("${stringResource(R.string.filter_saved)} ($savedCount)") },
                            modifier = Modifier.testTag("filter_saved")
                        )
                    }
                }
            }

            if (filteredItems.isEmpty()) {
                EmptyDownloadsState(
                    onGoToHome = onGoToHome,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        DownloadItemCard(
                            item = item,
                            onPause = { onPause(item.id) },
                            onResume = { onResume(item.id) },
                            onStartSaved = { onStartSaved(item) },
                            onPlay = { onPlay(item) },
                            onShare = { onShare(item) },
                            onDelete = { onDelete(item.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadItemCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStartSaved: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = item.progressFraction,
        label = "download_progress_${item.id}"
    )

    val (statusLabel, statusBg, statusFg) = when (item.status) {
        DownloadStatus.SAVED -> Triple(
            stringResource(R.string.status_saved),
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        DownloadStatus.QUEUED -> Triple(
            stringResource(R.string.status_queued),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        DownloadStatus.DOWNLOADING -> Triple(
            stringResource(R.string.status_downloading),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        DownloadStatus.PAUSED -> Triple(
            stringResource(R.string.status_paused),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        DownloadStatus.COMPLETED -> Triple(
            stringResource(R.string.status_completed),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        DownloadStatus.FAILED -> Triple(
            stringResource(R.string.status_failed),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download_card_${item.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FormatBadge(text = if (item.isHls) "HLS" else item.fileName.substringAfterLast('.', "MP4").uppercase())
                    FormatBadge(
                        text = statusLabel,
                        containerColor = statusBg,
                        contentColor = statusFg
                    )
                }
                Text(
                    text = FormatUtils.extractHost(item.sourceUrl),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.PAUSED || item.status == DownloadStatus.QUEUED) {
                val isIndeterminate = item.status == DownloadStatus.QUEUED ||
                    (item.totalBytes <= 0L && (!item.isHls || item.totalSegments == 0))

                if (isIndeterminate) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (item.status == DownloadStatus.PAUSED) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val sizeText = when {
                        item.isHls && item.totalSegments > 0 ->
                            "${FormatUtils.formatBytes(item.downloadedBytes)} (${item.completedSegments}/${item.totalSegments})"
                        item.totalBytes > 0L ->
                            "${FormatUtils.formatBytes(item.downloadedBytes)} / ${FormatUtils.formatBytes(item.totalBytes)}"
                        else -> FormatUtils.formatBytes(item.downloadedBytes)
                    }
                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val rightInfo = buildString {
                        if (!isIndeterminate) {
                            append("${(item.progressFraction * 100).toInt()}%")
                        }
                        val speed = FormatUtils.formatSpeed(item.speedBytesPerSec)
                        if (speed.isNotEmpty()) {
                            if (isNotEmpty()) append(" • ")
                            append(speed)
                        }
                        val eta = FormatUtils.formatEta(item.downloadedBytes, item.totalBytes, item.speedBytesPerSec)
                        if (eta.isNotEmpty()) {
                            append(" • $eta")
                        }
                    }
                    if (rightInfo.isNotEmpty()) {
                        Text(
                            text = rightInfo,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            } else if (item.status == DownloadStatus.COMPLETED && item.totalBytes > 0L) {
                Text(
                    text = "${FormatUtils.formatBytes(item.totalBytes)} • ${item.fileName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!item.errorMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = item.errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (item.status) {
                        DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                            OutlinedButton(
                                onClick = onPause,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("pause_button_${item.id}"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_pause))
                            }
                        }
                        DownloadStatus.PAUSED -> {
                            Button(
                                onClick = onResume,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("resume_button_${item.id}"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_resume))
                            }
                        }
                        DownloadStatus.FAILED -> {
                            Button(
                                onClick = onResume,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("retry_button_${item.id}"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                        DownloadStatus.SAVED -> {
                            Button(
                                onClick = onStartSaved,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("start_saved_button_${item.id}"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_download_now))
                            }
                            OutlinedButton(
                                onClick = onPlay,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("preview_saved_button_${item.id}"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.action_preview_stream))
                            }
                        }
                        DownloadStatus.COMPLETED -> {
                            Button(
                                onClick = onPlay,
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("play_button_${item.id}"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_play))
                            }
                            IconButton(
                                onClick = onShare,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("share_button_${item.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = stringResource(R.string.action_share),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("delete_button_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDownloadsState(
    onGoToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_empty_downloads),
            contentDescription = stringResource(R.string.empty_downloads_title),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .height(190.dp)
                .clip(RoundedCornerShape(24.dp))
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.empty_downloads_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_downloads_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onGoToHome,
            modifier = Modifier
                .height(48.dp)
                .testTag("empty_go_home_button"),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.go_to_home))
        }
    }
}
