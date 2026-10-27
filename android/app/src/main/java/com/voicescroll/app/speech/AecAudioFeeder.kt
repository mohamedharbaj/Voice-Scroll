package com.voicescroll.app.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.ArrayDeque

/**
 * يسجّل من الميكروفون بمصدر VOICE_COMMUNICATION مع إلغاء الصدى (AEC) وتخفيض الضجيج (NS)، ثم يمرّر الصوت
 * للـ SpeechRecognizer عبر EXTRA_AUDIO_SOURCE (أندرويد 13+).
 *
 * الهدف: صوت الفيديو (TikTok / YouTube ...) اللي كيخرج من سماعة الهاتف كيتحيّد من الصوت اللي كيوصل للتعرف،
 * وبالتالي أمر المستخدم (دوز / هبط / next ...) كيبقى واضح حتى والفيديو خدام.
 *
 * ميزة إضافية: buffer ديال ~0.8 ثانية، باش الكلام اللي كيتقال بين جلستين (وقت إعادة تشغيل الـ recognizer)
 * ما يضيعش.
 */
class AecAudioFeeder {

    companion object {
        private const val TAG = "AecAudioFeeder"
        const val SAMPLE_RATE = 16000
        private const val CHUNK_BYTES = 640 // 20ms من PCM16 mono 16kHz
        private const val PREROLL_CHUNKS = 40 // ~800ms
    }

    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    private val lock = Any()
    private val ring = ArrayDeque<ByteArray>()
    private var sink: OutputStream? = null

    /** كيرجع true فقط إلا كان AEC متوفر وتفعّل (بلا AEC ما كاين حتى فايدة، كنخليو الميكروفون العادي). */
    fun start(): Boolean {
        if (running) return true
        try {
            if (!AcousticEchoCanceler.isAvailable()) {
                Log.i(TAG, "AcousticEchoCanceler not available on this device")
                return false
            }
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return false

            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, CHUNK_BYTES * 8)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }

            val echo = AcousticEchoCanceler.create(rec.audioSessionId)
            if (echo == null) {
                rec.release()
                return false
            }
            echo.setEnabled(true)
            if (!echo.getEnabled()) {
                echo.release()
                rec.release()
                return false
            }
            aec = echo

            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(rec.audioSessionId)?.also { it.setEnabled(true) }
            }

            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                releaseAll(rec)
                return false
            }

            record = rec
            running = true
            val t = Thread({ readLoop(rec) }, "AecAudioFeeder")
            t.isDaemon = true
            t.start()
            thread = t
            Log.i(TAG, "AEC capture started (NS=${noiseSuppressor != null})")
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "start failed: ${e.message}")
            stop()
            return false
        }
    }

    private fun readLoop(rec: AudioRecord) {
        val buf = ByteArray(CHUNK_BYTES)
        while (running) {
            val n = try {
                rec.read(buf, 0, CHUNK_BYTES)
            } catch (e: Throwable) {
                -1
            }
            if (n < 0) break
            if (n == 0) continue
            val chunk = buf.copyOf(n)
            val out: OutputStream? = synchronized(lock) {
                ring.addLast(chunk)
                while (ring.size > PREROLL_CHUNKS) ring.removeFirst()
                sink
            }
            if (out != null) {
                try {
                    out.write(chunk)
                } catch (e: IOException) {
                    synchronized(lock) { if (sink === out) sink = null }
                    try { out.close() } catch (_: IOException) {}
                }
            }
        }
    }

    /**
     * كيفتح "جلسة" جديدة: pipe كيقراه الـ recognizer. كيبدا بآخر الصوت اللي تسجل بعد نهاية الجلسة السابقة.
     * الـ ParcelFileDescriptor المرجع خاصو يتسد من الطرف ديالنا ملي تسالي الجلسة.
     */
    fun openSession(): ParcelFileDescriptor? {
        if (!running) return null
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val os = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            synchronized(lock) {
                closeSinkLocked()
                for (c in ring) os.write(c)
                sink = os
            }
            readSide
        } catch (e: Throwable) {
            Log.w(TAG, "openSession failed: ${e.message}")
            null
        }
    }

    /** كيسد الجلسة الحالية وكيمحي الصوت القديم (باش الأمر اللي تنفذ ما يتعاودش). */
    fun closeSession() {
        synchronized(lock) {
            closeSinkLocked()
            ring.clear()
        }
    }

    private fun closeSinkLocked() {
        try { sink?.close() } catch (_: IOException) {}
        sink = null
    }

    fun stop() {
        running = false
        closeSession()
        val rec = record
        record = null
        try { thread?.join(300) } catch (_: InterruptedException) {}
        thread = null
        releaseAll(rec)
    }

    private fun releaseAll(rec: AudioRecord?) {
        try { noiseSuppressor?.release() } catch (_: Throwable) {}
        try { aec?.release() } catch (_: Throwable) {}
        noiseSuppressor = null
        aec = null
        try { rec?.stop() } catch (_: Throwable) {}
        try { rec?.release() } catch (_: Throwable) {}
    }
}
