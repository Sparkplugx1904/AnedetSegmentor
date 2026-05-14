# Segmentation Performance Fix Bugfix Design

## Overview

This bugfix addresses three critical issues in the conjunctiva segmentation Android application:

1. **Unnecessary Bounding Box**: Remove the green bounding box overlay that clutters the segmentation visualization
2. **Poor Mask Quality**: Replace the simplistic edge-detection polygon extraction with sophisticated OpenCV-based contour detection using findContours, morphological operations, and contour approximation
3. **Low FPS Performance**: Implement a parallel inference pipeline with multi-threading, frame queue management, and proper synchronization to increase FPS from 2 FPS to 8-10 FPS

The fix strategy is minimal and targeted: remove one method call for the bounding box, integrate OpenCV Mobile for contour detection with proper preprocessing, and refactor the threading architecture to enable concurrent frame processing and inference execution.

## Glossary

- **Bug_Condition (C)**: The conditions that trigger the bugs - bounding box being drawn, simple edge detection producing poor polygons, and sequential single-threaded processing causing low FPS
- **Property (P)**: The desired behavior - clean polygon-only visualization, smooth accurate contours following object boundaries, and 8-10 FPS with parallel processing
- **Preservation**: Existing segmentation accuracy, coordinate mapping, camera functionality, and UI info panel that must remain unchanged
- **OverlayView**: The custom View in `app/src/main/java/com/conjunctiva/segmentation/OverlayView.kt` that renders segmentation results over the camera preview
- **extractPolygonFromMask**: The function in `ConjunctivaSegmentor.kt` that converts binary mask to polygon coordinates
- **cameraExecutor**: The ExecutorService in `MainActivity.kt` that handles camera frame processing
- **inferenceExecutor**: The ExecutorService in `MainActivity.kt` that runs model inference
- **frameQueue**: The ArrayBlockingQueue that buffers frames between camera processing and inference
- **OpenCV Mobile**: Lightweight OpenCV distribution for Android (`github-reference/opencv-mobile-4.13.0-android/`) providing native image processing functions
- **findContours**: OpenCV function that detects contours in binary images using border following algorithms
- **morphologyEx**: OpenCV function for morphological operations (erosion, dilation, opening, closing) to clean binary masks
- **approxPolyDP**: OpenCV function that approximates contour curves using Douglas-Peucker algorithm for smooth polygons

## Bug Details

### Bug Condition

The bugs manifest in three distinct scenarios:

**Bug 1 - Bounding Box**: When `OverlayView.onDraw()` renders segmentation results, it calls `drawBoundingBox()` which draws a green rectangular border around each detection. This occurs for every segmentation result regardless of the application's focus on polygon masks.

**Bug 2 - Poor Mask Quality**: When `extractPolygonFromMask()` processes the binary mask from the model, it uses a simplistic algorithm that only finds left/right edges per row without proper contour detection. The `MaskContour.contourFromBinaryRoi()` uses Moore-neighbor boundary tracing which is basic and doesn't handle noise, holes, or produce smooth contours effectively.

**Bug 3 - Low FPS**: When frames arrive from the camera, `processImage()` runs on a single-threaded executor and blocks until inference completes (~500ms). The `inferenceExecutor` is also single-threaded, and there's no parallelism between frame acquisition, preprocessing, and inference execution.

**Formal Specification:**
```
FUNCTION isBugCondition1_BoundingBox(renderContext)
  INPUT: renderContext containing OverlayView drawing state
  OUTPUT: boolean
  
  RETURN renderContext.drawingSegmentationResults == true
         AND drawBoundingBox() is called in onDraw()
         AND boundingBoxVisible == true
END FUNCTION

FUNCTION isBugCondition2_PoorMask(maskProcessing)
  INPUT: maskProcessing containing mask and extraction algorithm
  OUTPUT: boolean
  
  RETURN maskProcessing.hasBinaryMask == true
         AND maskProcessing.algorithm == "simple_edge_detection"
         AND NOT usesOpenCVFindContours(maskProcessing)
         AND NOT usesMorphologicalOperations(maskProcessing)
         AND polygonQuality(maskProcessing.output) < QUALITY_THRESHOLD
END FUNCTION

FUNCTION isBugCondition3_LowFPS(processingPipeline)
  INPUT: processingPipeline containing threading and execution state
  OUTPUT: boolean
  
  RETURN processingPipeline.cameraExecutor.threadCount == 1
         AND processingPipeline.inferenceExecutor.threadCount == 1
         AND processingPipeline.processingMode == "sequential_blocking"
         AND processingPipeline.measuredFPS <= 2
         AND processingPipeline.inferenceTime >= 400
END FUNCTION
```

