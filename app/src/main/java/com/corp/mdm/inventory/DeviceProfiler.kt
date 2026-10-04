/*
 * [context: Corporate MDM fleet-inventory agent, Android, ARM/ARM64/x86]
 * DeviceProfiler.kt — collects a full hardware/software inventory snapshot.
 * Pure Kotlin + Android SDK only. No Gson, no Retrofit.
 */

package com.corp.mdm.inventory

import android.content.Context
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.location.LocationManager
import android.location.Location
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID

data class DeviceProfile(
    val deviceId: String,
    val androidId: String,
    val model: String,
    val manufacturer: String,
    val brand: String,
    val product: String,
    val hardware: String,
    val cpuAbi: String,
    val fingerprint: String,
    val androidVersion: String,
    val sdkVersion: Int,
    val buildId: String,
    val kernelVersion: String,
    val isRooted: Boolean,
    val ipAddressWifi: String?,
    val ipAddressMobile: String?,
    val macAddress: String?,
    val ssid: String?,
    val networkType: String,
    val simOperator: String?,
    val simCountryIso: String?,
    val phoneNumber: String?,
    val imei: String?,
    val simState: String,
    val internalStorageTotal: Long,
    val internalStorageFree: Long,
    val externalStorageTotal: Long?,
    val externalStorageFree: Long?,
    val batteryLevel: Int,
    val batteryCharging: Boolean,
    val batteryHealth: String,
    val installedApps: List<AppInfo>,
    val lastLatitude: Double?,
    val lastLongitude: Double?,
    val lastLocationAccuracy: Float?,
    val lastLocationTime: Long?,
    val collectedAt: Long
)

data class AppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val installedAt: Long,
    val isSystemApp: Boolean
)

