"""Build-time only: pin, convert and numerically verify pretrained FSRCNN x2.
No downloader is included in the Android application.
"""
from pathlib import Path
import hashlib, json, urllib.request
import numpy as np
import tensorflow as tf

COMMIT = '6a4812c4ef1c4f5947d79beafa32a05a6eb4a94d'
BASE = f'https://raw.githubusercontent.com/Saafke/FSRCNN_Tensorflow/{COMMIT}/'
assets = Path('app/src/main/assets')
assets.mkdir(parents=True, exist_ok=True)
cache = Path('build/model'); cache.mkdir(parents=True, exist_ok=True)
pb = cache / 'FSRCNN_x2.pb'
for remote, dest in [('models/FSRCNN_x2.pb', pb), ('LICENSE', assets/'FSRCNN-LICENSE.txt')]:
    with urllib.request.urlopen(BASE+remote, timeout=60) as r:
        dest.write_bytes(r.read())
gd = tf.compat.v1.GraphDef(); gd.ParseFromString(pb.read_bytes())
node_names = {n.name for n in gd.node}
assert 'IteratorGetNext' in node_names and 'NHWC_output' in node_names, node_names
converter = tf.compat.v1.lite.TFLiteConverter.from_frozen_graph(
    str(pb), ['IteratorGetNext'], ['NHWC_output'],
    input_shapes={'IteratorGetNext': [1,64,64,1]})
converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
model = converter.convert()
(assets/'fsrcnn_x2.tflite').write_bytes(model)
interpreter = tf.lite.Interpreter(model_content=model, num_threads=2)
interpreter.allocate_tensors()
inp = interpreter.get_input_details()[0]; out = interpreter.get_output_details()[0]
assert list(inp['shape']) == [1,64,64,1]
assert list(out['shape']) == [1,128,128,1]
graph = tf.Graph()
with graph.as_default(): tf.import_graph_def(gd, name='')
errors=[]
with tf.compat.v1.Session(graph=graph) as session:
    for seed in range(3):
        x=np.random.default_rng(seed).uniform(0,1,(1,64,64,1)).astype(np.float32)
        expected=session.run(graph.get_tensor_by_name('NHWC_output:0'),
                             {graph.get_tensor_by_name('IteratorGetNext:0'): x})
        interpreter.set_tensor(inp['index'],x);interpreter.invoke()
        actual=interpreter.get_tensor(out['index'])
        assert np.isfinite(actual).all()
        error=float(np.max(np.abs(actual-expected)));errors.append(error)
        assert error < 0.001, error
report={'source_commit':COMMIT,'source_sha256':hashlib.sha256(pb.read_bytes()).hexdigest(),
        'tflite_sha256':hashlib.sha256(model).hexdigest(),'input_shape':[1,64,64,1],
        'output_shape':[1,128,128,1],'max_conversion_errors':errors,
        'license':'Apache-2.0','model':'Saafke FSRCNN x2 (pretrained)','tile_overlap':8}
(assets/'model-info.json').write_text(json.dumps(report,indent=2)+'\n')
(assets/'THIRD_PARTY_NOTICES.txt').write_text(
    'FSRCNN Tensorflow: Saafke, Apache License 2.0.\n'+BASE+'\n'
    'Converted to TensorFlow Lite; architecture and trained weights are not retrained.\n'
    'TensorFlow Lite runtime: The TensorFlow Authors, Apache License 2.0.\n'
    'This app processes frames independently; no claim of recovering ground truth.\n')
print(json.dumps(report,indent=2))
