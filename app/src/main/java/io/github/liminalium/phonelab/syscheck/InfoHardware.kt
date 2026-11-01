package io.github.liminalium.phonelab.syscheck

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MicrophoneInfo
import android.os.Build
import android.os.Vibrator
import org.json.JSONArray
import org.json.JSONObject

// ---------- Sensors ----------

val SENSOR_KEYS = linkedMapOf(
    Sensor.TYPE_ACCELEROMETER to "accelerometer",
    Sensor.TYPE_GYROSCOPE to "gyroscope",
    Sensor.TYPE_MAGNETIC_FIELD to "magnetometer",
    Sensor.TYPE_PRESSURE to "barometer",
    Sensor.TYPE_LIGHT to "light",
    Sensor.TYPE_PROXIMITY to "proximity",
    Sensor.TYPE_AMBIENT_TEMPERATURE to "ambientTemperature",
    Sensor.TYPE_RELATIVE_HUMIDITY to "humidity",
    Sensor.TYPE_STEP_COUNTER to "stepCounter",
    Sensor.TYPE_STEP_DETECTOR to "stepDetector",
    Sensor.TYPE_ROTATION_VECTOR to "rotationVector",
    Sensor.TYPE_GAME_ROTATION_VECTOR to "gameRotationVector",
    Sensor.TYPE_GRAVITY to "gravity",
    Sensor.TYPE_LINEAR_ACCELERATION to "linearAcceleration",
    Sensor.TYPE_SIGNIFICANT_MOTION to "significantMotion",
    Sensor.TYPE_HEART_RATE to "heartRate",
    Sensor.TYPE_ACCELEROMETER_UNCALIBRATED to "accelerometerUncalibrated",
    Sensor.TYPE_GYROSCOPE_UNCALIBRATED to "gyroscopeUncalibrated",
    Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED to "magnetometerUncalibrated",
)

private fun reportingMode(mode: Int) = when (mode) {
    Sensor.REPORTING_MODE_CONTINUOUS -> "continuous"
    Sensor.REPORTING_MODE_ON_CHANGE -> "on-change"
    Sensor.REPORTING_MODE_ONE_SHOT -> "one-shot"
    Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "special"
    else -> "mode $mode"
}

fun sensorsInfo(ctx: Context): Outcome {
    val sm = ctx.getSystemService(SensorManager::class.java)
    val list = sm.getSensorList(Sensor.TYPE_ALL)
    val sensors = list.map { s ->
        json {
            put("name", s.name)
            put("vendor", s.vendor)
            put("type", s.stringType)
            put("typeId", s.type)
            put("version", s.version)
            put("powerMa", s.power)
            put("resolution", s.resolution)
            put("maxRange", s.maximumRange)
            put("minDelayUs", s.minDelay)
            put("maxDelayUs", s.maxDelay)
            put("maxRateHz", if (s.minDelay > 0) round(1e6f / s.minDelay, 1) else null)
            put("reportingMode", reportingMode(s.reportingMode))
            put("wakeUp", s.isWakeUpSensor)
            put("dynamic", s.isDynamicSensor)
            put("fifoMax", s.fifoMaxEventCount)
            put("fifoReserved", s.fifoReservedEventCount)
            put("directChannelRateLevel", s.highestDirectReportRateLevel)
        }
    }
    val present = SENSOR_KEYS.map { (type, key) -> key to (sm.getDefaultSensor(type) != null) }.toMap()
    fun hz(type: Int) = sm.getDefaultSensor(type)?.minDelay?.takeIf { it > 0 }?.let { 1_000_000 / it }
    val missing = listOf("gyroscope", "magnetometer", "barometer", "light", "proximity", "stepCounter")
        .filter { present[it] == false }
    val data = json {
        put("count", list.size)
        put("present", present)
        put("sensors", sensors)
    }
    val headline = buildString {
        append("${list.size} sensors")
        hz(Sensor.TYPE_ACCELEROMETER)?.let { append(" · accel up to $it Hz") }
        hz(Sensor.TYPE_GYROSCOPE)?.let { append(" · gyro up to $it Hz") }
        if (missing.isNotEmpty()) append(" · no ${missing.joinToString(", ")}")
    }
    val status = if (present["accelerometer"] == true) Status.OK else Status.WARN
    return Outcome(status, headline, data)
}

// ---------- Cameras ----------

