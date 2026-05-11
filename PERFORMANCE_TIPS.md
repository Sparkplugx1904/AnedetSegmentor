# ⚡ Performance Optimization Tips

Panduan lengkap untuk mengoptimalkan performa aplikasi Conjunctiva Segmentation.

## 🎯 Performance Goals

| Metric | Target | Acceptable | Poor |
|--------|--------|------------|------|
| FPS | > 25 | 15-25 | < 15 |
| Inference Time | < 50ms | 50-100ms | > 100ms |
| Memory Usage | < 150MB | 150-200MB | > 200MB |
| Battery Drain | < 8%/10min | 8-12%/10min | > 12%/10min |
| Cold Start | < 2s | 2-4s | > 4s |

## 🔧 Optimization Techniques

### 1. Frame Skipping

**Problem**: Processing every frame is CPU-intensive.

**Solution**: Process every N frames.

```kotlin
// MainActivity.kt
private var processEveryNFrames = 2  // Skip 1 frame

if (frameCount % processEveryNFrames != 0) {
    imageProxy.close()
    return
}
```

**Impact**:
- FPS: +50% (visual smoothness)
- Inference Load: -50%
- Battery: -30%

**Recommendation**:
- Flagship: `processEveryNFrames = 1`
- Mid-range: `processEveryNFrames = 2`
- Budget: `processEveryNFrames = 3`

### 2. Camera Resolution

**Problem**: High resolution = more pixels to process.

**Solution**: Lower camera resolution.

```kotlin
// MainActivity.kt - startCamera()
imageAnalyzer = ImageAnalysis.Builder()
    .setTargetResolution(android.util.Size(480, 360))  // Lower from 640x480
    .build()
```

**Impact**:
- Inference Time: -30%
- Accuracy: -5% (minimal)
- Memory: -10%

**Recommendation**:
- High accuracy needed: 640x480
- Balanced: 480x360
- Maximum speed: 320x240

### 3. Model Input Size

**Problem**: Model expects 320x320, but we can train smaller.

**Solution**: Retrain model with smaller input size.

```python
# Training
model.export(format='tflite', imgsz=224, half=True)  # Instead of 320
```

**Impact**:
- Inference Time: -40%
- Accuracy: -10%
- Model Size: -30%

**Trade-off**: Significant accuracy loss for speed gain.

### 4. GPU Acceleration

**Problem**: CPU inference can be slow.

**Solution**: Enable GPU delegate (already implemented).

```kotlin
// ConjunctivaSegmentor.kt
val compatList = CompatibilityList()
if (compatList.isDelegateSupportedOnThisDevice) {
    addDelegate(GpuDelegate(compatList.bestOptionsForThisDevice))
}
```

**Impact**:
- Inference Time: -30% to -50% (device-dependent)
- Memory: +20MB
- Battery: +10% drain

**Note**: Not all devices benefit from GPU. Test on target device.

### 5. XNNPACK Optimization

**Problem**: Default CPU inference is not optimized.

**Solution**: Enable XNNPACK (already enabled).

```kotlin
// ConjunctivaSegmentor.kt
val options = Interpreter.Options().apply {
    setUseXNNPACK(true)
    setNumThreads(4)
}
```

**Impact**:
- Inference Time: -20% to -40%
- No memory overhead
- Works on all devices

**Recommendation**: Always keep enabled.

### 6. Thread Pool Tuning

**Problem**: Too many threads = context switching overhead.

**Solution**: Optimize thread count.

```kotlin
// ConjunctivaSegmentor.kt
val options = Interpreter.Options().apply {
    setNumThreads(Runtime.getRuntime().availableProcessors() / 2)
}
```

**Impact**:
- Inference Time: -10%
- CPU Usage: -15%

**Recommendation**:
- Flagship (8+ cores): 4 threads
- Mid-range (6-8 cores): 3 threads
- Budget (4 cores): 2 threads

### 7. Confidence Threshold

**Problem**: Processing low-confidence detections wastes time.

**Solution**: Increase confidence threshold.

```kotlin
// ConjunctivaSegmentor.kt
private const val CONFIDENCE_THRESHOLD = 0.6f  // Increase from 0.5f
```

**Impact**:
- Postprocessing Time: -20%
- False Positives: -50%
- True Positives: -5%

**Recommendation**: Balance between speed and recall.

