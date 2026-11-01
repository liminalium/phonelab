package io.github.liminalium.phonelab.syscheck

import org.json.JSONArray
import org.json.JSONObject

enum class Verdict(val symbol: String) { YES("✓"), PARTIAL("◐"), NO("✗"), UNKNOWN("?") }

data class Capability(val area: String, val module: String, val verdict: Verdict, val note: String? = null) {
    fun toJson(): JSONObject = json {
        put("area", area)
        put("module", module)
        put("verdict", verdict)
        put("note", note)
    }
}

private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
private fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
private fun JSONObject?.bool(key: String): Boolean? = this?.opt(key) as? Boolean

/**
 * Maps what the check found onto the module catalog from the build plan, so
 * the roadmap can drop what this phone can't do.
 */
fun computeCapabilities(states: Map<String, CheckState>): List<Capability> {
    fun data(id: String): JSONObject? = (states[id] as? CheckState.Done)?.outcome?.data
    val present = data("sensors")?.optJSONObject("present")
    fun sensor(key: String) = present.bool(key)
    val radios = data("radios")
    fun radio(key: String) = radios.bool(key)
    val cameras = data("cameras")?.optJSONArray("cameras").objects()
    val camerasKnown = data("cameras") != null
    fun hasCap(cap: String) = cameras.any { cap in it.optJSONArray("capabilities").strings() }
    val audio = data("audio")
    val packages = data("packages")
    fun installed(key: String) = packages?.optJSONObject(key).bool("installed")
    val live = data("cameras_live")
    val gnss = data("gnss")
    val rates = data("sensor_rates")
    val cpu = data("cpu")
    val battery = data("battery")
    val thermal = data("thermal")
    val readiness = data("readiness")
    val sdk = data("device")?.optInt("sdk") ?: android.os.Build.VERSION.SDK_INT

    val out = mutableListOf<Capability>()
    fun add(area: String, module: String, verdict: Verdict, note: String? = null) {
        out += Capability(area, module, verdict, note)
    }
    fun flag(area: String, module: String, ok: Boolean?, yes: String? = null, no: String? = null) = add(
        area, module,
        when (ok) {
            true -> Verdict.YES
            false -> Verdict.NO
            null -> Verdict.UNKNOWN
        },
        if (ok == true) yes else if (ok == false) no else null,
    )

    // Capture
    add("Capture", "Screen recorder", Verdict.YES, "screen capture works on Android 10+, including other apps' sound")
    when (live.bool("backFrontTogether")) {
        true -> add("Capture", "Dual camera recorder", Verdict.YES, "front and back stream together")
        false -> add("Capture", "Dual camera recorder", Verdict.PARTIAL, "front and back can't run together; one camera bubble at a time")
        null -> add("Capture", "Dual camera recorder", Verdict.UNKNOWN, "run the hardware tests")
    }
    val manual = hasCap("MANUAL_SENSOR")
    val raw = hasCap("RAW")
    add(
        "Capture", "Pro camera",
        when {
            !camerasKnown -> Verdict.UNKNOWN
            manual && raw -> Verdict.YES
            manual || raw -> Verdict.PARTIAL
            else -> Verdict.NO
        },
        when {
            !camerasKnown -> null
            manual && raw -> "manual controls and RAW"
            manual -> "manual controls, no RAW"
            raw -> "RAW, no manual controls"
            else -> "camera only allows automatic mode"
        },
    )
    val mic = audio.bool("microphoneFeature")
    flag("Capture", "Audio recorder", mic, yes = "${audio?.optInt("micCount")} microphones")
    flag("Capture", "Audio analyzer", mic)
    flag("Capture", "Tone generator", audio?.let { true })
    when (audio.bool("lowLatency")) {
        true -> add("Capture", "Acoustic sonar", Verdict.YES, "low-latency audio")
        false -> add("Capture", "Acoustic sonar", Verdict.PARTIAL, "no low-latency audio; distance less precise")
        null -> add("Capture", "Acoustic sonar", Verdict.UNKNOWN)
    }
    val depth = hasCap("DEPTH_OUTPUT")
    when {
        depth -> add("Capture", "Depth and AR", Verdict.YES, "depth camera")
        installed("arcore") == true -> add("Capture", "Depth and AR", Verdict.PARTIAL, "ARCore installed; support for this phone not verified")
        packages == null -> add("Capture", "Depth and AR", Verdict.UNKNOWN)
        else -> add("Capture", "Depth and AR", Verdict.NO, "no depth camera and ARCore not installed")
    }
    flag("Capture", "Flashlight", if (camerasKnown) cameras.any { it.optBoolean("flash") } else null)

    // Motion
    flag("Motion", "Accelerometer", sensor("accelerometer"))
    flag("Motion", "Gyroscope", sensor("gyroscope"))
    flag("Motion", "Compass and magnetometer", sensor("magnetometer"))
    flag("Motion", "Level and inclinometer", sensor("accelerometer"))
    flag("Motion", "Pedometer", present?.let { sensor("stepCounter") == true || sensor("stepDetector") == true })
    val accelHz = rates?.optJSONObject("accelerometer")?.opt("rateHz") as? Number
    when {
        accelHz == null -> add("Motion", "Vibration analyzer", if (sensor("accelerometer") == true) Verdict.YES else Verdict.UNKNOWN, "run the tests to measure the real rate")
        accelHz.toDouble() >= 200 -> add("Motion", "Vibration analyzer", Verdict.YES, "accelerometer ${accelHz.toInt()} Hz")
        else -> add("Motion", "Vibration analyzer", Verdict.PARTIAL, "accelerometer only ${accelHz.toInt()} Hz")
    }
    flag("Motion", "Activity recognition", installed("playServices"), yes = "through Google Play services", no = "needs Google Play services")

    // Environment
    flag("Environment", "Barometer", sensor("barometer"))
    flag("Environment", "Light meter", sensor("light"))
    flag("Environment", "Proximity", sensor("proximity"))
    when {
        present == null -> add("Environment", "Temperature and humidity", Verdict.UNKNOWN)
        sensor("ambientTemperature") == true || sensor("humidity") == true -> add("Environment", "Temperature and humidity", Verdict.YES)
        else -> add("Environment", "Temperature and humidity", Verdict.PARTIAL, "battery temperature only")
    }

    // Radios
    val gps = radio("gps")
    if (gps == true && gnss != null) {
        val rawStatus = gnss.optString("rawMeasurements")
        val dual = gnss.bool("dualFrequency") == true
        add(
            "Radios", "GNSS lab",
            if (rawStatus == "supported") Verdict.YES else Verdict.PARTIAL,
            "raw measurements $rawStatus · dual-frequency ${yesNo(dual)}",
        )
    } else {
        flag("Radios", "GNSS lab", gps, yes = "run the tests for raw and dual-frequency support")
    }
    flag("Radios", "Track logger", gps)
    flag("Radios", "Wi-Fi analyzer", radio("wifi"), yes = "Wi-Fi ranging (RTT) ${yesNo(radio("wifiRtt"))}")
    flag("Radios", "Cellular info", radio("telephony"))
    flag("Radios", "Bluetooth/BLE scanner", radio("bluetoothLe"))
    flag("Radios", "UWB ranging", radio("uwb"), no = "no UWB chip")
    flag("Radios", "NFC tool", radio("nfc"), no = "no NFC chip")
    flag("Radios", "IR remote", radio("consumerIr"), no = "no IR blaster")
    flag("Radios", "USB/OTG", radio("usbHost"))
    add("Radios", "Network tools", Verdict.YES)

    // Device info
    when (cpu.bool("cpufreqReadable")) {
        true -> add("Device info", "CPU/GPU monitor", Verdict.YES)
        false -> add("Device info", "CPU/GPU monitor", Verdict.PARTIAL, "per-core speeds not readable")
        null -> add("Device info", "CPU/GPU monitor", Verdict.UNKNOWN)
    }
    add("Device info", "Memory and storage", Verdict.YES)
    when {
        battery == null -> add("Device info", "Battery lab", Verdict.UNKNOWN)
        battery.opt("currentNowUa") is Number -> add(
            "Device info", "Battery lab", Verdict.YES,
            "live current ✓ · cycle count ${yesNo(battery.opt("cycleCount") is Number)}",
        )
        else -> add("Device info", "Battery lab", Verdict.PARTIAL, "no live current reading")
    }
    when {
        thermal == null -> add("Device info", "Thermal monitor", Verdict.UNKNOWN)
        thermal.optInt("zonesReadable") > 0 -> add("Device info", "Thermal monitor", Verdict.YES, "${thermal.optInt("zonesReadable")} temperature sensors")
        thermal.opt("headroom10s") is Number -> add("Device info", "Thermal monitor", Verdict.PARTIAL, "throttling status and headroom; no temperatures")
        else -> add("Device info", "Thermal monitor", Verdict.PARTIAL, "throttling status only")
    }
    listOf("Display tester", "Touch tester", "Sensor inspector", "Camera inspector", "Codec inspector", "System info")
        .forEach { add("Device info", it, Verdict.YES) }

    // Output
    when (audio?.optJSONObject("vibrator").bool("amplitudeControl")) {
        true -> add("Output", "Haptics lab", Verdict.YES, "vibration strength control")
        false -> add("Output", "Haptics lab", Verdict.PARTIAL, "vibration on/off only")
        null -> add("Output", "Haptics lab", Verdict.UNKNOWN)
    }
    flag("Output", "Speaker and earpiece test", audio?.let { true }, yes = "earpiece ${yesNo(audio.bool("earpiece"))}")

    // Health
    flag("Health", "Heart rate", if (camerasKnown) cameras.any { it.optString("facing") == "back" && it.optBoolean("flash") } else null)
    if (sdk >= 34) {
        add("Health", "Health Connect", Verdict.YES, "built into Android 14+")
    } else {
        flag("Health", "Health Connect", installed("healthConnect"), no = "Health Connect app not installed")
    }

    // Data
    listOf("Multi-sensor logger", "Live streaming", "Remote control", "Experiment builder")
        .forEach { add("Data", it, Verdict.YES) }
    add("Data", "Triggers and automation", Verdict.YES, "sound triggers need the recording service running")
    when (readiness.bool("overlay")) {
        true -> add("Data", "Floating overlay", Verdict.YES)
        false -> add("Data", "Floating overlay", Verdict.PARTIAL, "grant \"display over other apps\"")
        null -> add("Data", "Floating overlay", Verdict.UNKNOWN)
    }
    flag("Data", "External sensors", radios?.let { radio("bluetoothLe") == true || radio("usbHost") == true })
    add("Data", "Export", Verdict.YES)
    return out
}
