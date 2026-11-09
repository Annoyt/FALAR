#!/usr/bin/env python3
"""Кодировщик parakeet для NPU MediaTek в A16W8: веса int8 по каналам, активации int16 симметрично (своя сборка
дополнения NeuroPilot — bench/npu/neuropilot-ep; пробы на POCO 09.11: FC, depthwise, смесь с float LayerNorm верны).

Из модели fp32 для MediaTek (mtk_model.py: постоянная форма, маски во float, модуль свёртки по времени):
- Gemm(transB=1) → MatMul с транспонированными весами (+ Add смещения): Gemm со смещением int32 в A16W8 Neuron не
  собирает (проба p18);
- набор операций ONNX → 21: int16 в стандартных QuantizeLinear/DequantizeLinear, а не в com.microsoft;
- калибровка MinMax на фразах публичного корпуса (признаки .stand/npu/asrfeat), квантование ONNX Runtime — только
  MatMul с постоянными 2D-весами и Conv; внимание (q·kᵀ, attn·v, позиционная часть с 4D-постоянной), LayerNorm,
  Softmax, маски — во float (fp16 на NPU);
- нулевые точки int16 убираются (у QuantizeLinear — output_dtype=INT16): дополнение читает нули только
  int8/uint8/int32, а симметричным int16 ноль и не нужен.
Выход — OUT/encoder-npu.onnx (веса int8 внутри, ~0,6 ГБ) и nemo128.onnx, как ждёт NpuAsr.

  mtk_quant.py [--calib 40]            # → .stand/npu/mtk/a16w8
  mtk_quant.py --check [--clips 4]     # выходы против исходной fp32 на фразах корпуса (ORT, процессор)
"""
import argparse
import shutil
import sys
import time
from collections import Counter
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper
from onnxruntime.quantization import CalibrationDataReader

P = Path("/home/not_me/antigravity-workspace/agentstuff/agenttranslator")
SRC = P / ".stand/npu/mtk/fp32"
OUT = P / ".stand/npu/mtk/a16w8"
FEAT = P / ".stand/npu/asrfeat"
FP32 = P / ".stand/npu/parakeet-fp32"
F = 832


def prequant(out):
    """Gemm → MatMul, opset 21; fp32 с внешними весами — вход квантователя."""
    m = onnx.load(str(SRC / "encoder-npu.onnx"))                      # с весами (2,4 ГБ)
    g = m.graph
    init = {i.name: i for i in g.initializer}
    nodes, k = [], 0
    for n in g.node:
        if n.op_type != "Gemm":
            nodes.append(n)
            continue
        at = {a.name: helper.get_attribute_value(a) for a in n.attribute}
        assert at.get("transA", 0) == 0 and at.get("alpha", 1.0) == 1.0 and at.get("beta", 1.0) == 1.0, (n.name, at)
        if at.get("transB", 0) == 1:                                   # точечные свёртки: [N, K] → [K, N]
            w = numpy_helper.to_array(init[n.input[1]])
            wt = numpy_helper.from_array(np.ascontiguousarray(w.T), n.input[1] + "_t")
            g.initializer.append(wt)
        else:                                                          # pre_encode/out (MatMulAddFusion): уже [K, N]
            wt = init[n.input[1]]
        mm_out = n.output[0] if len(n.input) < 3 or not n.input[2] else n.output[0] + "_mm"
        nodes.append(helper.make_node("MatMul", [n.input[0], wt.name], [mm_out], name=n.name + "_mm"))
        if mm_out != n.output[0]:
            nodes.append(helper.make_node("Add", [mm_out, n.input[2]], [n.output[0]], name=n.name + "_bias"))
        k += 1
    del g.node[:]
    g.node.extend(nodes)
    used = {i for n in g.node for i in n.input}
    left = [i for i in g.initializer if i.name in used]
    del g.initializer[:]
    g.initializer.extend(left)
    for o in m.opset_import:
        if o.domain in ("", "ai.onnx"):
            o.version = 21
    m.ir_version = max(m.ir_version, 10)
    d = out / "prequant"
    d.mkdir(parents=True, exist_ok=True)
    for f in d.glob("*"):
        f.unlink()
    onnx.save(m, str(d / "encoder.onnx"), save_as_external_data=True, location="encoder.onnx.data", size_threshold=1024)
    print(f"Gemm → MatMul: {k}; opset 21 → {d}")
    return d / "encoder.onnx"


