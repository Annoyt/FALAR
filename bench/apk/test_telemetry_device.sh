#!/bin/bash
# Телеметрия по согласию на телефоне — против локального приёмника вместо бота: пакет статистики, сбой,
# звук реплики, разовое окно согласия, «Статистика для разработчика» и «О приложении» в настройках.
# Приёмник — python на компьютере через adb reverse: записывает запросы (заголовки и тело) и отвечает как
# бот ({"ok":true,"n":N}). В бота и в группу разработчика ничего не уходит.
#
#   bash bench/apk/test_telemetry_device.sh      # на телефоне — сборка этого дерева (bench/apk/build.sh, adb install)
#
# Телефон — рабочий аппарат владельца: начинаем, когда он свободен (экран включён, впереди Falar или рабочий
# стол, минуту без касаний). Тестовый разговор со звуком кладётся файлом и убирается; согласие владельца
# возвращается тем, что было. TM4 нарочно роняет Falar (стенд --es telemetry crash) и поднимает снова.
R=$(cd "$(dirname "$0")/../.." && pwd)
if [ -z "$FALAR_STAND_LOCK" ]; then
  mkdir -p "${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand"; exec 9>"${XDG_CACHE_HOME:-$HOME/.cache}/falar-stand/lock"
  flock -n 9 || { echo "на телефоне уже идёт проверка — вторую не начинаю"; exit 1; }; export FALAR_STAND_LOCK=1
