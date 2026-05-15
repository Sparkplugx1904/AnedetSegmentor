# AGENTS.md — AnedetSegmentor Complete Overhaul
> Instruksi perombakan besar-besaran untuk AI coding agent.
> Baca dokumen ini PENUH sebelum menulis satu baris kode.
> Jangan ringkas, jangan skip bagian manapun.

---

## KONTEKS & TUJUAN

Repo ini adalah aplikasi Android untuk segmentasi konjungtiva mata secara real-time
menggunakan model YOLOv8n-seg yang dikonversi ke TFLite.

**Masalah utama saat ini:**
1. Inference berjalan di CPU → lambat (~600ms/frame), tidak layak real-time
2. Input kamera 9:16 (portrait) tidak ditangani dengan benar → bbox/polygon salah posisi
3. BBox parsing salah: kode membaca output NMS (x1,y1,x2,y2) sebagai (cx,cy,w,h)
4. Polygon mapping salah: proto mask global dipetakan sebagai mask lokal bbox
5. `rowStride` tidak ditangani → frame korup di device nyata
6. Dimensi overlay pakai pre-rotasi width/height → scale salah
7. Tidak ada crop preview overlay di sudut layar
8. Proto shape hardcoded [80,80], model aktual bisa [160,160]
9. Model TFLite memiliki mixed int8/float32 tensors → crash jika tidak ditangani

**Target setelah overhaul:**
- Inference GPU via TFLite GPU Delegate (fallback CPU jika tidak tersedia)
- Pipeline input 9:16 yang benar dengan letterbox ke INPUT_SIZE×INPUT_SIZE
- BBox dan polygon tampil tepat di atas konjungtiva
- Crop konjungtiva tampil di pojok kiri atas layar secara real-time
- Tidak crash karena mixed quantization
- FPS target ≥ 15 FPS di mid-range device

---

## FILE YANG HARUS DIMODIFIKASI

```
app/src/main/java/com/conjunctiva/segmentation/
  ├── ConjunctivaSegmentor.kt   ← ROMBAK TOTAL
  ├── ImageUtils.kt             ← ROMBAK TOTAL
  ├── OverlayView.kt            ← ROMBAK TOTAL
  └── MainActivity.kt           ← MODIFIKASI SIGNIFIKAN

app/build.gradle (atau build.gradle.kts)  ← TAMBAH DEPENDENCY
```

---

## BAGIAN 1 — `build.gradle` (app level)

Tambahkan dependency TFLite GPU Delegate. Jangan hapus dependency yang sudah ada.

```kotlin
dependencies {
    // TFLite GPU Delegate — tambahkan ini
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.4")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    // ... dependency lainnya tetap
}
```

Tambahkan di `android {}` block:
```kotlin
android {
    // ... existing config
    aaptOptions {
        noCompress "tflite"
    }
}
```

---

## BAGIAN 2 — `ConjunctivaSegmentor.kt` — TULIS ULANG PENUH

Hapus seluruh isi file dan ganti dengan implementasi berikut.
Jangan pertahankan kode lama sama sekali.

### 2.1 Konstanta & Struktur Data

```kotlin
package com.conjunctiva.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2

private const val TAG = "ConjunctivaSegmentor"
private const val MODEL_FILE = "best_float16.tflite"  // sesuaikan nama model
private const val INPUT_SIZE = 320           // harus cocok dengan export size model
private const val MAX_DETECTIONS = 300       // output NMS rows
private const val NUM_CLASSES = 1            // hanya "conjunctiva"
private const val NUM_MASK_COEFFS = 32       // mask coefficients per detection
private const val DETECTION_SIZE = 4 + 1 + NUM_CLASSES + NUM_MASK_COEFFS  // = 38
private const val CONF_THRESHOLD = 0.30f
private const val IOU_THRESHOLD = 0.45f

data class SegmentResult(
    val boundingBox: RectF,          // koordinat pixel dalam original frame
    val polygon: List<PointF>,       // koordinat pixel dalam original frame
    val confidence: Float,
    val cropBitmap: Bitmap?          // crop konjungtiva untuk preview pojok layar
)
```

