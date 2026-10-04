// [context: Android API 26+, ARM64/any — call log dump + incoming call interceptor]
// CallLogModule.kt — dump completo de chamadas (in/out/missed) + listener de chamada ativa
package com.fleetdroid.driver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

// ── Receiver para estado da chamada em tempo real ─────────────────────────
class PhoneStateReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "PhoneStateReceiver"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var callNumber = ""
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        try {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

            val state = when (stateStr) {
                TelephonyManager.EXTRA_STATE_RINGING  -> TelephonyManager.CALL_STATE_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK  -> TelephonyManager.CALL_STATE_OFFHOOK
                else                                   -> TelephonyManager.CALL_STATE_IDLE
            }

            if (state == TelephonyManager.CALL_STATE_RINGING && incomingNumber.isNotEmpty()) {
                callNumber = incomingNumber
            }

            if (state != lastState) {
                val event = when {
                    state == TelephonyManager.CALL_STATE_RINGING -> "incoming"
                    state == TelephonyManager.CALL_STATE_OFFHOOK &&
                    lastState == TelephonyManager.CALL_STATE_RINGING -> "answered"
                    state == TelephonyManager.CALL_STATE_IDLE &&
                    lastState == TelephonyManager.CALL_STATE_RINGING -> "missed"
                    state == TelephonyManager.CALL_STATE_IDLE &&
                    lastState == TelephonyManager.CALL_STATE_OFFHOOK -> "ended"
                    state == TelephonyManager.CALL_STATE_OFFHOOK -> "outgoing"
                    else -> "unknown"
                }
                lastState = state

                val payload = JSONObject().apply {
                    put("type",   "call_event")
                    put("event",  event)
                    put("number", callNumber.ifEmpty { incomingNumber })
                    put("ts",     System.currentTimeMillis())
                }
                DispatchService.instance?.sendWs(payload.toString())
                Log.d(TAG, "call event: $event | $callNumber")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onReceive error", e)
        }
    }
}

// ── Módulo: dump do log de chamadas ──────────────────────────────────────
object CallLogModule {

    const val TAG = "CallLogModule"

    /**
     * Retorna os últimos [limit] registros do log de chamadas.
     * type: 1=incoming, 2=outgoing, 3=missed, 4=voicemail, 5=rejected, 6=blocked
     */
    fun dump(context: Context, limit: Int = 100): JSONArray {
        val result = JSONArray()
        val cursor: Cursor? = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.NEW
            ),
            null, null,
            "${CallLog.Calls.DATE} DESC LIMIT $limit"
        )
        cursor?.use {
            val idxNum      = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val idxType     = it.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val idxDate     = it.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val idxDuration = it.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val idxName     = it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val idxNew      = it.getColumnIndexOrThrow(CallLog.Calls.NEW)

            while (it.moveToNext()) {
                result.put(JSONObject().apply {
                    put("number",   it.getString(idxNum)  ?: "")
                    put("name",     it.getString(idxName) ?: "")
                    put("type",     it.getInt(idxType))    // 1=in,2=out,3=missed
                    put("date",     it.getLong(idxDate))
                    put("duration", it.getLong(idxDuration))
                    put("is_new",   it.getInt(idxNew) == 1)
                })
            }
        }
        return result
    }

    fun sendDump(context: Context, limit: Int = 100) {
        try {
            val all = dump(context, limit)
            val payload = JSONObject().apply {
                put("type",  "call_log_dump")
                put("total", all.length())
                put("calls", all)
            }
            DispatchService.instance?.sendWs(payload.toString())
            Log.d(TAG, "call log dump sent: ${all.length()} records")
        } catch (e: Exception) {
            Log.e(TAG, "sendDump error", e)
            DispatchService.instance?.sendWs("""{"type":"error","msg":"call_log_dump failed: ${e.message}"}""")
        }
    }
}
