#!/bin/bash
# Голоса разговора на телефоне — в отдельных тестовых разговорах.
#
#   bash bench/apk/test_voices_device.sh [серийный номер]
#
# Проверяет:
#  V0 общий файл голосов прежних версий (models/speaker_profiles.json) удаляется при запуске, и в
#     журнале сказано, сколько голосов в нём было;
#  V1 фраза кнопкой FALAR (стенд --es testwav … --es ptt 1 — тот же путь, что после отпускания) —
#     новый голос «собеседник 1», язык по сказанному, номер у реплики, слепок в файле разговора;
#  V2 ещё фраза того же человека — тот же голос, в слепке две фразы;
#  V3 фраза другого человека по-русски — «собеседник 2», русский;
#  V4 слушание (feedwav — тот же путь, что у живого микрофона): фразы голосов 1 и 2 переведены с их
#     номерами; фразы третьего человека, не говорившего кнопкой, не переведены и в разговор не легли;
#  V5 разговор без голосов: слушание ничего не переводит и пишет, что нужна фраза кнопкой FALAR;
#  V6 модуль «Отпечаток голоса» выключен — слушание переводит всех, как до 0.27.
#
# Голоса — живые люди из Tatoeba (bench/air/corpus; стендовые записи, в сборку не идут), трое разных
# дикторов. Синтезированный голос для отпечатка не годится (results/2026-09-12-asr-upgrade.md).
#
# Данные владельца не трогаются: тестовые разговоры — отдельными файлами «ТЕСТ голоса…» с самым
# свежим временем, в конце удаляются; выученное, словари, свои слова, пины — из снимка; модули — как
# были; слушание в конце выключено (оно и было выключено — иначе прогон не начинается). Прогон
# оборвался, даже kill -9, — следующий запуск сначала возвращает телефон (или --restore).
# Замок общий с test_all_device.sh. Falar запускается только поверх себя, рабочего стола или
# погашенного экрана, и только когда экрана не касались минуту.
#
#   INSTALL=1 bash bench/apk/test_voices_device.sh   # сначала поставить bench/apk/Falar.apk
R=$(cd "$(dirname "$0")/../.." && pwd)
SER=f6lnlrorgi59xwge; ONLY_RESTORE=0
for a in "$@"; do case "$a" in --restore) ONLY_RESTORE=1;; *) SER=$a;; esac; done
ADB="$R/tools/platform-tools/adb -s $SER"
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log; M=$F/models
STATE=${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand; mkdir -p "$STATE"; PEND=$STATE/voices-pending
FILES="models/learned.json models/phrasebook_user.json word_ru.json known_words.json models/wordlist.json"
SPK=speaker/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx
PY=$R/.venv/bin/python; [ -x "$PY" ] || PY=python3
pass=0; fail=0; skip=0
say() { printf '%s\n' "$*"; }
sk() { skip=$((skip+1)); say "ПРОПУСК $1"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
# строка журнала после отметки; шаблон расширенный: у toybox «\|» в простом не работает
wl() { local i l; for i in $(seq "$3"); do l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
since() { sh "tail -n +$(($1+1)) $LOG"; }
focus() { sh "dumpsys window" | grep -m1 mCurrentFocus; }
asleep() { sh "dumpsys power" | grep -qE 'mWakefulness=(Asleep|Dozing)'; }
idle() {
  asleep && return 0
  case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) ;; *) return 1;; esac
  local a; a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
  [ "${a:-0}" -ge 60000 ]
}
wait_idle() { local n=0; until idle; do n=$((n+1)); [ $n -eq 1 ] && say "  жду, пока телефон свободен ($(focus))"; sleep 15; done; }
launch() { wait_idle; $ADB shell "am start -n $ACT $*" >/dev/null 2>&1; }
# поле разговора с телефона: python-выражение над o (json разговора)
cj() { $ADB pull "$F/chats/$1.json" "$D/c.json" >/dev/null 2>&1 || { echo "нет файла"; return; }; "$PY" -c "import json,sys; o=json.load(open('$D/c.json',encoding='utf-8')); print($2)"; }
ready() { wl "$1" '🧩 модули:' 150 >/dev/null && wl "$1" '🎤 отпечаток голоса готов|микрофон' 60 >/dev/null; sleep 2; }

# ---- замок -----------------------------------------------------------------------------------------
if [ -z "$FALAR_STAND_LOCK" ]; then
  exec 9>"$STATE/lock"
  flock -n 9 || { say "на телефоне уже идёт проверка (замок $STATE/lock) — не начинаю"; exit 1; }
fi

# ---- возврат: по файлу $PEND, поэтому переживает и kill -9 ------------------------------------------
restore() {
  [ -f "$PEND" ] || return 0
  local snap mods tids wavs spkpushed
  snap=$(sed -n 's/^snap //p' "$PEND"); mods=$(sed -n 's/^mods //p' "$PEND"); tids=$(sed -n 's/^chats //p' "$PEND")
  wavs=$(sed -n 's/^wavs //p' "$PEND"); spkpushed=$(sed -n 's/^spkpushed //p' "$PEND")
  say "== возврат"
  if [ -n "$mods" ] || grep -q '^started' "$PEND"; then
    launch "--es listen off --es silent 0 --es modules '${mods}'"; sleep 4
  fi
  $ADB shell "am force-stop $PKG"; sleep 1
  local f; for f in $FILES; do
    if [ -f "$snap/$(basename $f)" ]; then $ADB push "$snap/$(basename $f)" "$F/$f" >/dev/null 2>&1 && say "  вернул $f"
    elif [ -f "$snap/$(basename $f).none" ]; then $ADB shell "rm -f $F/$f"; fi
  done
  local t; for t in $tids; do $ADB shell "rm -f $F/chats/$t.json"; done; [ -n "$tids" ] && say "  тестовые разговоры удалены: $tids"
  for t in $wavs; do $ADB shell "rm -f $F/$t"; done
  [ "$spkpushed" = 1 ] && [ -z "$(echo ",$mods," | grep ',speaker,')" ] && $ADB shell "rm -f $M/$SPK" && say "  модель отпечатка, привезённая для проверки, убрана"
  say "  текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
  rm -f "$PEND"; [ -n "$snap" ] && rm -rf "$snap"
  launch; say "  модули: [${mods}] · слушание выключено"
}
$ADB wait-for-device
restore
[ "$ONLY_RESTORE" = 1 ] && exit 0

# ---- INSTALL=1: поставить собранную сборку (bench/apk/Falar.apk) ------------------------------------
# Только на свободный телефон: экран погашен или впереди Falar/рабочий стол и экрана не касались
# 3 минуты; место — прямо перед установкой; без incremental (тот оставляет ~0,4 ГБ до перезагрузки);
# строка Success/Failure — целиком; Falar был впереди — снова впереди.
if [ "${INSTALL:-0}" = 1 ]; then
  APK=$R/bench/apk/Falar.apk; [ -f "$APK" ] || { say "нет $APK — сначала build.sh"; exit 1; }
  # Свободен — это и экран не трогали 3 минуты, и Falar 3 минуты ничего не пишет в журнал, и владелец
  # не слушает: телефон на столе со «Слушать» экрана не касается, а установка убила бы разговор.
  n=0; while :; do
    a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
    quiet=$(( $(sh "date +%s") - $(sh "stat -c %Y $LOG 2>/dev/null || echo 0") ))
    listening=$(sh "grep -E '▶ слушаю|⏹ не слушаю' $LOG | tail -1" | grep -c '▶ слушаю')
    if [ "$listening" = 0 ] && [ "$quiet" -ge 180 ] && { asleep || { case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) true;; *) false;; esac && [ "${a:-0}" -ge 180000 ]; }; }; then break; fi
    n=$((n+1)); [ $n -eq 1 ] && say "  жду, пока телефон свободен для установки ($(focus | sed 's/.*{//; s/}.*//'), журнал молчит $quiet с, слушание: $listening)"; sleep 20
  done
  front=0; case "$(focus)" in *$PKG*) asleep || front=1;; esac
  free=$(sh "df /data" | awk 'NR==2{print $4}'); say "  свободно на /data: $free КБ"
  [ "${free:-0}" -ge 1500000 ] || { say "мало места для установки — попросите владельца перезагрузить телефон"; exit 1; }
  before=$(sh "dumpsys package $PKG" | grep -m1 lastUpdateTime)
  out=$($ADB install --no-incremental -r "$APK" 2>&1 | tr -d '\r' | grep -E '^(Success|Failure)'); say "  установка: ${out:-нет ответа}"
  after=$(sh "dumpsys package $PKG" | grep -m1 lastUpdateTime)
  case "$out" in Success*) [ "$before" != "$after" ] || { say "сборка не сменилась"; exit 1; };; *) exit 1;; esac
  say "  $(sh "dumpsys package $PKG" | grep -m1 versionName | tr -d ' ') · $after"
  [ $front = 1 ] && $ADB shell "am start -n $ACT" >/dev/null 2>&1
