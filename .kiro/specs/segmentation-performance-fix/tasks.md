# Implementation Plan

## Overview

This task list implements fixes for three critical bugs in the conjunctiva segmentation Android application:
1. **Bug 1**: Remove unnecessary bounding box overlay
2. **Bug 2**: Replace simplistic polygon extraction with sophisticated OpenCV contour detection
3. **Bug 3**: Implement parallel inference pipeline to increase FPS from 2 to 8-10

The workflow follows the bug condition methodology: explore bugs first, implement fixes, then verify preservation.

---

## Phase 1: Bug Condition Exploration

- [ ] 1. Write bug condition exploration tests (BEFORE implementing fixes)
  - **Property 1: Bug Condition** - Three Bugs Exist on Unfixed Code
  - **CRITICAL**: These tests MUST FAIL on unfixed code - failure confirms the bugs exist
  - **DO NOT attempt to fix the tests or the code when they fail**
  - **NOTE**: These tests encode the expected behavior - they will validate the fixes when they pass after implementation
  - **GOAL**: Surface counterexamples that demonstrate the bugs exist
  - Write property-based test for Bug 1 (Bounding Box Visibility):
    - Test that `OverlayView.onDraw()` calls `drawBoundingBox()` method (currently true)
    - Use reflection or instrumentation to verify method call exists
    - Expected: Test PASSES on unfixed code (confirms bounding box is drawn)
    - After fix: Test should FAIL (confirms bounding box is removed)
  - Write property-based test for Bug 2 (Poor Mask Quality):
    - Generate test masks with known shapes (circle, rectangle, irregular polygon)
    - Call `extractPolygonFromMask()` on unfixed code
    - Measure polygon quality: smoothness score, edge accuracy, noise handling
    - Test that quality score < QUALITY_THRESHOLD (e.g., 0.7)
    - Test that algorithm does NOT use OpenCV (verify no OpenCV imports/calls)
    - Expected: Test PASSES on unfixed code (confirms poor quality)
    - After fix: Test should FAIL (confirms improved quality with OpenCV)
  - Write property-based test for Bug 3 (Low FPS):
    - Measure FPS over 10 second run on unfixed code
    - Test that FPS <= 2
    - Test that `cameraExecutor` thread count == 1
    - Test that processing is sequential (no parallel pipeline)
    - Expected: Test PASSES on unfixed code (confirms low FPS)
    - After fix: Test should FAIL (confirms improved FPS)
  - Run all tests on UNFIXED code
  - **EXPECTED OUTCOME**: All tests PASS (this is correct - it proves the bugs exist)
  - Document counterexamples found:
    - Bug 1: Bounding box method is called in onDraw()
    - Bug 2: Polygon quality score is X (below threshold), no OpenCV usage detected
    - Bug 3: Measured FPS is Y (≤2), thread count is 1
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10_

---

## Phase 2: Implementation

- [ ] 2. Fix Bug 1 - Remove Bounding Box

  - [ ] 2.1 Remove drawBoundingBox call from OverlayView.onDraw()
    - File: `app/src/main/java/com/conjunctiva/segmentation/OverlayView.kt`
    - Locate the main rendering loop in `onDraw()` method
    - Find the line that calls `drawBoundingBox(canvas, result.boundingBox, scale, offsetX, offsetY)`
    - Remove or comment out this line
    - Keep `drawPolygon()` and `drawLabel()` calls intact
    - _Bug_Condition: isBugCondition1_BoundingBox(renderContext) where drawBoundingBox() is called_
    - _Expected_Behavior: Render only polygon mask without bounding box rectangle_
    - _Preservation: Polygon rendering, label display, coordinate mapping unchanged_
    - _Requirements: 1.1, 1.2, 2.1, 2.2_

  - [ ] 2.2 Optional cleanup - Remove unused bounding box code
    - File: `app/src/main/java/com/conjunctiva/segmentation/OverlayView.kt`
    - Remove `boxPaint` Paint object definition (if not used elsewhere)
    - Remove `drawBoundingBox()` method implementation entirely
    - This is optional cleanup to reduce code clutter
    - _Requirements: 2.1, 2.2_

