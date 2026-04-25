package com.demeter.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.demeter.speech.core.CorrectedReportPayload
import com.demeter.speech.core.DetailLevel
import com.demeter.speech.core.ReportDetailLevels
import com.demeter.speech.core.ReportPayloadStore
import com.demeter.speech.core.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReportPayloadStoreTest {
    @Test
    fun writesReadsAndDeletesCorrectedReportPayload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ReportPayloadStore(context)
        val payload = CorrectedReportPayload(
            title = "Compte rendu mobile",
            rawTranscript = "Interlocuteur 1: Bonjour",
            editedTranscript = "Patient Dupont: Bonjour",
            segments = listOf(
                TranscriptSegment(
                    id = "segment-1",
                    chunkIndex = 1,
                    speaker = "Patient Dupont",
                    text = "Bonjour",
                    startMs = 120.0,
                    endMs = 420.0,
                ),
            ),
            detailLevels = ReportDetailLevels(cri = DetailLevel.Verbose, crs = DetailLevel.Exhaustive),
        )

        val file = store.write(payload)

        assertTrue(file.exists())
        assertEquals(payload, store.read(file))

        store.delete(file)

        assertFalse(file.exists())
    }
}
