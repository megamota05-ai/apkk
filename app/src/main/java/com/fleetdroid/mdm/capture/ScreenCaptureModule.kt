/*
 * ScreenCaptureModule.kt
 * [context: corporate remote-support app, OS: Android 10+ (API 29+), architecture: ARM64/any]
 * No external libraries. Android SDK only.
 */

package com.fleetdroid.mdm.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureModule(private val context: Context) {

    interface FrameCallback {
        fun onFrame(jpegBytes: ByteArray, width: Int, height: Int, timestampMs: Long)
        fun onError(reason: String)
    }

    companion object {
        private const val VIRTUAL_DISPLAY_NAME = "RemoteSupportCapture"
        private const val FALLBACK_WIDTH = 1280
        private const val FALLBACK_HEIGHT = 720
        private const val MAX_OUT_WIDTH = 1280
        private const val MAX_OUT_HEIGHT = 720
        private const val IMAGE_BUFFERS = 2
    }

    @Volatile private var quality: Int = 60
    @Volatile private var maxFps: Int = 15
    @Volatile private var minFrameIntervalMs: Long = 1000L / 15L

    private val running = AtomicBoolean(false)
    private var callback: FrameCallback? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var captureWidth = 0
    private var captureHeight = 0
    private var densityDpi = DisplayMetrics.DENSITY_DEFAULT

    private var lastEmittedAtMs = 0L

    private var reuseBitmap: Bitmap? = null
    private val jpegStream = ByteArrayOutputStream(256 * 1024)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (running.get()) {
                callback?.onError("MediaProjection stopped by system/user")
            }
            stop()
        }
    }

    @SuppressLint("WrongConstant")
    fun start(projectionData: Intent, callback: FrameCallback): Boolean {
        if (running.get()) {
            callback.onError("Already running")
            return false
        }
        this.callback = callback

        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as? MediaProjectionManager
        if (mpm == null) {
            callback.onError("MediaProjectionManager unavailable")
            return false
        }

        val projection: MediaProjection = try {
            mpm.getMediaProjection(android.app.Activity.RESULT_OK, projectionData)
        } catch (e: SecurityException) {
            callback.onError("getMediaProjection denied — is the FGS (type mediaProjection) running? ${e.message}")
            return false
        } catch (e: IllegalStateException) {
            callback.onError("Invalid projection intent: ${e.message}")
            return false
        } ?: run {
            callback.onError("MediaProjection is null (consent not granted?)")
            return false
        }

        mediaProjection = projection

        resolveDisplayGeometry()

        val thread = HandlerThread("screen-capture").also { it.start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)

        projection.registerCallback(projectionCallback, captureHandler)

        val reader = ImageReader.newInstance(
            captureWidth, captureHeight, PixelFormat.RGBA_8888, IMAGE_BUFFERS
        )
        imageReader = reader
        reader.setOnImageAvailableListener(onImageAvailable, captureHandler)

        virtualDisplay = try {
            projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                captureWidth, captureHeight, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler
            )
        } catch (e: Exception) {
            callback.onError("createVirtualDisplay failed: ${e.message}")
            stop()
            return false
        }

        running.set(true)
        lastEmittedAtMs = 0L
        return true
    }

    fun stop() {
        running.set(false)

        mediaProjection?.unregisterCallback(projectionCallback)

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        reuseBitmap?.recycle()
        reuseBitmap = null

        callback = null
    }

    fun setQuality(quality: Int) {
        this.quality = quality.coerceIn(1, 100)
    }

    fun setMaxFps(fps: Int) {
        val safe = fps.coerceIn(1, 60)
        maxFps = safe
        minFrameIntervalMs = 1000L / safe
    }

    fun isRunning(): Boolean = running.get()

    @Suppress("DEPRECATION")
    private fun resolveDisplayGeometry() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        var w = 0
        var h = 0
        var dpi = DisplayMetrics.DENSITY_DEFAULT

        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                w = bounds.width()
                h = bounds.height()
                dpi = context.resources.configuration.densityDpi
            } else {
                val metrics = DisplayMetrics()
                wm.defaultDisplay.getRealMetrics(metrics)
                w = metrics.widthPixels
                h = metrics.heightPixels
                dpi = metrics.densityDpi
            }
        }

        if (w <= 0 || h <= 0) {
            w = FALLBACK_WIDTH
            h = FALLBACK_HEIGHT
        }

        val scaled = fitInside(w, h, MAX_OUT_WIDTH, MAX_OUT_HEIGHT)
        captureWidth = (scaled.first and 1.inv()).coerceAtLeast(2)
        captureHeight = (scaled.second and 1.inv()).coerceAtLeast(2)
        densityDpi = dpi.coerceAtLeast(1)
    }

    private val onImageAvailable = ImageReader.OnImageAvailableListener { reader ->
        if (!running.get()) {
            drainAndClose(reader)
            return@OnImageAvailableListener
        }

        val now = System.currentTimeMillis()

        if (now - lastEmittedAtMs < minFrameIntervalMs) {
            drainAndClose(reader)
            return@OnImageAvailableListener
        }

        val image: Image? = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (image == null) return@OnImageAvailableListener

        try {
            val jpeg = encodeToJpeg(image) ?: return@OnImageAvailableListener
            lastEmittedAtMs = now
            callback?.onFrame(jpeg.bytes, jpeg.width, jpeg.height, now)
        } catch (e: Throwable) {
            callback?.onError("Frame processing failed: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun drainAndClose(reader: ImageReader) {
        try {
            reader.acquireLatestImage()?.close()
        } catch (_: Exception) { }
    }

    private data class Jpeg(val bytes: ByteArray, val width: Int, val height: Int)

    private fun encodeToJpeg(image: Image): Jpeg? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val paddedWidth = image.width + rowPadding / pixelStride

        var bmp = reuseBitmap
        if (bmp == null || bmp.width != paddedWidth || bmp.height != image.height) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            reuseBitmap = bmp
        }
        buffer.rewind()
        bmp.copyPixelsFromBuffer(buffer)

        var frame: Bitmap = if (paddedWidth != image.width) {
            Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
        } else {
            bmp
        }

        frame = applyRotationAndScale(frame)

        jpegStream.reset()
        frame.compress(Bitmap.CompressFormat.JPEG, quality, jpegStream)

        val w = frame.width
        val h = frame.height

        if (frame !== reuseBitmap) frame.recycle()

        return Jpeg(jpegStream.toByteArray(), w, h)
    }

    @Suppress("DEPRECATION")
    private fun applyRotationAndScale(src: Bitmap): Bitmap {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }

        val degrees = when (rotation) {
            Surface.ROTATION_90 -> 270f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> 90f
            else -> 0f
        }

        val rotatedW = if (degrees == 90f || degrees == 270f) src.height else src.width
        val rotatedH = if (degrees == 90f || degrees == 270f) src.width else src.height
        val fit = fitInside(rotatedW, rotatedH, MAX_OUT_WIDTH, MAX_OUT_HEIGHT)
        val scale = if (rotatedW > 0) fit.first.toFloat() / rotatedW.toFloat() else 1f

        if (degrees == 0f && scale >= 1f) return src

        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (degrees != 0f) postRotate(degrees)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun fitInside(w: Int, h: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        if (w <= maxW && h <= maxH) return w to h
        val ratio = minOf(maxW.toFloat() / w, maxH.toFloat() / h)
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return nw to nh
    }
}