- [ ] 3. Fix Bug 2 - Integrate OpenCV for Sophisticated Contour Detection

  - [ ] 3.1 Add OpenCV Mobile dependency
    - File: `app/build.gradle.kts`
    - Add implementation line after existing dependencies:
      ```kotlin
      implementation(files("../github-reference/opencv-mobile-4.13.0-android/opencv-mobile-4.13.0.aar"))
      ```
    - Verify path is correct relative to app module
    - Sync Gradle to download dependency
    - _Requirements: 2.3_

  - [ ] 3.2 Initialize OpenCV in ConjunctivaSegmentor
    - File: `app/src/main/java/com/conjunctiva/segmentation/ConjunctivaSegmentor.kt`
    - Add imports at top of file:
      ```kotlin
      import org.opencv.android.OpenCVLoader
      import org.opencv.core.*
      import org.opencv.imgproc.Imgproc
      ```
    - Add static initialization in companion object:
      ```kotlin
      init {
          if (!OpenCVLoader.initLocal()) {
              Log.e(TAG, "OpenCV initialization failed")
          } else {
              Log.d(TAG, "OpenCV initialized successfully")
          }
      }
      ```
    - _Requirements: 2.3_

  - [ ] 3.3 Create OpenCV helper functions
    - File: `app/src/main/java/com/conjunctiva/segmentation/ConjunctivaSegmentor.kt`
    - Add helper function `booleanArrayToMat(binary: Array<BooleanArray>): Mat`
      - Convert 2D boolean array to OpenCV Mat (CV_8UC1)
      - Set pixel value 255 for true, 0 for false
    - Add helper function `matToPointList(mat: MatOfPoint2f): List<Pair<Float, Float>>`
      - Convert OpenCV MatOfPoint2f to Kotlin List<Pair<Float, Float>>
      - Extract x,y coordinates from Point2f array
    - Add helper function `filterContoursByArea(contours: List<MatOfPoint>, minArea: Int): List<MatOfPoint>`
      - Filter contours by area using `Imgproc.contourArea()`
      - Keep only contours with area >= minArea
      - Return filtered list sorted by area descending
    - _Requirements: 2.3, 2.4_

  - [ ] 3.4 Rewrite extractPolygonFromMask with OpenCV
    - File: `app/src/main/java/com/conjunctiva/segmentation/ConjunctivaSegmentor.kt`
    - Function: `extractPolygonFromMask()`
    - Replace entire implementation with OpenCV-based approach:
      1. Convert `roiBinary` Array<BooleanArray> to OpenCV Mat using `booleanArrayToMat()`
      2. Apply morphological opening to remove noise:
         - Create kernel: `val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))`
         - Apply opening: `Imgproc.morphologyEx(src, dst, Imgproc.MORPH_OPEN, kernel)`
      3. Find contours:
         - `val contours = ArrayList<MatOfPoint>()`
         - `val hierarchy = Mat()`
         - `Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)`
      4. Filter contours by area using `filterContoursByArea(contours, minArea)`
      5. Select largest contour: `val largestContour = filteredContours.maxByOrNull { Imgproc.contourArea(it) }`
      6. Approximate polygon using Douglas-Peucker:
         - Convert to MatOfPoint2f: `val contour2f = MatOfPoint2f(*largestContour.toArray())`
         - Calculate epsilon: `val epsilon = 0.01 * Imgproc.arcLength(contour2f, true)`
         - Approximate: `val approxCurve = MatOfPoint2f()`
         - `Imgproc.approxPolyDP(contour2f, approxCurve, epsilon, true)`
      7. Convert back to List<Pair<Float, Float>> using `matToPointList()`
      8. Map coordinates from ROI to original image using existing `protoToImage()` logic
      9. Wrap in try-catch, fallback to `bboxToPolygon()` if OpenCV fails or no contours found
    - _Bug_Condition: isBugCondition2_PoorMask(maskProcessing) where simple edge detection is used_
    - _Expected_Behavior: Smooth polygons following object boundaries with quality >= QUALITY_THRESHOLD_
    - _Preservation: Coordinate mapping, fallback behavior unchanged_
    - _Requirements: 1.3, 1.4, 1.5, 2.3, 2.4, 2.5_

  - [ ] 3.5 Deprecate old MaskContour functions
    - File: `app/src/main/java/com/conjunctiva/segmentation/MaskContour.kt`
    - Add `@Deprecated` annotation to `contourFromBinaryRoi()` function
    - Add deprecation message: "Use OpenCV-based contour detection in ConjunctivaSegmentor"
    - Keep functions for now as fallback (consider removing in future cleanup)
    - _Requirements: 2.3_

