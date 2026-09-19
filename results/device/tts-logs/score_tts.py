"""Сводка TTS-логов (RTF, время до первого звука) + WER обратного распознавания."""
import re, sys, json, statistics as st, unicodedata
def norm(s):
    s=unicodedata.normalize('NFC', s.lower()).replace('ё','е'); s=re.sub(r"[^\w\s]", " ", s); return s.split()
def wer(ref, hyp):
    r,h=norm(ref),norm(hyp); d=list(range(len(h)+1))
    for i in range(1,len(r)+1):
        p=d[:]; d[0]=i
        for j in range(1,len(h)+1): d[j]=min(p[j]+1,d[j-1]+1,p[j-1]+(r[i-1]!=h[j-1]))
    return d[len(h)],len(r)
texts={'ru':open('/tmp/llbench/tts/text/ru.txt').read().splitlines(),'pt':open('/tmp/llbench/tts/text/pt.txt').read().splitlines()}
def summarize(logpath):
    log=open(logpath, encoding='utf-8', errors='replace').read(); out={}
    for block in re.split(r'^### ', log, flags=re.M)[1:]:
        head, body = block.split('\n',1); parts=head.split()
        if len(parts)<2 or parts[1]=='END': continue
        tag=parts[0]; el=re.search(r'Elapsed seconds:\s*([\d.]+)', body); au=re.search(r'Audio duration:\s*([\d.]+)', body)
        if not (el and au): continue
        out.setdefault(tag,[]).append((float(el.group(1)), float(au.group(1))))
    return out
def asr_hyps(logpath):
    """parakeet offline log: пути и JSON пачками, сопоставляем по порядку"""
    log=open(logpath, encoding='utf-8', errors='replace').read(); res={}
    for block in re.split(r'^### ', log, flags=re.M)[1:]:
        head, body = block.split('\n',1); tag=head.strip()
        if tag=='END': continue
        lines=body.split('\n')
        paths=[re.match(r'^/data/local/tmp/sh/tts/out/([^/\s]+)\.wav$', l.strip()) for l in lines]; paths=[m.group(1) for m in paths if m]
        texts_=[json.loads('"'+mm.group(1)+'"') for l in lines for mm in re.finditer(r'"text"\s*:\s*"((?:[^"\\]|\\.)*)"', l)]
        for name,t in zip(paths,texts_): res[name]=t.strip()
    return res
if __name__=='__main__':
    tts=summarize(sys.argv[1]); asr=asr_hyps(sys.argv[2]) if len(sys.argv)>2 else {}
    print(f"{'голос':22s} {'фраз':>4s} {'RTF med':>8s} {'RTF max':>8s} {'синтез/фразу':>12s} {'аудио/фразу':>11s} {'ASR-WER':>8s}")
    for tag,rows in tts.items():
        lang=tag.split('_')[0]; rtfs=[e/a for e,a in rows]
        err=tot=0
        for i,(e,a) in enumerate(rows, start=1):
            hyp=asr.get(f'{tag}_{i}')
            if hyp is not None: ee,tt=wer(texts[lang][i-1],hyp); err+=ee; tot+=tt
        w=f"{100*err/tot:5.1f}%" if tot else "   —"
        print(f"{tag:22s} {len(rows):4d} {st.median(rtfs):8.3f} {max(rtfs):8.3f} {st.median(e for e,a in rows):10.2f} с {st.median(a for e,a in rows):9.2f} с {w:>8s}")
