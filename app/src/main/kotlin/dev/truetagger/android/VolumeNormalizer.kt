package dev.truetagger.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Playback-only volume normalization.
 *
 * It decodes a short portion of the track to PCM, estimates its RMS loudness,
 * and returns the gain needed to bring it to a consistent target loudness. The
 * playback service then applies that gain at roughly 80% output level. The
 * source file and the Android master/media volume are never modified.
 */
object VolumeNormalizer {
    private const val PREFS = "truetagger_volume_normalization"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_OUTPUT_LEVEL = "output_level"
    private const val TARGET_DBFS = -16.0
    private const val MIN_GAIN_DB = -10.0
    private const val MAX_GAIN_DB = 10.0
    private const val ANALYSIS_MICROS = 3_000_000L
    private const val MIN_SAMPLES = 8_000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TruePlayer-VolumeNormalizer").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getOutputLevel(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_OUTPUT_LEVEL, 0.80f)
            .coerceIn(0.50f, 1.00f)

    fun setOutputLevel(context: Context, level: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_OUTPUT_LEVEL, level.coerceIn(0.50f, 1.00f)).apply()
    }

    fun analyzeGain(context: Context, uri: Uri): Float =
        runCatching { calculateGainDb(context.applicationContext, uri).toFloat() }.getOrDefault(0f)

    fun analyzeGainAsync(context: Context, uri: Uri, onResult: (Float) -> Unit) {
        executor.execute {
            val result = analyzeGain(context, uri)
            mainHandler.post { onResult(result) }
        }
    }

    private fun calculateGainDb(context: Context, uri: Uri): Double {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, emptyMap())
            var audioIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioIndex = i
                    break
                }
            }
            if (audioIndex < 0) return 0.0

            extractor.selectTrack(audioIndex)
            val inputFormat = extractor.getTrackFormat(audioIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return 0.0
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var startPts = Long.MIN_VALUE
            var sumSquares = 0.0
            var sampleCount = 0L
            var lastOutputPts = 0L

            while (!outputDone && sampleCount < 50_000_000L) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                        if (input == null) {
                            inputDone = true
                        } else {
                            val size = extractor.readSampleData(input, 0)
                            val pts = extractor.sampleTime
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                if (startPts == Long.MIN_VALUE) startPts = pts.coerceAtLeast(0L)
                                codec.queueInputBuffer(inputIndex, 0, size, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            if (startPts == Long.MIN_VALUE) startPts = info.presentationTimeUs.coerceAtLeast(0L)
                            lastOutputPts = info.presentationTimeUs
                            accumulatePcm(buffer, info.offset, info.size, codec.outputFormat) { value ->
                                sumSquares += value.toDouble() * value.toDouble()
                                sampleCount++
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (lastOutputPts - startPts >= ANALYSIS_MICROS ||
                            (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }

                if (startPts != Long.MIN_VALUE && lastOutputPts - startPts >= ANALYSIS_MICROS) {
                    outputDone = true
                }
            }

            if (sampleCount < MIN_SAMPLES) return 0.0
            val rms = sqrt(sumSquares / sampleCount.toDouble()).coerceAtLeast(1e-6)
            val rmsDb = 20.0 * log10(rms)
            (TARGET_DBFS - rmsDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun accumulatePcm(
        source: ByteBuffer,
        offset: Int,
        size: Int,
        format: MediaFormat,
        consume: (Float) -> Unit,
    ) {
        val buffer = source.duplicate().order(ByteOrder.nativeOrder())
        buffer.position(offset.coerceAtLeast(0))
        buffer.limit((offset + size).coerceAtMost(source.capacity()))
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            android.media.AudioFormat.ENCODING_PCM_16BIT
        }

        when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                while (buffer.remaining() >= 4) consume(buffer.float)
            }
            else -> {
                while (buffer.remaining() >= 2) consume(buffer.short / 32768f)
            }
        }
    }
}
