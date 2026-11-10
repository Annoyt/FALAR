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
# проверяет), а раздел «== следующая» в bench/apk/whatsnew.txt — назвать этой версией: окно «Что нового»
# после обновления берёт пункты оттуда (без записи скрипт не начнёт). Имена файлов Falar.apk, Falar-slim.apk и их пары
# с NPU (Falar-npu.apk, Falar-slim-npu.apk) постоянные — ссылка
# releases/latest/download/Falar.apk не должна ломаться от версии к версии.
set -e
R=$(cd "$(dirname "$0")/.." && pwd); A=$R/bench/apk
REPO=${REPO:-Annoyt/FALAR}

[ -n "$FALAR_KEYSTORE" ] || FALAR_KEYSTORE=~/falar-release.keystore
[ -f "$FALAR_KEYSTORE" ] || { echo "нет файла ключа: $FALAR_KEYSTORE"; exit 1; }
command -v gh >/dev/null || { echo "нужен gh"; exit 1; }
# GITHUB_TOKEN/GH_TOKEN в окружении перебивают рабочий ключ из связки, и если он протух, gh
# отвечает 401 уже после сборки. Снимаем их на время выпуска: ключ из связки заведомо годен,
# раз человек им пользуется.
unset GITHUB_TOKEN GH_TOKEN
# Авторизацию gh проверяем до сборки. Дважды выпуск падал уже после трёх минут работы: в
# интерактивной оболочке gh не авторизован и отвечает 401, а git при этом берёт протухшую
# запись из глобального `store` и говорит «Invalid username or token» — по этому тексту
# настоящую причину не угадать.
# Именно запросом к API, а не `gh auth status`: тот отвечает «всё хорошо» по наличию записи в
# настройках, а недействительный токен всплывает только на настоящем обращении — и выпуск снова
# падает после сборки.
gh api user >/dev/null 2>&1 || { echo "токен GitHub недействителен в этой оболочке: выполните 'gh auth login' и повторите"; exit 1; }
# «Что нового» (bench/apk/whatsnew.txt) — до пароля и сборки: у выпуска должна быть своя запись, а
# раздел «следующая» — получить номер. Иначе окно после обновления на эту версию покажет не те пункты.
WVER=$(grep -o 'versionName="[^"]*"' $A/AndroidManifest.xml | cut -d'"' -f2); WCODE=$(grep -o 'versionCode="[0-9]*"' $A/AndroidManifest.xml | cut -d'"' -f2)
grep -qx "== $WVER ($WCODE)" $A/whatsnew.txt || { echo "в bench/apk/whatsnew.txt нет записи «== $WVER ($WCODE)»: что нового в этой версии — коротко, «+» новое, «*» исправлено"; exit 1; }
! grep -qx "== следующая" $A/whatsnew.txt || { echo "в bench/apk/whatsnew.txt остался раздел «== следующая»: дайте ему номер «== $WVER ($WCODE)»"; exit 1; }
# Качество распознавания, перевода и чтения снимков — тоже до пароля и сборки (bench/quality/gate.sh):
# настольные тесты, перевод кодом приложения, распознавание и чтение снимков на ПК против эталона
# bench/quality/baseline; если с прошлого выпуска менялось слушание, чтение снимков или модели — и на
# телефоне. Хуже эталона — не выпускаем: исправить или осознанно
# принять (gate.sh --accept, эталон — в коммит). Владелец, 02.10.2026: «тесты надо автоматизировать и
# применять в процессе наших изменений в распознавании и переводе». QUALITY=skip — только по его слову.
PREV=$(git -C $R describe --tags --abbrev=0 --match 'v*' HEAD 2>/dev/null)
if [ "${QUALITY:-}" = skip ]; then
  echo "ВНИМАНИЕ: проверка качества пропущена (QUALITY=skip)"
elif [ -n "$PREV" ]; then
  bash $R/bench/quality/gate.sh --release "$PREV" || { echo "проверка качества не пропустила выпуск — журнал выше"; exit 1; }
