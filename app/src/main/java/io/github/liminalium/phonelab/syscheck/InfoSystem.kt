package io.github.liminalium.phonelab.syscheck

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sqrt

private val INTERESTING_PROPS = listOf(
    "ro.product.marketname", "ro.product.vendor.marketname", "ro.product.model",
    "ro.miui.ui.version.name", "ro.miui.ui.version.code", "ro.miui.region", "ro.miui.build.region",
    "ro.lineage.version", "ro.modversion", "ro.build.display.id", "ro.build.date",
    "ro.vendor.build.security_patch", "ro.product.first_api_level", "ro.board.platform",
    "ro.hardware", "ro.hardware.egl", "ro.hardware.vulkan", "ro.soc.model",
    "ro.treble.enabled", "ro.vndk.version", "ro.boot.verifiedbootstate",
    "ro.boot.flash.locked", "ro.boot.vbmeta.device_state", "ro.crypto.state", "ro.crypto.type",
    "ro.config.low_ram", "dalvik.vm.heapsize", "ro.opengles.version", "ro.sf.lcd_density",
    "ro.build.characteristics",
)

fun miuiVersion(): String? =
    SystemProps["ro.miui.ui.version.name"]?.let { name -> SystemProps["ro.build.version.incremental"] ?: name }

fun deviceInfo(ctx: Context): Outcome {
    val market = SystemProps["ro.product.marketname"] ?: SystemProps["ro.product.vendor.marketname"]
    val miui = miuiVersion()
    val data = json {
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("marketName", market)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("board", Build.BOARD)
        put("hardware", Build.HARDWARE)
        put("android", Build.VERSION.RELEASE)
        put("sdk", Build.VERSION.SDK_INT)
        put("securityPatch", Build.VERSION.SECURITY_PATCH)
        put("buildId", Build.ID)
        put("incremental", Build.VERSION.INCREMENTAL)
        put("fingerprint", Build.FINGERPRINT)
        put("buildType", Build.TYPE)
        put("bootloader", Build.BOOTLOADER)
        attempt("radioVersion") { Build.getRadioVersion() }
        put("abis", Build.SUPPORTED_ABIS)
        if (Build.VERSION.SDK_INT >= 31) {
            put("socManufacturer", Build.SOC_MANUFACTURER)
            put("socModel", Build.SOC_MODEL)
        }
        put("miui", miui)
        put("kernel", System.getProperty("os.version"))
        put("vm", "${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
        put("uptimeHours", SystemClock.elapsedRealtime() / 3_600_000.0)
        put("locale", Locale.getDefault().toLanguageTag())
        put("timeZone", TimeZone.getDefault().id)
        put("props", INTERESTING_PROPS.mapNotNull { k -> SystemProps[k]?.let { k to it } }.toMap())
    }
    val name = market ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    val headline = buildString {
        append("$name · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        if (miui != null) append(" · MIUI $miui")
        append(" · patch ${Build.VERSION.SECURITY_PATCH}")
    }
    return Outcome(Status.OK, headline, data)
}

fun cpuInfo(ctx: Context): Outcome {
    val cpuinfo = readFile("/proc/cpuinfo").orEmpty()
    val fields = cpuinfo.lines().mapNotNull { line ->
        val i = line.indexOf(':')
        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
    }
    val hardware = fields.lastOrNull { it.first == "Hardware" }?.second
    val parts = fields.filter { it.first == "CPU part" }.map { it.second }
    val cpuDirs = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
        ?.sortedBy { it.name.removePrefix("cpu").toInt() }.orEmpty()
    val cores = cpuDirs.map { dir ->
        json {
            put("core", dir.name)
            put("minMHz", readFile("$dir/cpufreq/cpuinfo_min_freq")?.toLongOrNull()?.div(1000))
            put("maxMHz", readFile("$dir/cpufreq/cpuinfo_max_freq")?.toLongOrNull()?.div(1000))
            put("curMHz", readFile("$dir/cpufreq/scaling_cur_freq")?.toLongOrNull()?.div(1000))
            put("governor", readFile("$dir/cpufreq/scaling_governor"))
            put("online", readFile("$dir/online") ?: "1")
        }
    }
    val clusters = cores.mapNotNull { it.optLong("maxMHz", -1).takeIf { f -> f > 0 } }
        .groupingBy { it }.eachCount().toSortedMap()
    val clusterText = clusters.entries.joinToString(" + ") { (mhz, n) -> fmt("%d×%.2f GHz", n, mhz / 1000.0) }
    val socName = hardware ?: (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null) ?: Build.HARDWARE
    val data = json {
        put("hardware", hardware)
        put("board", SystemProps["ro.board.platform"])
        put("coreCount", cpuDirs.size)
        put("availableProcessors", Runtime.getRuntime().availableProcessors())
        put("clusters", clusterText.ifEmpty { null })
        put("cpuParts", parts.distinct())
        put("features", fields.firstOrNull { it.first == "Features" }?.second?.split(" "))
        put("cores", cores)
        put("cpufreqReadable", clusters.isNotEmpty())
    }
    val headline = "$socName · ${cpuDirs.size} cores" + if (clusterText.isNotEmpty()) " ($clusterText)" else ""
    return Outcome(if (clusters.isEmpty()) Status.WARN else Status.OK, headline, data)
}

private fun eglStrings(): Map<String, Any?> {
    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val version = IntArray(2)
    check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
    val configs = arrayOfNulls<EGLConfig>(1)
    val count = IntArray(1)
    val attribs = intArrayOf(
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
        EGL14.EGL_NONE,
    )
    check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no EGL config" }
    val context = EGL14.eglCreateContext(
        display, configs[0], EGL14.EGL_NO_CONTEXT,
        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
    )
    val surface = EGL14.eglCreatePbufferSurface(
        display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
    )
    try {
        EGL14.eglMakeCurrent(display, surface, surface, context)
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS)?.trim()?.split(" ").orEmpty()
        return mapOf(
            "renderer" to GLES20.glGetString(GLES20.GL_RENDERER),
            "vendor" to GLES20.glGetString(GLES20.GL_VENDOR),
            "glVersion" to GLES20.glGetString(GLES20.GL_VERSION),
            "eglVersion" to "${version[0]}.${version[1]}",
            "eglVendor" to EGL14.eglQueryString(display, EGL14.EGL_VENDOR),
            "glExtensions" to extensions.sorted(),
        )
    } finally {
        // Never eglTerminate the default display: the UI renderer shares it.
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }
}

fun gpuInfo(ctx: Context): Outcome {
    val egl = safe { eglStrings() }
    val features = ctx.packageManager.systemAvailableFeatures
    fun featureVersion(name: String) = features.firstOrNull { it.name == name }?.version
    val vkVersion = featureVersion("android.hardware.vulkan.version")
    val vkText = vkVersion?.let { "${it shr 22}.${(it shr 12) and 0x3ff}" }
    val am = ctx.getSystemService(ActivityManager::class.java)
    val data = json {
        put("renderer", egl?.get("renderer"))
        put("vendor", egl?.get("vendor"))
        put("glVersion", egl?.get("glVersion"))
        put("glesFromConfig", am.deviceConfigurationInfo.glEsVersion)
        put("eglVersion", egl?.get("eglVersion"))
        put("eglVendor", egl?.get("eglVendor"))
        put("vulkanVersion", vkText)
        put("vulkanLevel", featureVersion("android.hardware.vulkan.level"))
        put("vulkanCompute", featureVersion("android.hardware.vulkan.compute"))
        put("glExtensions", egl?.get("glExtensions"))
    }
    val gles = am.deviceConfigurationInfo.glEsVersion
    val headline = listOfNotNull(
        egl?.get("renderer") as String?,
        "OpenGL ES $gles",
        vkText?.let { "Vulkan $it" },
    ).joinToString(" · ")
    return Outcome(if (egl == null) Status.WARN else Status.OK, headline, data)
}

fun memoryInfo(ctx: Context): Outcome {
    val am = ctx.getSystemService(ActivityManager::class.java)
    val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val meminfo = readFile("/proc/meminfo").orEmpty().lines().mapNotNull { line ->
        val parts = line.split(Regex("\\s+"))
        if (parts.size >= 2) parts[0].removeSuffix(":") to parts[1].toLongOrNull() else null
    }.toMap()
    val data = StatFs(Environment.getDataDirectory().path)
    val sm = ctx.getSystemService(StorageManager::class.java)
    val volumes = sm.storageVolumes.map { v ->
        json {
            put("description", v.getDescription(ctx))
            put("primary", v.isPrimary)
            put("removable", v.isRemovable)
            put("emulated", v.isEmulated)
            put("state", v.state)
        }
    }
    val out = json {
        put("totalRamBytes", mi.totalMem)
        put("availRamBytes", mi.availMem)
        put("lowMemoryThresholdBytes", mi.threshold)
        put("lowMemory", mi.lowMemory)
        put("lowRamDevice", am.isLowRamDevice)
        put("appHeapMB", am.memoryClass)
        put("appLargeHeapMB", am.largeMemoryClass)
        put("swapTotalKB", meminfo["SwapTotal"])
        put("swapFreeKB", meminfo["SwapFree"])
        put("dataTotalBytes", data.totalBytes)
        put("dataFreeBytes", data.availableBytes)
        put("volumes", volumes)
        put("sdCardPresent", sm.storageVolumes.any { it.isRemovable && it.state == Environment.MEDIA_MOUNTED })
    }
    val headline = "${gb(mi.totalMem)} RAM (${gb(mi.availMem)} free) · " +
        "${gb(data.totalBytes)} storage (${gb(data.availableBytes)} free)"
    val status = if (data.availableBytes < 2_000_000_000L) Status.WARN else Status.OK
    return Outcome(status, headline, out)
}

private fun hdrName(type: Int) = when (type) {
    1 -> "Dolby Vision"
    2 -> "HDR10"
    3 -> "HLG"
    4 -> "HDR10+"
    else -> "type $type"
}

fun displayInfo(ctx: Context): Outcome {
    val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val m = DisplayMetrics()
    @Suppress("DEPRECATION")
    display.getRealMetrics(m)
    val w = maxOf(m.widthPixels, m.heightPixels)
    val h = minOf(m.widthPixels, m.heightPixels)
    val inches = sqrt((m.widthPixels / m.xdpi).let { it * it } + (m.heightPixels / m.ydpi).let { it * it })
    val modes = display.supportedModes.map { "${it.physicalWidth}x${it.physicalHeight}@${fmt("%.0f", it.refreshRate)}" }
    @Suppress("DEPRECATION")
    val hdr = display.hdrCapabilities
    val cutout = safe { display.cutout }
    val cr = ctx.contentResolver
    val data = json {
        put("resolution", "${w}x$h")
        put("densityDpi", m.densityDpi)
        put("density", m.density)
        put("xdpi", m.xdpi)
        put("ydpi", m.ydpi)
        put("diagonalInches", round(inches, 2))
        put("refreshRateHz", display.refreshRate)
        put("modes", modes)
        put("hdrTypes", hdr?.supportedHdrTypes?.map { hdrName(it) })
        put("hdrMaxLuminance", hdr?.desiredMaxLuminance)
        put("wideColorGamut", display.isWideColorGamut)
        put("screenHdr", ctx.resources.configuration.isScreenHdr)
        put("cutoutRects", cutout?.boundingRects?.map { it.flattenToString() })
        put("safeInsetTop", cutout?.safeInsetTop)
        attempt("brightness") { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) }
        attempt("autoBrightness") {
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        }
        put("fontScale", ctx.resources.configuration.fontScale)
    }
    val headline = "${w}×$h · ${fmt("%.1f", inches)}\" · ${fmt("%.0f", display.refreshRate)} Hz · ${m.densityDpi} dpi" +
        if (hdr?.supportedHdrTypes?.isNotEmpty() == true) " · HDR" else ""
    return Outcome(Status.OK, headline, data)
}

