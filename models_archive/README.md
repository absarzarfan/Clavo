# models_archive

Model yang tidak lagi dibawa di APK. Folder ini berada di root repo (di luar `app/src/main/assets`), jadi tidak ikut dikemas ke APK.

| Berkas | Ukuran (byte) | SHA-256 |
|---|---|---|
| `efficientdet_lite2_mangga_v1.tflite` | 7392667 | `2fb03d558ca23b55e7246ca0483ea2d6b407fd5643491f0c6b562b04572f6b97` |

Model mangga yang dipakai aplikasi sekarang: `app/src/main/assets/models/efficientdet_lite2_mangga_v2.tflite`.

Untuk memakai v1 lagi, salin (bukan pindahkan) berkas ke `app/src/main/assets/models/` lalu build ulang; nama tampilan di spinner dibuat otomatis dari nama berkas.