fi
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
# Сборки с NPU (владелец 09.11: «сборка с и без, страница помогает выбрать»; 10.11 — «с функциями для mediatek и
# qualcom»): те же две, но с ORT с QNN и библиотеками Qualcomm (лицензия AI Stack — только внутри приложения) и с
# дополнением ORT для NeuroPilot (MediaTek). Библиотеки Qualcomm — bench/npu/fetch_qnn.sh, по умолчанию в
# .stand/qnn-android основной копии проекта; дополнение — bench/npu/neuropilot-ep/build.sh, в .stand/mtk-ep/v0.3.0-int16-stub
# (рядом — своя заглушка libneuron_op_capability.so вместо закрытой).
# NPU_BUILDS=0 — выпуск без сборок с NPU, NPU_MTK=0 — сборки с NPU только для Snapdragon (по слову владельца).
MAIN=$(cd "$(git -C $R rev-parse --git-common-dir)/.." && pwd)
export QNN_DIR=${QNN_DIR:-$MAIN/.stand/qnn-android}
export MTK_EP_DIR=${MTK_EP_DIR:-$MAIN/.stand/mtk-ep/v0.3.0-int16-stub}   # своя заглушка libneuron_op_capability.so (решение владельца 10.11)
APKS="Falar.apk Falar-slim.apk"
# Страница (docs/index.html, NPU_FROM) ведёт телефоны с NPU на Falar-npu.apk начиная с этой версии: выпуск
# без сборок с NPU с такой версии сломал бы им кнопку «Скачать». По умолчанию сборки с NPU делаются ровно с NPU_FROM
# (владелец 10.11: 0.37.0 — без них, пока озвучка с eSpeak NG под GPL лежит в одном APK с закрытыми библиотеками
# Qualcomm); NPU_BUILDS=1 — собрать и раньше (выйдут файлами релиза, страница их не предложит).
NPU_FROM=$(grep -o "NPU_FROM = '[0-9.]*'" $R/docs/index.html | grep -o "[0-9.]*" | tail -1)
vnum() { echo "$1" | awk -F. '{print $1 * 1000000 + $2 * 1000 + $3}'; }
ONPAGE=$([ -n "$NPU_FROM" ] && [ $(vnum $VER) -ge $(vnum $NPU_FROM) ] && echo 1 || echo 0)
NPU_BUILDS=${NPU_BUILDS:-$ONPAGE}
if [ "$NPU_BUILDS" = 0 ] && [ "$ONPAGE" = 1 ]; then
  echo "NPU_BUILDS=0, а страница с $NPU_FROM предлагает Falar-npu.apk — поднимите NPU_FROM в docs/index.html или соберите с NPU"; exit 1
fi
echo "  сборки с NPU: $([ "$NPU_BUILDS" = 0 ] && echo "нет (страница предлагает их с ${NPU_FROM:-—})" || echo да)"
if [ "$NPU_BUILDS" != 0 ]; then
  [ -s $QNN_DIR/qnn/libQnnHtp.so ] || { echo "нет библиотек NPU в $QNN_DIR — bash bench/npu/fetch_qnn.sh (или NPU_BUILDS=0)"; exit 1; }
  MTKV=$([ "${NPU_MTK:-1}" = 0 ] || echo 1)
  [ -z "$MTKV" ] || [ -s $MTK_EP_DIR/libonnxruntime_providers_neuropilot.so ] \
    || { echo "нет дополнения NeuroPilot в $MTK_EP_DIR — bash bench/npu/neuropilot-ep/build.sh (или NPU_MTK=0)"; exit 1; }
  NPU=1 MTK=$MTKV bash $A/build.sh >/dev/null
  NPU=1 MTK=$MTKV SLIM=1 bash $A/build.sh >/dev/null
  APKS="$APKS Falar-npu.apk Falar-slim-npu.apk"
fi

# Проверка подписи: отладочный ключ в релиз не пускаем ни при каких обстоятельствах. Его CN
# известен всем, и «обновление» с такой подписью может выпустить кто угодно.
for f in $(for n in $APKS; do echo $A/$n; done); do
  CN=$(apksigner verify --print-certs "$f" | grep -m1 'Signer #1 certificate DN' | sed 's/.*DN: //')
  case "$CN" in *"Android Debug"*) echo "$f подписан отладочным ключом ($CN)"; exit 1;; esac
  echo "  $(basename $f): $(du -h $f | cut -f1), подпись $CN"
