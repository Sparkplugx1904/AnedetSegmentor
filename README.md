# Conjunctiva Segmentation - Real-time Android App

Aplikasi Android untuk segmentasi konjungtiva secara real-time menggunakan TensorFlow Lite FP16.

## 🎯 Fitur Utama

- **Real-time Segmentation**: Deteksi dan segmentasi konjungtiva langsung dari kamera
- **Optimized Performance**: Menggunakan model FP16 dengan XNNPACK dan GPU acceleration
- **Visual Overlay**: Menampilkan masking polygon semi-transparan di atas preview kamera
- **Performance Metrics**: Menampilkan FPS, inference time, dan jumlah deteksi

## 📋 Persyaratan

- Android Studio Hedgehog (2023.1.1) atau lebih baru
- Android SDK 24 atau lebih tinggi
- Perangkat Android dengan kamera
- Model TFLite: `models/best_float16.tflite`

## 🏗️ Struktur Aplikasi

```
app/
├── src/main/
│   ├── java/com/conjunctiva/segmentation/
│   │   ├── MainActivity.kt              # Activity utama dengan CameraX
│   │   ├── ConjunctivaSegmentor.kt      # Class untuk inference TFLite
│   │   ├── ImageUtils.kt                # Utility konversi gambar
│   │   └── OverlayView.kt               # Custom view untuk overlay masking
│   ├── res/
│   │   ├── layout/
│   │   │   └── activity_main.xml        # Layout dengan PreviewView dan OverlayView
│   │   └── values/
│   │       ├── strings.xml
│   │       └── themes.xml
│   └── AndroidManifest.xml
└── build.gradle
```

## 🚀 Cara Menggunakan

### 1. Setup Project

```bash
# Clone atau copy project ini
cd ConjunctivaSegmentation

# Pastikan model TFLite ada di folder yang benar
# models/best_float16.tflite
```

### 2. Copy Model ke Assets (Opsional)

Jika ingin model di-bundle dalam APK:

```bash
mkdir -p app/src/main/assets
cp models/best_float16.tflite app/src/main/assets/
```

Lalu ubah `MODEL_PATH` di `ConjunctivaSegmentor.kt`:
```kotlin
private const val MODEL_PATH = "best_float16.tflite"
```

### 3. Build dan Run

```bash
# Sync Gradle
./gradlew build

# Install ke device
./gradlew installDebug

# Atau langsung dari Android Studio: Run > Run 'app'
```

## ⚙️ Konfigurasi

### Mengubah Performa

Di `MainActivity.kt`, ubah variabel ini:

```kotlin
// Proses setiap N frame (1 = setiap frame, 2 = skip 1 frame, dst)
private var processEveryNFrames = 1
```

### Mengubah Threshold

Di `ConjunctivaSegmentor.kt`:

```kotlin
private const val CONFIDENCE_THRESHOLD = 0.5f  // Threshold confidence
private const val IOU_THRESHOLD = 0.45f        // Threshold NMS
```

### Mengubah Warna Masking

Di `OverlayView.kt`:

```kotlin
private val maskPaint = Paint().apply {
    style = Paint.Style.FILL
    color = Color.argb(100, 255, 0, 0) // ARGB: alpha, red, green, blue
    isAntiAlias = true
}
```

Atau dari kode:
```kotlin
overlayView.setMaskColor(Color.BLUE, alpha = 120)
overlayView.setBorderWidth(5f)
```

## 🔧 Troubleshooting

### Model tidak ditemukan
- Pastikan `models/best_float16.tflite` ada di root project
- Atau copy ke `app/src/main/assets/` dan ubah path loading

### Aplikasi lag/lambat
- Ubah `processEveryNFrames` ke 2 atau 3
- Nonaktifkan GPU delegate jika tidak kompatibel
- Kurangi resolusi kamera di `setTargetResolution()`

### Error TRANSPOSE_CONV
- Pastikan menggunakan model FP16 yang sudah di-export dengan benar
- Model harus di-export dengan `imgsz=320` dan format FP16

### Kamera tidak muncul
- Cek permission kamera di Settings > Apps > Conjunctiva Segmentation
- Pastikan device memiliki kamera belakang

## 📊 Performa

Pada perangkat mid-range (Snapdragon 7 series):
- **FPS**: 15-25 FPS
- **Inference Time**: 40-80ms
- **Memory**: ~150MB

Pada perangkat flagship (Snapdragon 8 series):
- **FPS**: 25-30 FPS
- **Inference Time**: 20-40ms
- **Memory**: ~150MB

## 🎨 Customization

### Menambah Kelas Deteksi

Jika model Anda memiliki multiple classes, edit di `OverlayView.kt`:

```kotlin
private fun drawLabel(canvas: Canvas, result: SegmentationResult, ...) {
    val className = when(result.classId) {
        0 -> "Normal"
        1 -> "Abnormal"
        else -> "Unknown"
    }
    val label = "$className ${confidence}%"
    // ...
}
```

### Menambah Fitur Screenshot

Tambahkan button di layout dan implementasi:

```kotlin
fun captureScreenshot() {
    val bitmap = Bitmap.createBitmap(overlayView.width, overlayView.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    overlayView.draw(canvas)
    // Save bitmap...
}
```

## 📝 Dependencies

- **CameraX**: 1.3.0 - Camera pipeline
- **TensorFlow Lite**: 2.14.0 - Model inference
- **TensorFlow Lite GPU**: 2.14.0 - GPU acceleration
- **TensorFlow Lite Support**: 0.4.4 - Image processing utilities
- **Kotlin Coroutines**: 1.7.3 - Async processing

## 📄 License

MIT License - Silakan digunakan untuk keperluan penelitian dan komersial.

## 👨‍💻 Author

Dibuat untuk aplikasi segmentasi konjungtiva real-time dengan model YOLO FP16.

## 🙏 Acknowledgments

- YOLOv8 Segmentation
- TensorFlow Lite
- CameraX Library
