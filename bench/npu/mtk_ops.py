#!/usr/bin/env python3
"""Проба операций для NPU MediaTek: какую операцию кодировщика parakeet не берёт рантайм Neuron телефона.

POCO X7 Pro 09.11: дополнение NeuroPilot приняло 1605 узлов из 1620, но рантайм Neuron 8.2.26 отказал первой части
графа целиком («Some ops unsupported by devices: mtk-gpu, mtk-dsp, mtk-mdla», «Fail to verify the given module»),
не говоря, какая операция виновата. Здесь — маленькие модели по одной операции в той же форме, что в кодировщике
(у каждой впереди MatMul: части без conv/matmul дополнение отдаёт процессору), и два настоящих куска с нашими весами:
pre_encode и слой 0 (из .stand/npu/mtk/fp32). MtkOpProbe.java прогоняет их на телефоне (bench/npu/mtk_ops_device.sh).

  mtk_ops.py [--out DIR]          # → .stand/npu/mtk/ops/*.onnx
"""
import argparse
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

P = Path("/home/not_me/antigravity-workspace/agentstuff/agenttranslator")
SRC = P / ".stand/npu/mtk/fp32/encoder-npu.onnx"
OUT = P / ".stand/npu/mtk/ops"
T, D, H, DK, FF = 104, 1024, 8, 128, 4096
rng = np.random.default_rng(0)


def w(*shape, s=0.02):
    return (rng.standard_normal(shape) * s).astype(np.float32)


class G:
    """Мини-граф: вход X [1, T, D] → MatMul (чтобы часть не была «лёгкой») → проверяемые операции → выход."""

    def __init__(self, name, x_shape=(1, T, D)):
        self.name, self.nodes, self.inits, self.k = name, [], [], 0
        self.inputs = [helper.make_tensor_value_info("X", TensorProto.FLOAT, list(x_shape))]

    def c(self, arr, dtype=None):
        self.k += 1
        n = f"c{self.k}"
        self.inits.append(numpy_helper.from_array(np.asarray(arr, dtype=dtype), n))
        return n

    def op(self, op, ins, **attrs):
        self.k += 1
        o = f"t{self.k}"
        self.nodes.append(helper.make_node(op, ins, [o], name=f"{op}_{self.k}", **attrs))
        return o

    def ops(self, op, ins, n, **attrs):
        self.k += 1
        outs = [f"t{self.k}_{i}" for i in range(n)]
        self.nodes.append(helper.make_node(op, ins, outs, name=f"{op}_{self.k}", **attrs))
        return outs

    def save(self, y, out, opset=None):
        if opset is None:
            opset = 21 if any(n.op_type in ("QuantizeLinear", "DequantizeLinear") for n in self.nodes) else 17
        g = helper.make_graph(self.nodes, self.name, self.inputs,
                              [helper.make_tensor_value_info(y, TensorProto.FLOAT, None)], self.inits)
        m = helper.make_model(g, opset_imports=[helper.make_opsetid("", opset)], ir_version=10 if opset >= 21 else 8)
        m = onnx.shape_inference.infer_shapes(m)
        onnx.checker.check_model(m)
        onnx.save(m, str(out / f"{self.name}.onnx"))


