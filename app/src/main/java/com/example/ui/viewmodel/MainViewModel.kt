package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.download.DownloadEngine
import com.example.data.local.DownloadDao
import com.example.data.model.DownloadItem
import com.example.data.model.DownloadStatus
import com.example.data.model.LinkInspectionResult
import com.example.data.model.StreamCandidate
import com.example.data.network.LinkInspector
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DownloadFilter {
    ALL,
    ACTIVE,
    COMPLETED,
    SAVED
}

sealed interface InspectionUiState {
    data object Idle : InspectionUiState
    data object Loading : InspectionUiState
    data class Result(val result: LinkInspectionResult) : InspectionUiState
}

sealed interface UiEvent {
    data class ShowMessage(val message: String) : UiEvent
    data object NavigateToDownloads : UiEvent
}

class MainViewModel(
    private val downloadDao: DownloadDao,
    private val linkInspector: LinkInspector,
    private val downloadEngine: DownloadEngine,
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _titleInput = MutableStateFlow("")
    val titleInput: StateFlow<String> = _titleInput.asStateFlow()

    private val _inspectionState = MutableStateFlow<InspectionUiState>(InspectionUiState.Idle)
    val inspectionState: StateFlow<InspectionUiState> = _inspectionState.asStateFlow()

    private val _selectedFilter = MutableStateFlow(DownloadFilter.ALL)
    val selectedFilter: StateFlow<DownloadFilter> = _selectedFilter.asStateFlow()

    private val _storageUsedBytes = MutableStateFlow(0L)
    val storageUsedBytes: StateFlow<Long> = _storageUsedBytes.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    val downloads: StateFlow<List<DownloadItem>> = downloadDao.observeAllDownloads()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val preferences: StateFlow<UserPreferences> = preferencesRepository.preferencesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UserPreferences()
        )

    init {
        refreshStorageUsage()
    }

    fun onUrlInputChange(value: String) {
        _urlInput.value = value
        if (value.isBlank()) {
            _inspectionState.value = InspectionUiState.Idle
        }
    }

    fun onTitleInputChange(value: String) {
        _titleInput.value = value
    }

    fun setIncomingSharedUrl(sharedText: String) {
        val normalized = linkInspector.normalizeUrl(sharedText) ?: sharedText.trim()
        _urlInput.value = normalized
        inspectLink()
    }

    fun clearInputs() {
        _urlInput.value = ""
        _titleInput.value = ""
        _inspectionState.value = InspectionUiState.Idle
    }

    fun dismissInspection() {
        _inspectionState.value = InspectionUiState.Idle
    }

    fun setFilter(filter: DownloadFilter) {
        _selectedFilter.value = filter
    }

    fun inspectLink() {
        val raw = _urlInput.value.trim()
        if (raw.isEmpty()) {
            emitMessage("გთხოვთ ჩასვათ ვიდეოს ან ფილმის ლინკი")
            return
        }
        viewModelScope.launch {
            _inspectionState.value = InspectionUiState.Loading
            val res = linkInspector.inspect(raw, _titleInput.value.trim().ifBlank { null })
            _inspectionState.value = InspectionUiState.Result(res)
        }
    }

    fun quickDownloadFromInput() {
        val raw = _urlInput.value.trim()
        if (raw.isEmpty()) {
            emitMessage("გთხოვთ ჩასვათ ვიდეოს ან ფილმის ლინკი")
            return
        }
        val customTitle = _titleInput.value.trim().ifBlank { null }
        viewModelScope.launch {
            _inspectionState.value = InspectionUiState.Loading
            when (val res = linkInspector.inspect(raw, customTitle)) {
                is LinkInspectionResult.DirectMedia -> {
                    _inspectionState.value = InspectionUiState.Idle
                    startCandidateDownload(res.candidate, customTitle)
                    _urlInput.value = ""
                    _titleInput.value = ""
                }
                is LinkInspectionResult.ExtractedStreams -> {
                    if (res.streams.size == 1) {
                        _inspectionState.value = InspectionUiState.Idle
                        startCandidateDownload(res.streams.first(), customTitle)
                        _urlInput.value = ""
                        _titleInput.value = ""
                    } else {
                        _inspectionState.value = InspectionUiState.Result(res)
                    }
                }
                is LinkInspectionResult.HlsMaster -> {
                    _inspectionState.value = InspectionUiState.Result(res)
                }
                else -> {
                    _inspectionState.value = InspectionUiState.Result(res)
                }
            }
        }
    }

    fun startCandidateDownload(candidate: StreamCandidate, customTitleOverride: String? = null) {
        viewModelScope.launch {
            val chosenTitle = customTitleOverride ?: _titleInput.value.trim().ifBlank { null }
            downloadEngine.enqueueDownload(candidate, chosenTitle)
            _inspectionState.value = InspectionUiState.Idle
            _events.emit(UiEvent.ShowMessage("გადმოწერა დაიწყო"))
            _events.emit(UiEvent.NavigateToDownloads)
            refreshStorageUsage()
        }
    }

    fun saveLinkFromInput() {
        val normalized = linkInspector.normalizeUrl(_urlInput.value)
        if (normalized == null) {
            emitMessage("გთხოვთ შეიყვანოთ სწორი http/https ლინკი")
            return
        }
        val customTitle = _titleInput.value.trim().ifBlank { null }
        viewModelScope.launch {
            downloadEngine.saveLinkOnly(normalized, customTitle)
            _urlInput.value = ""
            _titleInput.value = ""
            _inspectionState.value = InspectionUiState.Idle
            _events.emit(UiEvent.ShowMessage("ლინკი შენახულია ბიბლიოთეკაში"))
        }
    }

    fun startSavedItemDownload(item: DownloadItem) {
        viewModelScope.launch {
            when (val res = linkInspector.inspect(item.sourceUrl, item.title)) {
                is LinkInspectionResult.DirectMedia -> {
                    downloadDao.update(
                        item.copy(
                            resolvedUrl = res.candidate.url,
                            mimeType = res.candidate.mimeType,
                            totalBytes = res.candidate.fileSizeBytes,
                            isHls = res.candidate.isHls,
                            supportsResume = res.candidate.supportsResume,
                            status = DownloadStatus.QUEUED
                        )
                    )
                    downloadEngine.startOrQueueJob(item.id)
                    _events.emit(UiEvent.ShowMessage("გადმოწერა დაიწყო"))
                }
                is LinkInspectionResult.ExtractedStreams -> {
                    if (res.streams.isNotEmpty()) {
                        val first = res.streams.first()
                        downloadDao.update(
                            item.copy(
                                resolvedUrl = first.url,
                                mimeType = first.mimeType,
                                totalBytes = first.fileSizeBytes,
                                isHls = first.isHls,
                                supportsResume = first.supportsResume,
                                status = DownloadStatus.QUEUED
                            )
                        )
                        downloadEngine.startOrQueueJob(item.id)
                        _events.emit(UiEvent.ShowMessage("გადმოწერა დაიწყო"))
                    }
                }
                is LinkInspectionResult.HlsMaster -> {
                    val best = res.variants.firstOrNull()
                    if (best != null) {
                        downloadDao.update(
                            item.copy(
                                resolvedUrl = best.url,
                                mimeType = best.mimeType,
                                isHls = true,
                                supportsResume = true,
                                status = DownloadStatus.QUEUED
                            )
                        )
                        downloadEngine.startOrQueueJob(item.id)
                        _events.emit(UiEvent.ShowMessage("გადმოწერა დაიწყო"))
                    }
                }
                is LinkInspectionResult.UnsupportedOrProtected -> emitMessage(res.reason)
                is LinkInspectionResult.Error -> emitMessage(res.message)
            }
        }
    }

    fun pauseDownload(id: Long) {
        downloadEngine.pause(id)
    }

    fun resumeDownload(id: Long) {
        downloadEngine.resume(id)
    }

    fun deleteDownload(id: Long) {
        downloadEngine.delete(id)
        viewModelScope.launch {
            refreshStorageUsage()
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setWifiOnly(enabled) }
    }

    fun setAutoSaveToGallery(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setAutoSaveToGallery(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferencesRepository.setThemeMode(mode) }
    }

    fun refreshStorageUsage() {
        viewModelScope.launch {
            _storageUsedBytes.value = downloadEngine.calculateStorageUsedBytes()
        }
    }

    fun clearTempCache() {
        viewModelScope.launch {
            downloadEngine.clearOrphanedTempFiles()
            refreshStorageUsage()
            _events.emit(UiEvent.ShowMessage("დროებითი ქეში გასუფთავდა"))
        }
    }

    private fun emitMessage(msg: String) {
        viewModelScope.launch {
            _events.emit(UiEvent.ShowMessage(msg))
        }
    }

    class Factory(
        private val downloadDao: DownloadDao,
        private val linkInspector: LinkInspector,
        private val downloadEngine: DownloadEngine,
        private val preferencesRepository: UserPreferencesRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(downloadDao, linkInspector, downloadEngine, preferencesRepository) as T
        }
    }
}
