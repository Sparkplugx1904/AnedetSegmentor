package com.conjunctiva.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
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

private const val TAG = "ConjunctivaSegmentor"
private const val MODEL_FILE = "yolo26n-seg_best_float16.tflite"
private const val INPUT_SIZE = 640
private const val CONF_THRESHOLD = 0.4f
private const val NUM_MASK_COEFFS = 32

data class SegmentResult(
    val boundingBox: RectF,          // Dalam koordinat bitmap asli (setelah rotasi)
    val maskBitmap: Bitmap?,         // Masker 160x160 as Bitmap
    val confidence: Float
)

class ConjunctivaSegmentor(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    private var protoH: Int = 160
    private var protoW: Int = 160

    private lateinit var outputBoxes: TensorBuffer
    private lateinit var outputProtos: TensorBuffer

    init {
        setupInterpreter()
    }

    private fun setupInterpreter() {
        val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
        val options = Interpreter.Options()

        val compatList = CompatibilityList()
        if (compatList.isDelegateSupportedOnThisDevice) {
            val delegateOptions = compatList.bestOptionsForThisDevice.apply {
                setPrecisionLossAllowed(true)
                setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
            }
            gpuDelegate = GpuDelegate(delegateOptions)
            options.addDelegate(gpuDelegate)
            Log.i(TAG, "GPU Delegate: AKTIF")
        } else {
            options.setNumThreads(4)
            Log.w(TAG, "GPU Delegate tidak didukung, fallback ke CPU 4 threads")
        }

        interpreter = Interpreter(modelBuffer, options)
        readModelShapes()
    }

    private fun readModelShapes() {
        val interp = interpreter ?: return

        val outShape0 = interp.getOutputTensor(0).shape()
        outputBoxes = TensorBuffer.createFixedSize(outShape0, DataType.FLOAT32)

        val outShape1 = interp.getOutputTensor(1).shape()
        protoH = outShape1[1]
        protoW = outShape1[2]
        outputProtos = TensorBuffer.createFixedSize(outShape1, DataType.FLOAT32)
    }

    fun segment(bitmap: Bitmap): List<SegmentResult> {
        val interp = interpreter ?: return emptyList()

        val origW = bitmap.width
        val origH = bitmap.height

        // 1. Calculate Letterbox Resize
        val scale = minOf(INPUT_SIZE.toFloat() / origW, INPUT_SIZE.toFloat() / origH)
        val newW = (origW * scale).toInt()
        val newH = (origH * scale).toInt()

        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(newH, newW, ResizeOp.ResizeMethod.BILINEAR))
            .add(ResizeWithCropOrPadOp(INPUT_SIZE, INPUT_SIZE))
            .add(NormalizeOp(0f, 255f))
            .build()

        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(bitmap)
        val processedImage = imageProcessor.process(tensorImage)

        val outputs = mapOf(
            0 to outputBoxes.buffer.rewind(),
            1 to outputProtos.buffer.rewind()
        )
        interp.runForMultipleInputsOutputs(arrayOf(processedImage.buffer), outputs)

        // Metadata for coordinate mapping
        // ResizeWithCropOrPadOp places image in center
        val offsetX = (INPUT_SIZE - newW) / 2f
        val offsetY = (INPUT_SIZE - newH) / 2f

        return postprocess(origW, origH, scale, offsetX, offsetY)
    }

    private fun postprocess(
        origW: Int, origH: Int,
        scale: Float, offsetX: Float, offsetY: Float
    ): List<SegmentResult> {
        val results = mutableListOf<SegmentResult>()
        val boxes = outputBoxes.floatArray
        val protos = outputProtos.floatArray

        for (i in 0 until 300) {
            val offset = i * 38
            val confidence = boxes[offset + 4]
            if (confidence < CONF_THRESHOLD) continue

            val cx = boxes[offset + 0]
            val cy = boxes[offset + 1]
            val w = boxes[offset + 2]
            val h = boxes[offset + 3]

            // 1. Map Box to original frame
            val x1 = (cx - w / 2f - offsetX) / scale
            val y1 = (cy - h / 2f - offsetY) / scale
            val x2 = (cx + w / 2f - offsetX) / scale
            val y2 = (cy + h / 2f - offsetY) / scale
            val mappedRect = RectF(x1, y1, x2, y2)

            // 2. Generate Mask Bitmap (Perform dot product on background thread)
            val coeffs = FloatArray(NUM_MASK_COEFFS) { boxes[offset + 6 + it] }
            val maskBitmap = generateMaskBitmap(protos, coeffs)

            results.add(SegmentResult(mappedRect, maskBitmap, confidence))
        }

        // Return only the best detection for conjunctiva
        return if (results.isEmpty()) emptyList() else listOf(results.maxBy { it.confidence })
    }

    private fun generateMaskBitmap(protos: FloatArray, coeffs: FloatArray): Bitmap {
        val maskBitmap = Bitmap.createBitmap(protoW, protoH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(protoW * protoH)
        
        val purple = Color.parseColor("#AA66CC")
        val r = Color.red(purple)
        val g = Color.green(purple)
        val b = Color.blue(purple)
        val alpha = 180

        for (y in 0 until protoH) {
            for (x in 0 until protoW) {
                var sum = 0f
                val pOffset = (y * protoW + x) * NUM_MASK_COEFFS
                for (k in 0 until NUM_MASK_COEFFS) {
                    sum += protos[pOffset + k] * coeffs[k]
                }

                // Sigmoid check (sum > 0 is equivalent to sigmoid(sum) > 0.5)
                if (sum > 0f) {
                    pixels[y * protoW + x] = Color.argb(alpha, r, g, b)
                } else {
                    pixels[y * protoW + x] = Color.TRANSPARENT
                }
            }
        }
        maskBitmap.setPixels(pixels, 0, protoW, 0, 0, protoW, protoH)
        return maskBitmap
    }

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}
