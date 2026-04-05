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
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
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
private const val DEMETER_TRANSCRIPTIONS_PATH = "/api/v1/providers/demeter-sante/audio/transcriptions"
private const val DEMETER_TRANSCRIPTIONS_BACKEND_PATH = "/api/v1/providers/demeter-sante/audio/transcriptions/backend"
private const val DEMETER_TRANSCRIPTIONS_OPERATIONS_PATH = "/api/v1/providers/demeter-sante/audio/transcriptions/operations"
private const val DEMETER_UPLOAD_SLICE_SIZE_BYTES = 5L * 1024L * 1024L
private const val DEMETER_LONG_AUDIO_BACKEND_THRESHOLD_SEC = 2 * 60 * 60
private const val DEMETER_UPLOAD_REQUEST_TIMEOUT_MS = 60_000L
private const val DEMETER_UPLOAD_STATUS_TIMEOUT_MS = 15_000L
private const val DEMETER_UPLOAD_STATUS_POLL_INTERVAL_MS = 10_000L
private const val DEMETER_WAV_HEADER_BYTES = 44L
private const val DEMETER_WAV_BYTES_PER_SECOND = 16_000L * 2L

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

data class DemeterBackendTranscriptionOperationResponseDto(
    val operationId: String,
    val status: String,
    val statusCode: Int,
    val stage: String,
    val chunkIndex: Int,
    val chunkCount: Int,
    val progress: Double,
    val partialText: String? = null,
    val lastError: String? = null,
    val updatedAt: String = "",
    val finishedAt: String = "",
    val response: DemeterTranscriptionResponseDto? = null,
)

data class BackendHttpError(
    val status: Int,
    val code: String,
    override val message: String,
    val path: String,
    val method: String,
    val traceId: String? = null,
) : RuntimeException(message)

