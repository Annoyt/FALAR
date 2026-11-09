#!/bin/bash
# Прогон заданий стенда Gemini Nano на телефоне (POCO X7 Pro; Redmi в списке устройств Nano нет).
#
#   bash bench/nano/run.sh status|download|warmup
#   bash bench/nano/run.sh run JOB.tsv OUT.tsv [ключи am start, например --ei gap 500]
#
# Телефон указывается явно (NANO_SERIAL, по умолчанию POCO): при двух подключённых телефонах голый adb падает.
# Вывод Nano идёт только при приложении на экране и разблокированном телефоне — это телефон владельца,
# поэтому запуск только с его согласия (NANO_OK=1) и только если экран включён и не заблокирован.
set -e
R=$(cd "$(dirname "$0")/../.." && pwd)
S=${NANO_SERIAL:-FMTO9DMNQGZH55GY}
A="$R/tools/platform-tools/adb -s $S"
PKG=app.falar.nanoprobe; EXT=/sdcard/Android/data/$PKG/files
say() { echo "$(date +%T) $*"; }
sh() { $A shell "$@" < /dev/null | tr -d '\r'; }

[ "$NANO_OK" = 1 ] || { say "нужно согласие владельца на занятый экран: NANO_OK=1"; exit 2; }
sh dumpsys power | grep -q 'mWakefulness=Awake' || { say "экран погашен — Nano не ответит"; exit 3; }
if sh dumpsys window | grep -q -E 'mDreamingLockscreen=true|isKeyguardShowing=true|mKeyguardShowing=true'; then
  say "телефон заблокирован — разблокирует только владелец"; exit 3
fi
sh pm path $PKG | grep -q package: || { say "стенд не установлен: adb install bench/nano/NanoProbe.apk"; exit 4; }

start() { sh am start -n $PKG/.Probe "$@" >/dev/null; }
# Каталог files создаёт само приложение: созданный через adb каталог ему недоступен (Android 16)
sh "[ -d $EXT ]" 2>/dev/null || true
if ! sh ls $EXT >/dev/null 2>&1; then start; sleep 3; fi

cmd=$1; shift
case "$cmd" in
  status|download|warmup)
    sh rm -f $EXT/$cmd.txt $EXT/$cmd.err
    start --es cmd $cmd "$@"
    for i in $(seq 120); do
      sleep 2
      if sh cat $EXT/$cmd.err 2>/dev/null | grep -q .; then sh cat $EXT/$cmd.err | head -20; exit 5; fi
      t=$(sh cat $EXT/$cmd.txt 2>/dev/null || true)
      [ -n "$t" ] && { echo "$t"; [ "$cmd" != download ] && exit 0; echo "$t" | grep -q -E 'completed|failed' && exit 0; }
    done
    say "нет ответа за 4 мин"; exit 6;;
  run)
    job=$1; out=$2; shift 2
    jn=$(basename "$job"); on=$(basename "$out")
    $A push "$job" $EXT/$jn >/dev/null
    [ -f "$out" ] && $A push "$out" $EXT/$on >/dev/null || sh rm -f $EXT/$on
    sh rm -f $EXT/$on.done $EXT/run.err
    start --es cmd run --es job $jn --es out $on "$@"
    n=$(wc -l < "$job"); last=""
    while :; do
      sleep 15
      if sh cat $EXT/run.err 2>/dev/null | grep -q .; then sh cat $EXT/run.err | head -20; exit 5; fi
      d=$(sh cat $EXT/$on.done 2>/dev/null || true)
      got=$(sh "wc -l < $EXT/$on" 2>/dev/null || echo 0)
      [ "$got" != "$last" ] && { say "$jn: $got из $n"; last=$got; }
      [ -n "$d" ] && { say "готово: $d"; break; }
      f=$(sh dumpsys window | grep -m1 mCurrentFocus)
      echo "$f" | grep -q $PKG || say "на экране не стенд: ${f##* } — вывод стоит, пока стенд не вернётся"
    done
    $A pull $EXT/$on "$out" >/dev/null && say "результат: $out";;
  *) echo "не знаю команду: $cmd"; exit 2;;
esac
