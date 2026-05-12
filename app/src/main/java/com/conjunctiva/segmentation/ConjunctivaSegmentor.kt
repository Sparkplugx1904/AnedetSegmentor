package com.conjunctiva.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Segmentasi konjungtiva (TFLite). Mendukung variasi bentuk tensor umum dari export YOLOv8-seg:
 *
 * - Output 0: `[1, 300, 38]` (deteksi-major) **atau** `[1, 38, 300]` (fitur-major)
 * - Output 1 (opsional): proto `[1, 32, H, W]` atau `[1, H, W, 32]`
 *
 * Alokasi buffer mengikuti [Interpreter.getOutputTensor] agar `runInference` tidak gagal.
 */
class ConjunctivaSegmentor(context: Context) {

    private var interpreter: Interpreter

    private var inputSize = 320
    private val numChannels = 3
    private var protoH = 80
    private var protoW = 80
    private var numProtoChannels = 32
    private var numDetections = 300
    private var numFeatures = 38

    /** `true` → buffer[0][det][feat]; `false` → buffer[0][feat][det] */
    private var outerIsDetections = true

    private lateinit var outputBoxes: Array<Array<FloatArray>>
    /** Hanya terisi jika model punya output proto (tensor 1). */
    private var outputProtos: Any? = null
    private var protoChannelFirst = true
    private var hasProtoOutput = false

    private lateinit var inputBuffer: ByteBuffer

    companion object {
        private const val TAG = "ConjunctivaSegmentor"
        private const val MODEL_ASSET = "best_float16.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.35f
        private const val IOU_THRESHOLD = 0.45f
        private const val MASK_THRESHOLD = 0.5f
    }

    init {
        val modelFile = loadModelFile(context)

        val options = Interpreter.Options().apply {
            setUseXNNPACK(true)
            setNumThreads(4)
            Log.d(TAG, "Using CPU with XNNPACK acceleration")
        }

        interpreter = Interpreter(modelFile, options)

        val inputCount = interpreter.inputTensorCount
        val outputCount = interpreter.outputTensorCount
        Log.d(TAG, "Model has $inputCount inputs and $outputCount outputs")
        for (i in 0 until outputCount) {
            val shape = interpreter.getOutputTensor(i).shape()
            val dtype = interpreter.getOutputTensor(i).dataType()
            Log.d(TAG, "Output $i: shape=${shape.contentToString()}, dataType=$dtype")
        }

        resolveInputTensor()
        allocateBuffersFromModel()
        allocateInputBufferFloat32()

        Log.d(TAG, "Buffers: dets=$numDetections feats=$numFeatures outerDet=$outerIsDetections proto=$hasProtoOutput chFirst=$protoChannelFirst proto=${protoH}x${protoW}")
    }

    private fun resolveInputTensor() {
        val shape = interpreter.getInputTensor(0).shape()
        when {
            shape.size == 4 && shape[3] == 3 -> {
                inputSize = max(shape[1], shape[2])
            }
            shape.size == 4 && shape[1] == 3 -> {
                inputSize = max(shape[2], shape[3])
            }
            else -> {
                Log.w(TAG, "Unexpected input shape ${shape.contentToString()}, default inputSize=320")
            }
        }
    }

    private fun allocateBuffersFromModel() {
        val s0 = interpreter.getOutputTensor(0).shape()
        require(s0.size == 3 && s0[0] == 1) { "Output0 expected [1, A, B], got ${s0.contentToString()}" }
        val a = s0[1]
        val b = s0[2]

        when {
            a < b -> {
                numFeatures = a
                numDetections = b
                outerIsDetections = false
                outputBoxes = Array(1) { Array(numFeatures) { FloatArray(numDetections) } }
            }
            else -> {
                numDetections = a
                numFeatures = b
                outerIsDetections = true
                outputBoxes = Array(1) { Array(numDetections) { FloatArray(numFeatures) } }
            }
        }

        hasProtoOutput = interpreter.outputTensorCount > 1
        if (!hasProtoOutput) {
            outputProtos = null
            Log.w(TAG, "Model punya satu output saja — poligon dari bbox (tanpa proto mask).")
            return
        }

        val s1 = interpreter.getOutputTensor(1).shape()
        require(s1.size == 4 && s1[0] == 1) { "Output1 expected [1,?,?,?], got ${s1.contentToString()}" }

        when {
            s1[1] == 32 && s1.size >= 4 -> {
                protoChannelFirst = true
                numProtoChannels = s1[1]
                protoH = s1[2]
                protoW = s1[3]
                outputProtos = Array(1) {
                    Array(numProtoChannels) { Array(protoH) { FloatArray(protoW) } }
                }
            }
            s1[3] == 32 -> {
                protoChannelFirst = false
                protoH = s1[1]
                protoW = s1[2]
                numProtoChannels = s1[3]
                outputProtos = Array(1) {
                    Array(protoH) { Array(protoW) { FloatArray(numProtoChannels) } }
                }
            }
            else -> {
                throw IllegalStateException("Proto mask shape tidak didukung: ${s1.contentToString()}")
            }
        }
    }

