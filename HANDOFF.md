# Catatan serah-terima Clavo (10 Nov 2026)

REPO
- Branch kerja: feature/clavo-mangga-ta. Commit lokal: 63f5f64, 370671b, 71915f1, 75309b8, 48fe66d, f2d3db4, ditambah commit training/handoff ini.

MODEL
- Mangga v1 (default): AP50 0.857. v2: AP 0.609, AP50 0.796, busuk 0.502. Hasil seri, tes lapangan menentukan.
- Cengkeh V2: bawaan tim, belum dilatih ulang. Dataset asalnya belum dikonfirmasi.
- V3 tim ("Manggo_efficientdet_lite2_V3.tflite", catatan lama: 95.56% / 63.32 ms): bukti mentah belum ada. Periksa input, konfigurasi, dan kebocoran data (augmentasi lalu split acak).

DATA
- v1/v2 dilatih dari dataset OnieU (CC BY 4.0) lewat fork Roboflow k52fw versi 1: 1.533 gambar (1.073/307/153), tanpa augmentasi tambahan.
- Citra webcam Batam: belum dianotasi. Rencana: LabelImg + anotator 2 + Cohen's kappa, hanya sebagai test set.
- Dataset lain yang pernah dibuka: apip "mango detection and counting" (713, ekspor 1.869 dengan augmentasi 3x) dan apip "Manggo Ripeness" (886, 5 kelas). Belum diputuskan.

WEBCAM
- OTG putus-nyambung dengan webcam, mouse normal. Preview kadang tampil, belum ada kotak deteksi.
- Belum dites: putar 90 derajat, turunkan threshold, gambar yang sama dengan uji kamera HP. Lanjut Prompt 6.

PENDING (urut)
1. Push ke fork, clone di PC.
2. Webcam: tiga tes + Prompt 6.
3. Prompt 7 (latensi + log aktivitas) dan Prompt 8 (tes otomatis).
4. eval_cm.py untuk v1/v2, inspect_tflite.py, hash Cengkeh.
5. Regresi Cengkeh: APK A (hanya Cengkeh) vs B (+ mangga). Syarat utama sebelum submit.
6. Audit label (153 gambar) + data webcam Batam + kappa.
7. 30 sesi lapangan, UAT minimal 5 responden.
8. Naskah jurnal dan TA: abstrak tanpa angka yang tak terbukti, data availability OnieU, Bab I-III sesuai peta revisi.

JANGAN
- Jangan menyalin gradle.properties atau API key. Jangan menulis hasil yang belum diukur.
