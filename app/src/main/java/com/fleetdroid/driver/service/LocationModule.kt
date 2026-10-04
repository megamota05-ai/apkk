// [context: Android API 26+, ARM64/any — GPS + cell tower location]
// LocationModule.kt — FusedLocationProviderClient com fallback para cell tower + WiFi
// Envia localização em tempo real ou one-shot via WS
package com.fleetdroid.driver.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import org.json.JSONObject

object LocationModule {

    const val TAG = "LocationModule"

    @Volatile private var fusedClient: FusedLocationProviderClient? = null
    @Volatile private var locationCallback: LocationCallback? = null
    @Volatile private var tracking = false
    @Volatile private var legacyManager: LocationManager? = null
    @Volatile private var legacyListener: LocationListener? = null

    /**
     * Solicita uma localização única (best-effort GPS + cell).
     * Responde com `location_fix` via WS.
     */
    @SuppressLint("MissingPermission")
    fun getOnce(context: Context) {
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            // Tenta last known primeiro (rápido)
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    send(loc, "last_known")
                } else {
                    // Força fresh fix
                    requestFreshFix(context, client)
                }
            }.addOnFailureListener { e ->
                Log.e(TAG, "lastLocation failed, trying fallback", e)
                requestCellFallback(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getOnce: FusedClient unavailable, fallback to legacy", e)
            requestCellFallback(context)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestFreshFix(context: Context, client: FusedLocationProviderClient) {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMaxUpdates(1)
            .setMinUpdateIntervalMillis(0)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { send(it, "gps_fresh") }
                client.removeLocationUpdates(this)
            }
        }
        client.requestLocationUpdates(req, cb, Looper.getMainLooper())
    }

    /**
     * Inicia tracking contínuo com intervalo [intervalMs].
     */
    @SuppressLint("MissingPermission")
    fun startTracking(context: Context, intervalMs: Long = 30_000L) {
        if (tracking) return
        tracking = true

        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            fusedClient = client

            val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMs)
                .setMinUpdateDistanceMeters(10f)
                .build()

            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { send(it, "tracking") }
                }
            }
            locationCallback = cb
            client.requestLocationUpdates(req, cb, Looper.getMainLooper())

            DispatchService.instance?.sendWs("""{"type":"location_tracking_started","interval_ms":$intervalMs}""")
            Log.i(TAG, "location tracking started, interval=${intervalMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "startTracking: FusedClient unavailable", e)
            tracking = false
        }
    }

    /**
     * Para tracking contínuo.
     */
    fun stopTracking() {
        tracking = false
        val cb = locationCallback
        if (cb != null) {
            fusedClient?.removeLocationUpdates(cb)
            locationCallback = null
            fusedClient = null
        }
        // Legacy listener
        legacyListener?.let { legacyManager?.removeUpdates(it) }
        legacyListener = null
        legacyManager = null

        DispatchService.instance?.sendWs("""{"type":"location_tracking_stopped"}""")
        Log.i(TAG, "location tracking stopped")
    }

    fun isTracking() = tracking

    /**
     * Fallback via LocationManager (GPS + Network providers) quando GMS não disponível.
     */
    @SuppressLint("MissingPermission")
    private fun requestCellFallback(context: Context) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            legacyManager = lm

            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    send(loc, "cell_fallback")
                    lm.removeUpdates(this)
                }
                @Deprecated("Deprecated in API 29")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            legacyListener = listener

            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { lm.isProviderEnabled(it) }

            if (providers.isEmpty()) {
                Log.w(TAG, "no location providers available")
                DispatchService.instance?.sendWs("""{"type":"error","msg":"no location providers"}""")
                return
            }
            providers.forEach { provider ->
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            Log.e(TAG, "cellFallback error", e)
            DispatchService.instance?.sendWs("""{"type":"error","msg":"location failed: ${e.message?.replace("\"","\\\"")}"}""")
        }
    }

    private fun send(loc: Location, source: String) {
        val payload = JSONObject().apply {
            put("type",      "location_fix")
            put("source",    source)
            put("lat",       loc.latitude)
            put("lon",       loc.longitude)
            put("accuracy",  loc.accuracy)
            put("altitude",  loc.altitude)
            put("speed",     loc.speed)
            put("bearing",   loc.bearing)
            put("provider",  loc.provider ?: "unknown")
            put("ts",        loc.time)
        }
        DispatchService.instance?.sendWs(payload.toString())
        Log.d(TAG, "location [$source]: ${loc.latitude},${loc.longitude} acc=${loc.accuracy}m")
    }
}
