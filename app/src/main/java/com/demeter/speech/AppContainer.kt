package com.demeter.speech

import android.content.Context
import com.demeter.speech.core.AppPreferences
import com.demeter.speech.core.BackendApiClient
import com.demeter.speech.core.DemeterBackendTranscriptionEngine
import com.demeter.speech.core.LocalWhisperTranscriptionEngine

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val preferences = AppPreferences(appContext)
    val backendApiClient = BackendApiClient(appContext)
    val localTranscriptionEngine = LocalWhisperTranscriptionEngine()
    val demeterTranscriptionEngine = DemeterBackendTranscriptionEngine()
}
