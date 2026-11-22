# Evaluasi non-regresi Cengkeh dan latensi model

Alat ini membandingkan keluaran model Cengkeh antar varian APK (A, B1, B2) dan mengukur latensi
tiap model. Yang dibandingkan adalah keluaran model antar varian, bukan akurasi (gambar tanpa label).
Semua perintah `adb` dan pemasangan ke HP dijalankan manual oleh penulis, di PowerShell, dari root repo.

Varian: **A** = Cengkeh V2; **B1** = A + mangga v2; **B2** = B1 + mangga v1.

Perangkat uji: Xiaomi 22101316G (ADB nirkabel). Gambar uji Cengkeh (±30 .jpg/.jpeg/.png) TIDAK boleh
masuk Git (aturan HANDOFF.md); simpan di folder lokal di luar repo, misalnya `C:\dev\uji_cengkeh`.

## Nama yang dipakai

| Hal | Nilai (diperiksa dari `app/build.gradle` dan manifest tes hasil build) |
|---|---|
| Paket aplikasi | `com.google.mediapipe.examples.objectdetection` |
| Paket tes | `com.google.mediapipe.examples.objectdetection.test` |
| Runner | `androidx.test.runner.AndroidJUnitRunner` |
| Komponen instrumentasi | `com.google.mediapipe.examples.objectdetection.test/androidx.test.runner.AndroidJUnitRunner` |
| Folder data di HP | `/sdcard/Android/data/com.google.mediapipe.examples.objectdetection/files/` |

## Urutan untuk satu varian (contoh B1)

Ganti `B1` dengan `A` atau `B2`. Pohon kerja git harus bersih sebelum membangun.

```powershell
$V = 'B1'
$PKG = 'com.google.mediapipe.examples.objectdetection'
$FILES = "/sdcard/Android/data/$PKG/files"

# 1. Bangun (assets/models diatur sementara lalu dipulihkan otomatis)
.\tools\bangun_varian.ps1 -Varian $V
#    hasil: C:\dev\apk\apk_<varian>_<commit>.apk dan apk_tes_<commit>.apk; dicatat di apk_commit.txt
$C = (git rev-parse --short HEAD)

# 2. Pasang APK aplikasi, lalu APK tes (-t wajib untuk APK tes)
adb install -r "C:\dev\apk\apk_${V}_$C.apk"
adb install -r -t "C:\dev\apk\apk_tes_$C.apk"

# 3. Dorong gambar uji ke folder aplikasi (folder dibuat dulu; isi lama dihapus agar tidak bercampur)
adb shell "rm -rf $FILES/uji_cengkeh; mkdir -p $FILES/uji_cengkeh"
adb push C:\dev\uji_cengkeh\. "$FILES/uji_cengkeh/"
adb shell "ls $FILES/uji_cengkeh | wc -l"

# 4. Jalankan dua tes (instrument, aplikasi TIDAK di-uninstall)
adb shell am instrument -w -e class "$PKG.CengkehOutputDumpTest" -e label $V "$PKG.test/androidx.test.runner.AndroidJUnitRunner"
adb shell am instrument -w -e class "$PKG.LatencyBenchmarkTest" -e label $V "$PKG.test/androidx.test.runner.AndroidJUnitRunner"

# 5. Ambil keluaran ke folder lokal di luar repo
New-Item -ItemType Directory -Force C:\dev\hasil_uji | Out-Null
adb pull "$FILES/cengkeh_out_$V.txt" C:\dev\hasil_uji\
adb pull "$FILES/latensi_$V.csv" C:\dev\hasil_uji\

# 6. Bandingkan (acuan = A; kandidat = B1 atau B2). Kode keluar 0 = semua lulus.
.\tools\bandingkan_cengkeh.ps1 -Acuan C:\dev\hasil_uji\cengkeh_out_A.txt -Kandidat C:\dev\hasil_uji\cengkeh_out_B1.txt
```

Ulangi untuk A, B1, B2. Varian A dijalankan lebih dulu karena menjadi acuan.

Catatan penting:

- `am instrument -w` menunggu tes selesai dan mencetak `OK (1 test)` atau `FAILURES!!!` beserta pesannya.
  Tes dump GAGAL bila folder `uji_cengkeh` tidak ada atau kosong (pesan menyebut path-nya).
- Jalankan benchmark latensi dengan HP tidak sedang mengisi daya, layar menyala, tanpa aplikasi lain
  berjalan, dan suhu serupa antar varian. Suhu dan baterai sebelum/sesudah dicatat di logcat.
  Untuk membaca logcat: `adb logcat -d -s ClavoTest`; baris `CSV ...` adalah cadangan bila berkas CSV hilang.
- Latensi: CPU wajib; GPU opsional. GPU yang gagal tercatat `GPU_GAGAL` di kolom `delegasi` dan
  tidak menggagalkan tes. Kolom `thread` selalu `default` (jumlah thread MediaPipe tidak dapat diatur).
- `bandingkan_cengkeh.ps1` hanya bermakna bila kedua berkas berasal dari gambar yang sama.
- `CengkehRegressionTest` (lama) memakai foto pasta, tidak bermakna, jangan dipakai sebagai bukti.
  Dengan `-e class` hanya dua tes di atas yang dijalankan, jadi tes lama itu tidak ikut.

## Mengapa tidak memakai `connectedDebugAndroidTest`

Hasil pemeriksaan untuk konfigurasi repo ini:

- `app/build.gradle` dan `build.gradle` tidak punya blok `testOptions` dan tidak mengatur apa pun soal
  uninstall; AGP 8.13.2, runner standar `AndroidJUnitRunner`, tanpa Orchestrator. Jadi berlaku perilaku
  bawaan AGP.
- Perilaku bawaan AGP: task `connected<Variant>AndroidTest` memasang APK aplikasi dan APK tes, menjalankan
  tes, lalu meng-uninstall keduanya. Android menghapus folder `Android/data/<paket>/files` saat paket
  di-uninstall, sehingga `cengkeh_out_*.txt` dan `latensi_*.csv` hilang sebelum sempat di-`adb pull`;
  gambar di `uji_cengkeh` juga ikut hilang.
- Status pemeriksaan: kesimpulan dari konfigurasi (tidak ada override) dan perilaku bawaan AGP yang
  terdokumentasi; belum dibuktikan dengan menjalankannya di HP (sesi ini tidak menjalankan tes di perangkat).
  Dengan `adb shell am instrument` aplikasi tetap terpasang, sehingga berkas keluaran aman.
- Alasan tambahan: `connectedDebugAndroidTest` memakai satu APK dari `assets/models` apa adanya, dan
  `bangun_varian.ps1` perlu mengatur isi model per varian; pemasangan terpisah juga memberi kendali atas
  `-e label`.
