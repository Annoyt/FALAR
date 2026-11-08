#!/usr/bin/env python3
"""Починить QDQ-модель parakeet, которую выдал квантователь AI Hub, перед сборкой под NPU.

1. Длина фразы. Путь length → Cast → (Add, Div, Add, Floor) ×3 → Cast квантователь откалибровал по первой фразе
   калибровки: диапазон [0, 221] кадров вместо [111, 411]. В итоге длинные фразы обрезаются на ~2,2 с, а у коротких длина
   сбита на 1–2 кадра (так у всех 162 фраз на S25). Здесь всем тензорам и константам этого пути ставится точная сетка
   uint16: шаг 0,5, ноль 2 (диапазон −1…32766,5). Все значения на этом пути — целые или x,5, поэтому сетка без потерь.
2. --bias: свёрткам без смещения (pointwise_conv1/2, 48 шт.) добавляется нулевое смещение int32 с DequantizeLinear.
   Шаг смещения — шаг входа × шаг весов по каналам, как у остальных свёрток. Без смещения QAIRT на чипах без fp16
   (v73, v68) падает: «preprocessPerChannel: No bias info». Нулевое смещение в fp32-модели AI Hub выбрасывает при своей
   сборке, поэтому оно добавляется прямо в QDQ.

3. --float-length: вход length сразу float32, Cast из int64 убирается. На v73/v68 QAIRT не берёт float-выход Cast
   («Tensor '/pre_encode/Cast_output_0…' has a floating-point type»). С --quantize_io вход length тогда — uint16 в той же
   точной сетке: q = 2 × длина + 2.

  qdq_fix.py IN_DIR OUT_DIR [--bias] [--float-length]   # IN_DIR: model.onnx + model.data от AI Hub; data — жёсткая ссылка
  qdq_fix.py --check-length OUT_DIR    # путь длины отдельно на ПК (ORT) для всех фраз корпуса против fp32-эталона
  qdq_fix.py --io-params DIR           # сетка uint16 входа и выхода (для вывода со сборкой --quantize_io)

Смысл модели не меняется: смещения нулевые, путь длины считает то же, что fp32.
"""
import argparse
import os
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

STEP, ZERO = 0.5, 2   # uint16: (q − 2) × 0,5
REF = Path("/home/not_me/antigravity-workspace/agentstuff/agenttranslator/.stand/npu/aihub/ref-pc-fp32")
FEAT = Path("/home/not_me/antigravity-workspace/agentstuff/agenttranslator/.stand/npu/asrfeat")


def inline(t, base):
    """Копия тензора с данными внутри. Внешние байты читаются сами: загрузчик onnx не пускает жёсткие ссылки на data."""
    c = TensorProto()
    c.CopyFrom(t)
    if c.data_location == TensorProto.EXTERNAL:
        info = {e.key: e.value for e in c.external_data}
        with open(Path(base) / info["location"], "rb") as f:
            f.seek(int(info.get("offset", 0)))
            c.raw_data = f.read(int(info["length"]))
        del c.external_data[:]
        c.data_location = TensorProto.DEFAULT
    return c


def value(init, name, base):
    return numpy_helper.to_array(inline(init[name], base))


def length_path(g):
    """Узлы от входа length до Cast в целое (включая его): так считается длина после трёх свёрток с шагом 2."""
    cons = {}
    for n in g.node:
        for i in n.input:
            cons.setdefault(i, []).append(n)
    nodes, front = [], ["length"]
    while front:
        t = front.pop(0)
        for n in cons.get(t, []):
            if n in nodes:
                continue
            nodes.append(n)
            to = next((a.i for a in n.attribute if a.name == "to"), None) if n.op_type == "Cast" else None
            if n.op_type == "Cast" and to != TensorProto.FLOAT:
                continue          # обратно в целое — дальше маски (bool), их не квантуют
            front.extend(n.output)
    return nodes


