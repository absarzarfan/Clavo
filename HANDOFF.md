# Catatan serah-terima Clavo (diperbarui 24 Nov 2026)

## REPO
- Branch kerja: feature/clavo-mangga-ta. Commit terakhir saat pembaruan: b009989 (label anotator 2, kappa 3 arah, label konsensus, evaluasi tanpa duplikat).
- Rilis arsip terkini: tag v1.2-ta (menunjuk b009989), DOI 10.5281/zenodo.24182885. Sitasi hanya DOI ini; JANGAN sitasi versi lama 24156797. Rilis berikutnya: v1.3-ta setelah data lapangan.

## MODEL
- APK membawa satu model mangga (v2) ditambah Cengkeh. Mangga v1 diarsipkan di models_archive/ (tidak ikut APK; ukuran dan SHA-256 di models_archive/README.md). Preferensi model tersimpan yang sudah tidak ada jatuh ke model pertama yang tersedia.
- Mangga v1: AP50 0,857. v2: AP 0,609, AP50 0,796, busuk 0,502. v1 dan v2 tidak dapat dibedakan (lihat Kebocoran OnieU); klaim "v2 lebih baik" tidak boleh dipakai. Pilihan v2 di aplikasi bersifat sementara sampai uji lapangan.
- Evaluasi test split OnieU (153 gambar, 166 objek; skor 0,3, IoU 0,5): macro-F1 v1 0,831, v2 0,854 (angka ini terkontaminasi kebocoran; lihat bagian berikut). Busuk terbaca matang: v1 7/19, v2 5/19.
- Semua model berinput 448x448.
- Cengkeh V2: bawaan tim, belum dilatih ulang. Asal dataset terkonfirmasi (menurut pembuat dataset): dilatih dari dataset publik "detect level maturity cloveV" (Roboflow Universe, CC BY 4.0).
- V3 tim (Manggo, 14 Okt 2025): berlabel sama, bukan hasil latih penulis. Pada test resmi apip (45 gambar) akurasi 43/45 = 95,56%, macro-F1 0,954 (klaim lama direproduksi); pada test OnieU F1 0,758, akurasi 0,801. v1/v2 pada test resmi apip: akurasi 0,600/0,644 (domain gap besar). Belum terbukti: latensi 63,32 ms, "dikumpulkan di Batam", log pelatihan V3.

## UKURAN APK (commit 771e3d8, tag eval-771e3d8, PC, JDK 17, debug, APK bersih)
| Varian | Isi | Ukuran (byte) | Selisih terhadap A |
|---|---|---|---|
| A | Cengkeh V2 saja | 82.231.779 | - |
| B1 | A + mangga v2 | 89.624.622 | +8,99% |
| B2 | B1 + mangga v1 | 97.017.465 | +17,98% |
- Selisih per model mangga: 7.392.843 byte.
- USANG: angka commit 695d12c (A 82.179.915 / B1 89.572.758 / B2 96.965.601), commit 1f616a7, dan angka 11 Nov (A 82.163.107 / B 96.948.793) tidak berlaku lagi. Jangan dikutip; training/results/pc/ukuran_apk_695d12c.txt hanya arsip.

## Evaluasi non-regresi, skalabilitas, dan latensi (24 Nov 2026)
Prosedur ringkas:
1. Checkout commit 771e3d8 (tag eval-771e3d8). Bangun tiga varian: salin model ke app/src/main/assets/models lalu .\gradlew.bat assembleDebug (tanpa product flavor); A = Cengkeh V2; B1 = A + mangga v2; B2 = B1 + mangga v1 (salinan v1 dari models_archive/ dihapus lagi setelah build, tidak di-commit). Catat ukuran byte, SHA-256, dan commit di C:\dev\apk\apk_commit.txt.
2. Pasang ketiga APK di HP lewat ADB nirkabel (dijalankan pengguna di PowerShell).
3. Non-regresi: jalankan Cengkeh V2 pada 143 gambar uji pada tiap varian, simpan keluaran (kelas, skor, kotak), bandingkan A vs B1 dan A vs B2 dengan toleransi skor 0,005 dan kotak 2 px, serta hitung SHA-256 berkas keluaran.
4. Latensi: CPU, enam putaran seimbang (urutan varian diputar), suhu baterai 35 C atau kurang, 10 pemanasan dan 100 pengukuran per putaran; laporkan rata-rata dari rata-rata putaran dan SD antar putaran.
5. Overlay versi diperiksa pada mode terang dan gelap lewat tangkapan layar beruntun dan OCR.

