#!/bin/bash
# Во что обходится удержание кнопки с речью: процессор и кадры за время записи и время
# распознавания после — на той сборке, что стоит на телефоне. Для сравнения прежнего и нового
# счёта цвета (results/2026-09-29-mic-live.md) гоняется на двух сборках по очереди.
#
#   bash bench/apk/measure_live_cost.sh [удержаний=3]
#
# Вместо микрофона телефон играет запись комнаты (--es micfile: три фразы near-pt с паузами по
# 1,5 с, bench/air/live_wav.py), кнопку держит настоящее касание. За окно внутри удержания — время
# на процессоре (нс из schedstat): поток захвата (там счёт цвета удержания), главный поток,
# RenderThread, весь процесс, SurfaceFlinger — и кадры по dumpsys gfxinfo. После отпускания —
# время распознавания фразы из at.tsv. Разговор тестовый, озвучка выключена, выученное и словари
# возвращаются из снимка; разговоры владельца не трогаются.
#
# Телефон — рабочий аппарат владельца: ждём, пока журнал Falar молчит FREE_S секунд (180), и
# касаемся, только пока Falar впереди и экран включён. Прогон, во время которого сменился процесс
# или телефон взяли в руки, выбрасывается.
R=$(cd "$(dirname "$0")/../.." && pwd)
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
N=${1:-3}; FREE_S=${FREE_S:-180}
D=$(mktemp -d /tmp/falar-livecost.XXXX); SNAP=$D/snap; mkdir -p $SNAP; RAW=$D/raw.txt; TID=$(date +%s%3N)
say() { printf '%s\n' "$*"; }
sh() { $ADB shell "$@" 2>/dev/null | tr -d '\r'; }
quiet_s() { sh "echo \$(( \$(date +%s) - \$(stat -c %Y $LOG 2>/dev/null || echo 0) ))"; }
focus() { sh "dumpsys window" | grep -m1 mCurrentFocus; }
front() { sh "dumpsys power" | grep -q "mWakefulness=Awake" && focus | grep -q "$PKG/"; }
free_phone() {
  local n=0
  while :; do
    local f; f=$(focus)
    case "$f" in *$PKG*|*com.miui.home*|*launcher*|*mCurrentFocus=null*)
      [ "$(quiet_s)" -ge "$FREE_S" ] && return 0; f="журнал Falar писался $(quiet_s) с назад";; esac
    n=$((n+1)); [ $n -eq 1 ] && say "  телефон занят ($f), жду…"; sleep 10
  done
}
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
restore() {
  say "== возврат"
  $ADB shell "am force-stop $PKG"; sleep 2
  for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do
    [ -f "$SNAP/$(basename $f)" ] && $ADB push "$SNAP/$(basename $f)" "$F/$f" >/dev/null 2>&1; done
  $ADB shell "rm -f $F/chats/$TID.json $F/live?.wav /data/local/tmp/falar_cpu.sh"
  say "  тестовый разговор удалён · текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
  $ADB shell "am start -n $ACT" >/dev/null 2>&1; rm -rf "$D"
}
trap restore EXIT