### 2.2 Class Utama dengan GPU Delegate

```kotlin
class ConjunctivaSegmentor(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    // Runtime tensor info — dibaca dari model, JANGAN hardcode
    private var inputDataType: DataType = DataType.FLOAT32
    private var protoH: Int = 80
    private var protoW: Int = 80

    // Input buffer
    private var inputBuffer: ByteBuffer? = null

    // Output buffers — dialokasikan SETELAH membaca shape dari model
    // Output 0: detections [1, MAX_DETECTIONS, DETECTION_SIZE]
    private lateinit var outputBoxes: Array<Array<FloatArray>>
    // Output 1: proto masks [1, protoH, protoW, 32]
    private lateinit var outputProtos: Array<Array<Array<FloatArray>>>

    init {
        setupInterpreter()
    }

    // ─── SETUP ────────────────────────────────────────────────────────────────

    private fun setupInterpreter() {
        val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
        val options = Interpreter.Options()

        // Coba GPU Delegate dulu
        val compatList = CompatibilityList()
        if (compatList.isDelegateSupportedOnThisDevice) {
            val delegateOptions = compatList.bestOptionsForThisDevice
            gpuDelegate = GpuDelegate(delegateOptions)
            options.addDelegate(gpuDelegate!!)
            Log.i(TAG, "GPU Delegate: AKTIF")
        } else {
            options.numThreads = 4
            Log.w(TAG, "GPU Delegate tidak didukung, fallback ke CPU 4 threads")
        }

        interpreter = Interpreter(modelBuffer, options)
        readModelShapes()
        allocateBuffers()
    }

    private fun readModelShapes() {
        val interp = interpreter ?: return

        // Baca input tensor info
        val inputTensor = interp.getInputTensor(0)
        inputDataType = inputTensor.dataType()
        val inputShape = inputTensor.shape()
        // inputShape = [1, INPUT_SIZE, INPUT_SIZE, 3]
        Log.d(TAG, "Input tensor: shape=${inputShape.contentToString()}, dtype=$inputDataType")

        // Baca output tensor 0: detections
        val outShape0 = interp.getOutputTensor(0).shape()
        Log.d(TAG, "Output[0] detections shape: ${outShape0.contentToString()}")
        // Expected: [1, 300, 38]

        // Baca output tensor 1: proto masks
        val outShape1 = interp.getOutputTensor(1).shape()
        Log.d(TAG, "Output[1] proto shape: ${outShape1.contentToString()}")
        // Expected: [1, H, W, 32] — H dan W bisa 80 atau 160
        protoH = outShape1[1]
        protoW = outShape1[2]
        Log.i(TAG, "Proto mask size: ${protoH}×${protoW}")
    }

    private fun allocateBuffers() {
        val interp = interpreter ?: return

        // Input buffer — handle mixed quantization
        // Jika model INT8/UINT8, TFLite GPU delegate biasanya butuh FLOAT32 input
        // dan akan quantize secara internal
        val pixelCount = INPUT_SIZE * INPUT_SIZE * 3
        inputBuffer = ByteBuffer.allocateDirect(pixelCount * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        // Output buffers berdasarkan shape aktual dari model
        val detShape = interp.getOutputTensor(0).shape()
        val maxDets = detShape[1]   // biasanya 300
        val detSize = detShape[2]   // biasanya 38

        outputBoxes = Array(1) { Array(maxDets) { FloatArray(detSize) } }
        outputProtos = Array(1) { Array(protoH) { Array(protoW) { FloatArray(NUM_MASK_COEFFS) } } }

        Log.d(TAG, "Buffers allocated: boxes[1,$maxDets,$detSize], protos[1,$protoH,$protoW,32]")
    }

    // ─── INFERENCE ────────────────────────────────────────────────────────────

    /**
     * Segmentasi utama.
     * @param bitmap Frame dari kamera, SUDAH dirotasi ke orientasi benar (post-rotation)
     * @return List SegmentResult dalam koordinat pixel bitmap asli
     */
    fun segment(bitmap: Bitmap): List<SegmentResult> {
        val interp = interpreter ?: return emptyList()
        val buf = inputBuffer ?: return emptyList()

        val origW = bitmap.width
        val origH = bitmap.height

        // 1. Preprocess: letterbox ke INPUT_SIZE×INPUT_SIZE
        val (scaledBitmap, letterboxParams) = letterboxResize(bitmap, INPUT_SIZE)

        // 2. Fill input buffer sebagai FLOAT32 / 255.0 — selalu float
        //    GPU delegate handle quantization internal
        buf.rewind()
        fillInputBuffer(buf, scaledBitmap)
        scaledBitmap.recycle()

        // 3. Reset output buffers
        for (row in outputBoxes[0]) row.fill(0f)

        // 4. Run inference
        val inputs = mapOf(0 to buf)
        val outputs = mapOf(
            0 to outputBoxes,
            1 to outputProtos
        )
        try {
            interp.runForMultipleInputsOutputs(arrayOf(buf), outputs)
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            return emptyList()
        }

        // 5. Postprocess
        return postprocess(outputBoxes[0], outputProtos[0], letterboxParams, origW, origH, bitmap)
    }

    // ─── PREPROCESSING ────────────────────────────────────────────────────────

    data class LetterboxParams(
        val scale: Float,
        val xPad: Int,   // padding pixel di kiri (dalam INPUT_SIZE space)
        val yPad: Int    // padding pixel di atas (dalam INPUT_SIZE space)
    )

    private fun letterboxResize(src: Bitmap, targetSize: Int): Pair<Bitmap, LetterboxParams> {
        val srcW = src.width.toFloat()
        val srcH = src.height.toFloat()
        val scale = minOf(targetSize / srcW, targetSize / srcH)

        val newW = (srcW * scale).toInt()
        val newH = (srcH * scale).toInt()
        val xPad = (targetSize - newW) / 2
        val yPad = (targetSize - newH) / 2

        val dst = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(dst)
        canvas.drawColor(android.graphics.Color.BLACK)

        val scaled = Bitmap.createScaledBitmap(src, newW, newH, true)
        canvas.drawBitmap(scaled, xPad.toFloat(), yPad.toFloat(), null)
        scaled.recycle()

        return Pair(dst, LetterboxParams(scale, xPad, yPad))
    }

    private fun fillInputBuffer(buf: ByteBuffer, bitmap: Bitmap) {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (px in pixels) {
            buf.putFloat(((px shr 16) and 0xFF) / 255.0f)  // R
            buf.putFloat(((px shr 8) and 0xFF) / 255.0f)   // G
            buf.putFloat((px and 0xFF) / 255.0f)            // B
        }
    }

    // ─── POSTPROCESSING ───────────────────────────────────────────────────────

    private fun postprocess(
        detections: Array<FloatArray>,
        protos: Array<Array<FloatArray>>,   // [protoH][protoW][32]
        lp: LetterboxParams,
        origW: Int, origH: Int,
        originalBitmap: Bitmap
    ): List<SegmentResult> {

        val results = mutableListOf<SegmentResult>()

        for (det in detections) {
            val confidence = det[4]
            if (confidence < CONF_THRESHOLD) continue

            // ── BBox ──────────────────────────────────────────────────────────
            // NMS-embedded YOLOv8 output format: x1,y1,x2,y2 normalized [0,1]
            // JANGAN ubah ke cx,cy,w,h — ini sudah x1,y1,x2,y2
            val x1n = det[0].coerceIn(0f, 1f)
            val y1n = det[1].coerceIn(0f, 1f)
            val x2n = det[2].coerceIn(0f, 1f)
            val y2n = det[3].coerceIn(0f, 1f)

            if (x2n <= x1n || y2n <= y1n) continue

            // Inverse letterbox: dari normalized INPUT_SIZE space → original image pixels
            // Step 1: ke INPUT_SIZE pixel space
            val x1px = x1n * INPUT_SIZE
            val y1px = y1n * INPUT_SIZE
            val x2px = x2n * INPUT_SIZE
            val y2px = y2n * INPUT_SIZE

            // Step 2: subtract padding, divide by scale
            val origX1 = ((x1px - lp.xPad) / lp.scale).coerceIn(0f, origW.toFloat())
            val origY1 = ((y1px - lp.yPad) / lp.scale).coerceIn(0f, origH.toFloat())
            val origX2 = ((x2px - lp.xPad) / lp.scale).coerceIn(0f, origW.toFloat())
            val origY2 = ((y2px - lp.yPad) / lp.scale).coerceIn(0f, origH.toFloat())

            val bbox = RectF(origX1, origY1, origX2, origY2)

            // ── Mask ──────────────────────────────────────────────────────────
            val maskCoeffs = FloatArray(NUM_MASK_COEFFS) { det[6 + it] }
            val mask = reconstructMask(protos, maskCoeffs)   // [protoH][protoW] float sigmoid

            // ── Polygon ───────────────────────────────────────────────────────
            val polygon = maskToPolygon(mask, x1n, y1n, x2n, y2n, origW, origH, lp)

            // ── Crop Bitmap untuk preview pojok layar ─────────────────────────
            val crop = cropConjunctiva(originalBitmap, bbox)

            results.add(SegmentResult(bbox, polygon, confidence, crop))
        }

        // Kembalikan hanya deteksi dengan area polygon terbesar (konjungtiva utama)
        return if (results.isEmpty()) emptyList()
        else listOf(results.maxByOrNull { polygonArea(it.polygon) }!!)
    }

    // ─── MASK RECONSTRUCTION ──────────────────────────────────────────────────

    /**
     * Reconstruct mask dari koefisien + proto.
     * mask = sigmoid(proto @ coeffs)
     * proto shape: [protoH][protoW][32]
     * coeffs shape: [32]
     * output: [protoH][protoW] float, nilai 0-1 setelah sigmoid
     */
    private fun reconstructMask(
        protos: Array<Array<FloatArray>>,
        coeffs: FloatArray
    ): Array<FloatArray> {
        val mask = Array(protoH) { FloatArray(protoW) }
        for (py in 0 until protoH) {
            for (px in 0 until protoW) {
                var dot = 0f
                val protoVec = protos[py][px]
                for (k in 0 until NUM_MASK_COEFFS) {
                    dot += protoVec[k] * coeffs[k]
                }
                // sigmoid
                mask[py][px] = 1f / (1f + kotlin.math.exp(-dot.toDouble()).toFloat())
            }
        }
        return mask
    }

    // ─── MASK → POLYGON ───────────────────────────────────────────────────────

    /**
     * Konversi binary mask ke polygon points dalam koordinat original image.
     *
     * PENTING: Proto mask [protoH×protoW] adalah representasi SELURUH INPUT_SIZE space,
     * bukan hanya bbox region. Konversi koordinat:
     *   proto pixel (px, py) → normalized (px/protoW, py/protoH)
     *   → INPUT_SIZE pixel → inverse letterbox → original image pixel
     *
     * @param x1n,y1n,x2n,y2n bbox dalam normalized [0,1] untuk membatasi scan region
     */
    private fun maskToPolygon(
        mask: Array<FloatArray>,
        x1n: Float, y1n: Float, x2n: Float, y2n: Float,
        origW: Int, origH: Int,
        lp: LetterboxParams
    ): List<PointF> {

        val threshold = 0.5f

        // Batasi scan ke region bbox dalam proto space
        val px1 = (x1n * protoW).toInt().coerceIn(0, protoW - 1)
        val py1 = (y1n * protoH).toInt().coerceIn(0, protoH - 1)
        val px2 = (x2n * protoW).toInt().coerceIn(0, protoW)
        val py2 = (y2n * protoH).toInt().coerceIn(0, protoH)

        val boundaryPoints = mutableListOf<PointF>()

        for (py in py1 until py2) {
            for (px in px1 until px2) {
                if (mask[py][px] <= threshold) continue

                // Cek apakah ini boundary pixel (ada tetangga yang di bawah threshold)
                val isBoundary = (py == 0 || mask[py - 1][px] <= threshold) ||
                    (py == protoH - 1 || mask[py + 1][px] <= threshold) ||
                    (px == 0 || mask[py][px - 1] <= threshold) ||
                    (px == protoW - 1 || mask[py][px + 1] <= threshold)

                if (isBoundary) {
                    // Konversi: proto pixel → INPUT_SIZE normalized → inverse letterbox → original
                    val normX = (px + 0.5f) / protoW
                    val normY = (py + 0.5f) / protoH
                    val ipX = normX * INPUT_SIZE
                    val ipY = normY * INPUT_SIZE
                    val imgX = ((ipX - lp.xPad) / lp.scale).coerceIn(0f, origW.toFloat())
                    val imgY = ((ipY - lp.yPad) / lp.scale).coerceIn(0f, origH.toFloat())
                    boundaryPoints.add(PointF(imgX, imgY))
                }
            }
        }

        if (boundaryPoints.isEmpty()) {
            // Fallback ke bbox sebagai polygon
            val bx1 = ((x1n * INPUT_SIZE - lp.xPad) / lp.scale).coerceIn(0f, origW.toFloat())
            val by1 = ((y1n * INPUT_SIZE - lp.yPad) / lp.scale).coerceIn(0f, origH.toFloat())
            val bx2 = ((x2n * INPUT_SIZE - lp.xPad) / lp.scale).coerceIn(0f, origW.toFloat())
            val by2 = ((y2n * INPUT_SIZE - lp.yPad) / lp.scale).coerceIn(0f, origH.toFloat())
            return listOf(
                PointF(bx1, by1), PointF(bx2, by1),
                PointF(bx2, by2), PointF(bx1, by2)
            )
        }

        // Sort angular dari centroid → polygon terhubung dengan benar (clockwise)
        val cx = boundaryPoints.map { it.x }.average().toFloat()
        val cy = boundaryPoints.map { it.y }.average().toFloat()
        val sorted = boundaryPoints.sortedBy { pt ->
            atan2((pt.y - cy).toDouble(), (pt.x - cx).toDouble())
        }

        // Douglas-Peucker simplification: target 12-20 titik
        return douglasPeucker(sorted, epsilon = 3.0f)
    }

    // ─── CROP UNTUK PREVIEW ───────────────────────────────────────────────────

    private fun cropConjunctiva(src: Bitmap, bbox: RectF): Bitmap? {
        val left = bbox.left.toInt().coerceIn(0, src.width - 1)
        val top = bbox.top.toInt().coerceIn(0, src.height - 1)
        val right = bbox.right.toInt().coerceIn(left + 1, src.width)
        val bottom = bbox.bottom.toInt().coerceIn(top + 1, src.height)
        if (right <= left || bottom <= top) return null
        return Bitmap.createBitmap(src, left, top, right - left, bottom - top)
    }

    // ─── DOUGLAS-PEUCKER ──────────────────────────────────────────────────────

    private fun douglasPeucker(points: List<PointF>, epsilon: Float): List<PointF> {
        if (points.size <= 4) return points
        var maxDist = 0f
        var maxIdx = 0
        val first = points.first()
        val last = points.last()
        for (i in 1 until points.size - 1) {
            val d = perpendicularDistance(points[i], first, last)
            if (d > maxDist) { maxDist = d; maxIdx = i }
        }
        return if (maxDist > epsilon) {
            val left = douglasPeucker(points.subList(0, maxIdx + 1), epsilon)
            val right = douglasPeucker(points.subList(maxIdx, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(first, last)
        }
    }

    private fun perpendicularDistance(pt: PointF, lineStart: PointF, lineEnd: PointF): Float {
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y
        val len = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (len < 1e-6f) return kotlin.math.sqrt(
            ((pt.x - lineStart.x).let { it * it } + (pt.y - lineStart.y).let { it * it }).toDouble()
        ).toFloat()
        return kotlin.math.abs(dy * pt.x - dx * pt.y + lineEnd.x * lineStart.y - lineEnd.y * lineStart.x) / len
    }

    private fun polygonArea(pts: List<PointF>): Float {
        if (pts.size < 3) return 0f
        var area = 0f
        val n = pts.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += pts[i].x * pts[j].y - pts[j].x * pts[i].y
        }
        return kotlin.math.abs(area) / 2f
    }

    // ─── CLEANUP ──────────────────────────────────────────────────────────────

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
        interpreter = null
        gpuDelegate = null
        Log.d(TAG, "Resources released")
    }
}
```

