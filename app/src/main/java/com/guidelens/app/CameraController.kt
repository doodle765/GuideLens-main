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
 * COCO-SSD object detection -> tiered alerts -> overlay.
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

            // spoken alerts
            var anyDanger = false
            for (d in dets) {
                val dist = d.dist ?: continue
                alerts.alert(d.label, d.side, dist)
                if (dist <= 3f) anyDanger = true
            }
            zone?.let { z ->
                if (System.currentTimeMillis() > alerts.namedDangerUntil) {
                    alerts.alert("obstacle", "ahead", z.dist)
                    if (z.dist <= 3f) anyDanger = true
                }
            }

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
            overlay.update(boxes, zone)
            if (anyDanger) activity.runOnUiThread { onStatus("Stop! Obstacle ahead.") }
        } catch (e: Exception) {
            reportOnce("frame", e)
        } finally {
            proxy.close()
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
        overlay.clear()
    }

    fun shutdown() {
        stop()
        inferenceExecutor.shutdown()
    }
}

/** YUV_420_888 -> upright Bitmap (NV21 -> JPEG -> rotate to display orientation). */
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
