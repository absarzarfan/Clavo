# Bukti evaluasi TA (folder training)

- `my_script.py`, `my_script_apip.py`: pelatihan (v2 dan r1)
- `eval_cm.py`: evaluasi matriks konfusi; `count_dataset.py`, `leakage_check.py`, `overlap_check.py`, `dup_check.py`, `dup_pairs.py`: pemeriksaan data
- `results/`: hasil evaluasi (CSV dan teks), `results/pc/` (PC), `results/audit_apip/annotator1/` (label audit)
- Gambar dataset tidak disertakan (hak cipta pemilik asli); bobot model belum dirilis, hash model ada di `results/pc/r1_sha256.txt`
- Kunci API tidak disimpan di repositori; `roboflow-mangga.py` membaca `ROBOFLOW_API_KEY` dari variabel lingkungan