private val POWER_SUPPLY_FILES = listOf(
    "battery/capacity", "battery/charge_full", "battery/charge_full_design", "battery/cycle_count",
    "battery/current_now", "battery/voltage_now", "battery/temp", "battery/health",
    "bms/charge_full", "bms/charge_full_design", "bms/cycle_count", "bms/battery_type",
    "usb/current_max", "usb/voltage_max", "usb/real_type",
)

fun batteryInfo(ctx: Context): Outcome {
    val intent = ContextCompat.registerReceiver(
        ctx, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    val bm = ctx.getSystemService(BatteryManager::class.java)
    fun prop(id: Int) = bm.getIntProperty(id).takeIf { it != Int.MIN_VALUE && it != 0 }
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val pct = if (level >= 0) level * 100 / scale else null
    val tempC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        ?.takeIf { it != Int.MIN_VALUE }?.div(10.0)
    val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
    val status = when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
        else -> "unknown"
    }
    val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
        else -> "unknown"
    }
    val plugged = when (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        0 -> "unplugged"
        else -> "other"
    }
    val sysfs = POWER_SUPPLY_FILES.mapNotNull { f -> readFile("/sys/class/power_supply/$f")?.let { f to it } }.toMap()
    val designUah = (sysfs["battery/charge_full_design"] ?: sysfs["bms/charge_full_design"])?.toLongOrNull()
    val fullUah = (sysfs["battery/charge_full"] ?: sysfs["bms/charge_full"])?.toLongOrNull()
    val designMah = designUah?.div(1000) ?: safe {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val profile = cls.getConstructor(Context::class.java).newInstance(ctx)
        (cls.getMethod("getBatteryCapacity").invoke(profile) as Double).toLong()
    }
    val healthPct = if (designUah != null && fullUah != null && designUah > 0) fullUah * 100 / designUah else null
    val cycles = intent?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it >= 0 }
        ?: (sysfs["battery/cycle_count"] ?: sysfs["bms/cycle_count"])?.toIntOrNull()
    val data = json {
        put("percent", pct)
        put("status", status)
        put("plugged", plugged)
        put("health", health)
        put("temperatureC", tempC)
        put("voltageMv", voltage)
        put("technology", intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY))
        put("currentNowUa", prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
        put("currentAverageUa", prop(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE))
        put("chargeCounterUah", prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
        put("energyCounterNwh", bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER).takeIf { it != Long.MIN_VALUE && it != 0L })
        put("chargeTimeRemainingMs", bm.computeChargeTimeRemaining().takeIf { it >= 0 })
        put("designCapacityMah", designMah)
        put("fullCapacityMah", fullUah?.div(1000))
        put("healthPercent", healthPct)
        put("cycleCount", cycles)
        put("sysfsReadable", sysfs)
    }
    val headline = listOfNotNull(
        pct?.let { "$it%" }, status, tempC?.let { fmt("%.1f °C", it) }, voltage?.let { "$it mV" },
        designMah?.let { "$it mAh" }, healthPct?.let { "health $it%" } ?: "health $health",
        cycles?.let { "$it cycles" },
    ).joinToString(" · ")
    val verdict = if (health == "good" || health == "unknown") Status.OK else Status.WARN
    return Outcome(verdict, headline, data)
}

