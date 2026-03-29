package com.demeter.speech.core

import android.content.Context
import com.demeter.speech.BuildConfig
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val SESSION_PREFS = "demeter-session"
private const val COOKIE_STORE_KEY = "cookies"
private const val APP_REFRESH_COOKIE_NAME = "tc_app_refresh"

data class BackendUser(val id: String, val email: String, val status: String)
data class BackendOrganization(val id: String, val name: String, val code: String, val status: String)

data class AuthResponse(
    val user: BackendUser,
    val organization: BackendOrganization,
    val permissions: List<String>,
    val runtimeMode: String,
)

data class MeetingDraftResponseDto(
    val meetingTitle: String,
    val participants: List<String>,
    val selectedFormats: List<String>,
    val reportSourceMode: String,
    val reportProvider: String,
    val modelId: String,
    val generatedAt: String,
    val sourceTokenCount: Int,
    val reports: List<MeetingDraftEnvelopeDto>,
)

data class MeetingDraftEnvelopeDto(
    val format: String,
    val report: JSONObject,
    val raw: String,
    val modelId: String,
    val generatedAt: String,
    val sourceMode: String,
    val provider: String,
    val sourceTokenCount: Int,
)

data class MeetingFinalizeResponseDto(
    val operationId: String,
    val meetingTitle: String,
    val participants: List<String>,
    val transcriptionSourceMode: String,
    val transcriptionProvider: String,
    val reportSourceMode: String,
    val reportProvider: String,
    val selectedFormats: List<String>,
    val sentTo: String,
    val sentToEmails: List<String>,
    val generatedAt: String,
    val transcriptDocxFilename: String,
    val reportDocxFilenames: List<String>,
)

data class DemeterTranscriptionResponseDto(
    val text: String,
    val language: String? = null,
    val duration: Double? = null,
    val segments: List<DemeterTranscriptionSegmentDto> = emptyList(),
)

data class DemeterTranscriptionSegmentDto(
    val speakerId: String? = null,
    val speakerName: String? = null,
    val text: String = "",
    val startMs: Double? = null,
    val endMs: Double? = null,
)

data class MeetingDraftRequest(
    val meetingTitle: String,
    val participants: List<String>,
    val transcriptText: String,
    val selectedFormats: List<ReportFormat>,
    val reportModelId: String,
    val reportTemperature: Float,
    val reportMaxTokens: Int,
)

data class MeetingFinalizeRequest(
    val operationId: String = "",
    val meetingTitle: String,
    val participants: List<String>,
    val transcriptionSourceMode: TranscriptionSourceMode,
    val rawTranscriptText: String,
    val editedTranscriptText: String,
    val selectedFormats: List<ReportFormat>,
    val reportModelId: String,
    val reportTemperature: Float,
    val reportMaxTokens: Int,
    val reports: List<MeetingDraftEnvelopeUi>,
    val speakerAssignments: List<SpeakerAssignmentUi>,
    val recipientEmails: List<String> = emptyList(),
)

data class DemeterTranscriptionRequest(
    val file: File,
    val diarize: Boolean = true,
)

class BackendSessionExpiredException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class PersistentCookieJar(context: Context) : CookieJar {
    private val prefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val existing = loadAll().toMutableList()
        cookies.forEach { cookie ->
            existing.removeAll { it.matches(cookie) }
            existing.add(SerializableCookie.from(cookie))
        }
        prefs.edit().putString(COOKIE_STORE_KEY, JSONArray(existing.map { it.toJson() }).toString()).apply()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val validCookies = loadAll()
            .filter { it.expiresAt <= 0L || it.expiresAt > now }
        if (validCookies.size != loadAll().size) {
            saveAll(validCookies)
        }
        return validCookies
            .mapNotNull { it.toCookie() }
            .filter { it.matches(url) }
    }

    private fun loadAll(): List<SerializableCookie> {
        val raw = prefs.getString(COOKIE_STORE_KEY, "[]").orEmpty()
        val json = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                runCatching { SerializableCookie.fromJson(item) }.getOrNull()?.let { add(it) }
            }
        }
    }

    private fun saveAll(cookies: List<SerializableCookie>) {
        prefs.edit().putString(COOKIE_STORE_KEY, JSONArray(cookies.map { it.toJson() }).toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(COOKIE_STORE_KEY).apply()
    }

    fun hasCookie(name: String): Boolean {
        val now = System.currentTimeMillis()
        return loadAll().any { cookie ->
            cookie.name == name && (cookie.expiresAt <= 0L || cookie.expiresAt > now)
        }
    }
}

