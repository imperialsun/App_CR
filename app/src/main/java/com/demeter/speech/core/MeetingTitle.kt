package com.demeter.speech.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MEETING_TITLE_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE)

fun defaultMeetingTitle(date: LocalDate = LocalDate.now(ZoneId.systemDefault())): String {
    return "Réunion du ${date.format(MEETING_TITLE_DATE_FORMATTER)}"
}

fun resolvedMeetingTitle(value: String, date: LocalDate = LocalDate.now(ZoneId.systemDefault())): String {
    val normalized = value.trim()
    return if (normalized.isNotBlank()) normalized else defaultMeetingTitle(date)
}

fun buildDefaultMeetingWizardState(
    date: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    localWhisperModelId: String = "base",
    cloudMistralChunkDurationSec: Int = DEFAULT_DEMETER_CHUNK_DURATION_SEC,
    cloudMistralOverlapSec: Int = 0,
    reportModelId: String = DEFAULT_REPORT_MODEL_ID,
    reportTemperature: Float = 0f,
    reportMaxTokens: Int = 32768,
): MeetingWizardState {
    return MeetingWizardState(
        title = defaultMeetingTitle(date),
        participants = listOf(MeetingParticipant(displayName = "")),
        sourceMode = TranscriptionSourceMode.DEMETER_BACKEND,
        selectedFormats = setOf(ReportFormat.CRI, ReportFormat.CRO, ReportFormat.CRS),
        localWhisperModelId = localWhisperModelId,
        cloudMistralChunkDurationSec = cloudMistralChunkDurationSec,
        cloudMistralOverlapSec = cloudMistralOverlapSec,
        reportModelId = reportModelId,
        reportTemperature = reportTemperature,
        reportMaxTokens = reportMaxTokens,
    )
}