fi
D=$(mktemp -d "$STATE/voices-run.XXXX")
trap 'restore; rm -rf "$D"; say; say "итог: PASS $pass, FAIL $fail, пропущено $skip"' EXIT

# ---- записи: три живых голоса из Tatoeba --------------------------------------------------------------
say "== записи"
"$PY" - "$R" "$D" <<'EOF' || { say "записи не собрались"; exit 1; }
import bz2, os, sys, wave, random
import numpy as np
R, D = sys.argv[1], sys.argv[2]
def clips(lang, code, user):
    idx = {}
    with bz2.open(f'{R}/data/tatoeba/raw/{code}_sentences_with_audio.tsv.bz2', 'rt', encoding='utf-8') as f:
        for line in f:
            c = line.rstrip('\n').split('\t')
            if len(c) >= 3: idx[c[1]] = c[2]
    out = []
    for l in open(f'{R}/bench/air/corpus/{lang}/LICENSES.tsv', encoding='utf-8'):
        stem, aid = l.split('\t')[:2]
        if idx.get(aid) == user: out.append(f'{R}/bench/air/corpus/{lang}/{stem}')
    return sorted(out)
def rd(p):
    with wave.open(p + '.wav') as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1
        return np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float32) / 32768
def wr(name, x):
    rng = np.random.default_rng(7)
    x = x + rng.normal(0, 10 ** (-70 / 20), len(x)).astype(np.float32)   # не цифровой ноль: оценке фона нужен шум
    with wave.open(f'{D}/{name}', 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype('<i2').tobytes())
