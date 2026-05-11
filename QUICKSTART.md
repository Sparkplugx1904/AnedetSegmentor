# 🚀 Quick Start Guide

Panduan cepat untuk menjalankan aplikasi Conjunctiva Segmentation.

## ✅ Prerequisites

1. **Android Studio** (Hedgehog 2023.1.1 atau lebih baru)
2. **JDK 17** atau lebih tinggi
3. **Android Device** dengan kamera (atau emulator dengan webcam)
4. **Model TFLite**: `models/best_float16.tflite` ✓ (sudah ada)

## 📱 Langkah-langkah

### 1. Buka Project di Android Studio

```bash
# Buka Android Studio
File > Open > Pilih folder project ini
```

### 2. Sync Gradle

Android Studio akan otomatis sync Gradle. Jika tidak:
```
File > Sync Project with Gradle Files
```

Tunggu sampai selesai download dependencies (~5-10 menit pertama kali).

### 3. Connect Device atau Setup Emulator

**Opsi A: Real Device (Recommended)**
- Enable Developer Options di device
- Enable USB Debugging
- Connect via USB
- Device akan muncul di toolbar Android Studio

**Opsi B: Emulator**
- Tools > Device Manager
- Create Virtual Device
- Pilih device dengan Play Store
- System Image: API 30 atau lebih tinggi
- Enable webcam di Advanced Settings

### 4. Run Application

```
Run > Run 'app'
atau tekan Shift + F10
```

Aplikasi akan:
1. Build (~2-3 menit pertama kali)
2. Install ke device
3. Launch otomatis
4. Minta permission kamera → **Izinkan**

### 5. Test Segmentasi

- Arahkan kamera ke mata (konjungtiva)
- Overlay merah akan muncul jika terdeteksi
- Lihat info panel di kiri atas:
  - FPS: Frame per second
  - Inference: Waktu proses (ms)
  - Detections: Jumlah deteksi

## 🎯 Expected Results

### Performance Metrics

| Device Type | FPS | Inference Time |
|------------|-----|----------------|
| Flagship (SD 8 series) | 25-30 | 20-40ms |
| Mid-range (SD 7 series) | 15-25 | 40-80ms |
| Budget (SD 6 series) | 10-15 | 80-150ms |

### Visual Output

- **Merah semi-transparan**: Area konjungtiva terdeteksi
- **Garis hijau**: Bounding box
- **Label putih**: "Conjunctiva XX%" (confidence)

## 🐛 Troubleshooting

### Build Error: "SDK not found"

```bash
# Buat file local.properties
echo "sdk.dir=C:\\Users\\YourUsername\\AppData\\Local\\Android\\Sdk" > local.properties
```

Ganti path sesuai lokasi Android SDK Anda.

### Error: "Model not found"

Pastikan file ada:
```bash
ls app/src/main/assets/best_float16.tflite
```

Jika tidak ada:
```bash
mkdir -p app/src/main/assets
cp models/best_float16.tflite app/src/main/assets/
```

### Aplikasi Crash saat Launch

Check logcat:
```bash
adb logcat | grep "ConjunctivaSegmentor"
```

Common issues:
- Model file corrupt → Re-copy model
- Out of memory → Restart device
- GPU delegate error → Akan fallback ke CPU otomatis

### Kamera Tidak Muncul

1. Check permission:
   ```
   Settings > Apps > Conjunctiva Segmentation > Permissions > Camera
   ```

2. Restart app:
   ```bash
   adb shell am force-stop com.conjunctiva.segmentation
   adb shell am start -n com.conjunctiva.segmentation/.MainActivity
   ```

### Aplikasi Lag/Lambat

Edit `MainActivity.kt`:
```kotlin
// Line ~30
private var processEveryNFrames = 2  // Ubah dari 1 ke 2 atau 3
```

Rebuild dan run lagi.

## 🎨 Customization

### Ubah Warna Overlay

Edit `OverlayView.kt` line ~20:
```kotlin
private val maskPaint = Paint().apply {
    style = Paint.Style.FILL
    color = Color.argb(100, 0, 255, 0)  // Hijau semi-transparan
    isAntiAlias = true
}
```

Rebuild dan run.

### Ubah Threshold Confidence

Edit `ConjunctivaSegmentor.kt` line ~30:
```kotlin
private const val CONFIDENCE_THRESHOLD = 0.3f  // Lebih rendah = lebih sensitif
```

Rebuild dan run.

## 📊 Testing Checklist

- [ ] App builds successfully
- [ ] App launches without crash
- [ ] Camera permission granted
- [ ] Camera preview shows
- [ ] Overlay view renders
- [ ] Detection works (arahkan ke mata)
- [ ] FPS > 10
- [ ] Inference time < 200ms
- [ ] No memory leaks (test 5+ menit)
- [ ] Rotation works (portrait/landscape)

## 🔄 Development Workflow

### Make Changes

1. Edit code di Android Studio
2. Save (Ctrl+S)
3. Run lagi (Shift+F10)

Android Studio akan:
- Incremental build (lebih cepat)
- Install update ke device
- Restart app otomatis

### View Logs

```bash
# Real-time logs
adb logcat | grep "Conjunctiva"

# Save logs to file
adb logcat > logs.txt
```

### Debug Mode

1. Set breakpoint di code (klik di line number)
2. Run > Debug 'app' (Shift+F9)
3. App akan pause di breakpoint
4. Inspect variables di Debug panel

## 📦 Build APK

### Debug APK (untuk testing)

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Install manual:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Release APK (untuk distribusi)

1. Generate keystore:
```bash
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias
```

2. Edit `app/build.gradle`, tambahkan:
```gradle
android {
    signingConfigs {
        release {
            storeFile file("../my-release-key.jks")
            storePassword "your-password"
            keyAlias "my-key-alias"
            keyPassword "your-password"
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
}
```

3. Build:
```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

## 🎓 Next Steps

1. **Improve Model**: Train dengan lebih banyak data
2. **Add Features**: Screenshot, save results, history
3. **Optimize**: Profile dan optimize bottlenecks
4. **UI/UX**: Improve user interface
5. **Testing**: Unit tests, UI tests, integration tests

## 📚 Resources

- [README.md](README.md) - Overview dan fitur
- [IMPLEMENTATION_GUIDE.md](IMPLEMENTATION_GUIDE.md) - Detail teknikal
- [Android Developer Docs](https://developer.android.com)
- [TensorFlow Lite Guide](https://www.tensorflow.org/lite/guide)

## 💬 Support

Jika ada masalah:
1. Check logcat untuk error messages
2. Baca IMPLEMENTATION_GUIDE.md untuk detail
3. Google error message
4. Check Stack Overflow

---

**Selamat mencoba! 🎉**
