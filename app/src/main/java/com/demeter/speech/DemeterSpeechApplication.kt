package com.demeter.speech

import android.app.Application

class DemeterSpeechApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
