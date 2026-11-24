# Pemuat alat uji Clavo. Muat dengan:  . C:\dev\uji\clavo.ps1
$global:adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$global:pkg = "com.google.mediapipe.examples.objectdetection"
$global:alamatFile = "C:\dev\uji\alamat.txt"

function Sambung {
  $last = if (Test-Path $alamatFile) { (Get-Content $alamatFile -Raw).Trim() } else { "<IP_HP>:<PORT>" }
  $in = Read-Host "Alamat HP dari layar Wireless debugging, IP:port atau port saja (Enter = $last)"
  $in = $in.Trim()
  if ($in -match '^\d+$') { $alamat = $last.Split(':')[0] + ":" + $in }
  elseif ($in) { $alamat = $in }
  else { $alamat = $last }
  if ($alamat -notmatch '^\d{1,3}(\.\d{1,3}){3}:\d+$') { "Format salah, contoh <IP_HP>:<PORT>"; return }
  Set-Content $alamatFile $alamat
  Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
  & $adb connect $alamat
  $env:ANDROID_SERIAL = $alamat
  $m = (& $adb shell getprop ro.product.model | Out-String).Trim()
  if ($m) { "HP terhubung: $m  ($alamat)" } else { "GAGAL: HP tidak menjawab. Baca ulang port di layar Wireless debugging." }
}

function Pairing {
  $last = if (Test-Path $alamatFile) { (Get-Content $alamatFile -Raw).Trim().Split(':')[0] } else { "<IP_HP>" }
  $pp = Read-Host "Port PAIRING (dari dialog 'Pair device with pairing code')"
  & $adb pair ($last + ":" + $pp.Trim())
}

function Crash {
  $d = & $adb shell dumpsys dropbox --print data_app_native_crash | Out-String
  if (-not $d.Trim()) { "HP tidak menjawab. Jalankan Sambung dulu."; return }
  "Crash natif tercatat untuk aplikasi: " + ([regex]::Matches($d, 'Process: ' + [regex]::Escape($pkg))).Count + "   (terakhir terukur: 4)"
  & $adb shell dumpsys dropbox --print data_app_native_crash | Select-String -Pattern 'data_app_native_crash \(text' | Select-Object -Last 8
}

function Pasang($berkas = "C:\dev\apk\apk_B2_putar7.apk") {
  if (-not (Test-Path $berkas)) { "Berkas tidak ada: $berkas"; return }
  "Ukuran: " + (Get-Item $berkas).Length
  "SHA-256 PC: " + (Get-FileHash $berkas -Algorithm SHA256).Hash
  & $adb install -r $berkas
  $p = ((& $adb shell pm path $pkg | Select-Object -First 1) | Out-String).Trim() -replace '^package:',''
  "SHA-256 HP: " + ((& $adb shell sha256sum $p | Out-String).Trim().Split(' ')[0])
}

function Rekam {
  $global:g = "C:\dev\uji\fisik2_$(Get-Date -Format yyyyMMdd_HHmmss).log"
  "Merekam ke $g   (Ctrl+C untuk berhenti)"
  & $adb logcat -c
  & $adb logcat -v threadtime UJI:I ClavoUSB:I MultiCameraClient:I USBMonitor:I UsbHostManager:D Choreographer:I AndroidRuntime:E DEBUG:F *:S | Tee-Object -FilePath $g | Select-String -Pattern 'UJI|ATTACHED|DETACHED|decision|connect-|Skipped|FATAL|Fatal'
}

function Uji($nama, $petunjuk, $detik = 45) {
  & $adb shell log -t UJI $nama
  Write-Host ">>> $nama : $petunjuk" -ForegroundColor Yellow
  for ($i = $detik; $i -gt 0; $i--) { Write-Host -NoNewline "`r$i dtk   "; Start-Sleep 1 }
  Write-Host ""
}

function UjiSemua {
  Uji "A_diam" "jangan menyentuh apa pun"
  Uji "B_goyang_ponsel" "goyang HP beserta OTG, kabel tidak digerakkan"
  Uji "C_kabel_tengah" "gerakkan kabel di bagian tengah"
  Uji "D_dekat_webcam" "gerakkan kabel dekat webcam"
  Uji "E_dekat_otg" "gerakkan kabel dekat konektor OTG"
  Uji "F_goyang_galah" "goyang galah seperti pemakaian nyata"
  & $adb shell log -t UJI "SELESAI"
  "Selesai. Di jendela Rekam: Ctrl+C, lalu jalankan Analisis."
}

