package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.model.DownloadItem
import com.example.data.model.DownloadStatus
import com.example.ui.navigation.DownloadsRoute
import com.example.ui.navigation.HomeRoute
import com.example.ui.navigation.PlayerRoute
import com.example.ui.navigation.SettingsRoute
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.StreamVaultTheme
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.UiEvent
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        val app = application as StreamVaultApplication
        MainViewModel.Factory(
            downloadDao = app.downloadDao,
            linkInspector = app.linkInspector,
            downloadEngine = app.downloadEngine,
            preferencesRepository = app.preferencesRepository
        )
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val preferences by viewModel.preferences.collectAsStateWithLifecycle()

            StreamVaultTheme(themeMode = preferences.themeMode) {
                StreamVaultApp(
                    viewModel = viewModel,
                    useRail = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                viewModel.setIncomingSharedUrl(sharedText)
            }
        }
    }
}

@Composable
private fun StreamVaultApp(
    viewModel: MainViewModel,
    useRail: Boolean
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val urlInput by viewModel.urlInput.collectAsStateWithLifecycle()
    val titleInput by viewModel.titleInput.collectAsStateWithLifecycle()
    val inspectionState by viewModel.inspectionState.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val storageUsedBytes by viewModel.storageUsedBytes.collectAsStateWithLifecycle()

    var pendingDownloadAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        pendingDownloadAction?.invoke()
        pendingDownloadAction = null
    }

    fun runWithNotificationCheck(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                action()
            } else {
                pendingDownloadAction = action
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            action()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
                UiEvent.NavigateToDownloads -> {
                    navController.navigate(DownloadsRoute) {
                        popUpTo<HomeRoute> { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val isPlayerRoute = currentDestination?.hasRoute<PlayerRoute>() == true

    val activeCount = downloads.count {
        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
    }
    val completedCount = downloads.count { it.status == DownloadStatus.COMPLETED }

    val isHomeSelected = currentDestination?.hasRoute<HomeRoute>() == true
    val isDownloadsSelected = currentDestination?.hasRoute<DownloadsRoute>() == true
    val isSettingsSelected = currentDestination?.hasRoute<SettingsRoute>() == true

    fun navigateTopLevel(route: Any) {
        navController.navigate(route) {
            popUpTo<HomeRoute> { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun shareCompletedVideo(item: DownloadItem) {
        val shareUri: Uri? = when {
            !item.mediaStoreUri.isNullOrBlank() -> Uri.parse(item.mediaStoreUri)
            !item.localFilePath.isNullOrBlank() -> {
                val file = File(item.localFilePath)
                if (file.exists()) {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } else {
                    null
                }
            }
            else -> null
        }

        if (shareUri == null) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.msg_no_share_file))
            }
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_TITLE, item.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(shareIntent, context.getString(R.string.msg_share_chooser))
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (!useRail && !isPlayerRoute) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    NavigationBarItem(
                        selected = isHomeSelected,
                        onClick = { navigateTopLevel(HomeRoute) },
                        icon = {
                            Icon(
                                imageVector = if (isHomeSelected) Icons.Rounded.Home else Icons.Outlined.Home,
                                contentDescription = stringResource(R.string.nav_home)
                            )
                        },
                        label = { Text(stringResource(R.string.nav_home)) },
                        modifier = Modifier.testTag("nav_home")
                    )
                    NavigationBarItem(
                        selected = isDownloadsSelected,
                        onClick = { navigateTopLevel(DownloadsRoute) },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (activeCount > 0) {
                                        Badge { Text("$activeCount") }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (isDownloadsSelected) Icons.Rounded.Download else Icons.Outlined.Download,
                                    contentDescription = stringResource(R.string.nav_downloads)
                                )
                            }
                        },
                        label = { Text(stringResource(R.string.nav_downloads)) },
                        modifier = Modifier.testTag("nav_downloads")
                    )
                    NavigationBarItem(
                        selected = isSettingsSelected,
                        onClick = {
                            viewModel.refreshStorageUsage()
                            navigateTopLevel(SettingsRoute)
                        },
                        icon = {
                            Icon(
                                imageVector = if (isSettingsSelected) Icons.Rounded.Settings else Icons.Outlined.Settings,
                                contentDescription = stringResource(R.string.nav_settings)
                            )
                        },
                        label = { Text(stringResource(R.string.nav_settings)) },
                        modifier = Modifier.testTag("nav_settings")
                    )
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (useRail && !isPlayerRoute) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    NavigationRailItem(
                        selected = isHomeSelected,
                        onClick = { navigateTopLevel(HomeRoute) },
                        icon = {
                            Icon(
                                imageVector = if (isHomeSelected) Icons.Rounded.Home else Icons.Outlined.Home,
                                contentDescription = stringResource(R.string.nav_home)
                            )
                        },
                        label = { Text(stringResource(R.string.nav_home)) },
                        modifier = Modifier.testTag("rail_home")
                    )
                    NavigationRailItem(
                        selected = isDownloadsSelected,
                        onClick = { navigateTopLevel(DownloadsRoute) },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (activeCount > 0) {
                                        Badge { Text("$activeCount") }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (isDownloadsSelected) Icons.Rounded.Download else Icons.Outlined.Download,
                                    contentDescription = stringResource(R.string.nav_downloads)
                                )
                            }
                        },
                        label = { Text(stringResource(R.string.nav_downloads)) },
                        modifier = Modifier.testTag("rail_downloads")
                    )
                    NavigationRailItem(
                        selected = isSettingsSelected,
                        onClick = {
                            viewModel.refreshStorageUsage()
                            navigateTopLevel(SettingsRoute)
                        },
                        icon = {
                            Icon(
                                imageVector = if (isSettingsSelected) Icons.Rounded.Settings else Icons.Outlined.Settings,
                                contentDescription = stringResource(R.string.nav_settings)
                            )
                        },
                        label = { Text(stringResource(R.string.nav_settings)) },
                        modifier = Modifier.testTag("rail_settings")
                    )
                }
            }

            NavHost(
                navController = navController,
                startDestination = HomeRoute,
                modifier = Modifier.weight(1f)
            ) {
                composable<HomeRoute> {
                    HomeScreen(
                        urlInput = urlInput,
                        titleInput = titleInput,
                        inspectionState = inspectionState,
                        recentItems = downloads,
                        activeCount = activeCount,
                        completedCount = completedCount,
                        onUrlChange = viewModel::onUrlInputChange,
                        onTitleChange = viewModel::onTitleInputChange,
                        onClearInputs = viewModel::clearInputs,
                        onInspectClick = viewModel::inspectLink,
                        onQuickDownloadClick = {
                            runWithNotificationCheck { viewModel.quickDownloadFromInput() }
                        },
                        onSaveLinkClick = viewModel::saveLinkFromInput,
                        onDownloadCandidate = { candidate ->
                            runWithNotificationCheck { viewModel.startCandidateDownload(candidate) }
                        },
                        onPreviewStream = { title, uri ->
                            navController.navigate(PlayerRoute(title = title, uri = uri))
                        },
                        onDismissInspection = viewModel::dismissInspection,
                        onOpenDownloads = { navigateTopLevel(DownloadsRoute) },
                        onShowMessage = { msg ->
                            scope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                    )
                }

                composable<DownloadsRoute> {
                    DownloadsScreen(
                        items = downloads,
                        selectedFilter = selectedFilter,
                        onSelectFilter = viewModel::setFilter,
                        onPause = viewModel::pauseDownload,
                        onResume = { id ->
                            runWithNotificationCheck { viewModel.resumeDownload(id) }
                        },
                        onStartSaved = { item ->
                            runWithNotificationCheck { viewModel.startSavedItemDownload(item) }
                        },
                        onPlay = { item ->
                            val playable = item.playableUri ?: item.resolvedUrl
                            navController.navigate(PlayerRoute(title = item.title, uri = playable))
                        },
                        onShare = ::shareCompletedVideo,
                        onDelete = viewModel::deleteDownload,
                        onGoToHome = { navigateTopLevel(HomeRoute) }
                    )
                }

                composable<SettingsRoute> {
                    SettingsScreen(
                        preferences = preferences,
                        storageUsedBytes = storageUsedBytes,
                        onWifiOnlyChange = viewModel::setWifiOnly,
                        onAutoGalleryChange = viewModel::setAutoSaveToGallery,
                        onThemeModeChange = viewModel::setThemeMode,
                        onClearCache = viewModel::clearTempCache
                    )
                }

                composable<PlayerRoute> { backStackEntry ->
                    val route: PlayerRoute = backStackEntry.toRoute()
                    PlayerScreen(
                        title = route.title,
                        videoUri = route.uri,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
