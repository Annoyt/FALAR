#!/bin/bash
# Выпуск публичного релиза: две сборки релизным ключом, контрольные суммы, тег и релиз на GitHub
# с постоянными именами файлов (на них ссылается кнопка «Скачать APK» на странице и в README).
#
#   bash tools/release.sh            # спросит пароль ключа, соберёт, проверит подпись, выпустит
#   DRAFT=1 bash tools/release.sh    # черновик: посмотреть и опубликовать кнопкой на GitHub
#
# Ключ по умолчанию ~/falar-release.keystore, другой — через FALAR_KEYSTORE. Пароль можно передать
# в FALAR_KS_PASS, но лучше дать скрипту спросить: в команде он попадёт в историю оболочки и будет
# виден в списке процессов. Нигде не печатается. Версия берётся из манифеста
# приложения: менять её надо там, а заодно поднимать `app` в models/manifest.json (build.sh это
# проверяет). Имена файлов Falar.apk и Falar-slim.apk постоянные — ссылка
# releases/latest/download/Falar.apk не должна ломаться от версии к версии.
set -e
R=$(cd "$(dirname "$0")/.." && pwd); A=$R/bench/apk
REPO=${REPO:-Annoyt/FALAR}

[ -n "$FALAR_KEYSTORE" ] || FALAR_KEYSTORE=~/falar-release.keystore
[ -f "$FALAR_KEYSTORE" ] || { echo "нет файла ключа: $FALAR_KEYSTORE"; exit 1; }
command -v gh >/dev/null || { echo "нужен gh"; exit 1; }
# Авторизацию gh проверяем до сборки. Дважды выпуск падал уже после трёх минут работы: в
# интерактивной оболочке gh не авторизован и отвечает 401, а git при этом берёт протухшую
# запись из глобального `store` и говорит «Invalid username or token» — по этому тексту
# настоящую причину не угадать.
# Именно запросом к API, а не `gh auth status`: тот отвечает «всё хорошо» по наличию записи в
# настройках, а недействительный токен всплывает только на настоящем обращении — и выпуск снова
# падает после сборки.
gh api user >/dev/null 2>&1 || { echo "токен GitHub недействителен в этой оболочке: выполните 'gh auth login' и повторите"; exit 1; }
# Пароль спрашиваем здесь и держим только в окружении этого запуска: в команде он попал бы
# в историю оболочки и в список процессов, а на бумажке его забывают. Ввод не отображается.
if [ -z "$FALAR_KS_PASS" ]; then
  printf 'пароль ключа %s: ' "$(basename "$FALAR_KEYSTORE")" >&2
  read -rs FALAR_KS_PASS; echo >&2
  [ -n "$FALAR_KS_PASS" ] || { echo "пароль пустой"; exit 1; }
fi
export FALAR_KEYSTORE FALAR_KS_PASS
# Проверяем пароль до сборки: собирать три минуты, чтобы упасть на подписи, незачем.
keytool -list -keystore "$FALAR_KEYSTORE" -alias "${FALAR_KEY_ALIAS:-falar}" -storepass:env FALAR_KS_PASS >/dev/null 2>&1 \
  || { echo "пароль не подошёл или нет ключа ${FALAR_KEY_ALIAS:-falar} в хранилище"; exit 1; }

VER=$(grep -o 'versionName="[^"]*"' $A/AndroidManifest.xml | cut -d'"' -f2)
TAG=v$VER
git -C $R diff --quiet && git -C $R diff --cached --quiet || { echo "есть незакоммиченные изменения — релиз должен быть воспроизводим"; exit 1; }
git -C $R rev-parse "$TAG" >/dev/null 2>&1 && { echo "тег $TAG уже есть"; exit 1; }
gh release view "$TAG" -R $REPO >/dev/null 2>&1 && { echo "релиз $TAG уже есть"; exit 1; }

echo "== сборка $VER"
bash $A/build.sh >/dev/null
SLIM=1 bash $A/build.sh >/dev/null

