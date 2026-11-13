# -*- coding: utf-8 -*-
"""kappa3.py: kesepakatan tiga anotator (VOC XML) dan label konsensus mayoritas.

Pemakaian:
  python kappa3.py DIR_A0 DIR_A1 DIR_A2 [OUT_DIR IMAGES_DIR]

- Kotak dijodohkan antaranotator dengan IoU >= 0,5.
- Kappa Cohen dihitung pada pasangan kotak yang terjodoh (dua kotak yang
  hanya dimiliki satu anotator dilaporkan terpisah, tidak masuk kappa).
- Konsensus: kluster kotak dengan >= 2 suara sekelas diterima; kluster
  dengan satu suara atau suara seri dilaporkan dan tidak dipakai.
- Jika OUT_DIR dan IMAGES_DIR diberikan, ditulis OUT_DIR/annotations (XML
  konsensus), OUT_DIR/images, dan OUT_DIR/konsensus_rincian.csv.
"""
import csv
import glob
import os
import shutil
import sys
import xml.etree.ElementTree as ET
from collections import Counter

KELAS = ['matang', 'mentah', 'busuk']
NAMA = {'matang': 'mangga matang', 'mentah': 'mangga mentah', 'busuk': 'mangga busuk'}
IOU_MIN = 0.5


def norm(n):
    n = (n or '').strip().lower()
    for k in KELAS:
        if k in n:
            return k
    return n


def baca(folder):
    data = {}
    for p in glob.glob(os.path.join(folder, '*.xml')):
        stem = os.path.splitext(os.path.basename(p))[0]
        root = ET.parse(p).getroot()
        objs = []
        for o in root.findall('object'):
            bb = o.find('bndbox')
            b = [float(bb.find(t).text) for t in ('xmin', 'ymin', 'xmax', 'ymax')]
            objs.append((norm(o.find('name').text), b))
        size = root.find('size')
        wh = (0, 0)
        if size is not None and size.find('width') is not None:
            wh = (int(float(size.find('width').text)), int(float(size.find('height').text)))
        data[stem] = (objs, wh)
    return data


def iou(a, b):
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / ua if ua > 0 else 0.0


def jodohkan(la, lb):
    kand = []
    for i, (_, ba) in enumerate(la):
        for j, (_, bb) in enumerate(lb):
            v = iou(ba, bb)
            if v >= IOU_MIN:
                kand.append((v, i, j))
    kand.sort(reverse=True)
    pi, pj, out = set(), set(), []
    for _, i, j in kand:
        if i in pi or j in pj:
            continue
        pi.add(i)
        pj.add(j)
        out.append((i, j))
    return out


def kappa(pasang):
    n = len(pasang)
    if n == 0:
        return float('nan'), float('nan')
    po = sum(1 for a, b in pasang if a == b) / n
    ca = Counter(a for a, _ in pasang)
    cb = Counter(b for _, b in pasang)
    pe = sum((ca[k] / n) * (cb[k] / n) for k in set(ca) | set(cb))
    kap = (po - pe) / (1 - pe) if pe < 1 else float('nan')
    return po, kap


