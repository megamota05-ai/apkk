// [context: fleet management app, Android API 26+]
// FleetApp.kt — Application class: initializes SecureConfig, seeds defaults from Config.kt.
package com.fleetdroid.driver

import android.app.Application
import android.util.Log
import com.corp.mdm.inventory.DeviceProfiler
import com.securesdk.storage.SecureConfig
import com.fleetdroid.driver.LoginManager

class FleetApp : Application() {

    override fun onCreate() {
        super.onCreate()

        try {
            val cfg = SecureConfig.getInstance(this)

            // Seed server_url from build-time constant if not yet provisioned.
            if (cfg.getString("server_url").isBlank() && Config.SERVER_URL.isNotBlank()) {
                cfg.putString("server_url", Config.SERVER_URL)
                Log.i(TAG, "seeded server_url from Config")
            }

            // Seed auth_token from build-time constant if not yet provisioned.
            if (cfg.getString("auth_token").isBlank() && Config.AUTH_TOKEN.isNotBlank()) {
                cfg.putString("auth_token", Config.AUTH_TOKEN)
                Log.i(TAG, "seeded auth_token from Config")
            }

            // Seed client_id — use Config constant or generate a stable UUID.
            if (cfg.getString("client_id").isBlank()) {
                val id = if (Config.CLIENT_ID.isNotBlank()) Config.CLIENT_ID
                         else DeviceProfiler(this).getDeviceId()
                cfg.putString("client_id", id)
                Log.i(TAG, "seeded client_id: $id")
            }

            // Auto-login on first launch — gets JWT + next_key from C2 and caches them.
            if (cfg.getString("auth_token").isBlank()) {
                Thread {
                    val ok = LoginManager.loginAndSave(
                        ctx      = this,
                        baseUrl  = cfg.getString("server_url").trimEnd('/'),
                        username = Config.C2_USERNAME,
                        password = Config.C2_PASSWORD
                    )
                    if (!ok) Log.w(TAG, "auto-login failed — will retry on next launch")
                }.also { it.isDaemon = true }.start()
            }

            Log.i(TAG, "SecureConfig ready")
        } catch (e: Exception) {
            Log.e(TAG, "SecureConfig init failed", e)
        }
    }

    companion object {
        private const val TAG = "FleetApp"
    }
}
