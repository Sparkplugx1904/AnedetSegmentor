# 🔨 Build Instructions

Panduan lengkap untuk build aplikasi Conjunctiva Segmentation dari source code.

## 📋 Prerequisites

### 1. Install Android Studio

Download dan install Android Studio Hedgehog (2023.1.1) atau lebih baru:
- **Windows**: https://developer.android.com/studio
- **Mac**: https://developer.android.com/studio
- **Linux**: https://developer.android.com/studio

### 2. Install JDK 17

Android Studio biasanya sudah include JDK, tapi jika perlu install manual:
- **Windows**: Download dari Oracle atau OpenJDK
- **Mac**: `brew install openjdk@17`
- **Linux**: `sudo apt install openjdk-17-jdk`

Verifikasi:
```bash
java -version
# Should show: openjdk version "17.x.x"
```

### 3. Setup Android SDK

Di Android Studio:
1. Tools > SDK Manager
2. Install:
   - Android SDK Platform 34 (Android 14)
   - Android SDK Build-Tools 34.0.0
   - Android SDK Platform-Tools
   - Android SDK Command-line Tools

### 4. Setup Environment Variables (Optional)

**Windows**:
```cmd
setx ANDROID_HOME "C:\Users\YourUsername\AppData\Local\Android\Sdk"
setx PATH "%PATH%;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\tools"
```

**Mac/Linux**:
```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/tools
```

## 🚀 Build Steps

### Method 1: Android Studio (Recommended)

#### Step 1: Open Project
```
1. Launch Android Studio
2. File > Open
3. Navigate to project folder
4. Click OK
```

#### Step 2: Sync Gradle
```
1. Wait for "Gradle sync" to start automatically
2. Or: File > Sync Project with Gradle Files
3. Wait for dependencies to download (~5-10 minutes first time)
```

#### Step 3: Verify Model File
```
Check that model exists:
app/src/main/assets/best_float16.tflite
```

If not, copy it:
```bash
mkdir -p app/src/main/assets
cp models/best_float16.tflite app/src/main/assets/
```

#### Step 4: Build
```
1. Build > Make Project (Ctrl+F9)
2. Wait for build to complete
3. Check "Build" tab for any errors
```

#### Step 5: Run
```
1. Connect Android device via USB (or start emulator)
2. Run > Run 'app' (Shift+F10)
3. Select target device
4. Wait for installation and launch
```

### Method 2: Command Line

#### Step 1: Navigate to Project
```bash
cd /path/to/ConjunctivaSegmentation
```

#### Step 2: Make Gradlew Executable (Mac/Linux only)
```bash
chmod +x gradlew
```

#### Step 3: Build Debug APK
```bash
# Windows
gradlew.bat assembleDebug

# Mac/Linux
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

#### Step 4: Install to Device
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### Step 5: Launch App
```bash
adb shell am start -n com.conjunctiva.segmentation/.MainActivity
```

## 🏗️ Build Variants

### Debug Build
```bash
./gradlew assembleDebug
```
- Includes debug symbols
- Not optimized
- Larger APK size (~15-20MB)
- Faster build time

### Release Build
```bash
./gradlew assembleRelease
```
- Optimized with ProGuard
- Smaller APK size (~10-15MB)
- Requires signing key
- Slower build time

## 🔑 Signing Release APK

### Step 1: Generate Keystore
```bash
keytool -genkey -v -keystore my-release-key.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias my-key-alias
```

Follow prompts to set password and details.

### Step 2: Configure Signing

Create `keystore.properties` in project root:
```properties
storePassword=your-store-password
keyPassword=your-key-password
keyAlias=my-key-alias
storeFile=../my-release-key.jks
```

Add to `.gitignore`:
```bash
echo "keystore.properties" >> .gitignore
echo "*.jks" >> .gitignore
```

### Step 3: Update app/build.gradle

Add before `android` block:
```gradle
def keystorePropertiesFile = rootProject.file("keystore.properties")
def keystoreProperties = new Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(new FileInputStream(keystorePropertiesFile))
}
```

Add inside `android` block:
```gradle
signingConfigs {
    release {
        if (keystorePropertiesFile.exists()) {
            keyAlias keystoreProperties['keyAlias']
            keyPassword keystoreProperties['keyPassword']
            storeFile file(keystoreProperties['storeFile'])
            storePassword keystoreProperties['storePassword']
        }
    }
}

buildTypes {
    release {
        signingConfig signingConfigs.release
        minifyEnabled true
        proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
    }
}
```

### Step 4: Build Signed Release
```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

## 🧹 Clean Build

If you encounter build issues:

```bash
# Clean build artifacts
./gradlew clean

# Clean and rebuild
./gradlew clean assembleDebug

# Invalidate caches (Android Studio)
File > Invalidate Caches / Restart
```

## 🐛 Troubleshooting

### Error: "SDK location not found"

Create `local.properties`:
```properties
sdk.dir=C:\\Users\\YourUsername\\AppData\\Local\\Android\\Sdk
```

