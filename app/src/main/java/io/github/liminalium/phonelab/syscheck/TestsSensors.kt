package io.github.liminalium.phonelab.syscheck

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

private val RATE_TYPES = listOf(
    Sensor.TYPE_ACCELEROMETER,
    Sensor.TYPE_GYROSCOPE,
    Sensor.TYPE_MAGNETIC_FIELD,
    Sensor.TYPE_PRESSURE,
    Sensor.TYPE_LIGHT,
    Sensor.TYPE_PROXIMITY,
    Sensor.TYPE_ROTATION_VECTOR,
    Sensor.TYPE_GAME_ROTATION_VECTOR,
    Sensor.TYPE_LINEAR_ACCELERATION,
    Sensor.TYPE_GRAVITY,
    Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
    Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
    Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
    Sensor.TYPE_AMBIENT_TEMPERATURE,
    Sensor.TYPE_RELATIVE_HUMIDITY,
)

private class RateStat {
    var events = 0
    var first = 0L
    var last = 0L
    var values: FloatArray? = null
}

private fun sensorKey(s: Sensor) = "${s.type}|${s.name}|${s.vendor}"

/** Streams the main sensors at full speed for 3 s and measures the rate each one really delivers. */
suspend fun sensorRateTest(ctx: Context): Outcome = withContext(Dispatchers.Default) {
    val sm = ctx.getSystemService(SensorManager::class.java)
    val sensors = RATE_TYPES.mapNotNull { sm.getDefaultSensor(it) }
    val stats = sensors.associate { sensorKey(it) to RateStat() }
    val thread = HandlerThread("sensor-test").apply { start() }
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val s = stats[sensorKey(event.sensor)] ?: return
            if (s.events == 0) s.first = event.timestamp
            s.last = event.timestamp
            s.events++
            s.values = event.values.copyOf()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }
    val handler = Handler(thread.looper)
    sensors.forEach { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
    try {
        delay(3000)
    } finally {
        sm.unregisterListener(listener)
        thread.quitSafely()
        thread.join(1000)
    }
    val rates = mutableMapOf<String, Double?>()
    val data = json {
        for (s in sensors) {
            val st = stats.getValue(sensorKey(s))
            val key = SENSOR_KEYS[s.type] ?: s.stringType
            val rate = if (st.events >= 2 && st.last > st.first) (st.events - 1) * 1e9 / (st.last - st.first) else null
            rates[key] = rate
            put(key, json {
                put("name", s.name)
                put("events", st.events)
                put("rateHz", rate?.let { round(it.toFloat(), 1) })
                put("advertisedMaxHz", if (s.minDelay > 0) 1_000_000 / s.minDelay else null)
                put("lastValues", st.values?.map { round(it, 3) })
            })
        }
    }
    fun rateText(key: String, label: String) = rates[key]?.let { fmt("%s %.0f Hz", label, it) }
    val headline = listOfNotNull(
        rateText("accelerometer", "accel"),
        rateText("gyroscope", "gyro"),
        rateText("magnetometer", "mag"),
        rateText("barometer", "baro"),
    ).joinToString(" · ").ifEmpty { "no sensor delivered events" }
    val accelEvents = stats.entries.firstOrNull { it.key.startsWith("${Sensor.TYPE_ACCELEROMETER}|") }?.value?.events ?: 0
    Outcome(if (accelEvents > 0) Status.OK else Status.FAIL, headline, data)
}

/** Records 1.5 s from the main microphone and reports the level. Nothing is saved. */
@SuppressLint("MissingPermission")
suspend fun micTest(ctx: Context): Outcome = withContext(Dispatchers.IO) {
    val rate = 48_000
    val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) return@withContext Outcome(Status.FAIL, "48 kHz mono capture not supported", JSONObject())
    val rec = AudioRecord(
        MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate / 5 * 2),
    )
    try {
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            return@withContext Outcome(Status.FAIL, "microphone could not be opened (another app may hold it)", JSONObject())
        }
        val buf = ShortArray(rate / 10)
        val skip = rate / 5 // let the input settle for 200 ms
        val target = rate * 3 / 2
        var got = 0
        var n = 0L
        var sumSq = 0.0
        var peak = 0
        var clipped = 0
        rec.startRecording()
        while (got < target) {
            val r = rec.read(buf, 0, buf.size)
            if (r <= 0) break
            for (i in 0 until r) {
                if (got + i < skip) continue
                val s = buf[i].toInt()
                sumSq += s.toDouble() * s
                val a = abs(s)
                if (a > peak) peak = a
                if (a >= 32767) clipped++
                n++
            }
            got += r
        }
        val routed = rec.routedDevice
        val active = safe { rec.activeMicrophones.map { it.description } }
        rec.stop()
        val rmsDb = if (n > 0 && sumSq > 0) 20 * log10(sqrt(sumSq / n) / 32768) else null
        val peakDb = if (peak > 0) 20 * log10(peak / 32768.0) else null
        val data = json {
            put("sampleRate", rec.sampleRate)
            put("samples", n)
            put("rmsDbfs", rmsDb)
            put("peakDbfs", peakDb)
            put("clippedSamples", clipped)
            put("routedDevice", routed?.productName?.toString())
            put("activeMicrophones", active)
        }
        when {
            n == 0L -> Outcome(Status.FAIL, "no audio samples arrived", data)
            peak == 0 -> Outcome(Status.FAIL, "pure silence: mic muted or blocked by another app", data)
            else -> Outcome(
                Status.OK,
                fmt("mic works · ambient %.0f dBFS · peak %.0f dBFS", rmsDb ?: -120.0, peakDb ?: -120.0),
                data,
            )
        }
    } finally {
        rec.release()
    }
}
