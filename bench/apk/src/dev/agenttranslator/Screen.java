package dev.agenttranslator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Что показывают экраны 0.26.0 — подписи, метки и решения без Android, чтобы их проверял
 *  настольный тест (bench/apk/test/ScreenTest.java). Рисует и раскладывает MainActivity, а здесь —
 *  ход перевода и загрузки в реплике, строка под названием разговора, гаснет ли «Улучшить», окно
 *  «Память разговора», метки модулей, строка «Облако» в настройках, подсказка «Слов». */
final class Screen {
  private Screen() {}

  // ---- ход в реплике ------------------------------------------------------------------------

  /** Где виден ход работы: в реплике — живой перевод, уточнитель, облако и загрузка движков (решение
   *  владельца 01.10: ход — в самой реплике, «надо сохранить стиль»); в шапке — загрузки моделей:
   *  они идут минутами и закрыли бы кнопки реплики. */
  static final int NONE = 0, REPLY = 1, BAR = 2;
  static int where(String kind, String what) {
    if (what == null) return NONE;
    if ("load".equals(kind) || "live".equals(kind) || "refine".equals(kind) || "cloud".equals(kind)) return REPLY;
    return "models".equals(kind) ? BAR : NONE;
  }

  /** Этап реплики из трёх — «распознаю · перевожу · уточняю»: уточнитель — третий, перевод — второй,
   *  остальное (распознавание, чтение снимка) — первый. */
  static int stage(String kind, String what) {
    return "refine".equals(kind) ? 2 : what != null && what.startsWith("перевожу") ? 1 : 0;
  }
  static float stageFrac(int done, int total) { return total > 0 ? Math.min(1f, done / (float) total) : 0f; }
  /** «уточняю перевод · 1 из 2»: номер той, что в работе, а не уже сделанных. У процентов свой текст. */
  static String stageCaption(String what, int done, int total) {
    return total > 0 && !what.contains("%") ? L.f("%s · %d из %d", busyText(what), Math.min(done + 1, total), total) : busyText(what);
  }
  /** Подпись хода на языке надписей. Сервис шлёт её по-русски (по ней же различаются этапы — stage),
   *  иногда с процентом в конце: «качаю модели · 40 %» — переводится начало, процент остаётся. */
  static String busyText(String what) {
    if (what == null) return null;
    int i = what.lastIndexOf(" · ");
    if (i > 0 && what.endsWith("%")) return L.t(what.substring(0, i)) + what.substring(i);
    return L.t(what);
  }

  /** Сколько шли этапы загрузки движков в прошлый раз, в порядке этапов: распознавание, перевод, словарь и
   *  разговоры, озвучка (озвучка — последней с 03.10: перевод сказанного во время загрузки её не ждёт). В
   *  load_ms они записаны по-старому — «распознавание,перевод,озвучка,остальное», мс: так прежние записи
   *  остаются годными. Нет замера или он испорчен — Redmi Note 10 Pro, 30.09: 3,7 · 3,7 · 1,5 · 3,0 с. Без
   *  озвучки (n = 3) её этапа нет. */
  static long[] loadExpect(String loadMs, int n) {
    long[] d = {3700, 3700, 3000, 1500};
    try {
      String[] x = (loadMs == null ? "" : loadMs).split(",");
      if (x.length == 4) for (int i = 0; i < 4; i++) { long v = Long.parseLong(x[i].trim()); if (v > 0) d[i] = v; }
    } catch (RuntimeException ignore) {}
    return n == 4 ? new long[]{d[0], d[1], d[3], d[2]} : new long[]{d[0], d[1], d[3]};
  }
  /** Доля текущего этапа k из n за elapsed мс: по времени прошлого запуска и не до конца, пока этап
   *  не кончился сам. */
  static float loadFrac(long[] ms, int n, int k, long elapsed) {
    long cur = ms[Math.min(Math.min(k, n - 1), ms.length - 1)];
    return Math.min(0.95f, elapsed / (float) Math.max(1, cur));
  }
  /** Сколько ещё, мс: остаток текущего этапа (не меньше 0,5 с — этап ещё идёт) и все следующие. */
  static long loadLeft(long[] ms, int n, int k, long elapsed) {
    k = Math.min(k, n - 1);
    long cur = ms[Math.min(k, ms.length - 1)], after = 0;
    for (int i = k + 1; i < n; i++) after += ms[Math.min(i, ms.length - 1)];
    return Math.max(cur - elapsed, 500) + after;
  }
  static String loadCaption(String what, long leftMs) {
    return L.f("Загружаю модели · %s · ещё ≈ %d с", busyText(what), Math.max(1, Math.round(leftMs / 1000.0)));
  }

