import numpy as np
import os
np.object = object
np.bool = bool
np.complex = complex

from tflite_model_maker.config import ExportFormat
from tflite_model_maker import model_spec
from tflite_model_maker import object_detector
import tensorflow as tf
assert tf.__version__.startswith('2')

EPOCHS = int(os.environ.get('EPOCHS', 10))
BATCH = int(os.environ.get('BATCH', 2))
OUT = os.path.expanduser('~/clavo/out')
TFLITE_FILENAME = 'efficientdet_lite2_mangga_v2.tflite'
os.makedirs(OUT, exist_ok=True)

label_map = {1: 'mangga matang', 2: 'mangga mentah', 3: 'mangga busuk'}
base = os.path.expanduser('~/clavo/dataset')

train_data = object_detector.DataLoader.from_pascal_voc(
    f'{base}/train/images', f'{base}/train/annotations', label_map=label_map)
validation_data = object_detector.DataLoader.from_pascal_voc(
    f'{base}/valid/images', f'{base}/valid/annotations', label_map=label_map)
test_data = object_detector.DataLoader.from_pascal_voc(
    f'{base}/test/images', f'{base}/test/annotations', label_map=label_map)
print(len(train_data), len(validation_data), len(test_data))

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