def fix_length(g, init):
    prod = {o: n for n in g.node for o in n.output}
    path = length_path(g)
    qdq = [n for n in path if n.op_type in ("QuantizeLinear", "DequantizeLinear")]
    # константы, которые входят в Add/Div пути: DequantizeLinear(QuantizeLinear(константа))
    for n in path:
        if n.op_type in ("Add", "Div", "Sub", "Mul"):
            for i in n.input:
                dq = prod.get(i)
                if dq is not None and dq.op_type == "DequantizeLinear" and dq not in qdq:
                    q = prod.get(dq.input[0])
                    if q is not None and q.op_type == "QuantizeLinear" and q.input[0] in init:
                        qdq += [q, dq]
    s = numpy_helper.from_array(np.array(STEP, np.float32), "falar_len_step")
    z = numpy_helper.from_array(np.array(ZERO, np.uint16), "falar_len_zero")
    g.initializer.extend([s, z])
    for n in qdq:
        n.input[1], n.input[2] = s.name, z.name
    return len(qdq)


def float_length(g):
    """Вход length сразу float32: Cast(int64 → float) убирается, его потребители берут вход напрямую."""
    cast = next(n for n in g.node if n.op_type == "Cast" and n.input[0] == "length")
    for n in g.node:
        for k, i in enumerate(n.input):
            if i == cast.output[0]:
                n.input[k] = "length"
    g.node.remove(cast)
    next(i for i in g.input if i.name == "length").type.tensor_type.elem_type = TensorProto.FLOAT


def add_bias(g, init, base):
    prod = {o: n for n in g.node for o in n.output}
    new_nodes, n_fixed = [], 0
    for c in g.node:
        if c.op_type != "Conv" or (len(c.input) >= 3 and c.input[2]):
            continue
        xdq, wdq = prod.get(c.input[0]), prod.get(c.input[1])
        if xdq is None or wdq is None or xdq.op_type != "DequantizeLinear" or wdq.op_type != "DequantizeLinear":
            sys.exit(f"{c.name}: вход или веса не через DequantizeLinear — не знаю шаг смещения")
        xs = float(value(init, xdq.input[1], base))
        ws = value(init, wdq.input[1], base).astype(np.float32).ravel()
        cout = ws.size
        b = c.name.strip("/").replace("/", "_") + "_falar_bias"
        g.initializer.extend([
            numpy_helper.from_array(np.zeros(cout, np.int32), b + "_q"),
            numpy_helper.from_array((xs * ws).astype(np.float32), b + "_scale"),
            numpy_helper.from_array(np.zeros(cout, np.int32), b + "_zero_point"),
        ])
        new_nodes.append(helper.make_node("DequantizeLinear", [b + "_q", b + "_scale", b + "_zero_point"], [b + "_qdq"],
                                          name=b + "_dq", axis=0))
        while len(c.input) < 3:
            c.input.append("")
        c.input[2] = b + "_qdq"
        n_fixed += 1
    for i, n in enumerate(new_nodes):   # входы — константы, достаточно поставить в начало графа
        g.node.insert(i, n)
    return n_fixed


