package io.github.liminalium.phonelab.syscheck

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import android.bluetooth.le.ScanResult as BleScanResult

// ---------- GNSS ----------

private fun constellationName(type: Int) = when (type) {
    GnssStatus.CONSTELLATION_GPS -> "GPS"
    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
    GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
    GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
    GnssStatus.CONSTELLATION_IRNSS -> "NavIC"
    else -> "other"
}

private fun measurementStatus(status: Int?) = when (status) {
    GnssMeasurementsEvent.Callback.STATUS_NOT_SUPPORTED -> "not supported"
    GnssMeasurementsEvent.Callback.STATUS_READY -> "supported"
    GnssMeasurementsEvent.Callback.STATUS_LOCATION_DISABLED -> "location disabled"
    GnssMeasurementsEvent.Callback.STATUS_NOT_ALLOWED -> "not allowed"
    null -> "no answer"
    else -> "status $status"
}

/**
 * Listens to the GNSS chip for up to 30 s: satellites per constellation, carrier
 * frequencies (dual-frequency L5/E5a shows up near 1176 MHz) and raw measurements.
 * Coordinates are never saved.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
suspend fun gnssTest(ctx: Context): Outcome = withContext(Dispatchers.Main) {
    val lm = ctx.getSystemService(LocationManager::class.java)
    if (!lm.isLocationEnabled) {
        return@withContext Outcome(Status.SKIPPED, "Location is off: turn it on and run again", JSONObject())
    }
    if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        return@withContext Outcome(Status.SKIPPED, "GPS is off: location mode is network-only", JSONObject())
    }
    val handler = Handler(Looper.getMainLooper())
    val start = SystemClock.elapsedRealtime()
    var ttffMs: Int? = null
    var fix: Location? = null
    var fixAfterMs: Long? = null
    var maxInView = 0
    var maxUsed = 0
    var bestCn0 = 0f
    val perConstellation = sortedMapOf<String, Int>()
    val carrierMHz = sortedSetOf<Double>()
    var measStatus: Int? = null
    var measEvents = 0
    var maxPerEvent = 0
    var carrierPhase = false
    var fullBias = false

    val statusCallback = object : GnssStatus.Callback() {
        override fun onFirstFix(ttffMillis: Int) {
            ttffMs = ttffMillis
        }

        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val counts = mutableMapOf<String, Int>()
            var used = 0
            for (i in 0 until status.satelliteCount) {
                val name = constellationName(status.getConstellationType(i))
                counts[name] = (counts[name] ?: 0) + 1
                if (status.usedInFix(i)) used++
                bestCn0 = maxOf(bestCn0, status.getCn0DbHz(i))
                if (status.hasCarrierFrequencyHz(i)) carrierMHz += round(status.getCarrierFrequencyHz(i) / 1e6f, 2)
            }
            maxInView = maxOf(maxInView, status.satelliteCount)
            maxUsed = maxOf(maxUsed, used)
            counts.forEach { (k, v) -> perConstellation[k] = maxOf(perConstellation[k] ?: 0, v) }
        }
    }
    val measurementCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
            measEvents++
            maxPerEvent = maxOf(maxPerEvent, eventArgs.measurements.size)
            if (eventArgs.clock.hasFullBiasNanos()) fullBias = true
            for (m in eventArgs.measurements) {
                if (m.hasCarrierFrequencyHz()) carrierMHz += round(m.carrierFrequencyHz / 1e6f, 2)
                if ((m.accumulatedDeltaRangeState and GnssMeasurement.ADR_STATE_VALID) != 0) carrierPhase = true
            }
        }

        override fun onStatusChanged(status: Int) {
            measStatus = status
        }
    }
    // Implements every method: Android 10 calls the ones that later became defaults.
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (fix == null) {
                fix = location
                fixAfterMs = SystemClock.elapsedRealtime() - start
            }
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    lm.registerGnssStatusCallback(statusCallback, handler)
    lm.registerGnssMeasurementsCallback(measurementCallback, handler)
    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
    try {
        while (SystemClock.elapsedRealtime() - start < 30_000) {
            delay(250)
            val after = fixAfterMs
            if (after != null && SystemClock.elapsedRealtime() - start > after + 4000) break
        }
    } finally {
        lm.removeUpdates(listener)
        lm.unregisterGnssStatusCallback(statusCallback)
        lm.unregisterGnssMeasurementsCallback(measurementCallback)
    }
    val f = fix
    val dualFrequency = carrierMHz.any { it < 1300.0 }
    val raw = measurementStatus(measStatus)
    val data = json {
        put("fix", f != null)
        put("fixAfterSeconds", fixAfterMs?.div(1000.0))
        put("ttffSeconds", ttffMs?.div(1000.0))
        put("accuracyM", f?.accuracy)
        put("hasAltitude", f?.hasAltitude())
        put("satellitesInView", maxInView)
        put("satellitesUsed", maxUsed)
        put("bestCn0DbHz", bestCn0)
        put("constellations", perConstellation)
        put("carrierFrequenciesMHz", carrierMHz.toList())
        put("dualFrequency", dualFrequency)
        put("rawMeasurements", raw)
        put("rawEvents", measEvents)
        put("maxMeasurementsPerEvent", maxPerEvent)
        put("carrierPhase", carrierPhase)
        put("fullClockBias", fullBias)
        put("note", "Coordinates are not saved.")
    }
    val sats = if (perConstellation.isEmpty()) "no satellites seen" else
        "$maxInView satellites (" + perConstellation.entries.joinToString(", ") { "${it.key} ${it.value}" } + ")"
    val fixText = fixAfterMs?.let { fmt("fix in %.0f s", it / 1000.0) } ?: "no fix in 30 s (try near a window)"
    val headline = "$fixText · $sats · raw $raw · dual-frequency ${yesNo(dualFrequency)}"
    val status = when {
        f != null -> Status.OK
        maxInView > 0 -> Status.WARN
        else -> Status.FAIL
    }
    Outcome(status, headline, data)
}

// ---------- Wi-Fi ----------

private fun band(freq: Int) = when {
    freq < 2500 -> "2.4 GHz"
    freq < 5900 -> "5 GHz"
    freq < 7200 -> "6 GHz"
    else -> "60 GHz"
}

private fun channelWidth(w: Int) = when (w) {
    0 -> "20 MHz"
    1 -> "40 MHz"
    2 -> "80 MHz"
    3 -> "160 MHz"
    4 -> "80+80 MHz"
    5 -> "320 MHz"
    else -> "width $w"
}

/** Runs one Wi-Fi scan. Network names and addresses are not saved. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
suspend fun wifiScanTest(ctx: Context): Outcome {
    val wm = ctx.getSystemService(WifiManager::class.java)
        ?: return Outcome(Status.SKIPPED, "no Wi-Fi", JSONObject())
    if (!ctx.getSystemService(LocationManager::class.java).isLocationEnabled) {
        return Outcome(Status.SKIPPED, "Location is off: Android requires it for Wi-Fi scans", JSONObject())
    }
    if (!wm.isWifiEnabled && !wm.isScanAlwaysAvailable) {
        return Outcome(Status.SKIPPED, "Wi-Fi is off", JSONObject())
    }
    val done = CompletableDeferred<Boolean>()
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            done.complete(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
        }
    }
    ContextCompat.registerReceiver(
        ctx, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED,
    )
    val started = safe { wm.startScan() } == true
    val fresh = try {
        if (started) withTimeoutOrNull(12_000) { done.await() } == true else false
    } finally {
        ctx.unregisterReceiver(receiver)
    }
    val results = wm.scanResults.orEmpty()
    val bands = results.groupingBy { band(it.frequency) }.eachCount().toSortedMap()
    val strongest = results.maxOfOrNull { it.level }
    val data = json {
        put("networks", results.size)
        put("freshScan", fresh)
        put("throttled", !started)
        put("bands", bands)
        put("channelWidths", results.groupingBy { channelWidth(it.channelWidth) }.eachCount())
        put("strongestDbm", strongest)
        put("rttResponders", results.count { it.is80211mcResponder })
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            put("standards", results.groupingBy { "802.11 std ${it.wifiStandard}" }.eachCount())
        }
    }
    val bandText = bands.entries.joinToString(", ") { "${it.key} ${it.value}" }
    val headline = if (results.isEmpty()) "no networks found" else
        "${results.size} networks ($bandText) · strongest $strongest dBm" + if (!fresh) " · cached (Android limits scans)" else ""
    return Outcome(if (results.isEmpty()) Status.WARN else Status.OK, headline, data)
}

// ---------- Cellular ----------

private fun Int.avail(): Int? = takeIf { it != Int.MAX_VALUE && it != Int.MIN_VALUE }

private fun describeCell(c: CellInfo): JSONObject = json {
    put("registered", c.isRegistered)
    when (c) {
        is CellInfoLte -> {
            val s = c.cellSignalStrength
            put("type", "LTE")
            put("dbm", s.dbm.avail())
            put("rsrp", s.rsrp.avail())
            put("rsrq", s.rsrq.avail())
            put("rssnr", s.rssnr.avail())
            put("level", s.level)
            put("earfcn", c.cellIdentity.earfcn.avail())
            put("bandwidthKhz", c.cellIdentity.bandwidth.avail())
        }
        is CellInfoNr -> {
            val s = c.cellSignalStrength as CellSignalStrengthNr
            put("type", "5G NR")
            put("dbm", s.dbm.avail())
            put("ssRsrp", s.ssRsrp.avail())
            put("level", s.level)
            put("nrarfcn", (c.cellIdentity as CellIdentityNr).nrarfcn.avail())
        }
        is CellInfoWcdma -> {
            put("type", "3G WCDMA")
            put("dbm", c.cellSignalStrength.dbm.avail())
            put("level", c.cellSignalStrength.level)
            put("uarfcn", c.cellIdentity.uarfcn.avail())
        }
        is CellInfoGsm -> {
            put("type", "2G GSM")
            put("dbm", c.cellSignalStrength.dbm.avail())
            put("level", c.cellSignalStrength.level)
            put("arfcn", c.cellIdentity.arfcn.avail())
        }
        is CellInfoTdscdma -> {
            put("type", "TD-SCDMA")
            put("dbm", c.cellSignalStrength.dbm.avail())
            put("level", c.cellSignalStrength.level)
        }
        is CellInfoCdma -> {
            put("type", "CDMA")
            put("dbm", c.cellSignalStrength.dbm.avail())
            put("level", c.cellSignalStrength.level)
        }
        else -> put("type", c.javaClass.simpleName)
    }
}

/** Reads the serving and neighbouring cells. Cell IDs are not saved. */
@SuppressLint("MissingPermission")
suspend fun cellTest(ctx: Context): Outcome {
    if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
        return Outcome(Status.SKIPPED, "no cellular radio", JSONObject())
    }
    val tm = ctx.getSystemService(TelephonyManager::class.java)
        ?: return Outcome(Status.SKIPPED, "no cellular radio", JSONObject())
    val fresh = withTimeoutOrNull(5000) {
        suspendCancellableCoroutine<List<CellInfo>?> { cont ->
            try {
                tm.requestCellInfoUpdate(ctx.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: List<CellInfo>) {
                        if (cont.isActive) cont.resume(cellInfo)
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
    val cells = fresh ?: safe { tm.allCellInfo }.orEmpty()
    val described = cells.map { describeCell(it) }
    val serving = described.firstOrNull { it.optBoolean("registered") }
    val data = json {
        put("fresh", fresh != null)
        put("cellCount", cells.size)
        put("cells", described)
    }
    val headline = when {
        cells.isEmpty() -> "no cell info (no SIM, airplane mode, or location off)"
        serving == null -> "${cells.size} cells seen, none registered"
        else -> "${serving.optString("type")} ${serving.opt("dbm")} dBm (level ${serving.opt("level")}/4)" +
            " · ${cells.size - 1} neighbour cells"
    }
    return Outcome(if (serving != null) Status.OK else Status.WARN, headline, data)
}

// ---------- Bluetooth LE ----------

private val COMPANIES = mapOf(
    0x004C to "Apple",
    0x0006 to "Microsoft",
    0x0075 to "Samsung",
    0x00E0 to "Google",
    0x038F to "Xiaomi",
    0x0087 to "Garmin",
)

/** Scans for BLE advertisers for 6 s. Device addresses stay in memory and are not saved. */
@SuppressLint("MissingPermission")
suspend fun bleScanTest(ctx: Context): Outcome {
    val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        ?: return Outcome(Status.SKIPPED, "no Bluetooth", JSONObject())
    if (safe { adapter.isEnabled } != true) return Outcome(Status.SKIPPED, "Bluetooth is off", JSONObject())
    val scanner = adapter.bluetoothLeScanner
        ?: return Outcome(Status.SKIPPED, "BLE scanner unavailable", JSONObject())
    val rssi = ConcurrentHashMap<String, Int>()
    val maker = ConcurrentHashMap<String, String>()
    var failure: Int? = null
    fun record(r: BleScanResult) {
        val addr = r.device.address
        rssi.merge(addr, r.rssi) { a, b -> maxOf(a, b) }
        val ids = r.scanRecord?.manufacturerSpecificData
        if (ids != null && ids.size() > 0) {
            val id = ids.keyAt(0)
            maker[addr] = COMPANIES[id] ?: "0x%04X".format(id)
        }
    }
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: BleScanResult) = record(result)
        override fun onBatchScanResults(results: MutableList<BleScanResult>) = results.forEach { record(it) }
        override fun onScanFailed(errorCode: Int) {
            failure = errorCode
        }
    }
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    try {
        scanner.startScan(null, settings, callback)
        delay(6000)
    } catch (e: SecurityException) {
        return Outcome(Status.SKIPPED, "Bluetooth scan permission missing", JSONObject())
    } finally {
        safe { scanner.stopScan(callback) }
    }
    val levels = rssi.values.sorted()
    val makers = maker.values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
    val data = json {
        put("devices", levels.size)
        put("strongestDbm", levels.lastOrNull())
        put("medianDbm", levels.getOrNull(levels.size / 2))
        put("manufacturers", makers.associate { it.key to it.value })
        put("scanError", failure)
    }
    val headline = when {
        failure != null -> "scan failed (error $failure)"
        levels.isEmpty() -> "no BLE devices nearby"
        else -> "${levels.size} devices · strongest ${levels.last()} dBm" +
            if (makers.isNotEmpty()) " · " + makers.take(3).joinToString(", ") { "${it.key} ${it.value}" } else ""
    }
    val status = when {
        failure != null -> Status.FAIL
        levels.isEmpty() -> Status.WARN
        else -> Status.OK
    }
    return Outcome(status, headline, data)
}
