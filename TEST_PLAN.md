# 🧪 Test Plan - Conjunctiva Segmentation App

## 📋 Overview

Dokumen ini berisi rencana testing untuk memastikan aplikasi berjalan dengan baik di berbagai kondisi.

## 🎯 Test Objectives

1. **Functional Testing**: Memastikan semua fitur bekerja
2. **Performance Testing**: Memastikan app responsive dan cepat
3. **Compatibility Testing**: Test di berbagai device dan Android versions
4. **Usability Testing**: Memastikan UX yang baik
5. **Stress Testing**: Test stabilitas dalam penggunaan lama

## 🔬 Test Cases

### 1. Model Loading Tests

| Test ID | Test Case | Expected Result | Status |
|---------|-----------|-----------------|--------|
| ML-01 | Load model on app start | Model loads successfully | ⬜ |
| ML-02 | Load model with corrupted file | Show error message | ⬜ |
| ML-03 | Load model on low memory device | Graceful degradation | ⬜ |
| ML-04 | Check model input/output shapes | Correct dimensions | ⬜ |

### 2. Camera Tests

| Test ID | Test Case | Expected Result | Status |
|---------|-----------|-----------------|--------|
| CAM-01 | Request camera permission | Permission dialog shows | ⬜ |
| CAM-02 | Grant camera permission | Camera preview starts | ⬜ |
| CAM-03 | Deny camera permission | App shows error and exits | ⬜ |
| CAM-04 | Switch camera (front/back) | Camera switches smoothly | ⬜ |
| CAM-05 | Rotate device | Preview rotates correctly | ⬜ |
| CAM-06 | Cover camera lens | App continues running | ⬜ |

### 3. Inference Tests

| Test ID | Test Case | Expected Result | Status |
|---------|-----------|-----------------|--------|
| INF-01 | Run inference on valid image | Results returned | ⬜ |
| INF-02 | Run inference on blank image | No detections or empty results | ⬜ |
| INF-03 | Run inference 100 times | No memory leak | ⬜ |
| INF-04 | Measure inference time | < 100ms on mid-range device | ⬜ |
| INF-05 | Test with different image sizes | All sizes work | ⬜ |
| INF-06 | Test confidence threshold | Only high-confidence results | ⬜ |

### 4. UI/UX Tests

| Test ID | Test Case | Expected Result | Status |
|---------|-----------|-----------------|--------|
| UI-01 | Launch app | Splash screen → Camera | ⬜ |
| UI-02 | View info panel | FPS, inference time visible | ⬜ |
| UI-03 | View overlay | Masking renders correctly | ⬜ |
| UI-04 | Tap on detection | (Future: show details) | ⬜ |
| UI-05 | App in background | Camera stops | ⬜ |
| UI-06 | Return to foreground | Camera resumes | ⬜ |

### 5. Performance Tests

| Test ID | Test Case | Expected Result | Status |
|---------|-----------|-----------------|--------|
| PERF-01 | Measure FPS | > 15 FPS | ⬜ |
| PERF-02 | Measure inference time | < 100ms | ⬜ |
| PERF-03 | Measure memory usage | < 200MB | ⬜ |
| PERF-04 | Measure battery drain | < 10% per 10 minutes | ⬜ |
| PERF-05 | Test thermal throttling | No crash after 30 min | ⬜ |
| PERF-06 | Test on low-end device | Acceptable performance | ⬜ |

### 6. Accuracy Tests

| Test ID | Test Case | Expected Result | Status |
|---------|-----------|-----------------|--------|
| ACC-01 | Detect normal conjunctiva | Correct detection | ⬜ |
| ACC-02 | Detect abnormal conjunctiva | Correct detection | ⬜ |
| ACC-03 | No conjunctiva in frame | No false positives | ⬜ |
| ACC-04 | Multiple eyes in frame | Detect all | ⬜ |
| ACC-05 | Poor lighting | Reasonable performance | ⬜ |
| ACC-06 | Extreme close-up | Still detects | ⬜ |

### 7. Edge Cases

| Test ID | Test Case | Expected Result | Status |
|---------|-----------|-----------------|--------|
| EDGE-01 | No internet connection | App works offline | ⬜ |
| EDGE-02 | Low storage space | App continues running | ⬜ |
| EDGE-03 | Incoming call during use | App pauses gracefully | ⬜ |
| EDGE-04 | Lock screen during use | App stops camera | ⬜ |
| EDGE-05 | Force stop app | No data corruption | ⬜ |
| EDGE-06 | Uninstall and reinstall | Fresh start works | ⬜ |

### 8. Compatibility Tests

