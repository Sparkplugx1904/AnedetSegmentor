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
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
            val delegateOptions = GpuDelegate.Options().apply {
                setPrecisionLossAllowed(true) // Required for FP16 optimization
                setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
            }
            gpuDelegate = GpuDelegate(delegateOptions)
            options.addDelegate(gpuDelegate)
            Log.d(TAG, "GPU Delegate is supported and enabled with FP16 optimizations.")
        } else {
            options.setNumThreads(4)
            options.setUseXNNPACK(true)
            Log.d(TAG, "GPU Delegate is not supported, using CPU with XNNPACK.")
        }

        interpreter = Interpreter(model, options)
        Log.d(TAG, "Interpreter initialized with model: $MODEL_PATH")
    }

    fun segment(bitmap: Bitmap): SegmentationResult? {
        val startTime = System.currentTimeMillis()

        val height = bitmap.height
        val width = bitmap.width
        val scale = min(inputSize.toFloat() / width, inputSize.toFloat() / height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        // 1. Preprocessing with Letterboxing
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(newHeight, newWidth, ResizeOp.ResizeMethod.BILINEAR))
            .add(ResizeWithCropOrPadOp(inputSize, inputSize))
            .add(NormalizeOp(0f, 255f))
            .build()

        var tensorImage = TensorImage(org.tensorflow.lite.DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        val padX = (inputSize - newWidth) / 2f
        val padY = (inputSize - newHeight) / 2f

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
        val finalMask = generateMask(filteredDetections, prototypes, bitmap.width, bitmap.height, padX, padY, newWidth, newHeight)

        val inferenceTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "Inference and post-proc took: ${inferenceTime}ms")

        // Return best detection info (just for UI stats) and the full mask
        val best = filteredDetections.maxByOrNull { it.confidence }
        
        // Map bounding box back to original image coordinates (undo letterbox)
        val finalBox = best?.let {
            val bx1 = (it.boundingBox.left - padX) / newWidth * bitmap.width
            val by1 = (it.boundingBox.top - padY) / newHeight * bitmap.height
            val bx2 = (it.boundingBox.right - padX) / newWidth * bitmap.width
            val by2 = (it.boundingBox.bottom - padY) / newHeight * bitmap.height
            RectF(bx1, by1, bx2, by2)
        } ?: RectF()

        return SegmentationResult(
            confidence = best?.confidence ?: 0f,
            boundingBox = finalBox,
            mask = finalMask,
            inferenceTime = inferenceTime
        )
    }

    private fun generateMask(
        detections: List<Detection>,
        prototypes: FloatArray,
        origW: Int,
        origH: Int,
        padX: Float,
        padY: Float,
        newW: Int,
        newH: Int
    ): Bitmap {
        val maskBitmap = Bitmap.createBitmap(maskSize, maskSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(maskSize * maskSize)

        // Reset pixels
        pixels.fill(0)

        // Color: Ungu Muda (Light Purple) -> e.g., #AA66CC with alpha
        val maskColor = Color.argb(128, 170, 102, 204)

        for (det in detections) {
            // Scale bounding box to mask size (160x160)
            val bx1 = (det.boundingBox.left / inputSize * maskSize).toInt().coerceIn(0, maskSize - 1)
            val by1 = (det.boundingBox.top / inputSize * maskSize).toInt().coerceIn(0, maskSize - 1)
            val bx2 = (det.boundingBox.right / inputSize * maskSize).toInt().coerceIn(0, maskSize - 1)
            val by2 = (det.boundingBox.bottom / inputSize * maskSize).toInt().coerceIn(0, maskSize - 1)

            for (y in by1..by2) {
                val yOffset = y * maskSize
                for (x in bx1..bx2) {
                    val index = yOffset + x
                    var sum = 0f
                    val protoOffset = index * numProtoChannels
                    for (c in 0 until numProtoChannels) {
                        sum += prototypes[protoOffset + c] * det.maskCoeffs[c]
                    }
                    // sum > 0 is equivalent to sigmoid(sum) > 0.5
                    if (sum > 0f) {
                        pixels[index] = maskColor
                    }
                }
            }
        }

        maskBitmap.setPixels(pixels, 0, maskSize, 0, 0, maskSize, maskSize)

        // Crop the letterboxed mask back to the active area
        val cropX = (padX / inputSize * maskSize).toInt().coerceAtLeast(0)
        val cropY = (padY / inputSize * maskSize).toInt().coerceAtLeast(0)
        val cropW = (newW.toFloat() / inputSize * maskSize).toInt().coerceIn(1, maskSize - cropX)
        val cropH = (newH.toFloat() / inputSize * maskSize).toInt().coerceIn(1, maskSize - cropY)
        
        val croppedMask = Bitmap.createBitmap(maskBitmap, cropX, cropY, cropW, cropH)

        // Upsample to original size
        val finalResult = Bitmap.createScaledBitmap(croppedMask, origW, origH, true)
        
        if (croppedMask != maskBitmap) maskBitmap.recycle()
        return finalResult
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
        val mask: Bitmap,
        val inferenceTime: Long
    )
}