### 8. NMS Optimization

**Problem**: NMS on many boxes is slow.

**Solution**: Limit max detections before NMS.

```kotlin
// ConjunctivaSegmentor.kt
private fun postprocess(...): List<SegmentationResult> {
    val detections = outputBoxes[0]
        .filter { it.confidence > CONFIDENCE_THRESHOLD }
        .sortedByDescending { it.confidence }
        .take(100)  // Limit to top 100
    
    return applyNMS(detections)
}
```

**Impact**:
- Postprocessing Time: -30%
- Memory: -10%

### 9. Bitmap Reuse

**Problem**: Creating new bitmaps causes GC pressure.

**Solution**: Reuse bitmap objects.

```kotlin
// MainActivity.kt
private var reusableBitmap: Bitmap? = null

private fun processImage(imageProxy: ImageProxy) {
    if (reusableBitmap == null || 
        reusableBitmap!!.width != imageProxy.width) {
        reusableBitmap = Bitmap.createBitmap(
            imageProxy.width, 
            imageProxy.height, 
            Bitmap.Config.ARGB_8888
        )
    }
    
    // Reuse bitmap
    ImageUtils.imageProxyToBitmap(imageProxy, reusableBitmap!!)
}
```

**Impact**:
- GC Pauses: -50%
- Memory Allocations: -70%
- Smoother FPS

### 10. Overlay Rendering

**Problem**: Redrawing overlay every frame is expensive.

**Solution**: Only invalidate when results change.

```kotlin
// OverlayView.kt
fun setResults(results: List<SegmentationResult>, ...) {
    if (results != this.results) {  // Only update if changed
        this.results = results
        invalidate()
    }
}
```

**Impact**:
- UI Thread Load: -30%
- Battery: -5%

### 11. Image Format

**Problem**: YUV to RGB conversion is slow.

**Solution**: Use RGBA_8888 output format.

```kotlin
// MainActivity.kt
imageAnalyzer = ImageAnalysis.Builder()
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .build()
```

**Impact**:
- Conversion Time: -50%
- Total Pipeline: -10%

**Note**: Already implemented in current code.

### 12. Backpressure Strategy

**Problem**: Frame queue buildup causes lag.

**Solution**: Keep only latest frame.

```kotlin
// MainActivity.kt
imageAnalyzer = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .build()
```

**Impact**:
- Latency: -200ms
- Responsiveness: +100%

**Note**: Already implemented.

## 📊 Performance Profiling

### Using Android Studio Profiler

1. **CPU Profiler**
   ```
   View > Tool Windows > Profiler
   Select device and app
   Click "CPU" to start recording
   Use app for 30 seconds
   Stop recording
   ```

   Look for:
   - Hot methods (> 10% CPU time)
   - Long-running methods (> 100ms)
   - Frequent GC pauses

2. **Memory Profiler**
   ```
   View > Tool Windows > Profiler
   Click "Memory"
   Monitor for 5 minutes
   ```

   Look for:
   - Memory leaks (increasing trend)
   - Large allocations
   - Frequent GC

3. **Energy Profiler**
   ```
   View > Tool Windows > Profiler
   Click "Energy"
   Monitor battery drain
   ```

   Target: < 10% per 10 minutes

### Using ADB Commands

```bash
# CPU usage
adb shell top -n 1 | grep conjunctiva

# Memory usage
adb shell dumpsys meminfo com.conjunctiva.segmentation

# FPS
adb shell dumpsys gfxinfo com.conjunctiva.segmentation

# Battery stats
adb shell dumpsys batterystats --reset
# Use app for 10 minutes
adb shell dumpsys batterystats | grep conjunctiva
```

## 🎛️ Device-Specific Tuning

### Flagship Devices (SD 8 Gen 2, A16 Bionic)

```kotlin
// Aggressive settings for maximum quality
private var processEveryNFrames = 1
private const val CONFIDENCE_THRESHOLD = 0.5f
.setTargetResolution(android.util.Size(640, 480))
.setNumThreads(4)
```

**Expected**: 25-30 FPS, 20-40ms inference

### Mid-range Devices (SD 778G, Dimensity 8000)

```kotlin
// Balanced settings
private var processEveryNFrames = 2
private const val CONFIDENCE_THRESHOLD = 0.55f
.setTargetResolution(android.util.Size(480, 360))
.setNumThreads(3)
```