### Examples

**Bug 1 - Bounding Box:**
- **Current**: User sees red polygon mask + green bounding box rectangle around conjunctiva
- **Expected**: User sees only red polygon mask without bounding box
- **Impact**: Visual clutter, unprofessional appearance for segmentation app

**Bug 2 - Poor Mask Quality:**
- **Current**: Polygon has jagged edges, doesn't follow conjunctiva boundary smoothly, includes noise artifacts
- **Expected**: Polygon smoothly follows conjunctiva contour with clean edges
- **Example**: Mask with small holes produces polygon with irregular jumps instead of smooth curve
- **Edge Case**: Very small masks (<8 pixels) should fall back to bounding box polygon

**Bug 3 - Low FPS:**
- **Current**: Frame arrives → wait for previous inference → process → inference (500ms) → display → next frame = 2 FPS
- **Expected**: Frame arrives → queue → process in parallel → inference concurrent → display = 8-10 FPS
- **Example**: At 500ms inference time, sequential processing gives 1000ms/500ms = 2 FPS max
- **Edge Case**: When queue is full, drop oldest frame to prevent memory buildup

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Segmentation accuracy with confidence threshold 0.35f must remain identical
- NMS with IOU threshold 0.45f must continue to work exactly as before
- Mask threshold 0.5f for binary mask generation must be preserved
- Fit-center (letterbox) coordinate mapping in OverlayView must remain unchanged
- Label display "Conjunctiva X%" with black semi-transparent background must continue working
- Polygon fill color (red semi-transparent) and border color (red solid) must stay the same
- Camera initialization with back camera default must be preserved
- Preview resolution target 640x480 must remain unchanged
- Executor and segmentor cleanup on destroy must continue working
- Permission handling and error toasts must remain unchanged
- Info panel display of inference time, detection count, and FPS must continue working

**Scope:**
All inputs and scenarios that do NOT involve the three specific bugs should be completely unaffected by this fix. This includes:
- Model inference logic and tensor processing
- Coordinate transformation calculations
- Camera preview rendering
- Permission handling and lifecycle management
- Error handling and logging
- UI layout and styling (except bounding box removal)

## Hypothesized Root Cause

Based on the bug description and code analysis, the root causes are:

### Bug 1 - Bounding Box

**Root Cause**: `OverlayView.onDraw()` explicitly calls `drawBoundingBox()` method which renders a green rectangle using `boxPaint`. This was likely added during development for debugging but never removed for production.

**Evidence**: 
- `OverlayView.kt` contains `boxPaint` definition and `drawBoundingBox()` method
- The method is called in the main `onDraw()` loop for each result
- No configuration or flag controls whether bounding box is drawn

**Fix Approach**: Simply remove the `drawBoundingBox()` call from `onDraw()` method

### Bug 2 - Poor Mask Quality

**Root Cause Category 1 - Simplistic Algorithm**: The current `extractPolygonFromMask()` uses basic edge detection (left/right per row) without proper contour detection. `MaskContour.contourFromBinaryRoi()` uses Moore-neighbor tracing which is a basic boundary following algorithm.

**Root Cause Category 2 - No Noise Filtering**: The mask from the model may contain noise (small isolated pixels) and holes (missing pixels inside the object). The current algorithm doesn't apply morphological operations to clean the mask before contour extraction.

**Root Cause Category 3 - No Contour Smoothing**: Even if the boundary is traced correctly, the resulting polygon has too many points and follows pixel boundaries exactly, creating jagged edges instead of smooth curves.

**Root Cause Category 4 - No Contour Approximation**: The algorithm doesn't use contour approximation techniques like Douglas-Peucker (which is implemented but may not be tuned correctly) or polygon simplification to reduce points while maintaining shape.

**Fix Approach**: 
- Integrate OpenCV Mobile library
- Apply morphological operations (erosion + dilation = opening) to remove noise
- Use OpenCV `findContours()` for robust contour detection
- Apply `approxPolyDP()` for smooth polygon approximation
- Filter contours by area to remove small noise regions

