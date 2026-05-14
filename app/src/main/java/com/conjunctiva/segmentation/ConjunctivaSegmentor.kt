package com.conjunctiva.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class ConjunctivaSegmentor(context: Context) {

    private var interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null

    private val inputSize = 640
    private val maskSize = 160
    private val numProtoChannels = 32
    private val numDetections = 300
    private val numElements = 38 // 4 box + 2 class + 32 mask coefficients

    companion object {
        private const val TAG = "ConjunctivaSegmentor"
        private const val MODEL_PATH = "yolo26n-seg_best_float16.tflite"
        private const val CONFIDENCE_THRESHOLD = 0.45f
        private const val IOU_THRESHOLD = 0.5f
    }

    init {
        val model = FileUtil.loadMappedFile(context, MODEL_PATH)
        val options = Interpreter.Options()
        
        val compatList = CompatibilityList()
        if (compatList.isDelegateSupportedOnThisDevice) {
            val delegateOptions = compatList.bestOptionsForThisDevice
            gpuDelegate = GpuDelegate(delegateOptions)
            options.addDelegate(gpuDelegate)
            Log.d(TAG, "GPU Delegate is supported and enabled.")
        } else {
            options.setNumThreads(4)
            Log.d(TAG, "GPU Delegate is not supported, using CPU.")
        }

        interpreter = Interpreter(model, options)
        Log.d(TAG, "Interpreter initialized with model: $MODEL_PATH")
    }

    fun segment(bitmap: Bitmap): SegmentationResult? {
        val startTime = System.currentTimeMillis()

        // 1. Preprocessing
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
            .add(NormalizeOp(0f, 255f))
            .build()

        var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        // 2. Prepare outputs
        // Output 0: [1, 300, 38]
        val output0 = TensorBuffer.createFixedSize(intArrayOf(1, numDetections, numElements), org.tensorflow.lite.DataType.FLOAT32)
        // Output 1: [1, 160, 160, 32]
        val output1 = TensorBuffer.createFixedSize(intArrayOf(1, maskSize, maskSize, numProtoChannels), org.tensorflow.lite.DataType.FLOAT32)

        val outputs = mapOf(0 to output0.buffer, 1 to output1.buffer)

        // 3. Run Inference
        interpreter.runForMultipleInputsOutputs(arrayOf(tensorImage.buffer), outputs)

        // 4. Post-processing
        val boxesAndScores = output0.floatArray
        val prototypes = output1.floatArray

        val detections = mutableListOf<Detection>()

        for (i in 0 until numDetections) {
            val offset = i * numElements
            
            // YOLOv8-seg format: [xc, yc, w, h, class0, class1, coeff0...coeff31]
            val xc = boxesAndScores[offset + 0]
            val yc = boxesAndScores[offset + 1]
            val w = boxesAndScores[offset + 2]
            val h = boxesAndScores[offset + 3]

            // Skor klasifikasi (indeks 4 dan 5)
            val score0 = boxesAndScores[offset + 4]
            val score1 = boxesAndScores[offset + 5]
            val confidence = max(score0, score1)

            if (confidence > CONFIDENCE_THRESHOLD) {
                val x1 = (xc - w / 2f)
                val y1 = (yc - h / 2f)
                val x2 = (xc + w / 2f)
                val y2 = (yc + h / 2f)

                val coeffs = FloatArray(numProtoChannels)
                for (j in 0 until numProtoChannels) {
                    coeffs[j] = boxesAndScores[offset + 6 + j]
                }

                detections.add(Detection(RectF(x1, y1, x2, y2), confidence, coeffs))
            }
        }

        val filteredDetections = nms(detections)
        if (filteredDetections.isEmpty()) return null

        // Generate combined mask
        val finalMask = generateMask(filteredDetections, prototypes, bitmap.width, bitmap.height)

        val inferenceTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "Inference and post-proc took: ${inferenceTime}ms")

        // Return best detection info (just for UI stats) and the full mask
        val best = filteredDetections.maxByOrNull { it.confidence }
        return SegmentationResult(
            confidence = best?.confidence ?: 0f,
            boundingBox = best?.boundingBox ?: RectF(),
            mask = finalMask
        )
    }

    private fun generateMask(
        detections: List<Detection>,
        prototypes: FloatArray,
        origW: Int,
        origH: Int
    ): Bitmap {
        val maskBitmap = Bitmap.createBitmap(maskSize, maskSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(maskSize * maskSize)

        // Reset pixels
        pixels.fill(0)

        // For simplicity, we combine all masks into one 160x160 bitmap first
        // In a real YOLOv8-seg pipeline, we should crop per box, but here we can sum them
        val combinedMask = FloatArray(maskSize * maskSize)

        for (det in detections) {
            for (y in 0 until maskSize) {
                for (x in 0 until maskSize) {
                    var sum = 0f
                    for (c in 0 until numProtoChannels) {
                        // Prototype shape: [160, 160, 32]
                        sum += prototypes[(y * maskSize + x) * numProtoChannels + c] * det.maskCoeffs[c]
                    }
                    val sigmoid = 1f / (1f + exp(-sum))
                    if (sigmoid > 0.5f) {
                        // Apply box constraint (scaled to 160x160)
                        val bx1 = (det.boundingBox.left / inputSize) * maskSize
                        val by1 = (det.boundingBox.top / inputSize) * maskSize
                        val bx2 = (det.boundingBox.right / inputSize) * maskSize
                        val by2 = (det.boundingBox.bottom / inputSize) * maskSize

                        if (x >= bx1 && x <= bx2 && y >= by1 && y <= by2) {
                            combinedMask[y * maskSize + x] = max(combinedMask[y * maskSize + x], sigmoid)
                        }
                    }
                }
            }
        }

        // Color: Ungu Muda (Light Purple) -> e.g., #AA66CC with alpha
        val maskColor = Color.argb(128, 170, 102, 204)

        for (i in pixels.indices) {
            if (combinedMask[i] > 0.5f) {
                pixels[i] = maskColor
            } else {
                pixels[i] = Color.TRANSPARENT
            }
        }

        maskBitmap.setPixels(pixels, 0, maskSize, 0, 0, maskSize, maskSize)

        // Upsample to original size
        return Bitmap.createScaledBitmap(maskBitmap, origW, origH, true)
    }

    private fun nms(detections: List<Detection>): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val first = sorted.removeAt(0)
            selected.add(first)
            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (iou(first.boundingBox, next.boundingBox) > IOU_THRESHOLD) {
                    iterator.remove()
                }
            }
        }
        return selected
    }

    private fun iou(a: RectF, b: RectF): Float {
        val intersection = RectF()
        if (!intersection.setIntersect(a, b)) return 0f
        val intersectArea = intersection.width() * intersection.height()
        val unionArea = (a.width() * a.height()) + (b.width() * b.height()) - intersectArea
        return intersectArea / unionArea
    }

    fun close() {
        interpreter.close()
        gpuDelegate?.close()
    }

    data class Detection(
        val boundingBox: RectF,
        val confidence: Float,
        val maskCoeffs: FloatArray
    )

    data class SegmentationResult(
        val confidence: Float,
        val boundingBox: RectF,
        val mask: Bitmap
    )
}