  /** Облако — один запрос, точного хода нет: отрезок идёт по времени, сколько обычно отвечает эта
   *  модель (нет замера — 20 с), и не доходит до конца, пока ответа нет. */
  static long cloudTypical(long modelMs) { return modelMs > 0 ? modelMs : 20000; }
  static float cloudFrac(long ms, long typical) { return Math.min(0.95f, ms / (float) Math.max(1, typical)); }
  static String cloudCaption(long ms, long typical) {
    long left = typical - ms;
    return left >= 1000 ? L.f("Улучшаю в облаке · ещё ≈ %d с", Math.round(left / 1000.0)) : L.f("Улучшаю в облаке · дольше обычного · %d с", ms / 1000);
  }

  // ---- шапка разговора ----------------------------------------------------------------------

  /** Строка под названием разговора. Держат крупный текст — только это; спрятанная транскрипция,
   *  почему «Улучшить» серая (mode — improveMode(), null — не спрашивали) и тема — через «·». Пока
   *  с разговором идут загрузки (busy), строка — их ход. */
  static String hint(String base, boolean holdingRead, boolean cribHidden, String mode, String topic, String title, String busy) {
    if (busy != null) return busy;
    String h = base == null ? "" : base;
    if (holdingRead) h = L.t("читаете вслух · микрофон не слушает");
    else if (cribHidden) h = join(h, L.t("транскрипция скрыта · касание вернёт"));
    if (mode != null && !mode.equals("cloud") && !mode.equals("local")) h = join(h, L.f("улучшить нельзя: %s", L.t(mode)));
    if (topic != null && !topic.isEmpty() && !h.contains(topic) && !topic.equals(title)) h = join(h, topic);
    return h;
  }
  static String join(String a, String b) { return a.isEmpty() ? b : a + " · " + b; }

  /** Последняя реплика уже обработана тем, чем «Улучшить» обработала бы её сейчас, — тогда кнопка
   *  гаснет (владелец 01.10). t — реплика (Chats.turn), human — правлена человеком, mode —
   *  improveMode(); cloudSaw — облако пересматривало этот разговор до метки cloudAt; localAt — до
   *  какой метки разобрал уточнитель. По способу, а не «чем угодно»: уточнитель разбирает каждую
   *  реплику сам, и иначе облачное «Улучшить» не было бы доступно почти никогда. Правленное
   *  человеком автоматика не меняет, текст снимка «Улучшить» не трогает — тоже гаснет. */
  static boolean improved(String[] t, boolean human, String mode, boolean cloudSaw, long cloudAt, long localAt) {
    if (t == null) return true;
    if (Chats.PHOTO.equals(t[8])) return true;
    long at; try { at = Long.parseLong(t[6]); } catch (RuntimeException e) { return false; }
    if (human) return true;
    if ("cloud".equals(mode)) return Chats.BY_CLOUD.equals(t[5]) || (cloudSaw && cloudAt >= at);
    if ("local".equals(mode)) return !t[4].isEmpty() || localAt >= at;
    return false;
  }