class BackendSessionExpiredException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private class FileSliceRequestBody(
    private val file: File,
    private val offsetBytes: Long,
    private val lengthBytes: Long,
    private val mediaType: okhttp3.MediaType,
) : RequestBody() {
    override fun contentType() = mediaType

    override fun contentLength(): Long = lengthBytes

    override fun writeTo(sink: BufferedSink) {
        FileInputStream(file).use { input ->
            input.skipFully(offsetBytes)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = lengthBytes
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read < 0) {
                    throw IOException("Unexpected EOF while streaming audio slice")
                }
                sink.write(buffer, 0, read)
                remaining -= read.toLong()
            }
        }
    }
}

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
        val file = requestDto.file
        if (!file.exists() || file.length() <= 0L) {
            throw BackendHttpError(
                status = 400,
                code = "empty_audio_file",
                message = "Fichier audio vide.",
                path = demeterTranscriptionEndpointPath(file),
                method = "POST",
            )
        }

        val audioDurationSec = estimateDemeterWavDurationSeconds(file)
        val backendDirect = audioDurationSec > DEMETER_LONG_AUDIO_BACKEND_THRESHOLD_SEC
        val endpointPath = if (backendDirect) DEMETER_TRANSCRIPTIONS_BACKEND_PATH else DEMETER_TRANSCRIPTIONS_PATH
        val uploadId = createDemeterUploadId()
        val sliceCount = maxOf(1L, (file.length() + DEMETER_UPLOAD_SLICE_SIZE_BYTES - 1) / DEMETER_UPLOAD_SLICE_SIZE_BYTES)
        var operationId = uploadId

        for (sliceIndex in 0 until sliceCount.toInt()) {
            val start = sliceIndex.toLong() * DEMETER_UPLOAD_SLICE_SIZE_BYTES
            val end = minOf(file.length(), start + DEMETER_UPLOAD_SLICE_SIZE_BYTES)
            val body = buildDemeterSliceMultipartBody(requestDto, file, start, end)
            val request = Request.Builder()
                .url(url(endpointPath))
                .header("X-Demeter-Transport", "slice-v1")
                .header("X-Demeter-Upload-Id", uploadId)
                .header("X-Demeter-Upload-Index", sliceIndex.toString())
                .header("X-Demeter-Upload-Count", sliceCount.toString())
                .header("X-Demeter-Upload-Final", (sliceIndex == sliceCount.toInt() - 1).toString())
                .post(body)
                .build()

            val result = executeRequestWithSessionRefreshRetry(request, DEMETER_UPLOAD_REQUEST_TIMEOUT_MS)
            if (!result.isSuccessful) {
                throw parseBackendHttpError(
                    status = result.code,
                    method = "POST",
                    path = endpointPath,
                    body = result.body,
                )
            }

            if (sliceIndex < sliceCount.toInt() - 1) {
                continue
            }

            val startPayload = if (result.body.isBlank()) null else parseDemeterBackendTranscriptionOperationResponse(result.body)
            if (startPayload != null) {
                val returnedOperationId = startPayload.operationId.trim()
                if (returnedOperationId.isBlank()) {
                    throw IllegalStateException("Réponse backend Demeter invalide: operationId manquant")
                }
                if (returnedOperationId != uploadId) {
                    throw IllegalStateException("Réponse backend Demeter incohérente: operationId différent")
                }
                operationId = returnedOperationId
            }
        }

        val statusPath = "$DEMETER_TRANSCRIPTIONS_OPERATIONS_PATH/$operationId"
        while (true) {
            delay(DEMETER_UPLOAD_STATUS_POLL_INTERVAL_MS)
            val request = Request.Builder()
                .url(url(statusPath))
                .get()
                .build()
            val result = executeRequestWithSessionRefreshRetry(request, DEMETER_UPLOAD_STATUS_TIMEOUT_MS)
            if (!result.isSuccessful) {
                if (result.code in setOf(408, 429, 500, 502, 503, 504)) {
                    continue
                }
                throw parseBackendHttpError(
                    status = result.code,
                    method = "GET",
                    path = statusPath,
                    body = result.body,
                )
            }

            val snapshot = parseDemeterBackendTranscriptionOperationResponse(result.body)
            val returnedOperationId = snapshot.operationId.trim()
            if (returnedOperationId.isBlank()) {
                throw IllegalStateException("Réponse backend Demeter invalide: operationId manquant dans le statut")
            }
            if (returnedOperationId != operationId) {
                throw IllegalStateException("Réponse backend Demeter incohérente: operationId différent")
            }

            when (snapshot.status) {
                "completed" -> {
                    val finalResponse = snapshot.response
                        ?: throw IllegalStateException("Backend transcription completed without response payload")
                    return@withContext finalResponse
                }
                "failed", "cancelled" -> {
                    val statusCode = if (snapshot.statusCode > 0) snapshot.statusCode else if (snapshot.status == "cancelled") 408 else 500
                    throw BackendHttpError(
                        status = statusCode,
                        code = if (snapshot.status == "cancelled") "cancelled" else "backend_transcription_failed",
                        message = snapshot.lastError?.trim().orEmpty().ifBlank { "Backend transcription operation ${snapshot.status}" },
                        path = statusPath,
                        method = "GET",
                    )
                }
            }
        }
    }

    fun demeterTranscriptionEndpointPath(file: File): String {
        val durationSec = estimateDemeterWavDurationSeconds(file)
        return if (durationSec > DEMETER_LONG_AUDIO_BACKEND_THRESHOLD_SEC) {
            DEMETER_TRANSCRIPTIONS_BACKEND_PATH
        } else {
            DEMETER_TRANSCRIPTIONS_PATH
        }
    }

    suspend fun submitSupportErrorReport(payload: JSONObject): Unit = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url("/api/v1/support/frontend-error-reports"))
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val result = executeRequestWithSessionRefreshRetry(request)
        if (!result.isSuccessful) {
            throw IllegalStateException(result.body.ifBlank { "HTTP ${result.code}" })
        }
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

    private fun estimateDemeterWavDurationSeconds(file: File): Double {
        val payloadBytes = (file.length() - DEMETER_WAV_HEADER_BYTES).coerceAtLeast(0L)
        return payloadBytes.toDouble() / DEMETER_WAV_BYTES_PER_SECOND.toDouble()
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

    private suspend fun executeRequestWithSessionRefreshRetry(request: Request, timeoutMs: Long? = null): HttpResult {
        val result = executeRequest(request, timeoutMs)
        if (result.code == 401) {
            try {
                refreshSessionLocked()
            } catch (error: BackendSessionExpiredException) {
                cookieJar.clear()
                throw error
            }
            val retryResult = executeRequest(request, timeoutMs)
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

    private fun executeRequest(request: Request, timeoutMs: Long? = null): HttpResult {
        val call = client.newCall(request)
        if (timeoutMs != null && timeoutMs > 0L) {
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        }
        call.execute().use { response ->
            return HttpResult(
                code = response.code,
                body = response.body?.string().orEmpty(),
            )
        }
    }

    private fun buildDemeterSliceMultipartBody(
        requestDto: DemeterTranscriptionRequest,
        file: File,
        startBytes: Long,
        endBytes: Long,
    ): MultipartBody {
        val sliceBody = FileSliceRequestBody(
            file = file,
            offsetBytes = startBytes,
            lengthBytes = endBytes - startBytes,
            mediaType = AUDIO_WAV_MEDIA_TYPE,
        )
        return MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("diarize", requestDto.diarize.toString())
            .apply {
                if (requestDto.diarize) {
                    addFormDataPart("timestamp_granularities", "segment")
                }
            }
            .addFormDataPart("file", requestDto.file.name, sliceBody)
            .build()
    }

    private fun createDemeterUploadId(): String {
        return java.util.UUID.randomUUID().toString()
    }

    private fun parseBackendHttpError(status: Int, method: String, path: String, body: String): BackendHttpError {
        val json = runCatching { if (body.isBlank()) JSONObject() else JSONObject(body) }.getOrElse { JSONObject() }
        val message = json.optString("error")
            .ifBlank { json.optString("message") }
            .ifBlank { body.trim() }
            .ifBlank { "HTTP $status" }
        val code = json.optString("code").ifBlank { "http_$status" }
        return BackendHttpError(
            status = status,
            code = code,
            message = message,
            path = json.optString("path").ifBlank { path },
            method = json.optString("method").ifBlank { method },
            traceId = json.optString("traceId").trimToNull(),
        )
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

    private fun parseDemeterBackendTranscriptionOperationResponse(rawJson: String): DemeterBackendTranscriptionOperationResponseDto {
        val json = if (rawJson.isBlank()) JSONObject() else JSONObject(rawJson)
        return DemeterBackendTranscriptionOperationResponseDto(
            operationId = json.optString("operationId"),
            status = json.optString("status"),
            statusCode = json.optInt("statusCode"),
            stage = json.optString("stage"),
            chunkIndex = json.optInt("chunkIndex"),
            chunkCount = json.optInt("chunkCount"),
            progress = json.optDoubleOrNull("progress") ?: 0.0,
            partialText = json.optString("partialText").trimToNull(),
            lastError = json.optString("lastError").trimToNull(),
            updatedAt = json.optString("updatedAt"),
            finishedAt = json.optString("finishedAt"),
            response = json.optJSONObject("response")?.let(::parseDemeterTranscriptionResponse),
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

private fun InputStream.skipFully(bytesToSkip: Long) {
    var remaining = bytesToSkip
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
            continue
        }
        if (read() == -1) {
            throw IOException("Unexpected EOF while skipping audio slice")
        }
        remaining -= 1
    }
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
