import os, sys, glob
import numpy as np
from PIL import Image

# Pemakaian: python dup_check.py FOLDER_EKSPOR_ROBOFLOW [AMBANG]
# Mencari gambar split lain yang SANGAT MIRIP isinya (dHash 64-bit; cermin ikut dicek),
# tidak bergantung pada nama berkas. AMBANG = jarak Hamming maksimum (bawaan 6 dari 64 bit).
base = sys.argv[1]
thr = int(sys.argv[2]) if len(sys.argv) > 2 else 6

def dhash(img):
    g = np.asarray(img.convert('L').resize((9, 8), Image.LANCZOS), dtype=np.int16)
    bits = (g[:, 1:] > g[:, :-1]).flatten()
    return np.packbits(bits).view('>u8')[0]

def hashes(split):
    items = []
    for p in sorted(glob.glob(os.path.join(base, split, '**', '*.jpg'), recursive=True)):
        im = Image.open(p)
        items.append((p, dhash(im), dhash(im.transpose(Image.FLIP_LEFT_RIGHT))))
    return items

def popcount(x):
    return np.unpackbits(x.astype('>u8').view(np.uint8).reshape(-1, 8), axis=1).sum(1)

sets = {s: hashes(s) for s in ('train', 'valid', 'test')}
tr = np.array([h for _, h, _ in sets['train']], dtype=np.uint64)
tr_names = [p for p, _, _ in sets['train']]
print('gambar:', {k: len(v) for k, v in sets.items()})
for split in ('test', 'valid'):
    n_hit, rows = 0, []
    for p, h, hm in sets[split]:
        d = np.minimum(popcount(tr ^ h), popcount(tr ^ hm))
        j = int(d.argmin())
        if d[j] <= thr:
            n_hit += 1
            rows.append((int(d[j]), os.path.basename(p), os.path.basename(tr_names[j])))
    print(f'{split}: {n_hit} dari {len(sets[split])} gambar punya padanan mirip di train (jarak <= {thr})')
    for d, a, b in sorted(rows)[:10]:
        print(f'   jarak={d:2d}  {a}  <->  {b}')
