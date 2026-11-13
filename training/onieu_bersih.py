# -*- coding: utf-8 -*-
"""onieu_bersih.py: salin gambar uji OnieU yang TIDAK punya saudara di train atau valid.

Pemakaian: python onieu_bersih.py FOLDER_DATASET FOLDER_KELUARAN
Butuh onieu_leak_check.py di folder yang sama. Keluaran: images dan annotations.
"""
import os
import shutil
import sys

from onieu_leak_check import baca, inti


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    base, out = sys.argv[1], sys.argv[2]
    kunci = set()
    for s in ('train', 'valid'):
        kunci.update(inti(f) for f in baca(base, s))
    os.makedirs(os.path.join(out, 'images'), exist_ok=True)
    os.makedirs(os.path.join(out, 'annotations'), exist_ok=True)
    disalin = dikeluarkan = 0
    for f in baca(base, 'test'):
        if inti(f) in kunci:
            dikeluarkan += 1
            continue
        stem = os.path.splitext(f)[0]
        shutil.copy(os.path.join(base, 'test', 'images', f), os.path.join(out, 'images', f))
        shutil.copy(os.path.join(base, 'test', 'annotations', stem + '.xml'),
                    os.path.join(out, 'annotations', stem + '.xml'))
        disalin += 1
    print(f'dikeluarkan (punya saudara di train atau valid): {dikeluarkan}; disalin: {disalin}')


if __name__ == '__main__':
    main()
