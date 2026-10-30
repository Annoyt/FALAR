#!/bin/bash
# Голосовое из мессенджера на телефоне (VoiceMsg, ShareActivity, TranslatorService.voiceMessage; владелец 15.10).
# Голосовые собираются здесь же из записей корпуса Tatoeba (bench/air/corpus: живые голоса, лицензии в LICENSES.tsv):
# фразы через паузу, Ogg Opus 16 кГц моно 24 кбит/с — как голосовое WhatsApp. Всё — в своём тестовом разговоре:
#   M1 португальское из трёх фраз: строка «🎙 голосовое 0:NN · кусков ≥ 2», реплика pt2ru вида «голосовое», без
#      озвучки; распознано не меньше 70 % слов эталона; в разговоре у реплики пометка "voice";
#   M2 русское из двух фраз: направление ru2pt — по языку текста;
#   M3 не звук (текст с расширением .ogg): «не раскрылось», реплики нет, Falar жив;
#   M4 «Поделиться»: am start -a SEND -t audio/ogg с content-URI MediaStore → ShareActivity копирует и передаёт главному
#      экрану (если оболочка не может выдать право на URI — пропуск);
#   M5 файл стенда остаётся на месте, копии в cache/voice после распознавания нет.
#   bash bench/apk/test_voicemsg_device.sh          # INSTALL=1 — сначала поставить bench/apk/Falar.apk (build.sh)
# Телефон — рабочий аппарат владельца: под замком стенда, ждём свободный телефон; тестовый разговор, его звук и
# файлы голосовых после прогона удаляются, текущим снова становится прежний разговор.
. "$(dirname "$0")/../stand.sh"   # $STAND, $PRIVATE — состояние стенда и данные телефонов внутри проекта
R=$(cd "$(dirname "$0")/../.." && pwd); A=$R/bench/apk; C=$R/bench/air/corpus
if [ -z "$FALAR_STAND_LOCK" ]; then
  mkdir -p "$STAND"; exec 9>"$STAND/lock"
  flock -n 9 || { echo "на телефоне уже идёт проверка — вторую не начинаю"; exit 1; }; export FALAR_STAND_LOCK=1
fi
ADB=${ADB:-$R/tools/platform-tools/adb}; PY=${PY:-python3}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
FREE_S=${FREE_S:-60}
D=$(mktemp -d /tmp/falar-voicemsg.XXXX); pass=0; fail=0; skip=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
sk() { skip=$((skip+1)); say "ПРОПУСК $1"; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
focus() { sh "dumpsys window | grep -m1 mCurrentFocus"; }
idle() {
  sh "dumpsys power" | grep -qE "mWakefulness=(Asleep|Dozing)" && return 0
  case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) return 0;; esac; return 1
}
launch() {
  local n=0
  until idle; do n=$((n+1)); [ $n -eq 1 ] && say "  (запуск ждёт, пока телефон освободится: $(focus))"; sleep 5; done
  $ADB shell "am start $*" >/dev/null 2>&1
}
free_phone() {
  local n=0 f a
  [ -n "$(sh 'echo ok')" ] || { say "телефон не подключён"; exit 2; }
  while :; do
    sh "dumpsys power" | grep -qE "mWakefulness=(Asleep|Dozing)" && return 0
    f=$(focus); a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
    case "$f" in *$PKG*|*com.miui.home*|*launcher*) [ "${a:-0}" -ge $((FREE_S * 1000)) ] && return 0;; esac
    n=$((n+1)); [ $n -eq 1 ] && say "  жду: экран погашен или впереди Falar/рабочий стол, ${FREE_S} с без касаний ($f)"; sleep 10
  done
}
# Доля слов эталона, найденных в распознанном (без знаков и регистра).
share() { "$PY" - "$1" "$2" <<'PY'
import re, sys
w = lambda s: re.findall(r"\w+", s.lower())
ref, got = w(sys.argv[1]), set(w(sys.argv[2]))
print("%.2f" % (sum(1 for x in ref if x in got) / max(1, len(ref))))
PY
}
# Голосовое из записей корпуса: «список файлов» → файл .ogg; эталон — склейка их текстов.
make() {   # make <выход.ogg> <файлы корпуса…>
  local out=$1; shift; local args=() fc="" i=0 f
  for f in "$@"; do args+=(-i "$f"); fc="$fc[$i:a]aresample=16000,apad=pad_dur=0.9[a$i];"; i=$((i+1)); done
  local cat=""; for k in $(seq 0 $((i-1))); do cat="$cat[a$k]"; done
  ffmpeg -v error -y "${args[@]}" -filter_complex "${fc}${cat}concat=n=$i:v=0:a=1[out]" -map "[out]" -ac 1 -ar 16000 -c:a libopus -b:a 24k -application voip "$out"
}

