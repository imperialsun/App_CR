package com.demeter.speech

import android.app.Application
import com.demeter.speech.core.AppLog
import com.demeter.speech.core.SupportReportBuilder
import org.json.JSONObject

class DemeterSpeechApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val threadName = thread.name.ifBlank { "unknown" }
                AppLog.e(
                    "DemeterSpeechApp",
                    "Unhandled exception on $threadName",
                    throwable,
                    JSONObject().apply {
                        put("threadName", threadName)
                    },
                )
                val payload = SupportReportBuilder.buildUncaughtExceptionReportPayload(container.preferences, threadName, throwable)
                container.preferences.enqueuePendingSupportReport(payload.toString())
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
