import hashlib, sys, os, zipfile
try:
    import tensorflow as tf
    Interp = tf.lite.Interpreter
except Exception:
    from ai_edge_litert.interpreter import Interpreter as Interp

# Pemakaian: python inspect_tflite.py model1.tflite model2.tflite ...
for f in sys.argv[1:]:
    f = os.path.expanduser(f); raw = open(f, 'rb').read()
    print('\n###', os.path.basename(f))
    print('ukuran_byte:', len(raw), '| MiB:', round(len(raw) / 2**20, 2))
    print('sha256:', hashlib.sha256(raw).hexdigest())
    it = Interp(model_path=f); it.allocate_tensors()
    for d in it.get_input_details():
        print('input :', d['shape'].tolist(), d['dtype'].__name__, 'skala/zero-point:', d['quantization'])
    for d in it.get_output_details():
        print('output:', d['shape'].tolist(), d['dtype'].__name__)
    try:
        z = zipfile.ZipFile(f)
        for n in z.namelist():
            print('label tertanam:', [l for l in z.read(n).decode().split('\n') if l])
    except Exception:
        print('label tertanam: tidak ditemukan')