fi
ADB=${ADB:-$R/tools/platform-tools/adb}
A=$R/bench/apk; PKG=app.falar; ACT=$PKG/dev.agenttranslator.MainActivity
F=/sdcard/Android/data/$PKG/files; LOG=$F/at.log
D=$(mktemp -d /tmp/falar-tele.XXXX); mkdir -p $D/in; TID=$(date +%s%3N); PORT=${PORT:-8767}; BASE=http://127.0.0.1:$PORT
pass=0; fail=0
say() { printf '%s\n' "$*"; }
res() { if [ "$1" = 0 ]; then pass=$((pass+1)); say "PASS $2"; else fail=$((fail+1)); say "FAIL $2"; fi; }
chk() { if eval "$1"; then res 0 "$2"; else res 1 "$2"; fi; }
sh() { $ADB shell "$@" < /dev/null 2>/dev/null | tr -d '\r'; }
mark() { sh "wc -l < $LOG" | awk '{print $1+0}'; }
wl() { local i; for i in $(seq "$3"); do local l; l=$(sh "tail -n +$(($1+1)) $LOG | grep -E -m1 -- '$2'"); [ -n "$l" ] && { printf '%s\n' "$l"; return 0; }; sleep 1; done; return 1; }
focus() { sh "dumpsys window | grep -m1 mCurrentFocus"; }
idle() {
  sh "dumpsys power" | grep -qE "mWakefulness=(Asleep|Dozing)" && return 0
  case "$(focus)" in *$PKG*|*com.miui.home*|*launcher*) return 0;; esac; return 1
}
front() { sh "dumpsys power" | grep -q "mWakefulness=Awake" && focus | grep -q "$PKG/"; }
free_phone() {
  local n=0 f a
  while :; do
    f=$(focus); a=$(sh "dumpsys power" | sed -n 's/.*lastUserActivityTime=[0-9]* (\([0-9]*\) ms ago).*/\1/p' | head -1)
    if sh "dumpsys power" | grep -q "mWakefulness=Awake"; then
      case "$f" in *$PKG*|*com.miui.home*|*launcher*) [ "${a:-0}" -ge 60000 ] && return 0;; esac
    fi
    n=$((n+1)); [ $n -eq 1 ] && say "  жду: экран включён, впереди Falar или рабочий стол, минуту без касаний ($f)"; sleep 10
  done
}
launch() { local n=0; until idle; do n=$((n+1)); [ $n -eq 1 ] && say "  (запуск ждёт свободный телефон: $(focus))"; sleep 5; done; $ADB shell "am start -n $ACT $*" >/dev/null 2>&1; }
dump() { sh "uiautomator dump /sdcard/falar-ui.xml >/dev/null; cat /sdcard/falar-ui.xml; rm -f /sdcard/falar-ui.xml" > $D/ui.xml; }
ui() { local c=$1; shift; python3 $A/ui.py "$c" $D/ui.xml "$@"; }
at() { ui find "$@" | head -1; }
has() { ui find "$@" >/dev/null; }
xy() { at "$@" | awk '{print $5, $6}'; }
tapxy() { front || { say "  (впереди не Falar — касание пропущено: $(focus))"; return 1; }; $ADB shell "input tap $1 $2"; sleep "${3:-1.2}"; }
tapon() { local p; dump; p=$(xy "$@"); [ -n "$p" ] || { say "  (на экране нет: $*)"; return 1; }; tapxy $p; }
back() { front && $ADB shell "input keyevent 4"; sleep 1; }
WH=$(sh "wm size" | grep -oE '[0-9]+x[0-9]+' | tail -1); W=${WH%x*}; H=${WH#*x}
swipe() { front && $ADB shell "input swipe $((W / 2)) $1 $((W / 2)) $2 350"; sleep 1.2; }
seek() { local i; dump; has "$@" && return 0
  for i in 1 2 3 4 5 6; do swipe $((H * 7 / 10)) $((H * 3 / 10)); dump; has "$@" && return 0; done
  for i in 1 2 3 4 5 6 7 8 9 10; do swipe $((H * 3 / 10)) $((H * 7 / 10)); dump; has "$@" && return 0; done; return 1; }
# Окно «приложение остановлено» после нарочного сбоя — закрыть, если система его показала.
crashbox() { dump; local p; p=$(xy --rid android:id/aerr_close); [ -z "$p" ] && p=$(xy --rid android:id/aerr_restart); [ -n "$p" ] && $ADB shell "input tap $p"; sleep 1; }

# Приёмник вместо бота: путь, X-Falar, User-Agent и тело — в файлы <путь>-<номер>.json; ответ — номер записи.
cat > $D/recv.py <<'PY'
import http.server, json, os, sys
D, port = sys.argv[1], int(sys.argv[2]); n = [40]
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n[0] += 1
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        meta = {'path': self.path, 'falar': self.headers.get('X-Falar'), 'ua': self.headers.get('User-Agent'), 'n': n[0]}
        open(os.path.join(D, self.path.strip('/') + '-%d.json' % n[0]), 'wb').write(json.dumps(meta).encode() + b'\n' + body)
        out = json.dumps({'ok': True, 'n': n[0]}).encode()
        self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(out))); self.end_headers(); self.wfile.write(out)
    def log_message(self, *a): pass
http.server.HTTPServer(('127.0.0.1', port), H).serve_forever()
PY
python3 $D/recv.py $D/in $PORT & RECV=$!
got() { ls $D/in/$1-*.json 2>/dev/null | sort -t- -k2 -n | tail -1; }          # последний запрос на /<путь>
waitgot() { local i; for i in $(seq "$2"); do [ "$(ls $D/in/$1-*.json 2>/dev/null | wc -l)" -ge "$3" ] && return 0; sleep 1; done; return 1; }
js() { python3 -c "import json,sys; L=open(sys.argv[1],encoding='utf-8').read().split('\n',1); m=json.loads(L[0]); b=json.loads(L[1]); print($2)" "$1"; }

ORIG=""
restore() {
  say "== возврат"
  $ADB shell "am force-stop $PKG"; sleep 1
  $ADB shell "rm -f $F/chats/$TID.json $F/audio/${TID}_*.wav /sdcard/falar-ui.xml"; say "  убран тестовый разговор $TID и его звук"
  kill $RECV 2>/dev/null; $ADB reverse --remove tcp:$PORT >/dev/null 2>&1
  if [ -n "$ORIG" ]; then
    local n=0; until idle; do n=$((n+1)); [ $n -eq 1 ] && say "  телефон занят ($(focus)) — согласие верну, когда освободится"; sleep 15; done
    launch --es telemetrybase off --es telemetry "${ORIG:-ask}"; sleep 3
    say "  согласие на статистику: снова «${ORIG:-не спрашивали}»"
  fi
  say "  текущим снова станет: $(sh "ls -t $F/chats/ | head -1")"
  rm -rf "$D"
  say; say "итог: PASS $pass, FAIL $fail"
}
trap restore EXIT

