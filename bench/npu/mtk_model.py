#!/usr/bin/env python3
"""Кодировщик parakeet для NPU MediaTek (дополнение ONNX Runtime для NeuroPilot): постоянная форма, веса fp32.

Квантованная модель приложения для Snapdragon (app_model.py, w8a16) сюда не годится: у MediaTek 16-битные
активации только симметричные, а дополнение (onnxruntime-ep-neuropilot v0.3.0, neuron_qdq_support.cc) читает
нулевые точки только int8/uint8/int32. Зато у Neuron есть расчёт fp32 в fp16 (NEURON_FLAG_USE_FP16 →
NeuronModel_relaxComputationFloat32toFloat16), а fp16 на Snapdragon дал WER fp32 (results/2026-11-08-npu-aihub.md).

У NPU нет динамических форм, а в исходной модели parakeet (encoder-model.onnx + .data, 2,4 ГБ) половина узлов
(Shape, Gather, Concat…) только считает формы. Здесь вход фиксируется как у NpuAsr: признаки 1 × 128 × 832, длина —
float [1] (внутри Cast в int64), длина на выходе — тоже float. Затем ONNX Runtime на уровне BASIC сворачивает
постоянные (без своих операций com.microsoft — их дополнение не знает) и сохраняет модель с весами во внешнем
файле. Имена файлов — как ждёт NpuAsr: OUT/encoder-npu.onnx (+ .data) и OUT/nemo128.onnx.

  mtk_model.py [--out DIR]                       # собрать fp32 (OUT, по умолчанию .stand/npu/mtk/fp32)
  mtk_model.py --fp16 [--out DIR]                # из неё fp16 → OUT/../fp16 (вдвое меньше; вход и выход fp32)
  mtk_model.py --check [--out DIR] [--clips 4]   # выходы против исходной fp32 на фразах корпуса (ORT, процессор)
"""
import argparse
import os
import shutil
import sys
import time
from collections import Counter
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

P = Path("/home/not_me/antigravity-workspace/agentstuff/agenttranslator")
FP32 = P / ".stand/npu/parakeet-fp32"          # encoder-model.onnx + encoder-model.onnx.data (исходные веса)
NEMO = P / ".stand/npu/parakeet/nemo128.onnx"
FEAT = P / ".stand/npu/asrfeat"                # признаки корпуса 1 × 128 × 832 и list.tsv (имя, длина)
OUT = P / ".stand/npu/mtk/fp32"
F = 832


