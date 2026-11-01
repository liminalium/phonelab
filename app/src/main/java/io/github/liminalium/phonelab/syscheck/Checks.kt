package io.github.liminalium.phonelab.syscheck

import android.Manifest
import android.content.Context
import android.os.Build

private fun info(id: String, title: String, read: (Context) -> Outcome) =
    Check(id, title, Kind.INFO) { ctx -> read(ctx) }

private val blePermissions =
    if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

object Checks {
    val all: List<Check> = listOf(
        info("device", "Device & OS", ::deviceInfo),
        info("cpu", "CPU", ::cpuInfo),
        info("gpu", "GPU", ::gpuInfo),
        info("memory", "Memory & storage", ::memoryInfo),
        info("display", "Display", ::displayInfo),
        info("battery", "Battery", ::batteryInfo),
        info("thermal", "Thermal", ::thermalInfo),
        info("sensors", "Sensors", ::sensorsInfo),
        info("cameras", "Cameras", ::camerasInfo),
        info("codecs", "Media codecs", ::codecsInfo),
        info("audio", "Audio & haptics", ::audioInfo),
        info("radios", "Radios", ::radiosInfo),
        info("network", "Network", ::networkInfo),
        info("security", "Security", ::securityInfo),
        info("features", "System features", ::featuresInfo),
        info("packages", "Related apps", ::packagesInfo),
        info("readiness", "Recorder readiness", ::readinessInfo),
        Check("sensor_rates", "Sensor sample rates", Kind.TEST, emptyList(), ::sensorRateTest),
        Check("microphone", "Microphone", Kind.TEST, listOf(Manifest.permission.RECORD_AUDIO), ::micTest),
        Check("cameras_live", "Cameras live (incl. front + back)", Kind.TEST, listOf(Manifest.permission.CAMERA), ::cameraLiveTest),
        Check("gnss", "GNSS / GPS", Kind.TEST, listOf(Manifest.permission.ACCESS_FINE_LOCATION), ::gnssTest),
        Check("wifi_scan", "Wi-Fi scan", Kind.TEST, listOf(Manifest.permission.ACCESS_FINE_LOCATION), ::wifiScanTest),
        Check("cell", "Cellular", Kind.TEST, listOf(Manifest.permission.ACCESS_FINE_LOCATION), ::cellTest),
        Check("ble_scan", "Bluetooth LE scan", Kind.TEST, blePermissions, ::bleScanTest),
    )

    fun byId(id: String) = all.first { it.id == id }

    /** Everything the hardware tests ask for, in one system dialog. */
    val testPermissions: Array<String> = (
        all.filter { it.kind == Kind.TEST }.flatMap { it.permissions } +
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).distinct().toTypedArray()
}
