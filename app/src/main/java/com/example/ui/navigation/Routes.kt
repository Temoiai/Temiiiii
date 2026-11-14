package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object DownloadsRoute

@Serializable
data object SettingsRoute

@Serializable
data class PlayerRoute(
    val title: String,
    val uri: String
)
