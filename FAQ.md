# ❓ Frequently Asked Questions (FAQ)

## 📱 General Questions

### Q: Apa itu Conjunctiva Segmentation?
**A:** Aplikasi Android untuk mendeteksi dan melakukan segmentasi area konjungtiva (bagian putih mata) secara real-time menggunakan kamera smartphone dan model AI.

### Q: Apakah aplikasi ini gratis?
**A:** Ya, aplikasi ini open-source dan gratis untuk digunakan.

### Q: Apakah memerlukan koneksi internet?
**A:** Tidak. Semua proses dilakukan di device (on-device), tidak memerlukan internet sama sekali.

### Q: Apakah data saya aman?
**A:** Ya, sangat aman. Aplikasi tidak menyimpan, mengirim, atau mengumpulkan data apapun. Semua proses real-time dan tidak ada penyimpanan.

## 🔧 Technical Questions

### Q: Berapa minimum spesifikasi device yang diperlukan?
**A:** 
- Android 7.0 (API 24) atau lebih tinggi
- RAM minimal 2GB (recommended 4GB+)
- Kamera belakang
- Storage minimal 50MB untuk instalasi

### Q: Kenapa aplikasi lambat di device saya?
**A:** Performa tergantung pada chipset device. Untuk device budget/lama:
1. Edit `MainActivity.kt`, ubah `processEveryNFrames = 3`
2. Rebuild aplikasi
3. Atau gunakan device yang lebih baru

### Q: Berapa FPS yang normal?
**A:**
- Flagship (SD 8 series): 25-30 FPS
- Mid-range (SD 7 series): 15-25 FPS
- Budget (SD 6 series): 10-15 FPS

### Q: Kenapa inference time saya tinggi?
**A:** Inference time normal:
- Flagship: 20-40ms
- Mid-range: 40-80ms
- Budget: 80-150ms

Jika lebih tinggi, coba:
1. Restart device
2. Close aplikasi lain
3. Check apakah device panas (thermal throttling)

## 🐛 Troubleshooting

### Q: Aplikasi crash saat dibuka
**A:** Kemungkinan penyebab:
1. **Model file tidak ada**: Pastikan `app/src/main/assets/best_float16.tflite` ada
2. **Out of memory**: Restart device dan coba lagi
3. **Incompatible device**: Check minimum requirements

Solusi:
```bash
# Check logcat
adb logcat | grep "ConjunctivaSegmentor"
```

### Q: Kamera tidak muncul
**A:** 
1. Check permission: Settings > Apps > Conjunctiva Segmentation > Permissions > Camera (harus ON)
2. Restart aplikasi
3. Restart device
4. Pastikan kamera tidak digunakan aplikasi lain

### Q: Overlay tidak muncul
**A:**
1. Arahkan kamera ke mata/konjungtiva
2. Pastikan lighting cukup
3. Jarak kamera 10-30cm dari mata
4. Check confidence threshold (mungkin terlalu tinggi)

### Q: Deteksi tidak akurat
**A:**
1. **Lighting**: Pastikan pencahayaan cukup
2. **Jarak**: Jaga jarak 15-25cm
3. **Fokus**: Pastikan kamera fokus
4. **Model**: Mungkin perlu retrain dengan data lebih banyak

### Q: Aplikasi panas dan battery cepat habis
**A:** Normal untuk aplikasi real-time AI. Untuk mengurangi:
1. Ubah `processEveryNFrames` ke 2 atau 3
2. Kurangi resolusi kamera
3. Jangan gunakan terlalu lama (max 10-15 menit continuous)
4. Gunakan di ruangan ber-AC

### Q: Error "Model not found"
**A:**
```bash
# Verify model exists
ls app/src/main/assets/best_float16.tflite

# If not, copy it
mkdir -p app/src/main/assets
cp models/best_float16.tflite app/src/main/assets/

# Rebuild
./gradlew clean assembleDebug
```

### Q: Error "SDK location not found"
**A:** Create `local.properties`:
```properties
sdk.dir=C:\\Users\\YourUsername\\AppData\\Local\\Android\\Sdk
```
Ganti path sesuai lokasi Android SDK Anda.

### Q: Gradle sync failed
**A:**
1. Check internet connection
2. File > Invalidate Caches / Restart
3. Delete `.gradle` folder
4. Sync again

## 🎨 Customization Questions

### Q: Bagaimana mengubah warna overlay?
**A:** Edit `OverlayView.kt` line ~20:
```kotlin
private val maskPaint = Paint().apply {
    color = Color.argb(100, 0, 255, 0)  // Hijau
}
```

Atau dari kode:
```kotlin
overlayView.setMaskColor(Color.BLUE, alpha = 120)
```

### Q: Bagaimana mengubah confidence threshold?
**A:** Edit `ConjunctivaSegmentor.kt` line ~30:
```kotlin
private const val CONFIDENCE_THRESHOLD = 0.3f  // Lebih sensitif
```

### Q: Bagaimana menambah fitur screenshot?
**A:** Lihat contoh di `IMPLEMENTATION_GUIDE.md` section "Customization".

### Q: Bisa detect multiple classes?
**A:** Ya, edit `OverlayView.kt`:
```kotlin
val className = when(result.classId) {
    0 -> "Normal"
    1 -> "Abnormal"
    else -> "Unknown"
}
```

## 📊 Model Questions

### Q: Model apa yang digunakan?
**A:** YOLOv8n-seg (Nano Segmentation) yang di-export ke TensorFlow Lite FP16.

