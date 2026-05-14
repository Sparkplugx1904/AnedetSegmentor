package com.conjunctiva.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Optimized Conjunctiva Segmentation using YOLOv8 INT8 model and GPU acceleration.
 */
class ConjunctivaSegmentor(context: Context) {

    private var interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null

    private var inputSize = 320
    private val numChannels = 3
    private var protoH = 80
    private var protoW = 80
    private var numProtoChannels = 32
    private var numDetections = 300
    private var numFeatures = 38

    private var outerIsDetections = true
    private lateinit var outputBoxes: Any 
    private var outputProtos: Any? = null
    private var protoChannelFirst = true
    private var hasProtoOutput = false

    private lateinit var inputBuffer: ByteBuffer
    private var isQuantized = false

    companion object {
        private const val TAG = "ConjunctivaSegmentor"
        private const val MODEL_ASSET = "yolo26n-seg_best_int8.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.35f
        private const val IOU_THRESHOLD = 0.45f
        private const val MASK_THRESHOLD = 0.5f
    }

    init {
        val modelFile = loadModelFile(context)
        val options = Interpreter.Options()
        
        val inputTensor = Interpreter(modelFile).use { tempInterpreter ->
            tempInterpreter.getInputTensor(0)
        }
        isQuantized = inputTensor.dataType() == DataType.UINT8 || inputTensor.dataType() == DataType.INT8
        Log.d(TAG, "Model isQuantized=$isQuantized, DataType=${inputTensor.dataType()}")
        
        // GPU delegate doesn't work well with INT8 quantized models
        // Use CPU with XNNPACK for better compatibility
        if (!isQuantized) {
            try {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
                Log.d(TAG, "GPU Acceleration enabled for FLOAT32 model.")
            } catch (e: Exception) {
                Log.w(TAG, "GPU Acceleration failed: ${e.message}. Using CPU with XNNPACK.")
                gpuDelegate?.close()
                gpuDelegate = null
                options.setUseXNNPACK(true)
                options.setNumThreads(4)
            }
        } else {
            Log.d(TAG, "INT8 quantized model detected. Using CPU with XNNPACK for better compatibility.")
            options.setUseXNNPACK(true)
            options.setNumThreads(4)
        }

        interpreter = Interpreter(modelFile, options)

        val inputTensor = interpreter.getInputTensor(0)
        isQuantized = inputTensor.dataType() == DataType.UINT8 || inputTensor.dataType() == DataType.INT8
        Log.d(TAG, "Model isQuantized=$isQuantized, DataType=${inputTensor.dataType()}")

        resolveInputTensor()
        allocateBuffersFromModel()
        allocateInputBuffer()

        Log.d(TAG, "Init complete: ${inputSize}x${inputSize}, detections=$numDetections, features=$numFeatures")
    }

    private fun resolveInputTensor() {
        val shape = interpreter.getInputTensor(0).shape()
        inputSize = if (shape.size == 4) {
            if (shape[1] == 3) shape[2] else shape[1]
        } else 320
    }

    private fun allocateBuffersFromModel() {
        val s0 = interpreter.getOutputTensor(0).shape()
        val a = s0[1]
        val b = s0[2]

        if (a < b) {
            numFeatures = a
            numDetections = b
            outerIsDetections = false
        } else {
            numDetections = a
            numFeatures = b
            outerIsDetections = true
        }

        // Allocate based on data type
        val dtype0 = interpreter.getOutputTensor(0).dataType()
        outputBoxes = if (dtype0 == DataType.FLOAT32) {
            Array(1) { Array(s0[1]) { FloatArray(s0[2]) } }
        } else {
            Array(1) { Array(s0[1]) { ByteArray(s0[2]) } }
        }

        hasProtoOutput = interpreter.outputTensorCount > 1
        if (hasProtoOutput) {
            val s1 = interpreter.getOutputTensor(1).shape()
            val dtype1 = interpreter.getOutputTensor(1).dataType()
            
            if (s1[1] == 32) {
                protoChannelFirst = true
                numProtoChannels = s1[1]
                protoH = s1[2]
                protoW = s1[3]
                outputProtos = if (dtype1 == DataType.FLOAT32) {
                    Array(1) { Array(numProtoChannels) { Array(protoH) { FloatArray(protoW) } } }
                } else {
                    Array(1) { Array(numProtoChannels) { Array(protoH) { ByteArray(protoW) } } }
                }
            } else {
                protoChannelFirst = false
                protoH = s1[1]
                protoW = s1[2]
                numProtoChannels = s1[3]
                outputProtos = if (dtype1 == DataType.FLOAT32) {
                    Array(1) { Array(protoH) { Array(protoW) { FloatArray(numProtoChannels) } } }
                } else {
                    Array(1) { Array(protoH) { Array(protoW) { ByteArray(numProtoChannels) } } }
                }
            }
        }
    }

