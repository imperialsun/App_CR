package com.demeter.speech.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.demeter.speech.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

internal const val SAMPLE_RATE_HZ = 16_000

object RecordingRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(MeetingRecordingState())
    val state = _state

    @Volatile
    var latestRecordingFile: File? = null
        private set

    fun setRecording(path: String?) {
        _state.value = _state.value.copy(
            isRecording = true,
            isPaused = false,
            recordingPath = path,
        )
    }

    fun setPaused(paused: Boolean) {
        _state.value = _state.value.copy(
            isRecording = paused || _state.value.isRecording,
            isPaused = paused,
        )
    }

    fun updateDuration(elapsedMs: Long) {
        _state.value = _state.value.copy(elapsedMs = elapsedMs)
    }

    fun finishRecording(file: File?) {
        latestRecordingFile = file
        _state.value = _state.value.copy(
            isRecording = false,
            isPaused = false,
            recordingPath = file?.absolutePath,
        )
    }

    fun reset() {
        latestRecordingFile = null
        _state.value = MeetingRecordingState()
    }
}

class MeetingRecorderService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: AudioRecord? = null
    private var writer: WavWriter? = null
    private var recordingJob: Job? = null
    private var startedAtMs: Long = 0L
    private var pausedAtMs: Long = 0L
    private var pausedTotalMs: Long = 0L
    private var outputFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startRecording() {
        if (recordingJob != null) {
            return
        }
        val file = createRecordingFile()
        outputFile = file
        startedAtMs = System.currentTimeMillis()
        pausedAtMs = 0L
        pausedTotalMs = 0L
        RecordingRepository.setRecording(file.absolutePath)
        startForeground(NOTIFICATION_ID, buildRecordingNotification("Enregistrement en cours"))

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = max(minBufferSize, SAMPLE_RATE_HZ / 2)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        recorder = audioRecord
        writer = runCatching { WavWriter(file, SAMPLE_RATE_HZ, 1, 16) }.getOrNull()
        if (writer == null) {
            stopSelf()
            return
        }

        audioRecord.startRecording()
        recordingJob = serviceScope.launch {
            val buffer = ShortArray(bufferSize / 2)
            while (isActive) {
                if (RecordingRepository.state.value.isPaused) {
                    delay(100)
                    continue
                }
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    continue
                }
                writer?.write(buffer, read)
                val elapsed = System.currentTimeMillis() - startedAtMs - pausedTotalMs
                RecordingRepository.updateDuration(elapsed)
            }
        }
    }

    private fun pauseRecording() {
        if (recorder == null || recordingJob == null) {
            return
        }
        if (RecordingRepository.state.value.isPaused) {
            return
        }
        pausedAtMs = System.currentTimeMillis()
        runCatching { recorder?.stop() }
        RecordingRepository.setPaused(true)
        updateNotification("Enregistrement en pause")
    }

    private fun resumeRecording() {
        if (recorder == null || recordingJob == null) {
            return
        }
        if (!RecordingRepository.state.value.isPaused) {
            return
        }
        val now = System.currentTimeMillis()
        if (pausedAtMs > 0L) {
            pausedTotalMs += max(0L, now - pausedAtMs)
        }
        pausedAtMs = 0L
        runCatching { recorder?.startRecording() }
        RecordingRepository.setPaused(false)
        updateNotification("Enregistrement en cours")
    }

    private fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null

        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null

        runCatching { writer?.close() }
        writer = null

        val file = outputFile
        outputFile = null
        RecordingRepository.finishRecording(file)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createRecordingFile(): File {
        val dir = File(cacheDir, "recordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "meeting-${System.currentTimeMillis()}.wav")
    }

    private fun buildRecordingNotification(content: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildRecordingNotification(content))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.demeter.speech.action.START_RECORDING"
        const val ACTION_PAUSE = "com.demeter.speech.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.demeter.speech.action.RESUME_RECORDING"
        const val ACTION_STOP = "com.demeter.speech.action.STOP_RECORDING"
        private const val NOTIFICATION_CHANNEL_ID = "demeter_recording"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, MeetingRecorderService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, MeetingRecorderService::class.java).setAction(ACTION_PAUSE)
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, MeetingRecorderService::class.java).setAction(ACTION_RESUME)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeetingRecorderService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

class WavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitsPerSample: Int,
) : AutoCloseable {
    private val stream = FileOutputStream(file)
    private var dataSize: Long = 0

    init {
        stream.write(createWavHeader(0))
    }

    @Synchronized
    fun write(samples: ShortArray, length: Int) {
        val byteBuffer = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until length) {
            byteBuffer.putShort(samples[index])
        }
        val bytes = byteBuffer.array()
        stream.write(bytes)
        dataSize += bytes.size.toLong()
    }

    override fun close() {
        stream.flush()
        stream.close()
        patchHeader(file, dataSize, sampleRate, channels, bitsPerSample)
    }

    private fun createWavHeader(dataLength: Long): ByteArray {
        return buildWavHeader(dataLength, sampleRate, channels, bitsPerSample)
    }
}

object WavChunker {
    fun split(input: File, chunkDurationSec: Int, overlapSec: Int, sampleRate: Int = 16_000): List<File> {
        val normalizedDuration = max(1, chunkDurationSec)
        val normalizedOverlap = overlapSec.coerceIn(0, normalizedDuration - 1)
        val stepSec = max(1, normalizedDuration - normalizedOverlap)
        val data = readWavPcmData(input)
        if (data.isEmpty()) {
            return emptyList()
        }

        val bytesPerSecond = sampleRate * 2
        val totalSeconds = (data.size / bytesPerSecond).coerceAtLeast(1)
        val chunks = mutableListOf<File>()
        var index = 0
        var startSec = 0
        while (startSec < totalSeconds) {
            val endSec = minOf(totalSeconds, startSec + normalizedDuration)
            val startByte = (startSec * bytesPerSecond).coerceAtMost(data.size)
            val endByte = (endSec * bytesPerSecond).coerceAtMost(data.size)
            val chunkData = data.copyOfRange(startByte, endByte)
            val chunkFile = File(input.parentFile ?: input, "${input.nameWithoutExtension}-chunk-$index.wav")
            writeWavFile(chunkFile, chunkData, sampleRate, 1, 16)
            chunks.add(chunkFile)
            index += 1
            if (endSec >= totalSeconds) {
                break
            }
            startSec += stepSec
        }
        return chunks
    }

    fun estimateDurationSeconds(input: File, sampleRate: Int = 16_000): Int {
        val data = readWavPcmData(input)
        if (data.isEmpty()) {
            return 0
        }
        return max(1, data.size / (sampleRate * 2))
    }
}

object VadChunker {
    fun split(
        input: File,
        sampleRate: Int = 16_000,
        frameMs: Int = 30,
        speechThreshold: Double = 0.015,
        maxChunkDurationSec: Int = 30,
        overlapSec: Int = 0,
    ): List<File> {
        val pcmBytes = readWavPcmData(input)
        if (pcmBytes.isEmpty()) {
            return emptyList()
        }

        val samples = ShortArray(pcmBytes.size / 2)
        val byteBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (index in samples.indices) {
            samples[index] = byteBuffer.short
        }

        val frameSamples = max(1, sampleRate * frameMs / 1000)
        val windowCount = (samples.size + frameSamples - 1) / frameSamples
        val speechWindows = BooleanArray(windowCount)
        for (windowIndex in 0 until windowCount) {
            val start = windowIndex * frameSamples
            val end = minOf(samples.size, start + frameSamples)
            var sum = 0.0
            var count = 0
            for (sampleIndex in start until end) {
                val value = samples[sampleIndex].toDouble() / Short.MAX_VALUE
                sum += value * value
                count += 1
            }
            val rms = if (count > 0) kotlin.math.sqrt(sum / count) else 0.0
            speechWindows[windowIndex] = rms >= speechThreshold
        }

        val segments = mutableListOf<IntRange>()
        var currentStart = -1
        val minSpeechWindows = max(1, 300 / frameMs)
        val minSilenceWindows = max(1, 250 / frameMs)
        var silenceCount = 0

        for (windowIndex in speechWindows.indices) {
            if (speechWindows[windowIndex]) {
                if (currentStart < 0) {
                    currentStart = windowIndex
                }
                silenceCount = 0
            } else if (currentStart >= 0) {
                silenceCount += 1
                if (silenceCount >= minSilenceWindows) {
                    val endWindow = windowIndex - silenceCount
                    if (endWindow >= currentStart && endWindow - currentStart + 1 >= minSpeechWindows) {
                        segments.add(currentStart..endWindow)
                    }
                    currentStart = -1
                    silenceCount = 0
                }
            }
        }
        if (currentStart >= 0) {
            val endWindow = speechWindows.lastIndex
            if (endWindow >= currentStart && endWindow - currentStart + 1 >= minSpeechWindows) {
                segments.add(currentStart..endWindow)
            }
        }

        if (segments.isEmpty()) {
            return WavChunker.split(input, maxChunkDurationSec, overlapSec, sampleRate)
        }

        val maxChunkSamples = max(1, sampleRate * maxChunkDurationSec)
        val overlapSamples = max(0, sampleRate * overlapSec)
        val output = mutableListOf<File>()
        var chunkIndex = 0
        for (segment in segments) {
            val startSample = segment.first * frameSamples
            val endSample = minOf(samples.size, (segment.last + 1) * frameSamples)
            var cursor = startSample
            while (cursor < endSample) {
                val chunkEnd = minOf(endSample, cursor + maxChunkSamples)
                val chunkSamples = samples.copyOfRange(cursor, chunkEnd)
                if (chunkSamples.isNotEmpty()) {
                    val chunkFile = File(
                        input.parentFile ?: input,
                        "${input.nameWithoutExtension}-vad-$chunkIndex.wav"
                    )
                    writeWavFile(chunkFile, shortsToPcmBytes(chunkSamples), sampleRate, 1, 16)
                    output.add(chunkFile)
                    chunkIndex += 1
                }
                if (chunkEnd >= endSample) {
                    break
                }
                cursor = max(cursor + 1, chunkEnd - overlapSamples)
            }
        }
        return output
    }

