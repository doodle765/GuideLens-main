package com.guidelens.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Object detection, fully on-device.
 *
 * Model: SSD MobileNet TF1 export (TFLite_Detection_PostProcess, 0-based COCO
 * class indices, bundled at app/src/main/assets/detect.tflite).
 *
 * TF1 exports have 4 outputs with IDENTICAL names, resolved via the fallback
 * branch; TF2 exports (named tensors, 1-based ids) are handled by name matching.
 *
 * The monocular math ([estDistance], [sideOf]) lives in the companion object
 * so it is unit-testable without a model or a device (see DetectorMathTest).
 */
class Detector(context: Context) {

    companion object {
        private const val TAG = "GuideLens/Detector"

        const val HFOV_DEG = 62.0
        const val MIN_SCORE = 0.5f
        const val MAX_RESULTS = 25

        // Assumed real-world heights (m) used for monocular distance estimation
        val KNOWN_H = mapOf(
            "person" to 1.7, "bicycle" to 1.1, "car" to 1.5, "motorcycle" to 1.2,
            "bus" to 3.0, "truck" to 2.5, "dog" to 0.6, "cat" to 0.35,
            "bench" to 0.9, "chair" to 0.9, "potted plant" to 0.5,
            "suitcase" to 0.7, "backpack" to 0.5, "handbag" to 0.4
        )

        val FRIENDLY = mapOf(
            "potted plant" to "plant", "handbag" to "bag", "backpack" to "backpack",
            "suitcase" to "luggage", "person" to "person", "dog" to "dog", "cat" to "cat",
            "bicycle" to "bicycle", "car" to "car", "bus" to "bus", "truck" to "truck",
            "motorcycle" to "motorcycle", "bench" to "bench", "chair" to "chair"
        )

        // 1-based map with background/"object" at 0 (used for TF2 named exports)
        val LABELS = listOf(
            "object",
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train",
            "truck", "boat", "traffic light", "fire hydrant", "street sign",
            "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse",
            "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "hat",
            "backpack", "umbrella", "shoe", "eye glasses", "handbag", "tie",
            "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite",
            "baseball bat", "baseball glove", "skateboard", "surfboard",
            "tennis racket", "bottle", "plate", "wine glass", "cup", "fork",
            "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "couch", "potted plant", "bed", "mirror", "dining table", "window",
            "desk", "toilet", "door", "tv", "laptop", "mouse", "remote",
            "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "blender", "book", "clock", "vase", "scissors",
            "teddy bear", "hair drier", "toothbrush"
        )

        // 0-based COCO list for TF1 TFLite_Detection_PostProcess exports:
        // index 0 = person (this is what the bundled detect.tflite outputs).
        val LABELS_0BASED = LABELS.drop(1)

        /** Map normalized horizontal offset (-1..1) to a spoken side. */
        internal fun sideOf(r: Float): String = when {
            r < -0.35f -> "on your left"
            r > 0.35f -> "on your right"
            else -> "ahead"
        }

        /** Monocular distance from apparent size. Approximate bands only (7m / 5m / 3m). */
        internal fun estDistance(cls: String, bhPx: Float, frameH: Int, frameW: Int): Float? {
            val real = KNOWN_H[cls] ?: return null
            if (bhPx <= 0) return null
            val vfov = 2 * atan(tan(Math.toRadians(HFOV_DEG / 2.0)) * (frameH.toDouble() / frameW))
            val fPx = frameH / (2 * tan(vfov / 2))
            val d = (real * fPx / bhPx).toFloat()
            if (d.isNaN() || d < 0.2f || d > 25f) return null
            return d
        }
    }

    private var interpreter: Interpreter? = null

    val available: Boolean
    val error: String

    init {
        var ok = false
        var err = ""
        try {
            val fd = context.assets.openFd("detect.tflite")
            val model = FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            interpreter = Interpreter(model, Interpreter.Options().apply { numThreads = 4 })
            ok = true
            val itp = interpreter!!
            val inT = itp.getInputTensor(0)
            Log.i(TAG, "model loaded: input ${inT.shape().contentToString()} ${inT.dataType()}, " +
                    "$itp.outputTensorCount outputs")
        } catch (e: Exception) {
            err = e.message ?: "model load failed"
            Log.e(TAG, "model load failed", e)
        }
        available = ok
        error = err
    }

    data class Detection(
        val label: String,
        val score: Float,
        val box: RectF,
        val dist: Float?,
        val side: String
    )