def synthetic(out):
    g = G("p01_fc")                                         # FULLY_CONNECTED (контроль)
    g.save(g.op("MatMul", ["X", g.c(w(D, FF))]), out)

    g = G("p02_layernorm")                                  # LayerNorm — расширение MediaTek (код 65536)
    x = g.op("MatMul", ["X", g.c(w(D, D))])
    g.save(g.op("LayerNormalization", [x, g.c(1 + w(D)), g.c(w(D))], axis=-1, epsilon=1e-5), out)

    g = G("p03_swish")                                      # LOGISTIC + MUL
    x = g.op("MatMul", ["X", g.c(w(D, FF))])
    g.save(g.op("Mul", [x, g.op("Sigmoid", [x])]), out)

    def heads(g, x, n=H, dk=DK):                            # [1,T,n*dk] → [1,n,T,dk]
        return g.op("Transpose", [g.op("Reshape", [x, g.c([1, T, n, dk], np.int64)])], perm=[0, 2, 1, 3])

    g = G("p04_attention")                                  # BATCH_MATMUL, DIV, SOFTMAX
    q = heads(g, g.op("MatMul", ["X", g.c(w(D, D))]))
    k = heads(g, g.op("MatMul", ["X", g.c(w(D, D))]))
    v = heads(g, g.op("MatMul", ["X", g.c(w(D, D))]))
    s = g.op("Div", [g.op("MatMul", [q, g.op("Transpose", [k], perm=[0, 1, 3, 2])]), g.c(np.float32(np.sqrt(DK)))])
    a = g.op("Softmax", [s], axis=-1)
    g.save(g.op("MatMul", [a, v]), out)

    g = G("p05_relshift")                                   # PAD_V2, RESHAPE, SLICE (rel_shift NeMo)
    pl = 2 * T - 1
    x = g.op("MatMul", ["X", g.c(w(D, H * pl))])            # [1,T,8*207]
    x = g.op("Transpose", [g.op("Reshape", [x, g.c([1, T, H, pl], np.int64)])], perm=[0, 2, 1, 3])  # [1,8,T,207]
    x = g.op("Pad", [x, g.c([0, 0, 0, 1, 0, 0, 0, 0], np.int64), g.c(np.float32(0))], mode="constant")
    x = g.op("Reshape", [x, g.c([1, H, pl + 1, T], np.int64)])
    x = g.op("Slice", [x, g.c([1], np.int64), g.c([pl + 1], np.int64), g.c([2], np.int64)])
    x = g.op("Reshape", [x, g.c([1, H, T, pl], np.int64)])
    g.save(g.op("Slice", [x, g.c([0], np.int64), g.c([T], np.int64), g.c([3], np.int64)]), out)

    def conv_in(g):                                         # [1,T,D] → [1,D,1,T]
        x = g.op("MatMul", ["X", g.c(w(D, D))])
        return g.op("Unsqueeze", [g.op("Transpose", [x], perm=[0, 2, 1]), g.c([2], np.int64)])

    g = G("p06_glu")                                        # CONV_2D 1×1, SPLIT, LOGISTIC, MUL, SQUEEZE
    x = g.op("Conv", [conv_in(g), g.c(w(2 * D, D, 1, 1)), g.c(w(2 * D))], kernel_shape=[1, 1])
    a, b = g.ops("Split", [x, g.c([D, D], np.int64)], 2, axis=1)
    g.save(g.op("Squeeze", [g.op("Mul", [a, g.op("Sigmoid", [b])]), g.c([2], np.int64)]), out)

    g = G("p07_depthwise")                                  # DEPTHWISE_CONV_2D 1×9
    x = g.op("Conv", [conv_in(g), g.c(w(D, 1, 1, 9, s=0.2)), g.c(w(D))], kernel_shape=[1, 9], pads=[0, 4, 0, 4], group=D)
    g.save(g.op("Squeeze", [x, g.c([2], np.int64)]), out)

    g = G("p08_masks")                                      # маски: SUB, MUL, ADD с вещанием
    s = g.op("MatMul", [heads(g, g.op("MatMul", ["X", g.c(w(D, D))])),
                        g.op("Transpose", [heads(g, g.op("MatMul", ["X", g.c(w(D, D))]))], perm=[0, 1, 3, 2])])
    m = (rng.random((1, 1, T, T)) > 0.2).astype(np.float32)
    mc = g.c(m)
    bias = g.op("Mul", [g.op("Sub", [mc, g.c(np.float32(1))]), g.c(np.float32(10000))])
    g.save(g.op("Add", [g.op("Mul", [s, mc]), bias]), out)

    g = G("p09_pre_conv", x_shape=(1, 128, 832))           # pre_encode: CONV_2D 3×3 шаг 2, RELU, глубинная 3×3
    x = g.op("Unsqueeze", [g.op("Transpose", ["X"], perm=[0, 2, 1]), g.c([1], np.int64)])   # [1,1,832,128]
    x = g.op("Relu", [g.op("Conv", [x, g.c(w(256, 1, 3, 3, s=0.3)), g.c(w(256))], kernel_shape=[3, 3], strides=[2, 2], pads=[1, 1, 1, 1])])
    x = g.op("Conv", [x, g.c(w(256, 1, 3, 3, s=0.3)), g.c(w(256))], kernel_shape=[3, 3], strides=[2, 2], pads=[1, 1, 1, 1], group=256)
    x = g.op("Relu", [g.op("Conv", [x, g.c(w(256, 256, 1, 1, s=0.05)), g.c(w(256))], kernel_shape=[1, 1])])
    g.save(x, out)

    g = G("p10_floor")                                      # FLOOR (путь длины)
    x = g.op("MatMul", ["X", g.c(w(D, D))])
    g.save(g.op("Floor", [g.op("Mul", [x, g.c(np.float32(100))])]), out)

    g = G("p11_ln_big")                                     # LayerNorm на больших значениях (поток остатка)
    x = g.op("Mul", [g.op("MatMul", ["X", g.c(w(D, D))]), g.c(np.float32(300))])
    g.save(g.op("LayerNormalization", [x, g.c(1 + w(D)), g.c(w(D))], axis=-1, epsilon=1e-5), out)


