package com.demeter.speech.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ProcessingTaskKind {
    TranscriptionWithSpeakers,
    ReportsWithSpeakers,
    ReportsFromAudio,
}

enum class ProcessingTaskPhase {
    Running,
    Completed,
    Failed,
}

data class ProcessingTaskState(
    val kind: ProcessingTaskKind,
    val phase: ProcessingTaskPhase,
    val operationId: String = "",
    val audioPath: String = "",
    val audioDisplayName: String = "",
    val audioOrigin: AudioOrigin = AudioOrigin.Imported,
    val title: String = "",
    val reportPayloadPath: String = "",
    val detailLevels: ReportDetailLevels = ReportDetailLevels(),
    val operation: OperationStatus? = null,
    val retryAttempt: Int = 0,
    val retryMessage: String = "",
    val waitJoke: String = "",
    val chunks: List<TranscriptChunk> = emptyList(),
    val segments: List<TranscriptSegment> = emptyList(),
    val files: List<SentFile> = emptyList(),
    val successCanSaveAudio: Boolean = false,
    val error: String? = null,
)

object ProcessingTaskEvents {
    private val mutableState = MutableStateFlow<ProcessingTaskState?>(null)
    val state: StateFlow<ProcessingTaskState?> = mutableState.asStateFlow()

    fun publish(state: ProcessingTaskState) {
        mutableState.value = state
    }

    fun clear() {
        mutableState.value = null
    }
}