    fun detect(frame: Bitmap): List<Detection> {
        val itp = interpreter ?: return emptyList()
        val fw = frame.width
        val fh = frame.height

        // ---- input (size & type read from the model itself) ----
        val inShape = itp.getInputTensor(0).shape()          // [1, h, w, 3]
        val ih = inShape[1]
        val iw = inShape[2]
        val scaled = Bitmap.createScaledBitmap(frame, iw, ih, true)
        val px = IntArray(iw * ih)
        scaled.getPixels(px, 0, iw, 0, 0, iw, ih)

        val input: Any = if (itp.getInputTensor(0).dataType() == DataType.UINT8) {
            val b = ByteBuffer.allocateDirect(iw * ih * 3).order(ByteOrder.nativeOrder())
            for (p in px) {
                b.put((p shr 16 and 0xFF).toByte())
                b.put((p shr 8 and 0xFF).toByte())
                b.put((p and 0xFF).toByte())
            }
            b.rewind()
            b
        } else {
            val f = ByteBuffer.allocateDirect(iw * ih * 3 * 4).order(ByteOrder.nativeOrder())
            for (p in px) {
                f.putFloat((p shr 16 and 0xFF) / 255f)
                f.putFloat((p shr 8 and 0xFF) / 255f)
                f.putFloat((p and 0xFF) / 255f)
            }
            f.rewind()
            f
        }
        if (scaled !== frame) scaled.recycle()

        // ---- locate output tensors by name (TF2 export) ----
        var iBoxes = -1; var iClasses = -1; var iScores = -1; var iNum = -1
        for (i in 0 until itp.outputTensorCount) {
            val nm = itp.getOutputTensor(i).name().lowercase()
            when {
                "num" in nm -> iNum = i
                "box" in nm -> iBoxes = i
                "score" in nm && "multi" !in nm -> if (iScores < 0) iScores = i
                "class" in nm -> if (iClasses < 0) iClasses = i
            }
        }

        // ---- labels: TF1 exports (all outputs share one name, or >=4 unnamed
        //      outputs) use 0-based class ids; named TF2 exports use the 1-based map
        val labels: List<String>
        if (iBoxes < 0 || iScores < 0) {
            if (itp.outputTensorCount >= 4) {
                iBoxes = 0; iClasses = 1; iScores = 2; iNum = 3
                labels = LABELS_0BASED
            } else {
                Log.w(TAG, "unrecognized model: ${itp.outputTensorCount} outputs, no boxes/scores")
                return emptyList()
            }
        } else {
            labels = LABELS
        }

        val maxD = run {
            val s = itp.getOutputTensor(iBoxes).shape()
            if (s.size >= 3) s[1] else MAX_RESULTS
        }

        val boxes = Array(1) { Array(maxD) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(maxD) }
        val scores = Array(1) { FloatArray(maxD) }
        val nums = FloatArray(1)

        val outputs = mutableMapOf<Int, Any>()
        outputs[iBoxes] = boxes
        if (iClasses >= 0) outputs[iClasses] = classes
        outputs[iScores] = scores
        if (iNum >= 0) outputs[iNum] = nums

        itp.runForMultipleInputsOutputs(arrayOf(input), outputs)

        val n = (if (iNum >= 0) nums[0].toInt() else maxD).coerceIn(0, maxD)
        Log.d(TAG, "raw: n=$n maxScore=${scores[0].maxOrNull() ?: 0f}")
        val out = mutableListOf<Detection>()
        for (i in 0 until n) {
            val score = scores[0][i]
            if (score < MIN_SCORE) continue
            val clsInt = if (iClasses >= 0) classes[0][i].toInt() else 0
            val raw = labels.getOrNull(clsInt) ?: continue

            // boxes are normalized [ymin, xmin, ymax, xmax]
            val top = boxes[0][i][0].coerceIn(0f, 1f)
            val left = boxes[0][i][1].coerceIn(0f, 1f)
            val bottom = boxes[0][i][2].coerceIn(0f, 1f)
            val right = boxes[0][i][3].coerceIn(0f, 1f)
            val box = RectF(left * fw, top * fh, right * fw, bottom * fh)
            // ignore tiny detections (texture noise, shadows, far-away clutter)
            if (box.height() < fh * 0.05f && box.width() < fw * 0.05f) continue

            val label = FRIENDLY[raw] ?: raw
            val dist = estDistance(raw, box.height(), fh, fw)
            val r = (box.centerX() - fw / 2f) / (fw / 2f)
            val side = sideOf(r)
            out.add(Detection(label, score, box, dist, side))
        }
        Log.d(TAG, "detections passed: ${out.size}")
        return out
    }
}

fun distWord(d: Float): String =
    if (d < 2f) "right in front of you" else "about " + (d.roundToInt().coerceAtLeast(1)) + " meters"
