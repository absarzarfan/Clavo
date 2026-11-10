import os, sys, glob, collections
import xml.etree.ElementTree as ET

# Pemakaian: python count_dataset.py FOLDER_DATASET
# FOLDER_DATASET berisi train/ valid/ test/, masing-masing dengan images/ dan annotations/ (VOC XML)
base = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else '~/clavo/dataset')
CLASSES = ['mangga matang', 'mangga mentah', 'mangga busuk']
tot = collections.Counter(); tot_img = collections.Counter(); n_img_all = 0; n_obj_all = 0

print(f'Folder: {base}\n')
print(f'{"split":8s}{"gambar":>8s}{"xml":>6s}{"objek":>7s}   objek per kelas | gambar per kelas')
for split in ['train', 'valid', 'test']:
    imgs = [f for f in glob.glob(os.path.join(base, split, 'images', '*'))
            if f.lower().endswith(('.jpg', '.jpeg', '.png'))]
    xmls = glob.glob(os.path.join(base, split, 'annotations', '*.xml'))
    obj, imgc = collections.Counter(), collections.Counter()
    for xf in xmls:
        names = [(o.findtext('name') or '').strip().lower() for o in ET.parse(xf).getroot().iter('object')]
        obj.update(names); imgc.update(set(names))
    o_txt = ', '.join(f'{c.split()[-1]}={obj.get(c, 0)}' for c in CLASSES)
    i_txt = ', '.join(f'{c.split()[-1]}={imgc.get(c, 0)}' for c in CLASSES)
    other = {k: v for k, v in obj.items() if k not in CLASSES}
    print(f'{split:8s}{len(imgs):8d}{len(xmls):6d}{sum(obj.values()):7d}   {o_txt} | {i_txt}')
    if other:
        print(f'         label di luar 3 kelas: {other}')
    tot.update(obj); tot_img.update(imgc); n_img_all += len(imgs); n_obj_all += sum(obj.values())

o_txt = ', '.join(f'{c.split()[-1]}={tot.get(c, 0)}' for c in CLASSES)
i_txt = ', '.join(f'{c.split()[-1]}={tot_img.get(c, 0)}' for c in CLASSES)
print(f'{"TOTAL":8s}{n_img_all:8d}{"":6s}{n_obj_all:7d}   {o_txt} | {i_txt}')
