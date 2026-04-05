package com.demeter.speech

import android.app.Application
import android.net.Uri
import android.util.Patterns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.demeter.speech.core.AppAuthState
import com.demeter.speech.core.AppLog
import com.demeter.speech.core.AppPreferences
import com.demeter.speech.core.AudioImportConverter
import com.demeter.speech.core.BackendApiClient
import com.demeter.speech.core.BackendSession
import com.demeter.speech.core.BackendSessionExpiredException
import com.demeter.speech.core.DemeterSpeechUiState
import com.demeter.speech.core.LoginUiState
import com.demeter.speech.core.MeetingDraftRequest
import com.demeter.speech.core.MeetingFinalizeRequest
import com.demeter.speech.core.MeetingParticipant
import com.demeter.speech.core.MeetingPhase
import com.demeter.speech.core.MeetingReviewState
import com.demeter.speech.core.MeetingRecorderService
import com.demeter.speech.core.MeetingWizardState
import com.demeter.speech.core.MeetingRecordingState
import com.demeter.speech.core.MeetingFinalizeOperationInProgressException
import com.demeter.speech.core.MeetingFinalizeResponseDto
import com.demeter.speech.core.PendingMeetingFinalizeOperationSnapshot
import com.demeter.speech.core.SupportErrorReporter
import com.demeter.speech.core.SupportReportBackendError
import com.demeter.speech.core.SupportReportBuilder
import com.demeter.speech.core.SupportReportFile
import com.demeter.speech.core.SupportReportRetry
import com.demeter.speech.core.MeetingTranscriptChunkUi
import com.demeter.speech.core.ReportFormat
import com.demeter.speech.core.RootTab
import com.demeter.speech.core.SpeakerAssignmentUi
import com.demeter.speech.core.buildMeetingFinalizeRequestBody
import com.demeter.speech.core.buildSpeakerAssignmentsFromTranscript
import com.demeter.speech.core.buildDefaultMeetingWizardState
import com.demeter.speech.core.renderTranscriptFromChunks
import com.demeter.speech.core.TranscriptionSourceMode
import com.demeter.speech.core.resolvedMeetingTitle
import com.demeter.speech.core.splitChunkedTranscriptDisplay
import com.demeter.speech.core.RecordingRepository
import com.demeter.speech.core.toBackendSession
import com.demeter.speech.core.toUiDrafts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID
import org.json.JSONObject

class DemeterSpeechViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as DemeterSpeechApplication
    private val container = app.container
    private val backend: BackendApiClient = container.backendApiClient
    private val prefs: AppPreferences = container.preferences
    private val supportReporter: SupportErrorReporter = container.supportErrorReporter
    private val localEngine = container.localTranscriptionEngine
    private val demeterEngine = container.demeterTranscriptionEngine

    private val _state = MutableStateFlow(
        DemeterSpeechUiState(
            authState = AppAuthState.Checking,
            login = LoginUiState(email = prefs.lastEmail()),
            wizard = defaultWizardState(),
        )
    )
    val state = _state.asStateFlow()

    private var transcriptionJob: Job? = null
    @Volatile
    private var finalizeJob: Job? = null
    @Volatile
    private var sessionRefreshJob: Job? = null
    private var lastTranscribedPath: String? = null

    init {
        viewModelScope.launch {
            com.demeter.speech.core.RecordingRepository.state.collect { recordingState ->
                _state.update { current ->
                    current.copy(recording = recordingState)
                }
                val recordingPath = recordingState.recordingPath?.trim().orEmpty()
                if (!recordingState.isRecording && recordingPath.isNotEmpty() && lastTranscribedPath != recordingPath) {
                    lastTranscribedPath = recordingPath
                    transcriptionJob?.cancel()
                    transcriptionJob = viewModelScope.launch(Dispatchers.IO) {
                        transcribeRecording(File(recordingPath))
                    }
                }
            }
        }
    }

    fun onTabSelected(tab: RootTab) {
        _state.update { it.copy(activeTab = tab) }
    }

    fun onLoginEmailChanged(value: String) {
        _state.update { current -> current.copy(login = current.login.copy(email = value)) }
    }

    fun onLoginPasswordChanged(value: String) {
        _state.update { current -> current.copy(login = current.login.copy(password = value)) }
    }

    fun onPermissionsGranted(granted: Boolean) {
        _state.update { it.copy(permissionsGranted = granted) }
    }

    fun clearInfoMessage() {
        _state.update { it.copy(infoMessage = null) }
    }

    fun showInfoMessage(message: String) {
        _state.update { it.copy(infoMessage = message) }
    }

    fun refreshSession() {
        if (sessionRefreshJob?.isActive == true) {
            return
        }
        val authStateAtStart = _state.value.authState
        if (authStateAtStart == AppAuthState.SignedOut) {
            return
        }
        val job = viewModelScope.launch(Dispatchers.IO) {
            if (!backend.hasStoredRefreshSession()) {
                applySessionExpiredState(null)
                return@launch
            }

            runCatching { backend.refreshSession() }
                .onSuccess { auth ->
                    val session = auth.toBackendSession()
                    _state.update { current ->
                        current.copy(
                            authState = AppAuthState.SignedIn,
                            session = session,
                            login = current.login.copy(
                                email = session.email,
                                password = "",
                                errorMessage = null,
                                isLoading = false,
                            ),
                            activeTab = if (authStateAtStart == AppAuthState.SignedIn) current.activeTab else RootTab.MEETING,
                            meetingPhase = if (authStateAtStart == AppAuthState.SignedIn) current.meetingPhase else MeetingPhase.Welcome,
                            wizard = current.wizard.copy(
                                localWhisperModelId = prefs.localWhisperModelId(),
                                cloudMistralChunkDurationSec = prefs.demeterChunkDurationSec(),
                                cloudMistralOverlapSec = prefs.demeterOverlapSec(),
                                reportModelId = prefs.reportModelId(),
                                reportTemperature = prefs.reportTemperature(),
                                reportMaxTokens = prefs.reportMaxTokens(),
                            ),
                            review = if (authStateAtStart == AppAuthState.SignedIn) current.review else MeetingReviewState(),
                            recording = if (authStateAtStart == AppAuthState.SignedIn) current.recording else MeetingRecordingState(),
                            infoMessage = null,
                        )
                    }
                    resumePendingFinalizeOperationIfAny()
                    flushPendingSupportReports()
                }
                .onFailure { error ->
                    AppLog.w(TAG, "Session refresh failed", error)
                    val stateSnapshot = _state.value
                    viewModelScope.launch(Dispatchers.IO) {
                        reportSupportIncident(
                            route = "app://session-refresh",
                            backendError = SupportReportBackendError(
                                status = 401,
                                code = "session_refresh_failed",
                                message = error.message ?: "Session expirée",
                                path = "/api/v1/auth/refresh",
                                method = "POST",
                            ),
                            telemetry = JSONObject().apply {
                                put("authState", authStateAtStart.name)
                                put("errorClass", error.javaClass.name)
                            },
                            stateSnapshot = stateSnapshot,
                        )
                    }
                    applySessionExpiredState(error.message ?: "Session expirée")
                }
        }
        sessionRefreshJob = job
        job.invokeOnCompletion {
            if (sessionRefreshJob === job) {
                sessionRefreshJob = null
            }
        }
    }

    fun login() {
        val login = _state.value.login
        if (login.email.isBlank() || login.password.isBlank()) {
            _state.update { current ->
                current.copy(login = current.login.copy(errorMessage = "Email et mot de passe requis"))
            }
            return
        }
        _state.update { current ->
            current.copy(login = current.login.copy(isLoading = true, errorMessage = null))
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                backend.login(login.email, login.password)
            }.onSuccess { auth ->
                val session = auth.toBackendSession()
                _state.update { current ->
                    current.copy(
                        authState = AppAuthState.SignedIn,
                        session = session,
                        login = current.login.copy(
                            email = session.email,
                            password = "",
                            isLoading = false,
                            errorMessage = null,
                        ),
                        activeTab = RootTab.MEETING,
                        wizard = current.wizard.copy(
                            localWhisperModelId = prefs.localWhisperModelId(),
                            cloudMistralChunkDurationSec = prefs.demeterChunkDurationSec(),
                            cloudMistralOverlapSec = prefs.demeterOverlapSec(),
                            reportModelId = prefs.reportModelId(),
                            reportTemperature = prefs.reportTemperature(),
                            reportMaxTokens = prefs.reportMaxTokens(),
                        ),
                        meetingPhase = MeetingPhase.Welcome,
                    )
                }
                resumePendingFinalizeOperationIfAny()
                flushPendingSupportReports()
            }.onFailure { error ->
                _state.update { current ->
                    current.copy(
                        authState = AppAuthState.SignedOut,
                        login = current.login.copy(
                            password = "",
                            isLoading = false,
                            errorMessage = error.message ?: "Connexion impossible",
                        ),
                    )
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            cancelTranscriptionJob()
            cancelFinalizeJob()
            sessionRefreshJob?.cancel()
            sessionRefreshJob = null
            prefs.clearPendingFinalizeOperation()
            runCatching { backend.logout() }
            runCatching { MeetingRecorderService.stop(getApplication()) }
            deleteLatestRecordingArtifact()
            RecordingRepository.reset()
            lastTranscribedPath = null
            _state.update { current ->
                current.copy(
                    authState = AppAuthState.SignedOut,
                    session = null,
                    activeTab = RootTab.MEETING,
                    meetingPhase = MeetingPhase.Welcome,
                    review = MeetingReviewState(),
                    recording = MeetingRecordingState(),
                    login = current.login.copy(password = "", isLoading = false, errorMessage = null),
                )
            }
        }
    }

    fun openMeetingWizard() {
        if (prefs.pendingFinalizeOperation() != null) {
            resumePendingFinalizeOperationIfAny()
            return
        }
        _state.update { current ->
            current.copy(
                meetingPhase = MeetingPhase.Wizard,
                wizard = defaultWizardState(),
                review = MeetingReviewState(),
                infoMessage = null,
            )
        }
    }

    fun cancelWizard() {
        _state.update { current ->
            current.copy(meetingPhase = MeetingPhase.Welcome, infoMessage = null)
        }
    }

    fun updateMeetingTitle(value: String) {
        _state.update { current ->
            current.copy(
                wizard = current.wizard.copy(title = value),
                review = if (current.review.drafts.isEmpty()) current.review else current.review.copy(
                    drafts = emptyList(),
                    draftMessage = "Les rapports doivent être régénérés",
                ),
            )
        }
    }

    fun addParticipant() {
        _state.update { current ->
            current.copy(
                wizard = current.wizard.copy(
                    participants = normalizeParticipants(current.wizard.participants + MeetingParticipant(displayName = ""))
                ),
                review = if (current.review.drafts.isEmpty()) current.review else current.review.copy(
                    drafts = emptyList(),
                    draftMessage = "Les rapports doivent être régénérés",
                ),
            )
        }
    }

    fun removeParticipant(participantId: String) {
        _state.update { current ->
            val remaining = current.wizard.participants.filterNot { it.id == participantId }
            current.copy(
                wizard = current.wizard.copy(participants = normalizeParticipants(remaining)),
                review = if (current.review.drafts.isEmpty()) current.review else current.review.copy(
                    drafts = emptyList(),
                    draftMessage = "Les rapports doivent être régénérés",
                ),
            )
        }
    }

    fun updateParticipant(participantId: String, displayName: String) {
        _state.update { current ->
            val next = current.wizard.participants.map {
                if (it.id == participantId) it.copy(displayName = displayName) else it
            }
            current.copy(
                wizard = current.wizard.copy(participants = normalizeParticipants(next)),
                review = if (current.review.drafts.isEmpty()) current.review else current.review.copy(
                    drafts = emptyList(),
                    draftMessage = "Les rapports doivent être régénérés",
                ),
            )
        }
    }

    fun toggleReportFormat(format: ReportFormat, enabled: Boolean) {
        _state.update { current ->
            val next = current.wizard.selectedFormats.toMutableSet()
            if (enabled) next.add(format) else next.remove(format)
            val ordered = orderedReportFormats(next)
            val nextActiveReportFormat = when {
                current.review.activeReportFormat in next -> current.review.activeReportFormat
                ordered.isNotEmpty() -> ordered.first()
                else -> current.review.activeReportFormat
            }
            current.copy(
                wizard = current.wizard.copy(selectedFormats = next),
                review = if (current.review.drafts.isEmpty()) current.review else current.review.copy(
                    drafts = emptyList(),
                    draftMessage = "Les rapports doivent être régénérés",
                    activeReportFormat = nextActiveReportFormat,
                ),
            )
        }
    }

    fun updateSourceMode(mode: TranscriptionSourceMode) {
        _state.update { current ->
            current.copy(
                wizard = current.wizard.copy(sourceMode = mode),
                review = if (current.review.drafts.isEmpty()) current.review else current.review.copy(
                    drafts = emptyList(),
                    draftMessage = "Les rapports doivent être régénérés",
                ),
            )
        }
    }

    fun updateLocalWhisperModelId(value: String) {
        prefs.setLocalWhisperModelId(value)
        _state.update { current ->
            current.copy(
                wizard = current.wizard.copy(localWhisperModelId = prefs.localWhisperModelId()),
                review = if (current.review.drafts.isEmpty()) current.review else current.review.copy(
                    drafts = emptyList(),
                    draftMessage = "Les rapports doivent être régénérés",
                ),
            )
        }
    }

    fun updateDemeterChunkDurationSec(value: String) {
        val parsed = value.toIntOrNull()?.coerceAtLeast(1) ?: prefs.demeterChunkDurationSec()
        prefs.setDemeterChunkDurationSec(parsed)
        _state.update { current ->
            current.copy(
                wizard = current.wizard.copy(cloudMistralChunkDurationSec = prefs.demeterChunkDurationSec()),
                review = if (current.review.drafts.isEmpty()) current.review else current.review.copy(
                    drafts = emptyList(),
                    draftMessage = "Les rapports doivent être régénérés",
                ),
            )
        }
    }

    fun updateDemeterOverlapSec(value: String) {
        val parsed = value.toIntOrNull()?.coerceAtLeast(0) ?: prefs.demeterOverlapSec()
        prefs.setDemeterOverlapSec(parsed)
        _state.update { current ->
            current.copy(
                wizard = current.wizard.copy(cloudMistralOverlapSec = prefs.demeterOverlapSec()),
                review = if (current.review.drafts.isEmpty()) current.review else current.review.copy(
                    drafts = emptyList(),
                    draftMessage = "Les rapports doivent être régénérés",
                ),
            )
        }
    }

    fun updateReportModelId(value: String) {
        prefs.setReportModelId(value)
        _state.update { current ->
            current.copy(
                wizard = current.wizard.copy(reportModelId = prefs.reportModelId()),
                review = if (current.review.drafts.isEmpty()) current.review else current.review.copy(
                    drafts = emptyList(),
                    draftMessage = "Les rapports doivent être régénérés",
                ),
            )
        }
    }

    fun updateReportTemperature(value: String) {
        val parsed = value.replace(',', '.').toFloatOrNull() ?: 0f
        prefs.setReportTemperature(parsed)
        _state.update { current ->
            current.copy(
                wizard = current.wizard.copy(reportTemperature = prefs.reportTemperature()),
                review = if (current.review.drafts.isEmpty()) current.review else current.review.copy(
                    drafts = emptyList(),
                    draftMessage = "Les rapports doivent être régénérés",
                ),
            )
        }
    }

    fun updateReportMaxTokens(value: String) {
        val parsed = value.toIntOrNull()?.coerceAtLeast(1) ?: 32768
        prefs.setReportMaxTokens(parsed)
        _state.update { current ->
            current.copy(
                wizard = current.wizard.copy(reportMaxTokens = prefs.reportMaxTokens()),
                review = if (current.review.drafts.isEmpty()) current.review else current.review.copy(
                    drafts = emptyList(),
                    draftMessage = "Les rapports doivent être régénérés",
                ),
            )
        }
    }

    fun startRecording() {
        if (_state.value.meetingPhase == MeetingPhase.Recording) {
            return
        }
        cancelTranscriptionJob()
        deleteLatestRecordingArtifact()
        RecordingRepository.reset()
        lastTranscribedPath = null
        _state.update { current ->
            current.copy(
                meetingPhase = MeetingPhase.Recording,
                review = MeetingReviewState(),
                infoMessage = "Enregistrement démarré",
            )
        }
        MeetingRecorderService.start(getApplication())
    }

    fun pauseRecording() {
        MeetingRecorderService.pause(getApplication())
    }

    fun resumeRecording() {
        MeetingRecorderService.resume(getApplication())
    }

    fun stopRecording() {
        _state.update { current ->
            current.copy(
                meetingPhase = MeetingPhase.Review,
                review = current.review.copy(
                    isTranscribing = true,
                    errorMessage = null,
                    draftMessage = null,
                    submitMessage = null,
                    drafts = emptyList(),
                ),
                infoMessage = "Arrêt en cours",
            )
        }
        MeetingRecorderService.stop(getApplication())
    }

    fun updateTranscriptEdited(value: String) {
        _state.update { current ->
            current.copy(
                review = current.review.copy(
                    transcriptEdited = value,
                    drafts = emptyList(),
                    draftMessage = "Les rapports doivent être régénérés",
                    submitMessage = null,
                )
            )
        }
    }

    fun selectReportFormat(format: ReportFormat) {
        _state.update { current -> current.copy(review = current.review.copy(activeReportFormat = format)) }
    }

    fun addSpeakerCard() {
        updateCurrentSpeakerAssignments { assignments ->
            val nextId = nextSpeakerId(assignments)
            assignments + SpeakerAssignmentUi(speakerId = nextId)
        }
    }

    fun removeSpeakerCard(speakerId: String) {
        updateCurrentSpeakerAssignments { assignments ->
            assignments.filterNot { it.speakerId == speakerId }
        }
    }

    fun updateSpeakerCard(speakerId: String, firstName: String, lastName: String) {
        updateCurrentSpeakerAssignments { assignments ->
            assignments.map {
                if (it.speakerId == speakerId) {
                    it.copy(
                        firstName = firstName,
                        lastName = lastName,
                        confirmedLabel = null,
                        isValidated = false,
                    )
                } else {
                    it
                }
            }
        }
    }

    fun confirmSpeakerCard(speakerId: String) {
        updateCurrentSpeakerAssignments { assignments ->
            assignments.map { assignment ->
                if (assignment.speakerId != speakerId) {
                    assignment
                } else {
                    val confirmedLabel = buildSpeakerDisplayName(assignment.firstName, assignment.lastName)
                    if (confirmedLabel.isBlank()) {
                        assignment.copy(confirmedLabel = null, isValidated = false)
                    } else {
                        assignment.copy(
                            confirmedLabel = confirmedLabel,
                            isValidated = true,
                        )
                    }
                }
            }
        }
    }

    fun updateAdditionalRecipientEmails(value: String) {
        _state.update { current ->
            current.copy(
                review = current.review.copy(
                    additionalRecipientEmails = value,
                    submitMessage = null,
                )
            )
        }
    }

    fun generateDrafts() {
        val current = _state.value
        val transcript = current.review.transcriptEdited.ifBlank { current.review.transcriptRaw }
        val meetingTitle = resolvedMeetingTitle(current.wizard.title)
        if (transcript.isBlank()) {
            _state.update { it.copy(review = it.review.copy(draftMessage = "Transcription vide")) }
            return
        }
        if (current.wizard.selectedFormats.isEmpty()) {
            _state.update { it.copy(review = it.review.copy(draftMessage = "Sélectionnez au moins un format")) }
            return
        }

        _state.update { state ->
            state.copy(
                review = state.review.copy(
                    isGeneratingDrafts = true,
                    draftMessage = "Génération des rapports…",
                    errorMessage = null,
                ),
            )
        }

        if (meetingTitle != current.wizard.title) {
            _state.update { state ->
                state.copy(wizard = state.wizard.copy(title = meetingTitle))
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                backend.generateMeetingDrafts(
                    MeetingDraftRequest(
                        meetingTitle = meetingTitle,
                        participants = normalizeParticipantNames(current.wizard.participants),
                        transcriptText = transcript,
                        selectedFormats = orderedReportFormats(current.wizard.selectedFormats),
                        reportModelId = current.wizard.reportModelId,
                        reportTemperature = current.wizard.reportTemperature,
                        reportMaxTokens = current.wizard.reportMaxTokens,
                    )
                )
            }.onSuccess { response ->
                val drafts = response.toUiDrafts()
                _state.update { state ->
                    state.copy(
                        review = state.review.copy(
                            drafts = drafts,
                            isGeneratingDrafts = false,
                            draftMessage = "Rapports prêts à être validés",
                            activeReportFormat = drafts.firstOrNull()?.format ?: state.review.activeReportFormat,
                        ),
                        infoMessage = "Rapports générés",
                    )
                }
            }.onFailure { error ->
                if (error is BackendSessionExpiredException) {
                    applySessionExpiredState(error.message ?: "Session expirée")
                } else {
                    _state.update { state ->
                        state.copy(
                            review = state.review.copy(
                                isGeneratingDrafts = false,
                                draftMessage = null,
                                errorMessage = error.message ?: "Échec de génération",
                            ),
                            infoMessage = "Génération des rapports échouée",
                        )
                    }
                }
            }
        }
    }

    fun finalizeMeeting() {
        val current = _state.value
        if (current.review.isSubmitting || finalizeJob?.isActive == true) {
            return
        }

        val pendingSnapshot = prefs.pendingFinalizeOperation()
        if (pendingSnapshot != null) {
            resumePendingFinalizeOperationIfAny()
            return
        }

        val rawTranscript = current.review.transcriptRaw.ifBlank { current.review.transcriptEdited }
        val editedTranscript = current.review.transcriptEdited.ifBlank { rawTranscript }
        val meetingTitle = resolvedMeetingTitle(current.wizard.title)
        if (rawTranscript.isBlank()) {
            _state.update { it.copy(review = it.review.copy(errorMessage = "Transcription vide")) }
            return
        }
        val extraRecipientEmails = runCatching {
            parseRecipientEmails(current.review.additionalRecipientEmails)
        }.getOrElse { error ->
            _state.update { state ->
                state.copy(
                    review = state.review.copy(
                        isSubmitting = false,
                        submitMessage = null,
                        errorMessage = error.message ?: "Destinataire invalide",
                    )
                )
            }
            return
        }

        if (meetingTitle != current.wizard.title) {
            _state.update { state ->
                state.copy(wizard = state.wizard.copy(title = meetingTitle))
            }
        }

        val session = current.session
        if (session == null) {
            _state.update { state ->
                state.copy(
                    review = state.review.copy(
                        errorMessage = "Session expirée",
                    ),
                    infoMessage = "Connexion requise pour envoyer le compte rendu",
                )
            }
            return
        }

        val participants = normalizeParticipantNames(current.wizard.participants)
        val speakerAssignments = aggregateSpeakerAssignments(current.review)
        val reportFormats = allReportFormats()
        val operationId = UUID.randomUUID().toString()
        val requestDto = MeetingFinalizeRequest(
            operationId = operationId,
            meetingTitle = meetingTitle,
            participants = participants,
            transcriptionSourceMode = TranscriptionSourceMode.DEMETER_BACKEND,
            rawTranscriptText = rawTranscript,
            editedTranscriptText = editedTranscript,
            selectedFormats = reportFormats,
            recipientEmails = extraRecipientEmails,
            reportModelId = current.wizard.reportModelId,
            reportTemperature = current.wizard.reportTemperature,
            reportMaxTokens = current.wizard.reportMaxTokens,
            reports = current.review.drafts,
            speakerAssignments = speakerAssignments,
        )
        val requestJson = buildMeetingFinalizeRequestBody(requestDto)
        val snapshot = PendingMeetingFinalizeOperationSnapshot(
            operationId = operationId,
            organizationId = session.organizationId,
            userId = session.userId,
            requestJson = requestJson,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        prefs.setPendingFinalizeOperation(snapshot)
        launchFinalizeOperation(
            snapshot = snapshot,
            submitMessage = "Envoi du mail…",
        )
    }

    private fun resumePendingFinalizeOperationIfAny() {
        if (finalizeJob?.isActive == true) {
            return
        }
        val snapshot = prefs.pendingFinalizeOperation() ?: return
        val session = _state.value.session
        if (session == null || session.organizationId != snapshot.organizationId || session.userId != snapshot.userId) {
            prefs.clearPendingFinalizeOperation()
            return
        }
        if (snapshot.isExpired()) {
            prefs.clearPendingFinalizeOperation()
            _state.update { current ->
                current.copy(
                    meetingPhase = MeetingPhase.Welcome,
                    wizard = defaultWizardState(),
                    review = MeetingReviewState(),
                    infoMessage = "L'envoi a expiré, recommencez",
                )
            }
            return
        }
        launchFinalizeOperation(
            snapshot = snapshot,
            submitMessage = "Vérification du compte rendu…",
        )
    }

    private fun launchFinalizeOperation(snapshot: PendingMeetingFinalizeOperationSnapshot, submitMessage: String) {
        if (finalizeJob?.isActive == true) {
            return
        }
        _state.update { current ->
            current.copy(
                meetingPhase = MeetingPhase.Review,
                review = current.review.copy(
                    isSubmitting = true,
                    submitMessage = submitMessage,
                    errorMessage = null,
                ),
            )
        }
        val job = viewModelScope.launch(Dispatchers.IO) {
            runFinalizeOperation(snapshot)
        }
        finalizeJob = job
        job.invokeOnCompletion {
            if (finalizeJob === job) {
                finalizeJob = null
            }
        }
    }

    private suspend fun runFinalizeOperation(snapshot: PendingMeetingFinalizeOperationSnapshot) {
        var shouldPost = true
        var retriedAfterMissing = false
        while (true) {
            if (shouldPost) {
                try {
                    val response = backend.finalizeMeeting(snapshot.operationId, snapshot.requestJson)
                    completeFinalizeOperation(response)
                    return
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (sessionExpired: BackendSessionExpiredException) {
                    AppLog.i(TAG, "Finalize operation session expired: ${snapshot.operationId}", sessionExpired)
                    reportSupportIncident(
                        route = "app://meeting/finalize",
                        backendError = SupportReportBackendError(
                            status = 401,
                            code = "session_expired",
                            message = sessionExpired.message ?: "Session expirée",
                            path = "/api/v1/meetings/finalize",
                            method = "POST",
                        ),
                        telemetry = JSONObject().apply {
                            put("operationId", snapshot.operationId)
                            put("phase", "post_finalize")
                        },
                    )
                    applySessionExpiredState(sessionExpired.message ?: "Session expirée")
                    return
                } catch (inProgress: MeetingFinalizeOperationInProgressException) {
                    AppLog.i(TAG, "Finalize operation already in progress: ${snapshot.operationId}")
                    updateFinalizeProgressMessage("Vérification du compte rendu…")
                } catch (failure: IllegalStateException) {
                    failFinalizeOperation(finalizeFailureMessage(failure.message))
                    return
                } catch (error: IOException) {
                    AppLog.w(TAG, "Finalize request lost, checking status", error)
                    reportSupportIncident(
                        route = "app://meeting/finalize",
                        backendError = SupportReportBackendError(
                            status = 0,
                            code = "finalize_request_lost",
                            message = error.message ?: "Finalize request lost",
                            path = "/api/v1/meetings/finalize",
                            method = "POST",
                        ),
                        telemetry = JSONObject().apply {
                            put("operationId", snapshot.operationId)
                            put("phase", "request")
                            put("errorClass", error.javaClass.name)
                        },
                    )
                    updateFinalizeProgressMessage("Connexion perdue. Vérification du compte rendu…")
                } catch (error: Throwable) {
                    AppLog.w(TAG, "Finalize request failed, checking status", error)
                    reportSupportIncident(
                        route = "app://meeting/finalize",
                        backendError = SupportReportBackendError(
                            status = 0,
                            code = "finalize_request_failed",
                            message = error.message ?: "Finalize request failed",
                            path = "/api/v1/meetings/finalize",
                            method = "POST",
                        ),
                        telemetry = JSONObject().apply {
                            put("operationId", snapshot.operationId)
                            put("phase", "request")
                            put("errorClass", error.javaClass.name)
                        },
                    )
                    updateFinalizeProgressMessage("Connexion perdue. Vérification du compte rendu…")
                }
                shouldPost = false
            }

            val status = try {
                backend.getFinalizeMeetingOperationStatus(snapshot.operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (sessionExpired: BackendSessionExpiredException) {
                AppLog.i(TAG, "Finalize operation status session expired: ${snapshot.operationId}", sessionExpired)
                reportSupportIncident(
                    route = "app://meeting/finalize/status",
                    backendError = SupportReportBackendError(
                        status = 401,
                        code = "session_expired",
                        message = sessionExpired.message ?: "Session expirée",
                        path = "/api/v1/meetings/finalize/${snapshot.operationId}/status",
                        method = "GET",
                    ),
                    telemetry = JSONObject().apply {
                        put("operationId", snapshot.operationId)
                        put("phase", "status")
                    },
                )
                applySessionExpiredState(sessionExpired.message ?: "Session expirée")
                return
            } catch (error: Throwable) {
                AppLog.w(TAG, "Finalize status check failed", error)
                reportSupportIncident(
                    route = "app://meeting/finalize/status",
                    backendError = SupportReportBackendError(
                        status = 0,
                        code = "finalize_status_check_failed",
                        message = error.message ?: "Finalize status check failed",
                        path = "/api/v1/meetings/finalize/${snapshot.operationId}/status",
                        method = "GET",
                    ),
                    telemetry = JSONObject().apply {
                        put("operationId", snapshot.operationId)
                        put("phase", "status")
                        put("errorClass", error.javaClass.name)
                    },
                )
                delay(FINALIZE_OPERATION_POLL_DELAY_MS)
                continue
            }

            when {
                status == null -> {
                    if (snapshot.isExpired()) {
                        failFinalizeOperation("L'envoi a expiré, recommencez")
                        return
                    }
                    if (!retriedAfterMissing) {
                        retriedAfterMissing = true
                        shouldPost = true
                        updateFinalizeProgressMessage("Nouvelle tentative d'envoi…")
                        continue
                    }
                    updateFinalizeProgressMessage("Connexion perdue. Vérification du compte rendu…")
                    delay(FINALIZE_OPERATION_POLL_DELAY_MS)
                }
                status.status == FINALIZE_OPERATION_STATUS_PENDING -> {
                    updateFinalizeProgressMessage("Vérification du compte rendu…")
                    delay(FINALIZE_OPERATION_POLL_DELAY_MS)
                }
                status.status == FINALIZE_OPERATION_STATUS_COMPLETED -> {
                    val response = status.response
                    if (response == null) {
                        failFinalizeOperation("Réponse de finalisation indisponible")
                    } else {
                        completeFinalizeOperation(response)
                    }
                    return
                }
                status.status == FINALIZE_OPERATION_STATUS_FAILED -> {
                    failFinalizeOperation(status.error ?: "Échec de l'envoi")
                    return
                }
                else -> {
                    updateFinalizeProgressMessage("Vérification du compte rendu…")
                    delay(FINALIZE_OPERATION_POLL_DELAY_MS)
                }
            }
        }
    }

    private fun completeFinalizeOperation(response: MeetingFinalizeResponseDto) {
        prefs.clearPendingFinalizeOperation()
        RecordingRepository.latestRecordingFile?.delete()
        RecordingRepository.reset()
        lastTranscribedPath = null
        val sentTo = response.sentToEmails.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: response.sentTo
        _state.update { current ->
            current.copy(
                review = MeetingReviewState(
                    isSubmitting = false,
                    submitMessage = "Envoyé vers $sentTo",
                    activeReportFormat = current.review.activeReportFormat,
                ),
                meetingPhase = MeetingPhase.Welcome,
                wizard = defaultWizardState(),
                infoMessage = "Compte rendu envoyé",
            )
        }
    }

    private fun failFinalizeOperation(message: String) {
        prefs.clearPendingFinalizeOperation()
        _state.update { current ->
            current.copy(
                review = current.review.copy(
                    isSubmitting = false,
                    submitMessage = null,
                    errorMessage = message.ifBlank { "Échec de l'envoi" },
                ),
                infoMessage = "Envoi du mail échoué",
            )
        }
    }

    private fun applySessionExpiredState(message: String? = null) {
        runCatching { MeetingRecorderService.stop(getApplication()) }
        RecordingRepository.reset()
        lastTranscribedPath = null
        _state.update { current ->
            current.copy(
                authState = AppAuthState.SignedOut,
                session = null,
                login = current.login.copy(
                    password = "",
                    isLoading = false,
                    errorMessage = null,
                ),
                activeTab = RootTab.MEETING,
                meetingPhase = MeetingPhase.Welcome,
                wizard = defaultWizardState(),
                recording = MeetingRecordingState(),
                review = MeetingReviewState(),
                infoMessage = message,
            )
        }
    }

    private fun finalizeFailureMessage(rawMessage: String?): String {
        val message = rawMessage?.trim().orEmpty()
        val normalized = message.lowercase()
        return when {
            normalized.contains("operation not found") || normalized.contains("idempotency key") && normalized.contains("missing") -> {
                "L'envoi a expiré, recommencez"
            }
            message.isBlank() -> "Échec de l'envoi"
            else -> message
        }
    }

    private fun updateFinalizeProgressMessage(message: String) {
        _state.update { current ->
            current.copy(
                review = current.review.copy(
                    isSubmitting = true,
                    submitMessage = message,
                    errorMessage = null,
                ),
            )
        }
    }

    private fun flushPendingSupportReports() {
        viewModelScope.launch(Dispatchers.IO) {
            supportReporter.flushPending()
        }
    }

    private suspend fun reportSupportIncident(
        route: String,
        backendError: SupportReportBackendError,
        telemetry: JSONObject? = null,
        originalFile: SupportReportFile = SupportReportBuilder.defaultOriginalFile("android"),
        processedFile: SupportReportFile = SupportReportBuilder.defaultOriginalFile("android"),
        rawFile: SupportReportFile? = null,
        retry: SupportReportRetry = SupportReportRetry(),
        stateSnapshot: DemeterSpeechUiState? = null,
    ) {
        val currentState = stateSnapshot ?: _state.value
        val diagnosticBundle = SupportReportBuilder.buildDiagnosticBundle(
            client = "android",
            session = SupportReportBuilder.buildSessionSnapshot(currentState, route),
            settings = SupportReportBuilder.buildSettingsSnapshot(prefs),
            logs = AppLog.snapshot(),
            telemetry = telemetry,
        )
        val payload = SupportReportBuilder.buildSupportReportPayload(
            client = "android",
            provider = "android",
            backendError = backendError,
            originalFile = originalFile,
            processedFile = processedFile,
            retry = retry,
            diagnosticBundle = diagnosticBundle,
            rawFile = rawFile,
            traceId = backendError.traceId,
        )
        supportReporter.submit(payload)
    }

    private fun cancelFinalizeJob() {
        finalizeJob?.cancel()
        finalizeJob = null
    }

    fun resetMeeting() {
        cancelTranscriptionJob()
        cancelFinalizeJob()
        prefs.clearPendingFinalizeOperation()
        runCatching { MeetingRecorderService.stop(getApplication()) }
        deleteLatestRecordingArtifact()
        RecordingRepository.reset()
        lastTranscribedPath = null
        _state.update { current ->
            current.copy(
                meetingPhase = MeetingPhase.Welcome,
                wizard = defaultWizardState(),
                review = MeetingReviewState(),
                recording = MeetingRecordingState(),
                infoMessage = null,
            )
        }
    }

    private suspend fun transcribeRecording(file: File) {
        val snapshot = _state.value
        val demeterEndpointPath = backend.demeterTranscriptionEndpointPath(file)
        _state.update { current ->
            current.copy(
                review = current.review.copy(
                    isTranscribing = true,
                    transcriptRaw = "",
                    transcriptEdited = "",
                    transcriptChunks = emptyList(),
                    selectedSpeakerAssignments = emptyList(),
                    errorMessage = null,
                    draftMessage = "Préparation du premier chunk…",
                    submitMessage = null,
                ),
                meetingPhase = MeetingPhase.Review,
            )
        }

        try {
            val transcript = demeterEngine.transcribe(
                file = file,
                modelId = "",
                backendClient = backend,
                chunkDurationSec = snapshot.wizard.cloudMistralChunkDurationSec,
                overlapSec = snapshot.wizard.cloudMistralOverlapSec,
                onChunkTranscribed = { chunkTranscript ->
                    appendTranscriptChunk(
                        transcript = chunkTranscript,
                        participants = snapshot.wizard.participants,
                    )
                },
            )
            _state.update { current ->
                val renderedTranscript = renderTranscriptFromChunks(current.review.transcriptChunks)
                val transcriptText = renderedTranscript.ifBlank { transcript.trim() }
                current.copy(
                    review = current.review.copy(
                        isTranscribing = false,
                        transcriptRaw = transcriptText,
                        transcriptEdited = transcriptText,
                        selectedSpeakerAssignments = current.review.transcriptChunks.lastOrNull()?.speakerAssignments.orEmpty(),
                        draftMessage = "Relisez la transcription puis envoyez le compte rendu",
                        errorMessage = null,
                        submitMessage = null,
                    ),
                    infoMessage = "Transcription terminée",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (sessionExpired: BackendSessionExpiredException) {
            AppLog.i(TAG, "Transcription session expired", sessionExpired)
            reportSupportIncident(
                route = "app://meeting/transcription",
                backendError = SupportReportBackendError(
                    status = 401,
                    code = "session_expired",
                    message = sessionExpired.message ?: "Session expirée",
                    path = demeterEndpointPath,
                    method = "POST",
                ),
                telemetry = JSONObject().apply {
                    put("recordingPath", file.absolutePath)
                    put("errorClass", sessionExpired.javaClass.name)
                },
                originalFile = SupportReportFile(
                    name = file.name,
                    sizeBytes = file.length(),
                    mimeType = "audio/wav",
                    source = "android",
                ),
            )
            applySessionExpiredState(sessionExpired.message ?: "Session expirée")
        } catch (error: Throwable) {
            AppLog.w(TAG, "Transcription failed", error)
            reportSupportIncident(
                route = "app://meeting/transcription",
                backendError = SupportReportBackendError(
                    status = 0,
                    code = "transcription_failed",
                    message = error.message ?: "Transcription impossible",
                    path = demeterEndpointPath,
                    method = "POST",
                ),
                telemetry = JSONObject().apply {
                    put("recordingPath", file.absolutePath)
                    put("errorClass", error.javaClass.name)
                },
                originalFile = SupportReportFile(
                    name = file.name,
                    sizeBytes = file.length(),
                    mimeType = "audio/wav",
                    source = "android",
                ),
            )
            _state.update { current ->
                current.copy(
                    review = current.review.copy(
                        isTranscribing = false,
                        errorMessage = error.message ?: "Transcription impossible",
                        draftMessage = current.review.draftMessage?.takeIf { it.isNotBlank() }
                            ?: "Transcription impossible",
                    ),
                    infoMessage = "Transcription échouée",
                )
            }
        }
    }

    fun importAudio(uri: Uri) {
        cancelTranscriptionJob()
        _state.update { current ->
            current.copy(
                meetingPhase = MeetingPhase.Review,
                review = current.review.copy(
                    isTranscribing = true,
                    transcriptRaw = "",
                    transcriptEdited = "",
                    selectedSpeakerAssignments = emptyList(),
                    errorMessage = null,
                    draftMessage = "Conversion de l'audio en cours…",
                    submitMessage = null,
                ),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val convertedFile = runCatching {
                AudioImportConverter.convertToWav(
                    context = app.applicationContext,
                    sourceUri = uri,
                    outputDir = File(app.cacheDir, "audio-imports"),
                )
            }.getOrElse { error ->
                AppLog.w(TAG, "Audio import failed", error)
                viewModelScope.launch(Dispatchers.IO) {
                    reportSupportIncident(
                        route = "app://meeting/import-audio",
                        backendError = SupportReportBackendError(
                            status = 0,
                            code = "audio_import_failed",
                            message = error.message ?: "Import audio impossible",
                            path = "content://audio-import",
                            method = "OPEN_DOCUMENT",
                        ),
                        telemetry = JSONObject().apply {
                            put("sourceUri", uri.toString())
                            put("errorClass", error.javaClass.name)
                        },
                    )
                }
                _state.update { current ->
                    current.copy(
                        review = current.review.copy(
                            isTranscribing = false,
                            errorMessage = error.message ?: "Import audio impossible",
                            draftMessage = error.message ?: "Import audio impossible",
                        ),
                        infoMessage = error.message ?: "Import audio impossible",
                    )
                }
                return@launch
            }

            deleteLatestRecordingArtifact()
            RecordingRepository.reset()
            lastTranscribedPath = null
            RecordingRepository.finishRecording(convertedFile)
        }
    }

    private fun defaultWizardState(): MeetingWizardState {
        return buildDefaultMeetingWizardState(
            localWhisperModelId = prefs.localWhisperModelId(),
            cloudMistralChunkDurationSec = prefs.demeterChunkDurationSec(),
            cloudMistralOverlapSec = prefs.demeterOverlapSec(),
            reportModelId = prefs.reportModelId(),
            reportTemperature = prefs.reportTemperature(),
            reportMaxTokens = prefs.reportMaxTokens(),
        )
    }

    private fun normalizeParticipants(participants: List<MeetingParticipant>): List<MeetingParticipant> {
        val cleaned = participants
            .map { it.copy(displayName = it.displayName.trim()) }
            .filter { it.displayName.isNotBlank() }
        return if (cleaned.isEmpty()) listOf(MeetingParticipant(displayName = "")) else cleaned
    }

    private fun normalizeParticipantNames(participants: List<MeetingParticipant>): List<String> {
        return participants.mapNotNull { participant ->
            participant.displayName.trim().takeIf { it.isNotBlank() }
        }
    }

    private fun orderedReportFormats(selected: Set<ReportFormat>): List<ReportFormat> {
        return ReportFormat.entries.filter { selected.contains(it) }
    }

    private fun allReportFormats(): List<ReportFormat> {
        return ReportFormat.entries.toList()
    }

    private fun buildDefaultSpeakerAssignments(transcript: String, participants: List<MeetingParticipant>): List<SpeakerAssignmentUi> {
        val inferredLabels = buildSpeakerAssignmentsFromTranscript(transcript)
        if (inferredLabels.isNotEmpty()) {
            return inferredLabels
        }
        val names = normalizeParticipantNames(participants)
        if (names.isEmpty()) {
            return listOf(SpeakerAssignmentUi(speakerId = "speaker_1"))
        }
        return names.mapIndexed { index, name ->
            val parts = name.split(Regex("\\s+")).filter { it.isNotBlank() }
            SpeakerAssignmentUi(
                speakerId = "speaker_${index + 1}",
                firstName = parts.firstOrNull().orEmpty(),
                lastName = parts.drop(1).joinToString(" "),
            )
        }
    }

    private fun updateCurrentSpeakerAssignments(
        transform: (List<SpeakerAssignmentUi>) -> List<SpeakerAssignmentUi>,
    ) {
        _state.update { current ->
            val review = current.review
            val currentAssignments = review.transcriptChunks.lastOrNull()?.speakerAssignments ?: review.selectedSpeakerAssignments
            val updatedAssignments = transform(currentAssignments)
            val updatedChunks = if (review.transcriptChunks.isNotEmpty()) {
                review.transcriptChunks.dropLast(1) + MeetingTranscriptChunkUi(
                    rawText = review.transcriptChunks.last().rawText,
                    speakerAssignments = updatedAssignments,
                )
            } else {
                review.transcriptChunks
            }
            val rebuiltTranscript = if (updatedChunks.isNotEmpty()) {
                renderTranscriptFromChunks(updatedChunks)
            } else {
                review.transcriptEdited
            }
            current.copy(
                review = review.copy(
                    transcriptChunks = updatedChunks,
                    selectedSpeakerAssignments = updatedAssignments,
                    transcriptRaw = rebuiltTranscript,
                    transcriptEdited = rebuiltTranscript,
                    drafts = emptyList(),
                    draftMessage = when {
                        updatedChunks.isEmpty() -> review.draftMessage
                        review.isTranscribing -> "Transcription en cours…"
                        else -> "Les rapports doivent être régénérés"
                    },
                    submitMessage = null,
                )
            )
        }
    }

    private fun appendTranscriptChunk(transcript: String, participants: List<MeetingParticipant>) {
        val parsedChunks = splitChunkedTranscriptDisplay(transcript)
        if (parsedChunks.isEmpty()) {
            return
        }
        _state.update { current ->
            val review = current.review
            val existingChunks = review.transcriptChunks
            if (parsedChunks.size <= existingChunks.size) {
                return@update current
            }

            var updatedChunks = existingChunks
            for (index in existingChunks.size until parsedChunks.size) {
                val rawChunk = parsedChunks[index]
                updatedChunks = updatedChunks + MeetingTranscriptChunkUi(
                    rawText = rawChunk,
                    speakerAssignments = buildDefaultSpeakerAssignments(rawChunk, participants),
                )
            }

            val rebuiltTranscript = renderTranscriptFromChunks(updatedChunks)
            current.copy(
                review = review.copy(
                    transcriptChunks = updatedChunks,
                    selectedSpeakerAssignments = updatedChunks.lastOrNull()?.speakerAssignments.orEmpty(),
                    transcriptRaw = rebuiltTranscript,
                    transcriptEdited = rebuiltTranscript,
                    draftMessage = "Transcription en cours…",
                    errorMessage = null,
                    submitMessage = null,
                )
            )
        }
    }

    private fun aggregateSpeakerAssignments(review: MeetingReviewState): List<SpeakerAssignmentUi> {
        val sourceChunks = if (review.transcriptChunks.isNotEmpty()) {
            review.transcriptChunks
        } else {
            listOf(
                MeetingTranscriptChunkUi(
                    rawText = review.transcriptEdited.ifBlank { review.transcriptRaw },
                    speakerAssignments = review.selectedSpeakerAssignments,
                )
            )
        }
        val aggregated = linkedMapOf<String, SpeakerAssignmentUi>()
        for (chunk in sourceChunks) {
            for (assignment in chunk.speakerAssignments) {
                val speakerId = assignment.speakerId.trim()
                if (speakerId.isBlank()) {
                    continue
                }
                val existing = aggregated[speakerId]
                val merged = existing?.copy(
                    firstName = assignment.firstName.ifBlank { existing.firstName },
                    lastName = assignment.lastName.ifBlank { existing.lastName },
                    confirmedLabel = assignment.confirmedLabel?.takeIf { it.isNotBlank() }
                        ?: existing.confirmedLabel,
                    isValidated = existing.isValidated || assignment.isValidated,
                ) ?: assignment
                aggregated[speakerId] = merged
            }
        }
        return aggregated.values.toList()
    }

    private fun nextSpeakerId(assignments: List<SpeakerAssignmentUi>): String {
        val suffixPattern = Regex("""speaker_(\d+)$""", RegexOption.IGNORE_CASE)
        val nextIndex = assignments.mapNotNull { assignment ->
            suffixPattern.find(assignment.speakerId.trim())
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        }.maxOrNull()?.plus(1) ?: (assignments.size + 1)
        return "speaker_$nextIndex"
    }

    private fun buildSpeakerDisplayName(firstName: String, lastName: String): String {
        return listOf(firstName.trim(), lastName.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun parseRecipientEmails(rawValue: String): List<String> {
        val seen = linkedSetOf<String>()
        rawValue
            .split(Regex("[,;\\n\\r]+"))
            .forEach { raw ->
                val candidate = raw.trim().lowercase()
                if (candidate.isBlank()) {
                    return@forEach
                }
                if (!Patterns.EMAIL_ADDRESS.matcher(candidate).matches()) {
                    throw IllegalArgumentException("Adresse email invalide: $candidate")
                }
                seen.add(candidate)
            }
        return seen.toList()
    }

    private fun cancelTranscriptionJob() {
        transcriptionJob?.cancel()
        transcriptionJob = null
    }

    private fun deleteLatestRecordingArtifact() {
        RecordingRepository.latestRecordingFile?.let { file ->
            runCatching {
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    companion object {
        private const val TAG = "DemeterSpeechVM"
        private const val FINALIZE_OPERATION_STATUS_PENDING = "pending"
        private const val FINALIZE_OPERATION_STATUS_COMPLETED = "completed"
        private const val FINALIZE_OPERATION_STATUS_FAILED = "failed"
        private const val FINALIZE_OPERATION_POLL_DELAY_MS = 2_000L
    }
}
