import numpy as np
import os, random
np.object = object
np.bool = bool
np.complex = complex

from tflite_model_maker.config import ExportFormat
from tflite_model_maker import model_spec
from tflite_model_maker import object_detector
import tensorflow as tf
assert tf.__version__.startswith('2')

EPOCHS = int(os.environ.get('EPOCHS', 20))
BATCH = int(os.environ.get('BATCH', 4))
SEED = int(os.environ.get('SEED', 42))
OUT = os.path.expanduser(os.environ.get('OUT', '~/clavo/out_apip'))
TFLITE_FILENAME = os.environ.get('TFLITE_NAME', 'efficientdet_lite2_mangga_apip_r1.tflite')
base = os.path.expanduser(os.environ.get('DATASET', '~/clavo/dataset_apip'))
random.seed(SEED); np.random.seed(SEED); tf.random.set_seed(SEED)
os.makedirs(OUT, exist_ok=True)
print('KONFIGURASI:', dict(epochs=EPOCHS, batch=BATCH, seed=SEED, dataset=base, spec='efficientdet_lite2',
                           train_whole_model=True, tf=tf.__version__, keluaran=os.path.join(OUT, TFLITE_FILENAME)))

label_map = {1: 'mangga matang', 2: 'mangga mentah', 3: 'mangga busuk'}

train_data = object_detector.DataLoader.from_pascal_voc(
    f'{base}/train/images', f'{base}/train/annotations', label_map=label_map)
validation_data = object_detector.DataLoader.from_pascal_voc(
    f'{base}/valid/images', f'{base}/valid/annotations', label_map=label_map)
test_data = object_detector.DataLoader.from_pascal_voc(
    f'{base}/test/images', f'{base}/test/annotations', label_map=label_map)
print('jumlah data (latih, validasi, uji):', len(train_data), len(validation_data), len(test_data))

spec = model_spec.get('efficientdet_lite2')
model = object_detector.create(train_data=train_data, model_spec=spec,
                               validation_data=validation_data,
                               epochs=EPOCHS, batch_size=BATCH,
                               train_whole_model=True)

model.export(export_dir=OUT, tflite_filename=TFLITE_FILENAME,
             label_filename='labels.txt',
             export_format=[ExportFormat.TFLITE, ExportFormat.LABEL])
print(model.evaluate(test_data))
print(model.evaluate_tflite(os.path.join(OUT, TFLITE_FILENAME), test_data))
