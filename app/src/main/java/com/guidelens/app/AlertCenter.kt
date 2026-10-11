package com.guidelens.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Distance-tiered spoken + vibration alerts, mirroring the web app.
 *
 * All timing / threshold / throttling decisions live in the pure,
 * unit-tested [AlertPolicy]; this class is only the Android shell that
 * executes them (TextToSpeech + vibrator). Settings are re-read on every
 * alert so live changes apply immediately, as before.
 */
class AlertCenter(
    private val speaker: Speaker,
    private val prefs: Prefs,
    private val vibrator: Vibrator?
) {
    private val policy = AlertPolicy()

    var namedDangerUntil: Long = 0
        private set

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

    fun alert(name: String, side: String, dist: Float) {
        policy.sensitivity = prefs.sensitivity
        policy.verbosity = prefs.verbosity
        val decision = policy.evaluate(name, side, dist, System.currentTimeMillis())
        val text = decision.phrase ?: return

        if (decision.tier == AlertPolicy.Tier.STOP) {
            speaker.speak(text, priority = true)
            namedDangerUntil = System.currentTimeMillis() + 3000
        } else {
            speaker.speak(text)
        }
        decision.buzz?.let { buzz(it) }
    }

    companion object {
        fun vibratorOf(context: Context): Vibrator? =
            try {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } catch (e: Exception) { null }
    }
}
