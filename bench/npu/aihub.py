#!/usr/bin/env python3
"""Наши модели на настоящих Snapdragon в облаке Qualcomm AI Hub: процессор против NPU (Hexagon).

Только публичные модели (OPUS-MT и parakeet, обе CC-BY-4.0) и случайные входы профилировщика — ни
одного слова владельца. Токен — у владельца: он сам вводит `qai-hub configure --api_token …`, клиент
лежит в ~/.cache/falar-stand/venv-aihub. Номера заданий — в bench/npu/runs/ (локально, не в git).

  aihub.py devices [--grep TEXT]           # устройства AI Hub
  aihub.py submit --device NAME [--only mt_enc,…]
  aihub.py report [RUN.json]               # таблица: мс на вызов, память, сколько слоёв на NPU

Что сравнивается (формы входов фиксированы — у QNN нет динамических форм):
  mt_enc / mt_dec — OPUS-MT pt→ru fp32 (models/opus-onnx): процессор (ONNX Runtime) и NPU (контекст QNN, fp16);
  app_enc / app_dec — то, что стоит в приложении (int8, динамическое квантование): только процессор —
  это нынешняя скорость Falar на этом телефоне;
  asr_enc — кодировщик parakeet int8 из приложения, 8 с звука: процессор; NPU — попытка (int8-операции
  динамического квантования QNN не поддерживает, отказ компиляции — тоже ответ).
Длины: исходник 32 токена (p90 Tatoeba — 9 слов), прошлое декодера 16 шагов, звук 800 кадров = 8 с.
"""
import argparse
import json
import sys
import time
from pathlib import Path

import qai_hub as hub

ROOT = Path(__file__).resolve().parent.parent.parent
MODELS = Path("/home/not_me/antigravity-workspace/agentstuff/agenttranslator/models")
RUNS = Path(__file__).resolve().parent / "runs"
S, T, F = 32, 16, 800


def dec_specs():
    sp = {"encoder_attention_mask": ((1, S), "int64"), "input_ids": ((1, 1), "int64")}
    for i in range(6):
        sp[f"past_key_values.{i}.decoder.key"] = ((1, 16, T, 64), "float32")
        sp[f"past_key_values.{i}.decoder.value"] = ((1, 16, T, 64), "float32")
        sp[f"past_key_values.{i}.encoder.key"] = ((1, 16, S, 64), "float32")
        sp[f"past_key_values.{i}.encoder.value"] = ((1, 16, S, 64), "float32")
    return sp


ENC = {"input_ids": ((1, S), "int64"), "attention_mask": ((1, S), "int64")}
JOBS = {  # имя: (файл, формы, вычислители)
    "mt_enc": ("opus-onnx/pt2ru/encoder_model.onnx", ENC, ["cpu", "npu"]),
    "mt_dec": ("opus-onnx/pt2ru/decoder_with_past_model.onnx", dec_specs(), ["cpu", "npu"]),
    "app_enc": ("mt/pt2ru/encoder_kv_model.onnx", ENC, ["cpu"]),
    "app_dec": ("mt/pt2ru/decoder_with_past_model.onnx", dec_specs(), ["cpu"]),
    "asr_enc": ("asr_multi/encoder.int8.onnx", {"audio_signal": ((1, 128, F), "float32"), "length": ((1,), "int64")},
                ["cpu", "npu"]),
}
TARGET = {"cpu": "--target_runtime onnx", "npu": "--target_runtime qnn_context_binary"}
PROFILE = {"cpu": "--compute_unit cpu", "npu": "--compute_unit npu"}


def devices(a):
    for d in hub.get_devices():
        line = f"{d.name} | os {d.os} | {', '.join(x for x in d.attributes if x.startswith(('chipset', 'framework', 'format')))}"
        if not a.grep or a.grep.lower() in line.lower():
            print(line)


def submit(a):
    dev = hub.Device(a.device)
    only = set(a.only.split(",")) if a.only else set(JOBS)
    RUNS.mkdir(exist_ok=True)
    run = {"device": a.device, "started": time.strftime("%Y-%m-%d %H:%M:%S"), "jobs": []}
    out = RUNS / f"{time.strftime('%Y%m%d-%H%M%S')}-{a.device.replace(' ', '_').replace('(', '').replace(')', '')}.json"
    uploaded = {}
    for name, (path, specs, units) in JOBS.items():
        if name not in only:
            continue
        if path not in uploaded:
            print(f"загружаю {path} …", flush=True)
            uploaded[path] = hub.upload_model(str(MODELS / path))
        for u in units:
            cj = hub.submit_compile_job(model=uploaded[path], device=dev, name=f"falar {name} {u}",
                                        input_specs=specs, options=TARGET[u])
            run["jobs"].append({"name": name, "unit": u, "compile": cj.job_id})
            print(f"{name} {u}: компиляция {cj.job_id}", flush=True)
            out.write_text(json.dumps(run, ensure_ascii=False, indent=1))
    for j in run["jobs"]:
        cj = hub.get_job(j["compile"])
        st = cj.wait()
        if not st.success:
            j["error"] = f"компиляция: {st.message}"
            print(f"{j['name']} {j['unit']}: {j['error']}", flush=True)
            continue
        pj = hub.submit_inference_job(model=cj.get_target_model(), device=dev, name=f"falar {j['name']} {j['unit']}",
                                      options=PROFILE[j["unit"]], profile=True)
        j["profile"] = pj.job_id
        print(f"{j['name']} {j['unit']}: профиль {pj.job_id}", flush=True)
        out.write_text(json.dumps(run, ensure_ascii=False, indent=1))
    print(f"задания — в {out}")


def summary(prof):
    es = prof.get("execution_summary", {})
    ms = es.get("estimated_inference_time", 0) / 1000
    peak = es.get("estimated_inference_peak_memory", 0) / 2**20
    layers = prof.get("execution_detail", [])
    units = {}
    for l in layers:
        units[l.get("compute_unit", "?")] = units.get(l.get("compute_unit", "?"), 0) + 1
    return ms, peak, units


def report(a):
    files = [Path(a.run)] if a.run else sorted(RUNS.glob("*.json"))[-1:]
    for f in files:
        run = json.loads(f.read_text())
        print(f"== {run['device']} ({f.name})")
        for j in run["jobs"]:
            if "error" in j or "profile" not in j:
                print(f"  {j['name']:8} {j['unit']}: {j.get('error', 'профиль не запущен')}")
                continue
            pj = hub.get_job(j["profile"])
            st = pj.wait()
            if not st.success:
                print(f"  {j['name']:8} {j['unit']}: профиль не прошёл — {st.message}")
                continue
            ms, peak, units = summary(pj.download_profile())
            print(f"  {j['name']:8} {j['unit']}: {ms:8.2f} мс · пик {peak:6.0f} МБ · слои {units}")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    d = sub.add_parser("devices")
    d.add_argument("--grep", default="")
    s = sub.add_parser("submit")
    s.add_argument("--device", required=True)
    s.add_argument("--only", default="")
    r = sub.add_parser("report")
    r.add_argument("run", nargs="?")
    a = ap.parse_args()
    {"devices": devices, "submit": submit, "report": report}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