### Bug 3 - Low FPS

**Root Cause Category 1 - Single-Threaded Camera Executor**: `MainActivity.kt` line 75 uses `Executors.newSingleThreadExecutor()` for `cameraExecutor`, limiting frame processing to one thread.

**Root Cause Category 2 - Sequential Processing**: `processImage()` converts ImageProxy to Bitmap and queues it, but the single thread means frames are processed sequentially even though inference runs on a separate executor.

**Root Cause Category 3 - Blocking Inference**: While `inferenceExecutor` is separate, it's also single-threaded. With 500ms inference time, only 2 inferences can complete per second.

**Root Cause Category 4 - No Parallel Pipeline**: The architecture doesn't allow frame N+1 to be preprocessed while frame N is being inferred. Everything is sequential: acquire → convert → queue → inference → display → repeat.

**Fix Approach**:
- Change `cameraExecutor` to `Executors.newFixedThreadPool(2)` for parallel frame processing
- Keep `inferenceExecutor` single-threaded but ensure it doesn't block camera processing
- Implement proper frame queue with backpressure (already using `ArrayBlockingQueue(1)` with `STRATEGY_KEEP_ONLY_LATEST`)
- Consider atomic flags to prevent concurrent inference on same model instance if needed
- Optimize frame dropping strategy when inference can't keep up

## Correctness Properties

Property 1: Bug Condition - Bounding Box Removal

_For any_ rendering context where segmentation results are being drawn (isBugCondition1_BoundingBox returns true), the fixed OverlayView SHALL render only the polygon mask without drawing any bounding box rectangle, producing a clean visualization with only the red semi-transparent polygon and its border.

**Validates: Requirements 2.1, 2.2**

Property 2: Bug Condition - Sophisticated Contour Detection

_For any_ mask processing where a binary mask is available (isBugCondition2_PoorMask returns true), the fixed extractPolygonFromMask function SHALL use OpenCV findContours with morphological preprocessing (erosion/dilation) and approxPolyDP for contour approximation, producing smooth polygons that accurately follow object boundaries with quality score above QUALITY_THRESHOLD.

**Validates: Requirements 2.3, 2.4, 2.5**

Property 3: Bug Condition - Parallel Inference Pipeline

_For any_ processing pipeline execution where frames are being captured and processed (isBugCondition3_LowFPS returns true), the fixed system SHALL use multi-threaded executors (cameraExecutor with 2+ threads) and parallel processing architecture, achieving measured FPS of 8-10 with concurrent frame acquisition, preprocessing, and inference execution.

**Validates: Requirements 2.6, 2.7, 2.8, 2.9, 2.10**

Property 4: Preservation - Segmentation Accuracy

_For any_ input frame where segmentation is performed, the fixed code SHALL produce exactly the same segmentation results (confidence scores, bounding boxes, mask quality) as the original code, preserving confidence threshold 0.35f, NMS with IOU 0.45f, and mask threshold 0.5f.

**Validates: Requirements 3.1, 3.2, 3.3**

Property 5: Preservation - Rendering and Coordinates

_For any_ rendering operation where polygons and labels are drawn, the fixed code SHALL use the same fit-center coordinate mapping, label formatting, and color schemes as the original code, preserving visual appearance except for the removed bounding box.

**Validates: Requirements 3.4, 3.5, 3.6**

Property 6: Preservation - Camera and Lifecycle

_For any_ camera initialization, preview display, or lifecycle event (destroy, permission), the fixed code SHALL behave identically to the original code, preserving camera configuration, executor cleanup, and error handling.

**Validates: Requirements 3.7, 3.8, 3.9, 3.10**

Property 7: Preservation - Info Panel

_For any_ inference completion or detection event, the fixed code SHALL continue to display inference time, detection count, and FPS in the info panel with the same calculation methods and update frequency as the original code.

**Validates: Requirements 3.11, 3.12, 3.13**

## Fix Implementation

### Changes Required

Assuming our root cause analysis is correct:

#### Fix 1: Remove Bounding Box

**File**: `app/src/main/java/com/conjunctiva/segmentation/OverlayView.kt`

**Function**: `onDraw()`

