package com.demeter.speech

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.demeter.speech.core.AppScreen
import com.demeter.speech.core.AudioAsset
import com.demeter.speech.core.AudioOrigin
import com.demeter.speech.core.AudioStaging
import com.demeter.speech.core.CorrectedReportPayload
import com.demeter.speech.core.DetailLevel
import com.demeter.speech.core.MeetingRecorderService
import com.demeter.speech.core.MobileUiState
import com.demeter.speech.core.OperationStatus
import com.demeter.speech.core.ProcessingForegroundService
import com.demeter.speech.core.ProcessingTaskEvents
import com.demeter.speech.core.ProcessingTaskKind
import com.demeter.speech.core.ProcessingTaskPhase
import com.demeter.speech.core.ProcessingTaskState
import com.demeter.speech.core.RecordingEvent
import com.demeter.speech.core.RecordingEvents
import com.demeter.speech.core.ReportDetailLevels
import com.demeter.speech.core.ReportFormat
import com.demeter.speech.core.ReportPayloadStore
import com.demeter.speech.core.SpeakerAssignment
import com.demeter.speech.core.TranscriptChunk
import com.demeter.speech.core.TranscriptSegment
import com.demeter.speech.core.resolveSpeakerLabel
import com.demeter.speech.core.speakerAssignmentKey
import java.io.FileInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

interface MobileActions {
    fun bootstrap()
    fun updateEmail(value: String)
    fun updatePassword(value: String)
    fun login()
    fun logout()
    fun goHome()
    fun startRecording()
    fun pauseRecording()
    fun resumeRecording()
    fun finishRecording()
    fun importAudio(uri: Uri)
    fun chooseSpeakerAssignment(assign: Boolean)
    fun updateSegmentText(id: String, value: String)
    fun updateSegmentSpeaker(id: String, value: String)
    fun applySpeakerAssignments(assignments: Map<String, SpeakerAssignment>)
    fun goToReportSettings()
    fun updateDetailLevel(format: ReportFormat, level: DetailLevel)
    fun generateReports()
    fun saveAudioTo(uri: Uri)
    fun discardAudioAndHome()
    fun clearError()
}

class DemeterSpeechViewModel(application: Application) : AndroidViewModel(application), MobileActions {
    private val app = application as DemeterSpeechApplication
    private val api = app.container.backendApiClient
    private val staging = AudioStaging(application)
    private val reportPayloadStore = ReportPayloadStore(application)
    private val context = application.applicationContext
    private val _state = MutableStateFlow(MobileUiState())
    val state: StateFlow<MobileUiState> = _state.asStateFlow()
    private var bootstrapped = false
    private var elapsedJob: Job? = null

    init {
        viewModelScope.launch {
            ProcessingTaskEvents.state.collect { task ->
                if (task != null) applyProcessingTaskState(task)
            }
        }
    }

