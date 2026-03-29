package com.demeter.speech.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class MeetingTitleTest {
    @Test
    fun `builds the default meeting title from a fixed date`() {
        val date = LocalDate.of(2026, 3, 29)

        assertEquals("Réunion du 29/03/2026", defaultMeetingTitle(date))
    }

    @Test
    fun `uses the user provided title when present`() {
        val date = LocalDate.of(2026, 3, 29)

        assertEquals("Réunion qualité hebdo", resolvedMeetingTitle("  Réunion qualité hebdo  ", date))
    }

    @Test
    fun `falls back to the default title when the input is blank`() {
        val date = LocalDate.of(2026, 3, 29)

        assertEquals("Réunion du 29/03/2026", resolvedMeetingTitle("   ", date))
    }

    @Test
    fun `builds the default wizard state with a dated title`() {
        val date = LocalDate.of(2026, 3, 29)

        val state = buildDefaultMeetingWizardState(date = date)

        assertEquals("Réunion du 29/03/2026", state.title)
    }
}
