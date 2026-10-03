#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Два крошечных ONNX-графа для перебора вариантов в переводе (Engine.beamKv): отбор и перестановка идут
в ONNX Runtime, векторно, а не циклом на Java. Замер 03.10 (results/2026-10-03-mt-beam.md): отбор на Java —
log-softmax по 61 тыс. кусков на каждый черновик на каждом шаге — 28 мс на фразу на одном ядре ПК, перестановка
прошлого декодера — 5,5 мс; вся добавка ширины 4 к жадному — около 45 мс.

select: logits [B,1,V] — выход decoder_with_past; pad [B,1] int64 и neg [B,1] float — какой кусок запретить и чем
        (<pad>, −inf: это начало декодера, не текст); k [1] int64 →
        ix [B,K] int64 — K лучших кусков строки по самим логитам (при равенстве — меньший номер, как argmax),
        lp [B,K] float — их логвероятности (log-softmax по строке без запрещённого куска).
gather: p0..p11 [b,H,T,D] float, idx [n] int64 → g0..g11 = p_i[idx] по оси 0: прошлое декодера под новых
        родителей и K/V кодировщика на n одинаковых строк (idx из нулей).

  .venv/bin/python tools/mt_beam_ops.py           # сверить с numpy и напечатать base64 для BeamOps.java
"""
import base64
import sys

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto as T
from onnx import helper as h

OPSET, IR = 17, 8   # ONNX Runtime 1.29 приложения читает и то и другое
N_PAST = 12         # 6 слоёв × (key, value)


def select_model():
    nodes = [
        h.make_node("Constant", [], ["ax1"], value=h.make_tensor("ax1v", T.INT64, [1], [1])),
        h.make_node("Squeeze", ["logits", "ax1"], ["x"]),
        h.make_node("ScatterElements", ["x", "pad", "neg"], ["xm"], axis=1),
        h.make_node("TopK", ["xm", "k"], ["vals", "ix"], axis=1, largest=1, sorted=1),
        h.make_node("LogSoftmax", ["xm"], ["lsm"], axis=1),
        h.make_node("GatherElements", ["lsm", "ix"], ["lp"], axis=1),
    ]
    g = h.make_graph(nodes, "falar_beam_select",
                     [h.make_tensor_value_info("logits", T.FLOAT, ["B", 1, "V"]),
                      h.make_tensor_value_info("pad", T.INT64, ["B", 1]),
                      h.make_tensor_value_info("neg", T.FLOAT, ["B", 1]),
                      h.make_tensor_value_info("k", T.INT64, [1])],
                     [h.make_tensor_value_info("ix", T.INT64, ["B", "K"]),
                      h.make_tensor_value_info("lp", T.FLOAT, ["B", "K"])])
    return finish(g)


def gather_model():
    nodes = [h.make_node("Gather", [f"p{i}", "idx"], [f"g{i}"], axis=0) for i in range(N_PAST)]
    g = h.make_graph(nodes, "falar_beam_gather",
                     [h.make_tensor_value_info(f"p{i}", T.FLOAT, ["b", "h", f"t{i}", "d"]) for i in range(N_PAST)]
                     + [h.make_tensor_value_info("idx", T.INT64, ["n"])],
                     [h.make_tensor_value_info(f"g{i}", T.FLOAT, ["n", "h", f"t{i}", "d"]) for i in range(N_PAST)])
    return finish(g)


def finish(g):
    m = h.make_model(g, opset_imports=[h.make_opsetid("", OPSET)], producer_name="falar", producer_version="1")
    m.ir_version = IR
    onnx.checker.check_model(m, full_check=True)
    return m.SerializeToString()


def check(sel, gat):
    so = ort.SessionOptions(); so.intra_op_num_threads = 1
    s = ort.InferenceSession(sel, so, providers=["CPUExecutionProvider"])
    rng = np.random.default_rng(7)
    B, V, K, pad = 4, 61345, 8, 61344
    x = rng.normal(0, 4, (B, 1, V)).astype(np.float32)
    x[1, 0, 100] = x[1, 0, 200] = x[1, 0].max() + 1           # равенство: меньший номер первым
    x[2, 0, pad] = x[2, 0].max() + 5                          # запрещённый кусок не выбирается
    ix, lp = s.run(None, {"logits": x, "pad": np.full((B, 1), pad, np.int64),
                          "neg": np.full((B, 1), -np.inf, np.float32), "k": np.array([K], np.int64)})
    xm = x[:, 0, :].astype(np.float64); xm[:, pad] = -np.inf
    ref_ix = np.argsort(-xm, axis=1, kind="stable")[:, :K]
    mx = xm.max(1, keepdims=True); lsm = xm - mx - np.log(np.exp(xm - mx).sum(1, keepdims=True))
    assert (ix == ref_ix).all(), "select: не те куски"
    assert ix[1, 0] == 100 and ix[1, 1] == 200, "select: при равенстве не меньший номер"
    assert pad not in ix[2], "select: выбран запрещённый кусок"
    err = np.abs(lp - np.take_along_axis(lsm, ix, 1)).max()
    assert err < 1e-4, f"select: логвероятности расходятся на {err}"
    g = ort.InferenceSession(gat, so, providers=["CPUExecutionProvider"])
    ps = {f"p{i}": rng.normal(size=(3, 16, 5 + i % 2, 64)).astype(np.float32) for i in range(N_PAST)}
    idx = np.array([2, 0, 0, 1], np.int64)
    out = g.run(None, {**ps, "idx": idx})
    assert all((o == ps[f"p{i}"][idx]).all() for i, o in enumerate(out)), "gather: не те строки"
    print(f"сверка с numpy: select — куски точно, логвероятности до {err:.1e}; gather — строки точно", file=sys.stderr)


if __name__ == "__main__":
    sel, gat = select_model(), gather_model()
    check(sel, gat)
    print(f"// select: {len(sel)} байт, gather: {len(gat)} байт")
    print(f'static final String SELECT = "{base64.b64encode(sel).decode()}";')
    print(f'static final String GATHER = "{base64.b64encode(gat).decode()}";')
