package com.demeter.speech.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class PendingMeetingFinalizeOperationSnapshot(
    val operationId: String,
    val organizationId: String,
    val userId: String,
    val requestJson: String,
    val createdAtEpochMs: Long,
) {
    fun toJson(): String = JsonObject().apply {
        addProperty("operationId", operationId)
        addProperty("organizationId", organizationId)
        addProperty("userId", userId)
        addProperty("requestJson", requestJson)
        addProperty("createdAtEpochMs", createdAtEpochMs)
    }.toString()

    fun isExpired(nowEpochMs: Long = System.currentTimeMillis(), retentionMs: Long = FINALIZE_OPERATION_RETENTION_MS): Boolean {
        return createdAtEpochMs > 0L && nowEpochMs - createdAtEpochMs >= retentionMs
    }

    companion object {
        fun fromJson(rawJson: String): PendingMeetingFinalizeOperationSnapshot {
            return fromJson(parseJsonObject(rawJson))
        }

        fun fromJson(json: JsonObject): PendingMeetingFinalizeOperationSnapshot {
            val operationId = json.optString("operationId")
            val organizationId = json.optString("organizationId")
            val userId = json.optString("userId")
            val requestJson = json.optString("requestJson")
            val createdAtEpochMs = json.optLong("createdAtEpochMs")
            if (operationId.isBlank() || organizationId.isBlank() || userId.isBlank() || requestJson.isBlank() || createdAtEpochMs <= 0L) {
                throw IllegalArgumentException("invalid pending finalize operation snapshot")
            }
            return PendingMeetingFinalizeOperationSnapshot(
                operationId = operationId,
                organizationId = organizationId,
                userId = userId,
                requestJson = requestJson,
                createdAtEpochMs = createdAtEpochMs,
            )
        }
    }
}

data class MeetingFinalizeOperationStatusDto(
    val operationId: String,
    val status: String,
    val statusCode: Int,
    val response: MeetingFinalizeResponseDto? = null,
    val error: String? = null,
    val updatedAt: String = "",
    val expiresAt: String? = null,
)

class MeetingFinalizeOperationInProgressException(message: String) : IllegalStateException(message)

fun buildMeetingFinalizeRequestBody(requestDto: MeetingFinalizeRequest): String {
    return buildMeetingFinalizeRequestJson(requestDto).toString()
}

fun buildMeetingFinalizeRequestJson(requestDto: MeetingFinalizeRequest): JsonObject {
    return JsonObject().apply {
        addProperty("operationId", requestDto.operationId)
        addProperty("meetingTitle", requestDto.meetingTitle)
        add("participants", requestDto.participants.toJsonArray())
        addProperty("transcriptionSourceMode", requestDto.transcriptionSourceMode.apiValue)
        addProperty("transcriptionProvider", requestDto.transcriptionSourceMode.provider)
        addProperty("rawTranscriptText", requestDto.rawTranscriptText)
        addProperty("editedTranscriptText", requestDto.editedTranscriptText)
        add("selectedFormats", requestDto.selectedFormats.map { it.apiValue }.toJsonArray())
        add("recipientEmails", requestDto.recipientEmails.toJsonArray())
        addProperty("reportModelId", requestDto.reportModelId)
        addProperty("reportTemperature", requestDto.reportTemperature)
        addProperty("reportMaxTokens", requestDto.reportMaxTokens)
        add("speakerAssignments", JsonArray().apply {
            requestDto.speakerAssignments.forEach { assignment ->
                add(assignment.toJsonObject())
            }
        })
        add("reports", JsonArray().apply {
            requestDto.reports.forEach { report ->
                add(report.toFinalizeJsonObject())
            }
        })
    }
}

fun parseMeetingFinalizeResponse(rawJson: String): MeetingFinalizeResponseDto {
    return parseMeetingFinalizeResponse(parseJsonObject(rawJson))
}

fun parseMeetingFinalizeResponse(json: JsonObject): MeetingFinalizeResponseDto {
    return MeetingFinalizeResponseDto(
        operationId = json.optString("operationId"),
        meetingTitle = json.optString("meetingTitle"),
        participants = json.optStringList("participants"),
        transcriptionSourceMode = json.optString("transcriptionSourceMode"),
        transcriptionProvider = json.optString("transcriptionProvider"),
        reportSourceMode = json.optString("reportSourceMode"),
        reportProvider = json.optString("reportProvider"),
        selectedFormats = json.optStringList("selectedFormats"),
        sentTo = json.optString("sentTo"),
        sentToEmails = json.optStringList("sentToEmails"),
        generatedAt = json.optString("generatedAt"),
        transcriptDocxFilename = json.optString("transcriptDocxFilename"),
        reportDocxFilenames = json.optStringList("reportDocxFilenames"),
    )
}

fun parseMeetingFinalizeOperationStatus(rawJson: String): MeetingFinalizeOperationStatusDto {
    return parseMeetingFinalizeOperationStatus(parseJsonObject(rawJson))
}

fun parseMeetingFinalizeOperationStatus(json: JsonObject): MeetingFinalizeOperationStatusDto {
    val statusCode = json.optInt("statusCode")
    val nestedResponse = json.optJsonObject("response")
    return MeetingFinalizeOperationStatusDto(
        operationId = json.optString("operationId"),
        status = json.optString("status"),
        statusCode = statusCode,
        response = nestedResponse?.takeIf { statusCode in 200..299 }?.let(::parseMeetingFinalizeResponse),
        error = json.optString("error").trim().takeIf { it.isNotBlank() },
        updatedAt = json.optString("updatedAt"),
        expiresAt = json.optString("expiresAt").trim().takeIf { it.isNotBlank() },
    )
}