private fun capabilityName(c: Int) = when (c) {
    0 -> "BACKWARD_COMPATIBLE"
    1 -> "MANUAL_SENSOR"
    2 -> "MANUAL_POST_PROCESSING"
    3 -> "RAW"
    4 -> "PRIVATE_REPROCESSING"
    5 -> "READ_SENSOR_SETTINGS"
    6 -> "BURST_CAPTURE"
    7 -> "YUV_REPROCESSING"
    8 -> "DEPTH_OUTPUT"
    9 -> "HIGH_SPEED_VIDEO"
    10 -> "MOTION_TRACKING"
    11 -> "LOGICAL_MULTI_CAMERA"
    12 -> "MONOCHROME"
    13 -> "SECURE_IMAGE_DATA"
    14 -> "SYSTEM_CAMERA"
    15 -> "OFFLINE_PROCESSING"
    16 -> "ULTRA_HIGH_RESOLUTION"
    17 -> "REMOSAIC_REPROCESSING"
    18 -> "10BIT_OUTPUT"
    19 -> "STREAM_USE_CASE"
    20 -> "COLOR_SPACE_PROFILES"
    else -> "CAP_$c"
}

private fun levelName(level: Int?) = when (level) {
    0 -> "LIMITED"
    1 -> "FULL"
    2 -> "LEGACY"
    3 -> "LEVEL_3"
    4 -> "EXTERNAL"
    else -> "?"
}

fun facingName(facing: Int?) = when (facing) {
    CameraCharacteristics.LENS_FACING_FRONT -> "front"
    CameraCharacteristics.LENS_FACING_BACK -> "back"
    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
    else -> "?"
}

private fun formatName(f: Int) = when (f) {
    ImageFormat.JPEG -> "JPEG"
    ImageFormat.YUV_420_888 -> "YUV_420_888"
    ImageFormat.YUV_422_888 -> "YUV_422_888"
    ImageFormat.YUV_444_888 -> "YUV_444_888"
    ImageFormat.NV21 -> "NV21"
    ImageFormat.YV12 -> "YV12"
    ImageFormat.PRIVATE -> "PRIVATE"
    ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
    ImageFormat.RAW_PRIVATE -> "RAW_PRIVATE"
    ImageFormat.RAW10 -> "RAW10"
    ImageFormat.RAW12 -> "RAW12"
    ImageFormat.HEIC -> "HEIC"
    ImageFormat.DEPTH16 -> "DEPTH16"
    ImageFormat.DEPTH_POINT_CLOUD -> "DEPTH_POINT_CLOUD"
    ImageFormat.DEPTH_JPEG -> "DEPTH_JPEG"
    ImageFormat.Y8 -> "Y8"
    else -> "0x" + Integer.toHexString(f)
}

fun describeCamera(cm: CameraManager, id: String): JSONObject {
    val c = cm.getCameraCharacteristics(id)
    val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    return json {
        put("id", id)
        put("facing", facingName(c.get(CameraCharacteristics.LENS_FACING)))
        put("hardwareLevel", levelName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)))
        put("capabilities", c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.map { capabilityName(it) })
        put("megapixels", pixels?.let { round(it.width * it.height / 1e6f, 1) })
        put("pixelArray", pixels?.let { "${it.width}x${it.height}" })
        put("sensorSizeMm", c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { "${round(it.width, 2)}x${round(it.height, 2)}" })
        put("focalLengthsMm", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS))
        put("apertures", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES))
        put("ois", c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(1))
        put("fixedFocus", c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let { it == 0f })
        put("flash", c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))
        put("sensorOrientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION))
        put("isoRange", c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { "${it.lower}-${it.upper}" })
        put("exposureRangeNs", c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { "${it.lower}-${it.upper}" })
        put("aeFpsRanges", c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { "${it.lower}-${it.upper}" })
        put("videoStabilization", c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.contains(1))
        put("maxDigitalZoom", c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))
        put("physicalIds", c.physicalCameraIds.toList())
        if (map != null) {
            put("outputFormats", map.outputFormats.map { formatName(it) })
            put("jpegSizes", map.getOutputSizes(ImageFormat.JPEG)?.sortedByDescending { it.width * it.height }?.take(3)?.map { it.toString() })
            put("maxYuv", map.getOutputSizes(ImageFormat.YUV_420_888)?.maxByOrNull { it.width * it.height }?.toString())
            if (ImageFormat.RAW_SENSOR in map.outputFormats) {
                put("rawSizes", map.getOutputSizes(ImageFormat.RAW_SENSOR)?.map { it.toString() })
            }
            attempt("highSpeedVideo") {
                map.highSpeedVideoSizes?.map { size -> "$size@" + map.getHighSpeedVideoFpsRangesFor(size).maxOf { it.upper } }
            }
        }
    }
}

