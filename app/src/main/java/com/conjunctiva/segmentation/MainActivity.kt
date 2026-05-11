package com.conjunctiva.segmentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.conjunctiva.segmentation.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var segmentor: ConjunctivaSegmentor
    
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    
    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var processEveryNFrames = 1 // Proses setiap frame, ubah ke 2-3 jika lag
    
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inisialisasi executor untuk kamera
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Inisialisasi segmentor
        try {
            segmentor = ConjunctivaSegmentor(this)
            Log.d(TAG, "Segmentor initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing segmentor", e)
            Toast.makeText(this, "Error loading model: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Cek permission kamera
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
                Toast.makeText(
                    this,
                    getString(R.string.camera_permission_required),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            // Image Analysis untuk inference
            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind semua use case sebelum rebinding
                cameraProvider.unbindAll()

                // Bind use cases ke camera
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        frameCount++
        
        // Skip frames untuk performa (opsional)
        if (frameCount % processEveryNFrames != 0) {
            imageProxy.close()
            return
        }

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val startTime = System.currentTimeMillis()
                
                // Konversi ImageProxy ke Bitmap
                val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
                
                // Jalankan segmentasi
                val results = segmentor.segment(bitmap)
                
                val inferenceTime = System.currentTimeMillis() - startTime
                
                // Update UI
                withContext(Dispatchers.Main) {
                    // Update overlay dengan hasil segmentasi
                    binding.overlayView.setResults(
                        results,
                        bitmap.width,
                        bitmap.height
                    )
                    
                    // Update info panel
                    updateInfoPanel(inferenceTime, results.size)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
            } finally {
                imageProxy.close()
            }
        }
    }

    private fun updateInfoPanel(inferenceTime: Long, detectionCount: Int) {
        // Update inference time
        binding.tvInferenceTime.text = "Inference: ${inferenceTime}ms"
        
        // Update detection count
        binding.tvDetections.text = "Detections: $detectionCount"
        
        // Calculate FPS
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - lastFpsTimestamp
        if (timeDiff >= 1000) {
            val fps = (frameCount * 1000.0 / timeDiff).toInt()
            binding.tvFps.text = "FPS: $fps"
            frameCount = 0
            lastFpsTimestamp = currentTime
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        segmentor.close()
    }
}