private const val FINALIZE_OPERATION_RETENTION_MS = 24L * 60L * 60L * 1000L

private fun parseJsonObject(rawJson: String): JsonObject {
    if (rawJson.isBlank()) {
        return JsonObject()
    }
    return runCatching {
        val parsed = JsonParser.parseString(rawJson)
        if (parsed.isJsonObject) {
            parsed.asJsonObject
        } else {
            JsonObject()
        }
    }.getOrDefault(JsonObject())
}

private fun JsonObject.optString(key: String): String {
    if (!has(key)) {
        return ""
    }
    val element = get(key)
    if (element == null || element.isJsonNull) {
        return ""
    }
    return element.toString().decodeJsonScalarString().trim()
}

private fun JsonObject.optLong(key: String): Long {
    if (!has(key)) {
        return 0L
    }
    val element = get(key)
    if (element == null || element.isJsonNull) {
        return 0L
    }
    return element.toString().trim().toLongOrNull() ?: 0L
}

private fun JsonObject.optInt(key: String): Int {
    if (!has(key)) {
        return 0
    }
    val element = get(key)
    if (element == null || element.isJsonNull) {
        return 0
    }
    return element.toString().trim().toIntOrNull() ?: 0
}

private fun JsonObject.optJsonObject(key: String): JsonObject? {
    if (!has(key)) {
        return null
    }
    val element = get(key)
    if (element == null || element.isJsonNull || !element.isJsonObject) {
        return null
    }
    return element.asJsonObject
}

private fun JsonObject.optJsonArray(key: String): JsonArray? {
    if (!has(key)) {
        return null
    }
    val element = get(key)
    if (element == null || element.isJsonNull || !element.isJsonArray) {
        return null
    }
    return element.asJsonArray
}

private fun JsonObject.optStringList(key: String): List<String> {
    return optJsonArray(key).toStringList()
}

private fun JsonArray?.toStringList(): List<String> {
    val source = this ?: return emptyList()
    val values = mutableListOf<String>()
    val size = source.size()
    for (index in 0 until size) {
        val value = source.get(index).toString().decodeJsonScalarString().trim()
        if (value.isNotEmpty()) {
            values.add(value)
        }
    }
    return values
}

private fun List<String>.toJsonArray(): JsonArray {
    return JsonArray().apply {
        for (value in this@toJsonArray) {
            add(value)
        }
    }
}

private fun SpeakerAssignmentUi.toJsonObject(): JsonObject {
    return JsonObject().apply {
        addProperty("speakerId", speakerId)
        addProperty("firstName", firstName)
        addProperty("lastName", lastName)
    }
}

private fun MeetingDraftEnvelopeUi.toFinalizeJsonObject(): JsonObject {
    return JsonObject().apply {
        addProperty("format", format.apiValue)
        add("report", report.toFinalizeJsonObject())
        addProperty("raw", raw)
        addProperty("modelId", modelId)
        addProperty("generatedAt", generatedAt)
        addProperty("sourceMode", sourceMode)
        addProperty("provider", provider)
        addProperty("sourceTokenCount", sourceTokenCount)
    }
}

private fun MeetingReportDraftUi.toFinalizeJsonObject(): JsonObject {
    return JsonObject().apply {
        addProperty("format", format.apiValue)
        addProperty("title", title)
        addProperty("subtitle", subtitle)
        add("sections", JsonArray().apply {
            sections.forEach { section ->
                add(section.toFinalizeJsonObject())
            }
        })
        add("key_points", keyPoints.toJsonArray())
        add("action_items", actionItems.toJsonArray())
        add("caveats", caveats.toJsonArray())
    }
}

private fun MeetingReportSection.toFinalizeJsonObject(): JsonObject {
    return JsonObject().apply {
        addProperty("heading", heading)
        add("paragraphs", paragraphs.toJsonArray())
    }
}

private fun String.decodeJsonScalarString(): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) {
        return ""
    }
    if (trimmed.length < 2 || trimmed.first() != '"' || trimmed.last() != '"') {
        return trimmed
    }
    val content = trimmed.substring(1, trimmed.length - 1)
    val out = StringBuilder(content.length)
    var index = 0
    while (index < content.length) {
        val ch = content[index]
        if (ch != '\\') {
            out.append(ch)
            index++
            continue
        }
        if (index + 1 >= content.length) {
            out.append(ch)
            index++
            continue
        }
        when (val next = content[index + 1]) {
            '"' -> out.append('"')
            '\\' -> out.append('\\')
            '/' -> out.append('/')
            'b' -> out.append('\b')
            'f' -> out.append('\u000c')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> {
                if (index + 5 < content.length) {
                    val hex = content.substring(index + 2, index + 6)
                    val codePoint = hex.toIntOrNull(16)
                    if (codePoint != null) {
                        out.append(codePoint.toChar())
                        index += 4
                    } else {
                        out.append(next)
                    }
                } else {
                    out.append(next)
                }
            }
            else -> out.append(next)
        }
        index += 2
    }
    return out.toString()
}