# Проверка подписи: отладочный ключ в релиз не пускаем ни при каких обстоятельствах. Его CN
# известен всем, и «обновление» с такой подписью может выпустить кто угодно.
for f in $A/Falar.apk $A/Falar-slim.apk; do
  CN=$(apksigner verify --print-certs "$f" | grep -m1 'Signer #1 certificate DN' | sed 's/.*DN: //')
  case "$CN" in *"Android Debug"*) echo "$f подписан отладочным ключом ($CN)"; exit 1;; esac
  echo "  $(basename $f): $(du -h $f | cut -f1), подпись $CN"
done

cd $A && sha256sum Falar.apk Falar-slim.apk > SHA256SUMS.txt && cd $R
FULL=$(du -h $A/Falar.apk | cut -f1); SLIMSZ=$(du -h $A/Falar-slim.apk | cut -f1)

# latest.json — то, что приложение читает само, чтобы узнать о новой версии. Лежит среди файлов
# релиза, адрес постоянный: releases/latest/download/latest.json. Номер сборки, размер и sha256
# берутся из того, что реально собрано, — сверять обновление до установки иначе нечем.
# MIN_CODE=<число> — пометить обновление обязательным (ниже этой сборки пользоваться нельзя).
# NOTES='…' — строка, которую человек увидит в уведомлении.
python3 - "$A" "$VER" "${MIN_CODE:-0}" "${NOTES:-}" <<'PY'
import hashlib, json, os, re, sys
a, ver, mincode, notes = sys.argv[1:5]
code = int(re.search(r'versionCode="(\d+)"', open(os.path.join(a, "AndroidManifest.xml"), encoding="utf-8").read()).group(1))
def item(name):
    p = os.path.join(a, name)
    return {"apk": name, "size": os.path.getsize(p), "sha256": hashlib.sha256(open(p, "rb").read()).hexdigest()}
d = item("Falar.apk")
d.update({"versionCode": code, "versionName": ver, "minVersionCode": int(mincode), "notes": notes, "slim": item("Falar-slim.apk")})
json.dump(d, open(os.path.join(a, "latest.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("  latest.json: сборка", code, ver, ("· обязательное с " + mincode) if int(mincode) else "")
PY

RELNOTES=$(mktemp)
cat > $RELNOTES <<EOF
Установка и что это такое — на странице <https://annoyt.github.io/FALAR/>.

| Файл | Размер | Кому |
|---|---|---|
| \`Falar.apk\` | $FULL | обычная сборка |
| \`Falar-slim.apk\` | $SLIMSZ | без контекстного уточнителя, для слабых телефонов |

Android 9 и новее, процессор arm64. При первом запуске приложение скачает модели (около 2 ГБ,
по Wi-Fi, можно прервать и продолжить). После этого интернет не нужен.

Контрольные суммы — в \`SHA256SUMS.txt\`. Что изменилось — в [bench/apk/README.md](https://github.com/$REPO/blob/$TAG/bench/apk/README.md).
EOF

# Тег на сервере создаёт сам gh по своему токену (--target). Через git push это не делается:
# помощники учётных данных складываются, глобально настроенный `store` отвечает первым протухшей
# записью, и выпуск падает на «Invalid username or token» уже после сборки. Локальный тег ставим
# для себя, чтобы сборку можно было повторить.
git -C $R tag -a "$TAG" -m "Falar $VER"
gh release create "$TAG" -R $REPO --target "$(git -C $R rev-parse HEAD)" --title "Falar $VER" --notes-file $RELNOTES ${DRAFT:+--draft} \
  $A/Falar.apk $A/Falar-slim.apk $A/SHA256SUMS.txt $A/latest.json
rm -f $RELNOTES
echo "== готово: $(gh release view "$TAG" -R $REPO --json url --jq .url)"
echo "   кнопка на странице ведёт на https://github.com/$REPO/releases/latest/download/Falar.apk"
