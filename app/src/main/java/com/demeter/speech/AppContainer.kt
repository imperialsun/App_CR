package com.demeter.speech

import android.content.Context
import com.demeter.speech.core.AppPreferences
import com.demeter.speech.core.BackendApiClient
import com.demeter.speech.core.DemeterBackendTranscriptionEngine
import com.demeter.speech.core.LocalWhisperTranscriptionEngine
import com.demeter.speech.core.SupportErrorReporter

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val preferences = AppPreferences(appContext)
    val backendApiClient = BackendApiClient(appContext)
    val supportErrorReporter = SupportErrorReporter(backendApiClient, preferences)
    val localTranscriptionEngine = LocalWhisperTranscriptionEngine()
    val demeterTranscriptionEngine = DemeterBackendTranscriptionEngine()
}
