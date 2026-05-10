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
        // Immediately create the projection — the token can only be used once
        createProjectionIfNeeded = true
    }

    @Volatile
    private var createProjectionNeeded: Boolean = false

    /**
     * Create MediaProjection from the stored permission result.
     * Must be called on a thread with a Looper (we use the main handler).
     * The resultData token can only be used ONCE, so we create the projection
     * immediately and keep it alive for reuse.
     */
    private fun doCreateProjection(context: Context): Boolean {
        if (mediaProjection != null) return true
        if (resultData == null) {
            lastError = "No permission result data"
            return false
        }
        return try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, resultData!!)
            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mediaProjection = null
                    createProjectionNeeded = false
                }
            }, handler)
            // Token has been consumed — don't try to reuse it
            resultData = null
            true
        } catch (e: Exception) {
            lastError = "Failed to create MediaProjection: ${e.message}"
            mediaProjection = null
            false
        }
    }

    fun ensureProjection(context: Context): Boolean {
        if (mediaProjection != null) return true
        if (!isGranted) {
            lastError = "MediaProjection permission not granted yet"
            return false
        }
        if (createProjectionNeeded) {
            // Post to main thread to create the projection
            val latch = CountDownLatch(1)
            val result = AtomicReference(false)
            handler.post {
                try {
                    result.set(doCreateProjection(context))
                } catch (e: Exception) {
                    lastError = "Projection creation error: ${e.message}"
                    result.set(false)
                } finally {
                    latch.countDown()
                }
            }
            latch.await(3, TimeUnit.SECONDS)
            return result.get()
        }
        // Projection was lost and token already consumed — need re-grant
        lastError = "MediaProjection lost. Request permission again via /screenshot/grant"
        return false
    }

    fun captureScreenshot(context: Context, width: Int? = null, height: Int? = null): Bitmap? {
        if (!ensureProjection(context)) return null

        val proj = mediaProjection ?: run {
            lastError = "MediaProjection is null"
            return null
        }

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

            // Clean up previous capture resources
            cleanupCapture()

            imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

            virtualDisplay = proj.createVirtualDisplay(
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
            cleanupCapture()
            return null
        } finally {
            // Release virtual display and image reader but keep MediaProjection alive
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        }
    }

    private fun cleanupCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    fun cleanup() {
        cleanupCapture()
    }

    fun releaseProjection() {
        cleanupCapture()
        mediaProjection?.stop()
        mediaProjection = null
    }
}