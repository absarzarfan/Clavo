import os, sys, glob
import numpy as np
from PIL import Image, ImageDraw

# Pemakaian: python dup_pairs.py FOLDER_EKSPOR_ROBOFLOW FILE_KELUARAN.jpg [AMBANG]
# Membuat lembar perbandingan: gambar uji (kiri) vs gambar train yang paling mirip (kanan),
# supaya kemiripan bisa dinilai dengan mata. Hash 64-bit terlalu kasar untuk foto berlatar hitam.
base, out = sys.argv[1], sys.argv[2]
thr = int(sys.argv[3]) if len(sys.argv) > 3 else 6
H = 200

def dhash(img):
    g = np.asarray(img.convert('L').resize((9, 8), Image.LANCZOS), dtype=np.int16)
    return np.packbits((g[:, 1:] > g[:, :-1]).flatten()).view('>u8')[0]

def popcount(x):
    return np.unpackbits(x.astype('>u8').view(np.uint8).reshape(-1, 8), axis=1).sum(1)

def files(split):
    return sorted(glob.glob(os.path.join(base, split, '**', '*.jpg'), recursive=True))

tr_files = files('train')
tr = np.array([dhash(Image.open(p)) for p in tr_files], dtype=np.uint64)
pairs = []
for p in files('test'):
    im = Image.open(p)
    d1 = popcount(tr ^ dhash(im)); d2 = popcount(tr ^ dhash(im.transpose(Image.FLIP_LEFT_RIGHT)))
    d = np.minimum(d1, d2); j = int(d.argmin())
    if d[j] <= thr:
        pairs.append((int(d[j]), p, tr_files[j], bool(d2[j] < d1[j])))
pairs.sort()

def thumb(path):
    im = Image.open(path).convert('RGB'); w = max(1, int(im.width * H / im.height))
    return im.resize((w, H))

cols, cell_w, cell_h = 2, 2 * 260 + 20, H + 30
rows = (len(pairs) + cols - 1) // cols
sheet = Image.new('RGB', (cols * cell_w, max(1, rows) * cell_h), (255, 255, 255))
dr = ImageDraw.Draw(sheet)
for k, (d, a, b, mirror) in enumerate(pairs):
    x0, y0 = (k % cols) * cell_w, (k // cols) * cell_h
    sheet.paste(thumb(a), (x0 + 5, y0 + 25)); sheet.paste(thumb(b), (x0 + 270, y0 + 25))
    dr.text((x0 + 5, y0 + 5), f'#{k+1} jarak={d}{" (cermin)" if mirror else ""}  KIRI=uji  KANAN=train', fill=(0, 0, 0))
    dr.text((x0 + 5, y0 + H + 27 - 8), os.path.basename(a)[:34] + ' | ' + os.path.basename(b)[:34], fill=(90, 90, 90))
sheet.save(out, quality=90)
print(f'{len(pairs)} pasangan (jarak <= {thr}) disimpan ke {out}, ukuran {sheet.size}')
