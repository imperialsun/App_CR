package com.demeter.speech.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionFormattingTest {
    @Test
    fun `normalizes generic speaker labels and preserves named speakers`() {
        val transcript = """
            SPEAKER_00: Bonjour
            SPEAKER_01: Salut
            Dr Martin: Très bien
        """.trimIndent()

        val expected = """
            Speaker 1: Bonjour

            Speaker 2: Salut

            Dr Martin: Très bien
        """.trimIndent()

        assertEquals(expected, formatTranscriptForDisplay(transcript))
    }

    @Test
    fun `formats segment based transcript with visible speakers`() {
        val transcript = ""
        val segments = listOf(
            DemeterTranscriptionSegmentDto(
                speakerId = "SPEAKER_00",
                text = "Bonjour",
            ),
            DemeterTranscriptionSegmentDto(
                speakerId = "SPEAKER_01",
                text = "Salut",
            ),
        )

        val expected = """
            Speaker 1: Bonjour

            Speaker 2: Salut
        """.trimIndent()

        assertEquals(expected, formatTranscriptForDisplay(transcript, segments))
    }

    @Test
    fun `validates only the current chunk`() {
        val chunk = """
            Speaker 1: Bonjour

            Speaker 2: Salut
        """.trimIndent()
        val assignments = listOf(
            SpeakerAssignmentUi(
                speakerId = "Speaker 1",
                confirmedLabel = "Dr Martin",
                isValidated = true,
            ),
            SpeakerAssignmentUi(
                speakerId = "Speaker 2",
            ),
        )

        val expected = """
            Dr Martin: Bonjour

            Speaker 2: Salut
        """.trimIndent()

        assertEquals(expected, renderTranscriptChunk(chunk, assignments))
    }

    @Test
    fun `splits chunked transcripts into bodies`() {
        val transcript = """
            Partie 1
            Speaker 1: Bonjour

            Partie 2
            Speaker 2: Salut
        """.trimIndent()

        assertEquals(
            listOf(
                "Speaker 1: Bonjour",
                "Speaker 2: Salut",
            ),
            splitChunkedTranscriptDisplay(transcript),
        )
    }

    @Test
    fun `does not propagate validation to the next chunk`() {
        val chunks = listOf(
            MeetingTranscriptChunkUi(
                rawText = "Speaker 1: Bonjour",
                speakerAssignments = listOf(
                    SpeakerAssignmentUi(
                        speakerId = "Speaker 1",
                        confirmedLabel = "Dr Martin",
                        isValidated = true,
                    ),
                ),
            ),
            MeetingTranscriptChunkUi(
                rawText = "Speaker 1: Salut",
                speakerAssignments = listOf(
                    SpeakerAssignmentUi(speakerId = "Speaker 1"),
                ),
            ),
        )

        val expected = """
            Partie 1
            Dr Martin: Bonjour

            Partie 2
            Speaker 1: Salut
        """.trimIndent()

        assertEquals(expected, renderTranscriptFromChunks(chunks))
    }

    @Test
    fun `adds part headers when transcript is chunked`() {
        val result = joinChunkedTranscriptDisplay(
            listOf(
                "Speaker 1: Bonjour",
                "Speaker 2: Salut",
            )
        )

        val expected = """
            Partie 1
            Speaker 1: Bonjour

            Partie 2
            Speaker 2: Salut
        """.trimIndent()

        assertEquals(expected, result)
    }

    @Test
    fun `builds speaker assignments from transcript labels`() {
        val transcript = """
            Speaker 1: Bonjour
            Speaker 2: Salut
            Dr Martin: Très bien
        """.trimIndent()

        val assignments = buildSpeakerAssignmentsFromTranscript(transcript)

        assertEquals(
            listOf("Speaker 1", "Speaker 2", "Dr Martin"),
            assignments.map { it.speakerId }
        )
    }
}
