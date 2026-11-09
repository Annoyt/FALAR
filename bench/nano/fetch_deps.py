#!/usr/bin/env python3
"""Скачать ML Kit GenAI Prompt API со всеми зависимостями (без Gradle).

Обходит POM-файлы от корня (compile + runtime), при конфликте версий берёт старшую,
кладёт aar/jar в ~/.cache/falar-stand/jars/nano/ и пишет список в deps.txt.
"""
import os, re, sys, urllib.request, xml.etree.ElementTree as ET

REPOS = ["https://dl.google.com/android/maven2", "https://repo1.maven.org/maven2"]
OUT = os.path.expanduser("~/.cache/falar-stand/jars/nano")
ROOT = sys.argv[1] if len(sys.argv) > 1 else "com.google.mlkit:genai-prompt:1.0.0-beta4"
NS = "{http://maven.apache.org/POM/4.0.0}"

def get(path):
    for r in REPOS:
        try:
            with urllib.request.urlopen(f"{r}/{path}", timeout=60) as f:
                return f.read()
        except Exception:
            pass
    return None

def vkey(v):
    return [int(x) if x.isdigit() else x for x in re.split(r"[.\-]", v)]

def newer(a, b):
    try:
        return vkey(a) > vkey(b)
    except TypeError:
        return a > b

poms = {}
def pom(g, a, v):
    k = (g, a, v)
    if k not in poms:
        raw = get(f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.pom")
        poms[k] = ET.fromstring(raw) if raw else None
    return poms[k]

def text(e, tag):
    x = e.find(NS + tag)
    return x.text.strip() if x is not None and x.text else None

def props(p):
    d = {}
    pe = p.find(NS + "properties")
    if pe is not None:
        for c in pe:
            d[c.tag.replace(NS, "")] = (c.text or "").strip()
    return d

def resolve_ver(v, pr):
    if not v:
        return None
    v = re.sub(r"\$\{([^}]+)\}", lambda m: pr.get(m.group(1), m.group(0)), v)
    m = re.match(r"^\[([^,\]]+)\]$", v)  # [1.2.3] — точная версия
    return m.group(1) if m else v

best, packaging, queue = {}, {}, [tuple(ROOT.split(":"))]
while queue:
    g, a, v = queue.pop()
    if (g, a) in best and not newer(v, best[(g, a)]):
        continue
    best[(g, a)] = v
    p = pom(g, a, v)
    if p is None:
        print("нет POM:", g, a, v); continue
    packaging[(g, a)] = text(p, "packaging") or "jar"
    pr = props(p)
    deps = p.find(NS + "dependencies")
    for d in (deps if deps is not None else []):
        if (text(d, "scope") or "compile") not in ("compile", "runtime") or text(d, "optional") == "true":
            continue
        dv = resolve_ver(text(d, "version"), pr)
        if dv and "$" not in dv:
            queue.append((text(d, "groupId"), text(d, "artifactId"), dv))

os.makedirs(OUT, exist_ok=True)
lines = []
for (g, a), v in sorted(best.items()):
    pk = packaging.get((g, a), "jar")
    ext = "aar" if pk == "aar" else "jar"
    fn = f"{g}__{a}-{v}.{ext}"
    dst = os.path.join(OUT, fn)
    if not os.path.exists(dst):
        data = get(f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.{ext}")
        if data is None and ext == "jar":  # bundle/pom-only
            print("нет артефакта (только POM?):", g, a, v); continue
        open(dst, "wb").write(data)
    lines.append(fn)
    print(f"{g}:{a}:{v} ({ext})")
open(os.path.join(OUT, "deps.txt"), "w").write("\n".join(lines) + "\n")
print(len(lines), "артефактов в", OUT)