### Error: "Gradle sync failed"

1. Check internet connection
2. Try: File > Invalidate Caches / Restart
3. Delete `.gradle` folder and sync again

### Error: "Model file not found"

```bash
# Verify model exists
ls app/src/main/assets/best_float16.tflite

# If not, copy it
mkdir -p app/src/main/assets
cp models/best_float16.tflite app/src/main/assets/
```

### Error: "Execution failed for task ':app:mergeDebugResources'"

```bash
# Clean and rebuild
./gradlew clean
./gradlew assembleDebug
```

### Error: "AAPT: error: resource android:attr/lStar not found"

Update `compileSdk` in `app/build.gradle`:
```gradle
android {
    compileSdk 34  // Make sure this is 34
}
```

### Build is Very Slow

1. Enable Gradle daemon:
   ```properties
   # gradle.properties
   org.gradle.daemon=true
   org.gradle.parallel=true
   org.gradle.caching=true
   ```

2. Increase heap size:
   ```properties
   # gradle.properties
   org.gradle.jvmargs=-Xmx4096m -XX:MaxPermSize=1024m
   ```

3. Use offline mode (after first successful build):
   ```bash
   ./gradlew assembleDebug --offline
   ```

## 📊 Build Output

### Debug APK Location
```
app/build/outputs/apk/debug/app-debug.apk
```

### Release APK Location
```
app/build/outputs/apk/release/app-release.apk
```

### Build Reports
```
app/build/reports/
├── lint-results.html          # Lint report
├── tests/                     # Test reports
└── androidTests/              # Instrumented test reports
```

## 🧪 Build with Tests

### Run Unit Tests
```bash
./gradlew test
```

### Run Instrumented Tests
```bash
./gradlew connectedAndroidTest
```

### Build with All Tests
```bash
./gradlew build
```

This will:
1. Compile code
2. Run unit tests
3. Run lint checks
4. Generate APK

## 📦 Build Optimization

### Reduce APK Size

1. **Enable ProGuard** (already configured):
   ```gradle
   buildTypes {
       release {
           minifyEnabled true
           shrinkResources true
       }
   }
   ```

2. **Use APK Splits**:
   ```gradle
   android {
       splits {
           abi {
               enable true
               reset()
               include 'armeabi-v7a', 'arm64-v8a'
           }
       }
   }
   ```

3. **Use Android App Bundle**:
   ```bash
   ./gradlew bundleRelease
   ```
   Output: `app/build/outputs/bundle/release/app-release.aab`

### Speed Up Build

1. **Use Build Cache**:
   ```properties
   # gradle.properties
   org.gradle.caching=true
   android.enableBuildCache=true
   ```

2. **Parallel Execution**:
   ```properties
   # gradle.properties
   org.gradle.parallel=true
   ```

3. **Configuration on Demand**:
   ```properties
   # gradle.properties
   org.gradle.configureondemand=true
   ```

## 🔍 Verify Build

### Check APK Contents
```bash
# List files in APK
unzip -l app/build/outputs/apk/debug/app-debug.apk

# Verify model is included
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep tflite
```

### Check APK Size
```bash
# Windows
dir app\build\outputs\apk\debug\app-debug.apk

# Mac/Linux
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

### Analyze APK
```bash
# Android Studio
Build > Analyze APK
# Select app-debug.apk or app-release.apk
```

This shows:
- APK size breakdown
- Method count
- Resource sizes
- DEX files

## 📱 Install Methods

### Method 1: ADB
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Method 2: Android Studio
```
Run > Run 'app'
```

### Method 3: Manual Transfer
```
1. Copy APK to device
2. Open file manager on device
3. Tap APK file
4. Allow "Install from unknown sources"
5. Install
```

## 🚀 Continuous Integration

### GitHub Actions Example

Create `.github/workflows/android.yml`:
```yaml
name: Android CI

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'
    
    - name: Grant execute permission for gradlew
      run: chmod +x gradlew
    
    - name: Build with Gradle
      run: ./gradlew assembleDebug
    
    - name: Run tests
      run: ./gradlew test
    
    - name: Upload APK
      uses: actions/upload-artifact@v3
      with:
        name: app-debug
        path: app/build/outputs/apk/debug/app-debug.apk
```

## 📝 Build Checklist

Before releasing:
- [ ] All tests pass
- [ ] Lint checks pass
- [ ] ProGuard rules configured
- [ ] APK signed with release key
- [ ] APK size reasonable (< 20MB)
- [ ] Model file included
- [ ] Tested on multiple devices
- [ ] Version code incremented
- [ ] Version name updated
- [ ] Release notes prepared

## 🎓 Additional Resources

- [Android Build Configuration](https://developer.android.com/studio/build)
- [Gradle User Guide](https://docs.gradle.org/current/userguide/userguide.html)
- [ProGuard Manual](https://www.guardsquare.com/manual/home)
- [App Signing](https://developer.android.com/studio/publish/app-signing)

---

**Happy Building! 🔨**