fun thermalInfo(ctx: Context): Outcome {
    val pm = ctx.getSystemService(PowerManager::class.java)
    val statusName = when (pm.currentThermalStatus) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }
    val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
        ?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 0 }
        ?.mapNotNull { dir ->
            val raw = readFile("$dir/temp")?.toLongOrNull() ?: return@mapNotNull null
            val c = if (raw > 1000 || raw < -1000) raw / 1000.0 else raw.toDouble()
            (readFile("$dir/type") ?: dir.name) to c
        }.orEmpty()
    // Ignore implausible sentinel values some kernels report for idle sensors.
    val hottest = zones.filter { it.second in -40.0..150.0 }.maxByOrNull { it.second }
    // 0 = cool, 1 = throttling now; NaN when the phone doesn't report it.
    val headroom = if (Build.VERSION.SDK_INT >= 30) safe { pm.getThermalHeadroom(10) }?.takeIf { it.isFinite() } else null
    val data = json {
        put("status", statusName)
        put("headroom10s", headroom)
        put("zonesReadable", zones.size)
        put("zones", JSONArray().also { a -> zones.forEach { (t, c) -> a.put(json { put("type", t); put("celsius", c) }) } })
    }
    val headline = listOfNotNull(
        "throttling: $statusName",
        headroom?.let { fmt("headroom %.2f", it) },
        if (zones.isEmpty()) "temperature sensors not readable" else
            "${zones.size} sensors readable · hottest ${fmt("%.0f °C", hottest?.second ?: 0.0)} (${hottest?.first})",
    ).joinToString(" · ")
    val status = when {
        pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> Status.WARN
        zones.isEmpty() && headroom == null -> Status.WARN
        else -> Status.OK
    }
    return Outcome(status, headline, data)
}
