#!/bin/bash
# Во что обходится движение кнопки удержания: вращение кольца или пульс ореола — на телефоне.
#
#   bash bench/apk/measure_mic_anim.sh [повторов=2] [окно, с=10]
#   KINDS="none live" bash bench/apk/measure_mic_anim.sh 3
#
# Виды: live — рабочий: кнопка удержания с цветом и свечением по уровню, похожему на речь
# (MainActivity.liveDemo); pulse30 — ровный пульс без уровня; spin — вращение кольца надписей;
# none — нажатая позиция без движения (нулевая точка); meter — кольцо «как слышно» вокруг
# «Слушать» в доке (MainActivity.meterDemo; до 0.26.0 — полоска уровня под кнопками слушания). Пульс на каждом кадре экрана (pulse) из кода убран — замер
# 2026-09-29 в results/2026-09-29-mic-button.md.
# Приложение показывает нажатую позицию без записи (--es micanim <вид>): микрофон не открывается,
# в разговоры ничего не попадает. За окно считается время на процессоре (нс из schedstat) — главный
# поток приложения, его RenderThread, весь процесс, SurfaceFlinger, все ядра по /proc/stat — и кадры
# по dumpsys gfxinfo вместе с временем GPU. Виды идут туда и обратно: телефон греется, и второй
# прогон подряд медленнее первого при любом порядке.
#
# Нужны включённый экран и Falar впереди: без экрана кадры не рисуются, и замер был бы пуст.
# Экран не разблокируется; чужое приложение впереди — ждём; прогон, во время которого телефон
# взяли в руки, выбрасывается.
R=$(cd "$(dirname "$0")/../.." && pwd)
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
REP=${1:-2}; WIN=${2:-10}
KINDS=(${KINDS:-none spin pulse30 meter live})
D=$(mktemp -d /tmp/falar-micanim.XXXX); RAW=$D/raw.txt
sh() { $ADB shell "$@" 2>/dev/null | tr -d '\r'; }
say() { printf '%s\n' "$*"; }
focus() { sh "dumpsys window" | grep -m1 mCurrentFocus; }
awake() { sh "dumpsys power" | grep -q "mWakefulness=Awake"; }
# Falar впереди — можно; лаунчер — поднять Falar; что-то другое или погасший экран — ждать.
ready() {
  local n=0
  while :; do
    if awake; then
      case "$(focus)" in
        *$PKG/*) return 0;;
        *com.miui.home*|*launcher*) $ADB shell "am start -n $ACT" >/dev/null 2>&1; sleep 3; continue;;
      esac
    fi
    n=$((n+1)); [ $n -eq 1 ] && say "  телефон занят или экран погашен ($(focus)), жду…"; sleep 10
  done
}
# Сырые счётчики: складывать на телефоне нельзя — арифметика mksh 32-битная, наносекунды не влезают.
# Все потоки одним grep: цикл с подоболочкой на поток — это ~300 fork на телефоне и секунды на
# снимок, окно замера уезжало за конец показа (первый прогон так и выброшен).
cat > $D/cpu.sh <<'PHONE'
p=$(pidof app.falar); s=$(pidof surfaceflinger)
echo "pid $p"
echo "up $(cut -d' ' -f1 /proc/uptime)"
echo "stat $(head -1 /proc/stat)"
grep -H . /proc/$p/task/*/schedstat /proc/$p/task/*/comm /proc/$s/task/*/schedstat 2>/dev/null
PHONE
$ADB push $D/cpu.sh /data/local/tmp/falar_cpu.sh >/dev/null

order=()
for r in $(seq $REP); do
  if [ $((r % 2)) -eq 1 ]; then order+=("${KINDS[@]}"); else for ((k=${#KINDS[@]}-1; k>=0; k--)); do order+=("${KINDS[$k]}"); done; fi
done
say "== ${#order[@]} прогонов по $WIN с: ${order[*]}"
for k in "${order[@]}"; do
  ready
  $ADB shell "am start -n $ACT --es micanim $k --es micsec $((WIN + 5))" >/dev/null 2>&1
  sleep 2
  a=$(sh "sh /data/local/tmp/falar_cpu.sh"); sh "dumpsys gfxinfo $PKG reset" >/dev/null
  sleep $WIN
  b=$(sh "sh /data/local/tmp/falar_cpu.sh"); g=$(sh "dumpsys gfxinfo $PKG" | grep -E 'Total frames rendered|percentile')
  if ! awake || ! focus | grep -q "$PKG/"; then say "  $k: телефон взяли в руки — прогон выброшен"; sleep 5; continue; fi
  { echo "@run $k"; echo "$a" | sed 's/^/A /'; echo "$b" | sed 's/^/B /'; echo "$g" | sed 's/^/G /'; } >> $RAW
  say "  $k: $(echo "$g" | grep -m1 'Total frames' | tr -s ' ')"
  sleep 5
done
$ADB shell rm -f /data/local/tmp/falar_cpu.sh

python3 - "$RAW" <<'PY'
import re, sys, statistics as st
runs = []
for block in open(sys.argv[1], encoding="utf-8").read().split("@run ")[1:]:
    lines = block.splitlines(); kind = lines[0].strip()
    snap = {"A": {"t": {}}, "B": {"t": {}}}; g = {}
    for l in lines[1:]:
        tag, f = l[:1], l[2:].split(None, 3)
        if not f: continue
        if tag == "G":
            m = re.search(r"Total frames rendered: (\d+)", l)
            if m: g["frames"] = int(m.group(1))
            m = re.search(r"(\d+)th (gpu )?percentile: (\d+)ms", l)
            if m: g[("gpu" if m.group(2) else "cpu") + m.group(1)] = int(m.group(3))
        elif f[0] == "pid": snap[tag]["pid"] = f[1]
        elif f[0] == "up": snap[tag]["up"] = float(f[1])
        elif f[0] == "stat":
            v = list(map(int, l[2:].split()[2:]))
            snap[tag]["busy"] = sum(v) - v[3] - v[4]          # всё, кроме idle и iowait; тики по 10 мс
        else:
            m = re.match(r"/proc/(\d+)/task/(\d+)/(schedstat|comm):(.*)", l[2:])
            if m:
                d = snap[tag]["t"].setdefault((m.group(1), m.group(2)), [0, ""])
                if m.group(3) == "schedstat": d[0] = int(m.group(4).split()[0])
                else: d[1] = m.group(4)
    A, B = snap["A"], snap["B"]; dt = B["up"] - A["up"]
    if A.get("pid") != B.get("pid"): print("  " + kind + ": процесс сменился за окно — прогон выброшен"); continue
    def ms(sel):                                           # мс процессора на секунду окна
        return sum(B["t"][k][0] - A["t"][k][0] for k in B["t"] if k in A["t"] and sel(k, B["t"][k][1])) / 1e6 / dt
    p = A["pid"]
    runs.append({"kind": kind,
                 "main": ms(lambda k, c: k == (p, p)), "rt": ms(lambda k, c: k[0] == p and c == "RenderThread"),
                 "app": ms(lambda k, c: k[0] == p), "sf": ms(lambda k, c: k[0] != p),
                 "all": (B["busy"] - A["busy"]) * 10 / dt, "fps": g.get("frames", 0) / dt,
                 "f50": g.get("cpu50"), "f90": g.get("cpu90"), "gpu50": g.get("gpu50"), "gpu90": g.get("gpu90")})
print("\nмс процессора на секунду окна (среднее ± половина разброса), кадры — последнего прогона вида")
print("%-11s %2s %11s %11s %11s %11s %13s %11s  %9s  %9s" % ("вид", "n", "главный", "Render", "процесс", "SF", "все ядра", "кадр/с", "кадр50/90", "GPU50/90"))
for kind in ["none", "spin", "pulse", "pulse30", "meter", "live"]:
    rs = [r for r in runs if r["kind"] == kind]
    if not rs: continue
    def f(key):
        xs = [r[key] for r in rs]
        return "%5.1f ± %-3.1f" % (st.mean(xs), (max(xs) - min(xs)) / 2)
    q = lambda a, b: "/".join("-" if rs[-1][x] is None else str(rs[-1][x]) for x in (a, b))
    print("%-11s %2d %11s %11s %11s %11s %13s %11s  %9s  %9s" % (kind, len(rs), f("main"), f("rt"), f("app"), f("sf"), f("all"), f("fps"), q("f50", "f90"), q("gpu50", "gpu90")))
PY
say "сырые счётчики: $RAW"
