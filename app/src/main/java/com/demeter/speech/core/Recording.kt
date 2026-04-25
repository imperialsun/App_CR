package com.demeter.speech.core

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import com.demeter.speech.R
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface RecordingEvent {
    data class Started(val path: String) : RecordingEvent
    data class Stopped(val path: String) : RecordingEvent
    data class Failed(val message: String) : RecordingEvent
}

object RecordingEvents {
    private val mutable = MutableSharedFlow<RecordingEvent>(replay = 1, extraBufferCapacity = 4)
    val events = mutable.asSharedFlow()

    fun emit(event: RecordingEvent) {
        mutable.tryEmit(event)
    }
}

class MeetingRecorderService : Service() {
    private var recorder: WavRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent.getStringExtra(EXTRA_PATH).orEmpty())
            ACTION_PAUSE -> recorder?.pause()
            ACTION_RESUME -> recorder?.resume()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recorder?.stop()
        recorder = null
        super.onDestroy()
    }

    private fun startRecording(path: String) {
        if (path.isBlank()) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RecordingEvents.emit(RecordingEvent.Failed("Permission micro manquante"))
            stopSelf()
            return
        }
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, recordingNotification())
        val target = File(path)
        recorder?.stop()
        recorder = WavRecorder(target)
        runCatching {
            recorder?.start()
            RecordingEvents.emit(RecordingEvent.Started(path))
        }.onFailure {
            RecordingEvents.emit(RecordingEvent.Failed(it.message ?: "Enregistrement impossible"))
            stopSelf()
        }
    }

    private fun stopRecording() {
        val path = recorder?.file?.absolutePath.orEmpty()
        recorder?.stop()
        recorder = null
        if (path.isNotBlank()) {
            RecordingEvents.emit(RecordingEvent.Stopped(path))
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun recordingNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Demeter Sante")
            .setContentText("Enregistrement en cours")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "demeter_recording"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_START = "com.demeter.speech.RECORD_START"
        private const val ACTION_PAUSE = "com.demeter.speech.RECORD_PAUSE"
        private const val ACTION_RESUME = "com.demeter.speech.RECORD_RESUME"
        private const val ACTION_STOP = "com.demeter.speech.RECORD_STOP"
        private const val EXTRA_PATH = "path"

        fun startIntent(context: Context, path: String): Intent {
            return Intent(context, MeetingRecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PATH, path)
        }

        fun pauseIntent(context: Context): Intent = Intent(context, MeetingRecorderService::class.java).setAction(ACTION_PAUSE)
        fun resumeIntent(context: Context): Intent = Intent(context, MeetingRecorderService::class.java).setAction(ACTION_RESUME)
        fun stopIntent(context: Context): Intent = Intent(context, MeetingRecorderService::class.java).setAction(ACTION_STOP)
    }
}

private class WavRecorder(val file: File) {
    private val running = AtomicBoolean(false)
    @Volatile
    private var paused = false
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var pcmBytes: Long = 0

    @SuppressLint("MissingPermission")
    fun start() {
        file.parentFile?.mkdirs()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE * 2)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize,
        )
        audioRecord = record
        running.set(true)
        worker = thread(name = "demeter-wav-recorder") {
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(0)
                output.write(ByteArray(WAV_HEADER_BYTES))
                val buffer = ByteArray(bufferSize)
                record.startRecording()
                while (running.get()) {
                    if (paused) {
                        Thread.sleep(80)
                        continue
                    }
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        pcmBytes += read
                    }
                }
                runCatching { record.stop() }
                writeWavHeader(output, pcmBytes)
            }
            record.release()
        }
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun stop() {
        running.set(false)
        worker?.join(1500)
        worker = null
        audioRecord = null
    }

    private fun writeWavHeader(output: RandomAccessFile, dataBytes: Long) {
        val totalDataLen = dataBytes + 36
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        output.seek(0)
        output.writeBytes("RIFF")
        output.writeIntLE(totalDataLen.toInt())
        output.writeBytes("WAVE")
        output.writeBytes("fmt ")
        output.writeIntLE(16)
        output.writeShortLE(1)
        output.writeShortLE(CHANNELS)
        output.writeIntLE(SAMPLE_RATE)
        output.writeIntLE(byteRate)
        output.writeShortLE(CHANNELS * BITS_PER_SAMPLE / 8)
        output.writeShortLE(BITS_PER_SAMPLE)
        output.writeBytes("data")
        output.writeIntLE(dataBytes.toInt())
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
        write((value shr 16) and 0xff)
        write((value shr 24) and 0xff)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val WAV_HEADER_BYTES = 44
    }
}
