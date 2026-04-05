package com.demeter.speech.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException

private const val APP_LOG_LIMIT = 200

data class AppLogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String,
    val throwable: String? = null,
    val context: JSONObject? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("level", level)
        put("tag", tag)
        put("message", message)
        if (throwable != null) {
            put("throwable", throwable)
        }
        if (context != null) {
            put("context", context)
        }
    }
}

data class SupportReportBackendError(
    val status: Int,
    val code: String,
    val message: String,
    val path: String,
    val method: String,
    val traceId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("status", status)
        put("code", code)
        put("message", message)
        put("path", path)
        put("method", method)
        if (!traceId.isNullOrBlank()) {
            put("traceId", traceId)
        }
    }
}

data class SupportReportFile(
    val name: String = "",
    val sizeBytes: Long = 0L,
    val mimeType: String = "",
    val source: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("sizeBytes", sizeBytes)
        put("mimeType", mimeType)
        put("source", source)
    }

    companion object {
        fun empty(source: String = "android") = SupportReportFile(source = source)
    }
}

data class SupportReportRetry(
    val attempted: Boolean = false,
    val succeeded: Boolean = false,
    val usedRawFile: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("attempted", attempted)
        put("succeeded", succeeded)
        put("usedRawFile", usedRawFile)
    }
}

object AppLog {
    private val lock = Any()
    private val entries = ArrayDeque<AppLogEntry>()

    fun d(tag: String, message: String, throwable: Throwable? = null, context: JSONObject? = null) {
        log(Log.DEBUG, "DEBUG", tag, message, throwable, context)
    }

    fun i(tag: String, message: String, throwable: Throwable? = null, context: JSONObject? = null) {
        log(Log.INFO, "INFO", tag, message, throwable, context)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null, context: JSONObject? = null) {
        log(Log.WARN, "WARN", tag, message, throwable, context)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null, context: JSONObject? = null) {
        log(Log.ERROR, "ERROR", tag, message, throwable, context)
    }

    fun snapshot(): List<AppLogEntry> {
        synchronized(lock) {
            return entries.map { entry ->
                entry.copy(context = entry.context?.let { JSONObject(it.toString()) })
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    private fun log(priority: Int, level: String, tag: String, message: String, throwable: Throwable?, context: JSONObject?) {
        val normalizedMessage = message.trim().ifBlank { throwable?.message.orEmpty().ifBlank { "log" } }
        val normalizedContext = context?.let { JSONObject(it.toString()) }
        val entry = AppLogEntry(
            timestamp = Instant.now().toString(),
            level = level,
            tag = tag.trim().ifBlank { "DemeterSpeech" },
            message = normalizedMessage,
            throwable = throwable?.let { "${it.javaClass.simpleName}: ${it.message ?: it.javaClass.simpleName}" },
            context = normalizedContext,
        )
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > APP_LOG_LIMIT) {
                entries.removeFirst()
            }
        }

        when (priority) {
            Log.DEBUG -> {
                if (throwable != null) {
                    Log.d(entry.tag, entry.message, throwable)
                } else {
                    Log.d(entry.tag, entry.message)
                }
            }
            Log.INFO -> {
                if (throwable != null) {
                    Log.i(entry.tag, entry.message, throwable)
                } else {
                    Log.i(entry.tag, entry.message)
                }
            }
            Log.WARN -> {
                if (throwable != null) {
                    Log.w(entry.tag, entry.message, throwable)
                } else {
                    Log.w(entry.tag, entry.message)
                }
            }
            Log.ERROR -> {
                if (throwable != null) {
                    Log.e(entry.tag, entry.message, throwable)
                } else {
                    Log.e(entry.tag, entry.message)
                }
            }
            else -> {
                if (throwable != null) {
                    Log.println(priority, entry.tag, "${entry.message}: ${throwable.message ?: throwable.javaClass.simpleName}")
                } else {
                    Log.println(priority, entry.tag, entry.message)
                }
            }
        }
    }
}

object SupportReportBuilder {
    fun buildDiagnosticBundle(
        client: String,
        session: JSONObject,
        settings: JSONObject,
        logs: List<AppLogEntry>,
        telemetry: JSONObject? = null,
    ): JSONObject {
        return JSONObject().apply {
            put("schemaVersion", 1)
            put("client", client)
            put("exportedAt", Instant.now().toString())
            put("session", session)
            put("settings", settings)
            put("logs", JSONArray(logs.map { it.toJson() }))
            if (telemetry != null) {
                put("telemetry", telemetry)
            }
        }
    }

    fun buildSupportReportPayload(
        client: String,
        provider: String,
        backendError: SupportReportBackendError,
        originalFile: SupportReportFile,
        processedFile: SupportReportFile,
        retry: SupportReportRetry,
        diagnosticBundle: JSONObject,
        rawFile: SupportReportFile? = null,
        traceId: String? = null,
    ): JSONObject {
        return JSONObject().apply {
            if (!traceId.isNullOrBlank()) {
                put("traceId", traceId)
            }
            put("client", client)
            put("provider", provider)
            put("backendError", backendError.copy(traceId = backendError.traceId ?: traceId).toJson())
            put("originalFile", originalFile.toJson())
            put("processedFile", processedFile.toJson())
            if (rawFile != null) {
                put("rawFile", rawFile.toJson())
            }
            put("retry", retry.toJson())
            put("diagnosticBundle", diagnosticBundle)
        }
    }

