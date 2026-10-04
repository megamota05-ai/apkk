// [context: Android API 26+, ARM64/any — service watchdog + resurrection via AlarmManager]
// WatchdogReceiver.kt — BroadcastReceiver que ressuscita o DispatchService se morrer
// Estratégia: AlarmManager inexact repeating a cada 5min + ACTION_MY_PACKAGE_REPLACED
package com.fleetdroid.driver.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "WatchdogReceiver"
        const val ACTION_WATCHDOG = "com.fleetdroid.driver.WATCHDOG_TICK"
        const val INTERVAL_MS = 5 * 60 * 1000L // 5 minutos

        /**
         * Agenda o watchdog via AlarmManager setRepeating.
         * Chama no onCreate do DispatchService e no BootReceiver.
         */
        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + INTERVAL_MS, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + INTERVAL_MS, pi)
                }
            } else {
                am.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + INTERVAL_MS, INTERVAL_MS, pi)
            }
            Log.i(TAG, "watchdog scheduled, interval=${INTERVAL_MS / 1000}s")
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WatchdogReceiver::class.java).apply {
                action = ACTION_WATCHDOG
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            return PendingIntent.getBroadcast(context, 0xA0, intent, flags)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "watchdog tick, action=${intent.action}")
        ensureServiceRunning(context)
        // Re-agenda o próximo tick (API 31+ não usa setRepeating exato)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            schedule(context)
        }
    }

    private fun ensureServiceRunning(context: Context) {
        if (DispatchService.instance != null) {
            Log.d(TAG, "service alive, no action needed")
            return
        }
        Log.w(TAG, "service dead — resurrecting DispatchService")
        try {
            val serviceIntent = Intent(context, DispatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "resurrection failed", e)
        }
    }
}
