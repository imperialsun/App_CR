package com.demeter.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.demeter.speech.core.AlwaysAvailableNetwork
import com.demeter.speech.core.AppPreferences
import com.demeter.speech.core.BackendApiClient
import com.demeter.speech.core.DetailLevel
import com.demeter.speech.core.ProcessingTaskKind
import com.demeter.speech.core.ProcessingTaskPhase
import com.demeter.speech.core.ProcessingTaskState
import com.demeter.speech.core.ReportDetailLevels
import com.demeter.speech.core.ReportFormat
import com.demeter.speech.core.RetryPolicy
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackendApiClientRetryTest {
    private lateinit var server: MockWebServer
    private lateinit var preferences: AppPreferences

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        preferences = AppPreferences(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun retriesTransientLoginFailureThenSucceeds() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily unavailable"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"user":{"email":"a@b.test"}}"""))

        val user = client().login("a@b.test", "secret")

        assertEquals("a@b.test", user.email)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun retriesTooManyRequestsWithRetryAfterThenSucceeds() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "2"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"user":{"email":"a@b.test"}}"""))

        val user = client().login("a@b.test", "secret")

        assertEquals("a@b.test", user.email)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun doesNotRetryFunctionalLoginFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad request"}"""))

        val failed = runCatching { client().login("a@b.test", "bad") }

        assertTrue(failed.exceptionOrNull() is IOException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun retriesChunkUploadWithSameStableUploadId() = runBlocking {
        val audio = tempAudio()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"operationId":"fixed-op","status":"uploading","stage":"uploading","chunkIndex":1,"chunkCount":1}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"operationId":"fixed-op","status":"completed","stage":"email","progress":1.0}"""))

        val status = client().uploadAudioForBackendReports(
            audio = audio,
            title = "Compte rendu",
            detailLevels = ReportDetailLevels(cri = DetailLevel.Verbose),
            selectedFormats = listOf(ReportFormat.CRI, ReportFormat.CRN),
            operationId = "fixed-op",
            onStatus = {},
        )

        val firstUpload = server.takeRequest()
        val retriedUpload = server.takeRequest()
        assertEquals("fixed-op", firstUpload.getHeader("X-Demeter-Upload-Id"))
        assertEquals("fixed-op", retriedUpload.getHeader("X-Demeter-Upload-Id"))
        assertEquals(firstUpload.getHeader("X-Demeter-Upload-Index"), retriedUpload.getHeader("X-Demeter-Upload-Index"))
        assertEquals("completed", status.status)
    }

    @Test
    fun retriesPollingUntilOperationCompletes() = runBlocking {
        val audio = tempAudio()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"operationId":"poll-op","status":"uploading","stage":"uploading","chunkIndex":1,"chunkCount":1}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"operationId":"poll-op","status":"completed","stage":"email","progress":1.0}"""))

        val status = client().uploadAudioForBackendReports(
            audio = audio,
            title = "Compte rendu",
            detailLevels = ReportDetailLevels(),
            selectedFormats = listOf(ReportFormat.CRI, ReportFormat.CRN),
            operationId = "poll-op",
            onStatus = {},
        )

        assertEquals("completed", status.status)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun persistsRunningProcessingTaskForResume() = runBlocking {
        val state = ProcessingTaskState(
            kind = ProcessingTaskKind.ReportsFromAudio,
            phase = ProcessingTaskPhase.Running,
            operationId = "resume-op",
            audioPath = "/tmp/demeter.wav",
            audioDisplayName = "demeter.wav",
            title = "Compte rendu",
            detailLevels = ReportDetailLevels(crs = DetailLevel.Exhaustive),
        )

        preferences.saveProcessingState(state)
        val restored = preferences.loadProcessingState()

        assertNotNull(restored)
        assertEquals("resume-op", restored?.operationId)
        assertEquals(ProcessingTaskKind.ReportsFromAudio, restored?.kind)
        assertEquals(DetailLevel.Exhaustive, restored?.detailLevels?.crs)
    }

    private fun client(): BackendApiClient {
        return BackendApiClient(
            preferences = preferences,
            networkAvailability = AlwaysAvailableNetwork,
            backendBaseUrl = server.url("/").toString(),
            retryPolicy = RetryPolicy(maxElapsedMs = 5_000L, baseDelayMs = 1L, maxDelayMs = 1L),
            operationPollIntervalMs = 1L,
        )
    }

    private fun tempAudio(): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File.createTempFile("demeter-test-", ".wav", context.cacheDir).apply {
            writeBytes(ByteArray(128) { index -> index.toByte() })
        }
    }
}
