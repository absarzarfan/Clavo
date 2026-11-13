# Catatan serah-terima Clavo (diperbarui 13 Nov 2026)

## REPO
- Branch kerja: feature/clavo-mangga-ta. Commit terakhir saat pembaruan: b009989 (label anotator 2, kappa 3 arah, label konsensus, evaluasi tanpa duplikat).
- Rilis arsip: tag v1.2-ta (menunjuk b009989), DOI 10.5281/zenodo.24182885. Sitasi hanya DOI ini; JANGAN sitasi versi lama 24156797.

## MODEL
- APK membawa satu model mangga (v2) ditambah Cengkeh. Mangga v1 diarsipkan di models_archive/ (tidak ikut APK; ukuran dan SHA-256 di models_archive/README.md). Preferensi model tersimpan yang sudah tidak ada jatuh ke model pertama yang tersedia.
- Mangga v1: AP50 0,857. v2: AP 0,609, AP50 0,796, busuk 0,502. Hasil seri; tes lapangan menentukan.
- Evaluasi test split OnieU (153 gambar, 166 objek; skor 0,3, IoU 0,5): macro-F1 v1 0,831, v2 0,854. Busuk terbaca matang: v1 7/19, v2 5/19.
- Semua model berinput 448x448.
- Cengkeh V2: bawaan tim, belum dilatih ulang; dataset asal belum dikonfirmasi.
- V3 tim (Manggo, 14 Okt 2025): berlabel sama, bukan hasil latih penulis. Pada test resmi apip (45 gambar) akurasi 43/45 = 95,56%, macro-F1 0,954 (klaim lama direproduksi); pada test OnieU F1 0,758, akurasi 0,801. v1/v2 pada test resmi apip: akurasi 0,600/0,644 (domain gap besar). Belum terbukti: latensi 63,32 ms, "dikumpulkan di Batam", log pelatihan V3.

## UKURAN APK (commit 695d12c, PC, JDK 17, debug)
| Varian | Isi | Ukuran (byte) |
|---|---|---|
| A | Cengkeh saja | 82.179.915 |
| B1 | Cengkeh + mangga v2 | 89.572.758 |
| B2 | Cengkeh + mangga v1 + v2 | 96.965.601 |
- Hash Cengkeh identik di ketiga varian (3263AD49...704C3F). Sumber: training/results/pc/ukuran_apk_695d12c.txt.
- Angka lama (A 82.163.107 / B 96.948.793, 11 Nov) digantikan tabel di atas.

## HASIL EVALUASI
- Laptop: training/results. PC: training/results/pc (v1/v2 identik dengan laptop).
- Label audit (anotator 1, anotator 2, kappa 3 arah, rincian konsensus): training/results/audit_apip.
- Ringkasan konsensus bersih: training/results/pc/ringkasan_konsensus_bersih.txt.
- Dataset apip ekspor v2: 1734/90/45 gambar, 3747 objek. Pembagian ulang 60/20/20 notebook tim: 84,7% gambar uji punya saudara augmentasi di data latih (kebocoran).

## DATA
- v1/v2 dilatih dari dataset OnieU (CC BY 4.0) lewat fork Roboflow k52fw versi 1: 1.533 gambar (1.073/307/153), tanpa augmentasi tambahan.
- Citra webcam Batam: belum dianotasi; hanya sebagai test set (LabelImg + anotator 2 + Cohen's kappa).

## SISA PEKERJAAN (butuh perangkat)
1. Uji webcam dan HP: posisi kotak di 4 rotasi (90 derajat), slider threshold, status webcam saat cabut-colok. Catatan: OTG webcam sempat putus-nyambung; belum diuji di HP.
2. CSV latensi dari perangkat.
3. CengkehRegressionTest: jalankan sekali di perangkat, salin golden ke androidTest/assets.
4. TC-01..TC-11.
5. Minimal 100 gambar Batam (anotasi + kappa).
6. UAT minimal 5 responden (plus sesi lapangan).
7. Naskah jurnal dan TA: abstrak tanpa angka yang tak terbukti, data availability OnieU, Bab I-III sesuai peta revisi.

## JANGAN
- Jangan menyalin gradle.properties atau API key. Jangan menulis hasil yang belum diukur.
