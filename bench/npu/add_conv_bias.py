#!/usr/bin/env python3
"""Нулевой bias всем Conv без него — иначе QAIRT не квантует их поканально для чипов без fp16 на NPU.

AI Hub (7 Gen 4, 778G) на w8a16 parakeet: «preprocessPerChannel: No bias info for op: /layers.0/conv/pointwise_conv1/…».
В parakeet таких 48 (pointwise_conv1/2 во всех 24 слоях). Нулевой bias не меняет выход. Веса (.data) не трогаются:
новый граф ссылается на тот же файл внешних весов, его кладём жёсткой ссылкой рядом.

  add_conv_bias.py IN_DIR/encoder-model.onnx OUT_DIR

Не хватило (08.11): квантование AI Hub сначала собирает fp32-модель с фиксированными формами, и эта сборка нулевое
смещение выбрасывает. В QDQ-модели оно снова пропадает, и QAIRT падает на той же свёртке под именем node_Conv_163.
Рабочий путь — смещение прямо в QDQ: bench/npu/qdq_fix.py --bias.
"""
import os
import sys

import numpy as np
import onnx
from onnx import numpy_helper

src, out = sys.argv[1], sys.argv[2]
m = onnx.load(src, load_external_data=False)
inits = {i.name: i for i in m.graph.initializer}
added = 0
for n in m.graph.node:
    if n.op_type != "Conv" or (len(n.input) >= 3 and n.input[2]):
        continue
    w = inits[n.input[1]]
    b = numpy_helper.from_array(np.zeros(w.dims[0], np.float32), name=n.name + "/zero_bias")
    m.graph.initializer.append(b)
    while len(n.input) < 3:
        n.input.append("")
    n.input[2] = b.name
    added += 1
os.makedirs(out, exist_ok=True)
data = os.path.join(os.path.dirname(src), os.path.basename(src) + ".data")
dst_data = os.path.join(out, os.path.basename(data))
if not os.path.exists(dst_data):
    os.link(data, dst_data)
onnx.save(m, os.path.join(out, os.path.basename(src)))
print(f"добавлено нулевых bias: {added} → {out}")
