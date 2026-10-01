package dev.agenttranslator;

import java.util.*;

/** Что показывают экраны 0.26.0 (Screen): ход перевода и загрузки в реплике, строка под названием
 *  разговора, гаснет ли «Улучшить», окно «Память разговора», метки модулей, строка «Облако»,
 *  подсказка «Слов». Запуск: bash bench/apk/test.sh. */
public class ScreenTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }
  /** Реплика рядом Chats.row: dir, src, dst, индекс, правлена, кем, метка, кто, снимок. */
  static String[] turn(String fixed, String by, long at, boolean photo) {
    return new String[]{"pt2ru", "Bom dia", "Доброе утро", "0", fixed, by, String.valueOf(at), "", photo ? Chats.PHOTO : ""};
  }

  public static int run() {
    fails = 0; checks = 0;

    // P: где виден ход — в реплике или в шапке
    for (String k : new String[]{"load", "live", "refine", "cloud"}) eq(Screen.where(k, "x"), Screen.REPLY, "P1 ход «" + k + "» — в реплике");
    eq(Screen.where("models", "качаю модели"), Screen.BAR, "P1 загрузка моделей — в шапке: идёт минутами");
    eq(Screen.where("live", null), Screen.NONE, "P1 работа кончилась — хода нет");
    eq(Screen.where("что-то новое", "x"), Screen.NONE, "P1 незнакомый вид не показывается");

    // S: этап реплики и подпись
    eq(Screen.stage("live", "распознаю…"), 0, "S1 распознавание — первый отрезок");
    eq(Screen.stage("live", "читаю снимок…"), 0, "S1 чтение снимка — тоже первый");
    eq(Screen.stage("live", "перевожу…"), 1, "S1 перевод — второй");
    eq(Screen.stage("refine", "поднимаю уточнитель…"), 2, "S1 уточнитель — третий, чем бы ни был занят");
    eq(Screen.stage("live", null), 0, "S1 без подписи — первый, без падения");
    eq(Screen.stageCaption("уточняю перевод", 0, 2), "уточняю перевод · 1 из 2", "S2 номер той, что в работе, а не сделанных");
    eq(Screen.stageCaption("уточняю перевод", 1, 2), "уточняю перевод · 2 из 2", "S2 вторая из двух");
    eq(Screen.stageCaption("уточняю перевод", 2, 2), "уточняю перевод · 2 из 2", "S2 сделаны все — номер не выходит за «из»");
    eq(Screen.stageCaption("распознаю…", 0, 0), "распознаю…", "S2 без счёта — подпись как есть");
    eq(Screen.stageCaption("читаю снимок · 40 %", 4, 10), "читаю снимок · 40 %", "S2 у процентов свой текст — «k из N» не добавляется");
    eq(Screen.stageFrac(1, 4), 0.25f, "S3 доля этапа");
    eq(Screen.stageFrac(5, 4), 1f, "S3 доля не больше целого");
    eq(Screen.stageFrac(3, 0), 0f, "S3 без счёта — пусто");

    // L: загрузка движков — «Загружаю модели · перевод · ещё ≈ N с»
    long[] d4 = Screen.loadExpect("", 4);
    eq(Arrays.toString(d4), "[3700, 3700, 3000, 1500]", "L1 без замера — Redmi 30.09");
    eq(Arrays.toString(Screen.loadExpect("4200,3600,2800,1100", 4)), "[4200, 3600, 2800, 1100]", "L1 замер прошлого запуска");
    eq(Arrays.toString(Screen.loadExpect("4200,3600,2800,1100", 3)), "[4200, 3600, 1100]", "L1 без озвучки третьего этапа нет");
    eq(Arrays.toString(Screen.loadExpect("4200,0,-5,1100", 4)), "[4200, 3700, 3000, 1100]", "L1 нули и минусы — по умолчанию");
    eq(Arrays.toString(Screen.loadExpect("4200,мусор,2800,1100", 4)), "[4200, 3700, 3000, 1500]", "L1 испорченный замер дальше не читается");
    eq(Arrays.toString(Screen.loadExpect("1,2,3", 4)), "[3700, 3700, 3000, 1500]", "L1 не четыре числа — по умолчанию");
    eq(Arrays.toString(Screen.loadExpect(null, 4)), "[3700, 3700, 3000, 1500]", "L1 нет записи — по умолчанию");
    long[] ms = {4000, 3000, 2000, 1000};
    eq(Screen.loadLeft(ms, 4, 0, 0), 10000L, "L2 в начале — все этапы");
    eq(Screen.loadLeft(ms, 4, 1, 1000), 5000L, "L2 второй этап на секунде: его остаток и следующие");
    eq(Screen.loadLeft(ms, 4, 1, 9000), 3500L, "L2 этап затянулся — остаток не меньше 0,5 с");
    eq(Screen.loadLeft(ms, 4, 9, 0), 1000L, "L2 этап за пределами — последний");
    eq(Screen.loadLeft(new long[]{4000, 3000, 1000}, 3, 2, 400), 600L, "L2 без озвучки — три этапа");
    eq(Screen.loadFrac(ms, 4, 1, 1500), 0.5f, "L3 доля этапа по времени прошлого запуска");
    eq(Screen.loadFrac(ms, 4, 1, 99000), 0.95f, "L3 не до конца, пока этап не кончился сам");
    eq(Screen.loadFrac(new long[]{0, 0, 0, 0}, 4, 0, 10), 0.95f, "L3 нулевой замер — без деления на ноль");
    eq(Screen.loadCaption("перевод", 4600), "Загружаю модели · перевод · ещё ≈ 5 с", "L4 подпись с округлением");
    eq(Screen.loadCaption("словарь и разговоры", 200), "Загружаю модели · словарь и разговоры · ещё ≈ 1 с", "L4 меньше секунды — «ещё ≈ 1 с», не «0 с»");

    // C: облако — один запрос, ход по времени
    eq(Screen.cloudTypical(0), 20000L, "C1 модель ещё не отвечала — 20 с");
    eq(Screen.cloudTypical(5300), 5300L, "C1 сколько обычно отвечает модель");
    eq(Screen.cloudCaption(2000, 14000), "Улучшаю в облаке · ещё ≈ 12 с", "C2 сколько осталось");
    eq(Screen.cloudCaption(13500, 14000), "Улучшаю в облаке · дольше обычного · 13 с", "C2 меньше секунды до обычного — «дольше обычного»");
    eq(Screen.cloudCaption(31000, 20000), "Улучшаю в облаке · дольше обычного · 31 с", "C2 дольше обычного — сколько уже идёт");
    eq(Screen.cloudFrac(10000, 20000), 0.5f, "C3 доля по времени");
    eq(Screen.cloudFrac(50000, 20000), 0.95f, "C3 не до конца, пока ответа нет");

    // H: строка под названием разговора
    eq(Screen.hint("собеседник", false, false, "cloud", "", "ТЕСТ", null), "собеседник", "H1 обычное состояние");
    eq(Screen.hint("собеседник", true, true, "нет сети", "", "ТЕСТ", null), "читаете вслух · микрофон не слушает · улучшить нельзя: нет сети", "H2 держат текст — только это и причина серой кнопки");
    eq(Screen.hint("вы", false, true, "local", "", "ТЕСТ", null), "вы · транскрипция скрыта · касание вернёт", "H3 транскрипция спрятана касанием");
    eq(Screen.hint("", false, false, "модули «Облако» и «Уточнитель» выключены", "", "ТЕСТ", null), "улучшить нельзя: модули «Облако» и «Уточнитель» выключены", "H4 почему «Улучшить» серая — без «·» в начале");
    eq(Screen.hint("собеседник", false, false, null, "", "ТЕСТ", null), "собеседник", "H4 способ не спрашивали — причины нет");
    eq(Screen.hint("собеседник", false, false, "cloud", "Аренда квартиры", "ТЕСТ", null), "собеседник · Аренда квартиры", "H5 тема разговора");
    eq(Screen.hint("собеседник", false, false, "cloud", "Аренда квартиры", "Аренда квартиры", null), "собеседник", "H5 тема уже в названии — второй раз не пишется");
    eq(Screen.hint("продолжаем · Аренда квартиры", false, false, "cloud", "Аренда квартиры", "ТЕСТ", null), "продолжаем · Аренда квартиры", "H5 тема уже в строке");
    eq(Screen.hint("собеседник", true, true, "нет сети", "Тема", "ТЕСТ", "облегчаю перевод · 120 из 305 МБ"), "облегчаю перевод · 120 из 305 МБ", "H6 идёт загрузка — строка её ход");
    eq(Screen.hint(null, false, false, null, null, "ТЕСТ", null), "", "H7 пусто — пусто, без падения");

    // I: гаснет ли «Улучшить»
    ok(Screen.improved(null, false, "cloud", false, 0, 0), "I1 реплики нет — гаснет");
    ok(Screen.improved(turn("", "", 5000, true), false, "cloud", false, 0, 0), "I2 снимок — гаснет: «Улучшить» его не трогает");
    ok(Screen.improved(turn("", "", 5000, false), true, "cloud", false, 0, 0), "I3 правлено человеком — гаснет");
    ok(!Screen.improved(turn("", "", 5000, false), false, "cloud", false, 0, 0), "I4 облако: свежая фраза — можно");
    ok(Screen.improved(turn("", "", 5000, false), false, "cloud", true, 5000, 0), "I4 облако пересматривало этот разговор до неё — гаснет");
    ok(!Screen.improved(turn("", "", 5000, false), false, "cloud", false, 9000, 0), "I4 отметка облака — от другого разговора: можно");
    ok(!Screen.improved(turn("", "", 5000, false), false, "cloud", true, 4000, 0), "I4 облако видело только прежние — можно");
    ok(Screen.improved(turn("1", Chats.BY_CLOUD, 5000, false), false, "cloud", false, 0, 0), "I4 облако её уже правило — гаснет");
    ok(!Screen.improved(turn("1", Chats.BY_LLM, 5000, false), false, "cloud", false, 0, 9000), "I5 уточнитель правил, а улучшит облако — можно (по способу)");
    ok(Screen.improved(turn("1", Chats.BY_LLM, 5000, false), false, "local", false, 0, 0), "I5 уточнитель: уже улучшена — гаснет");
    ok(Screen.improved(turn("", "", 5000, false), false, "local", false, 0, 5000), "I5 уточнитель её уже разобрал — гаснет");
    ok(!Screen.improved(turn("", "", 5000, false), false, "local", true, 9000, 4000), "I5 уточнитель не дошёл, облако не в счёт — можно");
    ok(!Screen.improved(turn("", "", 5000, false), false, "нет сети, а контекст 🧠 выключен", true, 9000, 9000), "I6 улучшить нечем — не «обработана» (серая по другой причине)");
    String[] bad = turn("", "", 0, false); bad[6] = "x";
    ok(!Screen.improved(bad, false, "cloud", true, 9000, 9000), "I7 метка испорчена — не гаснет");

    // M: «Память разговора» — только для человека (владелец 01.10)
    eq(Screen.memoText("", new ArrayList<>(), ""), "Кто говорит — пока не ясно.\n\nКлючевые детали — пока нет.", "M1 пусто — коротко, без объяснений устройства");
    List<String[]> names = Arrays.asList(new String[]{"Maria", "Мария"}, new String[]{"João", ""}, new String[]{"Rio", "rio"});
    String m = Screen.memoText("Кто говорит: по-португальски — женщина, по-русски — мужчина.", names, "Аренда 2500 в месяц, можно с кошкой");
    eq(m, "Кто говорит: по-португальски — женщина, по-русски — мужчина.\nИмена: Maria (Мария), João, Rio\n\nКлючевые детали:\nАренда 2500 в месяц, можно с кошкой",
       "M2 кто говорит, имена (перевод — если отличается), детали");
    for (String w : new String[]{"облак", "модел", "уточнител", "глоссари", "ваши", "автоматик"}) ok(!m.toLowerCase().contains(w), "M3 в окне нет «" + w + "…»");
    eq(Screen.memoMore(false, 0, 0), Collections.emptyList(), "M4 нечего делать — «ещё…» нет");
    eq(Screen.memoMore(true, 2, 3), Arrays.asList("вернуть память автоматике", "добавить имена в свои слова (2)", "забыть подсказки разговора (3)"), "M4 всё, что есть");
    eq(Screen.memoMore(false, 1, 0), Collections.singletonList("добавить имена в свои слова (1)"), "M4 вернуть — только свою");
    eq(Screen.memoMore(2, false, 0, 1), Arrays.asList("назвать собеседника…", "забыть голоса разговора (2)", "забыть подсказки разговора (1)"), "M5 голоса разговора — первыми");
    eq(Screen.memoText("Кто говорит: по-португальски — женщина.", "Говорят двое.\n• Собеседник 1 — по-русски\n• Ана (собеседник 2) — по-португальски", true, new ArrayList<>(), ""),
       "Говорят двое.\n• Собеседник 1 — по-русски\n• Ана (собеседник 2) — по-португальски\n\nКлючевые детали — пока нет.", "M6 собеседники вместо строки «кто говорит»");
    eq(Screen.memoText("", "", true, new ArrayList<>(), ""),
       "Кто говорит — пока не ясно.\nГолосов в разговоре нет: пусть каждый скажет фразу кнопкой FALAR — тогда слушание переводит только их.\n\nКлючевые детали — пока нет.", "M6 голосов нет — как их завести");
    eq(Screen.memoText("", "", false, new ArrayList<>(), ""), "Кто говорит — пока не ясно.\n\nКлючевые детали — пока нет.", "M6 модуль выключен — о голосах ни слова");

    // K: строка «Облако» в настройках
    eq(Screen.cloudRow(0, 0), "ключа нет · пересмотр по кнопке", "K1 ключа нет");
    eq(Screen.cloudRow(1, 5), "1 ключ · пересмотр каждые 5 реплик", "K1 один ключ");
    eq(Screen.cloudRow(3, 10), "3 ключа · пересмотр каждые 10 реплик", "K1 три ключа");
    eq(Screen.cloudRow(5, 20), "5 ключей · пересмотр каждые 20 реплик", "K1 пять ключей");
    eq(Screen.cloudRow(21, 0), "21 ключ · пересмотр по кнопке", "K1 двадцать один ключ");
    eq(Screen.cloudRow(12, 0), "12 ключей · пересмотр по кнопке", "K1 двенадцать ключей");
    eq(Screen.plural(104, "раз", "раза", "раз"), "раза", "K2 104 раза");
    eq(Screen.plural(111, "ключ", "ключа", "ключей"), "ключей", "K2 111 ключей");

    // D: модули и файлы
    eq(String.join("|", Screen.modPill(true, 0, false, false, false, false, 0)), "нужен ключ|no", "D1 облако без ключа");
    eq(String.join("|", Screen.modPill(true, 0, false, false, false, false, 1)), "ключ есть|ok", "D1 облако с ключом");
    eq(String.join("|", Screen.modPill(true, 0, false, false, false, false, 3)), "ключей: 3|ok", "D1 облако с ключами");
    eq(String.join("|", Screen.modPill(false, 5_000_000, true, false, false, false, 0)), "выключен · файлы на месте|no", "D2 выключен — файлы остаются (владелец 28.09)");
    eq(String.join("|", Screen.modPill(false, 5_000_000, false, false, false, false, 0)), "выключен|no", "D2 выключен, файлов нет");
    eq(String.join("|", Screen.modPill(true, 5_000_000, true, false, false, true, 0)), "установлено|ok", "D3 на месте — установлено, хоть другие и качаются");
    eq(String.join("|", Screen.modPill(true, 5_000_000, false, true, false, true, 0)), "качается|go", "D3 качается сейчас");
    eq(String.join("|", Screen.modPill(true, 5_000_000, false, true, true, true, 0)), "ждёт Wi-Fi|wait", "D3 ждёт Wi-Fi");
    eq(String.join("|", Screen.modPill(true, 5_000_000, false, false, false, true, 0)), "в очереди|no", "D3 в очереди за другим");
    eq(String.join("|", Screen.modPill(true, 5_000_000, false, false, false, false, 0)), "не скачано|no", "D3 не скачано и не качается");
    ok(Screen.modBar(true, 5_000_000, false), "D4 полоса — у включённого, которого ещё нет");
    ok(!Screen.modBar(true, 5_000_000, true) && !Screen.modBar(false, 5_000_000, false) && !Screen.modBar(true, 0, false), "D4 на месте, выключен или без файлов — полосы нет");
    eq(Screen.modBarLabel(150_000_000, 305_000_000), "150 из 305 МБ · 49 %", "D5 подпись полосы");
    eq(Screen.modBarLabel(400_000_000, 305_000_000), "305 из 305 МБ · 100 %", "D5 больше размера — не больше 100 %");
    eq(Screen.modBarLabel(0, 0), "0 из 0 МБ · 0 %", "D5 нулевой размер — без деления на ноль");
    eq(Screen.modSize(0), "", "D6 без файлов — без размера");
    eq(Screen.modSize(305_400_000), "305 МБ", "D6 мегабайты");
    eq(Screen.modSize(1_520_000_000L), "1,5 ГБ", "D6 гигабайты — с запятой");
    eq(String.join("|", Screen.modelsSum(false, false, false, false, 0, 0, 0, 0, 14, 0, 0)), "Проверяю файлы…|", "D7 ещё не проверено");
    eq(String.join("|", Screen.modelsSum(true, true, true, false, 0, 0, 0, 0, 14, 0, 0)), "Проверяю файлы…|dl", "D7 проверка файлов");
    eq(String.join("|", Screen.modelsSum(true, false, true, false, 120_000_000, 305_000_000, 0, 1, 14, 0, 0)), "Качаю · 120.0 из 305.0 МБ|dl", "D7 качаю");
    eq(String.join("|", Screen.modelsSum(true, false, true, true, 120_000_000, 305_000_000, 0, 1, 14, 0, 0)), "Жду Wi-Fi · осталось 185.0 МБ|dl", "D7 жду Wi-Fi");
    eq(String.join("|", Screen.modelsSum(true, false, false, false, 0, 0, 0, 0, 14, 0, 0)), "Все модели установлены · 14 из 14|ok", "D7 всё на месте — одна строка (владелец 30.09)");
    eq(String.join("|", Screen.modelsSum(true, false, false, false, 0, 0, 2, 0, 14, 820_000_000, 0)), "Обязательных нет: 2 из 14 · 820.0 МБ|dl", "D7 нет обязательных");
    eq(String.join("|", Screen.modelsSum(true, false, false, false, 0, 0, 0, 3, 14, 0, 305_000_000)), "Модулям не хватает 305.0 МБ — докачается само|dl", "D7 модулям не хватает");

    // W: «Слова»
    eq(Screen.stepMin(3, +1), 4, "W1 «+» — на один больше");
    eq(Screen.stepMin(1, -1), 1, "W1 меньше одного не бывает");
    eq(Screen.stepMin(10, +1), 10, "W1 больше десяти не бывает");
    eq(Screen.wordsHint(false, 0, 3, true), "Пока нечего показать: нужно, чтобы слово встретилось не меньше 3 раз", "W2 пусто в «Учу»");
    eq(Screen.wordsHint(false, 0, 1, true), "Пока нечего показать: нужно, чтобы слово встретилось не меньше 1 раза", "W2 «не меньше 1 раза»");
    eq(Screen.wordsHint(false, 200, 3, true), "Слов от 3 повторов: 200 · нажатие произносит, долгое — «знаю»", "W3 слова в «Учу»");
    eq(Screen.wordsHint(false, 12, 1, false), "Слов от 1 повтора: 12 · долгое — «знаю»", "W3 без озвучки касание не произносит");
    eq(Screen.wordsHint(true, 0, 3, true), "Известных слов пока нет — отмечайте их долгим нажатием в «Учу»", "W4 пусто в «Знаю»");
    eq(Screen.wordsHint(true, 1, 3, true), "Знаю: 1 слово · нажатие произносит, долгое возвращает в изучение", "W4 одно слово");
    eq(Screen.wordsHint(true, 48, 3, false), "Знаю: 48 слов · долгое возвращает в изучение", "W4 сорок восемь слов");

    System.out.println(fails == 0 ? "Screen: " + checks + " проверок, все прошли" : "Screen: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