    private fun shortsToPcmBytes(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            buffer.putShort(sample)
        }
        return buffer.array()
    }
}

data class ChunkingConfig(
    val requestedDurationSec: Int,
    val effectiveDurationSec: Int,
    val effectiveOverlapSec: Int,
)

fun resolveDemeterChunking(requestedDurationSec: Int, requestedOverlapSec: Int): ChunkingConfig {
    val requestedDuration = if (requestedDurationSec > 0) requestedDurationSec else DEFAULT_DEMETER_CHUNK_DURATION_SEC
    val effectiveDurationSec = requestedDuration
    val effectiveOverlapSec = requestedOverlapSec.coerceAtLeast(0).coerceAtMost(maxOf(0, effectiveDurationSec - 1))
    return ChunkingConfig(
        requestedDurationSec = requestedDuration,
        effectiveDurationSec = effectiveDurationSec,
        effectiveOverlapSec = effectiveOverlapSec,
    )
}

interface TranscriptionEngine {
    suspend fun transcribe(
        file: File,
        modelId: String = "",
        backendClient: BackendApiClient,
        chunkDurationSec: Int = 0,
        overlapSec: Int = 0,
        onChunkTranscribed: (String) -> Unit = {},
    ): String
}

class LocalWhisperTranscriptionEngine(
    private val bridge: WhisperBridge = WhisperBridge(),
) : TranscriptionEngine {
    fun isBridgeAvailable(): Boolean = bridge.isAvailable()

    override suspend fun transcribe(
        file: File,
        modelId: String,
        backendClient: BackendApiClient,
        chunkDurationSec: Int,
        overlapSec: Int,
        onChunkTranscribed: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        if (!bridge.isAvailable()) {
            val text = bridge.transcribe(file, modelId)
            onChunkTranscribed(text)
            return@withContext text
        }
        val chunks = VadChunker.split(
            file,
            maxChunkDurationSec = if (chunkDurationSec > 0) chunkDurationSec else 30,
            overlapSec = overlapSec,
        )
        if (chunks.isEmpty()) {
            val text = bridge.transcribe(file, modelId)
            onChunkTranscribed(text)
            return@withContext text
        }
        try {
            val texts = mutableListOf<String>()
            chunks.forEach { chunk ->
                val text = bridge.transcribe(chunk, modelId).trim()
                if (text.isNotBlank()) {
                    texts.add(text)
                    onChunkTranscribed(joinChunkedTranscriptDisplay(texts))
                }
            }
            if (texts.isEmpty()) {
                throw IllegalStateException("Le bridge whisper.cpp n'a renvoye aucun texte exploitable")
            } else {
                joinChunkedTranscriptDisplay(texts)
            }
        } finally {
            chunks.forEach { chunk ->
                runCatching { chunk.delete() }
            }
        }
    }
}

