#!/usr/bin/env python3
"""parakeet на облачных Snapdragon в Qualcomm AI Hub: скорость NPU (fp16) и процессора (наш int8), WER по выходам NPU.

Отвечает на вопрос, на который не хватило лаборатории Samsung: что на средних Snapdragon (HTP v73, у части нет FP16
в HMX — сторонние отчёты о «мусоре»). Только публичное: модель — экспорт istupakov/parakeet-tdt-0.6b-v3-onnx
(fp32 → fp16, bench/npu/to_fp16.py), наш int8 — тот же файл, что в приложении (CC-BY-4.0), признаки — 162 фразы
публичного корпуса bench/air/corpus (Tatoeba). Записи комнаты владельца сюда не идут. Токен владелец вводит сам:
qai-hub configure --api_token … (клиент в .stand/venv-aihub).

  aihub_asr.py devices [--grep TEXT]
  aihub_asr.py submit --devices "ИМЯ;ИМЯ" [--npu-model ФАЙЛ|ID] [--cpu-model ФАЙЛ|ID|none] [--dataset ID] [--label fp16]
  aihub_asr.py quantize [--calib 40]    # fp32 → QDQ w8a16 (веса int8, активации int16) для средних чипов без fp16 на NPU
  aihub_asr.py collect RUN.json        # выходы → .stand/npu/aihub/<метка>-<устройство>/ → bench/npu/asr_wer.py → WER

Цель сборки под NPU — precompiled_qnn_onnx: ONNX с готовым контекстом QNN, его же грузил бы ONNX Runtime в приложении
(вариант qnn_context_binary AI Hub больше не принимает).

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
FP32_DIR = P / ".stand/npu/parakeet-fp32"    # только encoder-model.onnx + .data (жёсткие ссылки) — так AI Hub берёт внешние веса
CPU_MODEL = P / "models/asr_multi/encoder.int8.onnx"
FEAT = P / ".stand/npu/asrfeat"
OUT = P / ".stand/npu/aihub"
F = 832
SPECS = {"audio_signal": ((1, 128, F), "float32"), "length": ((1,), "int64")}
# precompiled — контекст QNN собирает AI Hub; onnx — модель как есть, граф QNN собирает ONNX Runtime на телефоне
# (так будет в приложении): тогда --infer-opts="--compute_unit npu --onnx_execution_providers qnn"
RUNTIMES = {"precompiled": "precompiled_qnn_onnx", "onnx": "onnx"}


def devices(a):
    for d in hub.get_devices():
        chip = next((x.split(":", 1)[1] for x in d.attributes if x.startswith("chipset:")), "?")
        line = f"{d.name} | {chip} | os {d.os} | {', '.join(x for x in d.attributes if x.startswith(('hexagon', 'htp')))}"
        if not a.grep or a.grep.lower() in line.lower():
            print(line)


def model_ref(v):
    """Путь к файлу/каталогу — загрузить; иначе это ID уже загруженной модели."""
    return hub.upload_model(str(v)) if Path(str(v)).exists() else hub.get_model(str(v))


def dataset(only=None, length_dtype=np.int32):
    names, x, ln = [], [], []
    for line in (FEAT / "list.tsv").read_text().splitlines():
        if not line.strip():
            continue
        name, T = line.split("\t")
        if only is not None and name not in only:
            continue
        names.append(name)
        x.append(np.fromfile(FEAT / f"{name}.f32", np.float32).reshape(1, 128, F))
        ln.append(np.array([int(T)], length_dtype))
    return names, {"audio_signal": x, "length": ln}


def submit(a):
    devs = [hub.Device(n.strip()) for n in a.devices.split(";") if n.strip()]
    (OUT / "runs").mkdir(parents=True, exist_ok=True)
    run = {"started": time.strftime("%Y-%m-%d %H:%M:%S"), "label": a.label, "jobs": []}
    path = OUT / "runs" / f"{time.strftime('%Y%m%d-%H%M%S')}-{a.label}.json"
    m_npu = model_ref(a.npu_model)
    m_cpu = None if a.cpu_model == "none" else model_ref(a.cpu_model)
    specs = dict(SPECS, length=((1,), "float32")) if a.float_length else SPECS
    names, data = dataset(length_dtype=np.float32 if a.float_length else np.int32)
    ds = None if a.no_infer else (hub.get_dataset(a.dataset) if a.dataset else hub.upload_dataset(
        data, name="falar-corpus-162" + ("-flen" if a.float_length else "")))
    run.update(names=names, npu_model=m_npu.model_id, cpu_model=m_cpu.model_id if m_cpu else None,
               dataset=ds.dataset_id if ds else None)
    print(f"модели: NPU {m_npu.model_id}, CPU {run['cpu_model']}, данные {run['dataset']}", flush=True)
    for d in devs:
        j = {"device": d.name}
        cj = hub.submit_compile_job(model=m_npu, device=d, name=f"falar parakeet {a.label}", input_specs=specs,
                                    calibration_data=hub.get_dataset(a.calib_dataset) if a.calib_dataset else None,
                                    options=f"--target_runtime {RUNTIMES[a.runtime]} "
                                    + ("--truncate_64bit_io " if a.runtime == "precompiled" else "") + a.compile_opts)
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
                                                        profile=True, options=a.infer_opts).job_id
            if ds is not None:
                j["npu_infer"] = hub.submit_inference_job(model=tm, device=d, inputs=ds, name="falar parakeet npu wer",
                                                          profile=False, options=a.infer_opts).job_id
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
                d = OUT / (run.get("label", "fp16") + "-" + dev.replace(" ", "_").replace("(", "").replace(")", ""))
                d.mkdir(parents=True, exist_ok=True)
                for i, n in enumerate(names):
                    o = np.asarray(outs[key][i])
                    if o.dtype == np.uint16:   # сборка с --quantize_io: выход в сетке output_0 модели
                        o = (o.astype(np.float32) - run["qio"]["out"][1]) * run["qio"]["out"][0]
                    o = np.asarray(o, np.float32).reshape(1024, -1)
                    lv = np.asarray(outs[lkey][i]).ravel()[0] if lkey else o.shape[1]
                    if lkey and outs[lkey][i].dtype == np.uint16:   # qdq_fix.py --float-length + --quantize_io
                        lv = (float(lv) - 2) * 0.5
                    L = int(np.rint(lv))
                    o.tofile(d / f"{n}.f32")
                    (d / f"{n}.len").write_text(str(L))
                r = subprocess.run([str(P / ".stand/venv-qnn/bin/python"), "-I", str(Path(__file__).with_name("asr_wer.py")), str(d)],
                                   capture_output=True, text=True)
                wer = r.stdout.strip().split(" ", 1)[-1]
            else:
                wer = f"вывод не прошёл: {st.message}"
        rows.append((dev, npu, cpu, wer))
    for dev, npu, cpu, wer in rows:
        fmt = lambda x: f"{x[0]:.1f} мс {x[1]}" if x[0] is not None else f"— ({x[1]})"
        print(f"{dev}\n  NPU {run.get('label', 'fp16')}: {fmt(npu)}\n  CPU int8: {fmt(cpu)}\n  WER NPU: {wer}")


def quantize(a):
    """fp32 parakeet → QDQ (веса int8, активации int16) квантованием AI Hub на калибровке из публичного корпуса."""
    fp32_dir = Path(a.fp32_dir)
    if fp32_dir == FP32_DIR:
        FP32_DIR.mkdir(parents=True, exist_ok=True)
        for f in ("encoder-model.onnx", "encoder-model.onnx.data"):
            if not (FP32_DIR / f).exists():
                (FP32_DIR / f).hardlink_to(P / ".stand/npu/parakeet" / f)
    lines = [l.split("\t")[0] for l in (FEAT / "list.tsv").read_text().splitlines() if l.strip()]
    pt = [n for n in lines if n.startswith("pt_")][::4][: a.calib // 2]   # каждая 4-я — равномерно по корпусу
    ru = [n for n in lines if n.startswith("ru_")][::4][: a.calib // 2]
    names, data = dataset(set(pt + ru), length_dtype=np.int64)
    print(f"калибровка: {len(names)} фраз ({len(pt)} pt + {len(ru)} ru)", flush=True)
    m = model_ref(a.fp32_model) if a.fp32_model else (print(f"загружаю fp32 (2,4 ГБ) из {fp32_dir} …", flush=True) or hub.upload_model(str(fp32_dir)))
    cal = hub.get_dataset(a.calib_dataset) if a.calib_dataset else hub.upload_dataset(data, name=f"falar-calib-{len(names)}")
    # Квантование требует фиксированных форм: сначала компиляция в ONNX с формами (так советует сам AI Hub)
    cj = hub.submit_compile_job(model=m, device=hub.Device("Samsung Galaxy S24 (Family)"), name="falar parakeet fp32 static",
                                input_specs=SPECS, options="--target_runtime onnx")
    print(f"фиксирую формы: компиляция {cj.job_id} …", flush=True)
    st = cj.wait()
    if not st.success:
        print("компиляция в ONNX не прошла:", st.message)
        return 1
    m = cj.get_target_model()
    dt = {"int8": hub.QuantizeDtype.INT8, "int16": hub.QuantizeDtype.INT16, "int4": hub.QuantizeDtype.INT4}
    qj = hub.submit_quantize_job(model=m, calibration_data=cal, weights_dtype=dt[a.weights],
                                 activations_dtype=dt[a.acts], name=f"falar parakeet {a.label}", options=a.quant_opts)
    print(f"квантование {qj.job_id} (fp32 {m.model_id}, калибровка {cal.dataset_id}) …", flush=True)
    st = qj.wait()
    if not st.success:
        print("квантование не прошло:", st.message)
        return 1
    tm = qj.get_target_model()
    (OUT / "runs").mkdir(parents=True, exist_ok=True)
    (OUT / "runs" / f"{a.label}-model.txt").write_text(f"{tm.model_id}\n{', '.join(names)}\n")
    print(f"готово: модель {a.label} {tm.model_id} — дальше submit --npu-model {tm.model_id} --label {a.label}", flush=True)


def qparams(qdq_dir):
    """Сетка uint16 входа audio_signal и выхода output_0 в QDQ-модели (читает qdq_fix.py --io-params в venv-qnn, где есть
    onnx). С --quantize_io QAIRT отдаёт вход и выход в этой сетке, поэтому признаки квантуются здесь, а выход — обратно."""
    r = subprocess.run([str(P / ".stand/venv-qnn/bin/python"), "-I", str(Path(__file__).with_name("qdq_fix.py")),
                        "--io-params", str(qdq_dir)], capture_output=True, text=True, check=True)
    d = json.loads(r.stdout)
    return d["in"], d["out"]


def infer_qio(a):
    """Вывод на собранных с --quantize_io моделях: признаки → uint16 в сетке входа; сетку выхода collect вернёт в float."""
    run = json.loads(Path(a.run).read_text())
    qin, qout = qparams(a.qdq)
    names, data = dataset()
    s, z = qin
    x = [np.clip(np.rint(v / s) + z, 0, 65535).astype(np.uint16) for v in data["audio_signal"]]
    # после qdq_fix.py --float-length длина тоже квантуется: точная сетка пути длины, шаг 0,5, ноль 2
    ln = [(2 * v + 2).astype(np.uint16) for v in data["length"]] if a.float_length else data["length"]
    ds = hub.get_dataset(a.dataset) if a.dataset else hub.upload_dataset(
        {"audio_signal": x, "length": ln}, name="falar-corpus-162-u16" + ("-flen" if a.float_length else ""))
    run.update(qio={"in": qin, "out": qout, "dataset": ds.dataset_id})
    print(f"вход uint16 шаг {s:.6g} ноль {z}; выход шаг {qout[0]:.6g} ноль {qout[1]}; данные {ds.dataset_id}", flush=True)
    for j in run["jobs"]:
        if "npu_error" in j or "npu_infer" in j:
            continue
        cj = hub.get_job(j["npu_compile"])
        if not cj.wait().success:
            j["npu_error"] = cj.get_status().message
            continue
        j["npu_infer"] = hub.submit_inference_job(model=cj.get_target_model(), device=hub.Device(j["device"]), inputs=ds,
                                                  name="falar parakeet npu wer u16").job_id
        print(f"{j['device']}: вывод {j['npu_infer']}", flush=True)
    Path(a.run).write_text(json.dumps(run, ensure_ascii=False, indent=1))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    d = sub.add_parser("devices")
    d.add_argument("--grep", default="")
    s = sub.add_parser("submit")
    s.add_argument("--devices", required=True)
    s.add_argument("--float-length", action="store_true", help="модель после qdq_fix.py --float-length: длина — float32")
    s.add_argument("--npu-model", default=str(NPU_MODEL))
    s.add_argument("--cpu-model", default=str(CPU_MODEL))
    s.add_argument("--dataset", default="")
    s.add_argument("--label", default="fp16")
    s.add_argument("--no-infer", action="store_true")
    s.add_argument("--compile-opts", default="", help="добавить к сборке под NPU: --compile-opts=\"--quantize_io …\"")
    s.add_argument("--runtime", default="precompiled", choices=sorted(RUNTIMES), help="onnx — граф собирает ORT с QNN на телефоне")
    s.add_argument("--infer-opts", default="", help="параметры вывода и профиля, напр. \"--compute_unit npu --onnx_execution_providers qnn\"")
    s.add_argument("--calib-dataset", default="", help="калибровка для --quantize_full_type (квантует сам QAIRT)")
    q = sub.add_parser("quantize")
    q.add_argument("--calib", type=int, default=40)
    q.add_argument("--fp32-model", default="")
    q.add_argument("--calib-dataset", default="")
    q.add_argument("--fp32-dir", default=str(FP32_DIR))
    q.add_argument("--label", default="w8a16")
    q.add_argument("--weights", default="int8", choices=["int4", "int8", "int16"])
    q.add_argument("--acts", default="int16", choices=["int8", "int16"])
    q.add_argument("--quant-opts", default="", help="напр. --quant-opts=\"--range_scheme min_max\" или \"--lite_mp percentage=10;override_qtype=int16\"")
    c = sub.add_parser("collect")
    c.add_argument("run")
    i = sub.add_parser("infer-qio", help="вывод для сборок с --quantize_io (вход и выход — uint16)")
    i.add_argument("run")
    i.add_argument("--qdq", required=True, help="каталог QDQ-модели (model.onnx + model.data), из которой собрано")
    i.add_argument("--dataset", default="", help="ID уже загруженных uint16-признаков")
    i.add_argument("--float-length", action="store_true", help="модель после qdq_fix.py --float-length: длина — uint16 2·L+2")
    a = ap.parse_args()
    return {"devices": devices, "submit": submit, "collect": collect, "quantize": quantize, "infer-qio": infer_qio}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
