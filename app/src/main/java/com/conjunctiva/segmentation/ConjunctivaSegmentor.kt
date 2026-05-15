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
    val cropBitmap: Bitmap?,         // Crop konjungtiva untuk preview
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

        val scale = minOf(640f / origW, 640f / origH)
        val newW = (origW * scale).toInt()
        val newH = (origH * scale).toInt()

        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(newH, newW, ResizeOp.ResizeMethod.BILINEAR))
            .add(ResizeWithCropOrPadOp(640, 640))
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

        val offsetX = (640f - newW) / 2f
        val offsetY = (640f - newH) / 2f

        return postprocess(bitmap, scale, offsetX, offsetY)
    }

    private fun postprocess(
        originalBitmap: Bitmap,
        scale: Float, offsetX: Float, offsetY: Float
    ): List<SegmentResult> {
        val results = mutableListOf<SegmentResult>()
        val boxes = outputBoxes.floatArray
        val protos = outputProtos.floatArray

        for (i in 0 until 300) {
            val offset = i * 38
            val confidence = boxes[offset + 4]
            if (confidence < CONF_THRESHOLD) continue

            // YOLOv8 output is x1,y1,x2,y2 in normalized [0,1] for the 640x640 input
            val x1n = boxes[offset + 0]
            val y1n = boxes[offset + 1]
            val x2n = boxes[offset + 2]
            val y2n = boxes[offset + 3]

            // 1. Map Box to original frame coordinates
            // Map normalized to 640px space, then inverse letterbox
            val x1 = (x1n * 640f - offsetX) / scale
            val y1 = (y1n * 640f - offsetY) / scale
            val x2 = (x2n * 640f - offsetX) / scale
            val y2 = (y2n * 640f - offsetY) / scale

            val mappedRect = RectF(
                x1.coerceIn(0f, originalBitmap.width.toFloat()),
                y1.coerceIn(0f, originalBitmap.height.toFloat()),
                x2.coerceIn(0f, originalBitmap.width.toFloat()),
                y2.coerceIn(0f, originalBitmap.height.toFloat())
            )

            if (mappedRect.width() <= 0 || mappedRect.height() <= 0) continue

            // 2. Generate Mask Bitmap
            val coeffs = FloatArray(NUM_MASK_COEFFS) { boxes[offset + 6 + it] }
            val maskBitmap = generateMaskBitmap(protos, coeffs)

            // 3. Create Crop for preview
            val crop = try {
                Bitmap.createBitmap(
                    originalBitmap,
                    mappedRect.left.toInt(),
                    mappedRect.top.toInt(),
                    mappedRect.width().toInt(),
                    mappedRect.height().toInt()
                )
            } catch (e: Exception) {
                null
            }

            results.add(SegmentResult(mappedRect, maskBitmap, crop, confidence))
        }

        // Return only the largest detection for conjunctiva
        return if (results.isEmpty()) emptyList()
        else listOf(results.maxBy { it.boundingBox.width() * it.boundingBox.height() })
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