    override fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true
        viewModelScope.launch {
            runCatching {
                val user = api.me()
                if (user != null) {
                    val (_, levels) = api.loadReportDetailLevels()
                    _state.value = _state.value.copy(
                        checkingSession = false,
                        user = user,
                        screen = AppScreen.Home,
                        reportDetails = levels,
                    )
                    app.container.preferences.loadProcessingState()?.let { task ->
                        if (task.phase == ProcessingTaskPhase.Running) applyProcessingTaskState(task)
                    }
                } else {
                    _state.value = _state.value.copy(checkingSession = false, screen = AppScreen.Auth)
                }
            }.onFailure {
                _state.value = _state.value.copy(checkingSession = false, screen = AppScreen.Auth)
            }
        }
    }

    override fun updateEmail(value: String) {
        _state.value = _state.value.copy(email = value, error = null)
    }

    override fun updatePassword(value: String) {
        _state.value = _state.value.copy(password = value, error = null)
    }

    override fun login() {
        val email = state.value.email
        val password = state.value.password
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching {
                val user = api.login(email, password)
                val (_, levels) = api.loadReportDetailLevels()
                _state.value = _state.value.copy(
                    busy = false,
                    user = user,
                    password = "",
                    screen = AppScreen.Home,
                    reportDetails = levels,
                )
            }.onFailure { showError(it.message ?: "Connexion impossible") }
        }
    }

    override fun logout() {
        viewModelScope.launch {
            stopRecordingServiceIfNeeded()
            api.logout()
            _state.value = MobileUiState(checkingSession = false, screen = AppScreen.Auth)
        }
    }

    override fun goHome() {
        val audioToDelete = state.value.audio
        val wasRecording = stopRecordingServiceIfNeeded()
        if (wasRecording) {
            viewModelScope.launch {
                delay(900)
                staging.deleteCached(audioToDelete)
            }
        } else {
            cleanupCurrentAudioIfNeeded()
        }
        _state.value = _state.value.copy(
            screen = AppScreen.Home,
            busy = false,
            error = null,
            audio = null,
            recording = false,
            paused = false,
            elapsedMs = 0L,
            wantsSpeakerAssignment = null,
            waitMessage = "",
            waitJoke = "",
            transcriptChunks = emptyList(),
            transcriptSegments = emptyList(),
            speakerAssignments = emptyMap(),
            operation = null,
            successCanSaveAudio = false,
            successFiles = emptyList(),
        )
    }

    override fun startRecording() {
        val file = staging.newRecordingFile()
        _state.value = _state.value.copy(
            screen = AppScreen.Recording,
            audio = AudioAsset(file, file.name, AudioOrigin.Recorded),
            recording = true,
            paused = false,
            elapsedMs = 0L,
            error = null,
        )
        ContextCompat.startForegroundService(context, MeetingRecorderService.startIntent(context, file.absolutePath))
        startElapsedTicker()
    }

    override fun pauseRecording() {
        context.startService(MeetingRecorderService.pauseIntent(context))
        _state.value = _state.value.copy(paused = true)
    }

    override fun resumeRecording() {
        context.startService(MeetingRecorderService.resumeIntent(context))
        _state.value = _state.value.copy(paused = false)
    }

    override fun finishRecording() {
        viewModelScope.launch {
            elapsedJob?.cancel()
            context.startService(MeetingRecorderService.stopIntent(context))
            val stopped = withTimeoutOrNull(2500) {
                RecordingEvents.events.filterIsInstance<RecordingEvent.Stopped>().first()
            }
            val file = stopped?.path?.let { java.io.File(it) } ?: state.value.audio?.file
            if (file == null || !file.exists()) {
                showError("Enregistrement introuvable")
                return@launch
            }
            _state.value = _state.value.copy(
                audio = AudioAsset(file, file.name, AudioOrigin.Recorded),
                recording = false,
                paused = false,
                screen = AppScreen.AssignChoice,
            )
        }
    }

    override fun importAudio(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching { staging.stageImportedAudio(uri) }
                .onSuccess { asset ->
                    _state.value = _state.value.copy(
                        busy = false,
                        audio = asset,
                        screen = AppScreen.AssignChoice,
                        wantsSpeakerAssignment = null,
                    )
                }
                .onFailure { showError(it.message ?: "Import audio impossible") }
        }
    }

    override fun chooseSpeakerAssignment(assign: Boolean) {
        _state.value = _state.value.copy(wantsSpeakerAssignment = assign, error = null)
        if (assign) startAssignedTranscription() else goToReportSettings()
    }

    override fun updateSegmentText(id: String, value: String) {
        updateSegments { segment -> if (segment.id == id) segment.copy(text = value) else segment }
    }

    override fun updateSegmentSpeaker(id: String, value: String) {
        updateSegments { segment -> if (segment.id == id) segment.copy(speaker = value) else segment }
    }

    override fun applySpeakerAssignments(assignments: Map<String, SpeakerAssignment>) {
        _state.value = _state.value.copy(speakerAssignments = assignments)
    }

    override fun goToReportSettings() {
        _state.value = _state.value.copy(screen = AppScreen.ReportSettings, error = null)
    }

    override fun updateDetailLevel(format: ReportFormat, level: DetailLevel) {
        val levels = state.value.reportDetails.update(format, level)
        if (levels == state.value.reportDetails) return
        _state.value = _state.value.copy(reportDetails = levels)
        viewModelScope.launch {
            runCatching { api.saveReportDetailLevels(levels) }
                .onFailure { showError("Réglages non sauvegardés") }
        }
    }

    override fun generateReports() {
        val current = state.value
        val audio = current.audio ?: return
        val title = generatedTitle()
        var payloadFile: java.io.File? = null
        runCatching {
            val intent = if (current.wantsSpeakerAssignment == true) {
                val assignedSegments = current.transcriptSegments.map { segment ->
                    segment.copy(speaker = resolveSpeakerLabel(segment, current.speakerAssignments))
                }
                val edited = assignedSegments.joinToString("\n") { segment ->
                    val speaker = segment.speaker.ifBlank { "Interlocuteur" }
                    "$speaker: ${segment.text}"
                }
                val raw = current.transcriptChunks.joinToString("\n") { it.text }.ifBlank { edited }
                payloadFile = reportPayloadStore.write(
                    CorrectedReportPayload(
                        title = title,
                        rawTranscript = raw,
                        editedTranscript = edited,
                        segments = assignedSegments,
                        detailLevels = current.reportDetails,
                    ),
                )
                ProcessingForegroundService.reportsWithSpeakersIntent(
                    context = context,
                    audio = audio,
                    payloadFile = requireNotNull(payloadFile),
                )
            } else {
                ProcessingForegroundService.reportsFromAudioIntent(
                    context = context,
                    audio = audio,
                    title = title,
                    detailLevels = current.reportDetails,
                )
            }
            _state.value = current.copy(
                screen = AppScreen.Processing,
                busy = true,
                error = null,
                waitJoke = jokes.random(),
            )
            ContextCompat.startForegroundService(context, intent)
        }.onFailure { error ->
            reportPayloadStore.delete(payloadFile)
            _state.value = current.copy(error = error.message ?: "Génération des comptes rendus impossible", busy = false)
        }
    }

    override fun saveAudioTo(uri: Uri) {
        val audio = state.value.audio ?: return
        viewModelScope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri).use { output ->
                    requireNotNull(output) { "Destination indisponible" }
                    FileInputStream(audio.file).use { input -> input.copyTo(output) }
                }
            }.onSuccess {
                staging.deleteCached(audio)
                goHome()
            }.onFailure { showError(it.message ?: "Sauvegarde impossible") }
        }
    }

    override fun discardAudioAndHome() {
        goHome()
    }

    override fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun startAssignedTranscription() {
        val audio = state.value.audio ?: return
        _state.value = _state.value.copy(
            screen = AppScreen.TranscriptionWait,
            busy = true,
            waitMessage = "Transcription en attente",
            waitJoke = jokes.random(),
        )
        ContextCompat.startForegroundService(context, ProcessingForegroundService.transcribeWithSpeakersIntent(context, audio))
    }

    private fun startElapsedTicker() {
        elapsedJob?.cancel()
        elapsedJob = viewModelScope.launch {
            while (true) {
                delay(250)
                val current = state.value
                if (current.recording && !current.paused) {
                    _state.value = current.copy(elapsedMs = current.elapsedMs + 250)
                }
            }
        }
    }

    private fun updateSegments(transform: (TranscriptSegment) -> TranscriptSegment) {
        val updated = state.value.transcriptSegments.map(transform)
        val chunks = state.value.transcriptChunks.map { chunk ->
            chunk.copy(segments = updated.filter { it.chunkIndex == chunk.index })
        }
        _state.value = _state.value.copy(transcriptSegments = updated, transcriptChunks = chunks)
    }

    private fun initialSpeakerAssignments(segments: List<TranscriptSegment>): Map<String, SpeakerAssignment> {
        return segments
            .mapNotNull { segment ->
                val speakerId = segment.speaker.trim()
                if (speakerId.isBlank()) null else speakerAssignmentKey(segment.chunkIndex, speakerId) to SpeakerAssignment()
            }
            .distinctBy { it.first }
            .toMap()
    }

    private fun applyProcessingTaskState(task: ProcessingTaskState) {
        val audio = task.audioAsset() ?: state.value.audio
        when (task.phase) {
            ProcessingTaskPhase.Running -> {
                val screen = if (task.kind == ProcessingTaskKind.TranscriptionWithSpeakers) {
                    AppScreen.TranscriptionWait
                } else {
                    AppScreen.Processing
                }
                _state.value = _state.value.copy(
                    screen = screen,
                    busy = true,
                    error = null,
                    audio = audio,
                    wantsSpeakerAssignment = task.kind != ProcessingTaskKind.ReportsFromAudio,
                    waitMessage = task.operation?.stage?.ifBlank { task.operation.status }.orEmpty(),
                    waitJoke = task.waitJoke,
                    operation = task.operation,
                )
            }
            ProcessingTaskPhase.Completed -> {
                if (task.kind == ProcessingTaskKind.TranscriptionWithSpeakers) {
                    val segments = task.segments.ifEmpty {
                        task.chunks.flatMap { it.segments }
                    }
                    _state.value = _state.value.copy(
                        screen = AppScreen.SpeakerReview,
                        busy = false,
                        audio = audio,
                        transcriptChunks = task.chunks,
                        transcriptSegments = segments,
                        speakerAssignments = initialSpeakerAssignments(segments),
                        operation = task.operation,
                    )
                } else {
                    _state.value = _state.value.copy(
                        screen = AppScreen.Success,
                        busy = false,
                        audio = audio,
                        operation = task.operation,
                        successFiles = task.files,
                        successCanSaveAudio = task.successCanSaveAudio,
                    )
                }
            }
            ProcessingTaskPhase.Failed -> {
                _state.value = _state.value.copy(
                    busy = false,
                    error = task.error ?: "Traitement impossible",
                    operation = task.operation,
                )
            }
        }
    }

    private fun ProcessingTaskState.audioAsset(): AudioAsset? {
        if (audioPath.isBlank()) return null
        val file = java.io.File(audioPath)
        val name = audioDisplayName.ifBlank { file.name }
        return AudioAsset(file, name, audioOrigin)
    }

    private fun cleanupCurrentAudioIfNeeded() {
        staging.deleteCached(state.value.audio)
    }

    private fun stopRecordingServiceIfNeeded(): Boolean {
        if (state.value.recording) {
            elapsedJob?.cancel()
            context.startService(MeetingRecorderService.stopIntent(context))
            return true
        }
        return false
    }

    private fun showError(message: String) {
        _state.value = _state.value.copy(busy = false, error = message)
    }

    private fun generatedTitle(): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        return "Compte rendu mobile ${LocalDateTime.now().format(formatter)}"
    }

    private companion object {
        val jokes = listOf(
            "Je range les virgules pendant que le backend travaille.",
            "Les micros ont parle, je trie les phrases.",
            "Pause cafe virtuelle pour les tokens.",
            "Je demande aux paragraphes de rester bien alignes.",
            "Les comptes rendus prennent leur plus belle forme.",
        )
    }
}