def layouts(out):
    """Обходы для свёрток (09.11: и NHWC, и NCHW ломаются на переходах 3D↔4D через Unsqueeze/Squeeze)."""
    g = G("q01_conv_4d_in", x_shape=(1, 1, 832, 128))     # чистый 4D-вход: 3×3 шаг 2 + Relu
    g.save(g.op("Relu", [g.op("Conv", ["X", g.c(w(256, 1, 3, 3, s=0.3)), g.c(w(256))], kernel_shape=[3, 3], strides=[2, 2], pads=[1, 1, 1, 1])]), out)

    g = G("q02_conv_reshape_in", x_shape=(1, 832, 128))    # 3D → Reshape → 4D (вместо Unsqueeze)
    x = g.op("Reshape", ["X", g.c([1, 1, 832, 128], np.int64)])
    g.save(g.op("Relu", [g.op("Conv", [x, g.c(w(256, 1, 3, 3, s=0.3)), g.c(w(256))], kernel_shape=[3, 3], strides=[2, 2], pads=[1, 1, 1, 1])]), out)

    g = G("q03_dw_4d_in", x_shape=(1, D, 1, T))            # depthwise 1×9 на чистом 4D
    g.save(g.op("Conv", ["X", g.c(w(D, 1, 1, 9, s=0.2)), g.c(w(D))], kernel_shape=[1, 9], pads=[0, 4, 0, 4], group=D), out)

    g = G("q04_dw_reshape_nhwc")                           # [1,T,C] → Reshape [1,1,T,C] → Transpose → dw → обратно
    x = g.op("MatMul", ["X", g.c(w(D, D))])
    x = g.op("Transpose", [g.op("Reshape", [x, g.c([1, 1, T, D], np.int64)])], perm=[0, 3, 1, 2])          # [1,C,1,T]
    x = g.op("Conv", [x, g.c(w(D, 1, 1, 9, s=0.2)), g.c(w(D))], kernel_shape=[1, 9], pads=[0, 4, 0, 4], group=D)
    g.save(g.op("Reshape", [g.op("Transpose", [x], perm=[0, 2, 3, 1]), g.c([1, T, D], np.int64)]), out)

    g = G("q05_dw_shiftsum")                               # depthwise без свёртки: Pad по времени + 9 сдвигов × вес
    x = g.op("MatMul", ["X", g.c(w(D, D))])
    xp = g.op("Pad", [x, g.c([0, 4, 0, 0, 4, 0], np.int64), g.c(np.float32(0))], mode="constant")      # [1,T+8,C]
    kw = w(9, D, s=0.2)
    acc = None
    for k in range(9):
        sl = g.op("Slice", [xp, g.c([k], np.int64), g.c([k + T], np.int64), g.c([1], np.int64)])
        t = g.op("Mul", [sl, g.c(kw[k])])
        acc = t if acc is None else g.op("Add", [acc, t])
    g.save(g.op("Add", [acc, g.c(w(D))]), out)

    g = G("q06_pointwise_glu_mm")                          # точечная свёртка как MatMul по времени + GLU по последней оси
    x = g.op("Add", [g.op("MatMul", ["X", g.c(w(D, 2 * D))]), g.c(w(2 * D))])
    a, b = g.ops("Split", [x, g.c([D, D], np.int64)], 2, axis=-1)
    g.save(g.op("Mul", [a, g.op("Sigmoid", [b])]), out)


def const_bmm(out):
    """MatMul с постоянным 4D-множителем (позиционная часть внимания: (q + bias_v) @ p, p [1,8,128,207]) — дополнение
    раскладывает его на FULLY_CONNECTED по головам + CONCATENATION; слой 0 на POCO 09.11 расходился с процессором."""
    def heads(g, x):
        return g.op("Transpose", [g.op("Reshape", [x, g.c([1, T, H, DK], np.int64)])], perm=[0, 2, 1, 3])
    pos = w(1, H, DK, 2 * T - 1, s=0.5)
    g = G("p12_const_bmm")
    g.save(g.op("MatMul", [heads(g, g.op("MatMul", ["X", g.c(w(D, D))])), g.c(pos)]), out)
    g = G("p13_const_bmm_zero")                            # обход: p + ноль со входа — множитель уже не постоянный
    g.inputs.append(helper.make_tensor_value_info("Z", TensorProto.FLOAT, [1]))
    g.save(g.op("MatMul", [heads(g, g.op("MatMul", ["X", g.c(w(D, D))])), g.op("Add", [g.c(pos), "Z"])]), out)