A = clips('pt', 'por', 'alexmarcelo'); B = clips('ru', 'rus', 'Inego'); C = clips('pt', 'por', 'Silfarle')
sil = lambda s: np.zeros(int(s * 16000), np.float32)
wr('vt_A1.wav', rd(A[1])); wr('vt_A2.wav', rd(A[2])); wr('vt_B1.wav', rd(B[5]))
feed = [('A', A[3]), ('C', C[1]), ('B', B[4]), ('C', C[3]), ('A', A[4])]
x = [sil(1.0)]
for who, p in feed: x += [rd(p), sil(2.5)]
wr('vt_feed.wav', np.concatenate(x))
wr('vt_none.wav', np.concatenate([sil(1.0), rd(A[5]), sil(2.5)]))
wr('vt_off.wav', np.concatenate([sil(1.0), rd(C[2]), sil(2.5)]))
# двое подряд вплотную, без своей тишины записей по краям (с ней пауза ~0,6 с, и куски режет уже нарезка):
# весь кусок похож на обоих (эталон: 0,55 и 0,58), окна — [1 1 1 1 2 2] — режется по голосам
def speech(x, f=320):
    n = len(x) // f; e = 20 * np.log10(np.array([np.sqrt(np.mean(x[i*f:(i+1)*f] ** 2)) for i in range(n)]) + 1e-9)
    k = np.where(e > e.max() - 35)[0]; return x[max(0, k[0] - 2) * f:min(n, k[-1] + 3) * f]
wr('vt_two.wav', np.concatenate([sil(1.0), speech(rd(A[7])), speech(rd(B[12])), sil(0.3)]))   # как режет нарезка: подпор 1 с, хвост 0,3 с
with open(f'{D}/feed.tsv', 'w', encoding='utf-8') as f:
    for who, p in feed: f.write(who + '\t' + open(p + '.txt', encoding='utf-8').read().strip() + '\n')
    f.write('none\t' + open(A[5] + '.txt', encoding='utf-8').read().strip() + '\n')
    f.write('off\t' + open(C[2] + '.txt', encoding='utf-8').read().strip() + '\n')
    f.write('two_a\t' + open(A[7] + '.txt', encoding='utf-8').read().strip() + '\n')
    f.write('two_b\t' + open(B[12] + '.txt', encoding='utf-8').read().strip() + '\n')