def check_length(model_dir):
    """Собрать подграф length → output_1 (скаляры, без весов) и прогнать на ORT для всех длин корпуса."""
    import onnxruntime as ort
    m = onnx.load(str(Path(model_dir) / "model.onnx"), load_external_data=False)
    g = m.graph
    init = {i.name: i for i in g.initializer}
    prod = {o: n for n in g.node for o in n.output}
    path = length_path(g)
    need, extra = set(), []
    for n in path:
        for i in n.input:
            if i in init or i == "length":
                continue
            if i not in {o for p in path for o in p.output}:
                extra.append(prod[i])
    nodes = []
    for n in extra + path:
        if not any(n is x for x in nodes):
            nodes.append(n)
    for n in nodes:
        need.update(i for i in n.input if i in init)
    # вход Q у констант — тоже константа; соберём цепочки до инициализаторов
    changed = True
    while changed:
        changed = False
        for n in list(nodes):
            for i in n.input:
                if i and i not in init and i != "length" and i not in {o for p in nodes for o in p.output}:
                    nodes.insert(0, prod[i])
                    changed = True
        for n in nodes:
            need.update(i for i in n.input if i in init)
    inits = [inline(init[nm], model_dir) for nm in need]
    cast = [n for n in path if n.op_type == "Cast"][-1]
    to = next(a.i for a in cast.attribute if a.name == "to")
    ltype = next(i for i in g.input if i.name == "length").type.tensor_type.elem_type
    ldt = np.float32 if ltype == TensorProto.FLOAT else np.int64
    sub = helper.make_graph(nodes, "len", [helper.make_tensor_value_info("length", ltype, [1])],
                            [helper.make_tensor_value_info(cast.output[0], to, [1])], inits)
    sm = helper.make_model(sub, opset_imports=m.opset_import, ir_version=m.ir_version)
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(sm.SerializeToString(), so, providers=["CPUExecutionProvider"])
    bad = 0
    lines = [l.split("\t") for l in (FEAT / "list.tsv").read_text().splitlines() if l.strip()]
    for name, T in lines:
        got = int(sess.run(None, {"length": np.array([int(T)], ldt)})[0][0])
        ref = int((REF / f"{name}.len").read_text())
        bad += got != ref
        if got != ref and bad <= 5:
            print(f"  {name}: {T} кадров → {got}, а fp32 даёт {ref}")
    print(f"длина: {len(lines) - bad}/{len(lines)} совпадают с fp32; все длины 1…832: ", end="")
    exp = lambda t: int(np.floor((np.floor((np.floor((t - 1) / 2 + 1) - 1) / 2 + 1) - 1) / 2 + 1))
    wrong = [t for t in range(1, 833) if int(sess.run(None, {"length": np.array([t], ldt)})[0][0]) != exp(t)]
    print("все верны" if not wrong else f"неверно {len(wrong)}, первые {wrong[:8]}")
    return 1 if bad or wrong else 0


def io_params(model_dir):
    """Шаг и ноль uint16 у входа audio_signal и выхода output_0 — для сборок с --quantize_io (JSON в stdout)."""
    import json
    m = onnx.load(str(Path(model_dir) / "model.onnx"), load_external_data=False)
    init = {i.name: i for i in m.graph.initializer}
    q = next(n for n in m.graph.node if n.op_type == "QuantizeLinear" and n.input[0] == "audio_signal")
    dq = next(n for n in m.graph.node if n.op_type == "DequantizeLinear" and n.output[0] == "output_0")
    pair = lambda n: [float(value(init, n.input[1], model_dir)), int(value(init, n.input[2], model_dir))]
    print(json.dumps({"in": pair(q), "out": pair(dq)}))
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src", nargs="?")
    ap.add_argument("dst", nargs="?")
    ap.add_argument("--bias", action="store_true")
    ap.add_argument("--float-length", action="store_true")
    ap.add_argument("--check-length", metavar="DIR")
    ap.add_argument("--io-params", metavar="DIR")
    a = ap.parse_args()
    if a.check_length:
        return check_length(a.check_length)
    if a.io_params:
        return io_params(a.io_params)
    src, dst = Path(a.src), Path(a.dst)
    m = onnx.load(str(src / "model.onnx"), load_external_data=False)
    g = m.graph
    init = {i.name: i for i in g.initializer}
    print(f"длина: точная сетка у {fix_length(g, init)} узлов Q/DQ")
    if a.bias:
        print(f"смещения: добавлено у {add_bias(g, init, src)} свёрток")
    if a.float_length:
        float_length(g)
        print("длина: вход float32, Cast из int64 убран")
    dst.mkdir(parents=True, exist_ok=True)
    data = dst / "model.data"
    if not data.exists():
        os.link(src / "model.data", data)
    onnx.save(m, str(dst / "model.onnx"))   # внешние веса остаются ссылками на model.data
    print(f"→ {dst}")


if __name__ == "__main__":
    sys.exit(main())