**Specific Changes**:
1. **Remove drawBoundingBox call**: Delete or comment out the line that calls `drawBoundingBox()` in the main rendering loop
   - Locate the loop: `for (result in results) { ... }`
   - Remove: `drawBoundingBox(canvas, result.boundingBox, scale, offsetX, offsetY)`
   - Keep: `drawPolygon()` and `drawLabel()` calls

2. **Optional cleanup**: Remove `boxPaint` definition and `drawBoundingBox()` method entirely if not used elsewhere
   - Remove `boxPaint` Paint object definition
   - Remove `drawBoundingBox()` method implementation

#### Fix 2: Integrate OpenCV for Contour Detection

**File**: `app/build.gradle.kts`

**Specific Changes**:
1. **Add OpenCV Mobile dependency**: Add implementation line for OpenCV Mobile from `github-reference/opencv-mobile-4.13.0-android/`
   ```kotlin
   implementation(files("../github-reference/opencv-mobile-4.13.0-android/opencv-mobile-4.13.0.aar"))
   ```
   - Add after existing implementation dependencies
   - Ensure path is correct relative to app module

**File**: `app/src/main/java/com/conjunctiva/segmentation/ConjunctivaSegmentor.kt`

**Function**: `extractPolygonFromMask()`

**Specific Changes**:
1. **Import OpenCV classes**: Add imports at top of file
   ```kotlin
   import org.opencv.android.OpenCVLoader
   import org.opencv.core.*
   import org.opencv.imgproc.Imgproc
   ```

2. **Initialize OpenCV**: Add static initialization in companion object
   ```kotlin
   init {
       if (!OpenCVLoader.initLocal()) {
           Log.e(TAG, "OpenCV initialization failed")
       } else {
           Log.d(TAG, "OpenCV initialized successfully")
       }
   }
   ```

3. **Replace extractPolygonFromMask implementation**: Rewrite the function to use OpenCV
   - Convert `roiBinary` Array<BooleanArray> to OpenCV Mat (CV_8UC1)
   - Apply morphological opening: `Imgproc.morphologyEx(src, dst, Imgproc.MORPH_OPEN, kernel)`
     - Use kernel size 3x3 or 5x5 for noise removal
   - Find contours: `Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)`
   - Filter contours by area: keep only contours with area >= minArea
   - Select largest contour by area
   - Approximate polygon: `Imgproc.approxPolyDP(contour, approxCurve, epsilon, true)`
     - Use epsilon = 0.01 * arcLength for smooth approximation
   - Convert MatOfPoint2f back to List<Pair<Float, Float>>
   - Map coordinates from ROI to original image using existing `protoToImage()` logic

4. **Add helper functions**: Create utility functions for OpenCV conversions
   - `booleanArrayToMat(binary: Array<BooleanArray>): Mat` - converts binary array to OpenCV Mat
   - `matToPointList(mat: MatOfPoint2f): List<Pair<Float, Float>>` - converts OpenCV points to Kotlin pairs
   - `filterContoursByArea(contours: List<MatOfPoint>, minArea: Int): List<MatOfPoint>` - filters small contours

5. **Fallback handling**: Keep fallback to bboxToPolygon if OpenCV fails or no contours found
   - Wrap OpenCV calls in try-catch
   - Log errors and fall back to bounding box polygon

**File**: `app/src/main/java/com/conjunctiva/segmentation/MaskContour.kt`

**Specific Changes**:
1. **Deprecate or remove**: Mark existing functions as deprecated since OpenCV will be used
   - Add `@Deprecated` annotation to `contourFromBinaryRoi()`
   - Keep functions for now in case fallback is needed
   - Consider removing in future cleanup

#### Fix 3: Implement Parallel Inference Pipeline

**File**: `app/src/main/java/com/conjunctiva/segmentation/MainActivity.kt`

**Specific Changes**:
1. **Change cameraExecutor to thread pool**: Replace single thread executor with fixed thread pool
   - Line ~75: Change `Executors.newSingleThreadExecutor()` to `Executors.newFixedThreadPool(2)`
   - Use 2 threads for camera frame processing (one for conversion, one for queuing)
   - Consider 3 threads if device has 4+ cores

2. **Keep inferenceExecutor single-threaded**: TFLite Interpreter is not thread-safe for concurrent inference
   - Keep `Executors.newSingleThreadExecutor()` for inferenceExecutor
   - Add comment explaining thread safety requirement
   - Consider adding atomic flag to prevent concurrent inference if needed

