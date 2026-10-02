#!/bin/bash
# Чувствительность микрофона на записях комнаты: WER и нарезка при разных усилениях, прежний срез
# против ограничителя. Нужен один телефон; разговоры владельца не трогаются.
#
#   bash bench/air/gain_sweep.sh [--rec "near-pt near-ru"] [--gains "0 12 24 auto"] [--modes "cut limit"]
#                                [--att <дБ>] [--autostart <дБ>] [--speed 4] [--vaddn "raw dn dn_sil"]
#                                [--seg "preroll=300 tail=300 gate=6 minspeech=250"]
#
# --seg — любые ключи стенда на все прогоны этого запуска: «ключ=значение» уходит в am start как
# --es ключ значение (нарезка — preroll/tail/gate/minspeech/hang, порог silero — vadthr=0.4 и т. п.;
# приложение держит их только до перезапуска, поэтому сами сбрасываются). Ради настроек нарезки был
# replay_air.sh — он устарел: писал в разговор владельца и не выключал отпечаток голоса.
#
# Каждый прогон: приложение перезапускается с --es micgaintest <дБ> --es limiter <0|1> (в настройки
# не пишется; auto — вместо числа: чувствительность подбирается сама, --es micauto 1), запись комнаты
# подаётся вместо микрофона (feedwav) тем же путём, что живой звук, —
# через чувствительность, VAD и распознавание. --att ослабляет запись перед подачей (тихий
# собеседник при том же отношении речи к фону). --autostart — с какого усиления начинает авто
# (запомненное с прошлой сессии); по умолчанию 0, чтобы прогоны не зависели от предыдущих. --vaddn — нарезка по
# очищенному звуку (--es vaddenoise, results/2026-10-02-vad-denoise.md): raw — как всегда, dn, dn_sil; во что
# обошёлся шумодав — колонка «шумодав_%ядра». Реплики ложатся в отдельный тестовый разговор;
# уточнитель и облако на время выключены — иначе семьдесят реплик подряд будили бы уточнитель и
# отправляли тестовый разговор в облако. В конце всё как было: частота разбора и облака, выученное
# и словари из снимка, тестовый разговор и запись удалены.
# Всё тело — в фигурных скобках: bash прочтёт его целиком до запуска, и правка файла посреди прогона
# не подсунет ему середину строки.
{
set -u
R=$(cd "$(dirname "$0")/../.." && pwd)
ADB=${ADB:-$R/tools/platform-tools/adb}
PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log; TSV=$F/at.tsv
RECS="near-pt"; GAINS="0 12 24"; MODES="cut limit"; ATT=0; SPEED=4; AUTOSTART=0; VADDN=raw; SEG=""
while [ $# -gt 0 ]; do
  case "$1" in --rec) RECS=$2; shift 2;; --gains) GAINS=$2; shift 2;; --modes) MODES=$2; shift 2;;
    --att) ATT=$2; shift 2;; --speed) SPEED=$2; shift 2;; --autostart) AUTOSTART=$2; shift 2;; --vaddn) VADDN=$2; shift 2;; --seg) SEG=$2; shift 2;;
    *) echo "не знаю ключ: $1"; exit 2;; esac
