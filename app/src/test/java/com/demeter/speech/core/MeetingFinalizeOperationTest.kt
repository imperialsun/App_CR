package com.demeter.speech.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MeetingFinalizeOperationTest {
    @Test
    fun `round-trips the pending finalize snapshot`() {
        val snapshot = PendingMeetingFinalizeOperationSnapshot(
            operationId = "op-123",
            organizationId = "org-123",
            userId = "user-123",
            requestJson = """{"operationId":"op-123","meetingTitle":"Réunion"}""",
            createdAtEpochMs = 1_746_500_000_000,
        )

        val decoded = PendingMeetingFinalizeOperationSnapshot.fromJson(snapshot.toJson())

        assertEquals(snapshot, decoded)
        assertFalse(decoded.isExpired(nowEpochMs = snapshot.createdAtEpochMs + 1_000L))
    }

    @Test
    fun `builds finalize request json with the operation id`() {
        val request = MeetingFinalizeRequest(
            operationId = "op-456",
            meetingTitle = "Réunion qualité",
            participants = listOf("Alice", "Bob"),
            transcriptionSourceMode = TranscriptionSourceMode.DEMETER_BACKEND,
            rawTranscriptText = "Bonjour",
            editedTranscriptText = "Bonjour",
            selectedFormats = listOf(ReportFormat.CRI, ReportFormat.CRO),
            reportModelId = "mistral-medium-latest",
            reportTemperature = 0.2f,
            reportMaxTokens = 2048,
            reports = listOf(
                MeetingDraftEnvelopeUi(
                    format = ReportFormat.CRI,
                    report = MeetingReportDraftUi(
                        format = ReportFormat.CRI,
                        title = "CRI",
                        subtitle = "Synthèse",
                        sections = listOf(
                            MeetingReportSection(
                                heading = "Contexte",
                                paragraphs = listOf("Point 1"),
                            ),
                        ),
                        keyPoints = listOf("Point clé"),
                        actionItems = listOf("Action"),
                        caveats = listOf("Caveat"),
                    ),
                    raw = "raw",
                    modelId = "mistral-medium-latest",
                    generatedAt = "2026-03-29T10:00:00Z",
                    sourceMode = "cloud_backend",
                    provider = "demeter_sante",
                    sourceTokenCount = 12,
                ),
            ),
            speakerAssignments = listOf(
                SpeakerAssignmentUi(
                    speakerId = "speaker_1",
                    firstName = "Alice",
                    lastName = "Martin",
                ),
            ),
            recipientEmails = listOf("assistant@example.com"),
        )

        val json = buildMeetingFinalizeRequestJson(request)

        assertEquals("op-456", json.get("operationId").toString().removeSurrounding("\""))
        assertEquals("Réunion qualité", json.get("meetingTitle").toString().removeSurrounding("\""))
        assertEquals("cloud_backend", json.get("transcriptionSourceMode").toString().removeSurrounding("\""))
        assertEquals("demeter_sante", json.get("transcriptionProvider").toString().removeSurrounding("\""))
        assertEquals(1, json.getAsJsonArray("speakerAssignments").size())
        assertEquals(1, json.getAsJsonArray("reports").size())
        assertEquals("assistant@example.com", json.getAsJsonArray("recipientEmails").get(0).toString().removeSurrounding("\""))
    }

    @Test
    fun `parses a completed finalize operation status payload`() {
        val status = parseMeetingFinalizeOperationStatus(
            """{
                "operationId":"op-789",
                "status":"completed",
                "statusCode":200,
                "response":{
                    "operationId":"op-789",
                    "meetingTitle":"Réunion qualité",
                    "participants":["Alice"],
                    "transcriptionSourceMode":"cloud_backend",
                    "transcriptionProvider":"demeter_sante",
                    "reportSourceMode":"cloud_backend",
                    "reportProvider":"demeter_sante",
                    "selectedFormats":["CRI"],
                    "sentTo":"alice@example.com",
                    "sentToEmails":["alice@example.com"],
                    "generatedAt":"2026-03-29T10:00:00Z",
                    "transcriptDocxFilename":"transcription-20260329.docx",
                    "reportDocxFilenames":["report-cri-20260329.docx"],
                    "attachments":[]
                },
                "updatedAt":"2026-03-29T10:00:05Z",
                "expiresAt":"2026-03-30T10:00:05Z"
            }"""
        )

        assertEquals("op-789", status.operationId)
        assertEquals("completed", status.status)
        assertEquals(200, status.statusCode)
        assertNotNull(status.response)
        assertEquals("op-789", status.response?.operationId)
        assertNull(status.error)
        assertEquals("2026-03-29T10:00:05Z", status.updatedAt)
        assertEquals("2026-03-30T10:00:05Z", status.expiresAt)
    }
}
