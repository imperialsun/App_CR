package com.demeter.speech.core

import org.json.JSONArray
import org.json.JSONObject

fun AuthResponse.toBackendSession(): BackendSession {
    return BackendSession(
        userId = user.id,
        email = user.email,
        organizationId = organization.id,
        organizationName = organization.name,
        permissions = permissions,
    )
}

fun MeetingDraftResponseDto.toUiDrafts(): List<MeetingDraftEnvelopeUi> {
    return reports.map { it.toUi() }
}

fun MeetingDraftEnvelopeDto.toUi(): MeetingDraftEnvelopeUi {
    val reportFormat = ReportFormat.fromApiValue(format) ?: ReportFormat.CRI
    return MeetingDraftEnvelopeUi(
        format = reportFormat,
        report = report.toMeetingReportDraftUi(reportFormat),
        raw = raw,
        modelId = modelId,
        generatedAt = generatedAt,
        sourceMode = sourceMode,
        provider = provider,
        sourceTokenCount = sourceTokenCount,
    )
}

fun JSONObject.toMeetingReportDraftUi(defaultFormat: ReportFormat = ReportFormat.CRI): MeetingReportDraftUi {
    val sections = buildList {
        val rawSections = optJSONArray("sections")
        if (rawSections != null) {
            for (index in 0 until rawSections.length()) {
                val section = rawSections.optJSONObject(index) ?: continue
                val heading = section.optString("heading").trim()
                if (heading.isEmpty()) continue
                val paragraphs = section.optJSONArray("paragraphs").toStringListOrEmpty()
                if (paragraphs.isEmpty()) continue
                add(MeetingReportSection(heading = heading, paragraphs = paragraphs))
            }
        }
    }

    val keyPoints = optJSONArray("key_points").toStringListOrEmpty()
    val actionItems = optJSONArray("action_items").toStringListOrEmpty()
    val caveats = optJSONArray("caveats").toStringListOrEmpty()

    return MeetingReportDraftUi(
        format = ReportFormat.fromApiValue(optString("format")) ?: defaultFormat,
        title = optString("title").trim().ifBlank { "Compte rendu ${defaultFormat.apiValue}" },
        subtitle = optString("subtitle").trim().ifBlank { null },
        sections = sections.ifEmpty {
            listOf(
                MeetingReportSection(
                    heading = "Synthèse",
                    paragraphs = listOf("Le modèle n’a pas renvoyé de sections structurées."),
                )
            )
        },
        keyPoints = keyPoints,
        actionItems = actionItems,
        caveats = caveats,
        raw = toString(),
    )
}

private fun JSONArray?.toStringListOrEmpty(): List<String> {
    if (this == null) {
        return emptyList()
    }
    return buildList {
        for (index in 0 until length()) {
            val value = optString(index).trim()
            if (value.isNotEmpty()) {
                add(value)
            }
        }
    }
}
