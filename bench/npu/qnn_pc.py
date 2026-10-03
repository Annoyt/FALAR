#!/usr/bin/env python3
"""Собрать модель под NPU Qualcomm (HTP) на ПК: какие узлы уходят на NPU и собирается ли контекст QNN.

На x86 сборка библиотекой QNN возможна, запуск — нет (HTP-устройства нет; документация onnxruntime-qnn). Значит,
здесь ответ «влезает ли граф в NPU» и готовый контекст QNN (EPContext) для телефона; скорость и точность —
только на настоящем Snapdragon. Окружение: ~/.cache/falar-stand/venv-qnn (onnxruntime 1.30 + onnxruntime-qnn 2.6.0).

  qnn_pc.py MODEL.onnx --dims name=N,… [--soc SM8750] [--ctx OUT_ctx.onnx]
"""
import argparse
import time

import numpy as np
import onnxruntime as ort
import onnxruntime_qnn as qnn


def inputs_for(sess, dims, rng):
    feed = {}
    for i in sess.get_inputs():
        shape = [dims.get(d, d) if isinstance(d, str) else d for d in i.shape]
        shape = [1 if isinstance(d, str) else d for d in shape]
        if "int64" in i.type:
            hi = 1 if "mask" in i.name else (shape[-1] if i.name == "length" else 1000)
            feed[i.name] = (np.ones(shape, np.int64) if "mask" in i.name else
                            np.full(shape, hi, np.int64) if i.name == "length" else
                            rng.integers(5, hi, size=shape, dtype=np.int64))
        else:
            feed[i.name] = rng.standard_normal(shape).astype(np.float32)
    return feed


def session(path, dims, qnn_on, soc, fallback, ctx=None):
    so = ort.SessionOptions()
    for k, v in dims.items():
        so.add_free_dimension_override_by_name(k, v)
    if qnn_on:
        devs = [d for d in ort.get_ep_devices() if d.ep_name == qnn.get_ep_name()]
        print("устройства QNN:", [(d.device.type, d.device.vendor, dict(d.device.metadata)) for d in devs])
        opts = {"backend_path": qnn.get_qnn_htp_path(), "enable_htp_fp16_precision": "1", "soc_model": soc, "htp_arch": "79"}
        so.add_provider_for_devices(devs, opts)
        if not fallback:
            so.add_session_config_entry("session.disable_cpu_ep_fallback", "1")
        if ctx:
            so.add_session_config_entry("ep.context_enable", "1")
            so.add_session_config_entry("ep.context_file_path", ctx)
            so.add_session_config_entry("ep.context_embed_mode", "0")
    so.log_severity_level = 1 if qnn_on else 3
    return ort.InferenceSession(path, sess_options=so, providers=None if qnn_on else ["CPUExecutionProvider"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model")
    ap.add_argument("--dims", default="")
    ap.add_argument("--soc", default="SM8750")
    ap.add_argument("--fallback", action="store_true", help="разрешить узлам уходить на CPU (по умолчанию — всё на NPU или отказ)")
    ap.add_argument("--ctx", default="", help="записать контекст QNN (EPContext-модель) сюда")
    a = ap.parse_args()
    dims = {k: int(v) for k, v in (p.split("=") for p in a.dims.split(",") if p)}
    ort.register_execution_provider_library(qnn.get_ep_name(), qnn.get_library_path())
    t = time.time()
    session(a.model, dims, True, a.soc, a.fallback, a.ctx or None)
    print(f"сборка под HTP ({a.soc}): {time.time() - t:.1f} с — граф принят" + (" целиком" if not a.fallback else ""))


if __name__ == "__main__":
    main()