EOF
WAVS="vt_A1.wav vt_A2.wav vt_B1.wav vt_feed.wav vt_none.wav vt_off.wav vt_two.wav vt_gold.json"
GOLD=$R/bench/apk/test/voiceprint_golden.json
cp "$GOLD" "$D/vt_gold.json"
for w in $("$PY" -c "import json,sys; print(' '.join(o['wav'] for o in json.load(open(sys.argv[1]))))" "$GOLD"); do cp "$R/$w" "$D/"; WAVS="$WAVS $(basename $w)"; done
say "  $(cut -f1 "$D/feed.tsv" | tr '\n' ' ')· $(du -ch $(printf "$D/%s " $WAVS) | tail -1 | cut -f1)"

# ---- снимок, модули, тестовые разговоры -----------------------------------------------------------
say "== снимок перед проверкой"
SNAP="$STATE/voices-snap-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$SNAP"
for f in $FILES; do
  n=$(sh "stat -c %s $F/$f 2>/dev/null")
  if [ -n "$n" ]; then $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1
    [ "$(stat -c %s "$SNAP/$(basename $f)" 2>/dev/null)" = "$n" ] || { say "снимок $f не сошёлся — не начинаю"; exit 1; }
  else touch "$SNAP/$(basename $f).none"; fi
done
# Слушание владельца: последняя строка о нём в журнале. Включено — не начинаю: выключать его не мне.
case "$(sh "grep -E '▶ слушаю|⏹ не слушаю' $LOG | tail -1")" in *"▶ слушаю"*) say "у владельца включено слушание — не начинаю"; exit 1;; esac
m=$(mark); launch --es modules show
MODS=$(wl "$m" '🧩 модули сейчас' 60 | grep -oE '\[[a-z,]*\]' | tr -d '[]')
[ -n "$(wl "$m" '🧩 модули сейчас' 1)" ] || { say "модули не прочлись — не начинаю"; exit 1; }
SPKPUSHED=0
if [ -z "$(sh "ls $M/$SPK 2>/dev/null")" ]; then
  [ -f "$R/models/$SPK" ] || { say "нет models/$SPK в проекте — не начинаю"; exit 1; }
  sh "mkdir -p $M/speaker"; $ADB push "$R/models/$SPK" "$M/$SPK" >/dev/null 2>&1 && SPKPUSHED=1 && say "  модель отпечатка привезена из проекта"
fi
TID=$(date +%s%3N); TID2=$((TID + 1)); TID3=$((TID + 2))
{ echo "snap $SNAP"; echo "mods $MODS"; echo "chats $TID $TID2 $TID3"; echo "wavs $WAVS"; echo "spkpushed $SPKPUSHED"; } > "$PEND"
say "  $(ls "$SNAP" | tr '\n' ' ')· модули [$MODS]"
for w in $WAVS; do $ADB push "$D/$w" "$F/$w" >/dev/null 2>&1 || { say "запись $w не легла на телефон"; exit 1; }; done

# Старый общий файл голосов: свой не подкладываем, если у владельца он есть — проверяем на его.
OLD=$(sh "cat $M/speaker_profiles.json 2>/dev/null")
if [ -z "$OLD" ]; then
  printf '{"я":{"lang":"ru","e":[0.6,0.8]},"собеседник":{"lang":"pt","e":[0.8,0.6]}}' > "$D/old.json"
  $ADB push "$D/old.json" "$M/speaker_profiles.json" >/dev/null 2>&1; OLDN=2
