"""WER по логам sherpa-onnx (offline и streaming форматы)."""
import re, sys, os, unicodedata, json
def norm(s):
    s=unicodedata.normalize('NFC', s.lower()).replace('ё','е'); s=re.sub(r"[^\w\s]", " ", s); return s.split()
def wer(ref, hyp):
    r, h = norm(ref), norm(hyp); d=list(range(len(h)+1))
    for i in range(1,len(r)+1):
        p=d[:]; d[0]=i
        for j in range(1,len(h)+1): d[j]=min(p[j]+1, d[j-1]+1, p[j-1]+(r[i-1]!=h[j-1]))
    return d[len(h)], len(r)
log=open(sys.argv[1]).read(); show='--show' in sys.argv
for block in re.split(r'^### ', log, flags=re.M)[1:]:
    name, body = block.split('\n',1); name=name.strip()
    if name=='END': continue
    lines=body.split('\n'); hyps={}
    paths=[re.match(r'^/data/local/tmp/sh/audio/(pt|ru)/([^\s/]+)\.wav\s*$', l.strip()) for l in lines]
    paths=[(i,m.group(1),m.group(2)) for i,m in enumerate(paths) if m]
    texts=[(i,json.loads('"'+mm.group(1)+'"')) for i,l in enumerate(lines) for mm in [re.search(r'"text"\s*:\s*"((?:[^"\\]|\\.)*)"', l)] if mm]
    if 'Number of threads' in body:   # streaming: текст идёт сразу после пути
        for i,lang,fn in paths:
            t=[x for k,x in texts if i<k<=i+6]
            if t: hyps[(lang,fn)]=t[0].strip()
    else:                              # offline: JSON печатается пачкой, в порядке файлов
        for (i,lang,fn),(k,t) in zip(paths,texts): hyps[(lang,fn)]=t.strip()
    err=tot=0; n=0; rows=[]
    for (lang,fn),hyp in hyps.items():
        rp=f'/tmp/llbench/asr/push/{lang}/{fn}.txt'
        if not os.path.exists(rp): continue
        ref=open(rp).read(); e,t=wer(ref,hyp); err+=e; tot+=t; n+=1; rows.append((e,t,ref,hyp))
    rtf=re.findall(r'RTF\)?\s*[:=]?\s*[\d.]+\s*/\s*[\d.]+\s*=\s*([\d.]+)', body)
    load=re.findall(r'[Rr]ecognizer created in ([\d.]+)', body)
    print(f"{name:22s} файлов: {n:2d}  WER: {100*err/max(1,tot):5.1f}%  RTF: {', '.join(rtf[:12])}  | загрузка: {load[0] if load else '?'} с")
    if show:
        for e,t,ref,hyp in rows[:4]: print(f"      [{e}/{t}] ref: {ref[:70]}\n              hyp: {hyp[:70]}")