done
D=$(mktemp -d /tmp/falar-gain.XXXX); SNAP=$D/snap; mkdir -p $SNAP; TID=$(date +%s%3N); SUM=$D/summary.tsv
say() { printf '%s\n' "$*"; }
sh() { $ADB shell "$@" 2>/dev/null | tr -d '\r'; }
# Свободен: погашен экран (прогону экран не нужен, всё идёт в сервисе) — или впереди Falar либо лаунчер,
# и экрана не касались минуту, как idle() в test_all_device.sh: телефон рабочий, его могут держать в руках,
# а прогон перезапускает Falar.
free_phone() {
  local n=0 f a
  while :; do
    sh "dumpsys power" | grep -qE "mWakefulness=(Asleep|Dozing)" && return 0
    f=$(sh "dumpsys window | grep -m1 mCurrentFocus")
    case "$f" in *$PKG*|*com.miui.home*|*launcher*|*mCurrentFocus=null*)
      a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
      [ "${a:-0}" -ge 60000 ] && return 0;;
    esac
    n=$((n+1)); [ $n -eq 1 ] && say "  телефон занят ($f), жду…"; sleep 10
  done
}
count() { sh "test -f $1 && wc -l < $1 || echo 0" | awk '{print $1+0}'; }
seen() { sh "tail -n +$(($1+1)) $LOG | grep -c -E -- '$2'" | awk '{print $1+0}'; }
# Частота разбора и облака — какими были: последняя строка о них в журнале приложения.
every() { sh "grep -E '$1: (каждые|только)' $LOG | tail -1" | sed -n 's/.*каждые \([0-9]*\).*/\1/p;s/.*только по кнопке.*/0/p'; }
REF=$(every 'разбор контекста'); REF=${REF:-3}; CLOUD=$(every 'пересмотр разговора в облаке'); CLOUD=${CLOUD:-0}
# Запомненная авто-чувствительность владельца — тоже из журнала; прогоны её переписывают.
AUTO0=$(sh "grep -E '🎚 (чувствительность: авто, сейчас|авто-чувствительность:)' $LOG | tail -1" | sed -n 's/.* \([+-][0-9.]*\) дБ.*/\1/p'); AUTO0=${AUTO0:-0}
restore() {
  say "== возврат: разбор каждые $REF, облако каждые $CLOUD, авто-чувствительность $AUTO0 дБ"
  $ADB shell "am force-stop $PKG"; sleep 2      # подача могла не кончиться — после сброса она подстроила бы авто снова
  $ADB shell "am start -n $ACT --es vad 0 --es feedonly 0 --es silent 0 --es refineevery $REF --es cloudevery $CLOUD --es micautodb $AUTO0 $([ -n "${NOSPK:-}" ] && echo "--es modules '$MODS0'")" >/dev/null 2>&1; sleep 3
  if [ -n "${NOSPK:-}" ]; then local mm; mm=$(count $LOG); $ADB shell "am start -n $ACT --es modules show" >/dev/null 2>&1; sleep 3
    local now; now=$(sh "tail -n +$((mm+1)) $LOG | grep -m1 '🧩 модули сейчас'" | sed -n 's/.*модули сейчас: \[\([a-z,]*\)\].*/\1/p')
    [ "$now" = "$MODS0" ] && say "  модуль «отпечаток голоса» включён обратно: модули [$now], как до замера" \
      || say "  ВНИМАНИЕ: модули после возврата [$now], а до замера были [$MODS0] — вернуть: am start -n $ACT --es modules '$MODS0'"; fi
  $ADB shell "am force-stop $PKG"; sleep 2
  for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do
    [ -f "$SNAP/$(basename $f)" ] && $ADB push "$SNAP/$(basename $f)" "$F/$f" >/dev/null 2>&1; done
  $ADB shell "rm -f $F/chats/$TID.json $F/replay.wav"
  # Больше 500 реплик — разговор продолжается в новом файле «ТЕСТ чувствительность · продолжение» с новым id
  # (Chats.KEEP). 03.10 так остались два продолжения, и одно стало бы у владельца текущим: девять прогонов по
  # ~80 фраз. Убираем всё, что появилось за прогон с этим именем; разговоры владельца имени не меняют.
  for c in $(sh "ls $F/chats/" | tr -d '\r'); do
    case " $CH0 " in *" $c "*) continue;; esac
    sh "head -c 300 $F/chats/$c" | grep -q '"name": *"ТЕСТ чувствительность' && { $ADB shell "rm -f $F/chats/$c"; say "  убрано продолжение тестового разговора: $c"; }
  done
  say "  тестовый разговор удалён · текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
  free_phone; $ADB shell "am start -n $ACT" >/dev/null 2>&1
  say; say "итог:"; column -t -s$'\t' $SUM 2>/dev/null; say "прогоны: $D"
}
trap restore EXIT

$ADB wait-for-device; free_phone
for f in models/learned.json models/phrasebook_user.json word_ru.json known_words.json; do $ADB pull "$F/$f" "$SNAP/" >/dev/null 2>&1; done
CH0=$(sh "ls $F/chats/" | tr -d '\r' | tr '\n' ' ')            # разговоры до прогона: их возврат не трогает
printf '{"id": %s, "name": "ТЕСТ чувствительность", "named": true, "saved": %s, "turns": []}\n' $TID $TID > $D/t.json
$ADB shell "am force-stop $PKG"; sleep 1; $ADB push $D/t.json "$F/chats/$TID.json" >/dev/null 2>&1
# Голоса разговора (ветка voices): слушание переводит только голоса, записанные кнопкой FALAR, а в
# тестовом разговоре их нет — замер не услышал бы ничего (02.10: все 73 куска «в разговоре ещё нет
# голосов»). Модуль отпечатка голоса на время прогона выключен, в конце — как был.
MODS0=; NOSPK=; m0=$(count $LOG); $ADB shell "am start -n $ACT --es modules show" >/dev/null 2>&1
for _ in $(seq 30); do sleep 1; ml=$(sh "tail -n +$((m0+1)) $LOG | grep -m1 '🧩 модули сейчас'"); [ -n "$ml" ] && break; done
MODS0=$(printf '%s' "$ml" | sed -n 's/.*модули сейчас: \[\([a-z,]*\)\].*/\1/p')
if printf ',%s,' "$MODS0" | grep -q ',speaker,'; then
  NOSPK=$(printf '%s' "$MODS0" | tr ',' '\n' | grep -vx speaker | paste -sd, -)
  $ADB shell "am start -n $ACT --es modules '$NOSPK'" >/dev/null 2>&1; sleep 3
  say "  модуль «отпечаток голоса» на время замера выключен (модули были: [$MODS0])"