    private fun allocateInputBuffer() {
        val bytesPerChannel = if (isQuantized) 1 else 4
        val inputByteSize = 1 * inputSize * inputSize * numChannels * bytesPerChannel
        inputBuffer = ByteBuffer.allocateDirect(inputByteSize).apply {
            order(ByteOrder.nativeOrder())
        }
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val afd = context.assets.openFd(MODEL_ASSET)
        FileInputStream(afd.fileDescriptor).use { fis ->
            return fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

    private fun getBoxValue(det: Int, feat: Int): Float {
        val tensor = interpreter.getOutputTensor(0)
        val quantParams = tensor.quantizationParams()
        val scale = quantParams.scale
        val zeroPoint = quantParams.zeroPoint

        return if (outerIsDetections) {
            if (outputBoxes is Array<*> && (outputBoxes as Array<Array<FloatArray>>)[0][0] is FloatArray) {
                (outputBoxes as Array<Array<FloatArray>>)[0][det][feat]
            } else {
                val raw = (outputBoxes as Array<Array<ByteArray>>)[0][det][feat].toInt() and 0xFF
                (raw.toFloat() - zeroPoint.toFloat()) * scale
            }
        } else {
            if (outputBoxes is Array<*> && (outputBoxes as Array<Array<FloatArray>>)[0][0] is FloatArray) {
                (outputBoxes as Array<Array<FloatArray>>)[0][feat][det]
            } else {
                val raw = (outputBoxes as Array<Array<ByteArray>>)[0][feat][det].toInt() and 0xFF
                (raw.toFloat() - zeroPoint.toFloat()) * scale
            }
        }
    }

    private fun getProtoValue(c: Int, y: Int, x: Int): Float {
        val p = outputProtos ?: return 0f
        val tensor = interpreter.getOutputTensor(1)
        val quantParams = tensor.quantizationParams()
        val scale = quantParams.scale
        val zeroPoint = quantParams.zeroPoint

        return if (protoChannelFirst) {
            if (p is Array<*> && (p as Array<Array<Array<FloatArray>>>)[0][0][0] is FloatArray) {
                (p as Array<Array<Array<FloatArray>>>)[0][c][y][x]
            } else {
                val raw = (p as Array<Array<Array<ByteArray>>>)[0][c][y][x].toInt() and 0xFF
                (raw.toFloat() - zeroPoint.toFloat()) * scale
            }
        } else {
            if (p is Array<*> && (p as Array<Array<Array<FloatArray>>>)[0][0][0] is FloatArray) {
                (p as Array<Array<Array<FloatArray>>>)[0][y][x][c]
            } else {
                val raw = (p as Array<Array<Array<ByteArray>>>)[0][y][x][c].toInt() and 0xFF
                (raw.toFloat() - zeroPoint.toFloat()) * scale
            }
        }
    }

    fun segment(bitmap: Bitmap): List<SegmentationResult> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        preprocessImage(resized)
        
        val outputs = mutableMapOf<Int, Any>(0 to outputBoxes)
        outputProtos?.let { outputs[1] = it }
        
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
        
        if (resized !== bitmap) resized.recycle()
        return postprocess(bitmap.width, bitmap.height)
    }

    private fun preprocessImage(bitmap: Bitmap) {
        inputBuffer.rewind()
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            if (isQuantized) {
                inputBuffer.put(r.toByte())
                inputBuffer.put(g.toByte())
                inputBuffer.put(b.toByte())
            } else {
                inputBuffer.putFloat(r / 255f)
                inputBuffer.putFloat(g / 255f)
                inputBuffer.putFloat(b / 255f)
            }
        }
    }

