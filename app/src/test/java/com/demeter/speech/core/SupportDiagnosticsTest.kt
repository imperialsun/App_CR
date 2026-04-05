package com.demeter.speech.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupportDiagnosticsTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        server = MockWebServer().apply { start() }
        AppLog.clear()
        AppPreferences(context).clearPendingSupportReports()
    }

    @After
    fun tearDown() {
        AppLog.clear()
        AppPreferences(context).clearPendingSupportReports()
        runCatching { server.shutdown() }
    }

    @Test
    fun appLogSnapshotCapturesContextAndDiagnosticBundle() {
        AppLog.i(
            tag = "SupportDiagnosticsTest",
            message = "hello",
            context = JSONObject().apply {
                put("route", "app://meeting/transcription")
            },
        )

        val logs = AppLog.snapshot()
        assertEquals(1, logs.size)
        assertEquals("INFO", logs.first().level)
        assertEquals("app://meeting/transcription", logs.first().context?.getString("route"))

        val bundle = SupportReportBuilder.buildDiagnosticBundle(
            client = "android",
            session = JSONObject().apply {
                put("route", "app://meeting/transcription")
            },
            settings = SupportReportBuilder.buildSettingsSnapshot(AppPreferences(context)),
            logs = logs,
            telemetry = JSONObject().apply {
                put("kind", "transcription_failed")
            },
        )

        assertEquals("android", bundle.getString("client"))
        assertEquals("transcription_failed", bundle.getJSONObject("telemetry").getString("kind"))
        assertEquals(1, bundle.getJSONArray("logs").length())
    }

    @Test
    fun uncaughtExceptionPayloadContainsAndroidClientAndCrashContext() {
        val payload = SupportReportBuilder.buildUncaughtExceptionReportPayload(
            preferences = AppPreferences(context),
            threadName = "main",
            throwable = RuntimeException("boom"),
            logs = AppLog.snapshot(),
        )

        assertEquals("android", payload.getString("client"))
        assertEquals("android", payload.getString("provider"))
        assertEquals("uncaught_exception", payload.getJSONObject("backendError").getString("code"))
        assertEquals(
            "android://uncaught-exception",
            payload.getJSONObject("backendError").getString("path"),
        )
        assertEquals(
            "android://uncaught-exception",
            payload.getJSONObject("diagnosticBundle").getJSONObject("session").getString("route"),
        )
    }

    @Test
    fun supportErrorReporterPostsAndroidReportAndQueuesWhenBackendFails() = runBlocking {
        val payload = SupportReportBuilder.buildSupportReportPayload(
            client = "android",
            provider = "android",
            backendError = SupportReportBackendError(
                status = 500,
                code = "uncaught_exception",
                message = "NullPointerException",
                path = "android://uncaught-exception",
                method = "PROCESS",
            ),
            originalFile = SupportReportFile.empty("android"),
            processedFile = SupportReportFile.empty("android"),
            retry = SupportReportRetry(),
            diagnosticBundle = SupportReportBuilder.buildDiagnosticBundle(
                client = "android",
                session = JSONObject().apply {
                    put("route", "android://uncaught-exception")
                },
                settings = SupportReportBuilder.buildSettingsSnapshot(AppPreferences(context)),
                logs = AppLog.snapshot(),
                telemetry = JSONObject().apply {
                    put("threadName", "main")
                },
            ),
        )

        val apiClient = BackendApiClient(context, baseUrl = server.url("/").toString())
        val reporter = SupportErrorReporter(apiClient, AppPreferences(context))

        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(reporter.submit(payload))

        val request = server.takeRequest()
        assertEquals("/api/v1/support/frontend-error-reports", request.path)
        val posted = JSONObject(request.body.readUtf8())
        assertEquals("android", posted.getString("client"))
        assertEquals("android", posted.getString("provider"))
        assertEquals("android", posted.getJSONObject("diagnosticBundle").getString("client"))
        assertEquals("uncaught_exception", posted.getJSONObject("backendError").getString("code"))

        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertFalse(reporter.submit(payload))
        assertEquals(1, AppPreferences(context).pendingSupportReports().size)
    }
}