---

## BAGIAN 3 — `ImageUtils.kt` — TULIS ULANG PENUH

Hapus isi lama, ganti dengan ini:

```kotlin
package com.conjunctiva.segmentation

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

object ImageUtils {

    /**
     * Konversi ImageProxy (RGBA_8888) ke Bitmap dengan rotasi yang benar.
     *
     * KRITIS: Handle rowStride — di device nyata, rowStride > width * pixelStride
     * karena ada padding bytes di ujung setiap row. Tanpa ini gambar miring/korup.
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val plane = imageProxy.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        val width = imageProxy.width
        val height = imageProxy.height

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        if (rowStride == width * pixelStride) {
            // Fast path: tidak ada row padding
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            // Slow path: strip padding bytes di ujung setiap row
            val cleanBuffer = ByteBuffer.allocateDirect(width * height * pixelStride)
            buffer.rewind()
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                val rowBytes = ByteArray(width * pixelStride)
                buffer.get(rowBytes)
                cleanBuffer.put(rowBytes)
            }
            cleanBuffer.rewind()
            bitmap.copyPixelsFromBuffer(cleanBuffer)
        }

        // Rotasi agar bitmap orientasinya portrait benar
        val rotation = imageProxy.imageInfo.rotationDegrees
        return if (rotation != 0) rotateBitmap(bitmap, rotation) else bitmap
    }

    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        src.recycle()
        return rotated
    }
}
```