fi
printf 'запись\tусиление\tрежим\tнарезка\tWER\tчисто\tWER_чистых\tпотеряно\tкусков\tречь_дБ\tфон_дБ\tперегруз_%%\tограничитель_%%\tсрез_%%\tшумодав_%%ядра\n' > $SUM

for rec in $RECS; do
  REC=$R/bench/air/rec/$rec; LANG_=${rec##*-}; DIR=$([ "$LANG_" = ru ] && echo ru2pt || echo pt2ru)
  python3 - "$REC/room.wav" "$D/feed.wav" "$ATT" <<'PY'
import sys, wave, array
src, dst, att = sys.argv[1], sys.argv[2], float(sys.argv[3])
w = wave.open(src); p = w.getparams(); raw = w.readframes(w.getnframes()); w.close()
if att == 0: open(dst, "wb").write(open(src, "rb").read()); sys.exit()
if p.sampwidth != 2: sys.exit("ослабление умею только для 16-битных записей")
a = array.array("h", raw); k = 10 ** (-att / 20)
a = array.array("h", (int(round(v * k)) for v in a))
o = wave.open(dst, "wb"); o.setparams(p); o.writeframes(a.tobytes()); o.close()
PY
  $ADB push "$D/feed.wav" "$F/replay.wav" >/dev/null
  SEC=$(python3 -c "import wave; w=wave.open('$D/feed.wav'); print(int(w.getnframes()/w.getframerate()))")
  SEGARGS=""; for kv in $SEG; do SEGARGS="$SEGARGS --es ${kv%%=*} ${kv#*=}"; done
  [ -n "$SEG" ] && say "  нарезка на все прогоны: $SEG"
  for g in $GAINS; do for m in $MODES; do for v in $VADDN; do
    [ "$g" = 0 ] && [ "$m" != "$(echo $MODES | awk '{print $NF}')" ] && continue   # при 0 дБ режимы совпадают
    GX=$([ "$g" = auto ] && echo "--es micauto 1 --es micautodb $AUTOSTART" || echo "--es micgaintest $g")
    L=$([ "$m" = cut ] && echo 0 || echo 1); OUT=$D/$rec-a$ATT-g$g-$m-$v; mkdir -p $OUT
    say "== $rec · ослабление $ATT дБ · усиление +$g дБ$([ "$g" = auto ] && echo " с $AUTOSTART") · $m · нарезка $v"
    ok=0; rm -f $OUT/invalid.txt
    for try in 1 2; do
      free_phone
      $ADB shell "am force-stop $PKG"; sleep 2
      m0=$(count $LOG)
      # feedonly: микрофон в нарезку не идёт и вне подачи — до неё и после «подача закончена» нарезка
      # слушала бы комнату (сборки до 02.10 ключ не знают и слушают — ждать в тишине).
      $ADB shell "am start -n $ACT --es vad 1 --es feedonly 1 --es silent 1 --es fixdir $DIR --es denoise 0 --es vaddenoise $v --es refineevery 0 --es cloudevery 0 $GX --es limiter $L $SEGARGS" >/dev/null 2>&1
      # Готов, когда движки подняты («🧩 модули:» пишется сразу после них) и захват идёт: подача
      # раньше движков теряла бы начало записи — поток нарезки выбрасывает кадры, пока движка нет.
      ok=0; for _ in $(seq 60); do sleep 2; [ "$(seen $m0 'микрофон:')" != 0 ] && [ "$(seen $m0 '🧩 модули:')" != 0 ] && { ok=1; break; }; done
      [ $ok = 1 ] || { say "  слушающий не поднялся — прогон пропущен"; break; }
      sleep 3; n0=$(count $TSV); m1=$(count $LOG)
      off=$(( $(sh "date +%s%3N") - $(date +%s%3N) ))
      $ADB shell "am start -n $ACT --es feedwav $F/replay.wav --es speed $SPEED" >/dev/null 2>&1
      # Стенд сброшен посреди прогона («↺ … сброшен» в журнале): сборки до 02.10 при погашенном экране
      # поднимали сервис с fromUi и возвращали озвучку и направление по кнопкам — near-ru слушался как
      # португальский, far-pt и noisy-pt шли вслух. Такой прогон не в счёт; Falar сразу останавливается,
      # чтобы оборвать озвучку. Проверка — каждые 2 с подачи и на каждом шаге ожидания распознавания.
      reset() { [ "$(seen $m0 'стендовый молчаливый режим сброшен')" != 0 ]; }
      bad=; for _ in $(seq $(( (SEC / SPEED + 240) / 2 ))); do sleep 2; reset && { bad=1; break; }; [ "$(seen $m1 'подача закончена')" != 0 ] && break; done
      # Распознавание догоняет подачу: ждём, пока журнал 20 с не растёт.
      last=-1; [ -z "$bad" ] && for _ in $(seq 60); do reset && { bad=1; break; }; c=$(count $TSV); [ "$c" = "$last" ] && break; last=$c; sleep 20; done
      if [ -n "$bad" ] || reset; then
        $ADB shell "am force-stop $PKG"
        # Чаще всего это человек: открыл Falar посреди прогона, и приложение, как и должно, сбросило
        # стендовые режимы. Один повтор — когда телефон снова свободен; второй сброс — прогон не в счёт.
        if [ $try = 1 ]; then say "  стенд сброшен посреди прогона (телефон взяли в руки?) — Falar остановлен, повтор, когда телефон освободится"; continue; fi
        say "  НЕДЕЙСТВИТЕЛЕН: приложение дважды сбросило стендовые режимы посреди прогона — Falar остановлен, прогон не в счёт"
        echo "стендовые режимы сброшены посреди прогона" > $OUT/invalid.txt
        printf '%s\t+%s\t%s\t%s\tнедействителен\n' "$rec" "$g" "$m" "$v" >> $SUM
      fi
      break
    done
    [ $ok = 1 ] && [ ! -f $OUT/invalid.txt ] || continue
    sh "tail -n +$((n0+1)) $TSV" > $OUT/listener.tsv; echo $off > $OUT/listener.offset; echo "g=$g $m att=$ATT vaddn=$v" > $OUT/seg.txt
    sh "tail -n +$((m1+1)) $LOG | grep '🔇 шумодав нарезки' | tail -1" > $OUT/dncost.txt
    python3 $R/bench/air/air_wer.py $OUT --lang $LANG_ --mode replay --rec $REC > $OUT/wer.txt 2>&1
    python3 - $OUT "$rec" "$g" "$m" "$v" >> $SUM <<'PY'
import re, sys, statistics as st
out, rec, g, m, v = sys.argv[1:6]
t = open(out + "/wer.txt", encoding="utf-8").read()
def f(rx):
    x = re.search(rx, t, re.M); return x.group(1) if x else "?"
seg = [l.rstrip("\n").split("\t") for l in open(out + "/listener.tsv", encoding="utf-8")]
seg = [p for p in seg if len(p) >= 20 and p[1] in ("asr", "silence", "skip_short", "skip_lang", "skip_self")]
num = lambda i: [float(p[i]) for p in seg if p[i] not in ("", "NaN")]
med = lambda xs: "%.1f" % st.median(xs) if xs else "?"
avg = lambda xs: "%.3f" % (sum(xs) / len(xs)) if xs else "?"
cost = re.search(r"([\d.]+) % ядра по процессору", open(out + "/dncost.txt", encoding="utf-8").read())
print("\t".join([rec, "+" + g, m, v, f(r"^WER ([\d.]+)%"), f(r"чисто \d+ \(([\d.]+)%\)"), f(r"на чисто нарезанных: WER ([\d.]+)%"),
                 f(r"не дошло (\d+) \("), str(sum(1 for p in seg if p[1] == "asr")),
                 med(num(15)), med(num(16)), avg(num(17)), avg(num(18)), avg(num(19)), cost.group(1) if cost else "-"]))
PY
    tail -1 $SUM | column -t -s$'\t'
    # Микрофон — выключить сразу: следующий прогон может ждать свободного телефона, и всё это время
    # слушание писало бы комнату (02.10 так в журнал попали обрывки домашних разговоров).
    $ADB shell "am force-stop $PKG"
  done; done; done
done
exit
}
