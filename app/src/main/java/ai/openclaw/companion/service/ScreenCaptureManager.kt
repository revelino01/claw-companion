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

/**
 * On-demand screen capture manager.
 *
 * **OVERHEATING FIX**: VirtualDisplay + ImageReader are now created per-capture
 * and released immediately after. Previously they were kept alive persistently,
 * which forced the GPU/compositor to mirror the screen continuously,
 * causing constant battery drain and heat even when idle.
 *
 * Trade-off: each /screenshot call has ~300ms setup overhead.
 * Benefit: zero idle power consumption.
 */
object ScreenCaptureManager {

    @Volatile
    var mediaProjection: MediaProjection? = null
        private set

    @Volatile
    var isGranted: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val handler = Handler(Looper.getMainLooper())

    private var vdWidth: Int = 0
    private var vdHeight: Int = 0

    // Permission result (consumed once)
    private var pendingResultCode: Int = 0
    private var pendingResultData: Intent? = null

    fun setPermissionResult(code: Int, data: Intent) {
        pendingResultCode = code
        pendingResultData = data
        isGranted = true
    }

    /**
     * Create MediaProjection ONCE. The projection itself is lightweight
     * and safe to keep. Only VirtualDisplay is created per-capture.
     */
    private fun ensureMediaProjection(context: Context): MediaProjection? {
        if (mediaProjection != null) return mediaProjection
        if (pendingResultData == null) {
            lastError = "No permission result. Call /screenshot/grant first."
            return null
        }
        return try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val data = pendingResultData!!
            pendingResultData = null  // Token consumed

            val mp = mpm.getMediaProjection(pendingResultCode, data)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mediaProjection = null
                }
            }, handler)

            mediaProjection = mp
            mp
        } catch (e: Exception) {
            lastError = "Projection setup failed: ${e.message}"
            null
        }
    }

    /**
     * Capture a single screenshot.
     * Creates VirtualDisplay + ImageReader, captures one frame, then tears down.
     */
    fun captureScreenshot(context: Context, width: Int? = null, height: Int? = null): Bitmap? {
        val mp = ensureMediaProjection(context) ?: return null

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(android.view.WindowManager::class.java)
            .defaultDisplay.getMetrics(metrics)
        val w = width ?: metrics.widthPixels
        val h = height ?: metrics.heightPixels
        vdWidth = w
        vdHeight = h

        var imageReader: ImageReader? = null
        var virtualDisplay: VirtualDisplay? = null
        var capturedImage: Image? = null
        val captureLatch = CountDownLatch(1)

        return try {
            imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1)

            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    capturedImage = reader.acquireLatestImage()
                } finally {
                    captureLatch.countDown()
                }
            }, handler)

            virtualDisplay = mp.createVirtualDisplay(
                "ClawCompanionCapture",
                w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,  // No callback needed — we release manually
                handler
            )

            // Wait for frame (max 3 seconds)
            captureLatch.await(3, TimeUnit.SECONDS)

            val img = capturedImage
            if (img == null) {
                lastError = "No frame captured within timeout"
                return null
            }

            // Convert Image to Bitmap
            val planes = img.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * w

            val bitmapWidth = w + rowPadding / pixelStride
            val rawBitmap = Bitmap.createBitmap(bitmapWidth, h, Bitmap.Config.ARGB_8888)
            rawBitmap.copyPixelsFromBuffer(buffer)

            val result = if (rowPadding > 0) {
                Bitmap.createBitmap(rawBitmap, 0, 0, w, h)
            } else {
                rawBitmap
            }

            result
        } catch (e: Exception) {
            lastError = "Capture failed: ${e.message}"
            null
        } finally {
            capturedImage?.close()
            virtualDisplay?.release()
            imageReader?.close()
            // Don't stop mediaProjection here — keep it for next capture
        }
    }

    fun releaseProjection() {
        mediaProjection?.stop()
        mediaProjection = null
        isGranted = false
    }
}