---

## BAGIAN 4 — `OverlayView.kt` — TULIS ULANG PENUH

Tambahkan fitur: crop preview di pojok kiri atas.

```kotlin
package com.conjunctiva.segmentation

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var results: List<SegmentResult> = emptyList()
    private var imageWidth: Int = 1      // dimensi bitmap POST-rotasi
    private var imageHeight: Int = 1

    // ── Paints ────────────────────────────────────────────────────────────────
    private val polygonFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 255, 50, 50)     // merah transparan
    }
    private val polygonStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(255, 50, 50)
        strokeWidth = 3f
    }
    private val bboxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(0, 200, 80)
        strokeWidth = 2.5f
    }
    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 0, 0, 0)
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val cropBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f
    }
    private val noDetectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0)
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }

    // ── API ───────────────────────────────────────────────────────────────────

    /**
     * @param imageWidth  Lebar bitmap POST-rotasi (bukan imageProxy.width)
     * @param imageHeight Tinggi bitmap POST-rotasi
     */
    fun setResults(results: List<SegmentResult>, imageWidth: Int, imageHeight: Int) {
        this.results = results
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    fun clear() {
        results = emptyList()
        invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (results.isEmpty()) {
            drawNoDetection(canvas)
            return
        }

        // Scale factor: dari image space ke view space
        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight

        for (result in results) {
            drawPolygon(canvas, result.polygon, scaleX, scaleY)
            drawBoundingBox(canvas, result.boundingBox, result.confidence, scaleX, scaleY)
            result.cropBitmap?.let { drawCropPreview(canvas, it) }
        }
    }

    private fun drawPolygon(canvas: Canvas, polygon: List<PointF>, sx: Float, sy: Float) {
        if (polygon.size < 3) return
        val path = Path()
        polygon.forEachIndexed { i, pt ->
            val x = pt.x * sx
            val y = pt.y * sy
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, polygonFillPaint)
        canvas.drawPath(path, polygonStrokePaint)
    }

    private fun drawBoundingBox(
        canvas: Canvas, bbox: RectF, confidence: Float, sx: Float, sy: Float
    ) {
        val rect = RectF(bbox.left * sx, bbox.top * sy, bbox.right * sx, bbox.bottom * sy)
        canvas.drawRect(rect, bboxPaint)

        val label = "Conjunctiva ${(confidence * 100).toInt()}%"
        val labelW = labelTextPaint.measureText(label) + 16f
        val labelH = 50f
        val lx = rect.left
        val ly = (rect.top - labelH).coerceAtLeast(0f)

        canvas.drawRect(lx, ly, lx + labelW, ly + labelH, labelBgPaint)
        canvas.drawText(label, lx + 8f, ly + labelH - 10f, labelTextPaint)
    }

    /**
     * Gambar crop konjungtiva di pojok kiri atas.
     * Ukuran: 160×120 dp, dengan border putih.
     */
    private fun drawCropPreview(canvas: Canvas, cropBitmap: Bitmap) {
        val previewW = (width * 0.28f).toInt().coerceAtLeast(120)   // 28% lebar layar
        val previewH = (previewW * 0.75f).toInt()                    // 4:3 ratio
        val margin = 16f

        val scaledCrop = Bitmap.createScaledBitmap(cropBitmap, previewW, previewH, true)
        canvas.drawBitmap(scaledCrop, margin, margin, null)

        // Border putih
        canvas.drawRect(
            margin, margin,
            margin + previewW, margin + previewH,
            cropBorderPaint
        )

        // Label kecil "CROP"
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText("Crop Preview", margin + 4f, margin + previewH - 6f, labelPaint)

        scaledCrop.recycle()
    }

    private fun drawNoDetection(canvas: Canvas) {
        // Tidak perlu menggambar apapun saat tidak ada deteksi
        // Biarkan kosong agar tidak mengganggu camera preview
    }
}
```