$ADB wait-for-device
free_phone
$ADB reverse tcp:$PORT tcp:$PORT >/dev/null || { say "adb reverse не вышел"; exit 2; }
CODE=$(sh "dumpsys package $PKG" | grep -m1 -oE 'versionCode=[0-9]+' | cut -d= -f2)
VER=$(sh "dumpsys package $PKG" | grep -m1 -oE 'versionName=[^ ]+' | cut -d= -f2)
MODEL=$(sh "getprop ro.product.model"); SDK=$(sh "getprop ro.build.version.sdk")
m=$(mark); launch --es telemetry show; l=$(wl "$m" '📊 стенд: согласие' 15)
[ -n "$l" ] || { say "на телефоне сборка без телеметрии (нет ответа на --es telemetry show) — поставьте сборку этого дерева"; exit 2; }
ORIG=$(printf '%s' "$l" | sed -n 's/.*согласие «\([^»]*\)».*/\1/p'); [ -z "$ORIG" ] && ORIG=ask
say "== телефон: $MODEL, Android sdk $SDK, Falar $VER ($CODE); согласие владельца — «$ORIG»"

say "== TM1–TM3: пакет статистики"
m=$(mark); launch --es telemetry yes; chk '[ -n "$(wl "$m" "📊 статистика разработчику: отправляю раз в сутки" 10)" ]' "TM1 согласие «да» (стенд)"
m=$(mark); launch --es telemetrybase $BASE --es telemetry now
l=$(wl "$m" '📊 статистик(а ушла|у отправить не вышло)' 30); waitgot t 10 1; P=$(got t)
chk '[ -n "$P" ] && printf "%s" "$l" | grep -q "ушла разработчику"' "TM2 пакет ушёл на приёмник (${l##*📊 })"
if [ -n "$P" ]; then
  chk '[ "$(js $P "m[\"falar\"], m[\"ua\"]")" = "1 Falar/$VER" ]' "TM2 заголовки: X-Falar 1, User-Agent Falar/$VER"
  chk '[ "$(js $P "b[\"v\"], b[\"app\"][\"code\"], b[\"app\"][\"name\"]")" = "1 $CODE $VER" ]' "TM3 приложение: версия $VER ($CODE)"
  chk '[ "$(js $P "b[\"device\"][\"model\"], b[\"device\"][\"sdk\"]")" = "$MODEL $SDK" ]' "TM3 телефон: $MODEL, sdk $SDK"
  chk '[ "$(js $P "\",\".join(sorted(b[\"device\"][\"fx\"])), b[\"app\"][\"ui\"] in (\"ru\", \"pt\"), len(b[\"asr\"][\"noise\"]), \"turns\" in b[\"asr\"]")" = "aec,agc,ns True 5 True" ]' "TM3 обработка звука, язык надписей, ступени фона, фразы"
  chk '! grep -qiE "\"(src|dst|text|audio|serial|imei|android_id)\"" <(sed 1d $P)' "TM3 в пакете нет текстов, звука и номеров устройства"
fi

