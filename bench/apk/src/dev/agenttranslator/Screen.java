package dev.agenttranslator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    return total > 0 && !what.contains("%") ? what + " · " + Math.min(done + 1, total) + " из " + total : what;
  }

  /** Сколько шли этапы загрузки движков в прошлый раз (load_ms: «распознавание,перевод,озвучка,
   *  остальное», мс). Нет замера или он испорчен — Redmi Note 10 Pro, 30.09: 3,7 · 3,7 · 3,0 · 1,5 с.
   *  Без озвучки (n = 3) третьего этапа нет. */
  static long[] loadExpect(String loadMs, int n) {
    long[] d = {3700, 3700, 3000, 1500};
    try {
      String[] x = (loadMs == null ? "" : loadMs).split(",");
      if (x.length == 4) for (int i = 0; i < 4; i++) { long v = Long.parseLong(x[i].trim()); if (v > 0) d[i] = v; }
    } catch (RuntimeException ignore) {}
    return n == 4 ? d : new long[]{d[0], d[1], d[3]};
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
    return "Загружаю модели · " + what + " · ещё ≈ " + Math.max(1, Math.round(leftMs / 1000.0)) + " с";
  }

  /** Облако — один запрос, точного хода нет: отрезок идёт по времени, сколько обычно отвечает эта
   *  модель (нет замера — 20 с), и не доходит до конца, пока ответа нет. */
  static long cloudTypical(long modelMs) { return modelMs > 0 ? modelMs : 20000; }
  static float cloudFrac(long ms, long typical) { return Math.min(0.95f, ms / (float) Math.max(1, typical)); }
  static String cloudCaption(long ms, long typical) {
    long left = typical - ms;
    return "Улучшаю в облаке · " + (left >= 1000 ? "ещё ≈ " + Math.round(left / 1000.0) + " с" : "дольше обычного · " + ms / 1000 + " с");
  }

  // ---- шапка разговора ----------------------------------------------------------------------

  /** Строка под названием разговора. Держат крупный текст — только это; спрятанная транскрипция,
   *  почему «Улучшить» серая (mode — improveMode(), null — не спрашивали) и тема — через «·». Пока
   *  с разговором идут загрузки (busy), строка — их ход. */
  static String hint(String base, boolean holdingRead, boolean cribHidden, String mode, String topic, String title, String busy) {
    if (busy != null) return busy;
    String h = base == null ? "" : base;
    if (holdingRead) h = "читаете вслух · микрофон не слушает";
    else if (cribHidden) h = join(h, "транскрипция скрыта · касание вернёт");
    if (mode != null && !mode.equals("cloud") && !mode.equals("local")) h = join(h, "улучшить нельзя: " + mode);
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
      b.append(who == null || who.isEmpty() ? "Кто говорит — пока не ясно." : who);
      if (speakerModule) b.append("\nГолосов в разговоре нет: пусть каждый скажет фразу кнопкой FALAR — тогда слушание переводит только их.");
    }
    if (names != null && !names.isEmpty()) {
      b.append("\nИмена: ");
      for (int k = 0; k < names.size(); k++) {
        String[] n = names.get(k); b.append(k > 0 ? ", " : "").append(n[0]);
        if (n.length > 1 && !n[1].isEmpty() && !n[1].equalsIgnoreCase(n[0])) b.append(" (").append(n[1]).append(')');
      }
    }
    return b.append("\n\nКлючевые детали").append(memo == null || memo.isEmpty() ? " — пока нет." : ":\n" + memo).toString();
  }
  /** Пункты «ещё…» окна памяти: что есть, то и можно сделать. */
  static List<String> memoMore(boolean byUser, int names, int terms) { return memoMore(0, byUser, names, terms); }
  /** Пункты голосов разговора — первыми: назвать собеседника, забыть голоса (слепок — биометрия, стереть
   *  его можно всегда). */
  static final String NAME_VOICE = "назвать собеседника…", FORGET_VOICES = "забыть голоса разговора";
  static List<String> memoMore(int voices, boolean byUser, int names, int terms) {
    List<String> m = new ArrayList<>();
    if (voices > 0) { m.add(NAME_VOICE); m.add(FORGET_VOICES + " (" + voices + ")"); }
    if (byUser) m.add("вернуть память автоматике");
    if (names > 0) m.add("добавить имена в свои слова (" + names + ")");
    if (terms > 0) m.add("забыть подсказки разговора (" + terms + ")");
    return m;
  }

  // ---- настройки -----------------------------------------------------------------------------

  /** Слово после числа: 1 ключ, 2 ключа, 5 ключей, 21 ключ, 12 ключей. */
  static String plural(int n, String one, String few, String many) {
    int a = Math.abs(n) % 100, b = a % 10;
    return a >= 11 && a <= 14 ? many : b == 1 ? one : b >= 2 && b <= 4 ? few : many;
  }
  /** Строка «Облако» в настройках: сколько ключей и как часто пересмотр. */
  static String cloudRow(int keys, int every) {
    return (keys == 0 ? "ключа нет" : keys + " " + plural(keys, "ключ", "ключа", "ключей"))
        + " · пересмотр " + (every == 0 ? "по кнопке" : "каждые " + every + " реплик");
  }

  /** Метка модуля и её вид (ok · wait · no · go). Облако файлов не держит — у него про ключи. */
  static String[] modPill(boolean on, long bytes, boolean onPhone, boolean loading, boolean wait, boolean busy, int keys) {
    if (bytes == 0) return new String[]{keys == 0 ? "нужен ключ" : keys == 1 ? "ключ есть" : "ключей: " + keys, keys == 0 ? "no" : "ok"};
    if (!on) return new String[]{onPhone ? "выключен · файлы на месте" : "выключен", "no"};
    if (onPhone) return new String[]{"установлено", "ok"};
    if (loading && !wait) return new String[]{"качается", "go"};
    if (wait) return new String[]{"ждёт Wi-Fi", "wait"};
    return new String[]{busy ? "в очереди" : "не скачано", "no"};
  }
  /** Метка модуля, чья модель лежит в самом APK (шумоподавление): качать нечего, включён — работает
   *  сам, когда вокруг шумно. */
  static String[] modPillApk(boolean on) { return on ? new String[]{"при шуме", "ok"} : new String[]{"выключен", "no"}; }
  /** Полоса загрузки — у включённого модуля, файлов которого ещё нет на телефоне. */
  static boolean modBar(boolean on, long bytes, boolean onPhone) { return on && bytes > 0 && !onPhone; }
  static String modBarLabel(long done, long bytes) {
    long d = Math.min(bytes, Math.max(0, done));
    return Math.round(d / 1e6) + " из " + Math.round(bytes / 1e6) + " МБ · " + (bytes > 0 ? d * 100 / bytes : 0) + " %";
  }
  static String modSize(long b) {
    return b == 0 ? "" : b >= 1_000_000_000L ? String.format(Locale.ROOT, "%.1f ГБ", b / 1e9).replace('.', ',')
         : b < 950_000 ? String.format(Locale.ROOT, "%.1f МБ", b / 1e6).replace('.', ',') : Math.round(b / 1e6) + " МБ";
  }
  /** Строка над модулями: текст и значок (dl — загрузка, ok — галочка, "" — без значка). */
  static String[] modelsSum(boolean checked, boolean check, boolean busy, boolean wait, long done, long total,
                            int coreMissing, int autoMissing, int core, long coreBytes, long autoBytes) {
    if (!checked) return new String[]{"Проверяю файлы…", ""};
    if (check) return new String[]{"Проверяю файлы…", "dl"};
    if (busy) return new String[]{wait ? "Жду Wi-Fi · осталось " + ModelStore.mb(Math.max(0, total - done)) + " МБ"
                                       : "Качаю · " + ModelStore.mb(done) + " из " + ModelStore.mb(total) + " МБ", "dl"};
    if (coreMissing == 0 && autoMissing == 0) return new String[]{"Все модели установлены · " + core + " из " + core, "ok"};
    if (coreMissing > 0) return new String[]{"Обязательных нет: " + coreMissing + " из " + core + " · " + ModelStore.mb(coreBytes) + " МБ", "dl"};
    return new String[]{"Модулям не хватает " + ModelStore.mb(autoBytes) + " МБ — докачается само", "dl"};
  }

  // ---- слова ---------------------------------------------------------------------------------

  /** Порог «встречалось не меньше N раз» кнопками −/+: от 1 до 10, как у прежнего ползунка. */
  static int stepMin(int cur, int d) { int n = cur + d; return n < 1 || n > 10 ? cur : n; }
  /** Подсказка над словами: сколько их и что делает касание (произносит — только с озвучкой). */
  static String wordsHint(boolean known, int n, int min, boolean say) {
    String tap = say ? "нажатие произносит, " : "";
    if (known) return n == 0 ? "Известных слов пока нет — отмечайте их долгим нажатием в «Учу»"
                             : "Знаю: " + n + " " + plural(n, "слово", "слова", "слов") + " · " + tap + "долгое возвращает в изучение";
    return n == 0 ? "Пока нечего показать: нужно, чтобы слово встретилось не меньше " + min + " " + plural(min, "раза", "раз", "раз")
                  : "Слов от " + min + " " + plural(min, "повтора", "повторов", "повторов") + ": " + n + " · " + tap + "долгое — «знаю»";
  }
}
