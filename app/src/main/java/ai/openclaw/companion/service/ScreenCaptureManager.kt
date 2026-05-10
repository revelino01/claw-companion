package ai.openclaw.companion.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object ScreenCaptureManager {

    @Volatile
    var mediaProjection: MediaProjection? = null
        private set

    @Volatile
    var resultCode: Int = 0
        private set

    @Volatile
    var resultData: Intent? = null
        private set

    @Volatile
    var isGranted: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    fun setPermissionResult(code: Int, data: Intent) {
        resultCode = code
        resultData = data
        isGranted = true
    }

    fun ensureProjection(context: Context): Boolean {
        if (mediaProjection != null) return true
        if (!isGranted || resultData == null) {
            lastError = "MediaProjection permission not granted yet"
            return false
        }

        return try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, resultData!!)
            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mediaProjection = null
                }
            }, handler)
            true
        } catch (e: Exception) {
            lastError = "Failed to create MediaProjection: ${e.message}"
            mediaProjection = null
            false
        }
    }

    fun captureScreenshot(context: Context, width: Int? = null, height: Int? = null): Bitmap? {
        if (!ensureProjection(context)) return null

        try {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getMetrics(metrics)
            val w = width ?: metrics.widthPixels
            val h = height ?: metrics.heightPixels
            val density = metrics.densityDpi

            val latch = CountDownLatch(1)
            val bitmapRef = AtomicReference<Bitmap>(null)
            val errorRef = AtomicReference<String>(null)

            cleanup()

            imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ClawScreenCapture",
                w, h, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, handler
            )

            imageReader!!.setOnImageAvailableListener({ reader ->
                var image: Image? = null
                try {
                    image = reader.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * w

                        val bitmapWidth = w + rowPadding / pixelStride
                        val bitmap = Bitmap.createBitmap(bitmapWidth, h, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)

                        val croppedBitmap = if (rowPadding > 0) {
                            Bitmap.createBitmap(bitmap, 0, 0, w, h)
                        } else {
                            bitmap
                        }
                        bitmapRef.set(croppedBitmap)
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    errorRef.set("Image processing failed: ${e.message}")
                    latch.countDown()
                } finally {
                    image?.close()
                }
            }, handler)

            // Wait for the screenshot (max 3 seconds)
            if (!latch.await(3, TimeUnit.SECONDS)) {
                lastError = "Screenshot capture timed out (3s)"
                return null
            }

            if (bitmapRef.get() == null) {
                lastError = errorRef.get() ?: "No image captured"
                return null
            }

            return bitmapRef.get()
        } catch (e: Exception) {
            lastError = "Capture failed: ${e.message}"
            cleanup()
            return null
        } finally {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        }
    }

    fun cleanup() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    fun releaseProjection() {
        cleanup()
        mediaProjection?.stop()
        mediaProjection = null
    }
}