say "== TM4: сбой — файлом, уходит со следующим пакетом"
T1=$(sh "date '+%m-%d %H:%M:%S.000'")
launch --es telemetry crash; sleep 4; crashbox
chk 'sh "logcat -d -v time -t \"$T1\"" | grep -A2 "FATAL EXCEPTION" | grep -q "проверка сбоя"' "TM4 нарочный сбой уронил Falar (logcat)"
n0=$(ls $D/in/t-*.json 2>/dev/null | wc -l)
m=$(mark); launch --es telemetrybase $BASE --es telemetry now; wl "$m" '📊 статистик(а ушла|у отправить не вышло)' 30 >/dev/null; waitgot t 10 $((n0 + 1)); P=$(got t)
T0=$(sh "date '+%m-%d %H:%M:%S.000'")                 # дальше Falar падать не должен (TM9)
chk '[ -n "$P" ] && [ "$(js $P "([c[\"what\"] for c in b.get(\"crashes\", []) if c.get(\"kind\") == \"crash\"] or [\"нет\"])[-1]")" = java.lang.IllegalStateException ]' "TM4 сбой в пакете: тип исключения"
chk '[ -n "$P" ] && js $P "[c[\"where\"] for c in b.get(\"crashes\", [])][-1]" | grep -q "TranslatorService" && ! grep -q "проверка сбоя" $P' "TM4 место — кадры приложения, текста ошибки нет"

say "== TM5–TM6: разовое окно согласия и «О приложении»"
m=$(mark); launch --es telemetry ask; wl "$m" '📊 статистика разработчику: спрошу' 10 >/dev/null
$ADB shell "am force-stop $PKG"; sleep 1; launch; sleep 4; dump
chk 'has --text "Помочь сделать Falar лучше?" || has --text "Ajudar a melhorar o Falar?"' "TM5 после запуска — окно согласия"
chk 'has --text "не отправлять" || has --text "não enviar"' "TM5 кнопки «отправлять / не отправлять / подробнее»"
tapon --text "подробнее" || tapon --text "saiba mais"; sleep 1; dump
chk 'has --text "Отказ от ответственности" --contains || has --text "Isenção de responsabilidade" --contains' "TM6 «подробнее» — «О приложении» с отказом от ответственности"
back
$ADB shell "am force-stop $PKG"; sleep 1; launch; sleep 4; dump
chk 'has --text "Помочь сделать Falar лучше?" || has --text "Ajudar a melhorar o Falar?"' "TM5 закрыли без ответа — спрашивает снова"
m=$(mark); tapon --text "не отправлять" || tapon --text "não enviar"
chk '[ -n "$(wl "$m" "📊 статистика разработчику: не отправляю" 10)" ]' "TM5 «не отправлять» — запомнено"
$ADB shell "am force-stop $PKG"; sleep 1; launch; sleep 4; dump
chk '! has --text "Помочь сделать Falar лучше?" && ! has --text "Ajudar a melhorar o Falar?"' "TM5 ответили — больше не спрашивает"