**Expected**: 15-25 FPS, 40-80ms inference

### Budget Devices (SD 680, Helio G85)

```kotlin
// Conservative settings for stability
private var processEveryNFrames = 3
private const val CONFIDENCE_THRESHOLD = 0.6f
.setTargetResolution(android.util.Size(320, 240))
.setNumThreads(2)
```

**Expected**: 10-15 FPS, 80-150ms inference

## 🔍 Bottleneck Analysis

### Measure Each Stage

```kotlin
// ConjunctivaSegmentor.kt
fun segment(bitmap: Bitmap): List<SegmentationResult> {
    val t0 = System.currentTimeMillis()
    
    val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
    val t1 = System.currentTimeMillis()
    Log.d(TAG, "Resize: ${t1 - t0}ms")
    
    preprocessImage(resizedBitmap)
    val t2 = System.currentTimeMillis()
    Log.d(TAG, "Preprocess: ${t2 - t1}ms")
    
    runInference()
    val t3 = System.currentTimeMillis()
    Log.d(TAG, "Inference: ${t3 - t2}ms")
    
    val results = postprocess(bitmap.width, bitmap.height)
    val t4 = System.currentTimeMillis()
    Log.d(TAG, "Postprocess: ${t4 - t3}ms")
    
    Log.d(TAG, "Total: ${t4 - t0}ms")
    return results
}
```

**Typical Breakdown**:
- Resize: 5-10ms
- Preprocess: 10-20ms
- Inference: 20-80ms (main bottleneck)
- Postprocess: 5-15ms

## 🚀 Advanced Optimizations

### 1. Model Quantization

Convert FP16 to INT8 for faster inference:

```python
# Training/Export
model.export(
    format='tflite',
    imgsz=320,
    int8=True,  # INT8 quantization
    data='conjunctiva.yaml'  # Representative dataset
)
```

**Impact**:
- Inference Time: -50%
- Accuracy: -5% to -10%
- Model Size: -75%

### 2. Custom TFLite Ops

Implement custom operations in C++ for critical paths.

### 3. Neural Network API (NNAPI)

Use Android's NNAPI for hardware acceleration:

```kotlin
val options = Interpreter.Options().apply {
    setUseNNAPI(true)
}
```

**Note**: Device and Android version dependent.

### 4. Hexagon DSP

For Qualcomm devices, use Hexagon DSP delegate:

```kotlin
// Requires Hexagon delegate library
val options = Interpreter.Options().apply {
    addDelegate(HexagonDelegate())
}
```

## 📈 Monitoring in Production

### Add Performance Logging

```kotlin
// MainActivity.kt
private val performanceLogger = PerformanceLogger()

private fun processImage(imageProxy: ImageProxy) {
    val startTime = System.currentTimeMillis()
    
    // ... processing ...
    
    val totalTime = System.currentTimeMillis() - startTime
    performanceLogger.log(totalTime, results.size)
}

class PerformanceLogger {
    private val times = mutableListOf<Long>()
    
    fun log(time: Long, detections: Int) {
        times.add(time)
        
        if (times.size >= 100) {
            val avg = times.average()
            val p95 = times.sorted()[95]
            Log.i(TAG, "Avg: ${avg}ms, P95: ${p95}ms, Detections: $detections")
            times.clear()
        }
    }
}
```

## ✅ Performance Checklist

Before release:
- [ ] Profiled on target devices
- [ ] FPS > 15 on mid-range devices
- [ ] Inference time < 100ms
- [ ] Memory usage < 200MB
- [ ] No memory leaks (tested 30+ minutes)
- [ ] Battery drain < 12% per 10 minutes
- [ ] Cold start < 3 seconds
- [ ] No ANR (Application Not Responding)
- [ ] Smooth UI (no jank)
- [ ] Tested in various lighting conditions

## 🎓 Best Practices

1. **Profile First**: Don't optimize blindly
2. **Measure Impact**: Quantify every optimization
3. **Test on Real Devices**: Emulators lie
4. **Balance Trade-offs**: Speed vs Accuracy
5. **Monitor Production**: Track real-world performance
6. **Iterate**: Continuous improvement

---

**Remember**: Premature optimization is the root of all evil. Profile first, optimize second! 🚀