class DeviceProfiler(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "mdm_device_identity"
        private const val KEY_DEVICE_ID = "device_id"

        private val SU_PATHS = arrayOf(
            "/su/bin/su", "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/system/sbin/su", "/vendor/bin/su", "/data/local/su",
            "/data/local/bin/su", "/data/local/xbin/su"
        )
    }

    fun collect(): DeviceProfile {
        val (intTotal, intFree) = internalStorage()
        val (extTotal, extFree) = externalStorage()
        val loc = lastKnownLocation()

        return DeviceProfile(
            deviceId = getDeviceId(),
            androidId = androidId(),
            model = Build.MODEL ?: "",
            manufacturer = Build.MANUFACTURER ?: "",
            brand = Build.BRAND ?: "",
            product = Build.PRODUCT ?: "",
            hardware = Build.HARDWARE ?: "",
            cpuAbi = primaryAbi(),
            fingerprint = Build.FINGERPRINT ?: "",
            androidVersion = Build.VERSION.RELEASE ?: "",
            sdkVersion = Build.VERSION.SDK_INT,
            buildId = Build.ID ?: "",
            kernelVersion = System.getProperty("os.version") ?: "",
            isRooted = isRooted(),
            ipAddressWifi = wifiIpAddress(),
            ipAddressMobile = mobileIpAddress(),
            macAddress = wifiMacAddress(),
            ssid = currentSsid(),
            networkType = networkType(),
            simOperator = simOperator(),
            simCountryIso = simCountryIso(),
            phoneNumber = phoneNumber(),
            imei = imei(),
            simState = simState(),
            internalStorageTotal = intTotal,
            internalStorageFree = intFree,
            externalStorageTotal = extTotal,
            externalStorageFree = extFree,
            batteryLevel = batteryLevel(),
            batteryCharging = batteryCharging(),
            batteryHealth = batteryHealth(),
            installedApps = installedApps(),
            lastLatitude = loc?.latitude,
            lastLongitude = loc?.longitude,
            lastLocationAccuracy = loc?.accuracy,
            lastLocationTime = loc?.time,
            collectedAt = System.currentTimeMillis()
        )
    }

    fun getDeviceId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun toJson(profile: DeviceProfile): String {
        val root = JSONObject()
        root.put("deviceId", profile.deviceId)
        root.put("androidId", profile.androidId)
        root.put("model", profile.model)
        root.put("manufacturer", profile.manufacturer)
        root.put("brand", profile.brand)
        root.put("product", profile.product)
        root.put("hardware", profile.hardware)
        root.put("cpuAbi", profile.cpuAbi)
        root.put("fingerprint", profile.fingerprint)
        root.put("androidVersion", profile.androidVersion)
        root.put("sdkVersion", profile.sdkVersion)
        root.put("buildId", profile.buildId)
        root.put("kernelVersion", profile.kernelVersion)
        root.put("isRooted", profile.isRooted)
        root.put("ipAddressWifi", profile.ipAddressWifi ?: JSONObject.NULL)
        root.put("ipAddressMobile", profile.ipAddressMobile ?: JSONObject.NULL)
        root.put("macAddress", profile.macAddress ?: JSONObject.NULL)
        root.put("ssid", profile.ssid ?: JSONObject.NULL)
        root.put("networkType", profile.networkType)
        root.put("simOperator", profile.simOperator ?: JSONObject.NULL)
        root.put("simCountryIso", profile.simCountryIso ?: JSONObject.NULL)
        root.put("phoneNumber", profile.phoneNumber ?: JSONObject.NULL)
        root.put("imei", profile.imei ?: JSONObject.NULL)
        root.put("simState", profile.simState)
        root.put("internalStorageTotal", profile.internalStorageTotal)
        root.put("internalStorageFree", profile.internalStorageFree)
        root.put("externalStorageTotal", profile.externalStorageTotal ?: JSONObject.NULL)
        root.put("externalStorageFree", profile.externalStorageFree ?: JSONObject.NULL)
        root.put("batteryLevel", profile.batteryLevel)
        root.put("batteryCharging", profile.batteryCharging)
        root.put("batteryHealth", profile.batteryHealth)
        root.put("lastLatitude", profile.lastLatitude ?: JSONObject.NULL)
        root.put("lastLongitude", profile.lastLongitude ?: JSONObject.NULL)
        root.put("lastLocationAccuracy", profile.lastLocationAccuracy?.toDouble() ?: JSONObject.NULL)
        root.put("lastLocationTime", profile.lastLocationTime ?: JSONObject.NULL)
        root.put("collectedAt", profile.collectedAt)

        val apps = JSONArray()
        for (app in profile.installedApps) {
            val a = JSONObject()
            a.put("packageName", app.packageName)
            a.put("appName", app.appName)
            a.put("versionName", app.versionName)
            a.put("versionCode", app.versionCode)
            a.put("installedAt", app.installedAt)
            a.put("isSystemApp", app.isSystemApp)
            apps.put(a)
        }
        root.put("installedApps", apps)
        return root.toString()
    }

    @Suppress("HardwareIds")
    private fun androidId(): String = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
    } catch (e: Exception) { "" }

    private fun primaryAbi(): String =
        if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.isNotEmpty())
            Build.SUPPORTED_ABIS[0] else ""

    private fun isRooted(): Boolean {
        for (path in SU_PATHS) {
            try { if (File(path).exists()) return true } catch (_: Exception) {}
        }
        try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val out = process.inputStream.bufferedReader().use { it.readLine() }
            process.waitFor()
            if (!out.isNullOrBlank()) return true
        } catch (_: Exception) {}
        val tags = Build.TAGS
        if (tags != null && tags.contains("test-keys")) return true
        return false
    }

    @Suppress("DEPRECATION")
    private fun wifiIpAddress(): String? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ip = wm?.connectionInfo?.ipAddress ?: 0
        if (ip == 0) {
            interfaceIpv4 { it.name.startsWith("wlan") || it.name.startsWith("ap") }
        } else {
            String.format(Locale.US, "%d.%d.%d.%d",
                ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
        }
    } catch (e: Exception) { null }

    private fun mobileIpAddress(): String? = try {
        interfaceIpv4 {
            val n = it.name.lowercase(Locale.US)
            n.startsWith("rmnet") || n.startsWith("ccmni") ||
                n.startsWith("pdp") || n.startsWith("clat")
        }
    } catch (e: Exception) { null }

    private fun interfaceIpv4(predicate: (NetworkInterface) -> Boolean): String? {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                if (!predicate(iface)) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress
                }
            }
        } catch (_: Exception) {}
        return null
    }

    @Suppress("DEPRECATION", "HardwareIds")
    private fun wifiMacAddress(): String? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val mac = wm?.connectionInfo?.macAddress
        if (mac.isNullOrBlank()) null else mac
    } catch (e: Exception) { null }

    @Suppress("DEPRECATION")
    private fun currentSsid(): String? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val raw = wm?.connectionInfo?.ssid
        when {
            raw.isNullOrBlank() -> null
            raw == "<unknown ssid>" -> null
            raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2 ->
                raw.substring(1, raw.length - 1)
            else -> raw
        }
    } catch (e: Exception) { null }

    private fun networkType(): String {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "NONE"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val net = cm.activeNetwork ?: return "NONE"
                val caps = cm.getNetworkCapabilities(net) ?: return "NONE"
                return when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> mobileSubtype()
                    else -> "NONE"
                }
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo ?: return "NONE"
                @Suppress("DEPRECATION")
                return when (info.type) {
                    ConnectivityManager.TYPE_WIFI -> "WIFI"
                    ConnectivityManager.TYPE_ETHERNET -> "ETHERNET"
                    ConnectivityManager.TYPE_MOBILE -> mobileSubtype()
                    else -> "NONE"
                }
            }
        } catch (e: Exception) { return "NONE" }
    }

    private fun mobileSubtype(): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return "MOBILE"
            val type = try { tm.dataNetworkType } catch (e: SecurityException) { return "MOBILE" }
            when (type) {
                TelephonyManager.NETWORK_TYPE_NR -> "MOBILE_5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "MOBILE_LTE"
                TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B -> "MOBILE_3G"
                TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT -> "MOBILE_2G"
                else -> "MOBILE"
            }
        } catch (e: Exception) { "MOBILE" }
    }

    private fun telephony(): TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private fun simOperator(): String? = try {
        telephony()?.simOperatorName?.ifBlank { null }
    } catch (e: Exception) { null }

    private fun simCountryIso(): String? = try {
        telephony()?.simCountryIso?.ifBlank { null }
    } catch (e: Exception) { null }

    @Suppress("DEPRECATION", "HardwareIds", "MissingPermission")
    private fun phoneNumber(): String? = try {
        telephony()?.line1Number?.ifBlank { null }
    } catch (e: Exception) { null }

    @Suppress("DEPRECATION", "HardwareIds", "MissingPermission")
    private fun imei(): String? {
        val tm = telephony() ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                tm.imei?.ifBlank { null }
            } else {
                tm.deviceId?.ifBlank { null }
            }
        } catch (e: Exception) { null }
    }

    private fun simState(): String = try {
        when (telephony()?.simState) {
            TelephonyManager.SIM_STATE_READY -> "READY"
            TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
            TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
            TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERM_DISABLED"
            TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "CARD_IO_ERROR"
            TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "CARD_RESTRICTED"
            else -> "UNKNOWN"
        }
    } catch (e: Exception) { "UNKNOWN" }

    private fun internalStorage(): Pair<Long, Long> = try {
        val stat = StatFs(Environment.getDataDirectory().path)
        Pair(stat.blockCountLong * stat.blockSizeLong, stat.availableBlocksLong * stat.blockSizeLong)
    } catch (e: Exception) { Pair(0L, 0L) }

    private fun externalStorage(): Pair<Long?, Long?> = try {
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            val ext = context.getExternalFilesDirs(null)
                .filterNotNull()
                .firstOrNull { it.absolutePath.contains("/storage/") &&
                    !it.absolutePath.startsWith(Environment.getDataDirectory().path) }
                ?: Environment.getExternalStorageDirectory()
            val stat = StatFs(ext.path)
            Pair(stat.blockCountLong * stat.blockSizeLong, stat.availableBlocksLong * stat.blockSizeLong)
        } else { Pair(null, null) }
    } catch (e: Exception) { Pair(null, null) }

    private fun batteryIntent() =
        context.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))

    private fun batteryLevel(): Int {
        val intent = batteryIntent() ?: return -1
        return try {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) -1 else (level * 100 / scale)
        } catch (e: Exception) { -1 }
    }

    private fun batteryCharging(): Boolean {
        val intent = batteryIntent() ?: return false
        return try {
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) { false }
    }

    private fun batteryHealth(): String = try {
        val intent = batteryIntent()
        when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "FAILURE"
            BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
            else -> "UNKNOWN"
        }
    } catch (e: Exception) { "UNKNOWN" }

    private fun installedApps(): List<AppInfo> {
        val pm = context.packageManager
        val result = ArrayList<AppInfo>()
        try {
            val flags = PackageManager.GET_META_DATA
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(flags)
            }
            for (pkg in packages) {
                try {
                    val ai: ApplicationInfo? = pkg.applicationInfo
                    val isSystem = ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val label = ai?.let { pm.getApplicationLabel(it).toString() } ?: pkg.packageName
                    val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pkg.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        pkg.versionCode.toLong()
                    }
                    result.add(AppInfo(
                        packageName = pkg.packageName,
                        appName = label,
                        versionName = pkg.versionName ?: "",
                        versionCode = vCode,
                        installedAt = pkg.firstInstallTime,
                        isSystemApp = isSystem
                    ))
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {}
        return result
    }

    @Suppress("MissingPermission")
    private fun lastKnownLocation(): Location? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return null
            var best: Location? = null
            val providers = lm.getProviders(true)
            for (provider in providers) {
                val loc = try {
                    lm.getLastKnownLocation(provider)
                } catch (e: Exception) { null } ?: continue
                if (best == null || loc.time > best!!.time) best = loc
            }
            best
        } catch (e: Exception) { null }
    }
}