    fun buildSessionSnapshot(
        state: DemeterSpeechUiState,
        route: String,
    ): JSONObject {
        return JSONObject().apply {
            put("route", route)
            put("authState", state.authState.name)
            put("activeTab", state.activeTab.name)
            put("meetingPhase", state.meetingPhase.name)
            put("permissionsGranted", state.permissionsGranted)
            put("login", JSONObject().apply {
                put("email", state.login.email)
                put("isLoading", state.login.isLoading)
                put("hasError", !state.login.errorMessage.isNullOrBlank())
            })
            state.session?.let { session ->
                put("session", JSONObject().apply {
                    put("userId", session.userId)
                    put("email", session.email)
                    put("organizationId", session.organizationId)
                    put("organizationName", session.organizationName)
                    put("permissions", JSONArray(session.permissions))
                })
            } ?: put("session", JSONObject.NULL)
            put("recording", JSONObject().apply {
                put("isRecording", state.recording.isRecording)
                put("isPaused", state.recording.isPaused)
                put("elapsedMs", state.recording.elapsedMs)
            })
            put("wizard", JSONObject().apply {
                put("title", state.wizard.title)
                put("sourceMode", state.wizard.sourceMode.apiValue)
                put("localWhisperModelId", state.wizard.localWhisperModelId)
                put("cloudMistralChunkDurationSec", state.wizard.cloudMistralChunkDurationSec)
                put("cloudMistralOverlapSec", state.wizard.cloudMistralOverlapSec)
                put("reportModelId", state.wizard.reportModelId)
                put("reportTemperature", state.wizard.reportTemperature)
                put("reportMaxTokens", state.wizard.reportMaxTokens)
                put("participantCount", state.wizard.participants.size)
                put("selectedFormats", JSONArray(state.wizard.selectedFormats.map { it.apiValue }))
            })
            put("review", JSONObject().apply {
                put("isTranscribing", state.review.isTranscribing)
                put("isGeneratingDrafts", state.review.isGeneratingDrafts)
                put("isSubmitting", state.review.isSubmitting)
                put("draftCount", state.review.drafts.size)
                put("errorMessage", state.review.errorMessage ?: JSONObject.NULL)
                put("submitMessage", state.review.submitMessage ?: JSONObject.NULL)
            })
            put("infoMessage", state.infoMessage ?: JSONObject.NULL)
        }
    }

    fun buildSettingsSnapshot(prefs: AppPreferences): JSONObject {
        return JSONObject().apply {
            put("lastEmail", prefs.lastEmail())
            put("localWhisperModelId", prefs.localWhisperModelId())
            put("demeterChunkDurationSec", prefs.demeterChunkDurationSec())
            put("demeterOverlapSec", prefs.demeterOverlapSec())
            put("reportModelId", prefs.reportModelId())
            put("reportTemperature", prefs.reportTemperature())
            put("reportMaxTokens", prefs.reportMaxTokens())
            put("pendingFinalizeOperation", prefs.pendingFinalizeOperation()?.let {
                JSONObject(it.toJson())
            } ?: JSONObject.NULL)
        }
    }

    fun defaultOriginalFile(source: String = "android"): SupportReportFile = SupportReportFile.empty(source)

    fun buildUncaughtExceptionReportPayload(
        preferences: AppPreferences,
        threadName: String,
        throwable: Throwable,
        logs: List<AppLogEntry> = AppLog.snapshot(),
    ): JSONObject {
        val safeThreadName = threadName.trim().ifBlank { "unknown" }
        val diagnosticBundle = buildDiagnosticBundle(
            client = "android",
            session = JSONObject().apply {
                put("route", "android://uncaught-exception")
                put("threadName", safeThreadName)
            },
            settings = buildSettingsSnapshot(preferences),
            logs = logs,
            telemetry = JSONObject().apply {
                put("exceptionClass", throwable.javaClass.name)
                put("exceptionMessage", throwable.message ?: throwable.javaClass.simpleName)
                put("threadName", safeThreadName)
            },
        )
        return buildSupportReportPayload(
            client = "android",
            provider = "android",
            backendError = SupportReportBackendError(
                status = 500,
                code = "uncaught_exception",
                message = throwable.message ?: throwable.javaClass.simpleName,
                path = "android://uncaught-exception",
                method = "PROCESS",
            ),
            originalFile = SupportReportFile.empty("android"),
            processedFile = SupportReportFile.empty("android"),
            retry = SupportReportRetry(),
            diagnosticBundle = diagnosticBundle,
        )
    }
}

class SupportErrorReporter(
    private val backendApiClient: BackendApiClient,
    private val preferences: AppPreferences,
) {
    suspend fun submit(payload: JSONObject): Boolean {
        return try {
            backendApiClient.submitSupportErrorReport(payload)
            AppLog.i("SupportErrorReporter", "support report submitted")
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AppLog.w("SupportErrorReporter", "support report queued", error)
            preferences.enqueuePendingSupportReport(payload.toString())
            false
        }
    }

    suspend fun flushPending(): Int {
        val pending = preferences.pendingSupportReports()
        if (pending.isEmpty()) {
            return 0
        }

        val remaining = mutableListOf<String>()
        var sent = 0
        for ((index, raw) in pending.withIndex()) {
            val payload = runCatching { JSONObject(raw) }.getOrNull()
            if (payload == null) {
                continue
            }
            val success = try {
                backendApiClient.submitSupportErrorReport(payload)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AppLog.w("SupportErrorReporter", "stopping pending report flush", error)
                false
            }
            if (success) {
                sent += 1
                continue
            }
            remaining.add(raw)
            remaining.addAll(pending.drop(index + 1))
            break
        }

        preferences.setPendingSupportReports(remaining)
        if (remaining.isEmpty()) {
            AppLog.i(
                "SupportErrorReporter",
                "pending support reports flushed",
                context = JSONObject().apply {
                    put("count", sent)
                },
            )
        }
        return sent
    }
}
