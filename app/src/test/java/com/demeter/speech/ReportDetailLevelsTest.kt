package com.demeter.speech

import com.demeter.speech.core.DetailLevel
import com.demeter.speech.core.defaultReportFormatEnabled
import com.demeter.speech.core.ReportDetailLevels
import com.demeter.speech.core.ReportFormat
import com.demeter.speech.core.selectedReportFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportDetailLevelsTest {
    @Test
    fun updateKeepsOtherFormatsAndSerializesWireValues() {
        val levels = ReportDetailLevels()
            .update(ReportFormat.CRI, DetailLevel.Verbose)
            .update(ReportFormat.CRS, DetailLevel.Exhaustive)

        assertEquals("verbose", levels.toWireMap()["CRI"])
        assertEquals("standard", levels.toWireMap()["CRO"])
        assertEquals("exhaustive", levels.toWireMap()["CRS"])
        assertEquals("standard", levels.toWireMap()["CRN"])
    }

    @Test
    fun selectedReportFormatsKeepsOnlyEnabledEntriesIncludingCrn() {
        val enabled = defaultReportFormatEnabled().toMutableMap().apply {
            put(ReportFormat.CRO, false)
        }

        val formats = selectedReportFormats(enabled)

        assertTrue(formats.contains(ReportFormat.CRI))
        assertTrue(formats.contains(ReportFormat.CRS))
        assertTrue(formats.contains(ReportFormat.CRN))
        assertEquals(listOf(ReportFormat.CRI, ReportFormat.CRS, ReportFormat.CRN), formats)
    }
}
