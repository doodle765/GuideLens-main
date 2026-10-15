package com.guidelens.app

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * "Sonar" earcon channel: short beeps whose rate and pitch rise as the nearest
 * obstacle approaches, stereo-panned toward its side. Runs on its own thread,
 * generated in-memory (no audio assets), and bypasses TTS entirely so it can
 * never get stuck behind the speech queue. Emits a soft heartbeat every 10 s
 * so the user knows the app is alive.
 */
class Sonar {

    /** 0..1 — 0 = silent (heartbeat only). Set every camera frame by AlertCenter. */
    @Volatile var urgency = 0f
    /** -1 (full left) .. +1 (full right). */
    @Volatile var pan = 0f
    @Volatile var muted = false

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        running = true
        thread = Thread { loop() }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { thread?.join(300) } catch (e: Exception) { }
        thread = null
    }

    /** Immediately quiet the obstacle beeps (camera covered / obstacle gone). */
    fun silence() { urgency = 0f }

    private fun loop() {
        var lastBeat = 0L
        while (running) {
            val u = urgency
            if (!muted && u > 0.03f) {
                beep(freqHz = 620f + 520f * u, durMs = 70, volume = 0.45f, pan = pan)
                sleepMs((1000f - 750f * u).toLong().coerceAtLeast(120L))
            } else {
                val now = System.currentTimeMillis()
                if (!muted && now - lastBeat > 10_000L) {
                    lastBeat = now
                    beep(520f, 40, 0.10f, 0f)
                }
                sleepMs(200)
            }
        }
    }

    private fun sleepMs(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { }
    }

    @Suppress("DEPRECATION")
    private fun beep(freqHz: Float, durMs: Int, volume: Float, pan: Float) {
        try {
            val sr = 22050
            val n = sr * durMs / 1000
            val buf = ByteArray(4 * n)                     // stereo, 16-bit
            val lg0 = cos((pan + 1f) * (PI / 4f)).toFloat()  // constant-power pan
            val rg0 = sin((pan + 1f) * (PI / 4f)).toFloat()
            for (i in 0 until n) {
                val t = i.toFloat() / sr
                val env = min(1f, i.toFloat() / (sr * 0.01f)) *
                        min(1f, (n - i).toFloat() / (n * 0.35f))
                val s = (sin(2.0 * PI * freqHz * t) * 32767.0 * volume * env).toInt()
                val l = (s * lg0).toInt().toShort()
                val r = (s * rg0).toInt().toShort()
                buf[4 * i] = (l.toInt() and 0xFF).toByte()
                buf[4 * i + 1] = (l.toInt() shr 8 and 0xFF).toByte()
                buf[4 * i + 2] = (r.toInt() and 0xFF).toByte()
                buf[4 * i + 3] = (r.toInt() shr 8 and 0xFF).toByte()
            }
            val at = AudioTrack(
                AudioManager.STREAM_NOTIFICATION, sr,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                buf.size, AudioTrack.MODE_STATIC
            )
            at.write(buf, 0, buf.size)
            at.play()
            sleepMs(durMs + 40L)
            try { at.stop() } catch (e: Exception) { }
            at.release()
        } catch (e: Exception) { }
    }
}