- [ ] 4. Fix Bug 3 - Implement Parallel Inference Pipeline

  - [ ] 4.1 Change cameraExecutor to thread pool
    - File: `app/src/main/java/com/conjunctiva/segmentation/MainActivity.kt`
    - Line ~75: Locate `cameraExecutor = Executors.newSingleThreadExecutor()`
    - Replace with: `cameraExecutor = Executors.newFixedThreadPool(2)`
    - Add comment explaining: "Use 2 threads for parallel frame processing (conversion + queuing)"
    - Consider 3 threads if device has 4+ cores (can be tuned later)
    - _Bug_Condition: isBugCondition3_LowFPS(processingPipeline) where single thread is used_
    - _Expected_Behavior: Multi-threaded executor enabling parallel processing_
    - _Preservation: Executor cleanup on destroy unchanged_
    - _Requirements: 1.7, 2.7, 2.8_

  - [ ] 4.2 Keep inferenceExecutor single-threaded with documentation
    - File: `app/src/main/java/com/conjunctiva/segmentation/MainActivity.kt`
    - Keep `inferenceExecutor = Executors.newSingleThreadExecutor()` unchanged
    - Add comment above: "TFLite Interpreter is not thread-safe for concurrent inference - keep single thread"
    - This ensures model inference is serialized (one at a time)
    - _Preservation: Inference executor behavior unchanged_
    - _Requirements: 2.6, 3.1_

  - [ ] 4.3 Add synchronization flag for inference safety
    - File: `app/src/main/java/com/conjunctiva/segmentation/MainActivity.kt`
    - Add field: `private val isInferenceRunning = AtomicBoolean(false)`
    - In `startInferenceConsumer()`, before inference:
      - Check: `if (!isInferenceRunning.compareAndSet(false, true)) continue`
    - After inference completes (in finally block):
      - Reset: `isInferenceRunning.set(false)`
    - This prevents concurrent inference attempts on same model instance
    - _Requirements: 2.6, 2.8_

  - [ ] 4.4 Optimize frame queue management and logging
    - File: `app/src/main/java/com/conjunctiva/segmentation/MainActivity.kt`
    - Keep `ArrayBlockingQueue(1)` for latest-frame-only strategy
    - Add dropped frame counter: `private val droppedFrames = AtomicInteger(0)`
    - In `processImage()`, when frame is dropped:
      - Increment counter: `droppedFrames.incrementAndGet()`
      - Log periodically: `if (droppedFrames.get() % 10 == 0) Log.d(TAG, "Dropped ${droppedFrames.get()} frames")`
    - Add logging for queue size and performance metrics
    - _Requirements: 2.8, 2.10_

  - [ ] 4.5 Verify non-blocking processImage behavior
    - File: `app/src/main/java/com/conjunctiva/segmentation/MainActivity.kt`
    - Review `processImage()` implementation
    - Ensure `imageProxy.close()` is called immediately after bitmap conversion
    - Verify bitmap conversion doesn't block camera thread unnecessarily
    - Confirm frame queue offer/poll logic is correct
    - Add timing logs if needed for debugging
    - _Requirements: 2.6, 2.8_

  - [ ] 4.6 Add performance monitoring and logging
    - File: `app/src/main/java/com/conjunctiva/segmentation/MainActivity.kt`
    - Add detailed logging for debugging:
      - Log frame drop count every 10 frames
      - Log queue size (should always be 0 or 1)
      - Log thread pool utilization (active thread count)
      - Log camera FPS vs inference FPS separately
    - Add FPS calculation for camera frames (separate from inference FPS)
    - This helps monitor and tune performance
    - _Requirements: 2.9, 3.11, 3.12, 3.13_