3. **Optimize frame queue management**: Improve backpressure handling
   - Keep `ArrayBlockingQueue(1)` for latest-frame-only strategy
   - Ensure `frameQueue.offer()` with `poll()` fallback is working correctly
   - Add logging for dropped frames to monitor performance

4. **Add synchronization if needed**: Protect model inference from concurrent access
   - Add `@Volatile private var isInferenceRunning = false` flag
   - Check flag before starting inference, skip if already running
   - Or use `AtomicBoolean` for thread-safe flag

5. **Optimize processImage**: Ensure non-blocking behavior
   - Verify `imageProxy.close()` is called immediately after bitmap conversion
   - Ensure bitmap conversion doesn't block camera thread
   - Consider moving bitmap conversion to inference thread if needed

6. **Monitor and log performance**: Add detailed logging for debugging
   - Log frame drop count
   - Log queue size and wait times
   - Log thread pool utilization
   - Add FPS calculation for camera frames vs inference completions

#### Fix 4: Fix Failing Unit Tests

**File**: `app/src/test/java/com/conjunctiva/segmentation/ImageUtilsTest.kt`

**Specific Changes**:
1. **Fix testResizeBitmap**: Update assertions to match actual behavior
   - Current issue: Test expects both dimensions <= 320, but aspect ratio preservation may result in one dimension < 320
   - Fix: Assert that max(width, height) <= 320 and aspect ratio is preserved
   - Or: Assert specific expected dimensions based on input 640x480 → 320x240

2. **Fix testCropCenterSquare**: Update expected dimension
   - Current issue: Test expects 480x480 but may get different result
   - Fix: Verify actual implementation behavior and update assertion
   - Ensure test bitmap is not recycled before assertion

3. **Fix testCropCenterSquareAlreadySquare**: Verify no-op behavior
   - Current issue: May be creating new bitmap instead of returning same
   - Fix: Assert dimensions are correct (500x500)
   - Consider asserting bitmap reference if implementation should return same instance

#### Fix 5: Fix Deprecated API Warning

**File**: `app/src/main/java/com/conjunctiva/segmentation/MainActivity.kt`

**Specific Changes**:
1. **Replace setTargetResolution**: Update to new CameraX API
   - Line 119: Replace `.setTargetResolution(android.util.Size(640, 480))`
   - Use: `.setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(...)).build())`
   - Or use: `.setTargetResolution()` with proper import if still supported in current CameraX version
   - Check CameraX version in gradle and use appropriate API

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bugs on unfixed code, then verify the fixes work correctly and preserve existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bugs BEFORE implementing the fix. Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Write tests that capture the current buggy behavior, run them on UNFIXED code to observe failures, then verify they pass after the fix.

**Test Cases**:
1. **Bounding Box Visibility Test**: Capture OverlayView rendering output and verify bounding box is present (will pass on unfixed code, fail on fixed code)
2. **Polygon Quality Test**: Generate test mask with known shape, extract polygon, measure smoothness and accuracy (will show poor quality on unfixed code)
3. **FPS Measurement Test**: Run inference loop for 10 seconds, measure FPS (will show ~2 FPS on unfixed code)
4. **Thread Count Test**: Verify cameraExecutor thread count (will show 1 thread on unfixed code)
5. **Contour Algorithm Test**: Verify that MaskContour is used instead of OpenCV (will pass on unfixed code, fail on fixed code)