function Analisis($berkas) {
  if (-not $berkas) { $berkas = (Get-ChildItem C:\dev\uji\fisik2_*.log -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName }
  if (-not $berkas) { "Tidak ada berkas fisik2_*.log"; return }
  "Berkas: $berkas"
  $lines = Get-Content $berkas
  $cur = "awal"; $hit = @{}
  foreach ($l in $lines) {
    if ($l -match ' UJI\s*: (\S+)') { $cur = $Matches[1]; if (-not $hit.ContainsKey($cur)) { $hit[$cur] = 0 } }
    elseif ($l -match 'Removed device at \S+: ') { $hit[$cur] = 1 + [int]$hit[$cur] }
  }
  "--- Removed device per kondisi"
  $hit.GetEnumerator() | Sort-Object Name | Format-Table -AutoSize
  $att = $null; $rows = @()
  foreach ($l in $lines) {
    if ($l -match 'ClavoUSB: ATTACHED') { $att = [datetime]::ParseExact($l.Substring(0,18),'MM-dd HH:mm:ss.fff',$null) }
    elseif ($att -and $l -match 'camera-state .* code=OPENED') {
      $t = [datetime]::ParseExact($l.Substring(0,18),'MM-dd HH:mm:ss.fff',$null)
      $rows += [pscustomobject]@{ attach = $att.ToString('HH:mm:ss.f'); detik_ke_OPENED = [math]::Round(($t-$att).TotalSeconds,1) }
      $att = $null
    }
  }
  "--- Waktu ATTACHED ke OPENED"
  $rows | Format-Table -AutoSize
  "connect-retry: " + @($lines | Select-String 'connect-retry').Count
  "OPENED-ignored: " + @($lines | Select-String 'OPENED-ignored').Count
  "decision-retry-suppressed: " + @($lines | Select-String 'decision-retry-suppressed').Count
  "Skipped frames (thread utama): " + @($lines | Select-String 'Skipped \d+ frames').Count
  Crash
}

"Alat Clavo dimuat. Perintah: Sambung, Pairing, Crash, Pasang, Rekam, UjiSemua, Analisis"

# ---- Tambahan v2 ----
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

function Pastikan {
  if (-not $env:ANDROID_SERIAL -and (Test-Path $alamatFile)) { $env:ANDROID_SERIAL = (Get-Content $alamatFile -Raw).Trim() }
}

function Pasang($berkas = "C:\dev\apk\apk_B2_putar7.apk") {
  if (-not (Test-Path $berkas)) { "Berkas tidak ada: $berkas"; return }
  Pastikan
  $hashPC = (Get-FileHash $berkas -Algorithm SHA256).Hash.ToLower()
  "Ukuran: " + (Get-Item $berkas).Length
  "SHA-256 PC: $hashPC"
  "Ruang /data di HP:"
  & $adb shell df -h /data
  "--- adb install ---"
  $out = (& $adb install -r $berkas 2>&1 | Out-String).Trim()
  $out
  if ($out -notmatch 'Success') {
    "--- Jalur cadangan: push lalu pm install ---"
    & $adb push $berkas /data/local/tmp/clavo.apk
    (& $adb shell pm install -r /data/local/tmp/clavo.apk 2>&1 | Out-String).Trim()
    & $adb shell rm -f /data/local/tmp/clavo.apk
  }
  $p = ((& $adb shell pm path $pkg | Select-Object -First 1) | Out-String).Trim() -replace '^package:',''
  if (-not $p) { "Aplikasi tidak terpasang."; return }
  $hashHP = ((& $adb shell sha256sum $p | Out-String).Trim().Split(' ')[0])
  "SHA-256 HP: $hashHP"
  "Cocok dengan PC: " + ($hashHP -eq $hashPC)
}

function PasangKabel($berkas = "C:\dev\apk\apk_B2_putar7.apk") {
  $s = $env:ANDROID_SERIAL
  Remove-Item Env:ANDROID_SERIAL -ErrorAction SilentlyContinue
  & $adb devices -l
  & $adb -d install -r $berkas
  $env:ANDROID_SERIAL = $s
}

function Status {
  Pastikan
  $m = (& $adb shell getprop ro.product.model | Out-String).Trim()
  if (-not $m) { "HP tidak menjawab. Jalankan Sambung."; return }
  "HP: $m  ($env:ANDROID_SERIAL)"
  $p = ((& $adb shell pm path $pkg | Select-Object -First 1) | Out-String).Trim() -replace '^package:',''
  if (-not $p) { "Aplikasi: TIDAK terpasang" }
  else {
    $h = ((& $adb shell sha256sum $p | Out-String).Trim().Split(' ')[0])
    $nama = switch ($h) {
      'a23e60661f4949c5fb60d7d2bbce85a7834cc04629a971272eaab6e10e60b85a' { 'putar6' }
      'b70c585de36cb7bb918bff188c37c12e7ddef47c263625ea53fec0e527dee5f9' { 'putar7' }
      default { 'tidak dikenal' }
    }
    "Aplikasi terpasang: $nama"
  }
  Crash
}

function Kode { Set-Location C:\dev\Clavo; claude -c }

function Bantuan {
  "Sambung    : hubungkan HP (Enter = alamat terakhir; ketik port saja bila berubah)"
  "Pairing    : pairing ulang bila perlu"
  "Status     : model HP, versi APK terpasang, jumlah crash"
  "Pasang     : pasang putar7 lewat Wi-Fi (dengan diagnosis)"
  "PasangKabel: pasang lewat kabel USB ke PC (lepas OTG webcam dulu)"
  "Rekam      : rekam log uji (jendela 2). Ctrl+C untuk berhenti"
  "UjiSemua   : jalankan enam kondisi uji (jendela 1)"
  "Analisis   : hitung hasil dari log terakhir"
  "Kode       : buka Claude Code di C:\dev\Clavo (lanjutkan sesi)"
}

Pastikan
"Tambahan v2 dimuat. Ketik Bantuan untuk daftar perintah."

function Analisis2($berkas) {
  if (-not $berkas) { $berkas = (Get-ChildItem C:\dev\uji\fisik2_*.log | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName }
  $lines = Get-Content $berkas
  "Berkas: $berkas"
  $pids = @($lines | Where-Object { $_ -match ' ClavoUSB:' } | ForEach-Object { ($_ -split '\s+')[2] } | Sort-Object -Unique)
  "Proses aplikasi (pid): " + ($pids -join ', ') + "  => " + $pids.Count + " proses"
  "Removed device: " + @($lines | Select-String 'Removed device at \S+: ').Count
  "ATTACHED: " + @($lines | Select-String 'ClavoUSB: ATTACHED').Count
  "decision-stall: " + @($lines | Select-String 'decision-stall').Count
  "decision-retry: " + @($lines | Select-String 'decision-retry ').Count
  "connect-gave-up: " + @($lines | Select-String 'connect-gave-up').Count
  "TIMEOUT: " + @($lines | Select-String 'connect-result .* TIMEOUT').Count
  "Failed to set preview size: " + @($lines | Select-String 'Failed to set preview size').Count
  "Skipped frames (hanya Clavo): " + @($lines | Where-Object { $_ -match 'Skipped \d+ frames' -and $pids -contains ($_ -split '\s+')[2] }).Count
  Crash
}

function Analisis2($berkas) {
  if (-not $berkas) { $berkas = (Get-ChildItem C:\dev\uji\fisik2_*.log | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName }
  $lines = Get-Content $berkas
  "Berkas: $berkas"
  $pids = @($lines | Where-Object { $_ -match ' ClavoUSB:' } | ForEach-Object { ($_ -split '\s+')[2] } | Sort-Object -Unique)
  "Proses aplikasi (pid): " + ($pids -join ', ') + "  => " + $pids.Count + " proses"
  "Removed device: " + @($lines | Select-String 'Removed device at \S+: ').Count
  "ATTACHED: " + @($lines | Select-String 'ClavoUSB: ATTACHED').Count
  "decision-stall: " + @($lines | Select-String 'decision-stall').Count
  "decision-retry: " + @($lines | Select-String 'decision-retry ').Count
  "connect-gave-up: " + @($lines | Select-String 'connect-gave-up').Count
  "TIMEOUT: " + @($lines | Select-String 'connect-result .* TIMEOUT').Count
  "Failed to set preview size: " + @($lines | Select-String 'Failed to set preview size').Count
  "Skipped frames (hanya Clavo): " + @($lines | Where-Object { $_ -match 'Skipped \d+ frames' -and $pids -contains ($_ -split '\s+')[2] }).Count
  Crash
}

$global:crashDasar = 5

function Crash {
  $d = & $adb shell dumpsys dropbox --print data_app_native_crash | Out-String
  if (-not $d.Trim()) { "HP tidak menjawab. Jalankan Sambung dulu."; return }
  $n = ([regex]::Matches($d, 'Process: ' + [regex]::Escape($pkg))).Count
  "Crash natif tercatat untuk aplikasi: $n   (dasar: $crashDasar; tambahan: $($n - $crashDasar))"
  & $adb shell dumpsys dropbox --print data_app_native_crash | Select-String -Pattern 'data_app_native_crash \(text' | Select-Object -Last 8
}

function LihatCrash($waktu = "23:32:21") {
  $d = & $adb shell dumpsys dropbox --print data_app_native_crash | Out-String
  $i = $d.IndexOf("2026-11-21 $waktu data_app_native_crash")
  if ($i -lt 0) { "Entri $waktu tidak ditemukan. Entri yang ada:"; Crash; return }
  $e = $d.Substring($i, [Math]::Min(9000, $d.Length - $i))
  ($e -split "`r?`n") | Where-Object { $_ -match 'Timestamp|Process uptime|^pid: |signal |Abort message|#\d\d pc' } | Select-Object -First 26 | ForEach-Object { $_.Substring(0,[Math]::Min(200,$_.Length)) }
}

function Status {
  Pastikan
  $m = (& $adb shell getprop ro.product.model | Out-String).Trim()
  if (-not $m) { "HP tidak menjawab. Jalankan Sambung."; return }
  "HP: $m  ($env:ANDROID_SERIAL)"
  $p = ((& $adb shell pm path $pkg | Select-Object -First 1) | Out-String).Trim() -replace '^package:',''
  if (-not $p) { "Aplikasi: TIDAK terpasang" }
  else {
    $h = ((& $adb shell sha256sum $p | Out-String).Trim().Split(' ')[0])
    $nama = switch ($h) {
      'a23e60661f4949c5fb60d7d2bbce85a7834cc04629a971272eaab6e10e60b85a' { 'putar6' }
      'b70c585de36cb7bb918bff188c37c12e7ddef47c263625ea53fec0e527dee5f9' { 'putar7' }
      '386389e1b31c0a0b797bb478f29229663aeba508c126884cf35ec92104c16127' { 'putar8' }
      default { 'tidak dikenal' }
    }
    "Aplikasi terpasang: $nama"
  }
  Crash
}

$global:daftarApk = @{
  'a23e60661f4949c5fb60d7d2bbce85a7834cc04629a971272eaab6e10e60b85a' = 'putar6'
  'b70c585de36cb7bb918bff188c37c12e7ddef47c263625ea53fec0e527dee5f9' = 'putar7'
  '386389e1b31c0a0b797bb478f29229663aeba508c126884cf35ec92104c16127' = 'putar8'
  'ec609713f69e499d87e1fb94ec44083a6b078fb387178245310343ffc4ef6283' = 'putar9'
}

function Tandai($t = "zoom") { & $adb shell log -t UJI $t }

function Status {
  Pastikan
  $m = (& $adb shell getprop ro.product.model | Out-String).Trim()
  if (-not $m) { "HP tidak menjawab. Jalankan Sambung."; return }
  "HP: $m  ($env:ANDROID_SERIAL)"
  $p = ((& $adb shell pm path $pkg | Select-Object -First 1) | Out-String).Trim() -replace '^package:',''
  if (-not $p) { "Aplikasi: TIDAK terpasang" }
  else {
    $h = ((& $adb shell sha256sum $p | Out-String).Trim().Split(' ')[0])
    $nama = $daftarApk[$h]; if (-not $nama) { $nama = 'tidak dikenal' }
    "Aplikasi terpasang: $nama"
  }
  Crash
}

$global:daftarApk['f729c4e667b2aa645035dfdfe1fb675d01e6d3583104cc42a3933d786a6094da'] = 'putar10'

function Rekam {
  $global:g = "C:\dev\uji\fisik2_$(Get-Date -Format yyyyMMdd_HHmmss).log"
  "Merekam ke $g   (Ctrl+C untuk berhenti)"
  & $adb logcat -c
  & $adb logcat -v threadtime UJI:I ClavoUSB:D MultiCameraClient:D USBMonitor:I UsbHostManager:D Choreographer:I AndroidRuntime:E DEBUG:F *:S | Tee-Object -FilePath $g | Select-String -Pattern 'UJI|ATTACHED|DETACHED|decision|connect-|teardown|aborted|render-size|FATAL|Fatal'
}

$global:daftarApk['f729c4e667b2aa645035dfdfe1fb675d01e6d3583104cc42a3933d786a6094da'] = 'putar10'

function Rekam {
  $global:g = "C:\dev\uji\fisik2_$(Get-Date -Format yyyyMMdd_HHmmss).log"
  "Merekam ke $g   (Ctrl+C untuk berhenti)"
  & $adb logcat -c
  & $adb logcat -v threadtime UJI:I ClavoUSB:D MultiCameraClient:D USBMonitor:I UsbHostManager:D Choreographer:I AndroidRuntime:E DEBUG:F *:S | Tee-Object -FilePath $g | Select-String -Pattern 'UJI|ATTACHED|DETACHED|decision|connect-|teardown|aborted|render-size|FATAL|Fatal'
}

$global:daftarApk['f729c4e667b2aa645035dfdfe1fb675d01e6d3583104cc42a3933d786a6094da'] = 'putar10'

function Rekam {
  $global:g = "C:\dev\uji\fisik2_$(Get-Date -Format yyyyMMdd_HHmmss).log"
  "Merekam ke $g   (Ctrl+C untuk berhenti)"
  & $adb logcat -c
  & $adb logcat -v threadtime UJI:I ClavoUSB:D MultiCameraClient:D USBMonitor:I UsbHostManager:D Choreographer:I AndroidRuntime:E DEBUG:F *:S | Tee-Object -FilePath $g | Select-String -Pattern 'UJI|ATTACHED|DETACHED|decision|connect-|teardown|aborted|render-size|FATAL|Fatal'
}

function Pasang($berkas = "C:\dev\apk\apk_B2_putar10.apk") {
  if (-not (Test-Path $berkas)) { "Berkas tidak ada: $berkas"; return }
  Pastikan
  $m = (& $adb shell getprop ro.product.model 2>$null | Out-String).Trim()
  if (-not $m) { "HP tidak menjawab. Jalankan Sambung dulu (ketik port baru dari layar Wireless debugging)."; return }
  $hashPC = (Get-FileHash $berkas -Algorithm SHA256).Hash.ToLower()
  "Ukuran: " + (Get-Item $berkas).Length
  "SHA-256 PC: $hashPC"
  "--- adb install ---"
  $out = (& $adb install -r $berkas 2>&1 | Out-String).Trim()
  $out
  if ($out -notmatch 'Success') {
    "--- Jalur cadangan: push lalu pm install ---"
    & $adb push $berkas /data/local/tmp/clavo.apk
    (& $adb shell pm install -r /data/local/tmp/clavo.apk 2>&1 | Out-String).Trim()
    & $adb shell rm -f /data/local/tmp/clavo.apk
  }
  $p = ((& $adb shell pm path $pkg | Select-Object -First 1) | Out-String).Trim() -replace '^package:',''
  if (-not $p) { "Aplikasi tidak terpasang."; return }
  $hashHP = ((& $adb shell sha256sum $p | Out-String).Trim().Split(' ')[0])
  "SHA-256 HP: $hashHP"
  "Cocok dengan PC: " + ($hashHP -eq $hashPC)
}

function Kirim($tugas) {
  $kf = "C:\dev\uji\konteks_claude.txt"; $tf = "C:\dev\uji\tugas_$tugas.txt"
  if (-not (Test-Path $kf)) { "Tidak ada $kf"; return }
  if (-not (Test-Path $tf)) { "Tidak ada $tf. Tugas yang ada:"; Get-ChildItem C:\dev\uji\tugas_*.txt | ForEach-Object { $_.BaseName -replace '^tugas_','' }; return }
  $isi = (Get-Content $kf -Raw) + "`n`n" + (Get-Content $tf -Raw)
  Set-Location C:\dev\Clavo
  claude $isi
}

function SegarkanHash {
  $csv = "C:\dev\uji\hash_apk.csv"
  $ada = @{}
  if (Test-Path $csv) { Import-Csv $csv | ForEach-Object { $ada[$_.berkas] = $_.sha256 } }
  Get-ChildItem C:\dev\apk\*.apk | ForEach-Object {
    if (-not $ada.ContainsKey($_.Name)) { $ada[$_.Name] = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower() }
  }
  $ada.GetEnumerator() | ForEach-Object { [pscustomobject]@{ berkas = $_.Key; sha256 = $_.Value } } | Export-Csv $csv -NoTypeInformation
  $global:daftarApk = @{}
  $ada.GetEnumerator() | ForEach-Object { $global:daftarApk[$_.Value] = $_.Key }
}

function Status {
  Pastikan
  $m = (& $adb shell getprop ro.product.model 2>$null | Out-String).Trim()
  if (-not $m) { "HP tidak menjawab. Jalankan Sambung."; return }
  "HP: $m  ($env:ANDROID_SERIAL)"
  $p = ((& $adb shell pm path $pkg | Select-Object -First 1) | Out-String).Trim() -replace '^package:',''
  if (-not $p) { "Aplikasi: TIDAK terpasang" }
  else {
    $h = ((& $adb shell sha256sum $p | Out-String).Trim().Split(' ')[0])
    $nama = $daftarApk[$h]
    if (-not $nama) { SegarkanHash; $nama = $daftarApk[$h] }
    if (-not $nama) { $nama = 'tidak dikenal' }
    "Aplikasi terpasang: $nama"
  }
  Crash
}

function UjiVarian($v, $commit = "1f616a7") {
  Pastikan
  $app = "C:\dev\apk\apk_${v}_$commit.apk"; $tesApk = "C:\dev\apk\apk_tes_$commit.apk"
  foreach ($f in $app, $tesApk) { if (-not (Test-Path $f)) { "Tidak ada $f"; return } }
  $m = (& $adb shell getprop ro.product.model 2>$null | Out-String).Trim()
  if (-not $m) { "HP tidak menjawab. Jalankan Sambung."; return }
  $dir = "/sdcard/Android/data/$pkg/files"
  $gambar = "C:\dev\uji_cengkeh"
  $runner = "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
  "--- Pasang aplikasi varian $v"; & $adb install -r $app
  "--- Pasang APK tes"; & $adb install -r -t $tesApk
  if (Test-Path $gambar) { "--- Dorong gambar"; & $adb push $gambar "$dir/" } else { "Folder $gambar tidak ada: regresi dilewati; latensi memakai gambar bawaan." }
  "--- Baterai dan suhu sebelum"; & $adb shell dumpsys battery | Select-String 'level|temperature'
  "--- Latensi"; & $adb shell am instrument -w -e class "$pkg.LatencyBenchmarkTest" -e label $v $runner
  if (Test-Path $gambar) { "--- Keluaran Cengkeh"; & $adb shell am instrument -w -e class "$pkg.CengkehOutputDumpTest" -e label $v $runner }
  "--- Baterai dan suhu sesudah"; & $adb shell dumpsys battery | Select-String 'level|temperature'
  New-Item -ItemType Directory -Force C:\dev\uji\hasil | Out-Null
  & $adb pull "$dir/latensi_$v.csv" C:\dev\uji\hasil\
  if (Test-Path $gambar) { & $adb pull "$dir/cengkeh_out_$v.txt" C:\dev\uji\hasil\ }
  "Hasil ada di C:\dev\uji\hasil"
}

function KirimWeb($tugas) {
  $kf = "C:\dev\uji\konteks_web.txt"; $tf = "C:\dev\uji\tugas_$tugas.txt"
  if (-not (Test-Path $kf)) { "Tidak ada $kf"; return }
  if (-not (Test-Path $tf)) { "Tidak ada $tf"; return }
  $isi = (Get-Content $kf -Raw) + "`n`n" + (Get-Content $tf -Raw)
  Set-Location C:\dev\Clavo-Web
  claude $isi
}

function Versi {
  Pastikan
  & $adb shell dumpsys package $pkg | Select-String 'versionName=|versionCode=' | Select-Object -First 2 | ForEach-Object { $_.Line.Trim() }
}

function UjiVarian($v) {
  Pastikan
  $m = (& $adb shell getprop ro.product.model 2>$null | Out-String).Trim()
  if (-not $m) { "HP tidak menjawab. Jalankan Sambung."; return }
  $berkasApp = Get-ChildItem "C:\dev\apk\arsip\apk_${v}_v*.apk" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $berkasApp) { "Tidak ada APK varian $v di C:\dev\apk\arsip"; return }
  $sha = ($berkasApp.BaseName -split '_')[-1]
  $berkasTes = Get-ChildItem "C:\dev\apk\arsip\apk_tes_$sha.apk","C:\dev\apk\apk_tes_$sha.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $berkasTes) { "Tidak ada APK tes untuk commit $sha"; return }
  $dir = "/sdcard/Android/data/$pkg/files"
  $gambar = "C:\dev\uji_cengkeh"
  $runner = "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
  "--- Pasang $($berkasApp.Name)"; & $adb install -r $berkasApp.FullName
  "--- Pasang $($berkasTes.Name)"; & $adb install -r -t $berkasTes.FullName
  "--- Versi terpasang"; Versi
  if (Test-Path $gambar) { "--- Dorong gambar"; & $adb push $gambar "$dir/" } else { "Folder $gambar tidak ada: regresi dilewati." }
  "--- Baterai dan suhu sebelum"; & $adb shell dumpsys battery | Select-String 'level|temperature'
  & $adb logcat -c
  "--- Latensi"; & $adb shell am instrument -w -e class "$pkg.LatencyBenchmarkTest" -e label $v $runner
  if (Test-Path $gambar) { "--- Keluaran Cengkeh"; & $adb shell am instrument -w -e class "$pkg.CengkehOutputDumpTest" -e label $v $runner }
  "--- Baterai dan suhu sesudah"; & $adb shell dumpsys battery | Select-String 'level|temperature'
  New-Item -ItemType Directory -Force C:\dev\uji\hasil | Out-Null
  & $adb pull "$dir/latensi_$v.csv" C:\dev\uji\hasil\
  if (Test-Path $gambar) { & $adb pull "$dir/cengkeh_out_$v.txt" C:\dev\uji\hasil\ }
  & $adb logcat -d -s ClavoTest | Out-File "C:\dev\uji\hasil\clavotest_$v.log" -Encoding utf8
  "Hasil ada di C:\dev\uji\hasil"
}

function SegarkanHash {
  $csv = "C:\dev\uji\hash_apk.csv"
  $ada = @{}
  if (Test-Path $csv) { Import-Csv $csv | ForEach-Object { $ada[$_.berkas] = $_.sha256 } }
  Get-ChildItem C:\dev\apk\*.apk, C:\dev\apk\arsip\*.apk -ErrorAction SilentlyContinue | ForEach-Object {
    if (-not $ada.ContainsKey($_.Name)) { $ada[$_.Name] = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower() }
  }
  $ada.GetEnumerator() | ForEach-Object { [pscustomobject]@{ berkas = $_.Key; sha256 = $_.Value } } | Export-Csv $csv -NoTypeInformation
  $global:daftarApk = @{}
  $ada.GetEnumerator() | ForEach-Object { $global:daftarApk[$_.Value] = $_.Key }
}

function SiapkanGambar {
  Pastikan
  $dir = "/sdcard/Android/data/$pkg/files"
  $lokal = "C:\dev\uji_cengkeh"
  if (-not (Test-Path $lokal)) { "Folder $lokal tidak ada."; return }
  & $adb shell mkdir -p "$dir"
  & $adb shell rm -rf "$dir/uji_cengkeh"
  & $adb push $lokal "$dir/"
  "Jumlah berkas gambar di HP:"
  & $adb shell "ls $dir/uji_cengkeh | wc -l"
}

function RingkasDump($v) {
  $f = "C:\dev\uji\hasil\cengkeh_out_$v.txt"
  if (-not (Test-Path $f)) { "Tidak ada $f"; return }
  $nama = $null; $hit = [ordered]@{}
  foreach ($l in (Get-Content $f)) {
    if ($l -match '^FILE:\s*(.+)$') { $nama = $Matches[1].Trim(); $hit[$nama] = 0 }
    elseif ($nama -and $l -match '\|.*\|') { $hit[$nama] = $hit[$nama] + 1 }
  }
  $kosong = @($hit.GetEnumerator() | Where-Object { $_.Value -eq 0 })
  "Varian ${v}: gambar=" + $hit.Count + "; total deteksi=" + (($hit.Values | Measure-Object -Sum).Sum) + "; gambar tanpa deteksi=" + $kosong.Count
  if ($kosong.Count) { "Tanpa deteksi: " + (($kosong | ForEach-Object { $_.Key }) -join ', ') }
}

function LatensiUlang($v, $n = 3) {
  Pastikan
  $dir = "/sdcard/Android/data/$pkg/files"
  $runner = "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
  New-Item -ItemType Directory -Force C:\dev\uji\hasil | Out-Null
  for ($i = 1; $i -le $n; $i++) {
    "=== Ulangan $i dari $n (varian $v)"
    & $adb shell dumpsys battery | Select-String 'powered|level|temperature'
    & $adb shell am instrument -w -e class "$pkg.LatencyBenchmarkTest" -e label "${v}_r$i" $runner | Select-String 'OK|FAIL|Time'
    & $adb pull "$dir/latensi_${v}_r$i.csv" C:\dev\uji\hasil\ | Out-Null
    if ($i -lt $n) { Start-Sleep 30 }
  }
  Get-ChildItem "C:\dev\uji\hasil\latensi_${v}_r*.csv" | ForEach-Object { Import-Csv $_.FullName | Where-Object { $_.delegasi -eq 'CPU' } } | Format-Table label, rata2_ms, median_ms, p95_ms, min_ms, maks_ms -AutoSize
}

function SalinGambarCengkeh($sumber = "C:\dev\uji\unduhan_clovev") {
  $tujuan = "C:\dev\uji_cengkeh"
  New-Item -ItemType Directory -Force $tujuan | Out-Null
  if ((Get-ChildItem $tujuan -File).Count -gt 0) { "Folder $tujuan tidak kosong. Kosongkan dulu."; return }
  $semua = Get-ChildItem $sumber -Recurse -File -Include *.jpg,*.jpeg,*.png
  $unik = $semua | Group-Object { ($_.Name -split '\.rf\.')[0] } | ForEach-Object { $_.Group | Sort-Object FullName | Select-Object -First 1 }
  $i = 1; $peta = @()
  foreach ($f in ($unik | Sort-Object FullName)) {
    $baru = "cengkeh_{0:D3}{1}" -f $i, $f.Extension.ToLower()
    Copy-Item $f.FullName (Join-Path $tujuan $baru)
    $peta += [pscustomobject]@{ baru = $baru; asli = $f.Name; folder = $f.DirectoryName.Replace($sumber, '') }
    $i++
  }
  $peta | Export-Csv C:\dev\uji\peta_nama_cengkeh.csv -NoTypeInformation
  "Berkas gambar di sumber: " + @($semua).Count + "; unik: " + @($unik).Count + "; disalin: " + ($i - 1)
}

function SuhuBaterai {
  $s = (& $adb shell dumpsys battery | Select-String 'temperature' | Select-Object -First 1).Line
  [double](($s -replace '\D','')) / 10
}

function TungguDingin($batas = 35.0, $maksMenit = 10) {
  $mulai = Get-Date
  while ($true) {
    $t = SuhuBaterai
    if ($t -le $batas) { "Suhu baterai $t C (batas $batas): lanjut."; return }
    if (((Get-Date) - $mulai).TotalMinutes -ge $maksMenit) { "Batas tunggu $maksMenit menit habis; suhu $t C. Lanjut dengan catatan."; return }
    "Suhu baterai $t C lebih dari $batas; tunggu 30 dtk..."
    Start-Sleep 30
  }
}

function RingkasLatensi($v) {
  $baris = Get-ChildItem "C:\dev\uji\hasil\latensi_${v}_r*.csv" | ForEach-Object { Import-Csv $_.FullName } | Where-Object { $_.delegasi -eq 'CPU' }
  $baris | Format-Table label, model, rata2_ms, median_ms, p95_ms, min_ms, maks_ms -AutoSize
  $baris | Group-Object model | ForEach-Object {
    $m = $_.Group | ForEach-Object { [double]$_.rata2_ms }
    [pscustomobject]@{ varian = $v; model = $_.Name; ulangan = $m.Count; rata2_dari_rata2 = [math]::Round(($m | Measure-Object -Average).Average, 1); terendah = [math]::Round(($m | Measure-Object -Minimum).Minimum, 1); tertinggi = [math]::Round(($m | Measure-Object -Maximum).Maximum, 1) }
  } | Format-Table -AutoSize
}

function LatensiUlang($v, $n = 3) {
  if (-not $v) { "Pakai: LatensiUlang A 3   (nama varian wajib)"; return }
  Pastikan
  $dir = "/sdcard/Android/data/$pkg/files"
  $runner = "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
  New-Item -ItemType Directory -Force C:\dev\uji\hasil | Out-Null
  for ($i = 1; $i -le $n; $i++) {
    "=== Ulangan $i dari $n (varian $v)"
    TungguDingin
    & $adb shell dumpsys battery | Select-String 'powered: true|level|temperature'
    & $adb shell am instrument -w -e class "$pkg.LatencyBenchmarkTest" -e label "${v}_r$i" $runner | Select-String 'OK|FAIL|Time'
    & $adb pull "$dir/latensi_${v}_r$i.csv" C:\dev\uji\hasil\ | Out-Null
    & $adb shell dumpsys battery | Select-String 'temperature'
  }
  RingkasLatensi $v
}

function PasangVarian($v) {
  Pastikan
  $berkasApp = Get-ChildItem "C:\dev\apk\arsip\apk_${v}_v*.apk" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $berkasApp) { "Tidak ada APK varian $v"; return }
  $sha = ($berkasApp.BaseName -split '_')[-1]
  $berkasTes = Get-ChildItem "C:\dev\apk\arsip\apk_tes_$sha.apk","C:\dev\apk\apk_tes_$sha.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $berkasTes) { "Tidak ada APK tes untuk commit $sha"; return }
  & $adb install -r $berkasApp.FullName
  & $adb install -r -t $berkasTes.FullName
  Versi
}

function OcrGambar($path) {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  $null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
  $null = [Windows.Storage.FileAccessMode, Windows.Storage, ContentType = WindowsRuntime]
  $null = [Windows.Storage.Streams.IRandomAccessStream, Windows.Storage.Streams, ContentType = WindowsRuntime]
  $null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
  $null = [Windows.Media.Ocr.OcrResult, Windows.Foundation, ContentType = WindowsRuntime]
  $null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics, ContentType = WindowsRuntime]
  $null = [Windows.Graphics.Imaging.SoftwareBitmap, Windows.Graphics, ContentType = WindowsRuntime]
  $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
  $tunggu = { param($op, $tipe) $t = $asTask.MakeGenericMethod($tipe).Invoke($null, @($op)); $t.Wait(-1) | Out-Null; $t.Result }
  $mesin = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
  if (-not $mesin) { return "" }
  $berkas = & $tunggu ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
  $aliran = & $tunggu ($berkas.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
  $dekoder = & $tunggu ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($aliran)) ([Windows.Graphics.Imaging.BitmapDecoder])
  $bmp = & $tunggu ($dekoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
  $hasil = & $tunggu ($mesin.RecognizeAsync($bmp)) ([Windows.Media.Ocr.OcrResult])
  $hasil.Text
}

function UjiOverlay($jumlah = 8) {
  Pastikan
  $keluar = "C:\dev\uji\overlay"
  New-Item -ItemType Directory -Force $keluar | Out-Null
  $laporan = @()
  foreach ($mode in 'terang','gelap') {
    $kode = 'no'; if ($mode -eq 'gelap') { $kode = 'yes' }
    & $adb shell cmd uimode night $kode | Out-Null
    Start-Sleep 2
    & $adb shell am force-stop $pkg
    Start-Sleep 1
    & $adb shell 'rm -f /sdcard/ov_*.png' | Out-Null
    & $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $perintah = 'for i in ' + ((1..$jumlah) -join ' ') + '; do screencap -p /sdcard/ov_$i.png; done'
    & $adb shell $perintah
    $sw.Stop()
    $selang = [math]::Round($sw.Elapsed.TotalSeconds / $jumlah, 2)
    for ($i = 1; $i -le $jumlah; $i++) { & $adb pull "/sdcard/ov_$i.png" "$keluar\${mode}_$i.png" | Out-Null }
    for ($i = 1; $i -le $jumlah; $i++) {
      $teks = ""
      try { $teks = (OcrGambar "$keluar\${mode}_$i.png") -replace '\s+', ' ' } catch { $teks = "(OCR gagal: " + $_.Exception.Message + ")" }
      $laporan += [pscustomobject]@{ mode = $mode; frame = $i; selang_dtk = $selang; overlay = ($teks -match 'made by'); model = ($teks -match 'Model'); navigasi = ($teks -match 'Webcam'); teks = $teks.Substring(0, [Math]::Min(160, $teks.Length)) }
    }
  }
  & $adb shell cmd uimode night auto | Out-Null
  & $adb shell 'rm -f /sdcard/ov_*.png' | Out-Null
  $laporan | Format-Table mode, frame, selang_dtk, overlay, model, navigasi -AutoSize
  $laporan | Export-Csv "$keluar\laporan_overlay.csv" -NoTypeInformation -Encoding UTF8
  "Gambar dan laporan: $keluar"
}

function HpHidup {
  $m = (& $adb shell getprop ro.product.model 2>$null | Out-String).Trim()
  return [bool]$m
}

function SuhuBaterai {
  $s = & $adb shell dumpsys battery 2>$null | Select-String 'temperature' | Select-Object -First 1
  if (-not $s) { return $null }
  [double](($s.Line -replace '\D','')) / 10
}

function TungguDingin($batas = 35.0, $maksMenit = 10) {
  $mulai = Get-Date
  while ($true) {
    $t = SuhuBaterai
    if ($null -eq $t) { "HP tidak menjawab saat membaca suhu."; return }
    if ($t -le $batas) { "Suhu baterai $t C (batas $batas): lanjut."; return }
    if (((Get-Date) - $mulai).TotalMinutes -ge $maksMenit) { "Batas tunggu $maksMenit menit habis; suhu $t C. Lanjut dengan catatan."; return }
    "Suhu baterai $t C lebih dari $batas; tunggu 30 dtk..."
    Start-Sleep 30
  }
}

function LatensiUlang($v, $n = 3) {
  if (-not $v) { "Pakai: LatensiUlang A 3   (nama varian wajib)"; return }
  Pastikan
  $dir = "/sdcard/Android/data/$pkg/files"
  $runner = "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
  New-Item -ItemType Directory -Force C:\dev\uji\hasil | Out-Null
  for ($i = 1; $i -le $n; $i++) {
    if (-not (HpHidup)) { "HP offline. Jalankan Sambung lalu ulangi."; return }
    "=== Ulangan $i dari $n (varian $v)"
    TungguDingin
    & $adb shell dumpsys battery | Select-String 'powered: true|level|temperature'
    & $adb shell am instrument -w -e class "$pkg.LatencyBenchmarkTest" -e label "${v}_r$i" $runner | Select-String 'OK|FAIL|Time'
    & $adb pull "$dir/latensi_${v}_r$i.csv" C:\dev\uji\hasil\ | Out-Null
  }
  RingkasLatensi $v
}

function RingkasPutaran {
  $baris = Get-ChildItem "C:\dev\uji\hasil\latensi_*_p*.csv" | ForEach-Object {
    $v = ($_.BaseName -replace '^latensi_','') -replace '_p\d+$',''
    Import-Csv $_.FullName | Where-Object { $_.delegasi -eq 'CPU' } | ForEach-Object { [pscustomobject]@{ varian = $v; model = $_.model; rata2 = [double]$_.rata2_ms } }
  }
  $baris | Group-Object varian, model | ForEach-Object {
    $m = @($_.Group | ForEach-Object { $_.rata2 })
    $rata = ($m | Measure-Object -Average).Average
    $sd = 0
    if ($m.Count -gt 1) { $sd = [math]::Sqrt((($m | ForEach-Object { ($_ - $rata) * ($_ - $rata) } | Measure-Object -Sum).Sum) / ($m.Count - 1)) }
    [pscustomobject]@{ varian = $_.Group[0].varian; model = $_.Group[0].model; putaran = $m.Count; rata2_ms = [math]::Round($rata,1); sd_ms = [math]::Round($sd,1); min_ms = [math]::Round(($m | Measure-Object -Minimum).Minimum,1); maks_ms = [math]::Round(($m | Measure-Object -Maximum).Maximum,1) }
  } | Sort-Object model, varian | Format-Table -AutoSize
}

function PutaranLatensi($putaran = 3) {
  Pastikan
  $runner = "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
  $dir = "/sdcard/Android/data/$pkg/files"
  New-Item -ItemType Directory -Force C:\dev\uji\hasil | Out-Null
  $dasar = @('A','B1','B2')
  for ($p = 1; $p -le $putaran; $p++) {
    $k = ($p - 1) % 3
    if ($k -eq 0) { $urut = $dasar } else { $urut = $dasar[$k..2] + $dasar[0..($k-1)] }
    foreach ($v in $urut) {
      if (-not (HpHidup)) { "HP offline. Jalankan Sambung lalu ulangi dari putaran $p."; return }
      "=== Putaran $p, varian $v"
      PasangVarian $v | Out-Null
      TungguDingin
      $label = "${v}_p$p"
      & $adb shell am instrument -w -e class "$pkg.LatencyBenchmarkTest" -e label $label $runner | Select-String 'OK|FAIL|Time'
      & $adb pull "$dir/latensi_$label.csv" C:\dev\uji\hasil\ | Out-Null
      "suhu sesudah: " + (SuhuBaterai) + " C"
    }
  }
  RingkasPutaran
}

function RingkasPutaran2 {
  $baris = Get-ChildItem "C:\dev\uji\hasil\latensi_*_p*.csv" | ForEach-Object {
    $v = ($_.BaseName -replace '^latensi_','') -replace '_p\d+$',''
    Import-Csv $_.FullName | Where-Object { $_.delegasi -eq 'CPU' } | ForEach-Object { [pscustomobject]@{ varian = $v; model = $_.model; rata2 = [double]$_.rata2_ms; p95 = [double]$_.p95_ms; maks = [double]$_.maks_ms } }
  }
  $hasil = $baris | Group-Object varian, model | ForEach-Object {
    $m = @($_.Group | ForEach-Object { $_.rata2 })
    $rata = ($m | Measure-Object -Average).Average
    $sd = 0
    if ($m.Count -gt 1) { $sd = [math]::Sqrt((($m | ForEach-Object { ($_ - $rata) * ($_ - $rata) } | Measure-Object -Sum).Sum) / ($m.Count - 1)) }
    [pscustomobject]@{ varian = $_.Group[0].varian; model = ($_.Group[0].model -replace '^efficientdet_lite2_','' -replace '\.tflite$',''); putaran = $m.Count; rata2 = [math]::Round($rata,1); sd = [math]::Round($sd,1); min_rata2 = [math]::Round(($m | Measure-Object -Minimum).Minimum,1); maks_rata2 = [math]::Round(($m | Measure-Object -Maximum).Maximum,1); p95_rata2 = [math]::Round((($_.Group | ForEach-Object { $_.p95 }) | Measure-Object -Average).Average,1); maks_tunggal = [math]::Round((($_.Group | ForEach-Object { $_.maks }) | Measure-Object -Maximum).Maximum,1) }
  } | Sort-Object model, varian
  $hasil | Export-Csv C:\dev\uji\hasil\ringkasan_putaran.csv -NoTypeInformation
  ($hasil | Format-Table -AutoSize | Out-String -Width 220)
}

function PutaranLatensi($putaran = 3, $mulai = 1) {
  Pastikan
  $runner = "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
  $dir = "/sdcard/Android/data/$pkg/files"
  New-Item -ItemType Directory -Force C:\dev\uji\hasil | Out-Null
  $dasar = @('A','B1','B2')
  for ($p = $mulai; $p -lt ($mulai + $putaran); $p++) {
    $k = ($p - 1) % 3
    if ($k -eq 0) { $urut = $dasar } else { $urut = $dasar[$k..2] + $dasar[0..($k-1)] }
    foreach ($v in $urut) {
      if (-not (HpHidup)) { "HP offline. Jalankan Sambung lalu ulangi dari putaran $p."; return }
      "=== Putaran $p, varian $v"
      PasangVarian $v | Out-Null
      TungguDingin
      $label = "${v}_p$p"
      & $adb shell am instrument -w -e class "$pkg.LatencyBenchmarkTest" -e label $label $runner | Select-String 'OK|FAIL|Time'
      & $adb pull "$dir/latensi_$label.csv" C:\dev\uji\hasil\ | Out-Null
      "suhu sesudah: " + (SuhuBaterai) + " C"
    }
  }
  RingkasPutaran2
}