- [ ] 5. Fix Failing Unit Tests

  - [ ] 5.1 Fix testResizeBitmap
    - File: `app/src/test/java/com/conjunctiva/segmentation/ImageUtilsTest.kt`
    - Current issue: Test expects both dimensions <= 320, but aspect ratio preservation may result in one dimension < 320
    - Fix approach:
      - Assert that `max(width, height) <= 320` (longest side is constrained)
      - Assert aspect ratio is preserved: `abs((width.toFloat() / height) - (640f / 480f)) < 0.01f`
      - Or assert specific expected dimensions: 640x480 → 320x240
    - Update test assertions to match actual implementation behavior
    - _Requirements: Testing infrastructure_

  - [ ] 5.2 Fix testCropCenterSquare
    - File: `app/src/test/java/com/conjunctiva/segmentation/ImageUtilsTest.kt`
    - Current issue: Test expects 480x480 but may get different result
    - Fix approach:
      - Verify actual implementation behavior by running test
      - Update assertion to match correct expected dimension
      - Ensure test bitmap is not recycled before assertion
      - Assert both width and height are equal (square output)
    - _Requirements: Testing infrastructure_

  - [ ] 5.3 Fix testCropCenterSquareAlreadySquare
    - File: `app/src/test/java/com/conjunctiva/segmentation/ImageUtilsTest.kt`
    - Current issue: May be creating new bitmap instead of returning same
    - Fix approach:
      - Assert dimensions are correct (500x500)
      - Consider asserting bitmap reference if implementation should return same instance
      - Or accept that new bitmap is created and assert dimensions only
    - _Requirements: Testing infrastructure_

- [ ] 6. Fix Deprecated API Warning

  - [ ] 6.1 Replace setTargetResolution with new CameraX API
    - File: `app/src/main/java/com/conjunctiva/segmentation/MainActivity.kt`
    - Line ~119: Locate `.setTargetResolution(android.util.Size(640, 480))`
    - Check CameraX version in `app/build.gradle.kts`
    - Replace with new API:
      ```kotlin
      .setResolutionSelector(
          ResolutionSelector.Builder()
              .setResolutionStrategy(
                  ResolutionStrategy(
                      android.util.Size(640, 480),
                      ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                  )
              )
              .build()
      )
      ```
    - Or use appropriate API based on CameraX version
    - Test that camera preview still works at 640x480
    - _Preservation: Camera resolution and preview behavior unchanged_
    - _Requirements: 3.7, 3.8_

---

## Phase 3: Verification

- [ ] 7. Verify bug condition exploration tests now fail (confirming fixes work)

  - [ ] 7.1 Re-run Bug 1 exploration test
    - **Property 1: Expected Behavior** - Bounding Box Removed
    - **IMPORTANT**: Re-run the SAME test from task 1 - do NOT write a new test
    - The test from task 1 checks if bounding box is drawn
    - Run test on FIXED code
    - **EXPECTED OUTCOME**: Test FAILS (confirms bounding box is removed)
    - If test still passes, the fix didn't work - debug and retry
    - _Requirements: 2.1, 2.2_

  - [ ] 7.2 Re-run Bug 2 exploration test
    - **Property 1: Expected Behavior** - Sophisticated Contour Detection
    - **IMPORTANT**: Re-run the SAME test from task 1 - do NOT write a new test
    - The test from task 1 checks polygon quality and OpenCV usage
    - Run test on FIXED code
    - **EXPECTED OUTCOME**: Test FAILS (confirms improved quality with OpenCV)
    - Verify quality score >= QUALITY_THRESHOLD
    - Verify OpenCV is initialized and used
    - If test still passes, the fix didn't work - debug and retry
    - _Requirements: 2.3, 2.4, 2.5_

  - [ ] 7.3 Re-run Bug 3 exploration test
    - **Property 1: Expected Behavior** - Parallel Inference Pipeline
    - **IMPORTANT**: Re-run the SAME test from task 1 - do NOT write a new test
    - The test from task 1 checks FPS and thread count
    - Run test on FIXED code
    - **EXPECTED OUTCOME**: Test FAILS (confirms improved FPS)
    - Verify FPS >= 8 (target 8-10 FPS)
    - Verify cameraExecutor thread count >= 2
    - Verify parallel processing is working
    - If test still passes, the fix didn't work - debug and retry
    - _Requirements: 2.6, 2.7, 2.8, 2.9, 2.10_

