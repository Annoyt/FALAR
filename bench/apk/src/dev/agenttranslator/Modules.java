package dev.agenttranslator;

import java.util.*;

/** Модули приложения: что человек выбирает при установке и в «Системе» → «Модули».
 *
 *  Базовый перевод речи (распознавание, перевод, словарь) обязателен. Остальное — по выбору:
 *  кому-то не нужно чтение снимков, кому-то уточнитель на 1,1 ГБ или облако, кто-то не хочет
 *  голоса. Выключенный модуль не качается, не поднимается в память, а его кнопки и пункты
 *  меню спрятаны — без ошибок «нет модели» на нажатие. Файлы модуля в манифесте моделей
 *  помечены полем module; включённый модуль, чьих файлов нет, докачивается сам в фоне.
 *
 *  Решения владельца от 28.09: при первой установке отмечено под телефон (озвучка, снимки,
 *  корпус фраз; уточнитель — если памяти не меньше 8 ГБ); выключение только прячет модуль, файлы
 *  остаются — удаляются отдельной кнопкой «удалить неиспользуемые модели»; обновившимся экран
 *  выбора не показывается — включено то, что уже скачано.
 *
 *  Без Android: проверяется на столе (bench/apk/test/ModulesTest.java). */
public final class Modules {
  private Modules() {}

  public static final String BASE = "base", TTS = "tts", OCR = "ocr", LLM = "llm", CLOUD = "cloud", SPEAKER = "speaker", CORPUS = "corpus";
  /** Шумоподавление нарезки (DenoiseGate): модель в самом APK, качать нечего. */
  public static final String DENOISE = "denoise";
  /** Выбираемые модули в порядке показа. */
  public static final List<String> CHOICE = Collections.unmodifiableList(Arrays.asList(TTS, OCR, LLM, CLOUD, SPEAKER, DENOISE, CORPUS));
  /** Модули, появившиеся после того, как выбор уже сохраняли, и включённые по умолчанию: у
   *  обновившегося они включаются сами (withNew). */
  static final List<String> NEW_ON = Collections.unmodifiableList(Arrays.asList(DENOISE));
  /** Какие модули знали версии, сохранявшие выбор без списка известных (до шумоподавления). */
  static final Set<String> KNOWN_BEFORE = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(TTS, OCR, LLM, CLOUD, SPEAKER, CORPUS)));
  /** Размер модели шумоподавления в APK (assets/gtcrn_simple.onnx) — для строки модуля. */
  public static final long DENOISE_BYTES = 535_638;
  /** Память, с которой уточнитель отмечен по умолчанию. Телефон «на 8 ГБ» отдаёт системе около
   *  7,4 ГБ (Redmi Note 10 Pro), «на 6 ГБ» — около 5,6. Порог между ними. На 8 ГБ уточнитель
   *  рядом с приложением помещается впритык (results/2026-09-28-memory.md), на 6 ГБ — нет. */
  public static final long LLM_RAM = 7_000_000_000L;

  public static String title(String m) {
    switch (m) {
      case BASE: return "Перевод речи";
      case TTS: return "🔊 Озвучка перевода";
      case OCR: return "📷 Чтение снимков";
      case LLM: return "🧠 Уточнитель перевода";
      case CLOUD: return "☁ Облако";
      case SPEAKER: return "🎤 Отпечаток голоса";
      case DENOISE: return "🔉 Шумоподавление";
      case CORPUS: return "📚 Корпус фраз";
      default: return m;
    }
  }

  /** Что даёт модуль и что пропадёт без него — строкой под переключателем. */
  public static String what(String m) {
    switch (m) {
      case BASE: return "распознавание речи, перевод pt ↔ ru и словарь — обязательно";
      case TTS: return "перевод звучит голосом — в наушниках или из динамика; без неё — только на экране";
      case OCR: return "вывеска, меню, скрин: текст читается на телефоне, перевод — поверх фото";
      case LLM: return "переводит заново с учётом разговора: род, отсылки, термины; нужен сильный телефон";
      case CLOUD: return "пересмотр разговора бесплатными моделями OpenRouter, названия разговоров; нужен ключ";
      case SPEAKER: return "узнаёт, кто говорит: авто-направление и разделение говорящих";
      case DENOISE: return "теряется меньше фраз и в тишине, и в шуме; сильный шумодав включается сам, когда вокруг шумно";
      case CORPUS: return "190 тыс. готовых пар фраз из Tatoeba: частые фразы переводятся мгновенно";
      default: return "";
    }
  }

  /** Выбор по умолчанию для новой установки: ram — вся память телефона, байт. */
  public static Set<String> defaults(long ram) {
    Set<String> s = new LinkedHashSet<>(Arrays.asList(TTS, OCR, DENOISE, CORPUS));
    if (ram >= LLM_RAM) s.add(LLM);
    return s;
  }

  /** Обновились с версии без модулей: включено то, что уже скачано (installed — модули, чьи файлы
   *  на месте), облако — если есть ключ. Чтение снимков — новый модуль на 18 МБ: включён сразу,
   *  иначе у обновившегося новая возможность оказалась бы выключенной без его выбора. */
  public static Set<String> upgraded(Set<String> installed, boolean cloudKey) {
    Set<String> s = new LinkedHashSet<>();
    for (String m : CHOICE) if (installed.contains(m) && !m.equals(CLOUD)) s.add(m);
    s.add(OCR);
    s.addAll(NEW_ON);
    if (cloudKey) s.add(CLOUD);
    return s;
  }

  /** Выбор, сохранённый до появления нового модуля, плюс сам модуль, если он включён по умолчанию
   *  (NEW_ON): иначе у обновившегося новая возможность оказалась бы выключенной без его выбора —
   *  так в 0.25 включили чтение снимков. known — модули, известные версии, сохранившей выбор
   *  (null — версия не записывала их: всё до шумоподавления). Выключенное потом самим человеком не
   *  включается: он сохранял выбор, уже зная о модуле. */
  public static Set<String> withNew(Set<String> saved, Set<String> known) {
    Set<String> k = known != null ? known : KNOWN_BEFORE, s = new LinkedHashSet<>(saved);
    for (String m : NEW_ON) if (!k.contains(m)) s.add(m);
    return s;
  }

  public static Set<String> parse(String s) {
    Set<String> out = new LinkedHashSet<>();
    if (s == null) return out;
    for (String x : s.split(",")) { x = x.trim(); if (CHOICE.contains(x)) out.add(x); }
    return out;
  }

  public static String join(Set<String> s) {
    StringBuilder b = new StringBuilder();
    for (String m : CHOICE) if (s.contains(m)) { if (b.length() > 0) b.append(','); b.append(m); }
    return b.toString();
  }

  /** Кнопка 📷 есть, если снимок есть чем прочитать: на телефоне или в облаке. */
  public static boolean photo(Set<String> on) { return on.contains(OCR) || on.contains(CLOUD); }
  /** Кнопка «Улучшить» есть, если есть чем уточнять: уточнителем или облаком. */
  public static boolean better(Set<String> on) { return on.contains(LLM) || on.contains(CLOUD); }
}
