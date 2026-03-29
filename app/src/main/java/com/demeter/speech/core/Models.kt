package com.demeter.speech.core

import java.util.UUID

const val DEFAULT_REPORT_MODEL_ID = "mistral-medium-latest"
const val DEFAULT_DEMETER_CHUNK_DURATION_SEC = 900

enum class TranscriptionSourceMode(val apiValue: String, val provider: String, val label: String) {
    LOCAL("local", "mic", "Local"),
    DEMETER_BACKEND("cloud_backend", "demeter_sante", "Demeter Santé");

    companion object {
        fun fromApiValue(value: String?): TranscriptionSourceMode {
            return entries.firstOrNull { it.apiValue == value?.trim()?.lowercase() } ?: LOCAL
        }
    }
}

enum class ReportFormat(val apiValue: String) {
    CRI("CRI"),
    CRO("CRO"),
    CRS("CRS");

    companion object {
        fun fromApiValue(value: String?): ReportFormat? {
            val normalized = value?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.apiValue == normalized }
        }
    }
}

enum class AppAuthState {
    Checking,
    SignedOut,
    SignedIn,
}

enum class RootTab(val label: String) {
    MEETING("Réunion"),
    SETTINGS("Réglages"),
}

enum class MeetingPhase {
    Welcome,
    Wizard,
    Recording,
    Review,
}

data class MeetingParticipant(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
)

data class SpeakerAssignmentUi(
    val speakerId: String,
    val firstName: String = "",
    val lastName: String = "",
    val confirmedLabel: String? = null,
    val isValidated: Boolean = false,
)

data class MeetingTranscriptChunkUi(
    val rawText: String,
    val speakerAssignments: List<SpeakerAssignmentUi> = emptyList(),
)

data class MeetingReportSection(
    val heading: String,
    val paragraphs: List<String>,
)

data class MeetingReportDraftUi(
    val format: ReportFormat,
    val title: String,
    val subtitle: String? = null,
    val sections: List<MeetingReportSection>,
    val keyPoints: List<String> = emptyList(),
    val actionItems: List<String> = emptyList(),
    val caveats: List<String> = emptyList(),
    val raw: String = "",
)

data class MeetingDraftEnvelopeUi(
    val format: ReportFormat,
    val report: MeetingReportDraftUi,
    val raw: String,
    val modelId: String,
    val generatedAt: String,
    val sourceMode: String,
    val provider: String,
    val sourceTokenCount: Int,
)

data class MeetingWizardState(
    val title: String = "",
    val participants: List<MeetingParticipant> = listOf(MeetingParticipant(displayName = "")),
    val sourceMode: TranscriptionSourceMode = TranscriptionSourceMode.DEMETER_BACKEND,
    val selectedFormats: Set<ReportFormat> = setOf(ReportFormat.CRI, ReportFormat.CRO, ReportFormat.CRS),
    val localWhisperModelId: String = "base",
    val cloudMistralChunkDurationSec: Int = DEFAULT_DEMETER_CHUNK_DURATION_SEC,
    val cloudMistralOverlapSec: Int = 0,
    val reportModelId: String = DEFAULT_REPORT_MODEL_ID,
    val reportTemperature: Float = 0f,
    val reportMaxTokens: Int = 32768,
)

data class MeetingRecordingState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedMs: Long = 0L,
    val recordingPath: String? = null,
)

data class MeetingReviewState(
    val isTranscribing: Boolean = false,
    val transcriptRaw: String = "",
    val transcriptEdited: String = "",
    val transcriptChunks: List<MeetingTranscriptChunkUi> = emptyList(),
    val selectedSpeakerAssignments: List<SpeakerAssignmentUi> = emptyList(),
    val additionalRecipientEmails: String = "",
    val drafts: List<MeetingDraftEnvelopeUi> = emptyList(),
    val activeReportFormat: ReportFormat = ReportFormat.CRI,
    val errorMessage: String? = null,
    val isGeneratingDrafts: Boolean = false,
    val draftMessage: String? = null,
    val isSubmitting: Boolean = false,
    val submitMessage: String? = null,
)

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

data class BackendSession(
    val userId: String,
    val email: String,
    val organizationId: String,
    val organizationName: String,
    val permissions: List<String>,
)

data class DemeterSpeechUiState(
    val authState: AppAuthState = AppAuthState.Checking,
    val session: BackendSession? = null,
    val login: LoginUiState = LoginUiState(),
    val permissionsGranted: Boolean = false,
    val activeTab: RootTab = RootTab.MEETING,
    val meetingPhase: MeetingPhase = MeetingPhase.Welcome,
    val wizard: MeetingWizardState = buildDefaultMeetingWizardState(),
    val recording: MeetingRecordingState = MeetingRecordingState(),
    val review: MeetingReviewState = MeetingReviewState(),
    val infoMessage: String? = null,
)