private data class SerializableCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("value", value)
        put("expiresAt", expiresAt)
        put("domain", domain)
        put("path", path)
        put("secure", secure)
        put("httpOnly", httpOnly)
        put("hostOnly", hostOnly)
    }

    fun toCookie(): Cookie? {
        val builder = Cookie.Builder()
            .name(name)
            .value(value)
            .path(path)
        if (hostOnly) {
            builder.hostOnlyDomain(domain)
        } else {
            builder.domain(domain)
        }
        if (expiresAt > 0L) {
            builder.expiresAt(expiresAt)
        }
        if (secure) {
            builder.secure()
        }
        if (httpOnly) {
            builder.httpOnly()
        }
        return runCatching { builder.build() }.getOrNull()
    }

    fun matches(cookie: Cookie): Boolean {
        return name == cookie.name && domain == cookie.domain && path == cookie.path
    }

    companion object {
        fun from(cookie: Cookie): SerializableCookie {
            return SerializableCookie(
                name = cookie.name,
                value = cookie.value,
                expiresAt = cookie.expiresAt,
                domain = cookie.domain,
                path = cookie.path,
                secure = cookie.secure,
                httpOnly = cookie.httpOnly,
                hostOnly = cookie.hostOnly,
            )
        }

        fun fromJson(json: JSONObject): SerializableCookie {
            return SerializableCookie(
                name = json.getString("name"),
                value = json.getString("value"),
                expiresAt = json.optLong("expiresAt", 0L),
                domain = json.getString("domain"),
                path = json.optString("path", "/"),
                secure = json.optBoolean("secure", false),
                httpOnly = json.optBoolean("httpOnly", false),
                hostOnly = json.optBoolean("hostOnly", false),
            )
        }
    }
}