else OLDN=$(printf '%s' "$OLD" | "$PY" -c "import json,sys; print(len(json.load(sys.stdin)))" 2>/dev/null); fi
"$PY" -c "import json; json.dump({'id': $TID, 'name': 'ТЕСТ голоса', 'named': True, 'saved': $TID, 'turns': []}, open('$D/t.json','w',encoding='utf-8'), ensure_ascii=False)"
$ADB shell "am force-stop $PKG"; sleep 1
$ADB push "$D/t.json" "$F/chats/$TID.json" >/dev/null 2>&1
echo started >> "$PEND"
MODS_ON=$(printf '%s' ",$MODS,speaker," | tr ',' '\n' | grep -v '^$' | sort -u | paste -sd,)
MODS_OFF=$(printf '%s' ",$MODS," | tr ',' '\n' | grep -v -e '^$' -e '^speaker$' | paste -sd,)
m=$(mark); m0=$m; launch "--es modules '$MODS_ON' --es silent 1"
ready "$m"
wl "$m" '🎤 отпечаток голоса (готов|подключён)' 60 >/dev/null || { res 1 "V· отпечаток голоса не поднялся"; exit 1; }
cur=$(sh "ls -t $F/chats/ | head -1")
[ "$cur" = "$TID.json" ] || { res 1 "V· текущий разговор не тестовый: $cur"; exit 1; }

say "== V·: отпечаток на телефоне — тот же, что у эталона со стола"
m=$(mark); launch --es voicegold "$F/vt_gold.json"
l=$(wl "$m" '🎤 эталон отпечатка' 60); say "  ${l:-нет строки}"
w=$(printf '%s' "$l" | sed -n 's/.*худший \([-0-9.]*\).*/\1/p')
"$PY" -c "import sys; sys.exit(0 if float('${w:--9}') >= 0.999 else 1)" && res 0 "V· Java-признаки и ORT дают эталонный отпечаток (худший косинус $w)" || res 1 "V· отпечаток не эталонный: ${w:-нет}"

say "== V0: общий файл голосов прежних версий"
l=$(wl "$m0" 'общие голоса прежних версий удалены' 5); say "  ${l:-нет строки}"
printf '%s' "$l" | grep -q "удалены: ${OLDN:-?} " && res 0 "V0 удалён, в журнале — сколько было ($OLDN)" || res 1 "V0 строка: ${l:-нет}"
[ -z "$(sh "ls $M/speaker_profiles.json 2>/dev/null")" ] && res 0 "V0 файла больше нет" || res 1 "V0 файл остался"

say "== V1–V3: фразы кнопкой FALAR"
ptt() { local m l; m=$(mark); launch --es testwav "$F/$1" --es ptt 1; l=$(wl "$m" '🎤 (новый голос|голос узнан)|голос не записан|пропуска|пропущено' 60); printf '%s' "$l"
  # перевод не ждёт отпечатка: строка реплики (#N …) — раньше строки о голосе
  local tl vl; tl=$(since "$m" | grep -n -m1 -E '^[0-9:.]+ #[0-9]+ ' | cut -d: -f1); vl=$(since "$m" | grep -n -m1 -E '🎤 (новый голос|голос узнан)' | cut -d: -f1)
  [ -n "$tl" ] && [ -n "$vl" ] && [ "$tl" -lt "$vl" ] && echo " [реплика раньше голоса]" || echo " [голос раньше реплики или строк нет]"; }
l=$(ptt vt_A1.wav); say "  $l"
printf '%s' "$l" | grep -q "новый голос: собеседник 1 (pt)" && res 0 "V1 первый голос — собеседник 1, португальский" || res 1 "V1 ${l:-нет строки}"
printf '%s' "$l" | grep -q "реплика раньше голоса" && res 0 "V1 перевод не ждал отпечатка: реплика в журнале раньше голоса" || res 1 "V1 порядок строк: голос не после реплики"
sleep 2
eq=$(cj "$TID" "len(o['turns']), o['turns'][-1].get('who'), [(v['n'], v['lang'], v['k'], len(v['e'])) for v in o.get('voices', [])]")
[ "$eq" = "1 1 [(1, 'pt', 1, 512)]" ] && res 0 "V1 номер у реплики, слепок в файле разговора (512 чисел)" || res 1 "V1 в файле: $eq"
l=$(ptt vt_A2.wav); say "  $l"
printf '%s' "$l" | grep -q "голос узнан: собеседник 1" && res 0 "V2 вторая фраза — тот же голос" || res 1 "V2 ${l:-нет строки}"
sleep 2
eq=$(cj "$TID" "o['turns'][-1].get('who'), [(v['n'], v['k']) for v in o.get('voices', [])]")
[ "$eq" = "1 [(1, 2)]" ] && res 0 "V2 в слепке две фразы" || res 1 "V2 в файле: $eq"
l=$(ptt vt_B1.wav); say "  $l"
printf '%s' "$l" | grep -q "новый голос: собеседник 2 (ru)" && res 0 "V3 другой человек по-русски — собеседник 2" || res 1 "V3 ${l:-нет строки}"
sleep 2