def calib_list(n):
    lines = [l.split("\t") for l in (FEAT / "list.tsv").read_text().splitlines() if l.strip()]
    step = max(1, len(lines) // n)
    return lines[1::step][:n]                                         # смешанно pt/ru, мимо фраз проверки (::41)


class Reader(CalibrationDataReader):
    def __init__(self, items):
        self.items = list(items)

    def get_next(self):
        if not self.items:
            return None
        name, T = self.items.pop(0)
        x = np.fromfile(FEAT / f"{name}.f32", np.float32).reshape(1, 128, F)
        return {"audio_signal": x, "length": np.array([float(T)], np.float32)}

    def rewind(self):
        pass


def quantize(pre, out, ncal):
    from onnxruntime.quantization import CalibrationMethod, QuantFormat, QuantType, quantize_static
    m = onnx.load(str(pre), load_external_data=False)
    init = {i.name: i for i in m.graph.initializer}
    excl = [n.name for n in m.graph.node if n.op_type == "MatMul" and (n.input[1] not in init or len(init[n.input[1]].dims) != 2)]
    # pre_encode (свёртки 3×3 шаг 2 и выход 4096→1024) — во float: в A16W8 Neuron считает его неверно (POCO 10.11,
    # проба q01: выход в ~2048 раз меньше), в fp16 — верно; весов там 4 млн из 600 млн
    pre_enc = [n.name for n in m.graph.node if n.name.startswith("/pre_encode/") and n.op_type in ("Conv", "MatMul")]
    excl += pre_enc
    nq = sum(1 for n in m.graph.node if n.op_type in ("MatMul", "Conv")) - len(excl)
    print(f"квантуются {nq} узлов MatMul/Conv; во float остаются {len(excl)}: внимание, позиции и pre_encode ({len(pre_enc)})")

    raw = out / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    quantize_static(str(pre), str(raw / "encoder.onnx"), Reader(calib_list(ncal)),
                    quant_format=QuantFormat.QDQ, op_types_to_quantize=["MatMul", "Conv"], per_channel=True,
                    reduce_range=False, activation_type=QuantType.QInt16, weight_type=QuantType.QInt8,
                    nodes_to_exclude=excl, use_external_data_format=True, calibrate_method=CalibrationMethod.MinMax,
                    # без CalibMaxIntermediateOutputs: в ORT 1.30 он сбрасывает собранное, не посчитав диапазоны
                    # («No data is collected»); MinMax и так хранит лишь ReduceMin/ReduceMax — по два числа на тензор
                    # MinimumRealRange: у 5 тензоров калибровка дала диапазон 0 → шкала 0, Neuron такой операнд не
                    # компилирует (POCO 10.11, «registered with scale 0 — invalid»)
                    extra_options={"ActivationSymmetric": True, "WeightSymmetric": True, "UseQDQContribOps": False,
                                   "MinimumRealRange": 1e-4})
    print(f"квантование ORT: {time.time() - t0:.0f} с ({ncal} фраз калибровки)")
    return raw / "encoder.onnx"


def strip_int16_zero_points(raw, out):
    """Нули int16 долой: у QuantizeLinear — атрибут output_dtype=INT16 вместо входа нулевой точки, у DequantizeLinear
    — просто без него (тип берётся из входа). Веса int8 и их нули int8 — как есть."""
    m = onnx.load(str(raw))                                           # веса int8 ~0,6 ГБ — в память, потом внутрь файла
    init = {i.name: i for i in m.graph.initializer}
    kq = kd = 0
    for n in m.graph.node:
        if n.op_type not in ("QuantizeLinear", "DequantizeLinear") or len(n.input) < 3 or not n.input[2]:
            continue
        zp = init.get(n.input[2])
        if zp is None or zp.data_type != TensorProto.INT16:
            continue
        assert not numpy_helper.to_array(zp).any(), f"{n.name}: ненулевая нулевая точка int16"
        del n.input[2]
        if n.op_type == "QuantizeLinear":
            n.attribute.append(helper.make_attribute("output_dtype", TensorProto.INT16))
            kq += 1
        else:
            kd += 1
    used = {i for n in m.graph.node for i in n.input}
    left = [i for i in m.graph.initializer if i.name in used]
    del m.graph.initializer[:]
    m.graph.initializer.extend(left)
    del m.graph.value_info[:]
    for f in ("encoder-npu.onnx", "encoder-npu.onnx.data"):
        (out / f).unlink(missing_ok=True)
    onnx.save(m, str(out / "encoder-npu.onnx"))
    shutil.copyfile(SRC / "nemo128.onnx", out / "nemo128.onnx")
    types = Counter(TensorProto.DataType.Name(i.data_type) for i in m.graph.initializer)
    print(f"нули int16 убраны: Q {kq}, DQ {kd}; постоянные по типам: {dict(types)}")
    print("операции:", dict(Counter(n.op_type for n in m.graph.node).most_common(12)))
    print(f"{out / 'encoder-npu.onnx'}: {(out / 'encoder-npu.onnx').stat().st_size / 1e9:.3f} ГБ")


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
    for name, T in pick:
        T = int(T)
        x = np.fromfile(FEAT / f"{name}.f32", np.float32).reshape(1, 128, F)
        ra = a.run(None, {"audio_signal": x, "length": np.array([T], np.int64)})
        rb = b.run(None, {"audio_signal": x, "length": np.array([float(T)], np.float32)})
        n = int(ra[1][0])
        u, v = ra[0][:, :, :n].astype(np.float64), rb[0][:, :, :n].astype(np.float64)
        cos = float((u * v).sum() / np.sqrt((u * u).sum() * (v * v).sum()))
        print(f"{name} T={T}: кадров {n} / {rb[1][0]:.0f}, макс. разница {np.abs(u - v).max():.3g} (размах {np.abs(u).max():.3g}), cos {cos:.5f}")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--calib", type=int, default=40)
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--clips", type=int, default=4)
    a = ap.parse_args()
    out = Path(a.out)
    if a.check:
        return check(out, a.clips)
    out.mkdir(parents=True, exist_ok=True)
    pre = prequant(out)
    raw = quantize(pre, out, a.calib)
    strip_int16_zero_points(raw, out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