$ADB wait-for-device; free_phone
say "сборка на телефоне: $(sh "dumpsys package $PKG" | grep -m1 -o 'versionName=[^ ]*') · $(sh "dumpsys package $PKG" | grep -m1 -o 'lastUpdateTime=.*')"
for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1; done
python3 - "$D/t.json" "$TID" <<'EOF'
import json, sys
path, tid = sys.argv[1], int(sys.argv[2])
json.dump({"id": tid, "name": "ТЕСТ цена цвета", "named": True, "saved": tid, "turns": []}, open(path, "w", encoding="utf-8"), ensure_ascii=False)
EOF
# У каждого удержания свои фразы: одинаковые отбросил бы фильтр «прочли вслух с экрана».
for k in $(seq $N); do python3 $R/bench/air/live_wav.py $R/bench/air/rec/near-pt $D/live$k.wav $((7 + 3 * k)) > $D/live$k.json; done
$ADB shell "am force-stop $PKG"; sleep 1
$ADB push "$D/t.json" "$F/chats/$TID.json" >/dev/null 2>&1
for k in $(seq $N); do $ADB push $D/live$k.wav $F/live$k.wav >/dev/null 2>&1; done
m=$(mark); $ADB shell "am start -n $ACT" >/dev/null 2>&1; wl "$m" 'микрофон выключен' 120 >/dev/null; sleep 2
front || { say "впереди не Falar или экран погашен — касаться нельзя"; exit 1; }
$ADB shell "am start -n $ACT --es silent 1" >/dev/null 2>&1; sleep 1
$ADB shell "am start -n $ACT --es micanim idle --es micsec 600" >/dev/null 2>&1; sleep 2
b=$(sh "uiautomator dump /sdcard/falar-ui.xml >/dev/null; cat /sdcard/falar-ui.xml; rm -f /sdcard/falar-ui.xml" | python3 -c "
import re,sys
m=re.search(r'content-desc=\"Удерживайте и говорите[^\"]*\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', sys.stdin.read())
print(*m.groups() if m else '')")
[ -n "$b" ] || { say "кнопки удержания нет на экране"; exit 1; }
read x0 y0 x1 y1 <<< "$b"; cx=$(( (x0 + x1) / 2 )); cy=$(( (y0 + y1) / 2 ))
# Сырые счётчики одним grep: на телефоне арифметика 32-битная, наносекунды не влезают.
cat > $D/cpu.sh <<'PHONE'
p=$(pidof app.falar); s=$(pidof surfaceflinger)
echo "pid $p"
echo "up $(cut -d' ' -f1 /proc/uptime)"
grep -H . /proc/$p/task/*/schedstat /proc/$p/task/*/comm /proc/$s/task/*/schedstat 2>/dev/null
PHONE
$ADB push $D/cpu.sh /data/local/tmp/falar_cpu.sh >/dev/null

say "== $N удержаний, после каждого — 25 с на распознавание и уточнитель, чтобы окно следующего было чистым"
for k in $(seq $N); do
  front || { say "  $k: впереди не Falar — жду"; free_phone; }
  L=$(python3 -c "import json; print(int(json.load(open('$D/live$k.json'))['len'] * 1000) + 400)")
  m=$(mark); $ADB shell "am start -n $ACT --es micfile $F/live$k.wav" >/dev/null 2>&1
  wl "$m" 'стенд: вместо микрофона будет' 10 >/dev/null || { say "  $k: телефон не взял запись"; continue; }
  sleep 1; m=$(mark)
  $ADB shell "input swipe $cx $cy $cx $cy $L" &
  sleep 1.8; a=$(sh "sh /data/local/tmp/falar_cpu.sh"); sh "dumpsys gfxinfo $PKG reset" >/dev/null
  sleep $(python3 -c "print(max(1, $L / 1000 - 3.2))")
  z=$(sh "sh /data/local/tmp/falar_cpu.sh"); g=$(sh "dumpsys gfxinfo $PKG" | grep -E 'Total frames rendered')
  wait
  asr=$(wl "$m" '→|пропущено' 40)
  t0=$(sh "tail -n 200 $F/at.tsv | grep micfile_begin | tail -1" | cut -f1)
  t=$(sh "tail -n 60 $F/at.tsv" | awk -F'\t' -v t0="$t0" '$1 > t0 && ($2 == "asr" || $2 ~ /^skip/)' | head -1)
  if ! front; then say "  $k: телефон взяли в руки — прогон выброшен"; continue; fi
  { echo "@run $k"; echo "$a" | sed 's/^/A /'; echo "$z" | sed 's/^/B /'; echo "$g" | sed 's/^/G /'; echo "T $t"; } >> $RAW
  say "  $k: $(echo "$g" | tr -s ' ') · $(echo "$asr" | cut -c1-90)"
  sleep 25
done

python3 - "$RAW" <<'PY'
import re, sys, statistics as st
runs = []
for block in open(sys.argv[1], encoding="utf-8").read().split("@run ")[1:]:
    lines = block.splitlines(); snap = {"A": {"t": {}}, "B": {"t": {}}}; g = {}; asr = None
    for l in lines[1:]:
        tag, rest = l[:1], l[2:]
        if tag == "G":
            m = re.search(r"Total frames rendered: (\d+)", l); g["frames"] = int(m.group(1)) if m else 0
        elif tag == "T":
            c = rest.split("\t")
            if len(c) > 11 and c[11].isdigit(): asr = int(c[11])
        else:
            f = rest.split(None, 1)
            if not f: continue
            if f[0] == "pid": snap[tag]["pid"] = f[1].strip()
            elif f[0] == "up": snap[tag]["up"] = float(f[1])
            else:
                m = re.match(r"/proc/(\d+)/task/(\d+)/(schedstat|comm):(.*)", rest)
                if m:
                    d = snap[tag]["t"].setdefault((m.group(1), m.group(2)), [0, ""])
                    if m.group(3) == "schedstat": d[0] = int(m.group(4).split()[0])
                    else: d[1] = m.group(4)
    A, B = snap["A"], snap["B"]
    if A.get("pid") != B.get("pid"): print("  процесс сменился за окно — прогон выброшен"); continue
    dt = B["up"] - A["up"]; p = A["pid"]
    def ms(sel): return sum(B["t"][k][0] - A["t"][k][0] for k in B["t"] if k in A["t"] and sel(k, B["t"][k][1])) / 1e6 / dt
    runs.append({"cap": ms(lambda k, c: k[0] == p and c == "capture"), "main": ms(lambda k, c: k == (p, p)),
                 "rt": ms(lambda k, c: k[0] == p and c == "RenderThread"), "app": ms(lambda k, c: k[0] == p),
                 "sf": ms(lambda k, c: k[0] != p), "fps": g.get("frames", 0) / dt, "asr": asr, "dt": dt})
if not runs: sys.exit("нет годных прогонов")
print("\nмс процессора на секунду окна внутри удержания (среднее ± половина разброса), окно %.1f с" % st.mean(r["dt"] for r in runs))
f = lambda key: "%6.1f ± %-4.1f" % (st.mean(r[key] for r in runs), (max(r[key] for r in runs) - min(r[key] for r in runs)) / 2)
print("  n=%d  захват %s  главный %s  Render %s  процесс %s  SF %s  кадр/с %s" % (len(runs), f("cap"), f("main"), f("rt"), f("app"), f("sf"), f("fps")))
a = [r["asr"] for r in runs if r["asr"] is not None]
print("  распознавание фразы после отпускания, мс: %s" % (" ".join(map(str, a)) or "—"))
PY