| Test ID | Device Type | Android Version | Expected Result | Status |
|---------|-------------|-----------------|-----------------|--------|
| COMP-01 | Flagship (SD 8 Gen 2) | Android 14 | Excellent performance | ⬜ |
| COMP-02 | Mid-range (SD 778G) | Android 13 | Good performance | ⬜ |
| COMP-03 | Budget (SD 680) | Android 12 | Acceptable performance | ⬜ |
| COMP-04 | Old device | Android 10 | Basic functionality | ⬜ |
| COMP-05 | Tablet | Android 13 | Works correctly | ⬜ |
| COMP-06 | Foldable | Android 14 | Adapts to screen | ⬜ |

## 🛠️ Testing Tools

### Automated Testing

```bash
# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest

# Run specific test
./gradlew test --tests ModelTest.testInferenceTime
```

### Manual Testing

1. **Android Studio Profiler**
   - CPU usage
   - Memory usage
   - Network (should be 0)
   - Energy consumption

2. **Logcat Monitoring**
   ```bash
   adb logcat | grep -E "Conjunctiva|TFLite|Camera"
   ```

3. **Performance Monitoring**
   ```bash
   # FPS
   adb shell dumpsys gfxinfo com.conjunctiva.segmentation
   
   # Memory
   adb shell dumpsys meminfo com.conjunctiva.segmentation
   ```

### Stress Testing

```bash
# Monkey test (random UI interactions)
adb shell monkey -p com.conjunctiva.segmentation -v 1000

# Long-running test
adb shell am start -n com.conjunctiva.segmentation/.MainActivity
# Let it run for 1 hour, monitor for crashes
```

## 📊 Performance Benchmarks

### Target Metrics

| Metric | Flagship | Mid-range | Budget |
|--------|----------|-----------|--------|
| FPS | 25-30 | 15-25 | 10-15 |
| Inference Time | 20-40ms | 40-80ms | 80-150ms |
| Memory Usage | < 180MB | < 200MB | < 250MB |
| Battery Drain | < 8%/10min | < 10%/10min | < 15%/10min |
| Cold Start | < 2s | < 3s | < 5s |

### Measurement Commands

```bash
# Measure cold start time
adb shell am start -W com.conjunctiva.segmentation/.MainActivity

# Measure memory
adb shell dumpsys meminfo com.conjunctiva.segmentation | grep TOTAL

# Measure battery
adb shell dumpsys batterystats --reset
# Use app for 10 minutes
adb shell dumpsys batterystats | grep com.conjunctiva.segmentation
```

## 🐛 Bug Reporting Template

```markdown
### Bug Report

**Title**: [Short description]

**Severity**: Critical / High / Medium / Low

**Device**: [Model, Android version]

**Steps to Reproduce**:
1. Step 1
2. Step 2
3. Step 3

**Expected Behavior**:
[What should happen]

**Actual Behavior**:
[What actually happens]

**Logs**:
```
[Paste logcat output]
```

**Screenshots**:
[Attach if applicable]

**Workaround**:
[If any]
```

## ✅ Test Execution Checklist

### Pre-Testing
- [ ] Build succeeds without errors
- [ ] Model file exists and is valid
- [ ] Test devices are ready
- [ ] Logcat is running

### During Testing
- [ ] Document all issues found
- [ ] Take screenshots of bugs
- [ ] Save logcat output
- [ ] Note device-specific behaviors

### Post-Testing
- [ ] All critical bugs fixed
- [ ] Performance meets targets
- [ ] Regression testing passed
- [ ] Documentation updated

## 📈 Test Coverage Goals

- **Unit Tests**: > 70% code coverage
- **Integration Tests**: All critical paths
- **UI Tests**: All user flows
- **Manual Tests**: All test cases executed

## 🔄 Regression Testing

After any code change, re-run:
1. All automated tests
2. Critical path manual tests
3. Performance benchmarks
4. Device compatibility tests

## 📝 Test Report Template

```markdown
# Test Report - [Date]

## Summary
- Total Tests: X
- Passed: Y
- Failed: Z
- Blocked: W

## Performance
- Average FPS: X
- Average Inference Time: Y ms
- Memory Usage: Z MB

## Issues Found
1. [Issue 1]
2. [Issue 2]

## Recommendations
1. [Recommendation 1]
2. [Recommendation 2]

## Sign-off
Tested by: [Name]
Date: [Date]
Status: Pass / Fail
```

## 🎓 Testing Best Practices

1. **Test Early, Test Often**: Don't wait until the end
2. **Automate When Possible**: Save time with automated tests
3. **Test on Real Devices**: Emulators are not enough
4. **Document Everything**: Keep detailed test logs
5. **Prioritize Critical Paths**: Test most important features first
6. **Test Edge Cases**: Don't just test happy paths
7. **Monitor Performance**: Always check FPS, memory, battery
8. **User Perspective**: Test as if you're the end user

---

**Happy Testing! 🧪**
