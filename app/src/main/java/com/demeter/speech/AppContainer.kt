package com.demeter.speech

import android.content.Context
import com.demeter.speech.core.AppPreferences
import com.demeter.speech.core.BackendApiClient
import com.demeter.speech.core.NetworkMonitor

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val preferences = AppPreferences(appContext)
    val networkMonitor = NetworkMonitor(appContext)
    val backendApiClient = BackendApiClient(preferences, networkMonitor)
}