  /** «Память разговора» — только то, что понятно без знания устройства приложения (владелец 01.10:
   *  «зачем простому пользователю информация, что от облака»): кто говорит, имена, ключевые детали.
   *  names — {португальское, русское}. */
  static String memoText(String who, List<String[]> names, String memo) { return memoText(who, "", false, names, memo); }
  /** С голосами разговора: speakers (Memo.speakers — сколько говорит и строка на каждого) — вместо строки
   *  «кто говорит». Голосов нет, а модуль «Отпечаток голоса» включён — одна строка, как их завести. */
  static String memoText(String who, String speakers, boolean speakerModule, List<String[]> names, String memo) {
    StringBuilder b = new StringBuilder();
    if (speakers != null && !speakers.isEmpty()) b.append(speakers);
    else {
      b.append(who == null || who.isEmpty() ? L.t("Кто говорит — пока не ясно.") : who);
      if (speakerModule) b.append(L.t("\nГолосов в разговоре нет: пусть каждый скажет фразу кнопкой FALAR — тогда слушание переводит только их."));
    }
    if (names != null && !names.isEmpty()) {
      b.append(L.t("\nИмена: "));
      for (int k = 0; k < names.size(); k++) {
        String[] n = names.get(k); b.append(k > 0 ? ", " : "").append(n[0]);
        if (n.length > 1 && !n[1].isEmpty() && !n[1].equalsIgnoreCase(n[0])) b.append(" (").append(n[1]).append(')');
      }
    }
    return b.append(memo == null || memo.isEmpty() ? L.t("\n\nКлючевые детали — пока нет.") : L.t("\n\nКлючевые детали:\n") + memo).toString();
  }
  /** Пункты «ещё…» окна памяти: что есть, то и можно сделать. */
  static List<String> memoMore(boolean byUser, int names, int terms) { return memoMore(0, byUser, names, terms); }
  /** Пункты голосов разговора — первыми: назвать собеседника, забыть голоса (слепок — биометрия, стереть
   *  его можно всегда). */
  static final String NAME_VOICE = L.k("назвать собеседника…"), FORGET_VOICES = L.k("забыть голоса разговора"),
      MEMO_BACK = L.k("вернуть память автоматике"), ADD_NAMES = L.k("добавить имена в свои слова"), FORGET_TERMS = L.k("забыть подсказки разговора");
  /** Пункты — на языке надписей; окно узнаёт их по тем же L.t(константа), а не по русскому началу. */
  static List<String> memoMore(int voices, boolean byUser, int names, int terms) {
    List<String> m = new ArrayList<>();
    if (voices > 0) { m.add(L.t(NAME_VOICE)); m.add(L.t(FORGET_VOICES) + " (" + voices + ")"); }
    if (byUser) m.add(L.t(MEMO_BACK));
    if (names > 0) m.add(L.t(ADD_NAMES) + " (" + names + ")");
    if (terms > 0) m.add(L.t(FORGET_TERMS) + " (" + terms + ")");
    return m;
  }

  // ---- настройки -----------------------------------------------------------------------------

  /** Подпись «Хранить звук собеседников»: пишется ли и сколько уже записано. Без слов «запись» и «МБ на
   *  реплику»: человеку нужно знать, включено ли и сколько места занято. */
  static String audioLine(boolean on, long n, long bytes) {
    String size = L.f("%d %s, %s МБ", n, L.n(n, "реплика", "реплики", "реплик"), ModelStore.mb(bytes));
    if (on) return n == 0 ? L.t("включено · пока пусто") : L.f("включено · %s", size);
    return n == 0 ? L.t("выключено") : L.f("выключено · хранится с прошлого раза: %s", size);
  }
  /** Вопрос перед «Удалить звук, оставить текст»: сколько уйдёт и что останется. */
  static String audioDropText(long n, long bytes) {
    return L.f("Уйдёт записанный звук собеседников: %d %s, %s МБ. Текст разговоров останется. Вернуть звук будет нельзя.",
        n, L.n(n, "реплика", "реплики", "реплик"), ModelStore.mb(bytes));
  }
  /** Строка «Облако» в настройках: сколько ключей и как часто пересмотр. */
  static String cloudRow(int keys, int every) {
    return (keys == 0 ? L.t("ключа нет") : keys + " " + L.n(keys, "ключ", "ключа", "ключей"))
        + (every == 0 ? L.t(" · пересмотр по кнопке") : L.f(" · пересмотр каждые %d реплик", every));
  }

