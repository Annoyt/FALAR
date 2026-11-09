#!/usr/bin/env python3
"""Кодировщик parakeet для NPU в приложении: та же QDQ-модель, что прошла восемь телефонов AI Hub (qdq_fix.py
--bias --float-length --a8-matmul in1 --pos-per-tensor), но постоянные хранятся сразу в целом типе.

У AI Hub веса лежат в fp32 и квантуются узлом QuantizeLinear при каждом запуске: 2,4 ГБ на диске. Здесь
QuantizeLinear(постоянная) заранее посчитан и заменён целым тензором (int8 у весов, uint16 у сетки длины и т. п.)
под тем же именем. DequantizeLinear дальше получает ровно те же числа, поэтому выход не меняется, а файл —
около 0,6 ГБ. Вход и выход остаются float: ONNX Runtime с QNN квантует их на процессоре
(qnn_offload_graph_io_quantization=1 по умолчанию).

  app_model.py IN_DIR OUT.onnx                        # IN_DIR — model.onnx + model.data
  app_model.py --check IN_DIR OUT.onnx [--clips 3]    # ORT на процессоре: выходы обеих моделей на фразах корпуса
"""
import argparse
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, numpy_helper

sys.path.insert(0, str(Path(__file__).parent))
from qdq_fix import inline, value  # noqa: E402

P = Path("/home/not_me/antigravity-workspace/agentstuff/agenttranslator")
FEAT = P / ".stand/npu/asrfeat"
F = 832


def quantize(x, scale, zp, axis):
    """QuantizeLinear по спецификации ONNX: saturate(round_half_even(x / scale) + zp), деление во float32."""
    if scale.size > 1:
        shape = [1] * x.ndim
        shape[axis] = -1
        scale, zp = scale.reshape(shape), zp.reshape(shape)
    info = np.iinfo(zp.dtype)
    q = np.rint(x.astype(np.float32) / scale.astype(np.float32)) + zp.astype(np.float64)
    return np.clip(q, info.min, info.max).astype(zp.dtype)


def build(src, dst):
    src, dst = Path(src), Path(dst)
    m = onnx.load(str(src / "model.onnx"), load_external_data=False)
    g = m.graph
    init = {i.name: i for i in g.initializer}
    folded, kept = 0, []
    for n in g.node:
        if n.op_type != "QuantizeLinear" or n.input[0] not in init:
            kept.append(n)
            continue
        attrs = {a.name: a for a in n.attribute}
        if "block_size" in attrs and attrs["block_size"].i:
            sys.exit(f"{n.name}: блочное квантование не поддержано")
        if len(n.input) < 3 or not n.input[2]:
            sys.exit(f"{n.name}: нет нулевой точки — тип выхода неизвестен")
        x = value(init, n.input[0], src)
        s = value(init, n.input[1], src)
        z = value(init, n.input[2], src)
        axis = attrs["axis"].i if "axis" in attrs else 1
        if axis < 0:
            axis += x.ndim
        q = quantize(x, s, z, axis)
        t = numpy_helper.from_array(q, n.output[0])
        g.initializer.append(t)
        init[t.name] = t
        folded += 1
    del g.node[:]
    g.node.extend(kept)
    used = {i for n in g.node for i in n.input} | {o.name for o in g.output}
    keep = []
    for i in g.initializer:
        if i.name in used:
            keep.append(inline(i, src))
    dropped = len(g.initializer) - len(keep)
    del g.initializer[:]
    g.initializer.extend(keep)
    names = {i.name for i in keep}
    vi = [v for v in g.value_info if v.name not in names]
    del g.value_info[:]
    g.value_info.extend(vi)
    onnx.checker.check_model(m)
    onnx.shape_inference.infer_shapes(m, check_type=True, strict_mode=True)
    dst.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(m, str(dst))
    left = {}
    for i in keep:
        k = TensorProto.DataType.Name(i.data_type)
        left[k] = left.get(k, 0) + int(np.prod(i.dims)) if i.dims else left.get(k, 0) + 1
    print(f"свёрнуто QuantizeLinear: {folded}; убрано постоянных: {dropped}; осталось по типам: {left}")
    print(f"{dst}: {dst.stat().st_size / 1e9:.3f} ГБ")
    print("вход:", [(i.name, TensorProto.DataType.Name(i.type.tensor_type.elem_type)) for i in g.input],
          "выход:", [(o.name, TensorProto.DataType.Name(o.type.tensor_type.elem_type)) for o in g.output])


def check(src, dst, clips):
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    so.inter_op_num_threads = 1
    a = ort.InferenceSession(str(Path(src) / "model.onnx"), so, providers=["CPUExecutionProvider"])
    b = ort.InferenceSession(str(dst), so, providers=["CPUExecutionProvider"])
    lines = [l.split("\t") for l in (FEAT / "list.tsv").read_text().splitlines() if l.strip()]
    pick = [x for x in lines if x[0].startswith("pt_")][:: 41][: (clips + 1) // 2] + \
           [x for x in lines if x[0].startswith("ru_")][:: 41][: clips // 2]
    ins = [i.name for i in a.get_inputs()]
    worst = 0.0
    for name, T in pick:
        x = np.fromfile(FEAT / f"{name}.f32", np.float32).reshape(1, 128, F)
        feed = {ins[0]: x, ins[1]: np.array([float(T)], np.float32)}
        ra, rb = a.run(None, feed), b.run(None, feed)
        for k, (u, v) in enumerate(zip(ra, rb)):
            d = float(np.max(np.abs(u.astype(np.float64) - v.astype(np.float64))))
            worst = max(worst, d)
            print(f"{name} T={T} выход {k} {u.shape}: макс. разница {d:.3g}" + (f"; длина {u.ravel()[0]} / {v.ravel()[0]}" if k == 1 else ""))
    print("СОВПАДАЮТ" if worst == 0.0 else f"расходятся: худшая разница {worst:.3g}")
    return 0 if worst == 0.0 else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--clips", type=int, default=3)
    a = ap.parse_args()
    if a.check:
        return check(a.src, a.dst, a.clips)
    build(a.src, a.dst)
    return 0


if __name__ == "__main__":
    sys.exit(main())
