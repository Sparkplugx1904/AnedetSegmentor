# Installation Instructions

## ✅ Build Status: SUCCESS

The app has been successfully built and is ready for installation!

**APK Location:** `app/build/outputs/apk/debug/app-debug.apk`

## Issues Fixed

1. ✅ Removed old `app/build.gradle` (Groovy) file - was conflicting with Kotlin DSL
2. ✅ Removed KAPT plugin - not needed and causing Java module access errors
3. ✅ All compilation errors resolved
4. ✅ Runtime ClassNotFoundException fixed

## Installation Methods

### Method 1: Using ADB (Recommended)

#### Step 1: Connect Your Device
```bash
# Enable USB debugging on your Android device:
# Settings > Developer Options > USB Debugging

# Connect via USB cable and verify connection
adb devices
```

You should see:
```
List of devices attached
a9cb16be        device
```

If you see `offline` or `unauthorized`:
- Unplug and replug the USB cable
- On your phone, tap "Allow" when prompted for USB debugging
- Run `adb devices` again

#### Step 2: Install the App
```bash
# Install (or reinstall if already installed)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected output:
```
Performing Streamed Install
Success
```

#### Step 3: Launch and Monitor
```bash
# Launch the app
adb shell am start -n com.conjunctiva.segmentation/.MainActivity

# Monitor logs in real-time
adb logcat | grep "ConjunctivaSegmentor"
```

### Method 2: Manual Installation

1. Copy `app/build/outputs/apk/debug/app-debug.apk` to your phone
2. Open the APK file on your phone
3. Tap "Install" (you may need to enable "Install from Unknown Sources")

### Method 3: Using Gradle (When Device is Connected)

```bash
# This will build and install in one command
./gradlew installDebug
```

## Troubleshooting Device Connection

### Device Shows as "offline"
```bash
# Restart ADB server
adb kill-server
adb start-server
adb devices
```

### Device Not Detected
```bash
# Check if device is detected by Windows
adb devices

# If not detected:
# 1. Try a different USB cable (some cables are charge-only)
# 2. Try a different USB port
# 3. Install device-specific USB drivers
# 4. Enable "File Transfer" mode on your phone (not just "Charging")
```

### "Unauthorized" Device
```bash
# Revoke USB debugging authorizations on your phone:
# Settings > Developer Options > Revoke USB debugging authorizations

# Then reconnect and approve the prompt on your phone
adb devices
```

## Expected App Behavior

### On First Launch
1. App will request camera permission - tap "Allow"
2. Camera preview should appear
3. Check logs for initialization:

```bash
adb logcat | grep "ConjunctivaSegmentor"
```

### Success Logs
```
D/ConjunctivaSegmentor: GPU Acceleration enabled successfully.
D/ConjunctivaSegmentor: Model isQuantized=true, DataType=INT8
D/ConjunctivaSegmentor: Init complete: 320x320, detections=300, features=38
```

### CPU Fallback (if GPU unavailable)
```
W/ConjunctivaSegmentor: GPU Acceleration not available: [reason]. Using CPU with XNNPACK.
D/ConjunctivaSegmentor: Model isQuantized=true, DataType=INT8
D/ConjunctivaSegmentor: Init complete: 320x320, detections=300, features=38
```

## Uninstall Previous Version

If you had an old version installed with a different package name:

```bash
# Uninstall old version (if exists)
adb uninstall com.madyapadma.anedetsegmentor

# Uninstall current version
adb uninstall com.conjunctiva.segmentation
```

## Build Commands Reference

```bash
# Clean build
./gradlew clean

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Build and install debug
./gradlew installDebug

# Build everything (debug + release)
./gradlew build

# Run tests
./gradlew test

# Run instrumented tests (requires device)
./gradlew connectedAndroidTest
```

## APK Information

- **Package Name:** `com.conjunctiva.segmentation`
- **Version Code:** 1
- **Version Name:** 1.0
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34 (Android 14)
- **APK Size:** ~15-20 MB (includes TensorFlow Lite models)

## Next Steps After Installation

1. **Grant Permissions**
   - Camera permission is required
   - Grant when prompted or via Settings > Apps > Conjunctiva Segmentation > Permissions

2. **Test Basic Functionality**
   - Open the app
   - Point camera at an eye
   - Verify segmentation overlay appears

3. **Monitor Performance**
   ```bash
   # Watch for any errors
   adb logcat *:E | grep conjunctiva
   
   # Monitor memory usage
   adb shell dumpsys meminfo com.conjunctiva.segmentation
   ```

4. **Report Issues**
   - If app crashes, capture logs: `adb logcat > crash_log.txt`
   - Check for ClassNotFoundException or other errors
   - Verify TFLite model files are included in APK

## Wireless ADB (Optional)

If you want to test without USB cable:

```bash
# Connect device via USB first
adb tcpip 5555

# Find device IP address (on phone: Settings > About > Status > IP address)
# Then connect wirelessly
adb connect 192.168.1.XXX:5555

# Verify connection
adb devices

# Now you can unplug USB cable
```

## Common Issues

### "INSTALL_FAILED_UPDATE_INCOMPATIBLE"
```bash
# Uninstall old version first
adb uninstall com.conjunctiva.segmentation
adb install app/build/outputs/apk/debug/app-debug.apk
```

### "INSTALL_FAILED_INSUFFICIENT_STORAGE"
```bash
# Free up space on device or install to SD card
adb install -s app/build/outputs/apk/debug/app-debug.apk
```

### App Crashes Immediately
```bash
# Check crash logs
adb logcat | grep "AndroidRuntime"

# Look for:
# - ClassNotFoundException
# - FileNotFoundException (missing model files)
# - Permission errors
```

## Success Checklist

- [ ] APK built successfully
- [ ] Device connected and authorized
- [ ] APK installed without errors
- [ ] App launches without crashes
- [ ] Camera permission granted
- [ ] Camera preview visible
- [ ] No ClassNotFoundException in logs
- [ ] TensorFlow Lite initialized successfully
- [ ] GPU or CPU acceleration working

---

**Current Status:** APK is ready at `app/build/outputs/apk/debug/app-debug.apk`

Connect your device and run:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