private fun cameraLine(cam: JSONObject): String {
    val caps = cam.optJSONArray("capabilities")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    val extra = listOfNotNull(
        "RAW".takeIf { "RAW" in caps },
        "manual".takeIf { "MANUAL_SENSOR" in caps },
    )
    val mp = cam.opt("megapixels").let { if (it is Number) fmt("%.0f MP", it.toDouble()) else "?" }
    return "${cam.optString("facing")} $mp ${cam.optString("hardwareLevel")}" +
        if (extra.isNotEmpty()) " (${extra.joinToString(", ")})" else ""
}

fun camerasInfo(ctx: Context): Outcome {
    val cm = ctx.getSystemService(CameraManager::class.java)
    val ids = cm.cameraIdList.toList()
    val cameras = ids.mapNotNull { id -> safe { describeCamera(cm, id) } }
    // Some phone makers keep extra cameras out of the public list but still answer for them.
    val hidden = (0..9).map { it.toString() }.filter { it !in ids }
        .mapNotNull { id -> safe { describeCamera(cm, id) } }
    val data = json {
        put("count", cameras.size)
        put("cameras", cameras)
        put("hiddenCameras", hidden)
        if (Build.VERSION.SDK_INT >= 30) {
            attempt("concurrentSets") { cm.concurrentCameraIds.map { it.sorted().joinToString("+") } }
        } else {
            put("concurrentSets", "not reported before Android 11; see the live camera test")
        }
    }
    val headline = "${cameras.size} cameras: " + cameras.joinToString(", ") { cameraLine(it) } +
        if (hidden.isNotEmpty()) " · ${hidden.size} hidden" else ""
    return Outcome(if (cameras.isEmpty()) Status.WARN else Status.OK, headline, data)
}

// ---------- Codecs ----------

private val MIME_NAMES = mapOf(
    "video/avc" to "H.264",
    "video/hevc" to "HEVC",
    "video/x-vnd.on2.vp8" to "VP8",
    "video/x-vnd.on2.vp9" to "VP9",
    "video/av01" to "AV1",
    "video/mp4v-es" to "MPEG-4",
    "video/3gpp" to "H.263",
    "video/dolby-vision" to "Dolby Vision",
    "audio/mp4a-latm" to "AAC",
    "audio/opus" to "Opus",
    "audio/flac" to "FLAC",
    "audio/3gpp" to "AMR-NB",
    "audio/amr-wb" to "AMR-WB",
    "audio/vorbis" to "Vorbis",
    "audio/mpeg" to "MP3",
    "audio/raw" to "PCM",
)

fun mimeName(mime: String) = MIME_NAMES[mime] ?: mime

private fun describeType(info: MediaCodecInfo, type: String): JSONObject = json {
    put("mime", type)
    val caps = safe { info.getCapabilitiesForType(type) } ?: return@json
    put("profiles", caps.profileLevels.map { it.profile }.distinct())
    caps.videoCapabilities?.let { v ->
        put("maxSize", "${v.supportedWidths.upper}x${v.supportedHeights.upper}")
        put("maxFps", v.supportedFrameRates.upper)
        put("maxBitrate", v.bitrateRange.upper)
        attempt("supports4k") { v.isSizeSupported(3840, 2160) }
        attempt("fps1080p") { v.getAchievableFrameRatesFor(1920, 1080)?.let { "${it.lower.toInt()}-${it.upper.toInt()}" } }
    }
    caps.audioCapabilities?.let { a ->
        put("maxChannels", a.maxInputChannelCount)
        put("sampleRates", a.supportedSampleRates)
        put("maxBitrate", a.bitrateRange.upper)
    }
    caps.encoderCapabilities?.let { e ->
        put("bitrateModes", listOf("CQ" to 0, "VBR" to 1, "CBR" to 2).filter { e.isBitrateModeSupported(it.second) }.map { it.first })
    }
}

fun codecsInfo(ctx: Context): Outcome {
    val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isAlias }
    val codecs = infos.map { info ->
        json {
            put("name", info.name)
            put("encoder", info.isEncoder)
            put("hardware", info.isHardwareAccelerated)
            put("softwareOnly", info.isSoftwareOnly)
            put("vendor", info.isVendor)
            put("types", JSONArray().also { a -> info.supportedTypes.forEach { t -> a.put(describeType(info, t)) } })
        }
    }
    fun names(encoder: Boolean, hardware: Boolean, prefix: String) = infos
        .filter { it.isEncoder == encoder && (!hardware || it.isHardwareAccelerated) }
        .flatMap { it.supportedTypes.toList() }
        .filter { it.startsWith(prefix) }
        .map { mimeName(it) }.distinct()
    val hwVideoEnc = names(encoder = true, hardware = true, prefix = "video/")
    val hwVideoDec = names(encoder = false, hardware = true, prefix = "video/")
    val audioEnc = names(encoder = true, hardware = false, prefix = "audio/")
    val hevcMain10 = infos.any { info ->
        info.isEncoder && "video/hevc" in info.supportedTypes && safe {
            info.getCapabilitiesForType("video/hevc").profileLevels.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            }
        } == true
    }
    val data = json {
        put("count", infos.size)
        put("hwVideoEncoders", hwVideoEnc)
        put("hwVideoDecoders", hwVideoDec)
        put("audioEncoders", audioEnc)
        put("opusEncoder", "Opus" in audioEnc)
        put("hevcMain10Encoder", hevcMain10)
        put("codecs", codecs)
    }
    val headline = "${infos.size} codecs · HW encode: ${hwVideoEnc.joinToString(", ").ifEmpty { "none" }}" +
        " · HW decode: ${hwVideoDec.joinToString(", ").ifEmpty { "none" }} · Opus encoder ${yesNo("Opus" in audioEnc)}"
    return Outcome(if (hwVideoEnc.isEmpty()) Status.WARN else Status.OK, headline, data)
}