class BackendApiClient(
    context: Context,
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL,
) {
    private val appContext = context.applicationContext
    private val prefs = AppPreferences(appContext)
    private val cookieJar = PersistentCookieJar(appContext)
    private val sessionRefreshMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun login(email: String, password: String): AuthResponse = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url("/api/v1/auth/login"))
            .post(body)
            .build()
        executeJson(request) { json ->
            prefs.setLastEmail(email)
            parseAuthResponse(json)
        }
    }

    suspend fun me(): AuthResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url("/api/v1/auth/me")).get().build()
        executeJsonWithSessionRefreshRetry(request, ::parseAuthResponse)
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url("/api/v1/auth/logout"))
            .post(FormBody.Builder().build())
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException(response.body?.string().orEmpty().ifBlank { "HTTP ${response.code}" })
                }
            }
        } finally {
            cookieJar.clear()
        }
    }

    suspend fun generateMeetingDrafts(requestDto: MeetingDraftRequest): MeetingDraftResponseDto = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("meetingTitle", requestDto.meetingTitle)
            .put("participants", JSONArray(requestDto.participants))
            .put("rawTranscriptText", requestDto.transcriptText)
            .put("editedTranscriptText", requestDto.transcriptText)
            .put("selectedFormats", JSONArray(requestDto.selectedFormats.map { it.apiValue }))
            .put("reportModelId", requestDto.reportModelId)
            .put("reportTemperature", requestDto.reportTemperature)
            .put("reportMaxTokens", requestDto.reportMaxTokens)
        val request = Request.Builder()
            .url(url("/api/v1/meetings/reports/drafts"))
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeJsonWithSessionRefreshRetry(request, ::parseMeetingDraftResponse)
    }

    suspend fun finalizeMeeting(requestDto: MeetingFinalizeRequest): MeetingFinalizeResponseDto {
        return finalizeMeeting(requestDto.operationId, buildMeetingFinalizeRequestBody(requestDto))
    }

    suspend fun finalizeMeeting(operationId: String, requestBodyJson: String): MeetingFinalizeResponseDto = withContext(Dispatchers.IO) {
        val normalizedOperationId = operationId.trim()
        if (normalizedOperationId.isBlank()) {
            throw IllegalArgumentException("operationId is required")
        }
        val request = Request.Builder()
            .url(url("/api/v1/meetings/finalize"))
            .header("X-Idempotency-Key", normalizedOperationId)
            .post(requestBodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val result = executeRequestWithSessionRefreshRetry(request)
        if (result.code == 409) {
            throw MeetingFinalizeOperationInProgressException(result.body.ifBlank { "meeting finalization already in progress" })
        }
        if (!result.isSuccessful) {
            throw IllegalStateException(result.body.ifBlank { "HTTP ${result.code}" })
        }
        parseMeetingFinalizeResponse(result.body)
    }

    suspend fun getFinalizeMeetingOperationStatus(operationId: String): MeetingFinalizeOperationStatusDto? = withContext(Dispatchers.IO) {
        val normalizedOperationId = operationId.trim()
        if (normalizedOperationId.isBlank()) {
            throw IllegalArgumentException("operationId is required")
        }
        val request = Request.Builder()
            .url(url("/api/v1/meetings/finalize/operations/$normalizedOperationId"))
            .get()
            .build()
        val result = executeRequestWithSessionRefreshRetry(request)
        if (result.code == 404) {
            return@withContext null
        }
        if (!result.isSuccessful) {
            throw IllegalStateException(result.body.ifBlank { "HTTP ${result.code}" })
        }
        parseMeetingFinalizeOperationStatus(result.body)
    }

    suspend fun transcribeDemeterChunk(requestDto: DemeterTranscriptionRequest): DemeterTranscriptionResponseDto = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("diarize", requestDto.diarize.toString())
            .apply {
                if (requestDto.diarize) {
                    addFormDataPart("timestamp_granularities", "segment")
                }
                addFormDataPart(
                    "file",
                    requestDto.file.name,
                    requestDto.file.asRequestBody(AUDIO_WAV_MEDIA_TYPE)
                )
            }
            .build()
        val request = Request.Builder()
            .url(url("/api/v1/providers/demeter-sante/audio/transcriptions"))
            .post(body)
            .build()
        executeJsonWithSessionRefreshRetry(request, ::parseDemeterTranscriptionResponse)
    }

    fun lastEmail(): String = prefs.lastEmail()

    fun hasStoredRefreshSession(): Boolean {
        return cookieJar.hasCookie(APP_REFRESH_COOKIE_NAME)
    }

    suspend fun refreshSession(): AuthResponse = withContext(Dispatchers.IO) {
        refreshSessionLocked()
    }

    private fun url(path: String): String {
        return resolveBackendRequestUrl(baseUrl, path)
    }

    private fun <T> executeJson(request: Request, parser: (JSONObject) -> T): T {
        val result = executeRequest(request)
        if (!result.isSuccessful) {
            throw IllegalStateException(result.body.ifBlank { "HTTP ${result.code}" })
        }
        val json = if (result.body.isBlank()) JSONObject() else JSONObject(result.body)
        return parser(json)
    }

    private suspend fun <T> executeJsonWithSessionRefreshRetry(request: Request, parser: (JSONObject) -> T): T {
        val result = executeRequestWithSessionRefreshRetry(request)
        if (!result.isSuccessful) {
            throw IllegalStateException(result.body.ifBlank { "HTTP ${result.code}" })
        }
        val json = if (result.body.isBlank()) JSONObject() else JSONObject(result.body)
        return parser(json)
    }

    private suspend fun executeRequestWithSessionRefreshRetry(request: Request): HttpResult {
        val result = executeRequest(request)
        if (result.code == 401) {
            try {
                refreshSessionLocked()
            } catch (error: BackendSessionExpiredException) {
                cookieJar.clear()
                throw error
            }
            val retryResult = executeRequest(request)
            if (retryResult.code == 401) {
                cookieJar.clear()
                throw BackendSessionExpiredException("Session expirée")
            }
            return retryResult
        }
        return result
    }

    private suspend fun refreshSessionLocked(): AuthResponse {
        return sessionRefreshMutex.withLock {
            if (!hasStoredRefreshSession()) {
                cookieJar.clear()
                throw BackendSessionExpiredException("Session expirée")
            }
            val request = Request.Builder()
                .url(url("/api/v1/auth/refresh"))
                .post(FormBody.Builder().build())
                .build()
            try {
                executeJson(request, ::parseAuthResponse)
            } catch (error: Throwable) {
                cookieJar.clear()
                throw BackendSessionExpiredException("Session expirée", error)
            }
        }
    }

    private fun executeRequest(request: Request): HttpResult {
        client.newCall(request).execute().use { response ->
            return HttpResult(
                code = response.code,
                body = response.body?.string().orEmpty(),
            )
        }
    }

    private fun parseAuthResponse(json: JSONObject): AuthResponse {
        return AuthResponse(
            user = BackendUser(
                id = json.optJSONObject("user")?.optString("id").orEmpty(),
                email = json.optJSONObject("user")?.optString("email").orEmpty(),
                status = json.optJSONObject("user")?.optString("status").orEmpty(),
            ),
            organization = BackendOrganization(
                id = json.optJSONObject("organization")?.optString("id").orEmpty(),
                name = json.optJSONObject("organization")?.optString("name").orEmpty(),
                code = json.optJSONObject("organization")?.optString("code").orEmpty(),
                status = json.optJSONObject("organization")?.optString("status").orEmpty(),
            ),
            permissions = json.optJSONArray("permissions").toStringList(),
            runtimeMode = json.optString("runtimeMode").ifBlank { "backend" },
        )
    }

    private data class HttpResult(
        val code: Int,
        val body: String,
    ) {
        val isSuccessful: Boolean
            get() = code in 200..299
    }

    private fun parseMeetingDraftResponse(json: JSONObject): MeetingDraftResponseDto {
        return MeetingDraftResponseDto(
            meetingTitle = json.optString("meetingTitle"),
            participants = json.optJSONArray("participants").toStringList(),
            selectedFormats = json.optJSONArray("selectedFormats").toStringList(),
            reportSourceMode = json.optString("reportSourceMode"),
            reportProvider = json.optString("reportProvider"),
            modelId = json.optString("modelId"),
            generatedAt = json.optString("generatedAt"),
            sourceTokenCount = json.optInt("sourceTokenCount"),
            reports = json.optJSONArray("reports").toMeetingDraftEnvelopeList(),
        )
    }

    private fun parseDemeterTranscriptionResponse(json: JSONObject): DemeterTranscriptionResponseDto {
        return DemeterTranscriptionResponseDto(
            text = json.optString("text"),
            language = json.optString("language").ifBlank { null },
            duration = json.optDoubleOrNull("duration"),
            segments = json.optJSONArray("segments").toDemeterTranscriptionSegments(),
        )
    }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val AUDIO_WAV_MEDIA_TYPE = "audio/wav".toMediaType()

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val value = optString(index).trim()
            if (value.isNotEmpty()) {
                add(value)
            }
        }
    }
}