- [ ] 8. Write preservation property tests (verify no regressions)

  - [ ] 8.1 Write segmentation accuracy preservation test
    - **Property 2: Preservation** - Segmentation Accuracy Unchanged
    - **IMPORTANT**: This is a NEW test to verify preservation
    - Test that segmentation results are identical between original and fixed code:
      - Confidence scores match (within tolerance)
      - Bounding boxes match (within tolerance)
      - Mask area is similar (polygon area may differ slightly due to better contours)
    - Use property-based testing: generate random test images
    - Run inference on 100 test images, compare results
    - **EXPECTED OUTCOME**: Test PASSES (confirms no regression in segmentation accuracy)
    - _Requirements: 3.1, 3.2, 3.3_

  - [ ] 8.2 Write coordinate mapping preservation test
    - **Property 2: Preservation** - Coordinate Mapping Unchanged
    - **IMPORTANT**: This is a NEW test to verify preservation
    - Test that coordinate transformation produces same results:
      - Generate random image sizes and overlay dimensions
      - Verify `imgToViewX()` and `imgToViewY()` produce same results
      - Test fit-center scaling and offset calculations
    - Use property-based testing: generate many random input combinations
    - **EXPECTED OUTCOME**: Test PASSES (confirms coordinate mapping unchanged)
    - _Requirements: 3.4_

  - [ ] 8.3 Write rendering preservation test
    - **Property 2: Preservation** - Rendering Unchanged (except bounding box)
    - **IMPORTANT**: This is a NEW test to verify preservation
    - Test that polygon and label rendering is unchanged:
      - Verify polygon fill color is same (red semi-transparent)
      - Verify polygon border color is same (red solid)
      - Verify label text format is same ("Conjunctiva X%")
      - Verify label background is same (black semi-transparent)
    - **EXPECTED OUTCOME**: Test PASSES (confirms rendering unchanged)
    - _Requirements: 3.5, 3.6_

  - [ ] 8.4 Write camera functionality preservation test
    - **Property 2: Preservation** - Camera Functionality Unchanged
    - **IMPORTANT**: This is a NEW test to verify preservation
    - Test that camera initialization and lifecycle work correctly:
      - Verify back camera is selected by default
      - Verify preview resolution is 640x480
      - Verify executor cleanup on destroy works
      - Verify permission handling works
    - **EXPECTED OUTCOME**: Test PASSES (confirms camera functionality unchanged)
    - _Requirements: 3.7, 3.8, 3.9, 3.10_

  - [ ] 8.5 Write info panel preservation test
    - **Property 2: Preservation** - Info Panel Unchanged
    - **IMPORTANT**: This is a NEW test to verify preservation
    - Test that info panel displays correct information:
      - Verify inference time is displayed in milliseconds
      - Verify detection count is displayed correctly
      - Verify FPS calculation uses same formula (updated every 1 second)
    - **EXPECTED OUTCOME**: Test PASSES (confirms info panel unchanged)
    - _Requirements: 3.11, 3.12, 3.13_

---

## Phase 4: Integration Testing

