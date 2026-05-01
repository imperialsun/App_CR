package com.demeter.speech.core

import com.demeter.speech.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.math.min
import kotlin.random.Random

data class RetryPolicy(
    val maxElapsedMs: Long = 2 * 60 * 60 * 1000L,
    val baseDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 60_000L,
)

data class RetrySignal(
    val attempt: Int,
    val delayMs: Long,
    val reason: String,
)

class BackendApiClient(
    private val preferences: AppPreferences,
    private val networkAvailability: NetworkAvailability = AlwaysAvailableNetwork,
    backendBaseUrl: String = BuildConfig.BACKEND_BASE_URL,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val operationPollIntervalMs: Long = DEFAULT_OPERATION_POLL_INTERVAL_MS,
) {
    private val gson = Gson()
    private val cookieJar = PersistentCookieJar(preferences)
    private val refreshMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()
    private val apiBaseUrl = backendBaseUrl.trim().trimEnd('/').let { configured ->
        if (configured.endsWith("/api/v1")) configured else "$configured/api/v1"
    }

    suspend fun login(email: String, password: String): AuthUser = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("email", email.trim())
            addProperty("password", password)
        }.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$apiBaseUrl/auth/login")
            .post(body)
            .build()
        execute(request, allowRefresh = false).use { response ->
            if (!response.isSuccessful) throw IOException("Identifiants invalides")
            parseAuthUser(response.body.string())
        }
    }

    suspend fun me(): AuthUser? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$apiBaseUrl/auth/me")
            .get()
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) return@withContext null
            parseAuthUser(response.body.string())
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$apiBaseUrl/auth/logout")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        runCatching { execute(request, allowRefresh = false).close() }
        cookieJar.clear()
    }

    suspend fun loadReportDetailLevels(): Pair<Int, ReportDetailLevels> = withContext(Dispatchers.IO) {
        val envelope = loadSettingsEnvelope()
        envelope.schemaVersion to parseDetailLevels(envelope.settings)
    }

    suspend fun saveReportDetailLevels(levels: ReportDetailLevels) = withContext(Dispatchers.IO) {
        val envelope = loadSettingsEnvelope()
        envelope.settings.add("llmApiReportDetailLevels", JsonObject().apply {
            levels.toWireMap().forEach { (format, level) -> addProperty(format, level) }
        })
        val body = JsonObject().apply {
            addProperty("schemaVersion", envelope.schemaVersion)
            add("settings", envelope.settings)
        }.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$apiBaseUrl/settings")
            .put(body)
            .build()
        execute(request).use { response ->
            if (!response.isSuccessful) throw IOException("Impossible d'enregistrer les réglages")
        }
    }

    suspend fun transcribeWithDemeter(
        audio: File,
        operationId: String = UUID.randomUUID().toString(),
        onStatus: suspend (OperationStatus) -> Unit,
    ): List<TranscriptChunk> = withContext(Dispatchers.IO) {
        val uploadedOperationId = uploadAudioSlices(
            audio = audio,
            uploadId = operationId,
            endpoint = "$apiBaseUrl/providers/demeter-sante/audio/transcriptions/backend",
            formFields = emptyMap(),
            onStatus = onStatus,
        )
        pollDemeterOperation(uploadedOperationId, onStatus)
    }

    suspend fun sendCorrectedTranscript(
        title: String,
        rawTranscript: String,
        editedTranscript: String,
        segments: List<TranscriptSegment>,
        detailLevels: ReportDetailLevels,
        selectedFormats: List<ReportFormat>,
        operationId: String = UUID.randomUUID().toString(),
        onStatus: suspend (OperationStatus) -> Unit,
    ): OperationStatus = withContext(Dispatchers.IO) {
        if (selectedFormats.isEmpty()) {
            throw IOException("Aucun format de compte rendu sélectionné")
        }
        val body = JsonObject().apply {
            addProperty("operationId", operationId)
            addProperty("meetingTitle", title)
            addProperty("rawTranscriptText", rawTranscript)
            addProperty("editedTranscriptText", editedTranscript)
            add("selectedFormats", JsonParser.parseString(reportFormatsWireJson(selectedFormats)))
            add("reportDetailLevels", JsonObject().apply {
                detailLevels.toWireMap().forEach { (format, level) -> addProperty(format, level) }
            })
            add("transcriptSegments", JsonArray().apply {
                segments.forEach { segment ->
                    add(JsonObject().apply {
                        addProperty("speakerId", segment.id)
                        addProperty("speakerName", segment.speaker)
                        addProperty("text", segment.text)
                        addProperty("startMs", segment.startMs)
                        addProperty("endMs", segment.endMs)
                    })
                }
            })
        }.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$apiBaseUrl/mobile/reports/email")
            .post(body)
            .build()
        execute(request, onRetry = { onStatus(it.toOperationStatus(operationId)) }).use { response ->
            if (!response.isSuccessful) throw IOException("Impossible de lancer l'envoi email")
            val status = parseMobileOperation(response.body.string())
            onStatus(status)
            pollMobileOperation(status.operationId, onStatus)
        }
    }

    suspend fun uploadAudioForBackendReports(
        audio: File,
        title: String,
        detailLevels: ReportDetailLevels,
        selectedFormats: List<ReportFormat>,
        operationId: String = UUID.randomUUID().toString(),
        onStatus: suspend (OperationStatus) -> Unit,
    ): OperationStatus = withContext(Dispatchers.IO) {
        if (selectedFormats.isEmpty()) {
            throw IOException("Aucun format de compte rendu sélectionné")
        }
        val uploadedOperationId = uploadAudioSlices(
            audio = audio,
            uploadId = operationId,
            endpoint = "$apiBaseUrl/mobile/audio/reports/backend",
            formFields = mapOf(
                "meetingTitle" to title,
                "selectedFormats" to reportFormatsWireJson(selectedFormats),
                "reportDetailLevels" to gson.toJson(detailLevels.toWireMap()),
            ),
            onStatus = onStatus,
        )
        pollMobileOperation(uploadedOperationId, onStatus)
    }

    private suspend fun uploadAudioSlices(
        audio: File,
        uploadId: String,
        endpoint: String,
        formFields: Map<String, String>,
        onStatus: suspend (OperationStatus) -> Unit,
    ): String {
        val chunkSize = 5 * 1024 * 1024
        val chunkCount = ((audio.length() + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
        RandomAccessFile(audio, "r").use { input ->
            repeat(chunkCount) { index ->
                val remaining = audio.length() - (index.toLong() * chunkSize)
                val size = remaining.coerceAtMost(chunkSize.toLong()).toInt()
                val bytes = ByteArray(size)
                input.readFully(bytes)
                val final = index == chunkCount - 1
                val bodyBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        audio.name,
                        bytes.toRequestBody(mimeForAudio(audio).toMediaTypeOrNull()),
                    )
                    .addFormDataPart("model", "voxtral-mini-latest")
                    .addFormDataPart("diarize", "true")
                formFields.forEach { (key, value) ->
                    bodyBuilder.addFormDataPart(key, value)
                }
                val request = Request.Builder()
                    .url(endpoint)
                    .header("X-Demeter-Transport", "slice-v1")
                    .header("X-Demeter-Upload-Id", uploadId)
                    .header("X-Demeter-Upload-Index", index.toString())
                    .header("X-Demeter-Upload-Count", chunkCount.toString())
                    .header("X-Demeter-Upload-Final", final.toString())
                    .post(bodyBuilder.build())
                    .build()
                execute(
                    request,
                    onRetry = { signal ->
                        onStatus(signal.toOperationStatus(uploadId, chunkIndex = index + 1, chunkCount = chunkCount))
                    },
                ).use { response ->
                    if (!response.isSuccessful) throw IOException("Upload audio impossible")
                    val responseBody = response.body.string()
                    if (responseBody.isNotBlank()) {
                        onStatus(parseAnyOperation(responseBody, uploadId, index + 1, chunkCount))
                    } else {
                        onStatus(OperationStatus(operationId = uploadId, status = "uploading", stage = "uploading", progress = (index + 1).toDouble() / chunkCount, chunkIndex = index + 1, chunkCount = chunkCount))
                    }
                }
            }
        }
        return uploadId
    }

    private suspend fun pollDemeterOperation(
        operationId: String,
        onStatus: suspend (OperationStatus) -> Unit,
    ): List<TranscriptChunk> {
        while (true) {
            delay(operationPollIntervalMs)
            val request = Request.Builder()
                .url("$apiBaseUrl/providers/demeter-sante/audio/transcriptions/operations/$operationId")
                .get()
                .build()
            execute(request, onRetry = { onStatus(it.toOperationStatus(operationId)) }).use { response ->
                if (!response.isSuccessful) throw IOException("Transcription indisponible")
                val json = response.body.string()
                val status = parseDemeterStatus(json)
                onStatus(status)
                if (status.status == "completed") return parseTranscriptChunks(json)
                if (status.status == "failed" || status.status == "cancelled") {
                    throw IOException(status.lastError.ifBlank { "Transcription echouee" })
                }
            }
        }
    }

    private suspend fun pollMobileOperation(
        operationId: String,
        onStatus: suspend (OperationStatus) -> Unit,
    ): OperationStatus {
        while (true) {
            delay(operationPollIntervalMs)
            val request = Request.Builder()
                .url("$apiBaseUrl/mobile/operations/$operationId")
                .get()
                .build()
            execute(request, onRetry = { onStatus(it.toOperationStatus(operationId)) }).use { response ->
                if (!response.isSuccessful) throw IOException("Operation mobile indisponible")
                val status = parseMobileOperation(response.body.string())
                onStatus(status)
                if (status.status == "completed") return status
                if (status.status == "failed" || status.status == "cancelled") {
                    throw IOException(status.lastError.ifBlank { "Operation echouee" })
                }
            }
        }
    }

    private fun loadSettingsEnvelope(): SettingsEnvelope {
        val request = Request.Builder()
            .url("$apiBaseUrl/settings")
            .get()
            .build()
        executeBlocking(request).use { response ->
            if (!response.isSuccessful) return SettingsEnvelope()
            val root = JsonParser.parseString(response.body.string()).asJsonObject
            return SettingsEnvelope(
                schemaVersion = root.get("schemaVersion")?.asInt ?: 1,
                settings = root.getAsJsonObject("settings") ?: JsonObject(),
            )
        }
    }

    private fun executeBlocking(request: Request): Response {
        return runBlocking { execute(request) }
    }

    private suspend fun execute(
        request: Request,
        allowRefresh: Boolean = true,
        onRetry: (suspend (RetrySignal) -> Unit)? = null,
    ): Response {
        val startedAt = System.currentTimeMillis()
        var attempt = 0
        while (true) {
            if (!networkAvailability.awaitAvailable(retryPolicy.maxDelayMs)) {
                attempt += 1
                ensureRetryBudget(startedAt)
                onRetry?.invoke(RetrySignal(attempt, retryPolicy.maxDelayMs, "Connexion perdue"))
                continue
            }
            try {
                val response = executeOnce(request, allowRefresh)
                if (!response.isTransientFailure()) return response
                val retryAfterMs = response.retryAfterMs()
                val reason = "HTTP ${response.code}"
                response.close()
                attempt += 1
                val delayMs = retryDelay(attempt, retryAfterMs)
                ensureRetryBudget(startedAt, delayMs)
                onRetry?.invoke(RetrySignal(attempt, delayMs, reason))
                delay(delayMs)
            } catch (error: IOException) {
                attempt += 1
                val delayMs = retryDelay(attempt)
                ensureRetryBudget(startedAt, delayMs)
                onRetry?.invoke(RetrySignal(attempt, delayMs, error.message ?: "Incident réseau"))
                delay(delayMs)
            }
        }
    }

    private suspend fun executeOnce(request: Request, allowRefresh: Boolean): Response {
        val response = client.newCall(request).execute()
        if (response.code != 401 || !allowRefresh) return response
        response.close()
        if (!refreshSession()) return client.newCall(request).execute()
        return client.newCall(request).execute()
    }

    private suspend fun refreshSession(): Boolean {
        return refreshMutex.withLock {
            val request = Request.Builder()
                .url("$apiBaseUrl/auth/refresh")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            execute(request, allowRefresh = false).use { it.isSuccessful }
        }
    }

    private fun Response.isTransientFailure(): Boolean = code in transientStatusCodes

    private fun Response.retryAfterMs(): Long? {
        val raw = header("Retry-After")?.trim() ?: return null
        return raw.toLongOrNull()?.let { TimeUnit.SECONDS.toMillis(it) }
    }

    private fun retryDelay(attempt: Int, retryAfterMs: Long? = null): Long {
        val exponential = retryPolicy.baseDelayMs * (1L shl min(attempt - 1, 5))
        val capped = min(exponential, retryPolicy.maxDelayMs)
        val jitter = Random.nextLong(0L, (capped / 4).coerceAtLeast(1L))
        return retryAfterMs?.coerceAtMost(retryPolicy.maxDelayMs) ?: (capped + jitter).coerceAtMost(retryPolicy.maxDelayMs)
    }

    private fun ensureRetryBudget(startedAt: Long, nextDelayMs: Long = 0L) {
        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed + nextDelayMs > retryPolicy.maxElapsedMs) {
            throw IOException("Incident réseau prolongé, réessayez plus tard")
        }
    }

    private fun RetrySignal.toOperationStatus(
        operationId: String,
        chunkIndex: Int = 0,
        chunkCount: Int = 0,
    ): OperationStatus {
        val seconds = (delayMs / 1000L).coerceAtLeast(1L)
        return OperationStatus(
            operationId = operationId,
            status = "retrying",
            stage = "network_retry",
            progress = if (chunkCount > 0) (chunkIndex - 1).coerceAtLeast(0).toDouble() / chunkCount else 0.0,
            chunkIndex = chunkIndex,
            chunkCount = chunkCount,
            message = "Connexion perdue, nouvelle tentative $attempt dans ${seconds}s",
            lastError = reason,
            retryAttempt = attempt,
        )
    }

    private fun parseAuthUser(raw: String): AuthUser {
        val root = JsonParser.parseString(raw).asJsonObject
        val user = root.getAsJsonObject("user")
        return AuthUser(email = user?.get("email")?.asString.orEmpty())
    }

    private fun parseDetailLevels(settings: JsonObject): ReportDetailLevels {
        val levels = settings.getAsJsonObject("llmApiReportDetailLevels") ?: JsonObject()
        return ReportDetailLevels(
            cri = DetailLevel.fromWire(levels.get("CRI")?.asString ?: levels.get("cri")?.asString),
            cro = DetailLevel.fromWire(levels.get("CRO")?.asString ?: levels.get("cro")?.asString),
            crs = DetailLevel.fromWire(levels.get("CRS")?.asString ?: levels.get("crs")?.asString),
            crn = DetailLevel.fromWire(levels.get("CRN")?.asString ?: levels.get("crn")?.asString),
        )
    }

    private fun reportFormatsWireJson(formats: List<ReportFormat>): String {
        return gson.toJson(formats.map { it.wire })
    }

    private fun parseAnyOperation(raw: String, fallbackId: String, chunkIndex: Int, chunkCount: Int): OperationStatus {
        return runCatching { parseMobileOperation(raw) }
            .recoverCatching { parseDemeterStatus(raw) }
            .getOrDefault(OperationStatus(operationId = fallbackId, status = "uploading", stage = "uploading", chunkIndex = chunkIndex, chunkCount = chunkCount))
    }

    private fun parseMobileOperation(raw: String): OperationStatus {
        val root = JsonParser.parseString(raw).asJsonObject
        return OperationStatus(
            operationId = root.get("operationId")?.asString.orEmpty(),
            status = root.get("status")?.asString.orEmpty(),
            stage = root.get("stage")?.asString.orEmpty(),
            progress = root.get("progress")?.asDouble ?: 0.0,
            chunkIndex = root.get("chunkIndex")?.asInt ?: 0,
            chunkCount = root.get("chunkCount")?.asInt ?: 0,
            message = root.get("message")?.asString.orEmpty(),
            lastError = root.get("lastError")?.asString.orEmpty(),
            files = root.getAsJsonArray("files")?.map { item ->
                val file = item.asJsonObject
                SentFile(
                    filename = file.get("filename")?.asString.orEmpty(),
                    contentType = file.get("contentType")?.asString.orEmpty(),
                    sizeBytes = file.get("sizeBytes")?.asInt ?: 0,
                )
            }.orEmpty(),
        )
    }

    private fun parseDemeterStatus(raw: String): OperationStatus {
        val root = JsonParser.parseString(raw).asJsonObject
        return OperationStatus(
            operationId = root.get("operationId")?.asString.orEmpty(),
            status = root.get("status")?.asString.orEmpty(),
            stage = root.get("stage")?.asString.orEmpty(),
            progress = root.get("progress")?.asDouble ?: 0.0,
            chunkIndex = root.get("chunkIndex")?.asInt ?: 0,
            chunkCount = root.get("chunkCount")?.asInt ?: 0,
            lastError = root.get("lastError")?.asString.orEmpty(),
        )
    }

    private fun parseTranscriptChunks(raw: String): List<TranscriptChunk> {
        val root = JsonParser.parseString(raw).asJsonObject
        val response = root.getAsJsonObject("response") ?: return emptyList()
        val chunks = response.getAsJsonArray("chunks") ?: JsonArray()
        if (chunks.size() == 0) {
            val text = response.get("text")?.asString.orEmpty()
            return listOf(TranscriptChunk(0, text, listOf(TranscriptSegment("speaker_1", 0, "", text))))
        }
        return chunks.mapIndexed { chunkPosition, chunkItem ->
            val chunk = chunkItem.asJsonObject
            val chunkIndex = chunk.get("index")?.asInt ?: chunkPosition
            val segments = chunk.getAsJsonArray("segments")?.mapIndexed { segmentPosition, segmentItem ->
                val segment = segmentItem.asJsonObject
                val speakerLabel = segment.get("speaker_id")?.asString
                    ?: segment.get("speaker")?.asString
                    ?: "Interlocuteur ${segmentPosition + 1}"
                TranscriptSegment(
                    id = "chunk_${chunkIndex}_segment_$segmentPosition",
                    chunkIndex = chunkIndex,
                    speaker = speakerLabel,
                    text = segment.get("text")?.asString.orEmpty(),
                    startMs = (segment.get("start")?.asDouble ?: 0.0) * 1000.0,
                    endMs = (segment.get("end")?.asDouble ?: 0.0) * 1000.0,
                )
            }.orEmpty()
            TranscriptChunk(
                index = chunkIndex,
                text = chunk.get("text")?.asString.orEmpty(),
                segments = segments.ifEmpty {
                    listOf(TranscriptSegment("chunk_${chunkIndex}_segment_0", chunkIndex, "", chunk.get("text")?.asString.orEmpty()))
                },
                startMs = (chunk.get("startSec")?.asDouble ?: chunk.get("start_sec")?.asDouble ?: 0.0) * 1000.0,
                endMs = (chunk.get("endSec")?.asDouble ?: chunk.get("end_sec")?.asDouble ?: 0.0) * 1000.0,
            )
        }
    }

    private fun mimeForAudio(file: File): String {
        return when (file.extension.lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "webm" -> "audio/webm"
            else -> "application/octet-stream"
        }
    }

    private companion object {
        const val DEFAULT_OPERATION_POLL_INTERVAL_MS = 10_000L
        val transientStatusCodes = setOf(408, 429, 500, 502, 503, 504)
        val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}

