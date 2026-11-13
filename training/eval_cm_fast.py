import os, sys, glob, csv
import numpy as np
import xml.etree.ElementTree as ET
from PIL import Image
try:
    import tensorflow as tf
    Interp = tf.lite.Interpreter
except Exception:
    from ai_edge_litert.interpreter import Interpreter as Interp

# Pemakaian: python eval_cm.py MODEL.tflite FOLDER_SPLIT [TAG]
# FOLDER_SPLIT berisi images/ dan annotations/ (VOC XML), mis. ~/clavo/dataset/test
MODEL = os.path.expanduser(sys.argv[1])
SPLIT_DIR = os.path.expanduser(sys.argv[2])
TAG = sys.argv[3] if len(sys.argv) > 3 else 'eval'
IOU_T = float(os.environ.get('IOU_T', 0.5))
THRS = [0.2, 0.3, 0.4, 0.5]
CLASSES = ['mangga matang', 'mangga mentah', 'mangga busuk']   # urutan labelmap model
K = len(CLASSES); BG = K; T0 = __import__("time").time()

it = Interp(model_path=MODEL, num_threads=6); it.allocate_tensors()
inp = it.get_input_details()[0]; outs = it.get_output_details()
H_in, W_in = int(inp['shape'][1]), int(inp['shape'][2])

def infer(img):
    x = np.asarray(img.convert('RGB').resize((W_in, H_in)), dtype=np.uint8)[None]
    it.set_tensor(inp['index'], x); it.invoke()
    t = [it.get_tensor(o['index']) for o in outs]
    boxes = next(v for v in t if v.ndim == 3)[0]                 # [ymin, xmin, ymax, xmax] ternormalisasi
    two = [v[0] for v in t if v.ndim == 2]
    cls = next(v for v in two if np.allclose(v, np.round(v)))    # tensor kelas bernilai bulat
    sc = next(v for v in two if not np.allclose(v, np.round(v)))
    n = int(next(v for v in t if v.ndim == 1)[0])
    return boxes[:n], cls[:n].astype(int), sc[:n]

def iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0])); iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    ua = (a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1]) - inter
    return inter / ua if ua > 0 else 0.0

data, unknown = [], 0
for xf in sorted(glob.glob(os.path.join(SPLIT_DIR, 'annotations', '*.xml'))):
    stem = os.path.splitext(os.path.basename(xf))[0]
    imgs = glob.glob(os.path.join(SPLIT_DIR, 'images', stem + '.*'))
    if not imgs:
        continue
    img = Image.open(imgs[0]); W, H = img.size
    gts = []
    for o in ET.parse(xf).getroot().iter('object'):
        name = (o.findtext('name') or '').strip().lower()
        if name not in CLASSES:
            unknown += 1; continue
        b = o.find('bndbox')
        gts.append((CLASSES.index(name), [float(b.findtext(k)) for k in ('xmin', 'ymin', 'xmax', 'ymax')]))
    boxes, cls, sc = infer(img)
    dets = [(float(s), int(c), [b[1]*W, b[0]*H, b[3]*W, b[2]*H]) for b, c, s in zip(boxes, cls, sc) if 0 <= c < K]
    data.append((gts, dets)); print("progres", len(data), "gambar,", round(__import__("time").time() - T0), "detik", flush=True) if len(data) % 10 == 0 else None
print('gambar:', len(data), '| objek GT:', sum(len(g) for g, _ in data), '| label tak dikenal:', unknown)

def evaluate(thr):
    M = np.zeros((K+1, K+1), int)          # baris = aktual, kolom = prediksi, indeks K = latar/tidak terdeteksi
    for gts, dets in data:
        used = set()
        for s, c, b in sorted([d for d in dets if d[0] >= thr], key=lambda d: -d[0]):
            best, bi = 0.0, -1
            for i, (gc, gb) in enumerate(gts):
                if i in used: continue
                v = iou(b, gb)
                if v >= IOU_T and v > best: best, bi = v, i
            if bi >= 0: used.add(bi); M[gts[bi][0], c] += 1
            else: M[BG, c] += 1                       # deteksi palsu
        for i, (gc, gb) in enumerate(gts):
            if i not in used: M[gc, BG] += 1          # objek terlewat
    return M

summary = []
for thr in THRS:
    M = evaluate(thr)
    P, R, F = [], [], []
    print(f'\n=== skor >= {thr}, IoU >= {IOU_T} ===')
    print('baris=aktual, kolom=prediksi; urutan:', CLASSES + ['tidak terdeteksi/latar'])
    print(M)
    for c in range(K):
        tp = M[c, c]; fp = M[:, c].sum() - tp; fn = M[c, :].sum() - tp
        p = tp/(tp+fp) if tp+fp else 0.0; r = tp/(tp+fn) if tp+fn else 0.0
        f = 2*p*r/(p+r) if p+r else 0.0
        P.append(p); R.append(r); F.append(f)
        print(f'{CLASSES[c]:15s} TP={tp:4d} FP={fp:4d} FN={fn:4d}  P={p:.3f} R={r:.3f} F1={f:.3f}')
    total_gt = M[:K, :].sum(); diag = np.trace(M[:K, :K])
    acc = diag/total_gt if total_gt else 0.0
    acc_cls = diag/M[:K, :K].sum() if M[:K, :K].sum() else 0.0
    print(f'macro P={np.mean(P):.3f} R={np.mean(R):.3f} F1={np.mean(F):.3f} | akurasi (diagonal/total GT)={acc:.3f} | akurasi klasifikasi (hanya yang terlokalisasi)={acc_cls:.3f}')
    np.savetxt(f'{TAG}_cm_thr{thr}.csv', M, fmt='%d', delimiter=',')
    summary.append((thr, np.mean(P), np.mean(R), np.mean(F), acc, acc_cls, *F))

with open(f'{TAG}_ringkasan.csv', 'w', newline='') as fh:
    w = csv.writer(fh)
    w.writerow(['thr', 'macroP', 'macroR', 'macroF1', 'akurasi', 'akurasi_klasifikasi'] + [f'F1_{c}' for c in CLASSES])
    for row in summary: w.writerow([round(float(x), 4) for x in row])
print('\nfile:', f'{TAG}_ringkasan.csv', f'{TAG}_cm_thr*.csv')
