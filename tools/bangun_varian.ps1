<#
.SYNOPSIS
  Membangun satu varian APK (A, B1, B2) dan APK tesnya, lalu mencatatnya di C:\dev\apk\apk_commit.txt.

.DESCRIPTION
  A  = Cengkeh V2 saja
  B1 = A + mangga v2
  B2 = B1 + mangga v1 (disalin dari models_archive)
  Langkah: periksa git bersih -> atur isi app/src/main/assets/models -> gradlew assembleDebug
  assembleDebugAndroidTest -> periksa isi assets/models di dalam APK -> salin ke C:\dev\apk ->
  catat ukuran, SHA-256, commit -> SELALU pulihkan assets/models (git checkout + git clean) dan
  pastikan git status bersih. Berkas model hasil salinan tidak pernah di-commit.
  Nama APK: apk_<varian>_v<versi>_<sha>.apk (versi = skema tanpa +sha, mis. 1.3.0-ta). Salinan juga
  ditaruh di <KeluaranDir>\arsip\ (ditolak bila nama sudah ada) dan dicatat di arsip\VERSI.md.
  SHA commit dihitung saat pohon masih bersih lalu diteruskan ke gradle (-PclavoGitSha), karena
  assets/models sementara diubah selama build.
  Syarat: pohon kerja git harus bersih di awal (supaya pemulihan tidak menghapus pekerjaan).

.EXAMPLE
  .\tools\bangun_varian.ps1 -Varian B2
