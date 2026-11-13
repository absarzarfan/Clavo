# -*- coding: utf-8 -*-
"""onieu_leak_check.py: cari gambar uji yang punya 'saudara' (nama inti sama) di train/valid.

Pemakaian: python onieu_leak_check.py FOLDER_DATASET
FOLDER_DATASET berisi train, valid, test; masing-masing punya folder images.
Nama inti = nama berkas tanpa akhiran Roboflow (.rf.hash), _jpg/_jpeg/_png,
varian _bright_a-b, dan -Copy. Ini hanya pemeriksaan nama, bukan isi gambar.
"""
import os
import re
import sys


def inti(nama):
    n = os.path.splitext(nama)[0]
    while True:
        s = n
        n = re.sub(r'\.rf\.[0-9a-f]+$', '', n)
        n = re.sub(r'_(jpe?g|png)$', '', n, flags=re.I)
        n = re.sub(r'_bright_\d+-\d+$', '', n, flags=re.I)
        n = re.sub(r'-Copy(\s*\(\d+\))?$', '', n, flags=re.I)
        if n == s:
            return n


def baca(base, split):
    d = os.path.join(base, split, 'images')
    return [f for f in sorted(os.listdir(d)) if f.lower().endswith(('.jpg', '.jpeg', '.png'))]


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    base = sys.argv[1]
    data = {s: baca(base, s) for s in ('train', 'valid', 'test')}
    inti_split = {s: {} for s in data}
    for s, fs in data.items():
        for f in fs:
            inti_split[s].setdefault(inti(f), []).append(f)
    for s, fs in data.items():
        print(f'{s}: {len(fs)} gambar, {len(inti_split[s])} nama inti unik')
    for tujuan in ('valid', 'test'):
        hit = [(f, inti(f)) for f in data[tujuan] if inti(f) in inti_split['train']]
        print(f'\n{tujuan} dengan saudara di train: {len(hit)} dari {len(data[tujuan])}')
        for f, k in hit[:15]:
            print('   ', f[:60], '<->', inti_split['train'][k][0][:50])
    hit_vt = [f for f in data['test'] if inti(f) in inti_split['valid']]
    print(f'\ntest dengan saudara di valid: {len(hit_vt)}')
    dalam = sum(1 for k, v in inti_split['test'].items() if len(v) > 1)
    print(f'nama inti dengan lebih dari satu gambar di dalam test: {dalam}')


if __name__ == '__main__':
    main()
