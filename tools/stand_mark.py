#!/usr/bin/env python3
"""Пометить в разговорах на телефоне реплики стендовых прогонов (поле `stand`, Chats.java).

Зачем. Прогоны через комнату (bench/air/replay_air.sh) писали корпусные фразы в текущий разговор
владельца: на 02.10 это было не меньше 455 реплик из 1280, и «Слова» учили слова замеров. Новые реплики
стенд помечает сам (TranslatorService.standNow); этот инструмент — для накопленных раньше. Реплики не
удаляются: метка только убирает их из разбора слов и снимается одной командой (--unmark).

Что считается стендовым. Фразы стенда: корпус bench/air/corpus/{pt,ru}/*.txt, фразы стенда эха
(TranslatorService.AEC_PHRASES) и буквальные feedtext/feedasr из скриптов bench/. Реплика —
кандидат, если с ними совпадает **каждое** её предложение: после нормализации (как Phrasebook.norm)
точно, или посимвольно ≥ 0,8, или по словам (Жаккар ≥ 0,6 при трёх словах и больше) — распознавание
искажает фразу. Метка — только кандидату в серии из двух и больше кандидатов подряд: прогон стенда —
это десятки корпусных фраз подряд, а одиночное совпадение бывает и живой речью. Первый сухой прогон
03.10 пометил бы одну живую фразу из настоящего разговора: по словам (три из пяти) она близка к
стендовой. Одиночные совпадения показывает --show; их метку решает человек.

Содержимое разговоров на компьютер не пишется: читается через adb в память, наружу — только счёт
(и по --show — короткие одиночные кандидаты, чтобы владелец решил сам).

  python3 tools/stand_mark.py            # только счёт, ничего не меняет
  python3 tools/stand_mark.py --show     # плюс одиночные совпадения — их метка не ставится
  python3 tools/stand_mark.py --apply    # пометить: под замком стенда, когда телефон свободен
  python3 tools/stand_mark.py --unmark   # снять все метки
"""
import difflib, fcntl, glob, json, os, re, subprocess, sys, time, unicodedata

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = os.path.join(R, "tools/platform-tools/adb")
PKG = "app.falar"; ACT = PKG + "/dev.agenttranslator.MainActivity"
F = "/sdcard/Android/data/%s/files" % PKG; LOG = F + "/at.log"
LOCK = os.path.expanduser("~/.cache/falar-stand/lock")
SENT = re.compile(r"(?<=[.!?…])\s+(?=\S)|\s*\n+\s*")
CONTR = {"vc": "você", "tá": "está", "ta": "está", "pra": "para", "pro": "para o", "né": "não é", "tô": "estou",
         "to": "estou", "cadê": "onde está", "q": "que", "tb": "também"}


def norm(s):
    """Как Phrasebook.norm: NFC, регистр, ё→е, без знаков, развёртка разговорных сокращений."""
    s = unicodedata.normalize("NFC", s).lower().replace("ё", "е")
    s = re.sub(r"[^\w\s'-]|_", " ", s)
    return " ".join(CONTR.get(w, w) for w in s.split())


def adb(*a, inp=None):
    return subprocess.run([ADB, *a], input=inp, capture_output=True).stdout


def sh(cmd):
    return adb("shell", cmd).decode("utf-8", "replace").replace("\r", "")


def phrases():
    out = set()
    for f in glob.glob(os.path.join(R, "bench/air/corpus/*/*.txt")):
        for s in SENT.split(open(f, encoding="utf-8").read()):
            if norm(s): out.add(norm(s))
    src = open(os.path.join(R, "bench/apk/src/dev/agenttranslator/TranslatorService.java"), encoding="utf-8").read()
    aec = src[src.find("AEC_PHRASES"):]
    for m in re.finditer(r'\{"(?:pt|ru)", "([^"]+)"\}', aec[:aec.find("};")]):
        for s in SENT.split(m.group(1)):
            if norm(s): out.add(norm(s))
    for f in glob.glob(os.path.join(R, "bench/**/*.sh"), recursive=True):
        for m in re.finditer(r"feed(?:text|asr) '([^'$]+)'", open(f, encoding="utf-8", errors="replace").read()):
            for s in SENT.split(m.group(1)):
                if norm(s): out.add(norm(s))
    return out