---

## BAGIAN 5 — `MainActivity.kt` — MODIFIKASI

Cari dan modifikasi bagian berikut. **Jangan ubah bagian lain.**

### 5.1 Inisialisasi segmentor — tidak ada perubahan

### 5.2 Di dalam `imageAnalysis.setAnalyzer` callback:

```kotlin
// SEBELUM (SALAH — pakai dimensi pre-rotasi):
val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
val results = segmentor.segment(bitmap)
binding.overlayView.setResults(results, imageProxy.width, imageProxy.height)
imageProxy.close()

// SESUDAH (BENAR — pakai dimensi post-rotasi):
val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
imageProxy.close()   // close SEGERA setelah toBitmap, sebelum processing

val results = segmentor.segment(bitmap)

runOnUiThread {
    // KRITIS: pakai bitmap.width/bitmap.height (post-rotasi), BUKAN imageProxy.width/height
    binding.overlayView.setResults(results, bitmap.width, bitmap.height)
}

// Recycle crop bitmaps setelah render
results.forEach { it.cropBitmap?.recycle() }
bitmap.recycle()
```

### 5.3 Di `onDestroy`:

```kotlin
override fun onDestroy() {
    super.onDestroy()
    segmentor.close()
    cameraExecutor.shutdown()
}
```