  /** Метка модуля и её вид (ok · wait · no · go). Облако файлов не держит — у него про ключи. */
  static String[] modPill(boolean on, long bytes, boolean onPhone, boolean loading, boolean wait, boolean busy, int keys) {
    if (bytes == 0) return new String[]{keys == 0 ? L.t("нужен ключ") : keys == 1 ? L.t("ключ есть") : L.f("ключей: %d", keys), keys == 0 ? "no" : "ok"};
    if (!on) return new String[]{L.t(onPhone ? "выключен · файлы на месте" : "выключен"), "no"};
    if (onPhone) return new String[]{L.t("установлено"), "ok"};
    if (loading && !wait) return new String[]{L.t("качается"), "go"};
    if (wait) return new String[]{L.t("ждёт Wi-Fi"), "wait"};
    return new String[]{L.t(busy ? "в очереди" : "не скачано"), "no"};
  }
  /** Метка модуля, чья модель лежит в самом APK (шумоподавление): качать нечего, включён — работает
   *  сам, когда вокруг шумно. */
  static String[] modPillApk(boolean on) { return on ? new String[]{L.t("при шуме"), "ok"} : new String[]{L.t("выключен"), "no"}; }
  /** Полоса загрузки — у включённого модуля, файлов которого ещё нет на телефоне. */
  static boolean modBar(boolean on, long bytes, boolean onPhone) { return on && bytes > 0 && !onPhone; }
  static String modBarLabel(long done, long bytes) {
    long d = Math.min(bytes, Math.max(0, done));
    return L.f("%d из %d МБ · %d %%", Math.round(d / 1e6), Math.round(bytes / 1e6), bytes > 0 ? d * 100 / bytes : 0);
  }
  static String modSize(long b) {
    return b == 0 ? "" : b >= 1_000_000_000L ? L.f("%.1f ГБ", b / 1e9).replace('.', ',')
         : b < 950_000 ? L.f("%.1f МБ", b / 1e6).replace('.', ',') : L.f("%d МБ", Math.round(b / 1e6));
  }
  /** Строка над модулями: текст и значок (dl — загрузка, ok — галочка, "" — без значка). */
  static String[] modelsSum(boolean checked, boolean check, boolean busy, boolean wait, long done, long total,
                            int coreMissing, int autoMissing, int core, long coreBytes, long autoBytes) {
    if (!checked) return new String[]{L.t("Проверяю файлы…"), ""};
    if (check) return new String[]{L.t("Проверяю файлы…"), "dl"};
    if (busy) return new String[]{wait ? L.f("Жду Wi-Fi · осталось %s МБ", ModelStore.mb(Math.max(0, total - done)))
                                       : L.f("Качаю · %s из %s МБ", ModelStore.mb(done), ModelStore.mb(total)), "dl"};
    if (coreMissing == 0 && autoMissing == 0) return new String[]{L.f("Все модели установлены · %d из %d", core, core), "ok"};
    if (coreMissing > 0) return new String[]{L.f("Обязательных нет: %d из %d · %s МБ", coreMissing, core, ModelStore.mb(coreBytes)), "dl"};
    return new String[]{L.f("Модулям не хватает %s МБ — докачается само", ModelStore.mb(autoBytes)), "dl"};
  }

  // ---- слова ---------------------------------------------------------------------------------

  /** Вкладки «Слов» (шаг 2, разбор 03.10): слова и связки, повторяющиеся фразы, известное. */
  static final int TAB_WORDS = 0, TAB_PHRASES = 1, TAB_KNOWN = 2;

  /** Кто сказал пример: собеседник с номером голоса, голос не определён (разговор назван — с его именем), вы. */
  static String exampleWho(boolean heard, String who, String chatName) {
    if (!heard) return L.t("вы сказали");
    if (who != null && who.matches("\\d{1,4}")) return L.f("Собеседник %s", who);
    return chatName == null || chatName.trim().isEmpty() ? L.t("голос не определён") : L.f("голос не определён · %s", chatName.trim());
  }

  /** Сводка голосов фразы: «Собеседник 1 ×2 · Собеседник 3 ×1 · вы ×1» — по убыванию, неизвестные голоса вместе. */
  static String voicesLine(Map<String, Integer> v) {
    List<Map.Entry<String, Integer>> l = new ArrayList<>(v.entrySet());
    l.sort((a, b) -> b.getValue() - a.getValue());
    StringBuilder s = new StringBuilder();
    for (Map.Entry<String, Integer> e : l) {
      String k = e.getKey();
      String who = Voices.OWNER.equals(k) ? L.t("вы") : k != null && k.matches("\\d{1,4}") ? L.f("Собеседник %s", k) : L.t("голос не определён");
      if (s.length() > 0) s.append(" · ");
      s.append(who).append(" ×").append(e.getValue());
    }
    return s.toString();
  }

