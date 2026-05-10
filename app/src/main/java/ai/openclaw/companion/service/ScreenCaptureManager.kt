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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages MediaProjection for screen capture.
 * 
 * Flow:
 * 1. User requests /screenshot or /ocr
 * 2. If no active projection, returns 402 with instructions to grant permission
 * 3. User triggers /screenshot/grant which opens the system permission dialog
 * 4. Result comes back via onActivityResult → we get the MediaProjection
 * 5. Subsequent /screenshot and /ocr calls work without re-prompting
 */
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

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    fun setPermissionResult(code: Int, data: Intent) {
        resultCode = code
        resultData = data
        isGranted = true
    }

    fun ensureProjection(context: Context): Boolean {
        if (mediaProjection?.isValid == true) return true
        if (!isGranted || resultData == null) return false

        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData!!)
        return true
    }

    /**
     * Capture a screenshot. Returns the Bitmap or null.
     * Must be called after ensureProjection succeeds.
     */
    fun captureScreenshot(context: Context, width: Int? = null, height: Int? = null): Bitmap? {
        if (!ensureProjection(context)) return null

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getMetrics(metrics)
        val w = width ?: metrics.widthPixels
        val h = height ?: metrics.heightPixels
        val density = metrics.densityDpi

        val latch = CountDownLatch(1)
        val bitmapRef = AtomicReference<Bitmap>(null)

        // Clean up previous resources
        cleanup()

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        try {
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

                        // Crop if there's padding
                        val croppedBitmap = if (rowPadding > 0) {
                            Bitmap.createBitmap(bitmap, 0, 0, w, h)
                        } else {
                            bitmap
                        }
                        bitmapRef.set(croppedBitmap)
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    latch.countDown()
                } finally {
                    image?.close()
                }
            }, handler)

            // Wait for the screenshot (max 3 seconds)
            latch.await(3, TimeUnit.SECONDS)

        } catch (e: Exception) {
            cleanup()
            return null
        } finally {
            // Clean up virtual display and image reader but keep media projection alive
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        }

        return bitmapRef.get()
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