import os, re, sys, glob, random, collections

# Pemakaian: python leakage_check.py FOLDER_EKSPOR_ROBOFLOW
# Meniru pembagian di notebook training: hanya folder train/ yang diambil, diurutkan,
# random.seed(42), lalu val 20% dan test 20% (sisanya latih).
base = os.path.expanduser(sys.argv[1])
files = [f for f in glob.glob(os.path.join(base, 'train', '**', '*.jpg'), recursive=True)]
files = sorted(files, key=lambda p: os.path.basename(p))
n = len(files)
if n == 0:
    sys.exit('Tidak ada .jpg di train/. Cek path folder ekspor.')

def source_stem(path):
    name = os.path.basename(path)
    m = re.split(r'\.rf\.', name)
    return m[0] if len(m) > 1 else os.path.splitext(name)[0]

random.seed(42)
order = files[:]
random.shuffle(order)
val_n, test_n = int(n * 0.2), int(n * 0.2)
val, test, train = order[:val_n], order[val_n:val_n + test_n], order[val_n + test_n:]
stems_train = {source_stem(p) for p in train}
groups = collections.Counter(source_stem(p) for p in files)

def leak(split):
    hit = sum(1 for p in split if source_stem(p) in stems_train)
    return hit, len(split)

print(f'gambar di train/ ekspor: {n} | gambar sumber unik: {len(groups)} | rata-rata versi per sumber: {n/len(groups):.2f}')
print(f'pembagian: latih={len(train)} validasi={len(val)} uji={len(test)}')
for name, sp in (('validasi', val), ('uji', test)):
    h, t = leak(sp)
    print(f'{name}: {h} dari {t} gambar ({100*h/t:.1f}%) punya "saudara" augmentasi dari gambar sumber yang sama di data latih')
