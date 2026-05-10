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

    // Dimensions of current virtual display
    private var captureWidth: Int = 0
    private var captureHeight: Int = 0

    // Pending image from OnImageAvailableListener
    @Volatile
    private var pendingImage: Image? = null
    private val imageLock = Object()

    fun setPermissionResult(code: Int, data: Intent) {
        resultCode = code
        resultData = data
        isGranted = true
    }

    private fun doCreateProjection(context: Context): Boolean {
        if (mediaProjection != null) return true
        if (resultData == null) {
            lastError = "No permission result data"
            return false
        }
        return try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val data = resultData!!
            resultData = null
            mediaProjection = mpm.getMediaProjection(resultCode, data)
            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mediaProjection = null
                    // Clean up everything on projection stop
                    synchronized(imageLock) {
                        pendingImage?.close()
                        pendingImage = null
                    }
                    virtualDisplay?.release()
                    virtualDisplay = null
                    imageReader?.close()
                    imageReader = null
                    captureWidth = 0
                    captureHeight = 0
                }
            }, handler)
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
        if (resultData != null) {
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
        lastError = "MediaProjection lost. Request permission again via /screenshot/grant"
        return false
    }

    /**
     * Set up or reuse the virtual display and image reader.
     * Keeps the OnImageAvailableListener active so the surface stays alive.
     */
    private fun ensureVirtualDisplay(context: Context, w: Int, h: Int): Boolean {
        // Reuse if same dimensions and still valid
        if (virtualDisplay != null && imageReader != null && captureWidth == w && captureHeight == h) {
            return true
        }

        // Tear down old resources
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        val proj = mediaProjection ?: run {
            lastError = "MediaProjection is null"
            return false
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getMetrics(metrics)
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        // Set up persistent listener that stores the latest image
        imageReader!!.setOnImageAvailableListener({ reader ->
            synchronized(imageLock) {
                pendingImage?.close()
                pendingImage = reader.acquireLatestImage()
            }
        }, handler)

        virtualDisplay = proj.createVirtualDisplay(
            "ClawScreenCapture",
            w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, handler
        )
        captureWidth = w
        captureHeight = h

        // Wait for first frame
        Thread.sleep(200)
        return true
    }

    fun captureScreenshot(context: Context, width: Int? = null, height: Int? = null): Bitmap? {
        if (!ensureProjection(context)) return null

        try {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getMetrics(metrics)
            val w = width ?: metrics.widthPixels
            val h = height ?: metrics.heightPixels

            if (!ensureVirtualDisplay(context, w, h)) return null

            // Wait for a fresh frame (up to 2 seconds)
            var image: Image? = null
            val startTime = System.currentTimeMillis()
            while (image == null && System.currentTimeMillis() - startTime < 2000) {
                synchronized(imageLock) {
                    image = pendingImage
                    if (image != null) {
                        pendingImage = null
                    }
                }
                if (image == null) {
                    Thread.sleep(50)
                }
            }

            if (image == null) {
                lastError = "No frame available after 2s"
                return null
            }

            try {
                val planes = image!!.planes
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
                return croppedBitmap
            } finally {
                image?.close()
            }
        } catch (e: Exception) {
            lastError = "Capture failed: ${e.message}"
            return null
        }
    }

    fun releaseProjection() {
        synchronized(imageLock) {
            pendingImage?.close()
            pendingImage = null
        }
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        captureWidth = 0
        captureHeight = 0
        mediaProjection?.stop()
        mediaProjection = null
    }
}