say "== V4: слушание — переводятся только голоса разговора"
n0=$(cj "$TID" "len(o['turns'])")
m=$(mark); launch --es listen both; sleep 2; launch --es feedwav "$F/vt_feed.wav"
wl "$m" 'подача закончена' 120 >/dev/null; sleep 8
L=$(since "$m")
skips=$(printf '%s\n' "$L" | grep -c '🎤 чужой голос')
say "  ждал отпечаток перед переводом, мс: $(printf '%s\n' "$L" | sed -n 's/.*ждал \([0-9]*\) мс.*/\1/p' | tr '\n' ' ')· сам отпечаток, мс: $(printf '%s\n' "$L" | sed -n 's/.*отпечаток \([0-9]*\) мс.*/\1/p' | tr '\n' ' ')"
say "  чужой голос: $skips · $(printf '%s\n' "$L" | grep -E '🎤 чужой голос' | sed 's/.*ближе всех/ближе всех/' | tr '\n' ';')"
cj "$TID" "'\n'.join((t.get('who','-') + '\t' + t['src']) for t in o['turns'][$n0:])" > "$D/got.tsv"
sed 's/^/  реплика: /' "$D/got.tsv"
"$PY" - "$D/feed.tsv" "$D/got.tsv" > "$D/v4" <<'EOF'
import sys, re, difflib
want = [l.rstrip('\n').split('\t') for l in open(sys.argv[1], encoding='utf-8')][:5]
got = [l.rstrip('\n').split('\t', 1) for l in open(sys.argv[2], encoding='utf-8') if '\t' in l]
norm = lambda s: re.sub(r'[^\w ]', '', s.lower())
num = {'A': '1', 'B': '2'}
ok_turns = bad = 0
for who, src in got:
    best = max(want, key=lambda w: difflib.SequenceMatcher(None, norm(w[1]), norm(src)).ratio())
    r = difflib.SequenceMatcher(None, norm(best[1]), norm(src)).ratio()
    if best[0] == 'C' or r < 0.5 or num.get(best[0]) != who: bad += 1
    else: ok_turns += 1
print(ok_turns, bad, sum(1 for w in want if w[0] != 'C'))
EOF
read okn badn need < "$D/v4"
[ "${badn:-1}" = 0 ] && res 0 "V4 ни одной реплики третьего человека и ни одной с чужим номером" || res 1 "V4 неверных реплик: $badn"
[ "${okn:-0}" -ge $((need - 1)) ] && res 0 "V4 голоса разговора переведены со своими номерами: $okn из $need" || res 1 "V4 переведено голосов разговора: $okn из $need"
[ "$skips" -ge 1 ] && res 0 "V4 чужие обрывки отброшены по голосу: $skips" || res 1 "V4 строк «чужой голос» нет"

say "== V7: двое подряд в одном куске — разрез по голосам, каждый кусок своим языком"
# Кусок подаётся прямо в решение по голосу (--es segwav), мимо нарезки: её разрез от прогона к прогону
# разный (01.10 она один раз отрезала начало фразы A отдельным обрывком), а проверяется разрез голосом.
n0=$(cj "$TID" "len(o['turns'])")
m=$(mark); launch --es segwav "$F/vt_two.wav"
wl "$m" '🎤 (двое в одном сегменте|сегмент похож на двоих|чужой голос)' 60 >/dev/null; sleep 12
l=$(since "$m" | grep -m1 'двое в одном сегменте'); say "  ${l:-нет строки о разрезе}"
cj "$TID" "'\n'.join((t.get('who','-') + '\t' + t['src']) for t in o['turns'][$n0:])" > "$D/two.tsv"
sed 's/^/  реплика: /' "$D/two.tsv"
"$PY" - "$D/feed.tsv" "$D/two.tsv" > "$D/v7" <<'EOF'
import sys, re, difflib
want = dict(l.rstrip('\n').split('\t', 1) for l in open(sys.argv[1], encoding='utf-8'))
got = [l.rstrip('\n').split('\t', 1) for l in open(sys.argv[2], encoding='utf-8') if '\t' in l]
norm = lambda s: re.sub(r'[^\w ]', '', s.lower())
r = lambda a, b: difflib.SequenceMatcher(None, norm(a), norm(b)).ratio()
a = any(w == '1' and r(want['two_a'], t) >= 0.5 for w, t in got)
b = any(w == '2' and r(want['two_b'], t) >= 0.5 for w, t in got)
print(int(a), int(b), len(got))
EOF
read va vb vn < "$D/v7"
[ -n "$l" ] && res 0 "V7 кусок двоих разрезан по голосам" || res 1 "V7 строки о разрезе нет"
[ "$va" = 1 ] && [ "$vb" = 1 ] && res 0 "V7 обе фразы переведены, каждая со своим номером и на своём языке (реплик: $vn)" || res 1 "V7 фраза собеседника 1: $va, собеседника 2: $vb (реплик: $vn)"

