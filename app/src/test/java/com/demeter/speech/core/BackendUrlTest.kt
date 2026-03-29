package com.demeter.speech.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BackendUrlTest {
    @Test
    fun `keeps api prefix when base already includes api v1`() {
        val url = resolveBackendRequestUrl(
            baseUrl = "https://trapi.demeter-sante.fr/api/v1",
            path = "/api/v1/auth/login",
        )

        assertEquals("https://trapi.demeter-sante.fr/api/v1/auth/login", url)
    }

    @Test
    fun `adds api prefix when base is host root`() {
        val url = resolveBackendRequestUrl(
            baseUrl = "http://10.0.2.2:8080",
            path = "/api/v1/providers/demeter-sante/models",
        )

        assertEquals("http://10.0.2.2:8080/api/v1/providers/demeter-sante/models", url)
    }

    @Test
    fun `trims trailing slashes and whitespace`() {
        val url = resolveBackendRequestUrl(
            baseUrl = " https://trapi.demeter-sante.fr/api/v1/ ",
            path = " /api/v1/meetings/finalize ",
        )

        assertEquals("https://trapi.demeter-sante.fr/api/v1/meetings/finalize", url)
    }
}
