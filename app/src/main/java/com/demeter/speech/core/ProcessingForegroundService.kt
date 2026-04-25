package com.demeter.speech.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.demeter.speech.DemeterSpeechApplication
import com.demeter.speech.MainActivity
import com.demeter.speech.R
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ProcessingForegroundService : Service() {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        if (worker?.isActive == true) return START_REDELIVER_INTENT
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Préparation", 0.0, true, ongoing = true))
        worker = scope.launch {
            runCatching {
                when (intent.action) {
                    ACTION_TRANSCRIBE_WITH_SPEAKERS -> runTranscription(intent)
                    ACTION_REPORTS_WITH_SPEAKERS -> runReportsWithSpeakers(intent)
                    ACTION_REPORTS_FROM_AUDIO -> runReportsFromAudio(intent)
                    else -> Unit
                }
            }.onFailure { error ->
                val failed = failureState(intent, error.message ?: "Traitement impossible")
                publishState(failed, persist = false)
                showFinalNotification(failed.error ?: "Traitement impossible")
            }
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        worker?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runTranscription(intent: Intent) {
        val api = api()
        val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH).orEmpty()
        val audio = File(audioPath)
        val base = baseState(intent, ProcessingTaskKind.TranscriptionWithSpeakers)
        publishState(base, persist = true)
        val chunks = api.transcribeWithDemeter(audio, operationId = base.operationId) { status ->
            publishProgress(base, status)
        }
        val segments = chunks.flatMap { it.segments }.ifEmpty {
            chunks.mapIndexed { index, chunk ->
                TranscriptSegment("speaker_${index + 1}", chunk.index, "", chunk.text)
            }
        }
        val completed = base.copy(
            phase = ProcessingTaskPhase.Completed,
            chunks = chunks,
            segments = segments,
            waitJoke = jokes[(chunks.size + segments.size).mod(jokes.size)],
        )
        publishState(completed, persist = false)
        showFinalNotification("Transcription terminée")
    }

    private suspend fun runReportsWithSpeakers(intent: Intent) {
        val api = api()
        val base = baseState(intent, ProcessingTaskKind.ReportsWithSpeakers)
        val payloadFile = intent.getStringExtra(EXTRA_REPORT_PAYLOAD_PATH)?.let(::File)
        val payloadStore = ReportPayloadStore(this)
        publishState(base, persist = true)
        val payload = payloadStore.read(requireNotNull(payloadFile) { "Compte rendu temporaire introuvable" })
        val finalStatus = api.sendCorrectedTranscript(
            title = payload.title,
            rawTranscript = payload.rawTranscript,
            editedTranscript = payload.editedTranscript,
            segments = payload.segments,
            detailLevels = payload.detailLevels,
            operationId = base.operationId,
        ) { status -> publishProgress(base, status) }
        payloadStore.delete(payloadFile)
        publishReportSuccess(base, finalStatus)
    }

    private suspend fun runReportsFromAudio(intent: Intent) {
        val api = api()
        val base = baseState(intent, ProcessingTaskKind.ReportsFromAudio)
        val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH).orEmpty()
        val audio = File(audioPath)
        publishState(base, persist = true)
        val finalStatus = api.uploadAudioForBackendReports(
            audio = audio,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            detailLevels = readDetailLevels(intent),
            operationId = base.operationId,
        ) { status -> publishProgress(base, status) }
        if (base.audioOrigin == AudioOrigin.Imported) {
            runCatching { audio.delete() }
        }
        publishReportSuccess(base, finalStatus)
    }

    private suspend fun publishReportSuccess(base: ProcessingTaskState, finalStatus: OperationStatus) {
        val completed = base.copy(
            phase = ProcessingTaskPhase.Completed,
            operation = finalStatus,
            files = finalStatus.files,
            successCanSaveAudio = base.audioOrigin == AudioOrigin.Recorded,
            waitJoke = jokes[(finalStatus.files.size + finalStatus.stage.length).mod(jokes.size)],
        )
        publishState(completed, persist = false)
        showFinalNotification("Comptes rendus envoyés")
    }

    private suspend fun publishProgress(base: ProcessingTaskState, status: OperationStatus) {
        val next = base.copy(
            phase = ProcessingTaskPhase.Running,
            operation = status,
            retryAttempt = if (status.stage == "network_retry") status.retryAttempt else 0,
            retryMessage = if (status.stage == "network_retry") status.message else "",
            waitJoke = jokes[(status.stage.length + status.status.length + status.chunkIndex + status.chunkCount).mod(jokes.size)],
        )
        publishState(next, persist = true)
        updateNotification(status)
    }

    private suspend fun publishState(state: ProcessingTaskState, persist: Boolean) {
        ProcessingTaskEvents.publish(state)
        if (persist && state.phase == ProcessingTaskPhase.Running) {
            preferences().saveProcessingState(state)
        } else {
            preferences().clearProcessingState()
        }
    }

    private fun updateNotification(status: OperationStatus) {
        val text = status.notificationLabel()
        val progress = status.progress.coerceIn(0.0, 1.0)
        val indeterminate = progress <= 0.0 || status.isQueueStage()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, progress, indeterminate, ongoing = true))
    }

    private fun showFinalNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, 1.0, indeterminate = false, ongoing = false))
    }

    private fun buildNotification(
        text: String,
        progress: Double,
        indeterminate: Boolean,
        ongoing: Boolean,
    ): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Demeter Sante")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, (progress.coerceIn(0.0, 1.0) * 100).toInt(), indeterminate)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.processing_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun baseState(intent: Intent, kind: ProcessingTaskKind): ProcessingTaskState {
        val origin = runCatching {
            AudioOrigin.valueOf(intent.getStringExtra(EXTRA_AUDIO_ORIGIN).orEmpty())
        }.getOrDefault(AudioOrigin.Imported)
        return ProcessingTaskState(
            kind = kind,
            phase = ProcessingTaskPhase.Running,
            operationId = intent.getStringExtra(EXTRA_OPERATION_ID).orEmpty().ifBlank { UUID.randomUUID().toString() },
            audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH).orEmpty(),
            audioDisplayName = intent.getStringExtra(EXTRA_AUDIO_DISPLAY_NAME).orEmpty(),
            audioOrigin = origin,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            reportPayloadPath = intent.getStringExtra(EXTRA_REPORT_PAYLOAD_PATH).orEmpty(),
            detailLevels = readDetailLevels(intent),
            waitJoke = jokes.random(),
        )
    }

    private fun failureState(intent: Intent, message: String): ProcessingTaskState {
        val kind = when (intent.action) {
            ACTION_TRANSCRIBE_WITH_SPEAKERS -> ProcessingTaskKind.TranscriptionWithSpeakers
            ACTION_REPORTS_WITH_SPEAKERS -> ProcessingTaskKind.ReportsWithSpeakers
            else -> ProcessingTaskKind.ReportsFromAudio
        }
        return baseState(intent, kind).copy(phase = ProcessingTaskPhase.Failed, error = message)
    }

    private fun readDetailLevels(intent: Intent): ReportDetailLevels {
        val json = intent.getStringExtra(EXTRA_DETAIL_LEVELS).orEmpty()
        return runCatching { gson.fromJson(json, ReportDetailLevels::class.java) }.getOrDefault(ReportDetailLevels())
    }

    private fun api() = (application as DemeterSpeechApplication).container.backendApiClient

    private fun preferences() = (application as DemeterSpeechApplication).container.preferences

    companion object {
        private const val CHANNEL_ID = "demeter_processing"
        private const val NOTIFICATION_ID = 77
        private const val ACTION_TRANSCRIBE_WITH_SPEAKERS = "com.demeter.speech.PROCESS_TRANSCRIBE_WITH_SPEAKERS"
        private const val ACTION_REPORTS_WITH_SPEAKERS = "com.demeter.speech.PROCESS_REPORTS_WITH_SPEAKERS"
        private const val ACTION_REPORTS_FROM_AUDIO = "com.demeter.speech.PROCESS_REPORTS_FROM_AUDIO"
        private const val EXTRA_AUDIO_PATH = "audio_path"
        private const val EXTRA_AUDIO_DISPLAY_NAME = "audio_display_name"
        private const val EXTRA_AUDIO_ORIGIN = "audio_origin"
        private const val EXTRA_OPERATION_ID = "operation_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_REPORT_PAYLOAD_PATH = "report_payload_path"
        private const val EXTRA_DETAIL_LEVELS = "detail_levels"

        fun transcribeWithSpeakersIntent(context: Context, audio: AudioAsset, operationId: String = UUID.randomUUID().toString()): Intent {
            return baseIntent(context, ACTION_TRANSCRIBE_WITH_SPEAKERS, audio, operationId)
        }

        fun reportsFromAudioIntent(
            context: Context,
            audio: AudioAsset,
            title: String,
            detailLevels: ReportDetailLevels,
            operationId: String = UUID.randomUUID().toString(),
        ): Intent {
            return baseIntent(context, ACTION_REPORTS_FROM_AUDIO, audio, operationId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_DETAIL_LEVELS, Gson().toJson(detailLevels))
        }

        fun reportsWithSpeakersIntent(
            context: Context,
            audio: AudioAsset,
            payloadFile: File,
            operationId: String = UUID.randomUUID().toString(),
        ): Intent {
            return baseIntent(context, ACTION_REPORTS_WITH_SPEAKERS, audio, operationId)
                .putExtra(EXTRA_REPORT_PAYLOAD_PATH, payloadFile.absolutePath)
        }

        fun resumeIntent(context: Context, task: ProcessingTaskState): Intent {
            val audio = AudioAsset(File(task.audioPath), task.audioDisplayName.ifBlank { File(task.audioPath).name }, task.audioOrigin)
            return when (task.kind) {
                ProcessingTaskKind.TranscriptionWithSpeakers -> transcribeWithSpeakersIntent(context, audio, task.operationId)
                ProcessingTaskKind.ReportsWithSpeakers -> reportsWithSpeakersIntent(context, audio, File(task.reportPayloadPath), task.operationId)
                ProcessingTaskKind.ReportsFromAudio -> reportsFromAudioIntent(context, audio, task.title, task.detailLevels, task.operationId)
            }
        }

        private fun baseIntent(context: Context, action: String, audio: AudioAsset, operationId: String): Intent {
            return Intent(context, ProcessingForegroundService::class.java)
                .setAction(action)
                .putExtra(EXTRA_OPERATION_ID, operationId)
                .putExtra(EXTRA_AUDIO_PATH, audio.file.absolutePath)
                .putExtra(EXTRA_AUDIO_DISPLAY_NAME, audio.displayName)
                .putExtra(EXTRA_AUDIO_ORIGIN, audio.origin.name)
        }

        private val jokes = listOf(
            "Je range les virgules pendant que le backend travaille.",
            "Les micros ont parlé, je trie les phrases.",
            "Pause café virtuelle pour les tokens.",
            "Je demande aux paragraphes de rester bien alignés.",
            "Les comptes rendus prennent leur plus belle forme.",
        )
    }
}

