package com.demeter.speech.core

import android.content.Context

private const val PREFS_NAME = "demeter-speech"
private const val KEY_LAST_EMAIL = "last_email"
private const val KEY_LOCAL_MODEL_ID = "local_model_id"
private const val KEY_DEMETER_CHUNK_DURATION_SEC = "demeter_chunk_duration_sec"
private const val KEY_DEMETER_OVERLAP_SEC = "demeter_overlap_sec"
private const val KEY_REPORT_MODEL_ID = "report_model_id"
private const val KEY_REPORT_TEMPERATURE = "report_temperature"
private const val KEY_REPORT_MAX_TOKENS = "report_max_tokens"
private const val KEY_PENDING_FINALIZE_OPERATION = "pending_finalize_operation"
private const val LEGACY_REPORT_MODEL_ID = "voxtral-mini-latest"

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun lastEmail(): String {
        return prefs.getString(KEY_LAST_EMAIL, "")?.trim().orEmpty()
    }

    fun setLastEmail(value: String) {
        prefs.edit().putString(KEY_LAST_EMAIL, value.trim()).apply()
    }

    fun localWhisperModelId(): String {
        return prefs.getString(KEY_LOCAL_MODEL_ID, "base")?.trim().orEmpty().ifBlank { "base" }
    }

    fun setLocalWhisperModelId(value: String) {
        prefs.edit().putString(KEY_LOCAL_MODEL_ID, value.trim()).apply()
    }

    fun demeterChunkDurationSec(): Int {
        return prefs.getInt(KEY_DEMETER_CHUNK_DURATION_SEC, DEFAULT_DEMETER_CHUNK_DURATION_SEC).coerceAtLeast(1)
    }

    fun setDemeterChunkDurationSec(value: Int) {
        prefs.edit().putInt(KEY_DEMETER_CHUNK_DURATION_SEC, value.coerceAtLeast(1)).apply()
    }

    fun demeterOverlapSec(): Int {
        return prefs.getInt(KEY_DEMETER_OVERLAP_SEC, 0).coerceAtLeast(0)
    }

    fun setDemeterOverlapSec(value: Int) {
        prefs.edit().putInt(KEY_DEMETER_OVERLAP_SEC, value.coerceAtLeast(0)).apply()
    }

    fun reportModelId(): String {
        val stored = prefs.getString(KEY_REPORT_MODEL_ID, DEFAULT_REPORT_MODEL_ID)?.trim().orEmpty()
        return when {
            stored.isBlank() -> DEFAULT_REPORT_MODEL_ID
            stored == LEGACY_REPORT_MODEL_ID -> DEFAULT_REPORT_MODEL_ID
            else -> stored
        }
    }

    fun setReportModelId(value: String) {
        prefs.edit().putString(KEY_REPORT_MODEL_ID, value.trim()).apply()
    }

    fun reportTemperature(): Float {
        return prefs.getFloat(KEY_REPORT_TEMPERATURE, 0f)
    }

    fun setReportTemperature(value: Float) {
        prefs.edit().putFloat(KEY_REPORT_TEMPERATURE, value).apply()
    }

    fun reportMaxTokens(): Int {
        return prefs.getInt(KEY_REPORT_MAX_TOKENS, 32768).coerceAtLeast(1)
    }

    fun setReportMaxTokens(value: Int) {
        prefs.edit().putInt(KEY_REPORT_MAX_TOKENS, value.coerceAtLeast(1)).apply()
    }

    fun pendingFinalizeOperation(): PendingMeetingFinalizeOperationSnapshot? {
        val raw = prefs.getString(KEY_PENDING_FINALIZE_OPERATION, "")?.trim().orEmpty()
        if (raw.isBlank()) {
            return null
        }
        return runCatching {
            PendingMeetingFinalizeOperationSnapshot.fromJson(raw)
        }.getOrNull()
    }

    fun setPendingFinalizeOperation(snapshot: PendingMeetingFinalizeOperationSnapshot) {
        prefs.edit().putString(KEY_PENDING_FINALIZE_OPERATION, snapshot.toJson()).apply()
    }

    fun clearPendingFinalizeOperation() {
        prefs.edit().remove(KEY_PENDING_FINALIZE_OPERATION).apply()
    }
}
