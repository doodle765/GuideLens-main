package com.guidelens.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Executes the pure, unit-tested decisions of [AlertPolicy] on Android:
 * speech, vibration and the sonar beep channel.
 *
 * Fed once per camera frame via [onFrame]. The policy's tracking rules mean
 * static or receding obstacles no longer get a running commentary, and the
 * user hears "Path clear" when something close disappears.
 */
class AlertCenter(
    private val speaker: Speaker,
    private val prefs: Prefs,
    private val vibrator: Vibrator?
) {
    private val policy = AlertPolicy()

    var namedDangerUntil: Long = 0
        private set

    @Volatile private var muted = false
    fun setMuted(m: Boolean) { muted = m }

    /** Forget all tracked obstacles (camera covered / pocketed / restarted). */
    fun reset() = policy.reset()

    fun onFrame(dets: List<Detector.Detection>, zone: Segmenter.Zone?, sonar: Sonar?) {
        val now = System.currentTimeMillis()
        policy.sensitivity = prefs.sensitivity
        policy.verbosity = prefs.verbosity

        var nearest: Float? = null
        var pan = 0f
        for (d in dets) {
            val dist = d.dist ?: continue
            if (nearest == null || dist < nearest) { nearest = dist; pan = panOf(d.side) }
            for (say in policy.evaluate(d.label, d.side, dist, now)) deliver(say)
        }
        if (zone != null && now > namedDangerUntil) {
            if (nearest == null || zone.dist < nearest) { nearest = zone.dist; pan = 0f }
            for (say in policy.evaluate("obstacle", "ahead", zone.dist, now)) deliver(say)
        }
        for (say in policy.tick(now)) deliver(say)

        sonar?.let { s ->
            s.urgency = if (muted || !prefs.audio) 0f
                        else nearest?.let { policy.urgency(it) } ?: 0f
            s.pan = pan
        }
    }

    private fun deliver(ev: AlertPolicy.Say) {
        if (muted) return
        speaker.speak(ev.text, priority = ev.priority)
        if (ev.priority) namedDangerUntil = System.currentTimeMillis() + 3000
        ev.buzz?.let { buzz(it) }
    }

    private fun buzz(pattern: LongArray) {
        if (!prefs.haptics || vibrator == null) return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern.sum())
            }
        } catch (e: Exception) { }
    }

    companion object {
        internal fun panOf(side: String): Float = when {
            side.startsWith("on your left") -> -0.8f
            side.startsWith("on your right") -> 0.8f
            else -> 0f
        }

        fun vibratorOf(context: Context): Vibrator? =
            try {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } catch (e: Exception) { null }
    }
}
