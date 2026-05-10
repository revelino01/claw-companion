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
    var isGranted: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    private var vdWidth: Int = 0
    private var vdHeight: Int = 0

    // Stored latest frame from persistent listener
    @Volatile
    private var latestImage: Image? = null
    private val frameLock = Any()

    // Permission result (consumed once)
    private var pendingResultCode: Int = 0
    private var pendingResultData: Intent? = null

    fun setPermissionResult(code: Int, data: Intent) {
        pendingResultCode = code
        pendingResultData = data
        isGranted = true
    }

    /**
     * Create everything ONCE: MediaProjection + VirtualDisplay + ImageReader.
     * Called on main thread via handler.
     * The Intent token is single-use, so we consume it immediately.
     */
    private fun doCreateEverything(context: Context): Boolean {
        if (mediaProjection != null) return true
        if (pendingResultData == null) {
            lastError = "No permission result. Call /screenshot/grant first."
            return false
        }
        return try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val data = pendingResultData!!
            pendingResultData = null  // Token consumed

            mediaProjection = mpm.getMediaProjection(pendingResultCode, data)

            // Register callback BEFORE createVirtualDisplay (per Android docs)
            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mediaProjection = null
                    virtualDisplay = null
                    imageReader = null
                    synchronized(frameLock) {
                        latestImage?.close()
                        latestImage = null
                    }
                }
            }, handler)

            // Set up ImageReader with persistent listener
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            context.getSystemService(android.view.WindowManager::class.java)
                .defaultDisplay.getMetrics(metrics)
            vdWidth = metrics.widthPixels
            vdHeight = metrics.heightPixels

            imageReader = ImageReader.newInstance(vdWidth, vdHeight, PixelFormat.RGBA_8888, 2)

            // Persistent listener — always stores the latest frame
            imageReader!!.setOnImageAvailableListener({ reader ->
                synchronized(frameLock) {
                    latestImage?.close()
                    latestImage = reader.acquireLatestImage()
                }
            }, handler)

            // Create VirtualDisplay with NON-NULL callback (critical for proper lifecycle)
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "ClawCompanion",
                vdWidth, vdHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                object : VirtualDisplay.Callback() {
                    override fun onStopped() {
                        virtualDisplay = null
                        synchronized(frameLock) {
                            latestImage?.close()
                            latestImage = null
                        }
                    }
                    override fun onPaused() {}
                    override fun onResumed() {}
                },
                handler
            )

            // Wait for first frame to arrive
            Thread.sleep(300)
            true
        } catch (e: Exception) {
            lastError = "Setup failed: ${e.message}"
            mediaProjection?.stop()
            mediaProjection = null
            imageReader?.close()
            imageReader = null
            virtualDisplay = null
            false
        }
    }

    fun ensureProjection(context: Context): Boolean {
        if (mediaProjection != null) return true
        if (!isGranted) {
            lastError = "Not granted. Call /screenshot/grant first."
            return false
        }
        if (pendingResultData != null) {
            val latch = CountDownLatch(1)
            val result = AtomicReference(false)
            handler.post {
                try {
                    result.set(doCreateEverything(context))
                } catch (e: Exception) {
                    lastError = "Projection creation error: ${e.message}"
                    result.set(false)
                } finally {
                    latch.countDown()
                }
            }
            latch.await(5, TimeUnit.SECONDS)
            return result.get()
        }
        lastError = "Projection lost. Re-grant via /screenshot/grant."
        return false
    }

    /**
     * Grab the latest frame from the persistent stream.
     * VirtualDisplay + ImageReader stay alive — we just take the current frame.
     */
    fun captureScreenshot(context: Context, width: Int? = null, height: Int? = null): Bitmap? {
        if (!ensureProjection(context)) return null

        // Wait for a frame to be available (up to 2 seconds)
        var img: Image? = null
        val start = System.currentTimeMillis()
        while (img == null && System.currentTimeMillis() - start < 2000) {
            synchronized(frameLock) {
                img = latestImage
                latestImage = null  // take ownership
            }
            if (img == null) Thread.sleep(50)
        }

        if (img == null) {
            lastError = "No frame available after 2s (vd=${virtualDisplay != null}, ir=${imageReader != null}, mp=${mediaProjection != null})"
            return null
        }

        return try {
            val planes = img!!.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * vdWidth

            val bitmapWidth = vdWidth + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bitmapWidth, vdHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, vdWidth, vdHeight)
            } else {
                bitmap
            }
        } finally {
            img?.close()
        }
    }

    fun releaseProjection() {
        synchronized(frameLock) {
            latestImage?.close()
            latestImage = null
        }
        // VirtualDisplay.Callback.onStopped() will null out our references
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        mediaProjection = null
        virtualDisplay = null
        imageReader = null
    }
}