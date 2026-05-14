# Bugfix Requirements Document

## Introduction

Aplikasi Android untuk segmentasi konjungtiva menggunakan YOLOv8-seg TFLite memiliki tiga masalah kritis yang mempengaruhi kualitas visual dan performa:

1. **Bounding Box yang Tidak Perlu**: OverlayView menggambar kotak hijau 4 sisi di sekitar setiap deteksi, membuat tampilan berantakan untuk aplikasi segmentasi yang seharusnya hanya menampilkan polygon mask.

2. **Segmentation Mask Kacau**: Algoritma `extractPolygonFromMask` terlalu sederhana (hanya mengambil left/right edge per baris), menghasilkan polygon yang tidak mengikuti kontur objek dengan baik dan terlihat tidak natural.

3. **FPS Sangat Rendah**: Model inference memakan ~500ms per frame dengan processing sequential, menghasilkan FPS maksimal hanya 2 FPS. Tidak ada parallel inference pipeline, camera executor menggunakan single thread, dan frame processing bersifat blocking.

Bugfix ini akan menghilangkan bounding box, mengimplementasikan contour detection yang sophisticated, dan membangun parallel inference pipeline untuk meningkatkan FPS secara signifikan.

## Bug Analysis

### Current Behavior (Defect)

#### Bug 1: Bounding Box yang Tidak Perlu

1.1 WHEN OverlayView menggambar hasil segmentasi THEN sistem menggambar bounding box hijau 4 sisi menggunakan `boxPaint` di sekitar setiap deteksi

1.2 WHEN aplikasi menampilkan hasil segmentasi konjungtiva THEN tampilan menjadi berantakan dengan kotak hijau yang tidak diperlukan untuk aplikasi segmentasi

#### Bug 2: Segmentation Mask Kacau

1.3 WHEN `extractPolygonFromMask` memproses mask dari model THEN algoritma hanya mengambil left edge dan right edge per baris tanpa contour detection yang proper

1.4 WHEN polygon dihasilkan dari mask THEN polygon tidak mengikuti kontur objek dengan akurat dan terlihat tidak natural dengan bentuk yang kacau

1.5 WHEN mask memiliki noise atau holes THEN polygon yang dihasilkan tidak smooth dan tidak merepresentasikan bentuk objek dengan baik

#### Bug 3: FPS Sangat Rendah (Bottleneck Performa)

1.6 WHEN model inference dijalankan pada setiap frame THEN inference memakan waktu ~500ms per frame secara sequential

1.7 WHEN `cameraExecutor` menggunakan `Executors.newSingleThreadExecutor()` THEN hanya satu thread yang tersedia untuk memproses frame

1.8 WHEN `processImage` memproses frame THEN processing bersifat blocking dimana satu frame harus selesai sebelum frame berikutnya diproses

1.9 WHEN aplikasi berjalan dengan sequential processing THEN FPS maksimal hanya mencapai 2 FPS (1000ms / 500ms inference time)

1.10 WHEN tidak ada parallel inference pipeline THEN frame-frame baru dari kamera tidak dapat diproses secara concurrent dengan inference yang sedang berjalan

### Expected Behavior (Correct)

#### Fix 1: Hilangkan Bounding Box

2.1 WHEN OverlayView menggambar hasil segmentasi THEN sistem SHALL hanya menggambar polygon mask tanpa bounding box hijau

2.2 WHEN aplikasi menampilkan hasil segmentasi konjungtiva THEN tampilan SHALL bersih dan profesional dengan hanya polygon mask yang terlihat

#### Fix 2: Implementasi Contour Detection yang Sophisticated

2.3 WHEN `extractPolygonFromMask` memproses mask dari model THEN algoritma SHALL menggunakan contour detection yang sophisticated (misalnya OpenCV `findContours` atau algoritma marching squares)

2.4 WHEN polygon dihasilkan dari mask THEN polygon SHALL mengikuti kontur objek dengan akurat dan smooth

2.5 WHEN mask memiliki noise atau holes THEN algoritma SHALL menerapkan filtering (misalnya area threshold) untuk menghasilkan polygon yang clean dan merepresentasikan bentuk objek dengan baik

#### Fix 3: Implementasi Parallel Inference Pipeline

2.6 WHEN model inference dijalankan pada frame THEN sistem SHALL menggunakan parallel inference pipeline untuk memproses multiple frames secara concurrent

2.7 WHEN `cameraExecutor` diinisialisasi THEN sistem SHALL menggunakan thread pool dengan multiple threads (misalnya `Executors.newFixedThreadPool(2)` atau lebih)

2.8 WHEN `processImage` memproses frame THEN sistem SHALL dapat memproses frame baru sementara inference sebelumnya masih berjalan

2.9 WHEN aplikasi berjalan dengan parallel processing THEN FPS SHALL meningkat signifikan dengan target 8-10 FPS

2.10 WHEN parallel inference pipeline diimplementasikan THEN sistem SHALL mengelola queue frame dengan strategi backpressure yang tepat (misalnya `STRATEGY_KEEP_ONLY_LATEST`)

### Unchanged Behavior (Regression Prevention)

#### Preservation 1: Fungsi Segmentasi Dasar

3.1 WHEN model inference dijalankan pada frame yang valid THEN sistem SHALL CONTINUE TO menghasilkan hasil segmentasi yang akurat dengan confidence threshold yang sama (0.35f)

3.2 WHEN hasil segmentasi diproses THEN sistem SHALL CONTINUE TO menerapkan NMS (Non-Maximum Suppression) dengan IOU threshold yang sama (0.45f)

3.3 WHEN polygon mask dihasilkan THEN sistem SHALL CONTINUE TO menggunakan mask threshold yang sama (0.5f) untuk menentukan pixel mana yang termasuk dalam mask

#### Preservation 2: Rendering dan Koordinat

3.4 WHEN OverlayView menggambar polygon mask THEN sistem SHALL CONTINUE TO menggunakan fit-center (letterbox) scaling dengan offset padding yang benar

3.5 WHEN label confidence ditampilkan THEN sistem SHALL CONTINUE TO menampilkan label "Conjunctiva X%" di atas polygon dengan background hitam semi-transparan

3.6 WHEN polygon mask digambar THEN sistem SHALL CONTINUE TO menggunakan warna merah semi-transparan (Color.argb(110, 255, 50, 50)) untuk fill dan merah solid untuk border

#### Preservation 3: Camera dan Lifecycle

3.7 WHEN kamera diinisialisasi THEN sistem SHALL CONTINUE TO menggunakan CameraX dengan back camera sebagai default

3.8 WHEN preview ditampilkan THEN sistem SHALL CONTINUE TO menampilkan preview kamera di PreviewView dengan resolusi target 640x480

3.9 WHEN aplikasi di-destroy THEN sistem SHALL CONTINUE TO menutup executor dan segmentor dengan proper cleanup

3.10 WHEN permission kamera tidak diberikan THEN sistem SHALL CONTINUE TO menampilkan toast error dan menutup aplikasi

#### Preservation 4: Info Panel

3.11 WHEN inference selesai THEN sistem SHALL CONTINUE TO menampilkan inference time dalam milliseconds di info panel

3.12 WHEN deteksi ditemukan THEN sistem SHALL CONTINUE TO menampilkan jumlah deteksi di info panel

3.13 WHEN FPS dihitung THEN sistem SHALL CONTINUE TO menghitung dan menampilkan FPS setiap 1 detik di info panel