def qdq_probes(out):
    """w8a16 по правилам MediaTek: веса int8 симметрично по каналам, активации int16 симметрично — без нулевых точек
    (у дополнения нули читаются только int8/uint8/int32; QuantizeLinear-21 с output_dtype=INT16), и запасной w8a8."""
    def qd(g, x, s, dt):                                    # Q → DQ активации, симметрично, без нулевой точки
        q = g.op("QuantizeLinear", [x, g.c(np.float32(s))], output_dtype=dt)
        return g.op("DequantizeLinear", [q, g.c(np.float32(s))])

    def wq(g, wf):                                          # веса [K, N] → int8 по столбцам (ось 1), нули int8
        sc = (np.abs(wf).max(axis=0) / 127).astype(np.float32)
        q = np.clip(np.rint(wf / sc), -127, 127).astype(np.int8)
        return g.op("DequantizeLinear", [g.c(q), g.c(sc), g.c(np.zeros(wf.shape[1], np.int8))], axis=1)

    for name, dt, amax in (("p14_w8a16_fc", TensorProto.INT16, 32767), ("p15_w8a8_fc", TensorProto.INT8, 127)):
        g = G(name)
        x = qd(g, "X", 5.0 / amax, dt)
        y = g.op("MatMul", [x, wq(g, w(D, FF))])
        g.save(qd(g, y, 3.0 / amax, dt), out)
    g = G("p17_w8a8u_fc")                                   # стандартный QDQ: активации uint8 с нулём 128, веса int8
    def qdu(g, x, s):
        q = g.op("QuantizeLinear", [x, g.c(np.float32(s)), g.c(np.uint8(128))])
        return g.op("DequantizeLinear", [q, g.c(np.float32(s)), g.c(np.uint8(128))])
    y = g.op("MatMul", [qdu(g, "X", 5.0 / 127), wq(g, w(D, FF))])
    g.save(qdu(g, y, 3.0 / 127), out)
    def wq_rows(g, wf, axis=0):                          # веса [N, ...] → int8 по оси 0 (Gemm transB=1, Conv)
        red = tuple(i for i in range(wf.ndim) if i != axis)
        sc = (np.abs(wf).max(axis=red) / 127).astype(np.float32)
        shp = [1] * wf.ndim; shp[axis] = -1
        q = np.clip(np.rint(wf / sc.reshape(shp)), -127, 127).astype(np.int8)
        return g.op("DequantizeLinear", [g.c(q), g.c(sc), g.c(np.zeros(wf.shape[axis], np.int8))], axis=axis), sc

    def bq(g, b, s_in, s_w):                               # смещение int32 со шкалой s_in·s_w (как у квантователей ORT)
        sc = (s_in * s_w).astype(np.float32)
        return g.op("DequantizeLinear", [g.c(np.rint(b / sc).astype(np.int32)), g.c(sc), g.c(np.zeros(len(b), np.int32))], axis=0)

    g = G("p18_w8a16_gemm")                                # Gemm transB=1 со смещением — точечные свёртки, выход pre_encode
    s_in = np.float32(5.0 / 32767)
    x = qd(g, g.op("Reshape", ["X", g.c([T, D], np.int64)]), s_in, TensorProto.INT16)
    wdq, sw = wq_rows(g, w(2 * D, D))
    y = g.op("Gemm", [x, wdq, bq(g, w(2 * D), s_in, sw)], transB=1)
    g.save(qd(g, y, 3.0 / 32767, TensorProto.INT16), out)

    g = G("p19_w8a16_dw")                                  # depthwise 1×9 в A16W8 (как в модуле свёртки по времени)
    x = g.op("Transpose", [g.op("Reshape", [g.op("MatMul", ["X", g.c(w(D, D))]), g.c([1, 1, T, D], np.int64)])], perm=[0, 3, 1, 2])
    x = qd(g, x, s_in, TensorProto.INT16)
    wdq, sw = wq_rows(g, w(D, 1, 1, 9, s=0.2))
    y = g.op("Conv", [x, wdq, bq(g, w(D), s_in, sw)], kernel_shape=[1, 9], pads=[0, 4, 0, 4], group=D)
    y = qd(g, y, 3.0 / 32767, TensorProto.INT16)
    g.save(g.op("Reshape", [g.op("Transpose", [y], perm=[0, 2, 3, 1]), g.c([1, T, D], np.int64)]), out)

    g = G("p16_w8a16_mixed")                               # квантованный FC → float LayerNorm → квантованный FC
    y = g.op("MatMul", [qd(g, "X", 5.0 / 32767, TensorProto.INT16), wq(g, w(D, D))])
    y = g.op("LayerNormalization", [g.op("DequantizeLinear", [g.op("QuantizeLinear", [y, g.c(np.float32(3.0 / 32767))], output_dtype=TensorProto.INT16), g.c(np.float32(3.0 / 32767))]),
                                     g.c(1 + w(D)), g.c(w(D))], axis=-1, epsilon=1e-5)
    y = g.op("MatMul", [qd(g, y, 6.0 / 32767, TensorProto.INT16), wq(g, w(D, D))])
    g.save(qd(g, y, 3.0 / 32767, TensorProto.INT16), out, opset=21)


