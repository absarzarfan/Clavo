# Catatan serah-terima Clavo (10 Nov 2026)

REPO
- Branch kerja: feature/clavo-mangga-ta. Commit lokal: 63f5f64, 370671b, 71915f1, 75309b8, 48fe66d, f2d3db4, ditambah commit training/handoff ini.

MODEL
- Mangga v1: AP50 0.857. v2: AP 0.609, AP50 0.796, busuk 0.502. Hasil seri, tes lapangan menentukan.
- APK kini hanya membawa mangga v2 (default mangga). Berkas v1 dipindah ke models_archive/ (tidak ikut APK; ukuran dan SHA-256 di models_archive/README.md). Preferensi model tersimpan yang sudah tidak ada jatuh ke model pertama yang tersedia.
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

## UPDATE 11 Nov 2026
- Regresi APK A vs B sah: A 82.163.107 byte, B 96.948.793 byte (+14.785.686, 7,39 MB per model mangga); hash Cengkeh identik (3263AD49...704C3F), sama dengan file di server (zip Pak Hajrul).
- Evaluasi test split (153 gambar, 166 objek), skor 0,3 dan IoU 0,5: v1 macro-F1 0,831, v2 0,854. Busuk terbaca matang: v1 7/19, v2 5/19. Hasil di training/results.
- Semua model berinput 448x448. V3 tim (Manggo, 14 Okt 2025) berlabel sama; bukan hasil latih penulis.
- Prompt 7 (latensi, log aktivitas), Prompt 8 (tes), Prompt 9 (perbaikan webcam: overlay, input 448, rotasi manual, slider threshold, status dari frame) sudah di-commit dan di-push. Tes unit 41/41. BELUM diuji di HP.
- APK uji: Downloads/clavo_prompt9_debug.apk (laptop).

## SISA (butuh HP dan webcam)
- Uji posisi kotak di 4 rotasi, slider threshold, status cabut-colok, latensi (CSV), TC-01..TC-11, 30 sesi, UAT.
- Golden Cengkeh: jalankan CengkehRegressionTest sekali di perangkat, salin golden ke androidTest/assets.

## UPDATE 11 Nov 2026 (PC)
- Build PC OK (41 tes). Evaluasi v1/v2 di PC identik dengan laptop (training/results/pc).
- V3 tim pada test resmi apip (45 gambar): akurasi 43/45 = 95,56%, macro-F1 0,954 (metrik ketat); klaim lama direproduksi. V3 pada test OnieU (153): F1 0,758, akurasi 0,801 (ambang 0,3).
- v1/v2 pada test resmi apip: akurasi 0,600/0,644 (ambang 0,3): domain gap besar.
- Dataset apip ekspor v2: 1734/90/45 gambar, 3747 objek. Pembagian ulang 60/20/20 notebook tim: 84,7% gambar uji punya saudara augmentasi di data latih.
- Belum terbukti: latensi 63,32 ms, "dikumpulkan di Batam", log pelatihan V3.