private data class SettingsEnvelope(
    val schemaVersion: Int = 1,
    val settings: JsonObject = JsonObject(),
)

private class PersistentCookieJar(
    private val preferences: AppPreferences,
) : CookieJar {
    private val lock = Any()
    private var cache: MutableList<Cookie> = runBlocking {
        preferences.loadCookies().mapNotNull { it.toCookie() }.toMutableList()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            cache = cache
                .filter { it.expiresAt > now }
                .filterNot { stored -> cookies.any { it.name == stored.name && it.domain == stored.domain && it.path == stored.path } }
                .toMutableList()
            cache.addAll(cookies)
            persistLocked()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            cache = cache.filter { it.expiresAt > now }.toMutableList()
            persistLocked()
            return cache.filter { it.matches(url) }
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
            runBlocking { preferences.clearCookies() }
        }
    }

    private fun persistLocked() {
        runBlocking {
            preferences.saveCookies(cache.map { it.toStoredCookie() })
        }
    }
}

private fun Cookie.toStoredCookie(): StoredCookie {
    return StoredCookie(
        name = name,
        value = value,
        domain = domain,
        path = path,
        expiresAt = expiresAt,
        secure = secure,
        httpOnly = httpOnly,
        hostOnly = hostOnly,
    )
}

private fun StoredCookie.toCookie(): Cookie? {
    return runCatching {
        Cookie.Builder()
            .name(name)
            .value(value)
            .path(path)
            .expiresAt(expiresAt)
            .apply {
                if (hostOnly) hostOnlyDomain(domain) else domain(domain)
                if (secure) secure()
                if (httpOnly) httpOnly()
            }
            .build()
    }.getOrNull()
}