### 5.4 ImageAnalysis setup — pastikan format RGBA_8888:

```kotlin
val imageAnalysis = ImageAnalysis.Builder()
    .setTargetResolution(Size(1280, 720))
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)  // WAJIB
    .build()
```

---

## BAGIAN 6 — CHECKLIST VALIDASI SETELAH IMPLEMENTASI

Setelah semua file diubah, verifikasi urutan ini sebelum build:

**Build check:**
- [ ] `build.gradle` sudah punya dependency `tensorflow-lite-gpu`
- [ ] `aaptOptions { noCompress "tflite" }` sudah ada
- [ ] Tidak ada import yang tertinggal dari kode lama

**Runtime check (dari logcat):**
```
# Harus muncul saat startup:
D/ConjunctivaSegmentor: Input tensor: shape=[1, 320, 320, 3], dtype=FLOAT32
D/ConjunctivaSegmentor: Output[0] detections shape: [1, 300, 38]
D/ConjunctivaSegmentor: Output[1] proto shape: [1, 80, 80, 32]  ← atau 160,160
I/ConjunctivaSegmentor: GPU Delegate: AKTIF  ← atau warning CPU fallback
D/ConjunctivaSegmentor: Buffers allocated: boxes[1,300,38], protos[1,80,80,32]
```