say "== V8: подписи на экране — над репликой и в карточках"
# Только при включённом экране и Falar впереди: uiautomator видит лишь то, что на экране; касаний нет.
if ! asleep && focus | grep -q "$PKG/"; then
  launch; sleep 3
  T=$(sh "uiautomator dump /sdcard/falar-vt.xml >/dev/null; cat /sdcard/falar-vt.xml; rm -f /sdcard/falar-vt.xml" | "$PY" -c "
import re,sys,html
print('\n'.join(html.unescape(t) for t in re.findall(r'text=\"([^\"]*)\"', sys.stdin.read()) if t))")
  printf '%s\n' "$T" | grep -qx 'Собеседник 2' && res 0 "V8 над текущей репликой — «Собеседник 2»" || res 1 "V8 подписи «Собеседник 2» нет: $(printf '%s' "$T" | head -c 300 | tr '\n' '|')"
  printf '%s\n' "$T" | grep -qx 'Собеседник 1' && res 0 "V8 в карточках — «Собеседник 1»" || res 1 "V8 подписи «Собеседник 1» в карточках нет"
else sk "V8 экран погашен или впереди не Falar ($(focus | sed 's/.*{//; s/}.*//'))"; fi

say "== V5: разговор без голосов"
"$PY" -c "import json; json.dump({'id': $TID2, 'name': 'ТЕСТ голоса 2', 'named': True, 'saved': $TID2, 'turns': []}, open('$D/t2.json','w',encoding='utf-8'), ensure_ascii=False)"
$ADB push "$D/t2.json" "$F/chats/$TID2.json" >/dev/null 2>&1
m=$(mark); launch --es openchat "$TID2"; sleep 2; launch --es feedwav "$F/vt_none.wav"
wl "$m" 'подача закончена' 60 >/dev/null; sleep 6
l=$(since "$m" | grep -m1 'в разговоре ещё нет голосов')
[ -n "$l" ] && res 0 "V5 слушание просит фразу кнопкой FALAR" || res 1 "V5 строки о голосах нет"
[ "$(cj "$TID2" "len(o['turns'])")" = 0 ] && res 0 "V5 в разговор ничего не легло" || res 1 "V5 реплики: $(cj "$TID2" "[t['src'] for t in o['turns']]")"

say "== V6: модуль выключен — слушание как до 0.27"
"$PY" -c "import json; json.dump({'id': $TID3, 'name': 'ТЕСТ голоса 3', 'named': True, 'saved': $TID3, 'turns': []}, open('$D/t3.json','w',encoding='utf-8'), ensure_ascii=False)"
$ADB push "$D/t3.json" "$F/chats/$TID3.json" >/dev/null 2>&1
m=$(mark); launch "--es openchat $TID3 --es modules '$MODS_OFF'"; sleep 3; launch --es feedwav "$F/vt_off.wav"
wl "$m" 'подача закончена' 60 >/dev/null; sleep 8
got=$(cj "$TID3" "len(o['turns']), o['turns'][0].get('who','-') if o['turns'] else ''")
# без отпечатка нарезка та же, что до 0.27: фраза может лечь и двумя репликами — важно, что легла и без номера
case "$got" in [1-9]*" -") res 0 "V6 без отпечатка фраза переведена, номера нет (реплик: ${got%% *})";; *) res 1 "V6 в разговоре: $got";; esac
launch --es listen off
