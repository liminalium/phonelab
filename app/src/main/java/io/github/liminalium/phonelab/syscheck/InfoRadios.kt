package io.github.liminalium.phonelab.syscheck

import android.Manifest
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.hardware.biometrics.BiometricManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.telephony.TelephonyManager
import android.webkit.WebView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.net.Inet6Address
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory

// ---------- Radios ----------

@Suppress("DEPRECATION")
fun radiosInfo(ctx: Context): Outcome {
    val pm = ctx.packageManager
    fun has(feature: String) = pm.hasSystemFeature(feature)
    val wifi = ctx.getSystemService(WifiManager::class.java)
    val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    val tm = ctx.getSystemService(TelephonyManager::class.java)
    val lm = ctx.getSystemService(LocationManager::class.java)
    val ir = ctx.getSystemService(ConsumerIrManager::class.java)
    val nfc = has(PackageManager.FEATURE_NFC)
    val uwb = has("android.hardware.uwb")
    val irPresent = has(PackageManager.FEATURE_CONSUMER_IR) && ir?.hasIrEmitter() == true
    val ble = has(PackageManager.FEATURE_BLUETOOTH_LE)
    val band5 = wifi?.is5GHzBandSupported == true
    val band6 = Build.VERSION.SDK_INT >= 30 && wifi?.is6GHzBandSupported == true
    val data = json {
        put("nfc", nfc)
        put("nfcCardEmulation", has(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
        put("uwb", uwb)
        put("consumerIr", irPresent)
        if (irPresent) attempt("irCarrierFrequencies") { ir?.carrierFrequencies?.map { "${it.minFrequency}-${it.maxFrequency}" } }
        put("bluetooth", has(PackageManager.FEATURE_BLUETOOTH))
        put("bluetoothLe", ble)
        put("wifi", has(PackageManager.FEATURE_WIFI))
        put("wifiDirect", has(PackageManager.FEATURE_WIFI_DIRECT))
        put("wifiAware", has(PackageManager.FEATURE_WIFI_AWARE))
        put("wifiRtt", has(PackageManager.FEATURE_WIFI_RTT))
        put("wifiPasspoint", has(PackageManager.FEATURE_WIFI_PASSPOINT))
        put("telephony", has(PackageManager.FEATURE_TELEPHONY))
        put("usbHost", has(PackageManager.FEATURE_USB_HOST))
        put("usbAccessory", has(PackageManager.FEATURE_USB_ACCESSORY))
        put("gps", has(PackageManager.FEATURE_LOCATION_GPS))
        put("networkLocation", has(PackageManager.FEATURE_LOCATION_NETWORK))
        put("wifiDetails", wifi?.let { w ->
            json {
                put("enabled", w.isWifiEnabled)
                put("band5GHz", band5)
                put("band6GHz", band6)
                attempt("wpa3") { w.isWpa3SaeSupported }
                attempt("enhancedOpen") { w.isEnhancedOpenSupported }
                attempt("p2p") { w.isP2pSupported }
                attempt("rttToAccessPoint") { w.isDeviceToApRttSupported }
                attempt("scanAlwaysAvailable") { w.isScanAlwaysAvailable }
                if (Build.VERSION.SDK_INT >= 30) {
                    attempt("staApConcurrency") { w.isStaApConcurrencySupported }
                    attempt("wifi6") { w.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX) }
                }
            }
        })
        put("bluetoothDetails", bt?.let { a ->
            json {
                attempt("enabled") { a.isEnabled }
                attempt("le2MPhy") { a.isLe2MPhySupported }
                attempt("leCodedPhy") { a.isLeCodedPhySupported }
                attempt("leExtendedAdvertising") { a.isLeExtendedAdvertisingSupported }
                attempt("lePeriodicAdvertising") { a.isLePeriodicAdvertisingSupported }
                attempt("multipleAdvertisement") { a.isMultipleAdvertisementSupported }
                attempt("offloadedFiltering") { a.isOffloadedFilteringSupported }
                attempt("offloadedScanBatching") { a.isOffloadedScanBatchingSupported }
                attempt("leMaxAdvertisingDataLength") { a.leMaximumAdvertisingDataLength }
            }
        })
        put("telephonyDetails", tm?.let { t ->
            json {
                put("phoneType", when (t.phoneType) {
                    TelephonyManager.PHONE_TYPE_GSM -> "GSM"
                    TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
                    TelephonyManager.PHONE_TYPE_SIP -> "SIP"
                    else -> "none"
                })
                put("simState", when (t.simState) {
                    TelephonyManager.SIM_STATE_READY -> "ready"
                    TelephonyManager.SIM_STATE_ABSENT -> "absent"
                    else -> "state ${t.simState}"
                })
                attempt("operator") { t.networkOperatorName }
                attempt("simOperator") { t.simOperatorName }
                attempt("simCountry") { t.simCountryIso }
                attempt("modemCount") { if (Build.VERSION.SDK_INT >= 30) t.activeModemCount else t.phoneCount }
                put("voiceCapable", t.isVoiceCapable)
                put("smsCapable", t.isSmsCapable)
            }
        })
        put("gnss", json {
            attempt("hardwareModel") { lm.gnssHardwareModelName }
            attempt("hardwareYear") { lm.gnssYearOfHardware }
            put("locationEnabled", lm.isLocationEnabled)
            put("providers", lm.allProviders)
            if (Build.VERSION.SDK_INT >= 31) {
                attempt("capabilities") {
                    val c = lm.gnssCapabilities
                    json {
                        put("measurements", c.hasMeasurements())
                        put("navigationMessages", c.hasNavigationMessages())
                        put("antennaInfo", c.hasAntennaInfo())
                        put("measurementCorrections", c.hasMeasurementCorrections())
                    }
                }
            }
        })
    }
    val wifiText = "Wi-Fi " + listOfNotNull("2.4", "5".takeIf { band5 }, "6".takeIf { band6 }).joinToString("/") + " GHz"
    val headline = listOf(
        wifiText,
        "BLE ${yesNo(ble)}",
        "NFC ${yesNo(nfc)}",
        "IR ${yesNo(irPresent)}",
        "UWB ${yesNo(uwb)}",
        "GNSS ${safe { lm.gnssHardwareModelName } ?: "?"}",
    ).joinToString(" · ")
    return Outcome(Status.OK, headline, data)
}

// ---------- Network ----------

@Suppress("DEPRECATION")
fun networkInfo(ctx: Context): Outcome {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val net = cm.activeNetwork
    val caps = net?.let { cm.getNetworkCapabilities(it) }
    val link = net?.let { cm.getLinkProperties(it) }
    val transports = listOf(
        NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
        NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
        NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
        NetworkCapabilities.TRANSPORT_VPN to "VPN",
        NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth",
    ).filter { caps?.hasTransport(it.first) == true }.map { it.second }
    val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    val down = caps?.linkDownstreamBandwidthKbps
    val data = json {
        put("connected", net != null)
        put("transports", transports)
        put("validated", validated)
        put("notMetered", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        put("metered", cm.isActiveNetworkMetered)
        put("downKbpsEstimate", down)
        put("upKbpsEstimate", caps?.linkUpstreamBandwidthKbps)
        put("interface", link?.interfaceName)
        put("mtu", link?.mtu)
        put("privateDnsActive", link?.isPrivateDnsActive)
        put("dnsServerCount", link?.dnsServers?.size)
        put("globalIpv6", link?.linkAddresses?.any { it.address is Inet6Address && !it.address.isLinkLocalAddress })
        put("networkCount", cm.allNetworks.size)
    }
    val headline = if (net == null) "offline" else
        "${transports.joinToString("+")} · internet ${yesNo(validated)}" + (down?.let { " · ~${it / 1000} Mbps estimated" } ?: "")
    return Outcome(if (validated) Status.OK else Status.WARN, headline, data)
}

// ---------- Security ----------

private fun keystoreProbe(strongBox: Boolean): Pair<Boolean, String> {
    val alias = "phonelab-syscheck-" + if (strongBox) "strongbox" else "tee"
    try {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        gen.init(spec)
        val key = gen.generateKey()
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return if (Build.VERSION.SDK_INT >= 31) {
            val level = when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                else -> "level ${info.securityLevel}"
            }
            (level == "StrongBox" || level == "TEE") to level
        } else {
            @Suppress("DEPRECATION")
            val hw = info.isInsideSecureHardware
            hw to if (hw) (if (strongBox) "StrongBox" else "TEE") else "software"
        }
    } catch (t: Throwable) {
        return false to t.javaClass.simpleName
    } finally {
        safe { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }
}

@Suppress("DEPRECATION")
fun securityInfo(ctx: Context): Outcome {
    val pm = ctx.packageManager
    val km = ctx.getSystemService(KeyguardManager::class.java)
    val bio = ctx.getSystemService(BiometricManager::class.java)
    val bioCode = safe {
        if (Build.VERSION.SDK_INT >= 30) bio.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        else bio.canAuthenticate()
    }
    val bioText = when (bioCode) {
        BiometricManager.BIOMETRIC_SUCCESS -> "ready"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "none enrolled"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "no hardware"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "unavailable"
        null -> "unknown"
        else -> "code $bioCode"
    }
    val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    val encryption = when (dpm.storageEncryptionStatus) {
        DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> "file-based"
        DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE -> "full-disk"
        DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> "default key"
        DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> "off"
        DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED -> "unsupported"
        else -> "status ${dpm.storageEncryptionStatus}"
    }
    val (teeOk, teeLevel) = keystoreProbe(strongBox = false)
    val strongBoxFeature = pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    val (strongBoxOk, _) = if (strongBoxFeature) keystoreProbe(strongBox = true) else (false to "absent")
    val suFound = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/app/Superuser.apk")
        .any { File(it).exists() }
    val data = json {
        put("screenLockSet", km.isDeviceSecure)
        put("biometrics", bioText)
        put("fingerprint", pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
        put("face", pm.hasSystemFeature(PackageManager.FEATURE_FACE))
        put("storageEncryption", encryption)
        put("keystoreHardwareBacked", teeOk)
        put("keystoreLevel", teeLevel)
        put("strongBoxFeature", strongBoxFeature)
        put("strongBoxWorks", strongBoxOk)
        put("verifiedBootState", SystemProps["ro.boot.verifiedbootstate"])
        put("bootloaderLocked", SystemProps["ro.boot.flash.locked"]?.let { it == "1" })
        put("buildTags", Build.TAGS)
        put("suBinaryFound", suFound)
    }
    val headline = listOf(
        "screen lock ${yesNo(km.isDeviceSecure)}",
        "biometrics $bioText",
        "keys in $teeLevel",
        "StrongBox ${yesNo(strongBoxOk)}",
        "$encryption encryption",
    ).joinToString(" · ")
    return Outcome(if (km.isDeviceSecure && teeOk) Status.OK else Status.WARN, headline, data)
}

// ---------- System features ----------

fun featuresInfo(ctx: Context): Outcome {
    val pm = ctx.packageManager
    val features = pm.systemAvailableFeatures
        .mapNotNull { f -> f.name?.let { if (f.version != 0) "$it=${f.version}" else it } }
        .sorted()
    val data = json {
        put("count", features.size)
        put("features", features)
        put("sharedLibraries", pm.systemSharedLibraryNames?.sorted())
    }
    return Outcome(Status.OK, "${features.size} system features", data)
}

// ---------- Related apps ----------

private val PACKAGES = linkedMapOf(
    "playServices" to "com.google.android.gms",
    "arcore" to "com.google.ar.core",
    "healthConnect" to "com.google.android.apps.healthdata",
    "termux" to "com.termux",
    "termuxApi" to "com.termux.api",
    "obtainium" to "dev.imranr.obtainium",
    "obtainiumFdroid" to "dev.imranr.obtainium.fdroid",
    "tailscale" to "com.tailscale.ipn",
    "miuiSecurityCenter" to "com.miui.securitycenter",
)

@Suppress("DEPRECATION")
fun packagesInfo(ctx: Context): Outcome {
    val pm = ctx.packageManager
    val found = PACKAGES.mapValues { (_, pkg) ->
        safe { pm.getPackageInfo(pkg, 0) }
    }
    val data = json {
        found.forEach { (key, info) ->
            put(key, json {
                put("installed", info != null)
                if (info != null) {
                    put("version", info.versionName)
                    put("versionCode", info.longVersionCode)
                }
            })
        }
        attempt("webView") { WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } }
    }
    fun mark(label: String, vararg keys: String) = "$label ${yesNo(keys.any { found[it] != null })}"
    val headline = listOf(
        mark("Play services", "playServices"),
        mark("ARCore", "arcore"),
        mark("Termux", "termux"),
        mark("Obtainium", "obtainium", "obtainiumFdroid"),
        mark("Tailscale", "tailscale"),
    ).joinToString(" · ")
    return Outcome(Status.OK, headline, data)
}

// ---------- Recorder readiness ----------

/** MIUI keeps some permissions as private AppOps; reading them is best effort. */
private fun miuiOpAllowed(ctx: Context, op: Int): Boolean? = safe {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    val check = AppOpsManager::class.java.getMethod(
        "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
    )
    (check.invoke(ops, op, Process.myUid(), ctx.packageName) as Int) == AppOpsManager.MODE_ALLOWED
}

fun readinessInfo(ctx: Context): Outcome {
    val overlay = Settings.canDrawOverlays(ctx)
    val unrestricted = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    val notifications = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    val locationOn = ctx.getSystemService(LocationManager::class.java).isLocationEnabled
    val wifiOn = ctx.getSystemService(WifiManager::class.java)?.isWifiEnabled
    val btOn = safe { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val miui = miuiVersion() != null
    val miuiBackgroundPopups = if (miui) miuiOpAllowed(ctx, 10021) else null
    val miuiAutostart = if (miui) miuiOpAllowed(ctx, 10008) else null
    val needs = buildList {
        if (!overlay) add("display over other apps")
        if (!unrestricted) add("battery unrestricted")
        if (!notifications) add("notifications")
        if (!granted(Manifest.permission.CAMERA)) add("camera")
        if (!granted(Manifest.permission.RECORD_AUDIO)) add("microphone")
        if (miuiBackgroundPopups == false) add("MIUI background pop-ups")
    }
    val data = json {
        put("overlay", overlay)
        put("batteryUnrestricted", unrestricted)
        put("notifications", notifications)
        put("cameraPermission", granted(Manifest.permission.CAMERA))
        put("microphonePermission", granted(Manifest.permission.RECORD_AUDIO))
        put("locationPermission", granted(Manifest.permission.ACCESS_FINE_LOCATION))
        put("locationOn", locationOn)
        put("wifiOn", wifiOn)
        put("bluetoothOn", btOn)
        put("miui", miui)
        put("miuiBackgroundPopups", miuiBackgroundPopups)
        put("miuiAutostart", miuiAutostart)
        put("recorderNeeds", needs)
    }
    val headline = if (needs.isEmpty()) "ready for the screen recorder" else "recorder still needs: ${needs.joinToString(", ")}"
    return Outcome(if (needs.isEmpty()) Status.OK else Status.WARN, headline, data)
}
