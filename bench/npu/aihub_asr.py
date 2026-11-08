#!/usr/bin/env python3
"""parakeet на облачных Snapdragon в Qualcomm AI Hub: скорость NPU (fp16) и процессора (наш int8), WER по выходам NPU.

Отвечает на вопрос, на который не хватило лаборатории Samsung: что на средних Snapdragon (HTP v73, у части нет FP16
в HMX — сторонние отчёты о «мусоре»). Только публичное: модель — экспорт istupakov/parakeet-tdt-0.6b-v3-onnx
(fp32 → fp16, bench/npu/to_fp16.py), наш int8 — тот же файл, что в приложении (CC-BY-4.0), признаки — 162 фразы
публичного корпуса bench/air/corpus (Tatoeba). Записи комнаты владельца сюда не идут. Токен владелец вводит сам:
qai-hub configure --api_token … (клиент в .stand/venv-aihub).

  aihub_asr.py devices [--grep TEXT]
  aihub_asr.py submit --devices "ИМЯ;ИМЯ" [--no-cpu] [--no-infer]
  aihub_asr.py collect RUN.json        # выходы → .stand/npu/aihub/<устройство>/ → bench/npu/asr_wer.py → WER

Формы фиксированы (у QNN нет динамических): 128 × 832 кадра (до 8,3 с; самая длинная фраза корпуса — 808), длина — int32
(--truncate_64bit_io). Номера заданий — в .stand/npu/aihub/runs/.
"""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import qai_hub as hub

P = Path("/home/not_me/antigravity-workspace/agentstuff/agenttranslator")
NPU_MODEL = P / ".stand/npu/parakeet/encoder_fp16.onnx"
CPU_MODEL = P / "models/asr_multi/encoder.int8.onnx"
FEAT = P / ".stand/npu/asrfeat"
OUT = P / ".stand/npu/aihub"
F = 832
SPECS = {"audio_signal": ((1, 128, F), "float32"), "length": ((1,), "int64")}


def devices(a):
    for d in hub.get_devices():
        chip = next((x.split(":", 1)[1] for x in d.attributes if x.startswith("chipset:")), "?")
        line = f"{d.name} | {chip} | os {d.os} | {', '.join(x for x in d.attributes if x.startswith(('hexagon', 'htp')))}"
        if not a.grep or a.grep.lower() in line.lower():
            print(line)


def dataset():
    names, x, ln = [], [], []
    for line in (FEAT / "list.tsv").read_text().splitlines():
        if not line.strip():
            continue
        name, T = line.split("\t")
        names.append(name)
        x.append(np.fromfile(FEAT / f"{name}.f32", np.float32).reshape(1, 128, F))
        ln.append(np.array([int(T)], np.int32))
    return names, {"audio_signal": x, "length": ln}