private fun JSONArray?.toMeetingDraftEnvelopeList(): List<MeetingDraftEnvelopeDto> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val report = item.optJSONObject("report") ?: JSONObject()
            add(
                MeetingDraftEnvelopeDto(
                    format = item.optString("format"),
                    report = report,
                    raw = item.optString("raw"),
                    modelId = item.optString("modelId"),
                    generatedAt = item.optString("generatedAt"),
                    sourceMode = item.optString("sourceMode"),
                    provider = item.optString("provider"),
                    sourceTokenCount = item.optInt("sourceTokenCount"),
                )
            )
        }
    }
}

private fun JSONArray?.toDemeterTranscriptionSegments(): List<DemeterTranscriptionSegmentDto> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val text = item.optString("text").trim()
            if (text.isEmpty()) {
                continue
            }
            add(
                DemeterTranscriptionSegmentDto(
                    speakerId = item.optString("speakerId")
                        .trimToNull()
                        ?: item.optString("speaker_id").trimToNull()
                        ?: item.optString("speaker").trimToNull(),
                    speakerName = item.optString("speakerName")
                        .trimToNull()
                        ?: item.optString("speaker_name").trimToNull(),
                    text = text,
                    startMs = item.optDoubleOrNull("startMs")
                        ?: item.optDoubleOrNull("start_ms")
                        ?: item.optDoubleOrNull("start"),
                    endMs = item.optDoubleOrNull("endMs")
                        ?: item.optDoubleOrNull("end_ms")
                        ?: item.optDoubleOrNull("end"),
                )
            )
        }
    }
}

private fun String.trimToNull(): String? {
    return trim().takeIf { it.isNotBlank() }
}

internal fun MeetingDraftEnvelopeUi.reportToJson(): JSONObject {
    return JSONObject().apply {
        put("format", format.apiValue)
        put("title", report.title)
        put("subtitle", report.subtitle)
        put(
            "sections",
            JSONArray(report.sections.map {
                JSONObject()
                    .put("heading", it.heading)
                    .put("paragraphs", JSONArray(it.paragraphs))
            })
        )
        put("key_points", JSONArray(report.keyPoints))
        put("action_items", JSONArray(report.actionItems))
        put("caveats", JSONArray(report.caveats))
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    return if (has(key) && !isNull(key)) {
        val value = optDouble(key, Double.NaN)
        if (value.isNaN()) null else value
    } else {
        null
    }
}
