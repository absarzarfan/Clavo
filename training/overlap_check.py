import os, re, sys, glob

# Pemakaian: python overlap_check.py FOLDER_EKSPOR_ROBOFLOW
# Memeriksa apakah gambar sumber yang sama muncul di lebih dari satu split ekspor.
base = sys.argv[1]

def stems(split):
    out = set()
    for p in glob.glob(os.path.join(base, split, '**', '*.jpg'), recursive=True):
        n = os.path.basename(p)
        out.add(re.split(r'\.rf\.', n)[0])
    return out

tr, va, te = stems('train'), stems('valid'), stems('test')
print(f'gambar sumber unik: train={len(tr)} valid={len(va)} test={len(te)}')
print(f'irisan test-train={len(te & tr)} | valid-train={len(va & tr)} | test-valid={len(te & va)}')
if te & tr:
    print('contoh irisan test-train:', sorted(te & tr)[:3])