TID=$(date +%s%3N)
restore() {
  say "== возврат"
  $ADB shell "am force-stop $PKG"; sleep 1
  $ADB shell "rm -f $F/chats/$TID.json $F/audio/${TID}_* $F/vm_pt.ogg $F/vm_ru.ogg $F/vm_bad.ogg /sdcard/Music/falar_vm_share.ogg" >/dev/null 2>&1
  [ -n "$MEDIA_ID" ] && sh "content delete --uri content://media/external/audio/media/$MEDIA_ID" >/dev/null
  say "  тестовый разговор и файлы голосовых убраны; текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
  idle && launch -n $ACT; rm -rf "$D"
  say; say "итог: PASS $pass, FAIL $fail, пропущено $skip"
}

command -v ffmpeg >/dev/null || { say "нет ffmpeg — голосовые не собрать"; exit 1; }
PT=(tat_1063144 tat_1063513 tat_1102494); RU=($(ls $C/ru/*.wav | head -40 | tail -2 | xargs -n1 basename | sed 's/\.wav$//'))
make $D/vm_pt.ogg $(for t in "${PT[@]}"; do echo $C/pt/$t.wav; done) || { say "голосовое PT не собралось"; exit 1; }
make $D/vm_ru.ogg $(for t in "${RU[@]}"; do echo $C/ru/$t.wav; done) || { say "голосовое RU не собралось"; exit 1; }
REF_PT=$(for t in "${PT[@]}"; do cat $C/pt/$t.txt; echo; done | tr '\n' ' '); REF_RU=$(for t in "${RU[@]}"; do cat $C/ru/$t.txt; echo; done | tr '\n' ' ')
printf 'это не звук, а текст\n' > $D/vm_bad.ogg
say "== голосовые: PT $(ffprobe -v error -show_entries format=duration -of csv=p=0 $D/vm_pt.ogg) с, RU $(ffprobe -v error -show_entries format=duration -of csv=p=0 $D/vm_ru.ogg) с"

free_phone
trap restore EXIT
if [ "$INSTALL" = 1 ]; then
  apk=${APK:-$A/Falar.apk}
  want=$(aapt2 dump badging "$apk" 2>/dev/null | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)
  have=$(sh "dumpsys package $PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
  [ -n "$have" ] && [ -n "$want" ] && [ "$want" -lt "$have" ] && { say "на телефоне сборка $have, эта — $want: понижение стёрло бы данные, не ставлю"; exit 1; }
  r=$($ADB install --no-incremental -r "$apk" 2>&1 | tail -1); say "== ставлю $apk ($want поверх ${have:-нет}): $r"
  case "$r" in Success*) ;; *) exit 1;; esac
fi
"$PY" -c "import json; json.dump({'id': $TID, 'name': 'ТЕСТ голосовые', 'named': True, 'saved': $TID, 'turns': []}, open('$D/t.json','w',encoding='utf-8'), ensure_ascii=False)"
$ADB shell "am force-stop $PKG"; sleep 1
$ADB push "$D/t.json" "$F/chats/$TID.json" >/dev/null 2>&1
for f in vm_pt.ogg vm_ru.ogg vm_bad.ogg; do $ADB push "$D/$f" "$F/$f" >/dev/null 2>&1 || { say "$f не лёг на телефон"; exit 1; }; done
m=$(mark); launch -n $ACT
wl "$m" '🧩 модули:' 150 >/dev/null || { res 1 "M· Falar не загрузился за 150 с"; exit 1; }
sleep 2
cur=$(sh "ls -t $F/chats/ | head -1")
[ "$cur" = "$TID.json" ] || { res 1 "M· текущий разговор не тестовый: $cur"; exit 1; }
last() { $ADB pull "$F/chats/$TID.json" "$D/c.json" >/dev/null 2>&1; "$PY" -c "
import json; t = json.load(open('$D/c.json', encoding='utf-8')).get('turns', [])
x = t[-1] if t else {}; print(len(t), x.get('dir', ''), x.get('voice', ''), x.get('src', '').replace(chr(10), ' '), sep='\t')"; }

say "== M1 португальское голосовое"
m=$(mark); launch -n $ACT --es voicefile "$F/vm_pt.ogg"
l=$(wl "$m" '🎙 голосовое [0-9]' 120); say "  ${l:-нет строки}"
n=$(printf '%s' "$l" | sed -n 's/.*кусков \([0-9]*\).*/\1/p')
[ "${n:-0}" -ge 2 ] && res 0 "M1 нарезано на куски: ${n:-0}" || res 1 "M1 нарезано на куски: ${n:-0} (ждали ≥ 2)"
t=$(wl "$m" 'голосовое, без озвучки' 60); say "  ${t:0:220}"
[ -n "$t" ] && res 0 "M1 реплика «голосовое», без озвучки" || res 1 "M1 реплика «голосовое», без озвучки"
IFS=$'\t' read -r cnt dir voice src <<< "$(last)"
[ "$dir" = pt2ru ] && res 0 "M1 направление pt2ru" || res 1 "M1 направление pt2ru ($dir)"
[ -n "$voice" ] && res 0 "M1 пометка voice: $voice с" || res 1 "M1 пометки voice нет"
s=$(share "$REF_PT" "$src"); say "  эталон: $REF_PT"; say "  распознано: $src"
"$PY" -c "import sys; sys.exit(0 if float('$s') >= 0.7 else 1)" && res 0 "M1 слова эталона распознаны: $s" || res 1 "M1 слова эталона распознаны: $s (< 0,7)"
sh "ls $F/vm_pt.ogg" | grep -q vm_pt && res 0 "M5 файл стенда на месте" || res 1 "M5 файл стенда пропал"

say "== M2 русское голосовое"
m=$(mark); launch -n $ACT --es voicefile "$F/vm_ru.ogg"
wl "$m" 'голосовое, без озвучки' 120 >/dev/null
IFS=$'\t' read -r cnt2 dir src <<< "$(last | cut -f1,2,4)"
[ "$dir" = ru2pt ] && res 0 "M2 направление ru2pt — по языку текста" || res 1 "M2 направление ru2pt ($dir)"
s=$(share "$REF_RU" "$src"); say "  распознано: $src"
"$PY" -c "import sys; sys.exit(0 if float('$s') >= 0.7 else 1)" && res 0 "M2 слова эталона распознаны: $s" || res 1 "M2 слова эталона распознаны: $s (< 0,7)"

say "== M3 не звук"
m=$(mark); launch -n $ACT --es voicefile "$F/vm_bad.ogg"
l=$(wl "$m" '🎙 голосовое не раскрылось' 30); say "  ${l:0:200}"
[ -n "$l" ] && res 0 "M3 «не раскрылось» в журнале" || res 1 "M3 «не раскрылось» в журнале"
sleep 2; IFS=$'\t' read -r cnt3 _ <<< "$(last)"
[ "$cnt3" = "$cnt2" ] && res 0 "M3 реплики не прибавилось" || res 1 "M3 реплика прибавилась ($cnt2 → $cnt3)"
sh "pidof $PKG" | grep -q . && res 0 "M3 Falar жив" || res 1 "M3 Falar упал"

say "== M4 «Поделиться» → Falar"
$ADB push "$D/vm_pt.ogg" /sdcard/Music/falar_vm_share.ogg >/dev/null 2>&1
sh "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/falar_vm_share.ogg" >/dev/null; sleep 3
MEDIA_ID=$(sh "content query --uri content://media/external/audio/media --projection _id --where \"_display_name='falar_vm_share.ogg'\"" | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | head -1)
if [ -z "$MEDIA_ID" ]; then sk "M4 MediaStore не нашёл файл — «Поделиться» не проверить с оболочки"
else
  m=$(mark); launch -a android.intent.action.SEND -t audio/ogg --eu android.intent.extra.STREAM content://media/external/audio/media/$MEDIA_ID \
    --grant-read-uri-permission -n $PKG/dev.agenttranslator.ShareActivity
  l=$(wl "$m" '🎙 голосовое [0-9]|голосовое не скопировалось' 120); say "  ${l:-нет строки}"
  case "$l" in *"голосовое не скопировалось"*) sk "M4 право на URI оболочка не выдала — копия не прочиталась";;
               *"🎙 голосовое"*) res 0 "M4 «Поделиться» → распознано";;
               *) res 1 "M4 «Поделиться» → строки нет";; esac
fi
sleep 2
if sh "run-as $PKG ls cache" >/dev/null && [ -n "$(sh "run-as $PKG ls cache")" ]; then
  n2=$(sh "run-as $PKG ls cache/voice 2>/dev/null" | grep -c .)
  [ "$n2" = 0 ] && res 0 "M5 копий в cache/voice не осталось" || res 1 "M5 копий в cache/voice осталось: $n2"
else sk "M5 cache приложения оболочке не виден (сборка не debuggable)"; fi
[ "$fail" = 0 ]
