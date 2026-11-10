import numpy as np, os
np.object = object; np.bool = bool; np.complex = complex
from tflite_model_maker import model_spec, object_detector

label_map = {1: 'mangga matang', 2: 'mangga mentah', 3: 'mangga busuk'}
base = os.path.expanduser('~/clavo/dataset')
test_data = object_detector.DataLoader.from_pascal_voc(
    f'{base}/test/images', f'{base}/test/annotations', label_map=label_map)

spec = model_spec.get('efficientdet_lite2')
det = object_detector.ObjectDetector(spec, test_data.label_map)

files = {
    'v1': '/mnt/c/Users/Absar_Bempolibatam/Downloads/efficientdet_lite2_mangga_v1.tflite',
    'v2': os.path.expanduser('~/clavo/out/efficientdet_lite2_mangga_v2.tflite'),
}
for name, path in files.items():
    if not os.path.exists(path):
        print('FILE TIDAK ADA', name, path); continue
    try:
        r = det.evaluate_tflite(path, test_data)
        keys = ['AP', 'AP50', 'AP_/mangga matang', 'AP_/mangga mentah', 'AP_/mangga busuk']
        print('HASIL', name, {k: round(float(v), 3) for k, v in r.items()})
    except Exception as e:
        print('GAGAL', name, repr(e)[:300])
