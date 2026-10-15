package com.guidelens.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

/**
 * Camera + detection pipeline, fully offline:
 * CameraX Preview (live feed) + ImageAnalysis (frame stream) ->
 * COCO-SSD object detection -> tiered, tracked alerts -> overlay.
 *
 * Also:
 *  - caches a small copy of the latest frame ([lastFrame]) so OCR ("read this"
 *    and periodic sign-reading) never depends on PreviewView.getBitmap(),
 *    which returns null on many Xiaomi/MIUI devices (the old "OCR reads only
 *    once" bug)
 *  - frame-luminance watchdog: tells the user when the lens is covered or it
 *    is too dark, instead of silently seeing nothing
 *
 * NOTE: PreviewView.getBitmap() returns null on many Xiaomi/MIUI devices,
 * so frames come from an ImageAnalysis use case instead.
 */
class CameraController(
    private val activity: androidx.activity.ComponentActivity,
    private val previewView: PreviewView,
    private val overlay: OverlayView,
    private val prefs: Prefs,
    private val speaker: Speaker,
    private val alerts: AlertCenter,
    private val sonar: Sonar?,
    private val onStatus: (String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val inferenceExecutor = Executors.newSingleThreadExecutor()

    private val detector = Detector(activity)
    private val segmenter = Segmenter(activity)

    private var provider: ProcessCameraProvider? = null
    private var lastLoopError: String? = null

    @Volatile var running = false
        private set

    @Volatile var lastSeen: List<Detector.Detection> = emptyList()
    @Volatile var lastZone: Segmenter.Zone? = null

    /** Small copy of the latest camera frame, for OCR. May be null before the first frame. */
    @Volatile var lastFrame: Bitmap? = null
        private set

    private var darkFrames = 0
    private var brightFrames = 0
    private var darkLatch = false

    fun start() {
        if (running) return
        running = true
        if (!detector.available) {
            onStatus("Object model missing. Run the downloadModels gradle task and rebuild.")
            speaker.speak("Object detection model is missing. It should have been downloaded when the app was built.")
            return
        }
        onStatus("Starting camera…")
        scope.launch(Dispatchers.Main) {
            try {
                provider = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    ProcessCameraProvider.getInstance(activity).get()
                }
                bindUseCases()
                val segNote = if (segmenter.available) "" else " Scene model unavailable on this device."
                onStatus("Detection running. Hold the phone at chest height, camera facing forward." + segNote)
                speaker.speak("Detection running. I'll warn you about obstacles.")
            } catch (e: Exception) {
                running = false
                onStatus("Camera unavailable: " + (e.message ?: "unknown error"))
                speaker.speak("I couldn't start the camera. Please check camera permission.")
            }
        }
    }

    private fun bindUseCases() {
        val p = provider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(inferenceExecutor) { proxy -> processFrame(proxy) }

        p.unbindAll()
        p.bindToLifecycle(
            activity as LifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis
        )
    }

    /** Runs on the inference thread for every camera frame. */
    private fun processFrame(proxy: ImageProxy) {
        try {
            val bmp = proxy.toBitmapRotated()
            val luma = luminance(bmp)

            if (luma < DARK_LUMA) {
                darkFrames++
                brightFrames = 0
                if (darkFrames >= DARK_FRAMES && !darkLatch) {
                    darkLatch = true
                    onStatus("Camera covered or too dark — detection paused.")
                    speaker.speak("It's too dark, or the camera is covered. I can't see anything.", priority = true)
                }
            } else {
                darkFrames = 0
                if (darkLatch) {
                    brightFrames++
                    // dwell: several consecutive bright frames above the threshold,
                    // so dim indoor light can't flap the latch on and off
                    if (luma > RECOVER_LUMA && brightFrames >= BRIGHT_FRAMES) {
                        darkLatch = false
                        brightFrames = 0
                        speaker.speak("I can see again. Detection resumed.")
                    }
                } else {
                    brightFrames = 0
                }
            }

            if (darkLatch) {
                // can't see: skip inference, reset tracking, quiet the sonar
                lastSeen = emptyList()
                lastZone = null
                lastFrame = null
                alerts.reset()
                sonar?.silence()
                activity.runOnUiThread { overlay.clear() }
                return
            }

            // cache a small frame for OCR (read-this button + periodic sign reading)
            val small = Bitmap.createScaledBitmap(bmp, 480, bmp.height * 480 / bmp.width, true)
            lastFrame?.recycle()
            lastFrame = small

            val dets = try {
                detector.detect(bmp)
            } catch (e: Exception) {
                reportOnce("detect", e)
                emptyList()
            }
            val zone = if (segmenter.available) {
                try { segmenter.analyze(bmp) } catch (e: Exception) { null }
            } else null

            lastSeen = dets
            lastZone = zone

            // tracked spoken alerts + sonar feed
            alerts.onFrame(dets, zone, sonar)

            var anyDanger = false
            for (d in dets) if ((d.dist ?: 99f) <= 3f) anyDanger = true
            if ((zone?.dist ?: 99f) <= 3f) anyDanger = true
            if (anyDanger) activity.runOnUiThread { onStatus("Stop! Obstacle ahead.") }

            // overlay: scale detection coordinates to the overlay view size
            val ow = overlay.width.takeIf { it > 0 } ?: bmp.width
            val oh = overlay.height.takeIf { it > 0 } ?: bmp.height
            val sx = ow / bmp.width.toFloat()
            val sy = oh / bmp.height.toFloat()

            val boxes = dets.map { d ->
                val sev = when {
                    (d.dist ?: 99f) <= 3f -> 2
                    (d.dist ?: 99f) <= 5f -> 1
                    else -> 0
                }
                val label = d.dist?.let { "${d.label} %.1f m".format(it) } ?: d.label
                OverlayView.Box(
                    label,
                    RectF(d.box.left * sx, d.box.top * sy, d.box.right * sx, d.box.bottom * sy),
                    sev
                )
            }
            activity.runOnUiThread { overlay.update(boxes, zone) }
        } catch (e: Exception) {
            reportOnce("frame", e)
        } finally {
            proxy.close()
        }
    }

    /** Mean luminance 0..1 from a heavily downscaled copy of the frame. */
    private fun luminance(bmp: Bitmap): Float {
        return try {
            val tiny = Bitmap.createScaledBitmap(bmp, 32, 18, true)
            val px = IntArray(tiny.width * tiny.height)
            tiny.getPixels(px, 0, tiny.width, 0, 0, tiny.width, tiny.height)
            tiny.recycle()
            var sum = 0L
            for (p in px) {
                sum += ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
            }
            sum.toFloat() / px.size / 255f
        } catch (e: Exception) {
            1f   // if measurement fails, keep detecting
        }
    }

    private fun reportOnce(where: String, e: Exception) {
        val msg = e.message ?: e.javaClass.simpleName
        if (msg != lastLoopError) {
            lastLoopError = msg
            Log.e("GuideLens/Camera", "$where failed", e)
            activity.runOnUiThread { onStatus("Detection error: $msg") }
        }
    }

    fun stop() {
        running = false
        try { provider?.unbindAll() } catch (e: Exception) { }
        lastSeen = emptyList()
        lastZone = null
        lastFrame?.recycle()
        lastFrame = null
        alerts.reset()
        overlay.clear()
    }

    fun shutdown() {
        stop()
        inferenceExecutor.shutdown()
    }

    companion object {
        private const val DARK_LUMA = 0.035f      // below: covered / very dark
        private const val RECOVER_LUMA = 0.09f    // hysteresis so it doesn't flap
        private const val DARK_FRAMES = 25        // ~2-3 s of dark frames before warning
        private const val BRIGHT_FRAMES = 5       // consecutive bright frames to recover
    }
}

/** YUV_420_888 -> upright Bitmap with correct chroma handling (no color swap). */
private fun ImageProxy.toBitmapRotated(): Bitmap {
    val yP = planes[0]
    val uP = planes[1]
    val vP = planes[2]
    val ySize = width * height
    val nv21 = ByteArray(ySize + ySize / 2)

    // luma
    if (yP.rowStride == width && yP.pixelStride == 1) {
        yP.buffer.get(nv21, 0, ySize)
    } else {
        var p = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                nv21[p++] = yP.buffer.get(row * yP.rowStride + col * yP.pixelStride)
            }
        }
    }

    // chroma: NV21 = interleaved VU, honoring row/pixel stride
    val cw = width / 2
    val ch = height / 2
    for (row in 0 until ch) {
        for (col in 0 until cw) {
            nv21[ySize + 2 * (row * cw + col)] = vP.buffer.get(row * vP.rowStride + col * vP.pixelStride)
            nv21[ySize + 2 * (row * cw + col) + 1] = uP.buffer.get(row * uP.rowStride + col * uP.pixelStride)
        }
    }

    val yuv = YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, width, height), 90, out)
    var bmp = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())

    val rot = imageInfo.rotationDegrees
    if (rot != 0) {
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
    return bmp
}
