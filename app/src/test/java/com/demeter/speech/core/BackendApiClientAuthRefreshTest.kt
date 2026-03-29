package com.demeter.speech.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

private const val APP_ACCESS_COOKIE_NAME = "tc_app_access"
private const val APP_REFRESH_COOKIE_NAME = "tc_app_refresh"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackendApiClientAuthRefreshTest {
    private lateinit var server: MockWebServer
    private lateinit var client: BackendApiClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val context = ApplicationProvider.getApplicationContext<Context>()
        client = BackendApiClient(context, baseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun generateMeetingDraftsRetriesOnceAfter401AndRefreshesCookies() = runBlocking {
        server.enqueue(authResponse("user@example.com"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        server.enqueue(
            authResponse(
                email = "user@example.com",
                accessToken = "access-token-2",
                refreshToken = "refresh-token-2",
            )
        )
        server.enqueue(meetingDraftResponse())

        client.login("user@example.com", "password123")
        val response = client.generateMeetingDrafts(sampleDraftRequest())

        assertEquals("Réunion test", response.meetingTitle)
        assertEquals(listOf("CRI", "CRO", "CRS"), response.selectedFormats)
        assertEquals(4, server.requestCount)

        assertEquals("/api/v1/auth/login", server.takeRequest().path)
        assertEquals("/api/v1/meetings/reports/drafts", server.takeRequest().path)
        assertEquals("/api/v1/auth/refresh", server.takeRequest().path)
        assertEquals("/api/v1/meetings/reports/drafts", server.takeRequest().path)
    }

    @Test
    fun refreshSessionFailureClearsStoredRefreshCookie() = runBlocking {
        server.enqueue(authResponse("user@example.com"))
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("invalid refresh token")
        )

        client.login("user@example.com", "password123")
        assertTrue(client.hasStoredRefreshSession())

        try {
            client.refreshSession()
        } catch (_: BackendSessionExpiredException) {
            // expected
        }

        assertFalse(client.hasStoredRefreshSession())
        assertEquals("/api/v1/auth/login", server.takeRequest().path)
        assertEquals("/api/v1/auth/refresh", server.takeRequest().path)
    }

    private fun authResponse(email: String): MockResponse {
        return authResponse(email, accessToken = "access-token-1", refreshToken = "refresh-token-1")
    }

    private fun authResponse(
        email: String,
        accessToken: String,
        refreshToken: String,
    ): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .addHeader("Set-Cookie", accessCookie(accessToken))
            .addHeader("Set-Cookie", refreshCookie(refreshToken))
            .setBody(
                JsonObject().apply {
                    add("user", JsonObject().apply {
                        addProperty("id", "user-1")
                        addProperty("email", email)
                        addProperty("status", "active")
                    })
                    add("organization", JsonObject().apply {
                        addProperty("id", "org-1")
                        addProperty("name", "Org")
                        addProperty("code", "ORG")
                        addProperty("status", "active")
                    })
                    add("permissions", JsonArray())
                    addProperty("runtimeMode", "backend")
                }.toString()
            )
    }

    private fun meetingDraftResponse(): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                JsonObject().apply {
                    addProperty("meetingTitle", "Réunion test")
                    add("participants", JsonArray())
                    add("selectedFormats", JsonArray().apply {
                        add("CRI")
                        add("CRO")
                        add("CRS")
                    })
                    addProperty("reportSourceMode", "cloud_backend")
                    addProperty("reportProvider", "mistral")
                    addProperty("modelId", "model-a")
                    addProperty("generatedAt", "2026-03-29T10:00:00Z")
                    addProperty("sourceTokenCount", 123)
                    add("reports", JsonArray())
                }.toString()
            )
    }

    private fun sampleDraftRequest(): MeetingDraftRequest {
        return MeetingDraftRequest(
            meetingTitle = "Réunion test",
            participants = listOf("Alice", "Bob"),
            transcriptText = "Bonjour tout le monde",
            selectedFormats = listOf(ReportFormat.CRI, ReportFormat.CRO, ReportFormat.CRS),
            reportModelId = "mistral-medium-latest",
            reportTemperature = 0f,
            reportMaxTokens = 2048,
        )
    }

    private fun accessCookie(value: String): String {
        return "$APP_ACCESS_COOKIE_NAME=$value; Path=/api/v1; HttpOnly"
    }

    private fun refreshCookie(value: String): String {
        return "$APP_REFRESH_COOKIE_NAME=$value; Path=/api/v1/auth; HttpOnly"
    }
}
