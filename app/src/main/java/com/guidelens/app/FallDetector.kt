package com.guidelens.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * EXPERIMENTAL fall detection: an impact spike (acceleration jump, ~3 g)
 * followed within seconds by near-stillness suggests a fall. MainActivity then
 * speaks a 15-second countdown and sends SOS unless the user taps the screen.
 *
 * This is a heuristic safety net, not a guarantee — expect occasional false
 * positives (phone dropped hard) and false negatives (soft falls).
 */
class FallDetector(
    context: Context,
    private val onFall: () -> Unit
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile private var armed = true
    @Volatile private var impactAt = 0L
    @Volatile private var registered = false

    fun start() {
        if (registered) return
        accel?.let { registered = sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        if (registered) sm.unregisterListener(this)
        registered = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        val a = sqrt(x * x + y * y + z * z)
        val now = System.currentTimeMillis()
        if (armed && a > 30f) {            // impact spike
            armed = false
            impactAt = now
        } else if (!armed) {
            val dt = now - impactAt
            if (dt in 1500..7000 && a in 7.5f..12.5f) {   // stillness after impact
                armed = true
                onFall()
            } else if (dt > 8000) {
                armed = true                    // false alarm (phone thrown onto sofa)
            }
        }
    }
}