class DemeterBackendTranscriptionEngine : TranscriptionEngine {
    override suspend fun transcribe(
        file: File,
        modelId: String,
        backendClient: BackendApiClient,
        chunkDurationSec: Int,
        overlapSec: Int,
        onChunkTranscribed: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val config = resolveDemeterChunking(requestedDurationSec = chunkDurationSec, requestedOverlapSec = overlapSec)
        val chunks = WavChunker.split(file, config.effectiveDurationSec, config.effectiveOverlapSec)
        try {
            if (chunks.isEmpty()) {
                val response = transcribeDemeterWithFallback(
                    backendClient = backendClient,
                    file = file,
                    overlapSec = config.effectiveOverlapSec,
                )
                val text = formatTranscriptForDisplay(response.text, response.segments).ifBlank {
                    "Transcription Demeter Santé vide."
                }
                onChunkTranscribed(text)
                return@withContext text
            }
            val texts = mutableListOf<String>()
            chunks.forEach { chunk ->
                val response = transcribeDemeterWithFallback(
                    backendClient = backendClient,
                    file = chunk,
                    overlapSec = config.effectiveOverlapSec,
                )
                val text = formatTranscriptForDisplay(response.text, response.segments)
                if (text.isNotBlank()) {
                    texts.add(text)
                    onChunkTranscribed(joinChunkedTranscriptDisplay(texts))
                }
            }
            if (texts.isEmpty()) {
                "Transcription Demeter Santé vide."
            } else {
                joinChunkedTranscriptDisplay(texts)
            }
        } finally {
            chunks.forEach { chunk ->
                runCatching { chunk.delete() }
            }
        }
    }

    private suspend fun transcribeDemeterWithFallback(
        backendClient: BackendApiClient,
        file: File,
        overlapSec: Int,
    ): DemeterTranscriptionResponseDto {
        return runCatching {
            backendClient.transcribeDemeterChunk(
                DemeterTranscriptionRequest(
                    file = file,
                    diarize = true,
                )
            )
        }.getOrElse { error ->
            val durationSeconds = WavChunker.estimateDurationSeconds(file)
            val splitDuration = max(1, durationSeconds / 2)
            if (splitDuration <= 1) {
                throw error
            }
            val smallerChunks = WavChunker.split(file, chunkDurationSec = splitDuration, overlapSec = overlapSec)
            if (smallerChunks.size <= 1) {
                throw error
            }
            try {
                val text = buildString {
                    smallerChunks.forEach { smaller ->
                        val response = transcribeDemeterWithFallback(
                            backendClient = backendClient,
                            file = smaller,
                            overlapSec = overlapSec,
                        )
                        if (response.text.isNotBlank()) {
                            if (isNotEmpty()) append('\n')
                            append(response.text)
                        }
                    }
                }
                return DemeterTranscriptionResponseDto(text = text)
            } finally {
                smallerChunks.forEach { chunk ->
                    runCatching { chunk.delete() }
                }
            }
        }
    }
}

class WhisperBridge {
    private val available = runCatching {
        System.loadLibrary("whisperbridge")
        true
    }.getOrDefault(false)

    fun isAvailable(): Boolean = available

    fun transcribe(file: File, modelId: String): String {
        if (!available) {
            throw IllegalStateException("whisper.cpp n'est pas disponible sur cet appareil")
        }
        val result = runCatching { nativeTranscribeChunk(file.absolutePath, modelId) }
            .getOrElse { error ->
                throw IllegalStateException("Echec du bridge whisper.cpp: ${error.message ?: error.javaClass.simpleName}", error)
            }
        return result.trim().ifBlank {
            throw IllegalStateException("Le bridge whisper.cpp n'a renvoye aucun texte")
        }
    }

    private external fun nativeTranscribeChunk(path: String, modelId: String): String
}

private fun buildWavHeader(dataLength: Long, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val totalDataLength = dataLength + 36
    val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray())
    buffer.putInt(totalDataLength.toInt())
    buffer.put("WAVE".toByteArray())
    buffer.put("fmt ".toByteArray())
    buffer.putInt(16)
    buffer.putShort(1)
    buffer.putShort(channels.toShort())
    buffer.putInt(sampleRate)
    buffer.putInt(byteRate)
    buffer.putShort(blockAlign.toShort())
    buffer.putShort(bitsPerSample.toShort())
    buffer.put("data".toByteArray())
    buffer.putInt(dataLength.toInt())
    return buffer.array()
}

private fun patchHeader(file: File, dataLength: Long, sampleRate: Int, channels: Int, bitsPerSample: Int) {
    RandomAccessFile(file, "rw").use { raf ->
        raf.seek(0)
        raf.write(buildWavHeader(dataLength, sampleRate, channels, bitsPerSample))
    }
}

private fun readWavPcmData(file: File): ByteArray {
    if (!file.exists() || file.length() <= 44) {
        return ByteArray(0)
    }
    return file.inputStream().use { stream ->
        val bytes = stream.readBytes()
        if (bytes.size <= 44) ByteArray(0) else bytes.copyOfRange(44, bytes.size)
    }
}

private fun writeWavFile(file: File, pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
    file.outputStream().use { stream ->
        stream.write(buildWavHeader(pcmData.size.toLong(), sampleRate, channels, bitsPerSample))
        stream.write(pcmData)
    }
}