def tulis_xml(path, folder, fname, w, h, objs):
    root = ET.Element('annotation')
    ET.SubElement(root, 'folder').text = folder
    ET.SubElement(root, 'filename').text = fname
    sz = ET.SubElement(root, 'size')
    ET.SubElement(sz, 'width').text = str(w)
    ET.SubElement(sz, 'height').text = str(h)
    ET.SubElement(sz, 'depth').text = '3'
    ET.SubElement(root, 'segmented').text = '0'
    for kls, b in objs:
        o = ET.SubElement(root, 'object')
        ET.SubElement(o, 'name').text = NAMA.get(kls, kls)
        ET.SubElement(o, 'pose').text = 'Unspecified'
        ET.SubElement(o, 'truncated').text = '0'
        ET.SubElement(o, 'difficult').text = '0'
        bb = ET.SubElement(o, 'bndbox')
        for t, v in zip(('xmin', 'ymin', 'xmax', 'ymax'), b):
            ET.SubElement(bb, t).text = str(int(round(v)))
    ET.ElementTree(root).write(path, encoding='utf-8')


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        sys.exit(1)
    dirs = sys.argv[1:4]
    out_dir = sys.argv[4] if len(sys.argv) > 4 else None
    img_dir = sys.argv[5] if len(sys.argv) > 5 else None
    D = [baca(d) for d in dirs]
    nama = ['anotator0 (asli)', 'anotator1', 'anotator2']
    stems = sorted(set().union(*[set(d) for d in D]))
    for k in range(3):
        print(f'{nama[k]}: {len(D[k])} berkas XML, {sum(len(v[0]) for v in D[k].values())} objek')
    kurang = [s for s in stems if any(s not in d for d in D)]
    if kurang:
        print(f'PERINGATAN: {len(kurang)} gambar tanpa XML di sebagian folder (dianggap tanpa objek):')
        for s in kurang:
            print('   ', s, [k for k in range(3) if s not in D[k]])

    def objs(k, s):
        return D[k].get(s, ([], (0, 0)))[0]

    print('\n== kesepakatan berpasangan (IoU >= 0,5) ==')
    for x, y in [(0, 1), (0, 2), (1, 2)]:
        pasang, hx, hy, beda = [], 0, 0, []
        for s in stems:
            lx, ly = objs(x, s), objs(y, s)
            m = jodohkan(lx, ly)
            for i, j in m:
                pasang.append((lx[i][0], ly[j][0]))
                if lx[i][0] != ly[j][0]:
                    beda.append((s, lx[i][0], ly[j][0]))
            hx += len(lx) - len(m)
            hy += len(ly) - len(m)
        po, kap = kappa(pasang)
        print(f'{nama[x]} vs {nama[y]}: cocok={len(pasang)} hanya_{x}={hx} hanya_{y}={hy} '
              f'kesepakatan={po:.3f} kappa={kap:.3f}')
        for s, a, b in beda:
            print(f'    beda kelas: {s[:40]}  {a} -> {b}')

    print('\n== konsensus mayoritas ==')
    rows, hasil_per_stem, tidak = [], {}, []
    for s in stems:
        L = [objs(k, s) for k in range(3)]
        nodes = [(k, i) for k in range(3) for i in range(len(L[k]))]
        par = {n: n for n in nodes}

        def cari(n):
            while par[n] != n:
                par[n] = par[par[n]]
                n = par[n]
            return n

        for x, y in [(0, 1), (0, 2), (1, 2)]:
            for i, j in jodohkan(L[x], L[y]):
                par[cari((x, i))] = cari((y, j))
        grup = {}
        for n in nodes:
            grup.setdefault(cari(n), []).append(n)
        diterima = []
        for g in grup.values():
            kls = [L[k][i][0] for k, i in g]
            bx = [L[k][i][1] for k, i in g]
            box = [sum(b[t] for b in bx) / len(bx) for t in range(4)]
            c = Counter(kls)
            top, topc = c.most_common(1)[0]
            suara = ['-'] * 3
            for k, i in g:
                suara[k] = L[k][i][0]
            if topc >= 2:
                status, hasil = 'diterima', top
                diterima.append((top, box))
            else:
                status = 'tunggal' if len(g) == 1 else 'seri'
                hasil = ''
                tidak.append((s, suara, status))
            rows.append([s] + [round(v) for v in box] + suara + [status, hasil])
        hasil_per_stem[s] = diterima
    n_ok = sum(len(v) for v in hasil_per_stem.values())
    per = Counter(k for v in hasil_per_stem.values() for k, _ in v)
    ringkas = ', '.join('%s=%d' % (k, per.get(k, 0)) for k in KELAS)
    print(f'objek konsensus: {n_ok}  ({ringkas})')
    print(f'tidak diterima: {len(tidak)}')
    for s, suara, st in tidak:
        print(f'    {st}: {s[:40]}  suara={suara}')

    if out_dir and img_dir:
        os.makedirs(os.path.join(out_dir, 'annotations'), exist_ok=True)
        os.makedirs(os.path.join(out_dir, 'images'), exist_ok=True)
        for s in stems:
            wh = next((D[k][s][1] for k in range(3) if s in D[k] and D[k][s][1] != (0, 0)), (0, 0))
            fn = None
            for ext in ('.jpg', '.jpeg', '.png', '.JPG'):
                if os.path.exists(os.path.join(img_dir, s + ext)):
                    fn = s + ext
                    break
            if fn:
                shutil.copy(os.path.join(img_dir, fn), os.path.join(out_dir, 'images', fn))
            tulis_xml(os.path.join(out_dir, 'annotations', s + '.xml'), 'images', fn or s, wh[0], wh[1], hasil_per_stem[s])
        with open(os.path.join(out_dir, 'konsensus_rincian.csv'), 'w', newline='', encoding='utf-8') as f:
            w = csv.writer(f)
            w.writerow(['gambar', 'xmin', 'ymin', 'xmax', 'ymax', 'a0', 'a1', 'a2', 'status', 'kelas'])
            w.writerows(rows)
        print(f'\nditulis: {out_dir}\\annotations ({len(stems)} XML), images, konsensus_rincian.csv')


if __name__ == '__main__':
    main()
