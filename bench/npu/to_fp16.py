#!/usr/bin/env python3
"""fp32 ONNX → fp16 (входы и выходы остаются fp32): вдвое меньше заливать на телефон, а HTP всё равно считает в fp16.

onnxconverter-common переводит тензоры в fp16, но у узлов Cast оставляет to=FLOAT — граф после этого не грузится
(«Type (tensor(float16)) of output arg (/Cast_output_0) … does not match»). Поэтому внутренние Cast в float
переписываются на float16; Cast на границе графа (их добавляет keep_io_types) остаются как есть.

  to_fp16.py IN.onnx OUT.onnx
"""
import sys

import onnx
from onnx import TensorProto
from onnxconverter_common import float16

m = onnx.load(sys.argv[1])
# Больше 2 ГБ (fp32 parakeet — 2,4 ГБ) protobuf не сериализует, а вывод типов в конвертере сериализует модель:
# тогда без него — типы промежуточных тензоров потом выведет ORT (value_info ниже всё равно очищается).
try:
    big = m.ByteSize() > 2_000_000_000
except Exception:  # EncodeError: больше 2 ГБ не может даже посчитать размер
    big = True
m16 = float16.convert_float_to_float16(m, keep_io_types=True, disable_shape_infer=big)
outs = {o.name for o in m16.graph.output}
fixed = 0
for n in m16.graph.node:
    if n.op_type != "Cast" or n.output[0] in outs or "graph_output_cast" in n.name:
        continue
    for at in n.attribute:
        if at.name == "to" and at.i == TensorProto.FLOAT:
            at.i = TensorProto.FLOAT16
            fixed += 1
# Устаревшие value_info (fp32) тоже ломают загрузку — типы промежуточных тензоров ORT выведет сам
del m16.graph.value_info[:]
onnx.save(m16, sys.argv[2])
print(sys.argv[2], "готово, Cast в fp16:", fixed)