done

cd $A && sha256sum $APKS > SHA256SUMS.txt && cd $R
FULL=$(du -h $A/Falar.apk | cut -f1); SLIMSZ=$(du -h $A/Falar-slim.apk | cut -f1)
NPUSZ=; SLIMNPUSZ=        # только если сборки с NPU в этом выпуске: прежний Falar-npu.apk в каталоге не в счёт
case " $APKS " in *" Falar-npu.apk "*) NPUSZ=$(du -h $A/Falar-npu.apk | cut -f1); SLIMNPUSZ=$(du -h $A/Falar-slim-npu.apk | cut -f1);; esac

# latest.json — то, что приложение читает само, чтобы узнать о новой версии. Лежит среди файлов
# релиза, адрес постоянный: releases/latest/download/latest.json. Номер сборки, размер и sha256
# берутся из того, что реально собрано, — сверять обновление до установки иначе нечем.
# MIN_CODE=<число> — пометить обновление обязательным (ниже этой сборки пользоваться нельзя).
# NOTES='…' — строка, которую человек увидит в уведомлении.
python3 - "$A" "$VER" "${MIN_CODE:-0}" "${NOTES:-}" "$APKS" <<'PY'
import hashlib, json, os, re, sys
a, ver, mincode, notes, apks = sys.argv[1:6]
code = int(re.search(r'versionCode="(\d+)"', open(os.path.join(a, "AndroidManifest.xml"), encoding="utf-8").read()).group(1))
def item(name):
    p = os.path.join(a, name)
    return {"apk": name, "size": os.path.getsize(p), "sha256": hashlib.sha256(open(p, "rb").read()).hexdigest()}
d = item("Falar.apk")
d.update({"versionCode": code, "versionName": ver, "minVersionCode": int(mincode), "notes": notes, "slim": item("Falar-slim.apk")})
# Сборки с NPU — для приложения с NPU (Updates.parse: npu, slim_npu); у остальных разделы просто не читаются
if "Falar-npu.apk" in apks.split():
    d.update({"npu": item("Falar-npu.apk"), "slim_npu": item("Falar-slim-npu.apk")})
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
${NPUSZ:+| \`Falar-npu.apk\` | $NPUSZ | обычная + распознавание на NPU: Snapdragon 888/778G и новее${MTKV:+, MediaTek Dimensity 8400}; экспериментально |}
${SLIMNPUSZ:+| \`Falar-slim-npu.apk\` | $SLIMNPUSZ | облегчённая + NPU |}

Android 9 и новее, процессор arm64. При первом запуске приложение скачает модели (около 1,5 ГБ,
по Wi-Fi, можно прервать и продолжить). После этого интернет не нужен.

Контрольные суммы — в \`SHA256SUMS.txt\`. Что изменилось — в [bench/apk/README.md](https://github.com/$REPO/blob/$TAG/bench/apk/README.md).
EOF

# Тег на сервере создаёт сам gh по своему токену (--target). Через git push это не делается:
# помощники учётных данных складываются, глобально настроенный `store` отвечает первым протухшей
# записью, и выпуск падает на «Invalid username or token» уже после сборки. Локальный тег ставим
# для себя, чтобы сборку можно было повторить.
git -C $R tag -a "$TAG" -m "Falar $VER"
gh release create "$TAG" -R $REPO --target "$(git -C $R rev-parse HEAD)" --title "Falar $VER" --notes-file $RELNOTES ${DRAFT:+--draft} \
  $(for n in $APKS; do echo $A/$n; done) $A/SHA256SUMS.txt $A/latest.json
rm -f $RELNOTES
echo "== готово: $(gh release view "$TAG" -R $REPO --json url --jq .url)"
echo "   кнопка на странице ведёт на https://github.com/$REPO/releases/latest/download/Falar.apk"