def real(out):
    """Настоящие куски кодировщика с нашими весами: pre_encode и слой 0 (вход слоя и маски — входы куска)."""
    from onnx.external_data_helper import load_external_data_for_model
    m = onnx.load(str(SRC), load_external_data=False)       # граф; веса куска подтягиваются потом
    by_name = {n.name: n for n in m.graph.node}
    prod = {o: n.name for n in m.graph.node for o in n.output}
    init = {i.name: i for i in m.graph.initializer}
    ln0 = by_name["/layers.0/norm_feed_forward1/LayerNormalization"].input[0]   # вход слоя 0 = выход pre_encode
    ln1 = by_name["/layers.1/norm_feed_forward1/LayerNormalization"].input[0]   # выход слоя 0
    print("слой 0:", ln0, "→", ln1, "(позиции linear_pos при постоянном окне свёрнуты в постоянную)")

    def cut(name, ins, outs):
        need, stack = set(), list(outs)
        while stack:
            t = stack.pop()
            if not t or t in ins or t in init or t not in prod or prod[t] in need:
                continue
            need.add(prod[t])
            stack.extend(by_name[prod[t]].input)
        nodes = [n for n in m.graph.node if n.name in need]
        used = {i for n in nodes for i in n.input}
        missing = [i for i in used if i not in init and i not in ins and i not in prod and i]
        assert not missing, missing
        g = helper.make_graph(nodes, name, [helper.make_tensor_value_info(k, TensorProto.FLOAT, v) for k, v in ins.items()],
                              [helper.make_tensor_value_info(k, TensorProto.FLOAT, v) for k, v in outs.items()],
                              [init[i] for i in used if i in init])
        x = helper.make_model(g, opset_imports=list(m.opset_import), ir_version=m.ir_version)
        load_external_data_for_model(x, str(SRC.parent))    # веса куска — внутрь файла
        onnx.checker.check_model(x)
        onnx.save(x, str(out / f"{name}.onnx"))
        print(name, "узлов", len(nodes), f"{(out / f'{name}.onnx').stat().st_size / 1e6:.0f} МБ")

    cut("r01_pre_encode", {"audio_signal": [1, 128, 832]}, {ln0: [1, T, D]})
    cut("r02_layer0", {ln0: [1, T, D], "mtk_matt": [1, 1, T, T], "mtk_bias": [1, 1, T, T], "mtk_vcol": [1, T, 1]}, {ln1: [1, T, D]})
    # подблоки слоя 0 (остаток: Add — после FF1, Add_1 — после внимания, Add_2 — после conv, Add_3 — после FF2)
    a = [f"/layers.0/Add{k}_output_0" for k in ("", "_1", "_2")]
    cut("r03_ff1", {ln0: [1, T, D]}, {a[0]: [1, T, D]})
    cut("r04_att", {a[0]: [1, T, D], "mtk_matt": [1, 1, T, T], "mtk_bias": [1, 1, T, T]}, {a[1]: [1, T, D]})
    cut("r05_conv", {a[1]: [1, T, D], "mtk_vcol": [1, T, 1]}, {a[2]: [1, T, D]})
    cut("r06_ff2_out", {a[2]: [1, T, D]}, {ln1: [1, T, D]})


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=str(OUT))
    ap.add_argument("--no-real", action="store_true", help="без настоящих кусков кодировщика (нужна модель fp32)")
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    synthetic(out)
    layouts(out)
    const_bmm(out)
    qdq_probes(out)
    if "--no-real" not in sys.argv:
        real(out)
    for f in sorted(out.glob("*.onnx")):
        print(f.name, f"{f.stat().st_size / 1e6:.1f} МБ")
    return 0


if __name__ == "__main__":
    sys.exit(main())
