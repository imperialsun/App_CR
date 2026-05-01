package com.demeter.speech.core

import android.net.Uri
import java.io.File

enum class AppScreen {
    Auth,
    Home,
    Recording,
    AssignChoice,
    TranscriptionWait,
    SpeakerReview,
    ReportSettings,
    Processing,
    Success,
}

enum class AudioOrigin {
    Recorded,
    Imported,
}

enum class DetailLevel(val wire: String, val label: String) {
    Standard("standard", "Standard"),
    Verbose("verbose", "Verbeux"),
    Exhaustive("exhaustive", "Exhaustif");

    companion object {
        fun fromWire(value: String?): DetailLevel {
            return entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: Standard
        }
    }
}

fun defaultReportFormatEnabled(): Map<ReportFormat, Boolean> = ReportFormat.entries.associateWith { true }

fun selectedReportFormats(values: Map<ReportFormat, Boolean>): List<ReportFormat> {
    return ReportFormat.entries.filter { values[it] == true }
}

data class ReportDetailLevels(
    val cri: DetailLevel = DetailLevel.Standard,
    val cro: DetailLevel = DetailLevel.Standard,
    val crs: DetailLevel = DetailLevel.Standard,
    val crn: DetailLevel = DetailLevel.Standard,
) {
    fun toWireMap(): Map<String, String> = mapOf(
        "CRI" to cri.wire,
        "CRO" to cro.wire,
        "CRS" to crs.wire,
        "CRN" to crn.wire,
    )

    fun update(format: ReportFormat, level: DetailLevel): ReportDetailLevels {
        return when (format) {
            ReportFormat.CRI -> copy(cri = level)
            ReportFormat.CRO -> copy(cro = level)
            ReportFormat.CRS -> copy(crs = level)
            ReportFormat.CRN -> copy(crn = level)
        }
    }
}

fun ReportDetailLevels.levelFor(format: ReportFormat): DetailLevel {
    return when (format) {
        ReportFormat.CRI -> cri
        ReportFormat.CRO -> cro
        ReportFormat.CRS -> crs
        ReportFormat.CRN -> crn
    }
}

enum class ReportFormat(val wire: String, val title: String) {
    CRI("CRI", "CR Détaillé"),
    CRO("CRO", "CR Opérationnel"),
    CRS("CRS", "CR Synthétique"),
    CRN("CRN", "CR Narratif"),
}

data class AudioAsset(
    val file: File,
    val displayName: String,
    val origin: AudioOrigin,
    val sourceUri: Uri? = null,
)

data class AuthUser(
    val email: String = "",
)

data class TranscriptSegment(
    val id: String,
    val chunkIndex: Int,
    val speaker: String,
    val text: String,
    val startMs: Double = 0.0,
    val endMs: Double = 0.0,
)

data class SpeakerAssignment(
    val firstName: String = "",
    val lastName: String = "",
) {
    val displayName: String
        get() = listOf(lastName.trim(), firstName.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
}

fun speakerAssignmentKey(chunkIndex: Int, speakerId: String): String {
    return "$chunkIndex::${speakerId.trim()}"
}

fun resolveSpeakerLabel(
    segment: TranscriptSegment,
    assignments: Map<String, SpeakerAssignment>,
): String {
    val speakerId = segment.speaker.trim()
    if (speakerId.isBlank()) return "Interlocuteur"
    val assignment = assignments[speakerAssignmentKey(segment.chunkIndex, speakerId)]
    return assignment?.displayName?.ifBlank { speakerId } ?: speakerId
}

data class TranscriptChunk(
    val index: Int,
    val text: String,
    val segments: List<TranscriptSegment>,
    val startMs: Double = 0.0,
    val endMs: Double = 0.0,
)

data class OperationStatus(
    val operationId: String = "",
    val status: String = "",
    val stage: String = "",
    val progress: Double = 0.0,
    val chunkIndex: Int = 0,
    val chunkCount: Int = 0,
    val message: String = "",
    val lastError: String = "",
    val files: List<SentFile> = emptyList(),
    val retryAttempt: Int = 0,
) {
    val isTerminal: Boolean
        get() = status == "completed" || status == "failed" || status == "cancelled"
}

data class SentFile(
    val filename: String,
    val contentType: String,
    val sizeBytes: Int,
)

data class MobileUiState(
    val screen: AppScreen = AppScreen.Auth,
    val checkingSession: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val user: AuthUser? = null,
    val email: String = "",
    val password: String = "",
    val audio: AudioAsset? = null,
    val recording: Boolean = false,
    val paused: Boolean = false,
    val elapsedMs: Long = 0L,
    val wantsSpeakerAssignment: Boolean? = null,
    val reportFormatsEnabled: Map<ReportFormat, Boolean> = defaultReportFormatEnabled(),
    val waitMessage: String = "",
    val waitJoke: String = "",
    val transcriptChunks: List<TranscriptChunk> = emptyList(),
    val transcriptSegments: List<TranscriptSegment> = emptyList(),
    val speakerAssignments: Map<String, SpeakerAssignment> = emptyMap(),
    val reportDetails: ReportDetailLevels = ReportDetailLevels(),
    val operation: OperationStatus? = null,
    val successCanSaveAudio: Boolean = false,
    val successFiles: List<SentFile> = emptyList(),
)
