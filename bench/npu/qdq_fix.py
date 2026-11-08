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

3. --float-length: путь длины без целых чисел, иначе QAIRT на v73/v68 оставляет на его концах float-тензоры
   («Tensor '/pre_encode/Cast_output_0…' / '…Floor_2…dequantized_FLOAT_32' has a floating-point type»).
   Вход length — float32 (Cast из int64 убран). Выход output_1 — сама длина в точной сетке (два Cast в целое убраны).
   Маска «кадр < длины» считается сравнением в той же сетке: arange 0…103 — константа в сетке.
   С --quantize_io вход и выход длины — uint16: q = 2 × длина + 2.

4. --a8-matmul in1|both: для HTP v68 (778G, 888). Там произведение двух 16-битных активаций не собирается
   («Failed to validate op /layers.0/self_attn/MatMul_1 … has incorrect Value 68, expected >= 73»). Таких MatMul 48:
   q·kᵀ и внимание·v во всех 24 слоях. Здесь их второй (in1) или оба входа переквантуются в uint8 на том же
   диапазоне, что был в 16 битах (шаг ×257).

  qdq_fix.py IN_DIR OUT_DIR [--bias] [--float-length] [--a8-matmul in1|both]   # IN_DIR: model.onnx + model.data от AI Hub
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
    """Путь длины без целых: вход и выход длины — float32 в точной сетке, маска — сравнение в сетке."""
    s, z = "falar_len_step", "falar_len_zero"
    # вход: Cast(int64 → float) убирается, его потребители берут вход напрямую
    cast = next(n for n in g.node if n.op_type == "Cast" and n.input[0] == "length")
    for n in g.node:
        for k, i in enumerate(n.input):
            if i == cast.output[0]:
                n.input[k] = "length"
    g.node.remove(cast)
    next(i for i in g.input if i.name == "length").type.tensor_type.elem_type = TensorProto.FLOAT
    # выход: output_1 ← Cast(int64) ← Cast(int32) ← DequantizeLinear (длина в сетке); оба Cast убираются
    prod = {o: n for n in g.node for o in n.output}
    c1 = prod["output_1"]
    c2 = prod[c1.input[0]]
    dq = prod[c2.input[0]]
    if (c1.op_type, c2.op_type, dq.op_type) != ("Cast", "Cast", "DequantizeLinear"):
        sys.exit(f"output_1: ждал Cast ← Cast ← DequantizeLinear, а там {c1.op_type} ← {c2.op_type} ← {dq.op_type}")
    stale = {cast.output[0], c2.output[0], dq.output[0]}   # тензоры, которых больше нет или у которых другой тип
    dq.output[0] = "output_1"
    g.node.remove(c1)
    g.node.remove(c2)
    next(o for o in g.output if o.name == "output_1").type.tensor_type.elem_type = TensorProto.FLOAT
    # маска: Less(arange int64, Unsqueeze(output_1)) → Less(arange в сетке, Unsqueeze(output_1) в сетке)
    init = {i.name: i for i in g.initializer}
    for u in [n for n in g.node if "output_1" in n.input]:
        k = list(g.node).index(u)
        uq, ud = u.output[0] + "_falar_q", u.output[0] + "_falar_dq"
        less = [n for n in g.node if u.output[0] in n.input]
        stale.add(u.output[0])   # Unsqueeze теперь float
        g.node.insert(k + 1, helper.make_node("QuantizeLinear", [u.output[0], s, z], [uq], name=uq))
        g.node.insert(k + 2, helper.make_node("DequantizeLinear", [uq, s, z], [ud], name=ud))
        for n in less:
            if n.op_type not in ("Less", "Greater", "LessOrEqual", "GreaterOrEqual", "Equal"):
                sys.exit(f"длину берёт {n.op_type} {n.name} — не сравнение, не знаю, как перевести в сетку")
            for j, i in enumerate(n.input):
                if i == u.output[0]:
                    n.input[j] = ud
                elif i in init:
                    a = numpy_helper.to_array(init[i]).astype(np.float32)
                    if np.any(a != np.round(a * 2) / 2) or a.min() < -1:
                        sys.exit(f"{i}: константа не в сетке шага 0,5")
                    c = i.strip("/").replace("/", "_") + "_falar_f"
                    g.initializer.append(numpy_helper.from_array(a, c))
                    g.node.insert(0, helper.make_node("DequantizeLinear", [c + "_q", s, z], [c + "_dq"], name=c + "_dq"))
                    g.node.insert(0, helper.make_node("QuantizeLinear", [c, s, z], [c + "_q"], name=c + "_q"))
                    n.input[j] = c + "_dq"
    keep = [v for v in g.value_info if v.name not in stale]   # иначе вывод форм видит старый int64
    del g.value_info[:]
    g.value_info.extend(keep)


