import sys, glob, os
import xml.etree.ElementTree as ET
from sklearn.metrics import cohen_kappa_score

# Pemakaian: python kappa.py FOLDER_XML_ANOTATOR1 FOLDER_XML_ANOTATOR2  (nama file XML sama)
A, B = [os.path.expanduser(p) for p in sys.argv[1:3]]

def load(p):
    out = []
    for o in ET.parse(p).getroot().iter('object'):
        b = o.find('bndbox')
        out.append((o.findtext('name').strip().lower(),
                    [float(b.findtext(k)) for k in ('xmin', 'ymin', 'xmax', 'ymax')]))
    return out

def iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0])); iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    ua = (a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1]) - inter
    return inter / ua if ua > 0 else 0.0

ya, yb, only_a, only_b = [], [], 0, 0
for fa in sorted(glob.glob(os.path.join(A, '*.xml'))):
    fb = os.path.join(B, os.path.basename(fa))
    if not os.path.exists(fb):
        continue
    oa, ob = load(fa), load(fb); used = set()
    for na, ba in oa:
        cand = [(iou(ba, bb), i) for i, (nb, bb) in enumerate(ob) if i not in used]
        best = max(cand, default=(0.0, -1))
        if best[0] >= 0.5:
            used.add(best[1]); ya.append(na); yb.append(ob[best[1]][0])
        else:
            only_a += 1
    only_b += len(ob) - len(used)
print('objek cocok (IoU>=0.5):', len(ya), '| hanya anotator 1:', only_a, '| hanya anotator 2:', only_b)
print('kesepakatan label:', round(sum(x == y for x, y in zip(ya, yb)) / max(1, len(ya)), 3))
print("Cohen's kappa:", round(cohen_kappa_score(ya, yb), 3))