// ---------- Audio and haptics ----------

private fun audioDeviceName(type: Int) = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth SCO"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth A2DP"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
    AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
    AudioDeviceInfo.TYPE_FM_TUNER -> "FM tuner"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote submix"
    AudioDeviceInfo.TYPE_IP -> "IP"
    AudioDeviceInfo.TYPE_BUS -> "bus"
    else -> "type $type"
}

fun audioInfo(ctx: Context): Outcome {
    val am = ctx.getSystemService(AudioManager::class.java)
    val pm = ctx.packageManager
    val devices = am.getDevices(AudioManager.GET_DEVICES_ALL).map { d ->
        json {
            put("type", audioDeviceName(d.type))
            put("input", d.isSource)
            put("output", d.isSink)
            put("name", d.productName?.toString())
            put("sampleRates", d.sampleRates)
            put("channelCounts", d.channelCounts)
        }
    }
    val mics = safe { am.microphones }.orEmpty().map { m ->
        json {
            put("description", m.description)
            put("type", audioDeviceName(m.type))
            put("location", when (m.location) {
                MicrophoneInfo.LOCATION_MAINBODY -> "main body"
                MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "movable"
                MicrophoneInfo.LOCATION_PERIPHERAL -> "peripheral"
                else -> "unknown"
            })
            put("directionality", when (m.directionality) {
                MicrophoneInfo.DIRECTIONALITY_OMNI -> "omni"
                MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL -> "bi-directional"
                MicrophoneInfo.DIRECTIONALITY_CARDIOID -> "cardioid"
                MicrophoneInfo.DIRECTIONALITY_HYPER_CARDIOID -> "hypercardioid"
                MicrophoneInfo.DIRECTIONALITY_SUPER_CARDIOID -> "supercardioid"
                else -> "unknown"
            })
            val pos = m.position
            put("position", if (pos == MicrophoneInfo.POSITION_UNKNOWN) null else "${round(pos.x)},${round(pos.y)},${round(pos.z)}")
            put("sensitivityDb", m.sensitivity.takeIf { it != MicrophoneInfo.SENSITIVITY_UNKNOWN })
            put("maxSplDb", m.maxSpl.takeIf { it != MicrophoneInfo.SPL_UNKNOWN })
            put("frequencyResponsePoints", m.frequencyResponse.size)
        }
    }
    val builtinMics = am.getDevices(AudioManager.GET_DEVICES_INPUTS).count { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    val vibrator = ctx.getSystemService(Vibrator::class.java)
    val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    val frames = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
    val lowLatency = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
    val micCount = if (mics.isNotEmpty()) mics.size else builtinMics
    val data = json {
        put("outputSampleRate", rate)
        put("outputFramesPerBuffer", frames)
        put("lowLatency", lowLatency)
        put("pro", pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO))
        put("microphoneFeature", pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
        put("unprocessedSource", am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
        put("micNearUltrasound", am.getProperty(AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND) == "true")
        put("speakerNearUltrasound", am.getProperty(AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND) == "true")
        put("musicVolume", "${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
        put("micCount", micCount)
        put("microphones", mics)
        put("devices", devices)
        put("earpiece", am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE })
        put("vibrator", json {
            put("present", vibrator?.hasVibrator())
            put("amplitudeControl", vibrator?.hasAmplitudeControl())
        })
    }
    val headline = listOfNotNull(
        rate?.let { "${it / 1000} kHz" },
        frames?.let { "$it frames/buffer" },
        "low latency ${yesNo(lowLatency)}",
        "$micCount mics",
        "vibration strength control ${yesNo(vibrator?.hasAmplitudeControl())}",
    ).joinToString(" · ")
    return Outcome(Status.OK, headline, data)
}
