<#
.SYNOPSIS
  Membandingkan dua berkas keluaran CengkehOutputDumpTest (acuan vs kandidat).

.DESCRIPTION
  Format berkas (per gambar satu blok):
    FILE: nama.jpg
    kelas|skor|left,top,right,bottom
    (baris kosong)
  Per gambar diperiksa:
    1. jumlah deteksi sama
    2. kelas sama (multiset kelas)
    3. selisih skor <= 0,005
    4. selisih tiap sisi kotak <= 2 px
  Deteksi dipasangkan per kelas dengan kotak terdekat (bukan urutan baris), supaya jitter skor
  tidak menukar pasangan. Gambar yang hanya ada di salah satu berkas dihitung pelanggaran.
  Kode keluar: 0 = semua lulus, 1 = ada pelanggaran, 2 = berkas/format bermasalah.

.EXAMPLE
  .\tools\bandingkan_cengkeh.ps1 -Acuan cengkeh_out_A.txt -Kandidat cengkeh_out_B2.txt
#>
param(
    [Parameter(Mandatory = $true)][string]$Acuan,
    [Parameter(Mandatory = $true)][string]$Kandidat,
    [double]$ToleransiSkor = 0.005,
    [int]$ToleransiPiksel = 2
)

$ErrorActionPreference = 'Stop'
$inv = [System.Globalization.CultureInfo]::InvariantCulture
$eps = 1e-9

function Read-Keluaran([string]$path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Berkas tidak ditemukan: $path" }
    $map = [ordered]@{}
    $cur = $null
    $n = 0
    foreach ($raw in [System.IO.File]::ReadAllLines((Resolve-Path -LiteralPath $path).Path)) {
        $n++
        $line = $raw.Trim()
        if ($line -eq '') { continue }
        if ($line.StartsWith('FILE: ')) {
            $cur = $line.Substring(6)
            if ($map.Contains($cur)) { throw "${path}:${n}: nama gambar ganda '$cur'" }
            $map[$cur] = New-Object System.Collections.ArrayList
            continue
        }
        if ($null -eq $cur) { throw "${path}:${n}: baris deteksi sebelum 'FILE:'" }
        $p = $line.Split('|')
        if ($p.Count -ne 3) { throw "${path}:${n}: format baris salah: '$line'" }
        $b = $p[2].Split(',')
        if ($b.Count -ne 4) { throw "${path}:${n}: kotak harus 4 angka: '$line'" }
        $null = $map[$cur].Add([pscustomobject]@{
            Kelas = $p[0]
            Skor  = [double]::Parse($p[1], $inv)
            Kotak = [double[]]($b | ForEach-Object { [double]::Parse($_, $inv) })
        })
    }
    return $map
}

function Get-JarakKotak($a, $b) {
    $m = 0.0
    for ($i = 0; $i -lt 4; $i++) { $m = [Math]::Max($m, [Math]::Abs($a.Kotak[$i] - $b.Kotak[$i])) }
    return $m
}

try {
    $ref = Read-Keluaran $Acuan
    $kan = Read-Keluaran $Kandidat
} catch {
    Write-Host "GALAT: $($_.Exception.Message)"
    exit 2
}

if ($ref.Count -eq 0) { Write-Host "GALAT: berkas acuan tidak berisi gambar"; exit 2 }

$pelanggaran = New-Object System.Collections.ArrayList
$lulus = 0

foreach ($nama in $ref.Keys) {
    if (-not $kan.Contains($nama)) {
        $null = $pelanggaran.Add("${nama}: tidak ada di kandidat"); continue
    }
    $r = @($ref[$nama]); $k = @($kan[$nama])
    $masalah = @()

    if ($r.Count -ne $k.Count) {
        $masalah += "jumlah deteksi berbeda (acuan=$($r.Count), kandidat=$($k.Count))"
    }
    $kelasR = ($r | ForEach-Object { $_.Kelas } | Sort-Object) -join ','
    $kelasK = ($k | ForEach-Object { $_.Kelas } | Sort-Object) -join ','
    if ($kelasR -ne $kelasK) {
        $masalah += "kelas berbeda (acuan=[$kelasR], kandidat=[$kelasK])"
    }

    # Pasangkan per kelas dengan kotak terdekat; hanya diperiksa bila jumlah dan kelas sama.
    if ($masalah.Count -eq 0) {
        $dipakai = New-Object 'System.Collections.Generic.HashSet[int]'
        foreach ($d in $r) {
            $best = -1; $bestJ = [double]::MaxValue
            for ($i = 0; $i -lt $k.Count; $i++) {
                if ($dipakai.Contains($i) -or $k[$i].Kelas -ne $d.Kelas) { continue }
                $j = Get-JarakKotak $d $k[$i]
                if ($j -lt $bestJ) { $bestJ = $j; $best = $i }
            }
            $null = $dipakai.Add($best)
            $c = $k[$best]
            $selSkor = [Math]::Abs($d.Skor - $c.Skor)
            if ($selSkor -gt $ToleransiSkor + $eps) {
                $masalah += ("{0}: selisih skor {1:0.000} > {2} (acuan={3:0.000}, kandidat={4:0.000})" -f $d.Kelas, $selSkor, $ToleransiSkor, $d.Skor, $c.Skor)
            }
            $sisi = @('left', 'top', 'right', 'bottom')
            for ($s = 0; $s -lt 4; $s++) {
                $sel = [Math]::Abs($d.Kotak[$s] - $c.Kotak[$s])
                if ($sel -gt $ToleransiPiksel + $eps) {
                    $masalah += ("{0}: sisi {1} selisih {2} px > {3} (acuan={4}, kandidat={5})" -f $d.Kelas, $sisi[$s], $sel, $ToleransiPiksel, $d.Kotak[$s], $c.Kotak[$s])
                }
            }
        }
    }

    if ($masalah.Count -eq 0) { $lulus++ }
    else { foreach ($m in $masalah) { $null = $pelanggaran.Add("${nama}: $m") } }
}

foreach ($nama in $kan.Keys) {
    if (-not $ref.Contains($nama)) { $null = $pelanggaran.Add("${nama}: ada di kandidat tetapi tidak di acuan") }
}

$total = $ref.Count
Write-Host "Acuan   : $Acuan"
Write-Host "Kandidat: $Kandidat"
Write-Host "Toleransi: skor <= $ToleransiSkor, sisi kotak <= $ToleransiPiksel px"
Write-Host "Jumlah gambar (acuan): $total"
Write-Host "Lulus                : $lulus"
Write-Host "Gagal                : $($total - $lulus)"
if ($pelanggaran.Count -gt 0) {
    Write-Host "Pelanggaran ($($pelanggaran.Count)):"
    foreach ($p in $pelanggaran) { Write-Host "  - $p" }
}
if ($pelanggaran.Count -eq 0) { Write-Host "HASIL: SEMUA LULUS"; exit 0 }
Write-Host "HASIL: ADA PELANGGARAN"
exit 1