  /** Где в предложении слово или связка карточки: {начало, конец} в исходной строке или null. Сравнение — по
   *  нормализованным словам (Phrasebook.norm: регистр, знаки, «tá» = «está»), выделяется исходный текст. */
  static int[] highlight(String sentence, String key) {
    if (sentence == null || key == null || key.isEmpty()) return null;
    String[] want = key.split(" ");
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\S+").matcher(sentence);
    List<int[]> span = new ArrayList<>(); List<String> norm = new ArrayList<>();
    while (m.find()) { span.add(new int[]{m.start(), m.end()}); norm.add(Phrasebook.norm(m.group())); }   // «pro» → «para o» ни с чем не совпадёт
    for (int i = 0; i + want.length <= norm.size(); i++) {
      boolean ok = true;
      for (int j = 0; j < want.length && ok; j++) ok = norm.get(i + j).equals(want[j]);
      if (!ok) continue;
      // знак препинания после слова не выделяется
      int start = span.get(i)[0], end = span.get(i + want.length - 1)[1];
      while (end > start && !Character.isLetterOrDigit(sentence.charAt(end - 1))) end--;
      while (start < end && !Character.isLetterOrDigit(sentence.charAt(start))) start++;
      return new int[]{start, end};
    }
    return null;
  }

  /** Метрика «Слов» за неделю (решение владельца 03.10): с первой попытки в «Скажите сами» и сказанное
   *  по-португальски самим в живом разговоре. */
  static String metricLine(int tries, int ok, int said) {
    if (tries == 0 && said == 0) return L.t("За неделю пока пусто: «Скажите сами» — в карточке слова");
    String s = L.f("За неделю: с первой попытки %d из %d", ok, tries);
    return said > 0 ? s + L.f(" · сами по-португальски: %d", said) : s;
  }
  /** Повторение (решение владельца 03.10): сколько пора повторить сегодня, а если ничего — когда следующее.
   *  inDays — через сколько календарных дней ближайший срок (Learn.daysUntil), 0 — сроков нет. */
  static String reviewLine(int due, int known, int inDays) {
    if (known == 0) return L.t("Отмечайте «знаю» в «Словах» и «Фразах» — известное будет повторяться здесь");
    if (due > 0) return L.f("Пора повторить: %d · на слух или вслух", due);
    if (inDays <= 0) return L.t("На сегодня всё");
    return L.f("На сегодня всё · следующее %s", when(inDays));
  }
  /** «завтра», «послезавтра», «через 5 дней». */
  static String when(int inDays) {
    return inDays <= 1 ? L.t("завтра") : inDays == 2 ? L.t("послезавтра") : L.f("через %d %s", inDays, L.n(inDays, "день", "дня", "дней"));
  }
  /** После ответа: когда спросим снова — «через 3 дня», «завтра». */
  static String reviewNext(boolean ok, int inDays) {
    return L.f(ok ? "получилось · снова %s" : "не получилось · снова %s", when(inDays));
  }

  /** Подсказка над вкладкой: что в ней и что делает касание. */
  static String cardsHint(int tab, int n, int min, boolean computed) {
    if (!computed) return L.t("Разбираю разговоры — в затишье, когда никто не говорит");
    if (tab == TAB_KNOWN) return n == 0 ? L.t("Известного пока нет — отмечайте долгим нажатием в «Словах» и «Фразах»")
                                        : L.f("Знаю: %d · касание — примеры, долгое — назад в изучение", n);
    String times = L.n(min, "раза", "раз", "раз");
    if (tab == TAB_PHRASES) return n == 0 ? L.f("Повторяющихся фраз пока нет: фраза попадает сюда, когда прозвучала не меньше %d %s", min, times)
                                          : L.f("Фразы, сказанные не меньше %d %s: %d · касание — кто и как сказал", min, times, n);
    return n == 0 ? L.f("Пока нечего показать: нужно, чтобы слово встретилось не меньше %d %s", min, times)
                  : L.f("Слова и связки от %d %s: %d · касание — живые примеры, долгое — «знаю»", min, L.n(min, "повтора", "повторов", "повторов"), n);
  }

  /** Порог «встречалось не меньше N раз» кнопками −/+: от 1 до 10, как у прежнего ползунка. */
  static int stepMin(int cur, int d) { int n = cur + d; return n < 1 || n > 10 ? cur : n; }
}