def a8_matmul(g, init, base, which):
    """Входы MatMul «активация × активация» переквантовать из uint16 в uint8 на том же диапазоне."""
    prod = {o: n for n in g.node for o in n.output}
    memo = {}

    def static(x):
        if x not in memo:
            p = prod.get(x)
            memo[x] = x in init or (p is not None and all(static(i) for i in p.input if i))
        return memo[x]

    done, cache = 0, {}
    for mm in [n for n in g.node if n.op_type == "MatMul"]:
        if static(mm.input[0]) or static(mm.input[1]):
            continue
        for k in ((1,) if which == "in1" else (0, 1)):
            x = mm.input[k]
            dq = prod.get(x)
            if dq is None or dq.op_type != "DequantizeLinear":
                sys.exit(f"{mm.name}: вход {k} не из DequantizeLinear")
            if x not in cache:
                s16 = float(value(init, dq.input[1], base))
                z16 = int(value(init, dq.input[2], base))
                if value(init, dq.input[2], base).dtype != np.uint16:
                    sys.exit(f"{x}: ждал uint16")
                b = x.strip("/").replace("/", "_") + "_falar_a8"
                g.initializer.extend([numpy_helper.from_array(np.array(s16 * 257, np.float32), b + "_scale"),
                                      numpy_helper.from_array(np.array(int(round(z16 / 257)), np.uint8), b + "_zp")])
                at = list(g.node).index(mm)
                g.node.insert(at, helper.make_node("DequantizeLinear", [b + "_q", b + "_scale", b + "_zp"], [b + "_dq"],
                                                   name=b + "_dq"))
                g.node.insert(at, helper.make_node("QuantizeLinear", [x, b + "_scale", b + "_zp"], [b + "_q"],
                                                   name=b + "_q"))
                cache[x] = b + "_dq"
            mm.input[k] = cache[x]
        done += 1
    return done


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
    """Подграф length → output_1 (и маска /Less, если есть) без весов — на ORT для всех длин корпуса и 1…832."""
    import onnxruntime as ort
    m = onnx.load(str(Path(model_dir) / "model.onnx"), load_external_data=False)
    g = m.graph
    init = {i.name: i for i in g.initializer}
    prod = {o: n for n in g.node for o in n.output}
    pos = {id(n): k for k, n in enumerate(g.node)}
    outs = ["output_1"] + (["/Less_output_0"] if "/Less_output_0" in prod else [])
    need, todo, nodes = set(), list(outs), {}
    while todo:
        x = todo.pop()
        if x in init:
            need.add(x)
        elif x in prod and id(prod[x]) not in nodes:
            nodes[id(prod[x])] = prod[x]
            todo.extend(i for i in prod[x].input if i)
    ltype = next(i for i in g.input if i.name == "length").type.tensor_type.elem_type
    ldt = np.float32 if ltype == TensorProto.FLOAT else np.int64
    otype = next(o for o in g.output if o.name == "output_1").type.tensor_type.elem_type
    vi = [helper.make_tensor_value_info("output_1", otype, [1])]
    if len(outs) > 1:
        vi.append(helper.make_tensor_value_info(outs[1], TensorProto.BOOL, None))
    sub = helper.make_graph(sorted(nodes.values(), key=lambda n: pos[id(n)]), "len",
                            [helper.make_tensor_value_info("length", ltype, [1])], vi,
                            [inline(init[nm], model_dir) for nm in need])
    sm = helper.make_model(sub, opset_imports=m.opset_import, ir_version=m.ir_version)
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(sm.SerializeToString(), so, providers=["CPUExecutionProvider"])

    def run(T):
        r = sess.run(None, {"length": np.array([T], ldt)})
        L = int(np.rint(np.asarray(r[0]).ravel()[0]))
        mask_ok = True
        if len(r) > 1:
            mk = np.asarray(r[1]).ravel()
            mask_ok = bool(np.array_equal(mk, np.arange(mk.size) < L))
        return L, mask_ok

    bad = 0
    lines = [l.split("\t") for l in (FEAT / "list.tsv").read_text().splitlines() if l.strip()]
    for name, T in lines:
        got, mask_ok = run(int(T))
        ref = int((REF / f"{name}.len").read_text())
        bad += got != ref or not mask_ok
        if (got != ref or not mask_ok) and bad <= 5:
            print(f"  {name}: {T} кадров → {got}{'' if mask_ok else ', маска не та'}, а fp32 даёт {ref}")
    print(f"длина{' и маска' if len(outs) > 1 else ''}: {len(lines) - bad}/{len(lines)} совпадают с fp32; "
          f"все длины 1…832: ", end="")
    exp = lambda t: int(np.floor((np.floor((np.floor((t - 1) / 2 + 1) - 1) / 2 + 1) - 1) / 2 + 1))
    wrong = [t for t in range(1, 833) if run(t) != (exp(t), True)]
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
    ap.add_argument("--a8-matmul", choices=["in1", "both"])
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
    if a.a8_matmul:
        print(f"uint8: {a8_matmul(g, init, src, a.a8_matmul)} MatMul «активация × активация» (входы: {a.a8_matmul})")
    if a.float_length:
        float_length(g)
        print("длина: вход и выход float32 в точной сетке, маска — сравнением в сетке (Cast в целые убраны)")
    # так же проверяет AI Hub при загрузке; веса для вывода форм не нужны
    onnx.shape_inference.infer_shapes(m, check_type=True, strict_mode=True)
    dst.mkdir(parents=True, exist_ok=True)
    data = dst / "model.data"
    if not data.exists():
        os.link(src / "model.data", data)
    onnx.save(m, str(dst / "model.onnx"))   # внешние веса остаются ссылками на model.data
    print(f"→ {dst}")


if __name__ == "__main__":
    sys.exit(main())