def submit(a):
    devs = [hub.Device(n.strip()) for n in a.devices.split(";") if n.strip()]
    (OUT / "runs").mkdir(parents=True, exist_ok=True)
    run = {"started": time.strftime("%Y-%m-%d %H:%M:%S"), "jobs": []}
    path = OUT / "runs" / f"{time.strftime('%Y%m%d-%H%M%S')}.json"
    print("загружаю модель для NPU …", flush=True)
    m_npu = hub.upload_model(str(NPU_MODEL))
    m_cpu = None if a.no_cpu else (print("загружаю int8 для процессора …", flush=True) or hub.upload_model(str(CPU_MODEL)))
    names, data = dataset()
    ds = None if a.no_infer else hub.upload_dataset(data, name="falar-corpus-162")
    run["names"] = names
    for d in devs:
        j = {"device": d.name}
        cj = hub.submit_compile_job(model=m_npu, device=d, name="falar parakeet npu", input_specs=SPECS,
                                    options="--target_runtime qnn_context_binary --truncate_64bit_io")
        j["npu_compile"] = cj.job_id
        if m_cpu is not None:
            cc = hub.submit_compile_job(model=m_cpu, device=d, name="falar parakeet cpu int8", input_specs=SPECS,
                                        options="--target_runtime onnx")
            j["cpu_compile"] = cc.job_id
        run["jobs"].append(j)
        print(f"{d.name}: компиляция NPU {cj.job_id}" + (f", CPU {j['cpu_compile']}" if "cpu_compile" in j else ""), flush=True)
        path.write_text(json.dumps(run, ensure_ascii=False, indent=1))
    for j in run["jobs"]:
        d = hub.Device(j["device"])
        cj = hub.get_job(j["npu_compile"])
        st = cj.wait()
        if not st.success:
            j["npu_error"] = st.message
            print(f"{j['device']}: NPU не скомпилировалось — {st.message}", flush=True)
        else:
            tm = cj.get_target_model()
            j["npu_profile"] = hub.submit_inference_job(model=tm, device=d, name="falar parakeet npu profile",
                                                        profile=True).job_id
            if ds is not None:
                j["npu_infer"] = hub.submit_inference_job(model=tm, device=d, inputs=ds, name="falar parakeet npu wer",
                                                          profile=False).job_id
        if "cpu_compile" in j:
            cc = hub.get_job(j["cpu_compile"])
            st = cc.wait()
            if st.success:
                j["cpu_profile"] = hub.submit_inference_job(model=cc.get_target_model(), device=d, profile=True,
                                                            name="falar parakeet cpu profile",
                                                            options="--compute_unit cpu").job_id
            else:
                j["cpu_error"] = st.message
        print(f"{j['device']}: " + ", ".join(f"{k} {v}" for k, v in j.items() if k != "device"), flush=True)
        path.write_text(json.dumps(run, ensure_ascii=False, indent=1))
    print(f"задания — {path}")


def ms_of(job):
    st = job.wait()
    if not st.success:
        return None, st.message
    prof = job.download_profile()
    es = prof.get("execution_summary", {})
    units = {}
    for l in prof.get("execution_detail", []):
        units[l.get("compute_unit", "?")] = units.get(l.get("compute_unit", "?"), 0) + 1
    return es.get("estimated_inference_time", 0) / 1000, units


def collect(a):
    run = json.loads(Path(a.run).read_text())
    names = run["names"]
    rows = []
    for j in run["jobs"]:
        dev = j["device"]
        npu = ms_of(hub.get_job(j["npu_profile"])) if "npu_profile" in j else (None, j.get("npu_error"))
        cpu = ms_of(hub.get_job(j["cpu_profile"])) if "cpu_profile" in j else (None, j.get("cpu_error"))
        wer = ""
        if "npu_infer" in j:
            ij = hub.get_job(j["npu_infer"])
            st = ij.wait()
            if st.success:
                outs = ij.download_output_data()
                key = next(k for k in outs if outs[k][0].ndim == 3)
                lkey = next((k for k in outs if k != key), None)
                d = OUT / dev.replace(" ", "_").replace("(", "").replace(")", "")
                d.mkdir(parents=True, exist_ok=True)
                for i, n in enumerate(names):
                    o = np.asarray(outs[key][i], np.float32).reshape(1024, -1)
                    L = int(np.asarray(outs[lkey][i]).ravel()[0]) if lkey else o.shape[1]
                    o.tofile(d / f"{n}.f32")
                    (d / f"{n}.len").write_text(str(L))
                r = subprocess.run([sys.executable, "-I", str(Path(__file__).with_name("asr_wer.py")), str(d)],
                                   capture_output=True, text=True)
                wer = r.stdout.strip().split(" ", 1)[-1]
            else:
                wer = f"вывод не прошёл: {st.message}"
        rows.append((dev, npu, cpu, wer))
    for dev, npu, cpu, wer in rows:
        fmt = lambda x: f"{x[0]:.1f} мс {x[1]}" if x[0] is not None else f"— ({x[1]})"
        print(f"{dev}\n  NPU fp16: {fmt(npu)}\n  CPU int8: {fmt(cpu)}\n  WER NPU: {wer}")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    d = sub.add_parser("devices")
    d.add_argument("--grep", default="")
    s = sub.add_parser("submit")
    s.add_argument("--devices", required=True)
    s.add_argument("--no-cpu", action="store_true")
    s.add_argument("--no-infer", action="store_true")
    c = sub.add_parser("collect")
    c.add_argument("run")
    a = ap.parse_args()
    {"devices": devices, "submit": submit, "collect": collect}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