class Matcher:
    def __init__(self, ph):
        self.ph = ph; self.lst = sorted(ph); self.words = {p: set(p.split()) for p in self.lst}

    def sent(self, s):
        if s in self.ph: return True
        w = set(s.split())
        for p in self.lst:
            if abs(len(p) - len(s)) > 0.35 * max(len(p), len(s)): continue
            m = difflib.SequenceMatcher(None, s, p)
            if m.real_quick_ratio() >= 0.8 and m.quick_ratio() >= 0.8 and m.ratio() >= 0.8: return True
            pw = self.words[p]
            if len(w) >= 3 and len(pw) >= 3 and len(w & pw) / len(w | pw) >= 0.6: return True
        return False

    def turn(self, text):
        ss = [norm(s) for s in SENT.split(text)]
        ss = [s for s in ss if re.search(r"[^\W\d_]", s)]
        return bool(ss) and all(self.sent(s) for s in ss)


def chats():
    for n in sh("ls %s/chats" % F).split():
        if not n.endswith(".json"): continue
        try: yield int(n[:-5]), json.loads(adb("exec-out", "cat %s/chats/%s" % (F, n)).decode("utf-8"))
        except Exception as e: print("не прочёлся %s: %s" % (n, e))


def plan(show=False):
    m = Matcher(phrases())
    print("фраз стенда: %d" % len(m.ph))
    marks, total, already, lonely = {}, 0, 0, []
    for k, (cid, o) in enumerate(chats()):
        t = [x for x in o.get("turns", []) if not x.get("photo")]
        cand = [m.turn(x.get("src", "")) or m.turn(x.get("fixed") or x.get("dst", "")) for x in t]
        mk = []
        for i, x in enumerate(t):
            if not cand[i]: continue
            run = (i > 0 and cand[i - 1]) or (i + 1 < len(t) and cand[i + 1])
            if run: mk.append(x)
            else: lonely.append(x.get("src", ""))
        already += sum(1 for x in t if x.get("stand") == 1)
        total += len(t)
        print("разговор %2d: реплик %4d · похожих на стенд %4d · к пометке %4d" % (k + 1, len(t), sum(cand), len(mk)))
        if mk: marks[cid] = [x.get("at") for x in mk if x.get("stand") != 1]
    n = sum(len(v) for v in marks.values())
    print("всего реплик %d · к пометке %d (%.0f %%) · уже с меткой %d · одиночных совпадений без метки %d"
          % (total, n, 100 * n / max(1, total), already, len(lonely)))
    if show and lonely: print("одиночные совпадения (не помечаются):\n  " + "\n  ".join(sorted(set(lonely))))
    return marks


def idle():
    p = sh("dumpsys power")
    if re.search(r"mWakefulness=(Asleep|Dozing)", p): return True
    f = re.search(r"mCurrentFocus=.*", sh("dumpsys window")); f = f.group(0) if f else ""
    if not any(k in f for k in (PKG, "com.miui.home", "launcher")): return False
    a = re.search(r"lastUserActivityTime=\d+ \((\d+) ms ago\)", p)
    return a is not None and int(a.group(1)) >= 60000


def send(extra, value, expect, data=None):
    os.makedirs(os.path.dirname(LOCK), exist_ok=True)
    with open(LOCK, "w") as lk:
        t0, said = time.time(), False
        while True:
            try: fcntl.flock(lk, fcntl.LOCK_EX | fcntl.LOCK_NB); break
            except BlockingIOError:
                if time.time() - t0 > 7200: sys.exit("замок стенда не освободился — не начинаю")
                if not said: print("замок стенда занят — жду"); said = True
                time.sleep(15)
        n = 0
        while not idle():
            n += 1
            if n == 1: print("жду, пока телефон свободен")
            time.sleep(15)
        if data is not None:
            adb("push", data, F + "/standmark.txt")
        mark = int(sh("wc -l < %s" % LOG).strip() or 0)
        sh("am start -n %s --es %s %s" % (ACT, extra, value))
        for _ in range(60):
            time.sleep(1)
            got = [l for l in sh("tail -n +%d %s" % (mark + 1, LOG)).splitlines() if expect in l]
            if got: print(got[-1]); return
        print("ответа приложения в журнале не дождался — посмотрите журнал")


if __name__ == "__main__":
    if "--unmark" in sys.argv:
        send("standunmark", "1", "метка прогона снята"); sys.exit(0)
    marks = plan("--show" in sys.argv)
    if "--apply" in sys.argv:
        if not marks: print("помечать нечего"); sys.exit(0)
        tmp = os.path.expanduser("~/.cache/falar-stand/standmark.txt")
        with open(tmp, "w") as f:
            for cid, ats in marks.items():
                for at in ats: f.write("%d %d\n" % (cid, at))
        try: send("standmark", "standmark.txt", "помечено реплик прогонов", tmp)
        finally: os.remove(tmp)