#>
param(
    [Parameter(Mandatory = $true)][ValidateSet('A', 'B1', 'B2')][string]$Varian,
    [string]$KeluaranDir = 'C:\dev\apk'
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $repo

$modelDirRel = 'app/src/main/assets/models'
$modelDir = Join-Path $repo $modelDirRel
$cengkeh = 'efficientdet_lite2_cengkeh_V2.tflite'
$mangga2 = 'efficientdet_lite2_mangga_v2.tflite'
$mangga1 = 'efficientdet_lite2_mangga_v1.tflite'
$arsipV1 = Join-Path $repo "models_archive\$mangga1"

$diperlukan = switch ($Varian) {
    'A'  { @($cengkeh) }
    'B1' { @($cengkeh, $mangga2) }
    'B2' { @($cengkeh, $mangga2, $mangga1) }
}
$deskripsi = switch ($Varian) {
    'A'  { 'A = Cengkeh V2 saja' }
    'B1' { 'B1 = Cengkeh V2 + mangga v2' }
    'B2' { 'B2 = Cengkeh V2 + mangga v1 + v2' }
}

function Get-Sha256([string]$path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLower() }

function Assert-GitBersih([string]$konteks) {
    $s = git status --porcelain
    if ($s) { throw "git status tidak bersih ($konteks):`n$($s -join "`n")" }
}

# --- Pemeriksaan awal (belum mengubah apa pun) ---
Assert-GitBersih 'sebelum mulai; commit atau stash dulu'
$commitPendek = (git rev-parse --short HEAD).Trim()
$commitPenuh = (git rev-parse HEAD).Trim()
$cabang = (git rev-parse --abbrev-ref HEAD).Trim()
if (-not (Test-Path -LiteralPath $KeluaranDir -PathType Container)) { throw "Folder keluaran tidak ada: $KeluaranDir" }
$cengkehSha = Get-Sha256 (Join-Path $modelDir $cengkeh)
if (-not ($cengkehSha.StartsWith('3263ad49') -and $cengkehSha.EndsWith('704c3f'))) {
    throw "SHA-256 model Cengkeh V2 tidak sesuai acuan (3263AD49...704C3F): $cengkehSha"
}
if ($Varian -eq 'B2' -and -not (Test-Path -LiteralPath $arsipV1)) { throw "Tidak ada: $arsipV1" }

# versionName dari gradle (skema+sha), dihitung dengan SHA bersih yang sama.
$infoVersi = & (Join-Path $repo 'gradlew.bat') -q printVersionInfo "-PclavoGitSha=$commitPendek" 2>$null
$versionName = ($infoVersi | Where-Object { $_ -like 'versionName=*' } | Select-Object -First 1)
if (-not $versionName) { throw 'Gagal membaca versionName dari gradle (printVersionInfo)' }
$versionName = $versionName.Substring('versionName='.Length).Trim()
$versiDasar = $versionName.Split('+')[0]
$arsipDir = Join-Path $KeluaranDir 'arsip'
$namaApp = "apk_${Varian}_v${versiDasar}_${commitPendek}.apk"
$tujuanArsip = Join-Path $arsipDir $namaApp
if (Test-Path -LiteralPath $tujuanArsip) { throw "Arsip sudah punya $namaApp (tidak menimpa). Commit baru atau pindahkan berkas lama." }
$namaTes = "apk_tes_${commitPendek}.apk"
$berhasil = $false

try {
    # --- Atur isi assets/models: hanya model yang diperlukan ---
    Get-ChildItem -LiteralPath $modelDir -Filter *.tflite | Where-Object { $diperlukan -notcontains $_.Name } |
        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
    foreach ($m in $diperlukan) {
        $tujuan = Join-Path $modelDir $m
        if (-not (Test-Path -LiteralPath $tujuan)) {
            $sumber = if ($m -eq $mangga1) { $arsipV1 } else { throw "Sumber model tidak diketahui: $m" }
            Copy-Item -LiteralPath $sumber -Destination $tujuan
        }
    }
    $isiModel = (Get-ChildItem -LiteralPath $modelDir -Filter *.tflite | Sort-Object Name | ForEach-Object { $_.Name })
    Write-Host "assets/models untuk ${Varian}: $($isiModel -join ', ')"

    # --- Bangun ---
    # Paksa pengemasan APK dari nol. Pengemasan inkremental AGP memakai ulang berkas APK lama dan
    # dapat meninggalkan ruang mati (byte model dari varian sebelumnya) sehingga ukuran/SHA-256
    # APK tidak mencerminkan varian ini. Terbukti: A dibangun setelah B2 = 97.079.785 byte
    # inkremental vs 82.230.457 byte bersih.
    foreach ($apkLama in @('app\build\outputs\apk\debug\app-debug.apk', 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')) {
        Remove-Item -LiteralPath (Join-Path $repo $apkLama) -Force -ErrorAction SilentlyContinue
    }
    Get-ChildItem -LiteralPath (Join-Path $repo 'app\build\intermediates\incremental') -Directory -Filter 'package*Debug*' -ErrorAction SilentlyContinue |
        Remove-Item -Recurse -Force
    & (Join-Path $repo 'gradlew.bat') assembleDebug assembleDebugAndroidTest "-PclavoGitSha=$commitPendek" --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle gagal (exit $LASTEXITCODE)" }

    $apkApp = Join-Path $repo 'app\build\outputs\apk\debug\app-debug.apk'
    $apkTes = Join-Path $repo 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    foreach ($p in @($apkApp, $apkTes)) {
        if (-not (Test-Path -LiteralPath $p)) { throw "APK tidak ditemukan: $p" }
    }

    # --- Periksa versi yang masuk ke build ---
    $bc = Get-Content -LiteralPath (Join-Path $repo 'app\build\generated\source\buildConfig\debug\com\google\mediapipe\examples\objectdetection\BuildConfig.java') -Raw
    if ($bc -notmatch [regex]::Escape("VERSION_NAME = `"$versionName`"")) { throw "BuildConfig.VERSION_NAME tidak sama dengan $versionName" }

    # --- Periksa isi assets/models di dalam APK ---
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($apkApp)
    try {
        # Ruang mati: ukuran berkas dikurangi jumlah isi entri. APK bersih ~0,4 MB (header zip).
        $jumlahIsi = ($zip.Entries | Measure-Object -Property CompressedLength -Sum).Sum
        $ruangMati = (Get-Item -LiteralPath $apkApp).Length - $jumlahIsi
        if ($ruangMati -gt 2MB) { throw "APK mengandung ruang mati $ruangMati byte (pengemasan inkremental); ukuran tidak sah" }
        $didalam = @($zip.Entries | Where-Object { $_.FullName -like 'assets/models/*.tflite' } |
            ForEach-Object { $_.Name } | Sort-Object)
    } finally { $zip.Dispose() }
    if (($didalam -join '|') -ne ($isiModel -join '|')) {
        throw "Isi APK tidak sesuai. Diharapkan: $($isiModel -join ', '); di APK: $($didalam -join ', ')"
    }

    # --- Salin dan catat ---
    $tujuanApp = Join-Path $KeluaranDir $namaApp
    $tujuanTes = Join-Path $KeluaranDir $namaTes
    Copy-Item -LiteralPath $apkApp -Destination $tujuanApp -Force
    Copy-Item -LiteralPath $apkTes -Destination $tujuanTes -Force
    New-Item -ItemType Directory -Force -Path $arsipDir | Out-Null
    if (Test-Path -LiteralPath $tujuanArsip) { throw "Arsip sudah punya $namaApp (tidak menimpa)" }
    Copy-Item -LiteralPath $apkApp -Destination $tujuanArsip
    $ukApp = (Get-Item -LiteralPath $tujuanApp).Length; $shaApp = Get-Sha256 $tujuanApp
    if ((Get-Sha256 $tujuanArsip) -ne $shaApp) { throw 'Salinan arsip tidak identik' }
    $versiMd = Join-Path $arsipDir 'VERSI.md'
    if (-not (Test-Path -LiteralPath $versiMd)) {
        [System.IO.File]::WriteAllText($versiMd, "# Arsip versi APK Clavo$([Environment]::NewLine)$([Environment]::NewLine)| tanggal | versionName | commit | varian | ukuran (byte) | SHA-256 | catatan |$([Environment]::NewLine)|---|---|---|---|---|---|---|$([Environment]::NewLine)", (New-Object System.Text.UTF8Encoding($false)))
    }
    $subjek = ((git log -1 --format=%s).Trim()) -replace '\|', '/'
    $baris = "| $(Get-Date -Format 'yyyy-MM-dd') | $versionName | $commitPendek | $Varian | $ukApp | $shaApp | $subjek |$([Environment]::NewLine)"
    [System.IO.File]::AppendAllText($versiMd, $baris, (New-Object System.Text.UTF8Encoding($false)))
    $ukTes = (Get-Item -LiteralPath $tujuanTes).Length; $shaTes = Get-Sha256 $tujuanTes

    $catatan = Join-Path $KeluaranDir 'apk_commit.txt'
    $isiCatatan = if (Test-Path -LiteralPath $catatan) { [System.IO.File]::ReadAllText($catatan) } else { '' }
    $nl = [Environment]::NewLine
    $entri = "$nl$namaApp$nl" +
        "size_bytes: $ukApp$nl" +
        "sha256: $shaApp$nl" +
        "commit: $commitPenuh (branch $cabang)$nl" +
        "variant: $deskripsi, debug, JDK 17$nl" +
        "build note: tree at that commit with assets/models set to [$($isiModel -join ', ')] during the build" +
        $(if ($Varian -eq 'B2') { " (mangga v1 copied from models_archive)" } else { '' }) +
        "; restored afterwards, not committed; APK content checked ($($didalam.Count) tflite in assets/models)$nl"
    if (-not $isiCatatan.Contains("sha256: $shaTes")) {
        $entri += "$nl$namaTes$nl" +
            "size_bytes: $ukTes$nl" +
            "sha256: $shaTes$nl" +
            "commit: $commitPenuh (branch $cabang)$nl" +
            "variant: androidTest APK (CengkehOutputDumpTest, LatencyBenchmarkTest, ...), debug, JDK 17$nl"
    }
    [System.IO.File]::AppendAllText($catatan, $entri, (New-Object System.Text.UTF8Encoding($false)))

    Write-Host ""
    Write-Host "arsip: $tujuanArsip"
    Write-Host "$namaApp  $ukApp byte  sha256=$shaApp"
    Write-Host "$namaTes  $ukTes byte  sha256=$shaTes"
    $berhasil = $true
}
finally {
    # Pulihkan assets/models ke keadaan commit: berkas terlacak yang dihapus/diubah kembali,
    # berkas hasil salinan (tidak terlacak) dihapus.
    git checkout -- $modelDirRel
    git clean -fd -- $modelDirRel | Out-Null
    $sisa = git status --porcelain
    if ($sisa) {
        Write-Host "PERINGATAN: git status TIDAK bersih setelah pemulihan:"
        $sisa | ForEach-Object { Write-Host "  $_" }
        $berhasil = $false
    } else {
        Write-Host "assets/models dipulihkan; git status bersih."
    }
}
if (-not $berhasil) { exit 1 }
exit 0
