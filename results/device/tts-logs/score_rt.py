"""Финальная сводка TTS: RTF из логов синтеза + ASR-WER из rt/<tag>.err (порядок JSON = порядок файлов 1..12)."""
import re, json, glob, os, statistics as st, sys
sys.path.insert(0,'/tmp/llbench/tts'); from score_tts import summarize, texts, wer
tts=summarize('phone_tts_all.log')
print(f"{'голос':18s} {'RTF med':>8s} {'RTF max':>8s} {'синтез/фразу':>12s} {'аудио/фразу':>11s} {'ASR-WER':>8s}  худшая фраза")
rows_out={}
for tag,rows in tts.items():
    lang=tag.split('_')[0]; f=f'rt/{tag}.out'
    if not os.path.exists(f): continue
    hyps=[json.loads('"'+m.group(1)+'"') for m in re.finditer(r'"text"\s*:\s*"((?:[^"\\]|\\.)*)"', open(f,encoding='utf-8',errors='replace').read())]
    err=tot=0; worst=(0,'','')
    for i,hyp in enumerate(hyps[:12]):
        e,t=wer(texts[lang][i],hyp); err+=e; tot+=t
        if t and e/t>worst[0]: worst=(e/t,texts[lang][i],hyp)
    rtfs=[e/a for e,a in rows]
    rows_out[tag]=dict(rtf_med=round(st.median(rtfs),3), rtf_max=round(max(rtfs),3), synth=round(st.median(e for e,a in rows),2), audio=round(st.median(a for e,a in rows),2), wer=round(100*err/tot,1) if tot else None, n=len(hyps))
    print(f"{tag:18s} {st.median(rtfs):8.3f} {max(rtfs):8.3f} {st.median(e for e,a in rows):10.2f} с {st.median(a for e,a in rows):9.2f} с {100*err/tot if tot else 0:7.1f}%  [{len(hyps)}] {worst[1][:38]} -> {worst[2][:38]}")
json.dump(rows_out, open('tts_summary.json','w'), ensure_ascii=False, indent=1)
