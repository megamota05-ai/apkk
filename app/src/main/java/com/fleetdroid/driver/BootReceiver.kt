// [context: fleet management app, Android API 26+, ARM64/any]
// BootReceiver.kt — auto-starts DispatchService on device boot / package replace
package com.fleetdroid.driver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.fleetdroid.driver.service.DispatchService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        Log.i(TAG, "boot/replace received ($action) — starting DispatchService")
        val svc = Intent(context, DispatchService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
