#!/usr/bin/env python3
"""Найти операцию, которую NPU одного поколения считает неверно: QDQ-модель с «отводами» (промежуточные тензоры —
дополнительными выходами) на двух телефонах AI Hub, сравнение отвод за отводом.

Повод: на Galaxy S22 (8 Gen 1, HTP v69) выход parakeet w8a16 испорчен (cos 0,62 к fp32), та же модель верна на v68,
v73 и v79. Отводы: выходы свёрток предкодировщика, все блоки первого слоя, выход каждого из 24 слоёв.

  aihub_taps.py make IN_DIR OUT_DIR                 # QDQ (после qdq_fix.py --float-length) → то же + отводы
  aihub_taps.py submit OUT_DIR --model-id ID --devices "ЭТАЛОН;ПРОВЕРЯЕМЫЙ" [--clips 6]
  aihub_taps.py compare RUN.json                    # cos и относительная ошибка по отводам: проверяемый против эталона

Сборка как у основной модели: precompiled_qnn_onnx, --quantize_io, длина float32 (вход uint16 2·L+2). Выходы-отводы
тогда uint16 в сетке своего DequantizeLinear; сетки — в КАТАЛОГ.taps.json рядом с моделью. Только публичные признаки корпуса.
"""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

P = Path("/home/not_me/antigravity-workspace/agentstuff/agenttranslator")
FEAT = P / ".stand/npu/asrfeat"
OUT = P / ".stand/npu/aihub"
F = 832


def taps_list(g):
    """Имена тензоров-отводов (по выходу DequantizeLinear), в порядке графа."""
    want = ["/pre_encode/conv/conv.1/Relu_output_0", "/pre_encode/conv/conv.1_1/Relu_output_0",
            "/pre_encode/conv/conv.1_2/Relu_output_0", "/pre_encode/out/Add_output_0"]
    l0 = ["norm_feed_forward1/LayerNormalization_output_0", "feed_forward1/linear1/MatMul_output_0",
          "feed_forward1/activation/Mul_output_0", "Mul_output_0", "Add_output_0",
          "norm_self_att/LayerNormalization_output_0", "self_attn/MatMul_output_0", "self_attn/MatMul_1_output_0",
          "self_attn/Add_2_output_0", "self_attn/Softmax_output_0", "self_attn/MatMul_2_output_0",
          "self_attn/linear_out/MatMul_output_0", "Add_1_output_0", "norm_conv/LayerNormalization_output_0",
          "conv/pointwise_conv1/Conv_output_0", "conv/Mul_output_0", "conv/depthwise_conv/Conv_output_0",
          "conv/activation/Mul_output_0", "conv/pointwise_conv2/Conv_output_0", "Add_2_output_0",
          "norm_feed_forward2/LayerNormalization_output_0", "Add_3_output_0", "norm_out/LayerNormalization_output_0"]
    want += ["/layers.0/" + x for x in l0]
    want += [f"/layers.{i}/norm_out/LayerNormalization_output_0" for i in range(1, 24)]
    dq_of = {}
    for n in g.node:
        if n.op_type == "QuantizeLinear":
            dq_of.setdefault(n.input[0], n.output[0])
    q2dq = {n.input[0]: n for n in g.node if n.op_type == "DequantizeLinear"}
    out = []
    for t in want:
        q = dq_of.get(t)
        dq = q2dq.get(q) if q else None
        if dq is None:
            print(f"  нет Q/DQ у {t} — пропускаю", file=sys.stderr)
            continue
        out.append((t, dq))
    return out


def taps_file(model_dir):
    """Сетки отводов — рядом с каталогом модели: в самом каталоге AI Hub принимает только .onnx/.data/.encodings/.bin."""
    return model_dir.parent / f"{model_dir.name}.taps.json"


def make(a):
    import onnx
    from onnx import helper, TensorProto
    sys.path.insert(0, str(Path(__file__).parent))
    from qdq_fix import value
    src, dst = Path(a.src), Path(a.dst)
    m = onnx.load(str(src / "model.onnx"), load_external_data=False)
    g = m.graph
    init = {i.name: i for i in g.initializer}
    # формы отводов — из вывода форм (AI Hub требует у выходов графа тип с формой)
    vi = {v.name: v for v in onnx.shape_inference.infer_shapes(m, check_type=True, strict_mode=True).graph.value_info}
    taps = {}
    for t, dq in taps_list(g):
        o = dq.output[0]
        if o not in vi or not vi[o].type.tensor_type.HasField("shape"):
            sys.exit(f"{o}: форма неизвестна")
        out = onnx.ValueInfoProto()
        out.CopyFrom(vi[o])
        g.output.append(out)
        taps[o] = {"tensor": t, "scale": float(value(init, dq.input[1], src)), "zp": int(value(init, dq.input[2], src)),
                   "shape": [d.dim_value for d in out.type.tensor_type.shape.dim]}
    keep = [v for v in g.value_info if v.name not in taps]   # выход графа не должен дублироваться в value_info
    del g.value_info[:]
    g.value_info.extend(keep)
    onnx.shape_inference.infer_shapes(m, check_type=True, strict_mode=True)
    bad = [o.name for o in g.output if not o.type.tensor_type.HasField("shape")]
    if bad:
        sys.exit(f"у выходов нет формы: {bad[:3]}")
    dst.mkdir(parents=True, exist_ok=True)
    if not (dst / "model.data").exists():
        (dst / "model.data").hardlink_to(src / "model.data")
    onnx.save(m, str(dst / "model.onnx"))
    taps_file(dst).write_text(json.dumps(taps, ensure_ascii=False, indent=1))
    print(f"отводов {len(taps)} → {dst}")


