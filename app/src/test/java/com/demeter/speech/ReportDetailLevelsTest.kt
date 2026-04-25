package com.demeter.speech

import com.demeter.speech.core.DetailLevel
import com.demeter.speech.core.ReportDetailLevels
import com.demeter.speech.core.ReportFormat
import org.junit.Assert.assertEquals
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
    }
}