Hasil (angka terukur):
| Uji | A (Cengkeh V2) | B1 (+ mangga v2) | B2 (+ v1 dan v2) |
|---|---|---|---|
| Ukuran APK (byte) | 82.231.779 | 89.624.622 (+8,99%) | 97.017.465 (+17,98%) |
| Regresi keluaran Cengkeh V2 vs A (143 gambar) | - | 143 lulus, 0 gagal | 143 lulus, 0 gagal |
| Latensi Cengkeh V2, CPU (ms, rata-rata; SD antar putaran) | 131,0; 3,3 | 133,9; 5,0 | 133,9; 5,2 |
| Latensi mangga v1 (ms) | - | - | 133,2; 4,0 |
| Latensi mangga v2 (ms) | - | 134,4; 6,2 | 135,8; 2,4 |
- Regresi: 389 deteksi; SHA-256 berkas keluaran identik di ketiga varian (6FB6086A...C322D5). Gambar: 143 gambar 640x640 dari dataset publik detect level maturity cloveV (Roboflow Universe, CC BY 4.0).
- Latensi Cengkeh terhadap A: +2,9 ms (+2,2%) untuk B1 dan B2; belum dapat dibedakan dari derau (SD antar putaran 3-5 ms).
- GPU gagal diinisialisasi (ToTensorConverter: input data size does not match expected size), sehingga hanya CPU yang diukur.
- Overlay versi (versi, commit, jenis build, daftar model, kredit) terverifikasi pada mode terang dan gelap.

Keterbatasan:
- Satu HP (Xiaomi 22101316G, Android 14, HyperOS); belum ada perangkat lain.
- Build debug, bukan release; ukuran dan latensi dapat berbeda pada release.
- CPU saja; latensi GPU tidak terukur.
- Gambar uji berasal dari data yang dipakai melatih Cengkeh V2 (bukan data tak terlihat) dan diubah ke 640x640 dengan peregangan (stretch); hasilnya menguji non-regresi (keluaran identik), bukan akurasi.
- Enam putaran latensi; selisih kecil tidak dapat disimpulkan.

Alat uji: fungsi UjiVarian, PutaranLatensi, SalinGambarCengkeh, dan UjiOverlay ada di tools/alat_uji_clavo.ps1 (salinan dokumentasi; ketik IP:port penuh saat memakai Sambung, karena alamat bawaan sudah diganti placeholder).

Lokasi bukti: bukti mentah di luar Git, C:\dev\uji\bukti_evaluasi_20261124_2153.zip (SHA-256 48EF823D8495E15D76043EB8D0335B7D687EA5C5973369361426C22C9C3D9075). APK: C:\dev\apk.

## HASIL EVALUASI
- Laptop: training/results. PC: training/results/pc (v1/v2 identik dengan laptop).
- Hasil terkini: training/results/audit_apip, training/results/audit_onieu, dan training/results/pc.
- Skrip (training/): eval_cm_fast.py (evaluasi), kappa3.py, onieu_leak_check.py, onieu_bersih.py.
- Label audit apip (anotator 1, 2, 3, kappa 3 arah, rincian konsensus): training/results/audit_apip.
- Ringkasan konsensus bersih: training/results/pc/ringkasan_konsensus_bersih.txt.
- Dataset apip ekspor v2: 1734/90/45 gambar, 3747 objek. Pembagian ulang 60/20/20 notebook tim: 84,7% gambar uji punya saudara augmentasi di data latih (kebocoran).