def build(out):
    out.mkdir(parents=True, exist_ok=True)
    m = onnx.load(str(FP32 / "encoder-model.onnx"), load_external_data=False)
    g = m.graph
    ins = {i.name: i for i in g.input}
    outs = {o.name: o for o in g.output}
    assert set(ins) == {"audio_signal", "length"} and set(outs) == {"outputs", "encoded_lengths"}, (list(ins), list(outs))

    def fix(v, elem, dims):
        t = v.type.tensor_type
        t.elem_type = elem
        del t.shape.dim[:]
        for d in dims:
            t.shape.dim.add().dim_value = d

    # длина: float [1] на входе → int64 внутри (дальше путь длины исходный)
    for n in g.node:
        for k, x in enumerate(n.input):
            if x == "length":
                n.input[k] = "length_i64"
    g.node.insert(0, helper.make_node("Cast", ["length"], ["length_i64"], name="mtk_length_in", to=TensorProto.INT64))
    fix(ins["length"], TensorProto.FLOAT, [1])
    fix(ins["audio_signal"], TensorProto.FLOAT, [1, 128, F])
    # длина на выходе: int64 → float
    for n in g.node:
        for k, x in enumerate(n.output):
            if x == "encoded_lengths":
                n.output[k] = "encoded_lengths_i64"
    g.node.append(helper.make_node("Cast", ["encoded_lengths_i64"], ["encoded_lengths"], name="mtk_length_out", to=TensorProto.FLOAT))
    fix(outs["encoded_lengths"], TensorProto.FLOAT, [1])
    fix(outs["outputs"], TensorProto.FLOAT, [1, 1024, F // 8])
    del g.value_info[:]          # старые типы с динамическими размерами — ORT выведет заново

    # промежуточная модель рядом с весами (внешние данные ищутся по относительному пути)
    tmp = out / "tmp-in"
    tmp.mkdir(exist_ok=True)
    data = tmp / "encoder-model.onnx.data"
    if not data.exists():
        os.link(FP32 / "encoder-model.onnx.data", data)        # жёсткая ссылка, без копии 2,4 ГБ
    onnx.save(m, str(tmp / "encoder-model.onnx"))

    import onnxruntime as ort
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    so.inter_op_num_threads = 1
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    so.optimized_model_filepath = str(out / "encoder-npu.onnx")
    so.add_session_config_entry("session.optimized_model_external_initializers_file_name", "encoder-npu.onnx.data")
    so.add_session_config_entry("session.optimized_model_external_initializers_min_size_in_bytes", "1024")
    for f in ("encoder-npu.onnx", "encoder-npu.onnx.data"):
        (out / f).unlink(missing_ok=True)
    t0 = time.time()
    ort.InferenceSession(str(tmp / "encoder-model.onnx"), so, providers=["CPUExecutionProvider"])
    print(f"ORT BASIC: {time.time() - t0:.0f} с")
    shutil.rmtree(tmp)
    shutil.copyfile(NEMO, out / "nemo128.onnx")

    r = onnx.load(str(out / "encoder-npu.onnx"), load_external_data=False)
    float_masks(r, out)
    conv_time_major(r)
    conv2d(r)
    reshape_rank_ops(r)
    onnx.save(r, str(out / "encoder-npu.onnx"))      # только граф: веса остаются в encoder-npu.onnx.data
    report(out / "encoder-npu.onnx", r)


def report(path, r):
    doms = {n.domain for n in r.graph.node if n.domain}
    data = Path(str(path) + ".data")
    size = (data.stat().st_size if data.exists() else 0) + path.stat().st_size
    print(f"{path}: узлов {len(r.graph.node)}, {size / 1e9:.2f} ГБ, чужие домены: {sorted(doms) or 'нет'}")
    print("операции:", dict(Counter(n.op_type for n in r.graph.node).most_common()))
    for v in list(r.graph.input) + list(r.graph.output):
        t = v.type.tensor_type
        print("  ", v.name, TensorProto.DataType.Name(t.elem_type), [d.dim_value or d.dim_param for d in t.shape.dim])


def float_masks(m, base):
    """Маски длины — арифметикой во float вместо bool: у дополнения NeuroPilot нет Where, Less, Not, And, Tile
    (op_builder_factory.cc), и каждая такая операция рвала бы граф между NPU и процессором (их 72 + 9).

    Исходно (NeMo, после свёртки постоянных): Lf = длина после сжатия в 8 раз (Floor_2, float) → Cast int →
    valid[1,104] = Less(0..103, Lf); свёртка: Where(Not valid, 0, X); внимание: m = valid_i ∧ valid_j ∧ C
    (C — постоянная окна внимания), Where(¬m, −10000, S) до softmax и Where(¬m, 0, P) после.
    Здесь: v = Relu(Lf − r) − Relu(Lf − r − 1) (длины целые — ровно 0 или 1); X·v; S·m + (m − 1)·10000; P·m. Длина на выходе —
    сам Lf (float), путь длины — без int64. Для целых длин результат тот же до бита, кроме S·m (ровно −10000 → то же)."""
    g = m.graph
    init = {i.name: i for i in g.initializer}
    by_name = {n.name: n for n in g.node}
    need = ["mtk_length_in", "/pre_encode/Cast", "/pre_encode/Cast_3", "/Cast", "mtk_length_out", "/Unsqueeze_3", "/Less",
            "/Not_1", "/layers.0/conv/Unsqueeze", "/Unsqueeze_4", "/Tile", "/Transpose_1", "/And", "/And_1", "/Not",
            "/layers.0/self_attn/Unsqueeze_35"]
    miss = [k for k in need if k not in by_name]
    assert not miss, f"другой граф — нет узлов {miss}"
    floor = by_name["/pre_encode/Cast_3"].input[0]                 # Lf, float [1]
    rng = numpy_helper.to_array(init[by_name["/Less"].input[0]], base_dir=str(base))  # 0..103, int64 [1, 104]
    ctxm = numpy_helper.to_array(init[by_name["/And_1"].input[1]], base_dir=str(base))  # bool [1, 104, 104], во внешнем файле
    T = rng.shape[1]
    # длина: вход float сразу в путь длины, выход — Lf
    for n in g.node:
        for k, x in enumerate(n.input):
            if x == "/pre_encode/Cast_output_0":
                n.input[k] = "length"
    pad_att = by_name["/layers.0/self_attn/Unsqueeze_35"].output[0]
    pad_conv = by_name["/layers.0/conv/Unsqueeze"].output[0]
    drop = set(need)
    keep = [n for n in g.node if n.name not in drop]

    def c(name, arr):
        g.initializer.append(numpy_helper.from_array(np.asarray(arr), name))
        return name

    mk = []
    mk.append(helper.make_node("Sub", [floor, c("mtk_range", rng.astype(np.float32))], ["mtk_v0"], name="mtk_v0"))
    # Clip(0, 1) дополнение берёт только с границами [0, 6] или [−1, 1] — поэтому Relu(x) − Relu(x − 1): для целых то же
    mk.append(helper.make_node("Relu", ["mtk_v0"], ["mtk_r0"], name="mtk_r0"))
    mk.append(helper.make_node("Sub", ["mtk_v0", c("mtk_one", np.array(1, np.float32))], ["mtk_v1"], name="mtk_v1"))
    mk.append(helper.make_node("Relu", ["mtk_v1"], ["mtk_r1"], name="mtk_r1"))
    mk.append(helper.make_node("Sub", ["mtk_r0", "mtk_r1"], ["mtk_valid"], name="mtk_valid"))          # [1, T]
    mk.append(helper.make_node("Reshape", ["mtk_valid", c("mtk_sh_conv", np.array([1, 1, T], np.int64))], ["mtk_vconv"], name="mtk_vconv"))
    mk.append(helper.make_node("Reshape", ["mtk_valid", c("mtk_sh_col", np.array([1, T, 1], np.int64))], ["mtk_vcol"], name="mtk_vcol"))
    mk.append(helper.make_node("Mul", ["mtk_vcol", "mtk_vconv"], ["mtk_m2"], name="mtk_m2"))           # [1, T, T]
    last = "mtk_m2"
    if not ctxm.all():
        mk.append(helper.make_node("Mul", [last, c("mtk_ctx", ctxm.astype(np.float32))], ["mtk_m2c"], name="mtk_m2c"))
        last = "mtk_m2c"
    mk.append(helper.make_node("Reshape", [last, c("mtk_sh_att", np.array([1, 1, T, T], np.int64))], ["mtk_matt"], name="mtk_matt"))
    mk.append(helper.make_node("Sub", ["mtk_matt", "mtk_one"], ["mtk_m1"], name="mtk_m1"))
    mk.append(helper.make_node("Mul", ["mtk_m1", c("mtk_big", np.array(10000, np.float32))], ["mtk_bias"], name="mtk_bias"))

    out, kinds = [], Counter()
    for n in keep:
        if n.op_type != "Where":
            out.append(n)
            continue
        cond, x, y = n.input
        xv = float(numpy_helper.to_array(init[x])) if x in init else None
        if cond == pad_att and xv == -10000.0:
            out.append(helper.make_node("Mul", [y, "mtk_matt"], [n.output[0] + "_m"], name=n.name + "_m"))
            out.append(helper.make_node("Add", [n.output[0] + "_m", "mtk_bias"], [n.output[0]], name=n.name + "_b"))
            kinds["внимание до softmax"] += 1
        elif cond == pad_att and xv == 0.0:
            out.append(helper.make_node("Mul", [y, "mtk_matt"], [n.output[0]], name=n.name + "_m"))
            kinds["внимание после softmax"] += 1
        elif cond == pad_conv and xv == 0.0:
            out.append(helper.make_node("Mul", [y, "mtk_vconv"], [n.output[0]], name=n.name + "_m"))
            kinds["свёртка"] += 1
        else:
            raise SystemExit(f"{n.name}: неизвестный Where ({cond}, {x}={xv})")
    # Lf (mtk_lf) — на выход encoded_lengths через Identity: внутри графа выход не читается, иначе после fp16
    # (keep_io_types ставит Cast в fp32 на выходе) маски получили бы fp32 вместо fp16
    for n in out + mk:
        for k, o in enumerate(n.output):
            if o == floor:
                n.output[k] = "mtk_lf"
        for k, x in enumerate(n.input):
            if x == floor:
                n.input[k] = "mtk_lf"
    # маски — сразу после узла, дающего Lf (порядок узлов в ONNX должен быть топологическим)
    pos = next(i for i, n in enumerate(out) if "mtk_lf" in n.output) + 1
    out[pos:pos] = mk + [helper.make_node("Identity", ["mtk_lf"], ["encoded_lengths"], name="mtk_length_out")]
    del g.node[:]
    g.node.extend(out)
    used = {i for n in g.node for i in n.input}
    left = [i for i in g.initializer if i.name in used]
    del g.initializer[:]
    g.initializer.extend(left)
    del g.value_info[:]
    print("маски во float:", dict(kinds), "; окно внимания", "всё" if ctxm.all() else "с ограничением")


def conv_time_major(m, T=F // 8, C=1024):
    """Модуль свёртки conformer'а — по времени [1, T, C], без трёхмерных [1, C, T] между 4D-свёртками. На POCO 09.11
    так собранный модуль (Transpose → Conv 1×1 → Split по каналам → … → Transpose) Neuron считал неверно: разница
    1780 при размахе 2010 (mtk_ops.py r05), хотя по отдельности FF, внимание и depthwise-свёртка в форме NHWC верны.
    Здесь: точечные свёртки — Gemm(transB=1) (FULLY_CONNECTED берёт веса [out, in] как есть — байты весов те же);
    GLU — Split по последней оси; маска — mtk_vcol [1, T, 1]; depthwise 1×9 — Reshape [1,1,T,C] → Transpose в NCHW →
    Conv → Transpose → Reshape (как проба q04, верна на NPU). Явный Pad свёртки — в её pads."""
    g = m.graph
    init = {i.name: i for i in g.initializer}
    by = {n.name: n for n in g.node}
    shapes = {}

    def cs(name, arr):
        if name not in shapes:
            g.initializer.append(numpy_helper.from_array(np.array(arr, np.int64), name))
            shapes[name] = True
        return name

    drop, new_at, k = set(), {}, 0
    for L in range(64):
        pre = f"/layers.{L}/conv/"
        if pre + "pointwise_conv1/Conv" not in by:
            break
        n = {s: by[pre + s] for s in ("Transpose", "pointwise_conv1/Conv", "Split", "Sigmoid", "Mul", "Where_m",
                                      "depthwise_conv/Pad", "depthwise_conv/Conv", "activation/Sigmoid",
                                      "activation/Mul", "pointwise_conv2/Conv", "Transpose_1")}
        x = n["Transpose"].input[0]                                   # выход LayerNorm, [1, T, C]
        out = n["Transpose_1"].output[0]
        w1, w2, wd = (init[n[s].input[1]] for s in ("pointwise_conv1/Conv", "pointwise_conv2/Conv", "depthwise_conv/Conv"))
        assert len(n["pointwise_conv1/Conv"].input) == 2 and len(n["pointwise_conv2/Conv"].input) == 2, "у точечных свёрток есть смещение"
        bd = n["depthwise_conv/Conv"].input[2]
        pads = numpy_helper.to_array(init[n["depthwise_conv/Pad"].input[1]])
        assert list(pads) == [0, 0, 4, 0, 0, 4] and list(wd.dims) == [C, 1, 9], (pads, wd.dims)
        C2 = w1.dims[0]
        w1.dims[:] = [C2, C]                                         # [2C, C, 1] → [2C, C]: те же байты
        w2.dims[:] = [C, C]
        wd.dims[:] = [C, 1, 1, 9]
        t = lambda s: f"{pre}tm_{s}"
        nodes = [
            helper.make_node("Reshape", [x, cs("mtk_sh_tc", [T, C])], [t("x2")], name=t("x2")),
            helper.make_node("Gemm", [t("x2"), w1.name], [t("p1")], name=t("pw1"), transB=1),
            helper.make_node("Reshape", [t("p1"), cs("mtk_sh_1t2c", [1, T, C2])], [t("p1r")], name=t("p1r")),
            helper.make_node("Split", [t("p1r"), cs("mtk_split_cc", [C2 // 2, C2 // 2])], [t("a"), t("b")], name=t("glu_split"), axis=-1),
            helper.make_node("Sigmoid", [t("b")], [t("sb")], name=t("glu_sig")),
            helper.make_node("Mul", [t("a"), t("sb")], [t("g")], name=t("glu")),
            helper.make_node("Mul", [t("g"), "mtk_vcol"], [t("gm")], name=t("mask")),
            helper.make_node("Reshape", [t("gm"), cs("mtk_sh_11tc", [1, 1, T, C])], [t("h4")], name=t("h4")),
            helper.make_node("Transpose", [t("h4")], [t("hn")], name=t("to_nchw"), perm=[0, 3, 1, 2]),
            helper.make_node("Conv", [t("hn"), wd.name, bd], [t("d")], name=t("dw"), kernel_shape=[1, 9], pads=[0, 4, 0, 4], group=C),
            helper.make_node("Transpose", [t("d")], [t("dh")], name=t("to_nhwc"), perm=[0, 2, 3, 1]),
            helper.make_node("Reshape", [t("dh"), cs("mtk_sh_1tc", [1, T, C])], [t("dt")], name=t("dt")),
            helper.make_node("Sigmoid", [t("dt")], [t("sd")], name=t("swish_sig")),
            helper.make_node("Mul", [t("dt"), t("sd")], [t("s")], name=t("swish")),
            helper.make_node("Reshape", [t("s"), "mtk_sh_tc"], [t("s2")], name=t("s2")),
            helper.make_node("Gemm", [t("s2"), w2.name], [t("p2")], name=t("pw2"), transB=1),
            helper.make_node("Reshape", [t("p2"), "mtk_sh_1tc"], [out], name=t("out")),
        ]
        drop |= {v.name for v in n.values()}
        new_at[n["Transpose"].name] = nodes
        k += 1
    res = []
    for nd in g.node:
        if nd.name in new_at:
            res.extend(new_at[nd.name])
        elif nd.name not in drop:
            res.append(nd)
    del g.node[:]
    g.node.extend(res)
    used = {i for nd in g.node for i in nd.input}
    left = [i for i in g.initializer if i.name in used]
    del g.initializer[:]
    g.initializer.extend(left)
    del g.value_info[:]
    print(f"модуль свёртки по времени: {k} слоёв")


def conv2d(m):
    """Conv1D → Conv2D высотой 1: у дополнения NeuroPilot только двумерная свёртка («Only conv 2d is supported»,
    журнал POCO 09.11), а в каждом слое conformer'а их три (pointwise 1, depthwise 9, pointwise 2) — 72 узла.
    X [N,C,T] → Unsqueeze(2) → Conv(ядро 1×K, отступы 0/p, шаг 1/s) → Squeeze(2). Веса [Co,Ci,K] → [Co,Ci,1,K]: те же
    байты (во внешнем файле не меняются), поменялась только форма. Результат тот же до бита."""
    g = m.graph
    init = {i.name: i for i in g.initializer}
    users = Counter(x for n in g.node for x in n.input)
    ax = "mtk_axis2"
    g.initializer.append(numpy_helper.from_array(np.array([2], np.int64), ax))
    out, k1 = [], 0
    for n in g.node:
        w = init.get(n.input[1]) if n.op_type == "Conv" and len(n.input) > 1 else None
        if w is None or len(w.dims) != 3:
            out.append(n)
            continue
        assert users[w.name] == 1, f"{n.name}: веса {w.name} общие — форму не поменять"
        at = {a.name: helper.get_attribute_value(a) for a in n.attribute}
        K = w.dims[2]
        pads = at.get("pads", [0, 0])
        assert at.get("auto_pad", b"NOTSET") in (b"NOTSET", "NOTSET"), f"{n.name}: auto_pad"
        w.dims[:] = [w.dims[0], w.dims[1], 1, K]
        x4, y4 = n.input[0] + "_h1_" + str(k1), n.output[0] + "_h1"
        out.append(helper.make_node("Unsqueeze", [n.input[0], ax], [x4], name=n.name + "_h1_in"))
        attrs = dict(kernel_shape=[1, K], pads=[0, pads[0], 0, pads[1]], strides=[1, at.get("strides", [1])[0]],
                     dilations=[1, at.get("dilations", [1])[0]], group=at.get("group", 1))
        out.append(helper.make_node("Conv", [x4] + list(n.input[1:]), [y4], name=n.name, **attrs))
        out.append(helper.make_node("Squeeze", [y4, ax], [n.output[0]], name=n.name + "_h1_out"))
        k1 += 1
    del g.node[:]
    g.node.extend(out)
    del g.value_info[:]
    print(f"свёртки 1D → 2D: {k1}")


def reshape_rank_ops(m):
    """Unsqueeze/Squeeze → Reshape с постоянной формой. Дополнение NeuroPilot теряет измерение на Unsqueeze/Squeeze
    рядом со свёрткой: вход DEPTHWISE_CONV_2D выходил [104, 1, 1024] при выходе [1, 1, 104, 1024], и Neuron отвергал
    всю часть графа (POCO 09.11, mtk_ops.py p06/p07/p09); через Reshape та же свёртка проходит (q02, q04)."""
    from onnx import shape_inference
    mi = shape_inference.infer_shapes(m)
    shapes = {v.name: [d.dim_value for d in v.type.tensor_type.shape.dim]
              for v in list(mi.graph.value_info) + list(mi.graph.output) + list(mi.graph.input)}
    k = 0
    for i, n in enumerate(m.graph.node):
        if n.op_type not in ("Unsqueeze", "Squeeze"):
            continue
        shp = shapes.get(n.output[0])
        assert shp and all(d > 0 for d in shp), f"{n.name}: форма выхода неизвестна ({shp})"
        name = f"mtk_shape_{k}"
        k += 1
        m.graph.initializer.append(numpy_helper.from_array(np.array(shp, np.int64), name))
        m.graph.node[i].CopyFrom(helper.make_node("Reshape", [n.input[0], name], list(n.output), name=n.name + "_r"))
    used = {i for n in m.graph.node for i in n.input}
    left = [i for i in m.graph.initializer if i.name in used]
    del m.graph.initializer[:]
    m.graph.initializer.extend(left)
    del m.graph.value_info[:]
    print(f"Unsqueeze/Squeeze → Reshape: {k}")


def to_fp16(src, dst):
    """fp32 → fp16, вход и выход fp32 (как to_fp16.py для Snapdragon). Внутренние Cast в float → в float16."""
    from onnxconverter_common import float16
    m = onnx.load(str(src / "encoder-npu.onnx"))
    m16 = float16.convert_float_to_float16(m, keep_io_types=True, disable_shape_infer=True)
    outs = {o.name for o in m16.graph.output}
    for n in m16.graph.node:
        if n.op_type == "Cast" and n.output[0] not in outs and "graph_output_cast" not in n.name:
            for at in n.attribute:
                if at.name == "to" and at.i == TensorProto.FLOAT:
                    at.i = TensorProto.FLOAT16
    del m16.graph.value_info[:]
    dst.mkdir(parents=True, exist_ok=True)
    for f in ("encoder-npu.onnx", "encoder-npu.onnx.data"):
        (dst / f).unlink(missing_ok=True)
    onnx.save(m16, str(dst / "encoder-npu.onnx"), save_as_external_data=True, location="encoder-npu.onnx.data",
              size_threshold=1024)
    shutil.copyfile(NEMO, dst / "nemo128.onnx")
    report(dst / "encoder-npu.onnx", onnx.load(str(dst / "encoder-npu.onnx"), load_external_data=False))


def check(out, clips):
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    so.inter_op_num_threads = 1
    a = ort.InferenceSession(str(FP32 / "encoder-model.onnx"), so, providers=["CPUExecutionProvider"])
    b = ort.InferenceSession(str(out / "encoder-npu.onnx"), so, providers=["CPUExecutionProvider"])
    lines = [l.split("\t") for l in (FEAT / "list.tsv").read_text().splitlines() if l.strip()]
    pick = [x for x in lines if x[0].startswith("pt_")][::41][: (clips + 1) // 2] + \
           [x for x in lines if x[0].startswith("ru_")][::41][: clips // 2]
    worst = 0.0
    for name, T in pick:
        T = int(T)
        x = np.fromfile(FEAT / f"{name}.f32", np.float32).reshape(1, 128, F)
        # то же окно с нулями, что и у NpuAsr: без нулей края последних кадров после сжатия в 8 раз другие
        # (свёртки pre_encode маской не закрыты) — это свойство окна, а не преобразования
        ra = a.run(None, {"audio_signal": x, "length": np.array([T], np.int64)})
        rb = b.run(None, {"audio_signal": x, "length": np.array([float(T)], np.float32)})
        n = int(ra[1][0])
        d = float(np.max(np.abs(ra[0][:, :, :n].astype(np.float64) - rb[0][:, :, :n].astype(np.float64))))
        worst = max(worst, d)
        print(f"{name} T={T}: кадров {n} / {rb[1][0]:.0f}, макс. разница {d:.3g} (размах {np.abs(ra[0]).max():.3g})")
    print(f"худшая разница {worst:.3g}")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--fp16", action="store_true")
    ap.add_argument("--clips", type=int, default=4)
    a = ap.parse_args()
    if a.check:
        return check(Path(a.out), a.clips)
    if a.fp16:
        to_fp16(Path(a.out), Path(a.out).parent / "fp16")
        return 0
    build(Path(a.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