- [ ] 9. Integration testing and validation

  - [ ] 9.1 Test full pipeline end-to-end
    - Run application on physical Android device
    - Test camera → frame → inference → rendering pipeline
    - Verify no crashes or errors in logcat
    - Verify smooth operation over 5 minute run
    - Check for memory leaks (monitor memory usage)
    - _Requirements: All requirements_

  - [ ] 9.2 Measure and verify FPS improvement
    - Run application for 30 seconds
    - Record FPS measurements from info panel
    - Verify FPS is consistently 8-10 (target range)
    - Compare with baseline 2 FPS (should be 4-5x improvement)
    - Log detailed performance metrics
    - _Requirements: 2.6, 2.7, 2.8, 2.9, 2.10_

  - [ ] 9.3 Visual inspection of polygon quality
    - Capture screenshots of segmentation results
    - Verify bounding box is NOT visible (only polygon)
    - Verify polygon contours are smooth and accurate
    - Compare with original simple edge detection (should be significantly better)
    - Test on various conjunctiva images (different sizes, shapes, lighting)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

  - [ ] 9.4 Test error handling and fallback
    - Test graceful fallback when OpenCV fails (simulate by corrupting OpenCV lib)
    - Verify application doesn't crash
    - Verify fallback to bounding box polygon works
    - Test with invalid masks (empty, all zeros, all ones)
    - _Requirements: 2.3, 2.4, 2.5_

  - [ ] 9.5 Test device compatibility
    - Test on multiple Android devices (different manufacturers, API levels)
    - Verify works on Android API 21+ (minimum supported version)
    - Test on devices with different CPU cores (2, 4, 8 cores)
    - Verify thread pool scales appropriately
    - _Requirements: All requirements_

  - [ ] 9.6 Verify all unit tests pass
    - Run full test suite: `./gradlew test`
    - Verify all ImageUtils tests pass (3 tests fixed)
    - Verify all new property-based tests pass
    - Verify no test regressions
    - _Requirements: Testing infrastructure_

---

## Phase 5: Checkpoint

- [ ] 10. Final checkpoint - Ensure all tests pass and requirements met
  - Verify all bug condition exploration tests now FAIL (confirming fixes work)
  - Verify all preservation tests PASS (confirming no regressions)
  - Verify all unit tests pass
  - Verify integration tests pass
  - Verify FPS improvement achieved (8-10 FPS)
  - Verify visual quality improved (smooth polygons, no bounding box)
  - Review logcat for any warnings or errors
  - Ask user if any questions or issues arise
  - Mark spec as complete if all requirements validated

---

## Notes

### Bug Condition Methodology

This task list follows the bug condition methodology:
- **C(X)**: Bug Condition - identifies inputs that trigger the bug
- **P(result)**: Property - desired behavior for buggy inputs
- **¬C(X)**: Non-buggy inputs that should be preserved
- **F**: Original (unfixed) function
- **F'**: Fixed function

### Task Ordering (CRITICAL)

1. **Bug Condition Exploration** (Task 1) - Write tests that PASS on unfixed code, confirming bugs exist
2. **Implementation** (Tasks 2-6) - Apply fixes to resolve bugs
3. **Verification** (Task 7) - Re-run exploration tests, should now FAIL (confirming fixes work)
4. **Preservation** (Task 8) - Write NEW tests to verify no regressions, should PASS
5. **Integration** (Task 9) - End-to-end testing and validation
6. **Checkpoint** (Task 10) - Final verification

### Property-Based Testing

Property-based testing is used for:
- **Exploration**: Generate test cases that demonstrate bugs exist
- **Preservation**: Generate many test cases to verify behavior unchanged across input domain
- **Stronger guarantees**: Catches edge cases that manual tests might miss

### Zero-Day Bug Philosophy

All bugs must be fixed immediately with maximum quality:
- No shortcuts or workarounds
- Comprehensive testing at all levels
- Sophisticated solutions (OpenCV, not simple algorithms)
- Fix all related issues (unit tests, deprecated APIs)