## KEBOCORAN ONIEU
- 26 dari 153 gambar uji punya saudara di train (training/results/audit_onieu/onieu_leak_check.txt).
- Pada 127 gambar tanpa saudara: v1 akurasi 0,891 / macro-F1 0,791; v2 0,883 / 0,759. v1 dan v2 tidak dapat dibedakan.
- Gunakan angka 127 gambar ini, bukan angka 153 gambar, untuk perbandingan v1 vs v2.

## AUDIT LABEL
- Audit OnieU (50 gambar, training/results/audit_onieu): kappa 0,788 (asli-penulis), 0,845 (asli-rekan independen), 0,909 (penulis-rekan); konsensus 52 objek.
- Audit apip (45 gambar) kini punya penilai independen: rekan, buta, gambar dianonimkan (training/results/audit_apip/annotator3). Sesi kedua penulis (annotator2) tidak dipakai sebagai penilai.
- Kappa apip: asli-penulis 0,833; asli-rekan 0,933; penulis-rekan 0,903. Konsensus tiga set (asli, penulis, rekan): 47 objek.
- Akurasi pada konsensus tiga set: V3 dan r1 0,936 (44/47); tanpa gambar mirip data latih 0,952 (41 gambar) dan 0,947 (37 gambar). v1 0,596, v2 0,617.
- README di audit_apip dan audit_onieu menjelaskan penamaan berkas.

## ALUR KERJA
- Pelatihan dan evaluasi di PC (RTX 4060 8 GB); uji Android dan webcam di laptop.
- Gambar Batam dipindah ke PC lewat USB atau Drive, tidak lewat Git.

## UJI LAPANGAN
- Pakai APK varian B2 (Cengkeh + v1 + v2) agar v1 dan v2 bisa dibandingkan lewat spinner. APK: apk_B2_v1.3.0-ta_771e3d8.apk di C:\dev\apk\arsip. Nama terbaru selalu dilihat di C:\dev\apk\arsip\VERSI.md (daftar versi, ukuran, SHA-256); bangun ulang dari commit bila perlu.
- Lingkup: aplikasi untuk warga yang memiliki pohon mangga di rumah, bukan hanya petani atau pemilik kebun. Sumber utama gambar uji lapangan Batam adalah pohon mangga di pekarangan rumah warga di beberapa lokasi; kebun narasumber di Barelang hanya pendukung.
- Catatan pengambilan gambar: lokasi dicatat dengan kode tanpa alamat; tidak ada orang atau wajah di bingkai; wajib izin pemilik pohon; catat tanggal, jam, cuaca, varietas, jarak, dan kategori bertumpuk (buah saling menutupi).

## DATA
- v1/v2 dilatih dari dataset OnieU (CC BY 4.0) lewat fork Roboflow k52fw versi 1: 1.533 gambar (1.073/307/153), tanpa augmentasi tambahan.
- Citra webcam Batam: belum dianotasi; hanya sebagai test set (LabelImg + anotator 2 + Cohen's kappa).

## SISA PEKERJAAN (butuh perangkat)
1. Uji webcam dan HP: posisi kotak di 4 rotasi (90 derajat), slider threshold, status webcam saat cabut-colok. Catatan: OTG webcam sempat putus-nyambung; belum diuji di HP.
2. SELESAI: latensi dari perangkat sudah diukur; lihat bagian Evaluasi 24 Nov 2026.
3. CengkehRegressionTest: jalankan sekali di perangkat, salin golden ke androidTest/assets.
4. TC-01..TC-11.
5. Minimal 100 gambar Batam (anotasi + kappa).
6. UAT minimal 5 responden (plus sesi lapangan).
7. Naskah jurnal dan TA: abstrak tanpa angka yang tak terbukti, data availability OnieU, Bab I-III sesuai peta revisi.

## JANGAN
- Jangan menyalin gradle.properties atau API key. Jangan menulis hasil yang belum diukur.
