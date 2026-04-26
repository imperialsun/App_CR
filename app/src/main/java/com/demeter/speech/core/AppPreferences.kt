package com.demeter.speech.core

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "demeter_mobile")

class AppPreferences(private val context: Context) {
    private val gson = Gson()

    suspend fun loadCookies(): List<StoredCookie> {
        val value = context.dataStore.data.first()[cookieKey].orEmpty()
        if (value.isBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<StoredCookie>>() {}.type
            gson.fromJson<List<StoredCookie>>(value, type)
        }.getOrDefault(emptyList())
    }

    suspend fun saveCookies(cookies: List<StoredCookie>) {
        context.dataStore.edit { prefs ->
            prefs[cookieKey] = gson.toJson(cookies)
        }
    }

    suspend fun clearCookies() {
        context.dataStore.edit { prefs ->
            prefs.remove(cookieKey)
        }
    }

    suspend fun loadProcessingState(): ProcessingTaskState? {
        val value = context.dataStore.data.first()[processingStateKey].orEmpty()
        if (value.isBlank()) return null
        return runCatching {
            gson.fromJson(value, ProcessingTaskState::class.java).sanitizedOrNull()
        }.getOrNull()
    }

    suspend fun saveProcessingState(state: ProcessingTaskState) {
        context.dataStore.edit { prefs ->
            prefs[processingStateKey] = gson.toJson(state)
        }
    }

    suspend fun clearProcessingState() {
        context.dataStore.edit { prefs ->
            prefs.remove(processingStateKey)
        }
    }

    private companion object {
        val cookieKey = stringPreferencesKey("cookies")
        val processingStateKey = stringPreferencesKey("processing_state")
    }
}

@Suppress("USELESS_ELVIS", "USELESS_CAST")
private fun ProcessingTaskState.sanitizedOrNull(): ProcessingTaskState? {
    val safeKind = kind ?: return null
    val safePhase = phase ?: return null
    return copy(
        kind = safeKind,
        phase = safePhase,
        operationId = (operationId as String?).orEmpty(),
        audioPath = (audioPath as String?).orEmpty(),
        audioDisplayName = (audioDisplayName as String?).orEmpty(),
        audioOrigin = audioOrigin ?: AudioOrigin.Imported,
        title = (title as String?).orEmpty(),
        reportPayloadPath = (reportPayloadPath as String?).orEmpty(),
        detailLevels = detailLevels ?: ReportDetailLevels(),
        operation = operation?.sanitized(),
        retryMessage = (retryMessage as String?).orEmpty(),
        waitJoke = (waitJoke as String?).orEmpty(),
        chunks = chunks ?: emptyList(),
        segments = segments ?: emptyList(),
        files = files ?: emptyList(),
        error = error as String?,
    )
}

@Suppress("USELESS_ELVIS")
private fun OperationStatus.sanitized(): OperationStatus {
    return copy(
        operationId = (operationId as String?).orEmpty(),
        status = (status as String?).orEmpty(),
        stage = (stage as String?).orEmpty(),
        message = (message as String?).orEmpty(),
        lastError = (lastError as String?).orEmpty(),
        files = files ?: emptyList(),
    )
}

data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
)