def qio_params(model_dir):
    r = subprocess.run([str(P / ".stand/venv-qnn/bin/python"), "-I", str(Path(__file__).with_name("qdq_fix.py")),
                        "--io-params", str(model_dir)], capture_output=True, text=True, check=True)
    return json.loads(r.stdout)


def submit(a):
    import qai_hub as hub
    d = Path(a.dir)
    qp = qio_params(d)
    lines = [l.split("\t") for l in (FEAT / "list.tsv").read_text().splitlines() if l.strip()]
    pt = [x for x in lines if x[0].startswith("pt_")][1::27][: a.clips // 2]
    ru = [x for x in lines if x[0].startswith("ru_")][1::27][: a.clips - a.clips // 2]
    names = [n for n, _ in pt + ru]
    s, z = qp["in"]
    x = [np.clip(np.rint(np.fromfile(FEAT / f"{n}.f32", np.float32).reshape(1, 128, F) / s) + z, 0, 65535)
         .astype(np.uint16) for n in names]
    ln = [np.array([2 * int(T) + 2], np.uint16) for _, T in pt + ru]
    ds = hub.upload_dataset({"audio_signal": x, "length": ln}, name=f"falar-taps-{len(names)}")
    m = hub.get_model(a.model_id)
    specs = {"audio_signal": ((1, 128, F), "float32"), "length": ((1,), "float32")}
    run = {"started": time.strftime("%Y-%m-%d %H:%M:%S"), "dir": str(d), "names": names, "dataset": ds.dataset_id,
           "qio": qp, "jobs": []}
    path = OUT / "runs" / f"{time.strftime('%Y%m%d-%H%M%S')}-taps.json"
    for dev in [x.strip() for x in a.devices.split(";") if x.strip()]:
        cj = hub.submit_compile_job(model=m, device=hub.Device(dev), name="falar parakeet taps", input_specs=specs,
                                    options="--target_runtime precompiled_qnn_onnx --truncate_64bit_io --quantize_io")
        run["jobs"].append({"device": dev, "compile": cj.job_id})
        print(f"{dev}: сборка {cj.job_id}", flush=True)
    path.write_text(json.dumps(run, ensure_ascii=False, indent=1))
    for j in run["jobs"]:
        cj = hub.get_job(j["compile"])
        st = cj.wait()
        if not st.success:
            j["error"] = st.message
            print(f"{j['device']}: не собралось — {st.message}", flush=True)
            continue
        j["infer"] = hub.submit_inference_job(model=cj.get_target_model(), device=hub.Device(j["device"]), inputs=ds,
                                              name="falar parakeet taps").job_id
        print(f"{j['device']}: вывод {j['infer']}", flush=True)
        path.write_text(json.dumps(run, ensure_ascii=False, indent=1))
    print(f"задания — {path}")


def compare(a):
    import qai_hub as hub
    run = json.loads(Path(a.run).read_text())
    taps = json.loads(taps_file(Path(run["dir"])).read_text())
    outs = {}
    for j in run["jobs"]:
        if "infer" not in j:
            print(f"{j['device']}: нет вывода ({j.get('error', '?')[:200]})")
            continue
        st = hub.get_job(j["infer"]).wait()
        if not st.success:
            print(f"{j['device']}: вывод не прошёл — {st.message[:300]}")
            continue
        o = hub.get_job(j["infer"]).download_output_data()
        # AI Hub переименовывает выходы по порядку: output_0, output_1 — модели, дальше отводы в порядке taps.json;
        # значения приходят уже в float (если вдруг uint16 — возвращаем в float по сетке отвода)
        deq = {}
        for i, k in enumerate(taps):
            arr = [np.asarray(t) for t in o[f"output_{i + 2}"]]
            if arr[0].dtype == np.uint16:
                arr = [(t.astype(np.float32) - taps[k]["zp"]) * taps[k]["scale"] for t in arr]
            if list(arr[0].shape) != taps[k]["shape"]:
                sys.exit(f"output_{i + 2}: форма {list(arr[0].shape)}, а у {taps[k]['tensor']} {taps[k]['shape']}")
            deq[k] = [np.asarray(t, np.float32) for t in arr]
        outs[j["device"]] = deq
    if len(outs) < 2:
        return 1
    ref_dev, dev = list(outs)[:2]
    print(f"эталон: {ref_dev}; проверяемый: {dev}; фраз {len(run['names'])}")
    print(f"{'отвод':62s} {'cos':>7s} {'отн.ош':>7s}  диапазон эталона")
    for k, meta in taps.items():
        if k not in outs[ref_dev] or k not in outs[dev]:
            print(f"{meta['tensor']:62s}  нет в выходах")
            continue
        cs, rel = [], []
        for r, x in zip(outs[ref_dev][k], outs[dev][k]):
            r, x = r.ravel(), x.ravel()
            cs.append(float(r @ x / (np.linalg.norm(r) * np.linalg.norm(x) + 1e-12)))
            rel.append(float(np.linalg.norm(r - x) / (np.linalg.norm(r) + 1e-12)))
        lo = min(float(t.min()) for t in outs[ref_dev][k])
        hi = max(float(t.max()) for t in outs[ref_dev][k])
        flag = "  ←" if min(cs) < 0.99 else ""
        print(f"{meta['tensor']:62s} {min(cs):7.4f} {max(rel):7.4f}  [{lo:.3g}, {hi:.3g}]{flag}")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("make")
    m.add_argument("src")
    m.add_argument("dst")
    s = sub.add_parser("submit")
    s.add_argument("dir")
    s.add_argument("--model-id", required=True)
    s.add_argument("--devices", required=True)
    s.add_argument("--clips", type=int, default=6)
    c = sub.add_parser("compare")
    c.add_argument("run")
    a = ap.parse_args()
    return {"make": make, "submit": submit, "compare": compare}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
