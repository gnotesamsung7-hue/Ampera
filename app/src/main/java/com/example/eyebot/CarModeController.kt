package com.example.eyebot

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Android side of Car Mode and power: GPS speed, accelerometer bumps, ambient light, battery
 * level / charging / temperature and the system thermal status. All callbacks on the main thread;
 * the decisions are made by the pure classes in CarLogic.kt (via CompanionBrain).
 */
class CarModeController(
    private val context: Context,
    private val listener: Listener,
) : SensorEventListener, LocationListener {

    interface Listener {
        fun onSpeed(kmh: Float?)
        fun onRoad(event: RoadFeel.Event)
        fun onShake()
        fun onLight(lux: Float)
        fun onPower(percent: Int, charging: Boolean, tempC: Float?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locations = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val roadFeel = RoadFeel()
    private val shake = ShakeDetector()
    private var lastFixAt = 0L
    private var gpsOn = false
    private var sensorsOn = false
    private var thermalHot = false
    private var lastLuxAt = 0L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = handleBattery(i)
    }

    private val thermalListener: Any? = if (Build.VERSION.SDK_INT >= 29) {
        PowerManager.OnThermalStatusChangedListener { status ->
            thermalHot = status >= PowerManager.THERMAL_STATUS_SEVERE
        }
    } else null

    private val staleCheck = object : Runnable {
        override fun run() {
            if (gpsOn && System.currentTimeMillis() - lastFixAt > STALE_MS) listener.onSpeed(null)
            main.postDelayed(this, 2_000)
        }
    }

    /** Battery / heat / light monitoring (always on). */
    fun startPowerAndSensors() {
        ContextCompat.registerReceiver(context, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { handleBattery(it) }
        if (Build.VERSION.SDK_INT >= 29) {
            (thermalListener as? PowerManager.OnThermalStatusChangedListener)?.let { powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(context), it) }
        }
        if (!sensorsOn) {
            sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            sensors.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            sensorsOn = true
        }
    }

    /** GPS speed (needs ACCESS_FINE_LOCATION, checked by MainActivity). */
    @SuppressLint("MissingPermission")
    fun startGps(): Boolean {
        if (gpsOn) return true
        return try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
            gpsOn = true
            main.removeCallbacks(staleCheck)
            main.postDelayed(staleCheck, 2_000)
            true
        } catch (e: Exception) {
            Log.w(TAG, "GPS unavailable", e); false
        }
    }

    fun stopGps() {
        if (!gpsOn) return
        try { locations.removeUpdates(this) } catch (_: Exception) {}
        gpsOn = false
        main.removeCallbacks(staleCheck)
    }

    fun stopAll() {
        stopGps()
        if (sensorsOn) { sensors.unregisterListener(this); sensorsOn = false }
        try { context.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 29) {
            (thermalListener as? PowerManager.OnThermalStatusChangedListener)?.let { powerManager.removeThermalStatusListener(it) }
        }
    }

    private fun handleBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val tenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        var temp: Float? = if (tenths == Int.MIN_VALUE) null else tenths / 10f
        if (thermalHot) temp = maxOf(temp ?: 0f, 47f)      // the system says it's overheating
        if (level >= 0 && scale > 0) listener.onPower(level * 100 / scale, plugged, temp)
    }

    // ---- LocationListener (all methods implemented: older Androids call the deprecated ones) ----
    override fun onLocationChanged(location: Location) {
        lastFixAt = System.currentTimeMillis()
        listener.onSpeed(if (location.hasSpeed()) location.speed * 3.6f else null)
    }
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { listener.onSpeed(null) }

    // ---- SensorEventListener ----
    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val t = System.currentTimeMillis()
                if (shake.update(t, e.values[0], e.values[1], e.values[2])) listener.onShake()
                else roadFeel.update(t, e.values[0], e.values[1], e.values[2])?.let(listener::onRoad)
            }
            Sensor.TYPE_LIGHT -> {
                val now = System.currentTimeMillis()
                if (now - lastLuxAt > 1_000) { lastLuxAt = now; listener.onLight(e.values[0]) }
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val TAG = "CarMode"
        private const val STALE_MS = 10_000L
    }
}
