package com.guidelens.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Pocket pause: mutes alerts + sonar while the proximity sensor is covered
 * (phone in pocket / face-down), so 20 minutes in a bag doesn't mean 20
 * minutes of "Stop!" beeping.
 */
class PocketWatcher(
    context: Context,
    private val onPocket: (covered: Boolean) -> Unit
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    @Volatile private var registered = false

    fun start() {
        if (registered) return
        prox?.let { registered = sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        if (registered) sm.unregisterListener(this)
        registered = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_PROXIMITY) return
        onPocket(e.values[0] < e.sensor.maximumRange)
    }
}