### Q: Berapa ukuran model?
**A:** ~6MB (FP16 format).

### Q: Bisa ganti model?
**A:** Ya, ganti file `best_float16.tflite` dengan model baru Anda. Pastikan:
- Format: TFLite
- Input: 320x320x3 (atau sesuaikan kode)
- Output: Compatible dengan YOLO format

### Q: Bagaimana meningkatkan akurasi?
**A:**
1. **Training data**: Tambah lebih banyak data training
2. **Augmentation**: Gunakan augmentation yang lebih agresif
3. **Model size**: Gunakan YOLOv8s-seg atau YOLOv8m-seg (lebih besar)
4. **Epochs**: Train lebih lama
5. **Hyperparameters**: Tune learning rate, batch size, dll

### Q: Kenapa menggunakan FP16 bukan INT8?
**A:** 
- **FP16**: Balance antara speed dan accuracy
- **INT8**: Lebih cepat tapi accuracy drop ~10-15%
- Untuk medical application, accuracy lebih penting

### Q: Bisa menggunakan model INT8?
**A:** Ya, export model dengan `int8=True`:
```python
model.export(format='tflite', imgsz=320, int8=True)
```

## 🚀 Development Questions

### Q: Bagaimana cara build dari source?
**A:** Lihat `BUILD_INSTRUCTIONS.md` untuk panduan lengkap.

### Q: Bagaimana cara testing?
**A:** 
```bash
# Unit tests
./gradlew test

# Instrumented tests
./gradlew connectedAndroidTest
```

### Q: Bagaimana cara profiling?
**A:** 
1. Android Studio > View > Tool Windows > Profiler
2. Select device dan app
3. Monitor CPU, Memory, Energy

### Q: Bagaimana cara debug?
**A:**
1. Set breakpoint di code
2. Run > Debug 'app' (Shift+F9)
3. Inspect variables di Debug panel

### Q: Bisa integrate dengan backend?
**A:** Ya, tambahkan networking library (Retrofit, OkHttp) dan implement API calls. Tapi ingat privacy concerns.

## 📱 Device Compatibility

### Q: Device apa saja yang support?
**A:** Semua Android device dengan:
- Android 7.0+
- Kamera
- RAM 2GB+

### Q: Apakah support tablet?
**A:** Ya, aplikasi akan adapt ke screen size tablet.

### Q: Apakah support foldable?
**A:** Ya, tapi mungkin perlu adjustment untuk multi-window mode.

### Q: Kenapa tidak support Android 6.0 ke bawah?
**A:** 
- CameraX requires API 21+
- TFLite GPU requires API 24+
- Modern Android features

## 🔐 Security & Privacy

### Q: Apakah aplikasi mengumpulkan data?
**A:** Tidak sama sekali. Zero data collection.

### Q: Apakah ada analytics?
**A:** Tidak ada analytics atau tracking.

### Q: Apakah aman untuk medical use?
**A:** Aplikasi ini untuk **research/educational purposes only**. Untuk medical use:
1. Perlu validasi klinis
2. Perlu approval regulatory (FDA, BPOM, dll)
3. Perlu clinical trials
4. Perlu medical professional supervision

### Q: Apakah HIPAA compliant?
**A:** Tidak applicable karena tidak ada data storage atau transmission. Tapi untuk medical use, perlu proper compliance review.

## 💡 Best Practices

### Q: Berapa lama maksimal continuous use?
**A:** Recommended max 15-20 menit untuk menghindari:
- Device overheating
- Battery drain
- Eye strain

### Q: Lighting terbaik untuk deteksi?
**A:**
- Natural daylight (best)
- Bright indoor lighting
- Avoid direct sunlight (too bright)
- Avoid low light (noise)

### Q: Jarak optimal kamera ke mata?
**A:** 15-25cm untuk hasil terbaik.

### Q: Posisi kamera terbaik?
**A:** 
- Sejajar dengan mata
- Slight angle dari atas (10-15°)
- Avoid extreme angles

## 📚 Learning Resources

### Q: Dimana belajar tentang YOLO?
**A:** 
- [Ultralytics Docs](https://docs.ultralytics.com/)
- [YOLOv8 Paper](https://arxiv.org/abs/2305.09972)

### Q: Dimana belajar TensorFlow Lite?
**A:**
- [TFLite Guide](https://www.tensorflow.org/lite/guide)
- [TFLite Android](https://www.tensorflow.org/lite/android)

### Q: Dimana belajar CameraX?
**A:**
- [CameraX Documentation](https://developer.android.com/training/camerax)
- [CameraX Codelab](https://developer.android.com/codelabs/camerax-getting-started)

## 🤝 Contributing

### Q: Bagaimana cara contribute?
**A:**
1. Fork repository
2. Create feature branch
3. Make changes
4. Write tests
5. Submit pull request

### Q: Apa yang bisa di-contribute?
**A:**
- Bug fixes
- Performance improvements
- New features
- Documentation
- Tests
- Translations

## 📞 Support

### Q: Dimana mendapat bantuan?
**A:**
1. Baca dokumentasi (README, QUICKSTART, dll)
2. Check FAQ ini
3. Check logcat untuk error messages
4. Search Stack Overflow
5. Open GitHub issue

### Q: Bagaimana melaporkan bug?
**A:** Gunakan template di `TEST_PLAN.md` section "Bug Reporting Template".

---

**Tidak menemukan jawaban?** Check dokumentasi lengkap atau open issue di GitHub! 🚀
