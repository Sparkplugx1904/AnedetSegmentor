package com.conjunctiva.segmentation

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.conjunctiva.segmentation.databinding.ActivityMainBinding
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var inferenceExecutor: ExecutorService
    private lateinit var segmentor: ConjunctivaSegmentor
    private val mainHandler = Handler(Looper.getMainLooper())

    private val frameQueue = ArrayBlockingQueue<Bitmap>(1)
    private val completedInferences = AtomicInteger(0)

    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null

    private var lastFpsTimestamp = System.currentTimeMillis()
    
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newFixedThreadPool(2)
        inferenceExecutor = Executors.newSingleThreadExecutor()

        try {
            segmentor = ConjunctivaSegmentor(this)
            Log.d(TAG, "Segmentor initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing segmentor", e)
            Toast.makeText(this, "Error loading model: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        startInferenceConsumer()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
                        imageProxy.close()

                        if (!frameQueue.offer(bitmap)) {
                            frameQueue.poll()?.recycle()
                            frameQueue.offer(bitmap)
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private var lastResults: List<SegmentResult>? = null

    private fun startInferenceConsumer() {
        inferenceExecutor.execute {
            while (!Thread.currentThread().isInterrupted) {
                val bitmap = try {
                    frameQueue.take()
                } catch (_: InterruptedException) {
                    break
                }

                try {
                    val startTime = System.currentTimeMillis()
                    val result = segmentor.segment(bitmap)
                    val inferenceTime = System.currentTimeMillis() - startTime

                    val bw = bitmap.width
                    val bh = bitmap.height

                    mainHandler.post {
                        if (!isFinishing && !isDestroyed) {
                            // Recycle old bitmaps
                            lastResults?.forEach {
                                it.maskBitmap?.recycle()
                                it.cropBitmap?.recycle()
                            }
                            lastResults = result

                            binding.overlayView.setResults(result, bw, bh)
                            completedInferences.incrementAndGet()
                            updateInfoPanel(inferenceTime, result.size)
                        } else {
                            // Activity destroyed during inference
                            result.forEach {
                                it.maskBitmap?.recycle()
                                it.cropBitmap?.recycle()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Inference error", e)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun updateInfoPanel(inferenceTime: Long, detectionCount: Int) {
        binding.tvInferenceTime.text = "Inference: ${inferenceTime}ms"
        binding.tvDetections.text = "Detections: $detectionCount"
        
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - lastFpsTimestamp
        if (timeDiff >= 1000) {
            val n = completedInferences.getAndSet(0)
            val fps = (n * 1000.0 / timeDiff).toInt()
            binding.tvFps.text = "FPS: $fps"
            lastFpsTimestamp = currentTime
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inferenceExecutor.shutdownNow()
        cameraExecutor.shutdown()
        lastResults?.forEach {
            it.maskBitmap?.recycle()
            it.cropBitmap?.recycle()
        }
        if (::segmentor.isInitialized) {
            segmentor.close()
        }
    }
}