**Expected Counterexamples**:
- Bounding box is visible in rendered output
- Polygon has jagged edges and doesn't follow smooth curves
- FPS is consistently 2 or lower
- Only 1 thread is used for camera processing
- OpenCV is not initialized or used

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**
```
FOR ALL renderContext WHERE isBugCondition1_BoundingBox(renderContext) DO
  output := OverlayView_fixed.onDraw(renderContext)
  ASSERT NOT boundingBoxVisible(output)
  ASSERT polygonVisible(output)
END FOR

FOR ALL maskProcessing WHERE isBugCondition2_PoorMask(maskProcessing) DO
  polygon := extractPolygonFromMask_fixed(maskProcessing.mask)
  ASSERT usesOpenCV(maskProcessing)
  ASSERT polygonQuality(polygon) >= QUALITY_THRESHOLD
  ASSERT polygonSmoothness(polygon) >= SMOOTHNESS_THRESHOLD
END FOR

FOR ALL processingPipeline WHERE isBugCondition3_LowFPS(processingPipeline) DO
  fps := measureFPS_fixed(processingPipeline, duration=10s)
  ASSERT fps >= 8
  ASSERT processingPipeline.cameraExecutor.threadCount >= 2
  ASSERT canProcessConcurrently(processingPipeline)
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed function produces the same result as the original function.

**Pseudocode:**
```
FOR ALL frame WHERE isValidSegmentationInput(frame) DO
  results_original := segment_original(frame)
  results_fixed := segment_fixed(frame)
  ASSERT results_original.confidence == results_fixed.confidence
  ASSERT results_original.boundingBox == results_fixed.boundingBox
  ASSERT results_original.classId == results_fixed.classId
  // Polygon may differ due to better contour detection, but mask area should be similar
  ASSERT abs(polygonArea(results_original) - polygonArea(results_fixed)) < AREA_TOLERANCE
END FOR

FOR ALL renderContext WHERE hasValidResults(renderContext) DO
  // Verify coordinate mapping unchanged
  ASSERT coordinateMapping_original(renderContext) == coordinateMapping_fixed(renderContext)
  // Verify label rendering unchanged
  ASSERT labelText_original(renderContext) == labelText_fixed(renderContext)
  // Verify colors unchanged (except bounding box removed)
  ASSERT polygonColor_original == polygonColor_fixed
END FOR

FOR ALL cameraEvent WHERE isCameraLifecycleEvent(cameraEvent) DO
  behavior_original := handleCameraEvent_original(cameraEvent)
  behavior_fixed := handleCameraEvent_fixed(cameraEvent)
  ASSERT behavior_original == behavior_fixed
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many test cases automatically across the input domain
- It catches edge cases that manual unit tests might miss
- It provides strong guarantees that behavior is unchanged for all non-buggy inputs

**Test Plan**: Observe behavior on UNFIXED code first for segmentation accuracy, coordinate mapping, and camera functionality, then write property-based tests capturing that behavior.

**Test Cases**:
1. **Segmentation Accuracy Preservation**: Run inference on 100 test images, compare confidence scores and bounding boxes between original and fixed code
2. **Coordinate Mapping Preservation**: Generate random image sizes and overlay dimensions, verify coordinate transformation produces same results
3. **Camera Functionality Preservation**: Test camera initialization, preview, and lifecycle events produce same behavior
4. **Info Panel Preservation**: Verify inference time, detection count, and FPS calculations use same formulas

### Unit Tests

- Test OverlayView rendering without bounding box (verify drawBoundingBox not called)
- Test OpenCV initialization succeeds
- Test booleanArrayToMat conversion produces correct Mat format
- Test matToPointList conversion produces correct Kotlin pairs
- Test filterContoursByArea removes small contours correctly
- Test extractPolygonFromMask with various mask shapes (circle, rectangle, irregular)
- Test extractPolygonFromMask fallback when OpenCV fails
- Test cameraExecutor thread pool has correct thread count
- Test frameQueue backpressure drops old frames correctly
- Test ImageUtils.resizeBitmap preserves aspect ratio
- Test ImageUtils.cropCenterSquare produces square output

### Property-Based Tests

- Generate random binary masks, verify extractPolygonFromMask produces valid polygons (at least 3 points, closed shape)
- Generate random image dimensions, verify coordinate mapping preserves relative positions
- Generate random segmentation results, verify rendering produces valid canvas operations
- Generate random frame sequences, verify frame queue management doesn't leak memory
- Test that polygon area is within tolerance of mask area across many random masks

### Integration Tests

- Test full pipeline: camera → frame → inference → rendering with fixed code
- Test FPS measurement over 30 second run, verify 8-10 FPS achieved
- Test visual output: capture screenshot, verify only polygon visible (no bounding box)
- Test polygon quality: visual inspection of rendered polygons shows smooth contours
- Test memory usage: verify no memory leaks over extended run (5 minutes)
- Test thread safety: verify no crashes or race conditions under load
- Test error handling: verify graceful fallback when OpenCV fails
- Test device compatibility: verify works on different Android devices and API levels