private fun OperationStatus.notificationLabel(): String {
    if (stage == "network_retry") return message.ifBlank { "Connexion perdue, nouvelle tentative..." }
    queueLabel()?.let { return it }
    uploadProgressLabel()?.let { return "Upload audio · $it" }
    transcriptionProgressLabel()?.let { return "Transcription · $it" }
    val normalizedStage = stage.lowercase()
    return when {
        normalizedStage.contains("generation") -> "Génération des comptes rendus"
        normalizedStage.contains("email") -> "Envoi email"
        else -> "Traitement en cours"
    }
}

private fun OperationStatus.queueLabel(): String? {
    return if (isQueueStage()) "En file d'attente" else null
}

private fun OperationStatus.uploadProgressLabel(): String? {
    if (!isUploadStage()) return null
    return progressLabel("Morceau")
}

private fun OperationStatus.transcriptionProgressLabel(): String? {
    if (!isTranscriptionStage()) return null
    return progressLabel("Partie")
}

private fun OperationStatus.progressLabel(prefix: String): String? {
    if (chunkCount <= 0) return null
    val currentChunk = chunkIndex.coerceIn(1, chunkCount)
    return "$prefix $currentChunk/$chunkCount"
}

private fun OperationStatus.isUploadStage(): Boolean {
    val stageOrStatus = "${stage.lowercase()} ${status.lowercase()}"
    return stageOrStatus.contains("upload")
}

private fun OperationStatus.isQueueStage(): Boolean {
    val normalizedStage = stage.lowercase()
    return normalizedStage == "queue" || normalizedStage == "queued"
}

private fun OperationStatus.isTranscriptionStage(): Boolean {
    val normalizedStage = stage.lowercase()
    return !isUploadStage() &&
        !isQueueStage() &&
        (normalizedStage.contains("transcription") ||
            normalizedStage == "running" ||
            normalizedStage == "chunk_completed")
}
