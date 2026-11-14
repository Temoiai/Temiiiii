package com.example

import android.app.Application
import com.example.data.download.DownloadEngine
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadDao
import com.example.data.network.LinkInspector
import com.example.data.preferences.UserPreferencesRepository

class StreamVaultApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val downloadDao: DownloadDao by lazy { database.downloadDao() }
    val preferencesRepository: UserPreferencesRepository by lazy { UserPreferencesRepository(this) }
    val linkInspector: LinkInspector by lazy { LinkInspector() }
    val downloadEngine: DownloadEngine by lazy {
        DownloadEngine(
            context = this,
            downloadDao = downloadDao,
            preferencesRepository = preferencesRepository,
            linkInspector = linkInspector
        )
    }
}
