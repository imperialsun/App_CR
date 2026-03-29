package com.demeter.speech.core

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal object AudioImportConverter {
    suspend fun convertToWav(
        context: Context,
        sourceUri: Uri,
        outputDir: File,
        targetSampleRate: Int = SAMPLE_RATE_HZ,
    ): File = withContext(Dispatchers.IO) {
        require(targetSampleRate > 0) { "Target sample rate must be positive" }
        ensureDirectory(outputDir)

        val outputFile = File(outputDir, "import-${System.currentTimeMillis()}.wav")
        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor().apply {
                setDataSource(context, sourceUri, null)
            }
            val trackIndex = selectAudioTrack(extractor)
                ?: throw IllegalStateException("Aucune piste audio n'a ete trouvee dans ce fichier")
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)?.trim().orEmpty()
            if (mime.isBlank()) {
                throw IllegalStateException("Format audio non supporte")
            }

            val sourceSampleRate = format.safeInt(MediaFormat.KEY_SAMPLE_RATE, targetSampleRate)
            val sourceChannelCount = format.safeInt(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            val sourcePcmEncoding = format.safePcmEncoding(AudioFormat.ENCODING_PCM_16BIT)

            WavWriter(outputFile, targetSampleRate, 1, 16).use { writer ->
                val resampler = StreamingMonoResampler(
                    sourceSampleRate = sourceSampleRate,
                    targetSampleRate = targetSampleRate,
                    writer = writer,
                )
                if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                    decodeRawPcm(extractor, sourceChannelCount, sourcePcmEncoding, resampler)
                } else {
                    decodeCompressedAudio(
                        extractor = extractor,
                        sourceFormat = format,
                        mime = mime,
                        resampler = resampler,
                    )
                }
                resampler.finish()
            }

            outputFile
        } catch (throwable: Throwable) {
            runCatching { outputFile.delete() }
            throw throwable
        } finally {
            runCatching { extractor?.release() }
        }
    }

    private fun decodeRawPcm(
        extractor: MediaExtractor,
        channelCount: Int,
        pcmEncoding: Int,
        resampler: StreamingMonoResampler,
    ) {
        val buffer = ByteBuffer.allocate(RAW_BUFFER_BYTES)
        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize <= 0) {
                break
            }
            buffer.position(0)
            buffer.limit(sampleSize)
            resampler.consume(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), channelCount, pcmEncoding)
            if (!extractor.advance()) {
                break
            }
        }
    }

    private fun decodeCompressedAudio(
        extractor: MediaExtractor,
        sourceFormat: MediaFormat,
        mime: String,
        resampler: StreamingMonoResampler,
    ) {
        val codec = MediaCodec.createDecoderByType(mime)
        var inputDone = false
        var outputDone = false
        var outputChannelCount = sourceFormat.safeInt(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
        var outputPcmEncoding = sourceFormat.safePcmEncoding(AudioFormat.ENCODING_PCM_16BIT)

        try {
            codec.configure(sourceFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Impossible de lire un buffer d'entree audio")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        outputChannelCount = outputFormat.safeInt(MediaFormat.KEY_CHANNEL_COUNT, outputChannelCount).coerceAtLeast(1)
                        outputPcmEncoding = outputFormat.safePcmEncoding(outputPcmEncoding)
                    }
                    else -> {
                        if (outputIndex < 0) {
                            continue
                        }
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                            ?: throw IllegalStateException("Impossible de lire un buffer de sortie audio")
                        if (bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            resampler.consume(
                                outputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN),
                                outputChannelCount,
                                outputPcmEncoding,
                            )
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME)?.trim().orEmpty()
            if (mime.startsWith("audio/")) {
                return index
            }
        }
        return null
    }

    private fun ensureDirectory(directory: File) {
        if (directory.exists()) {
            return
        }
        if (!directory.mkdirs() && !directory.exists()) {
            throw IllegalStateException("Impossible de creer le dossier temporaire audio")
        }
    }

    private fun MediaFormat.safeInt(key: String, defaultValue: Int): Int {
        return if (containsKey(key)) getInteger(key) else defaultValue
    }

    private fun MediaFormat.safePcmEncoding(defaultValue: Int): Int {
        return if (containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            defaultValue
        }
    }

    private class StreamingMonoResampler(
        private val sourceSampleRate: Int,
        private val targetSampleRate: Int,
        private val writer: WavWriter,
    ) {
        private val sourceSamples = ShortBufferBuilder()
        private val outputBuffer = ShortArray(4096)
        private var outputBufferSize = 0
        private var totalSourceFrames = 0L
        private var nextOutputIndex = 0L
        private var bufferStartFrameIndex = 0L

        fun consume(buffer: ByteBuffer, channelCount: Int, pcmEncoding: Int) {
            val monoSamples = when (pcmEncoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> downmixFloat(buffer, channelCount)
                AudioFormat.ENCODING_PCM_16BIT -> downmixPcm16(buffer, channelCount)
                else -> throw IllegalStateException("Format PCM non supporte pour l'import audio")
            }
            if (monoSamples.isEmpty()) {
                return
            }
            sourceSamples.append(monoSamples)
            totalSourceFrames += monoSamples.size.toLong()
            drainAvailableSamples(forceEnd = false)
        }

        fun finish() {
            if (totalSourceFrames <= 0L || targetSampleRate <= 0 || sourceSampleRate <= 0) {
                flushOutput()
                return
            }
            val desiredOutputCount = ((totalSourceFrames.toDouble() * targetSampleRate.toDouble()) / sourceSampleRate.toDouble())
                .roundToLong()
                .coerceAtLeast(0L)
            while (nextOutputIndex < desiredOutputCount) {
                emitSample(forceEnd = true)
            }
            flushOutput()
        }

        private fun drainAvailableSamples(forceEnd: Boolean) {
            while (true) {
                val position = nextOutputIndex.toDouble() * sourceSampleRate.toDouble() / targetSampleRate.toDouble()
                val floorIndex = position.toLong()
                if (!forceEnd && floorIndex + 1 >= totalSourceFrames) {
                    break
                }
                if (forceEnd && totalSourceFrames == 0L) {
                    break
                }
                if (!emitSample(forceEnd)) {
                    break
                }
            }
        }

        private fun emitSample(forceEnd: Boolean): Boolean {
            if (sourceSamples.size == 0) {
                return false
            }

            val position = nextOutputIndex.toDouble() * sourceSampleRate.toDouble() / targetSampleRate.toDouble()
            val floorIndex = position.toLong()
            val localIndex = (floorIndex - bufferStartFrameIndex).toInt()
            if (localIndex < 0) {
                throw IllegalStateException("Resampler audio buffer underrun")
            }

            val sample0 = when {
                localIndex < sourceSamples.size -> sourceSamples[localIndex]
                forceEnd -> sourceSamples.last()
                else -> return false
            }.toInt()
            val sample1 = when {
                localIndex + 1 < sourceSamples.size -> sourceSamples[localIndex + 1].toInt()
                forceEnd -> sample0
                else -> return false
            }

            val fraction = position - floorIndex.toDouble()
            val interpolated = (sample0 + (sample1 - sample0) * fraction)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            outputBuffer[outputBufferSize] = interpolated
            outputBufferSize += 1
            if (outputBufferSize == outputBuffer.size) {
                flushOutput()
            }

            nextOutputIndex += 1
            trimBufferedSamples(floorIndex)
            return true
        }

        private fun trimBufferedSamples(floorIndex: Long) {
            val desiredStart = maxOf(0L, floorIndex - 1L)
            if (desiredStart <= bufferStartFrameIndex) {
                return
            }
            val dropCount = (desiredStart - bufferStartFrameIndex).toInt().coerceAtMost(sourceSamples.size)
            if (dropCount <= 0) {
                return
            }
            sourceSamples.dropPrefix(dropCount)
            bufferStartFrameIndex += dropCount
        }

        private fun flushOutput() {
            if (outputBufferSize <= 0) {
                return
            }
            writer.write(outputBuffer, outputBufferSize)
            outputBufferSize = 0
        }

        private fun downmixPcm16(buffer: ByteBuffer, channelCount: Int): ShortArray {
            val pcmBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val frameCount = pcmBuffer.remaining() / channelCount
            if (frameCount <= 0) {
                return ShortArray(0)
            }
            val mono = ShortArray(frameCount)
            for (frameIndex in 0 until frameCount) {
                var sum = 0
                for (channelIndex in 0 until channelCount) {
                    sum += pcmBuffer.get().toInt()
                }
                mono[frameIndex] = (sum / channelCount).toShort()
            }
            return mono
        }

        private fun downmixFloat(buffer: ByteBuffer, channelCount: Int): ShortArray {
            val floatBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val frameCount = floatBuffer.remaining() / channelCount
            if (frameCount <= 0) {
                return ShortArray(0)
            }
            val mono = ShortArray(frameCount)
            for (frameIndex in 0 until frameCount) {
                var sum = 0f
                for (channelIndex in 0 until channelCount) {
                    sum += floatBuffer.get()
                }
                val average = sum / channelCount
                mono[frameIndex] = (average * Short.MAX_VALUE.toFloat())
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            return mono
        }
    }

    private class ShortBufferBuilder(initialCapacity: Int = 4096) {
        private var data = ShortArray(initialCapacity)
        private var start = 0
        var size: Int = 0
            private set

        operator fun get(index: Int): Short {
            return data[start + index]
        }

        fun last(): Short {
            if (size <= 0) {
                throw IllegalStateException("Buffer vide")
            }
            return data[start + size - 1]
        }

        fun append(samples: ShortArray) {
            ensureCapacity(size + samples.size)
            System.arraycopy(samples, 0, data, start + size, samples.size)
            size += samples.size
        }

        fun dropPrefix(count: Int) {
            if (count <= 0 || size <= 0) {
                return
            }
            val trimmed = count.coerceAtMost(size)
            start += trimmed
            size -= trimmed
            if (size == 0) {
                start = 0
            } else if (start > data.size / 2) {
                compact()
            }
        }

        private fun ensureCapacity(requiredSize: Int) {
            if (start + requiredSize <= data.size) {
                return
            }
            compact()
            if (requiredSize <= data.size) {
                return
            }
            data = data.copyOf(max(data.size * 2, requiredSize))
        }

        private fun compact() {
            if (start == 0) {
                return
            }
            if (size > 0) {
                System.arraycopy(data, start, data, 0, size)
            }
            start = 0
        }
    }

    private const val RAW_BUFFER_BYTES = 256 * 1024
    private const val CODEC_TIMEOUT_US = 10_000L
}