    private fun allocateInputBufferFloat32() {
        val inputByteSize = 1 * inputSize * inputSize * numChannels * 4
        inputBuffer = ByteBuffer.allocateDirect(inputByteSize).apply {
            order(ByteOrder.nativeOrder())
        }
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val modelFile = context.filesDir
            .parentFile?.parentFile?.parentFile
            ?.resolve("models/best_float16.tflite")

        return if (modelFile?.exists() == true) {
            Log.d(TAG, "Loading model from: ${modelFile.absolutePath}")
            FileInputStream(modelFile).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, fis.channel.size())
            }
        } else {
            Log.d(TAG, "Loading model from assets: $MODEL_ASSET")
            val afd = context.assets.openFd(MODEL_ASSET)
            FileInputStream(afd.fileDescriptor).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }

    private fun det(det: Int, feat: Int): Float =
        if (outerIsDetections) outputBoxes[0][det][feat] else outputBoxes[0][feat][det]

    private fun protoAt(c: Int, y: Int, x: Int): Float {
        val p = outputProtos ?: return 0f
        return if (protoChannelFirst) {
            @Suppress("UNCHECKED_CAST")
            (p as Array<Array<Array<FloatArray>>>)[0][c][y][x]
        } else {
            @Suppress("UNCHECKED_CAST")
            (p as Array<Array<Array<FloatArray>>>)[0][y][x][c]
        }
    }

    /**
     * @param bitmap Frame (sudah dirotasi sesuai orientasi)
     */
    fun segment(bitmap: Bitmap): List<SegmentationResult> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        try {
            preprocessImage(resized)
            val t0 = System.currentTimeMillis()
            runInference()
            Log.d(TAG, "Inference: ${System.currentTimeMillis() - t0}ms")
            return postprocess(bitmap.width, bitmap.height)
        } finally {
            if (!resized.isRecycled && resized !== bitmap) {
                resized.recycle()
            }
        }
    }

    private fun preprocessImage(bitmap: Bitmap) {
        inputBuffer.rewind()
        require(bitmap.width == inputSize && bitmap.height == inputSize) {
            "Internal: bitmap harus ${inputSize}x${inputSize}, dapat ${bitmap.width}x${bitmap.height}"
        }
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (px in pixels) {
            inputBuffer.putFloat(((px shr 16) and 0xFF) / 255f)
            inputBuffer.putFloat(((px shr 8) and 0xFF) / 255f)
            inputBuffer.putFloat((px and 0xFF) / 255f)
        }
    }

    private fun runInference() {
        try {
            if (hasProtoOutput) {
                val protos = outputProtos
                    ?: throw IllegalStateException("hasProtoOutput tapi outputProtos null")
                val outputs = mutableMapOf<Int, Any>(
                    0 to outputBoxes,
                    1 to protos
                )
                interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
            } else {
                interpreter.run(inputBuffer, outputBoxes)
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "TFLite inference gagal (cek bentuk buffer vs model): ${e.message}", e)
            throw e
        }

        val maxConf = (0 until numDetections).maxOfOrNull { det(it, 4) } ?: 0f
        val maxXc = (0 until numDetections).maxOfOrNull { det(it, 0) } ?: 0f
        Log.d(TAG, "Output[0] maxConf=$maxConf, maxXc=$maxXc")
    }

    private fun postprocess(origW: Int, origH: Int): List<SegmentationResult> {
        val results = mutableListOf<SegmentationResult>()

        val maskCoeffCount = (numFeatures - 6).coerceIn(0, 32)
        if (maskCoeffCount <= 0) {
            Log.w(TAG, "numFeatures=$numFeatures tidak cukup untuk koef mask (harap ≥7)")
        }

        var maxConf = 0f
        var validCount = 0

        for (i in 0 until numDetections) {
            val conf = det(i, 4)
            if (conf > maxConf) maxConf = conf
            if (conf < CONFIDENCE_THRESHOLD) continue

            val rawXc = det(i, 0)
            val rawYc = det(i, 1)
            val rawW = det(i, 2)
            val rawH = det(i, 3)

            val (xcN, ycN, wN, hN) = if (rawXc <= 1.05f && rawYc <= 1.05f && rawW <= 1.05f && rawH <= 1.05f) {
                Quad(rawXc, rawYc, rawW, rawH)
            } else {
                Quad(rawXc / inputSize, rawYc / inputSize, rawW / inputSize, rawH / inputSize)
            }

            val coeffs = FloatArray(maskCoeffCount) { c -> det(i, 6 + c) }

            val polygon = if (hasProtoOutput && maskCoeffCount > 0) {
                val mask = generateMask(coeffs)
                extractPolygonFromMask(mask, xcN, ycN, wN, hN, origW, origH)
            } else {
                bboxToPolygon(xcN, ycN, wN, hN, origW, origH)
            }

            val left = ((xcN - wN / 2) * origW).coerceIn(0f, origW.toFloat())
            val top = ((ycN - hN / 2) * origH).coerceIn(0f, origH.toFloat())
            val right = ((xcN + wN / 2) * origW).coerceIn(0f, origW.toFloat())
            val bottom = ((ycN + hN / 2) * origH).coerceIn(0f, origH.toFloat())

            validCount++
            results.add(
                SegmentationResult(
                    confidence = conf,
                    classId = 0,
                    boundingBox = BoundingBox(left, top, right, bottom),
                    polygon = polygon
                )
            )
        }

        Log.d(TAG, "Postprocess: maxConf=$maxConf, valid=$validCount, threshold=$CONFIDENCE_THRESHOLD")
        return applyNMS(results)
    }

    private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

    private fun bboxToPolygon(
        xcN: Float,
        ycN: Float,
        wN: Float,
        hN: Float,
        origW: Int,
        origH: Int
    ): List<Pair<Float, Float>> {
        val l = ((xcN - wN / 2) * origW).coerceIn(0f, origW.toFloat())
        val t = ((ycN - hN / 2) * origH).coerceIn(0f, origH.toFloat())
        val r = ((xcN + wN / 2) * origW).coerceIn(0f, origW.toFloat())
        val b = ((ycN + hN / 2) * origH).coerceIn(0f, origH.toFloat())
        return listOf(Pair(l, t), Pair(r, t), Pair(r, b), Pair(l, b))
    }

    private fun generateMask(coeffs: FloatArray): Array<FloatArray> {
        val mask = Array(protoH) { FloatArray(protoW) }
        val cMax = min(coeffs.size, numProtoChannels)
        for (y in 0 until protoH) {
            for (x in 0 until protoW) {
                var sum = 0f
                for (c in 0 until cMax) {
                    sum += protoAt(c, y, x) * coeffs[c]
                }
                mask[y][x] = 1f / (1f + exp(-sum))
            }
        }
        return mask
    }

    private fun extractPolygonFromMask(
        mask: Array<FloatArray>,
        xcN: Float,
        ycN: Float,
        wN: Float,
        hN: Float,
        origW: Int,
        origH: Int
    ): List<Pair<Float, Float>> {

        val leftMask = ((xcN - wN / 2) * protoW).toInt().coerceIn(0, protoW - 1)
        val topMask = ((ycN - hN / 2) * protoH).toInt().coerceIn(0, protoH - 1)
        val rightMask = ((xcN + wN / 2) * protoW).toInt().coerceIn(0, protoW - 1)
        val bottomMask = ((ycN + hN / 2) * protoH).toInt().coerceIn(0, protoH - 1)

        val leftEdge = mutableListOf<Pair<Float, Float>>()
        val rightEdge = mutableListOf<Pair<Float, Float>>()

        for (y in topMask..bottomMask) {
            var firstX = -1
            var lastX = -1
            for (x in leftMask..rightMask) {
                if (mask[y][x] > MASK_THRESHOLD) {
                    if (firstX == -1) firstX = x
                    lastX = x
                }
            }
            if (firstX != -1) {
                val imgY = (y.toFloat() / protoH) * origH
                leftEdge.add(Pair((firstX.toFloat() / protoW) * origW, imgY))
                if (firstX != lastX) {
                    rightEdge.add(Pair((lastX.toFloat() / protoW) * origW, imgY))
                }
            }
        }

        val polygon = mutableListOf<Pair<Float, Float>>()
        polygon.addAll(leftEdge)
        polygon.addAll(rightEdge.reversed())

        if (polygon.isEmpty()) {
            return bboxToPolygon(xcN, ycN, wN, hN, origW, origH)
        }

        return polygon
    }

    private fun applyNMS(results: List<SegmentationResult>): List<SegmentationResult> {
        if (results.isEmpty()) return emptyList()

        val sorted = results.sortedByDescending { it.confidence }
        val selected = mutableListOf<SegmentationResult>()

        for (candidate in sorted) {
            val suppress = selected.any { kept ->
                calculateIoU(candidate.boundingBox, kept.boundingBox) > IOU_THRESHOLD
            }
            if (!suppress) selected.add(candidate)
        }
        return selected
    }

    private fun calculateIoU(a: BoundingBox, b: BoundingBox): Float {
        val iL = max(a.left, b.left)
        val iT = max(a.top, b.top)
        val iR = min(a.right, b.right)
        val iB = min(a.bottom, b.bottom)

        if (iR <= iL || iB <= iT) return 0f

        val interArea = (iR - iL) * (iB - iT)
        val aArea = (a.right - a.left) * (a.bottom - a.top)
        val bArea = (b.right - b.left) * (b.bottom - b.top)
        return interArea / (aArea + bArea - interArea)
    }

    fun close() {
        interpreter.close()
    }
}

data class SegmentationResult(
    val confidence: Float,
    val classId: Int,
    val boundingBox: BoundingBox,
    val polygon: List<Pair<Float, Float>>
)

data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
