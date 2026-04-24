package com.zeeshan.androidllmserver.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Simple PCM→WAV audio recorder for the chat composer's voice button.
 *
 * The sample rate and format are fixed at 16 kHz mono 16-bit PCM because
 * that's what Gemma 4's audio conformer expects (see
 * mtmd_get_audio_sample_rate == 16000). Capturing at the native rate skips
 * a resample pass on the inference side and also produces the most compact
 * WAV for the ~30s cap mtmd enforces.
 *
 * start()/stop() are safe to call from the UI thread. Reading happens on a
 * dedicated background thread owned by this class.
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BITS_PER_SAMPLE = 16
        private const val CHANNELS = 1
    }

    private var record: AudioRecord? = null
    private val running = AtomicBoolean(false)
    private val buffers = mutableListOf<ByteArray>()
    private var readerThread: Thread? = null

    val isRecording: Boolean get() = running.get()

    @SuppressLint("MissingPermission") // caller is responsible for RECORD_AUDIO runtime grant
    fun start() {
        if (running.get()) return

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // Double the min buffer so we don't drop samples under UI jank.
        val bufSize = (minBuf * 2).coerceAtLeast(4096)

        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufSize,
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise (state=${r.state})")
            r.release()
            return
        }

        buffers.clear()
        record = r
        running.set(true)
        r.startRecording()

        readerThread = Thread({
            val tmp = ByteArray(bufSize)
            while (running.get()) {
                val n = try { r.read(tmp, 0, tmp.size) } catch (_: Throwable) { -1 }
                if (n > 0) {
                    buffers.add(tmp.copyOf(n))
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read returned $n, stopping reader")
                    break
                }
            }
        }, "audio-recorder").apply { isDaemon = true; start() }
    }

    /**
     * Stop recording and return the captured audio as a self-contained
     * WAV blob (header + PCM). Returns an empty array if nothing was
     * captured or if start() was never called.
     */
    fun stop(): ByteArray {
        if (!running.get() && record == null) return ByteArray(0)
        running.set(false)
        try { readerThread?.join(500) } catch (_: InterruptedException) {}
        readerThread = null

        val r = record
        record = null
        try {
            r?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord.stop threw", t)
        } finally {
            r?.release()
        }

        val totalSize = buffers.sumOf { it.size }
        if (totalSize == 0) return ByteArray(0)

        val pcm = ByteArray(totalSize)
        var offset = 0
        for (chunk in buffers) {
            System.arraycopy(chunk, 0, pcm, offset, chunk.size)
            offset += chunk.size
        }
        buffers.clear()
        return wrapWav(pcm)
    }

    /** Abort without emitting a WAV. Useful if the user cancels. */
    fun cancel() {
        running.set(false)
        try { readerThread?.join(500) } catch (_: InterruptedException) {}
        readerThread = null
        try { record?.stop() } catch (_: Throwable) {}
        record?.release()
        record = null
        buffers.clear()
    }

    /**
     * Wrap a mono 16-bit 16 kHz PCM blob in a 44-byte RIFF/WAVE header so
     * it's a valid .wav file that libmtmd's miniaudio can decode.
     */
    private fun wrapWav(pcm: ByteArray): ByteArray {
        val dataSize = pcm.size
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)           // file size - 8
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                      // PCM fmt chunk size
            putShort(1)                     // PCM format
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        val out = ByteArrayOutputStream(header.size + dataSize)
        out.write(header)
        out.write(pcm)
        return out.toByteArray()
    }
}
