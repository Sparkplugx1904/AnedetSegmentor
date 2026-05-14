# Testing Guide

## Quick Start

### 1. Connect Your Device
```bash
# Check if device is connected
adb devices

# If no devices shown, enable USB debugging on your Android device:
# Settings > Developer Options > USB Debugging
```

### 2. Install the App
```bash
# Install debug version
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Or rebuild and install in one command
./gradlew installDebug
```

### 3. Monitor Logs
```bash
# Watch app logs in real-time
adb logcat | grep "ConjunctivaSegmentor"

# Or filter by package name
adb logcat | grep "com.conjunctiva.segmentation"

# Clear logs first for cleaner output
adb logcat -c && adb logcat | grep "ConjunctivaSegmentor"
```

## Expected Log Output

### Successful GPU Initialization
```
D/ConjunctivaSegmentor: GPU Acceleration enabled successfully.
D/ConjunctivaSegmentor: Model isQuantized=true, DataType=INT8
D/ConjunctivaSegmentor: Init complete: 320x320, detections=300, features=38
```

### CPU Fallback (if GPU not available)
```
W/ConjunctivaSegmentor: GPU Acceleration not available: [error message]. Using CPU with XNNPACK.
D/ConjunctivaSegmentor: Model isQuantized=true, DataType=INT8
D/ConjunctivaSegmentor: Init complete: 320x320, detections=300, features=38
```

## Troubleshooting

### App Crashes on Launch

#### Check for ClassNotFoundException
```bash
adb logcat | grep "ClassNotFoundException"
```

If you see `GpuDelegateFactory$Options` error, the fix wasn't applied correctly. Rebuild:
```bash
./gradlew clean assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

#### Check for Model File Issues
```bash
adb logcat | grep "FileNotFoundException"
```

Ensure `yolo26n-seg_best_int8.tflite` exists in `app/src/main/assets/`

### Camera Permission Issues
If the app crashes when accessing camera:
1. Grant camera permission manually: Settings > Apps > Conjunctiva Segmentation > Permissions > Camera
2. Or check logs: `adb logcat | grep "Permission"`

### Performance Issues

#### Check if GPU is Being Used
```bash
adb logcat | grep "GPU"
```

#### Monitor Memory Usage
```bash
adb shell dumpsys meminfo com.conjunctiva.segmentation
```

#### Check CPU Usage
```bash
adb shell top | grep conjunctiva
```

## Build Variants

### Debug Build (Current)
```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Release Build (Optimized)
```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release-unsigned.apk
```

### Install Specific Variant
```bash
# Install debug
./gradlew installDebug

# Install release (requires signing)
./gradlew installRelease
```

## Uninstall App
```bash
adb uninstall com.conjunctiva.segmentation
```

## Clean Build
If you encounter persistent issues:
```bash
# Clean all build artifacts
./gradlew clean

# Clean and rebuild
./gradlew clean assembleDebug

# Nuclear option: delete build directories
rm -rf app/build build .gradle
./gradlew assembleDebug
```

## Verify APK Contents
```bash
# List files in APK
unzip -l app/build/outputs/apk/debug/app-debug.apk

# Check if TFLite model is included
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep tflite

# Check native libraries
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "\.so$"
```

## Device Requirements

- **Minimum SDK**: Android 8.0 (API 26)
- **Target SDK**: Android 14 (API 34)
- **Camera**: Required
- **GPU**: Optional (will use CPU if not available)
- **RAM**: Recommended 2GB+ for smooth operation

## Testing Checklist

- [ ] App launches without crashes
- [ ] Camera permission is granted
- [ ] Camera preview is visible
- [ ] Model loads successfully (check logs)
- [ ] GPU or CPU acceleration is initialized
- [ ] Segmentation runs on camera frames
- [ ] Results are displayed on screen
- [ ] No memory leaks during extended use
- [ ] App handles rotation correctly
- [ ] App handles background/foreground transitions

## Common Log Filters

```bash
# All app logs
adb logcat -s "ConjunctivaSegmentor:*" "MainActivity:*"

# Errors only
adb logcat *:E | grep conjunctiva

# TensorFlow Lite logs
adb logcat | grep "TfLite"

# GPU delegate logs
adb logcat | grep "GpuDelegate"

# Camera logs
adb logcat | grep "Camera"
```

## Performance Profiling

### Using Android Studio Profiler
1. Open Android Studio
2. Run > Profile 'app'
3. Select device and launch
4. Monitor CPU, Memory, and Network usage

### Command Line Profiling
```bash
# CPU profiling
adb shell am profile start com.conjunctiva.segmentation /sdcard/profile.trace
# Use the app for a while
adb shell am profile stop com.conjunctiva.segmentation
adb pull /sdcard/profile.trace

# Memory dump
adb shell am dumpheap com.conjunctiva.segmentation /sdcard/heap.hprof
adb pull /sdcard/heap.hprof
```

## Next Steps

After successful installation and testing:
1. Test with different lighting conditions
2. Test with different eye images
3. Measure inference time (check logs for timing info)
4. Compare GPU vs CPU performance
5. Test on different devices if available