**Jika GPU delegate crash:**
Tambahkan fallback ini di `setupInterpreter()`:
```kotlin
try {
    gpuDelegate = GpuDelegate(delegateOptions)
    options.addDelegate(gpuDelegate!!)
} catch (e: Exception) {
    Log.w(TAG, "GPU delegate init failed, falling back to CPU: ${e.message}")
    gpuDelegate = null
    options.numThreads = 4
}
```

**Visual check:**
- [ ] Bbox muncul di atas area konjungtiva (bukan di tepi kiri layar)
- [ ] Polygon mengikuti bentuk konjungtiva (bukan zigzag random)
- [ ] Crop preview muncul di pojok kiri atas layar
- [ ] FPS ≥ 15 (GPU), atau ≥ 5 (CPU fallback)

---

## CATATAN PENTING TENTANG MODEL MIXED INT8/FLOAT32

Model `best_float16.tflite` (atau sejenisnya dengan mixed quantization) sering menyebabkan
crash karena GPU Delegate memerlukan seluruh tensor float32 atau seluruh int8.

**Jika crash dengan GPU Delegate, lakukan ini:**

```kotlin
// Opsi 1: Force CPU untuk model mixed quant
private fun setupInterpreter() {
    val options = Interpreter.Options().apply {
        numThreads = 4
        // Aktifkan XNNPACK untuk akselerasi CPU yang lebih baik
        setUseXNNPACK(true)
    }
    interpreter = Interpreter(modelBuffer, options)
}
```

```kotlin
// Opsi 2: Coba NNAPI delegate (lebih kompatibel dengan mixed quant)
val nnApiDelegate = NnApiDelegate()
options.addDelegate(nnApiDelegate)
```

**Solusi terbaik jangka panjang:**
Ekspor ulang model dengan `float32` penuh atau `int8` penuh (bukan mixed):
```python
# Di Python saat export:
model.export(format='tflite', int8=True)   # Full int8
# atau
model.export(format='tflite')              # Full float32
```

---

## URUTAN IMPLEMENTASI

1. Update `build.gradle` — tambah GPU dependency
2. Tulis ulang `ImageUtils.kt`
3. Tulis ulang `ConjunctivaSegmentor.kt`
4. Tulis ulang `OverlayView.kt`
5. Modifikasi `MainActivity.kt`
6. Build dan cek logcat untuk validasi shape output model
7. Jika GPU crash → fallback ke XNNPACK CPU atau NNAPI