say "== TM7: настройки — переключатель и «О приложении»"
tapon --desc "Разговоры, слова, настройки"; tapon --text Настройки || tapon --text Configurações; sleep 1.5
seek --text "Статистика для разработчика" || seek --text "Estatísticas para o desenvolvedor"
chk 'has --text "Статистика для разработчика" || has --text "Estatísticas para o desenvolvedor"' "TM7 в настройках — «Статистика для разработчика»"
p=$(python3 - "$D/ui.xml" <<'PY'
import re, sys
x = open(sys.argv[1], encoding='utf-8').read()
nodes = re.findall(r'<node [^>]*>', x)
def b(n): return [int(v) for v in re.findall(r'\d+', re.search(r'bounds="([^"]*)"', n).group(1))]
lab = [b(n) for n in nodes if re.search(r'text="(Статистика для разработчика|Estatísticas para o desenvolvedor)"', n)]
sws = [(b(n), 'checked="true"' in n) for n in nodes if 'class="android.widget.Switch"' in n]
if lab:
    y = (lab[0][1] + lab[0][3]) // 2
    best = min(sws, key=lambda s: abs((s[0][1] + s[0][3]) // 2 - y)) if sws else None
    if best: print((best[0][0] + best[0][2]) // 2, (best[0][1] + best[0][3]) // 2, best[1])
PY
)
chk '[ "${p##* }" = False ]' "TM7 после «не отправлять» переключатель выключен"
m=$(mark); [ -n "$p" ] && tapxy ${p% *}
chk '[ -n "$(wl "$m" "📊 статистика разработчику: отправляю раз в сутки" 10)" ]' "TM7 переключатель — согласие «да»"
seek --text "О приложении" || seek --text "Sobre o app"; tapon --text "О приложении" || tapon --text "Sobre o app"; sleep 1; dump
chk '(has --text "Что уходит в интернет" --contains && has --text "Отказ от ответственности" --contains) || has --text "Isenção de responsabilidade" --contains' "TM7 «О приложении»: что уходит в интернет, отказ от ответственности"
back

say "== TM8: звук реплики разработчику"
python3 - "$D/t.json" "$D/a.wav" "$TID" <<'PY'
import json, math, struct, sys, wave
path, wav, tid = sys.argv[1], sys.argv[2], int(sys.argv[3])
w = wave.open(wav, 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
w.writeframes(b''.join(struct.pack('<h', int(3000 * math.sin(2 * math.pi * 440 * i / 16000))) for i in range(16000 * 2))); w.close()
at = tid + 1000
turn = {"dir": "pt2ru", "src": "Bom dia, tudo bem?", "dst": "Доброе утро, как дела?", "at": at, "audio": "%d_%d.wav" % (tid, at)}
json.dump({"id": tid, "name": "ТЕСТ телеметрия", "named": True, "saved": tid, "turns": [turn]}, open(path, "w", encoding="utf-8"), ensure_ascii=False)
PY
WAVSZ=$(stat -c %s $D/a.wav)
$ADB shell "am force-stop $PKG"; sleep 1
$ADB push $D/t.json $F/chats/$TID.json >/dev/null 2>&1; $ADB shell "mkdir -p $F/audio"; $ADB push $D/a.wav $F/audio/${TID}_$((TID + 1000)).wav >/dev/null 2>&1
launch --es telemetrybase $BASE; sleep 4
tapon --desc "Меню реплики"; sleep 1; dump
chk 'has --text "Отправить звук разработчику" || has --text "Enviar áudio ao desenvolvedor"' "TM8 в меню реплики со звуком — «Отправить звук разработчику»"
tapon --text "Отправить звук разработчику" || tapon --text "Enviar áudio ao desenvolvedor"; sleep 1; dump
chk '[ "$(at --rid android:id/button1 | awk "{print \$7}")" = false ]' "TM8 «отправить» недоступна без отметки согласия"
tapon --class android.widget.CheckBox; dump
chk '[ "$(at --rid android:id/button1 | awk "{print \$7}")" = true ]' "TM8 отметили согласие — «отправить» доступна"
n0=$(ls $D/in/audio-*.json 2>/dev/null | wc -l)
tapon --rid android:id/button1; waitgot audio 20 $((n0 + 1)); P=$(got audio); sleep 2; dump
chk '[ -n "$P" ] && [ "$(js $P "b[\"consent\"], b[\"lang\"], b[\"mime\"], b[\"heard\"]")" = "True pt audio/wav Bom dia, tudo bem?" ]' "TM8 на приёмник: согласие, язык, формат, распознанный текст"
chk '[ -n "$P" ] && [ "$(js $P "len(__import__(\"base64\").b64decode(b[\"audio\"])), 1500 <= b[\"dur_ms\"] <= 2500")" = "$WAVSZ True" ]' "TM8 звук целиком ($WAVSZ байт), длина ~2 с"
N=$([ -n "$P" ] && js $P 'm["n"]')
chk 'has --text "Звук отправлен" || has --text "Áudio enviado"' "TM8 человеку: «Звук отправлен»"
chk 'has --text "$N" --contains' "TM8 и номер записи ($N) — чтобы попросить удалить"
tapon --text Понятно || tapon --text Entendi

chk '[ -z "$(sh "logcat -d -v time -t \"$T0\"" | grep -A3 "FATAL EXCEPTION" | grep -A1 "Process: $PKG" | grep -v "проверка сбоя" | grep -vE "Process:|^--" | head -1)" ]' "TM9 после нарочного сбоя Falar больше не падал"