    private fun postprocess(origW: Int, origH: Int): List<SegmentationResult> {
        val results = mutableListOf<SegmentationResult>()
        val maskCoeffCount = (numFeatures - 4).coerceIn(0, 32) 

        for (i in 0 until numDetections) {
            val conf = getBoxValue(i, 4)
            if (conf < CONFIDENCE_THRESHOLD) continue

            val xc = getBoxValue(i, 0)
            val yc = getBoxValue(i, 1)
            val w = getBoxValue(i, 2)
            val h = getBoxValue(i, 3)

            val xcN = xc / inputSize
            val ycN = yc / inputSize
            val wN = w / inputSize
            val hN = h / inputSize

            val coeffs = FloatArray(maskCoeffCount) { c -> getBoxValue(i, numFeatures - maskCoeffCount + c) }

            val polygon = if (hasProtoOutput) {
                val mask = generateMask(coeffs)
                extractPolygonFromMask(mask, xcN, ycN, wN, hN, origW, origH)
            } else {
                bboxToPolygon(xcN, ycN, wN, hN, origW, origH)
            }

            results.add(SegmentationResult(
                confidence = conf,
                classId = 0,
                boundingBox = BoundingBox(
                    ((xcN - wN/2) * origW), ((ycN - hN/2) * origH),
                    ((xcN + wN/2) * origW), ((ycN + hN/2) * origH)
                ),
                polygon = polygon
            ))
        }
        return applyNMS(results)
    }

    private fun generateMask(coeffs: FloatArray): Array<FloatArray> {
        val mask = Array(protoH) { FloatArray(protoW) }
        for (y in 0 until protoH) {
            for (x in 0 until protoW) {
                var sum = 0f
                for (c in 0 until coeffs.size) {
                    sum += getProtoValue(c, y, x) * coeffs[c]
                }
                mask[y][x] = 1f / (1f + exp(-sum))
            }
        }
        return mask
    }

    private fun extractPolygonFromMask(
        mask: Array<FloatArray>,
        xcN: Float, ycN: Float, wN: Float, hN: Float,
        origW: Int, origH: Int
    ): List<Pair<Float, Float>> {
        val left = ((xcN - wN / 2) * protoW).toInt().coerceIn(0, protoW - 1)
        val top = ((ycN - hN / 2) * protoH).toInt().coerceIn(0, protoH - 1)
        val right = ((xcN + wN / 2) * protoW).toInt().coerceIn(0, protoW - 1)
        val bottom = ((ycN + hN / 2) * protoH).toInt().coerceIn(0, protoH - 1)

        val roiW = right - left + 1
        val roiH = bottom - top + 1
        if (roiW <= 0 || roiH <= 0) return emptyList()

        val roiBinary = Array(roiH) { ry ->
            BooleanArray(roiW) { rx -> mask[top + ry][left + rx] > MASK_THRESHOLD }
        }

        val contour = MaskContour.contourFromBinaryRoi(roiBinary, 10)
        return MaskContour.protoToImage(left, top, protoW, protoH, origW, origH, contour)
    }

    private fun bboxToPolygon(xcN: Float, ycN: Float, wN: Float, hN: Float, origW: Int, origH: Int): List<Pair<Float, Float>> {
        val l = (xcN - wN / 2) * origW
        val t = (ycN - hN / 2) * origH
        val r = (xcN + wN / 2) * origW
        val b = (ycN + hN / 2) * origH
        return listOf(Pair(l, t), Pair(r, t), Pair(r, b), Pair(l, b))
    }

    private fun applyNMS(results: List<SegmentationResult>): List<SegmentationResult> {
        val sorted = results.sortedByDescending { it.confidence }
        val selected = mutableListOf<SegmentationResult>()
        for (candidate in sorted) {
            if (selected.none { calculateIoU(candidate.boundingBox, it.boundingBox) > IOU_THRESHOLD }) {
                selected.add(candidate)
            }
        }
        return selected
    }

    private fun calculateIoU(a: BoundingBox, b: BoundingBox): Float {
        val iL = max(a.left, b.left); val iT = max(a.top, b.top)
        val iR = min(a.right, b.right); val iB = min(a.bottom, b.bottom)
        if (iR <= iL || iB <= iT) return 0f
        val inter = (iR - iL) * (iB - iT)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        return inter / (areaA + areaB - inter)
    }

    fun close() {
        interpreter.close()
        gpuDelegate?.close()
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
