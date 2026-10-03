package dev.agenttranslator;

import java.util.*;

/** Карточки «Слов» и «Фраз» из разговоров — шаг 2 переделки «Слов» (PLAN §8, разбор с владельцем 03.10:
 *  «слова, вырванные из контекста, невозможно запоминать»). Частота выбирает, **что** учить — слово, связку
 *  или повторяющуюся фразу, — а тело карточки — **настоящие предложения**, где это прозвучало: живой голос
 *  собеседника (звук реплики, Chats поле `audio`) и перевод **этого** предложения, а не первые 90 знаков
 *  перевода всей реплики, как было.
 *
 *  Чистая функция над репликами: без Android и без диска, проверяется на столе (CardsTest). Считается в
 *  затишье (TranslatorService.learnIo, quietFor) — правило владельца 03.10: «отбор фраз в словарь не должен
 *  тормозить приложение, а проходить фоном, вне времени активной деятельности».
 *
 *  Источник (решение владельца): речь собеседников и свои реплики **только с улучшенным переводом** — сырой
 *  машинный перевод как образец учит ошибке; это отбирает Learn. Реплики стенда сюда не попадают. */
final class Cards {
  static final int WORD = 0, CHUNK = 1, PHRASE = 2;
  /** Связка вместо слова: слово почти всегда идёт в ней (≥ 60 % встреч), связка встречалась в двух разговорах
   *  и больше (термин одной темы остаётся словом) и не кончается служебным словом («conta de» — обрывок,
   *  «conta de luz» — связка). Проверено на разговорах владельца 03.10: из 419 частых слов 47 стали 43
   *  связками — бытовыми и с артиклем, по которому виден род. */
  static final double ABSORB = 0.6;
  static final int MIN_CHUNK = 2, CHUNK_CHATS = 2, MAX_EX = 3;
  /** Карточек слов с примерами — не больше: экран показывает до 200, редкие слова без примеров не нужны. */
  static final int MAX_CARDS = 300;
  /** Предложение-пример удобно учить в 3–12 слов: короче — без контекста, длиннее — стена текста. */
  static final int EX_MIN = 3, EX_MAX = 12;
  static final String SENT = "(?<=[.!?…])\\s+(?=\\S)|\\s*\\n+\\s*";

  /** Реплика для разбора. heard — сказал собеседник (pt — его речь, ru — перевод); иначе своя реплика
   *  (pt — улучшенный перевод, ru — что вы сказали). who — номер голоса разговора или ""; by — кто улучшил. */
  static final class Turn {
    final long chat, at; final String chatName; final boolean heard; final String pt, ru, who, audio, by;
    /** Где реплика делится на предложения, мс от начала её звука (Chats поле `cuts`); null — разреза нет. */
    int[] cuts;
    Turn(long chat, String chatName, long at, boolean heard, String pt, String ru, String who, String audio, String by) {
      this.chat = chat; this.chatName = chatName == null ? "" : chatName; this.at = at; this.heard = heard;
      this.pt = pt == null ? "" : pt; this.ru = ru == null ? "" : ru; this.who = who == null ? "" : who;
      this.audio = audio == null ? "" : audio; this.by = by == null ? "" : by;
    }
  }

  /** Пример: одно предложение реплики. ru — его перевод, если перевод реплики делится на столько же
   *  предложений; иначе null — переведёт фон (Learn.sentRu). sent из sents — какое оно в реплике: по нему
   *  ▶ режет кусочек или играет реплику целиком. */
  static final class Example {
    final Turn t; final String pt, ru; final int sent, sents;
    Example(Turn t, String pt, String ru, int sent, int sents) { this.t = t; this.pt = pt; this.ru = ru; this.sent = sent; this.sents = sents; }
    boolean live() { return t.heard && !t.audio.isEmpty(); }
  }

  static final class Card {
    final String key; final int kind; final List<Example> ex = new ArrayList<>();
    /** Как показать: связка и слово — как ключ; фраза — самой частой формой. */
    String shown; int n;
    /** «Фразы»: сколько раз каким голосом («2» — собеседник 2, "" — не определён, owner — вы). */
    final Map<String, Integer> voices = new LinkedHashMap<>();
    Card(String key, int kind) { this.key = key; this.kind = kind; this.shown = key; }
  }

  static final class Result {
    final List<Card> words = new ArrayList<>(), phrases = new ArrayList<>();
    int turns, sentences;
  }

  /** Предложение реплики с нормализованными словами (Phrasebook.norm: регистр, знаки, «tá» = «está»). */
  static final class Sent {
    final Turn t; final String text, ru, key; final String[] tok; final int i, n;
    Sent(Turn t, String text, String ru, String key, int i, int n) { this.t = t; this.text = text; this.ru = ru; this.key = key; this.tok = key.split(" "); this.i = i; this.n = n; }
  }

  static String[] split(String s) {
    List<String> out = new ArrayList<>();
    for (String p : s.trim().split(SENT)) if (!p.trim().isEmpty()) out.add(p.trim());
    return out.toArray(new String[0]);
  }

  /** Значимое слово: от трёх букв, не служебное (верхушка частотности корпуса, как в Learn), только буквы
   *  (дефис и апостроф внутри — можно). */
  static boolean content(String w, Set<String> stop) {
    if (w.length() < 3 || stop.contains(w) || !Character.isLetter(w.charAt(0))) return false;
    for (int k = 0; k < w.length(); k++) { char c = w.charAt(k); if (!Character.isLetter(c) && c != '-' && c != '\'') return false; }
    return true;
  }

  /** keep — ключи, которым нужны примеры при любой частоте (отмеченные «Знаю»: по ним идёт повторение). */
  static Result build(List<Turn> turns, Set<String> stop, int minPhrase, Set<String> keep) {
    Result r = new Result(); r.turns = turns.size();
    List<Sent> ss = new ArrayList<>();
    for (Turn t : turns) {
      String[] ps = split(t.pt), rs = split(t.ru);
      for (int i = 0; i < ps.length; i++) {
        String key = Phrasebook.norm(ps[i]);
        if (key.isEmpty()) continue;
        ss.add(new Sent(t, ps[i], ps.length == rs.length ? rs[i] : null, key, i, ps.length));
      }
    }
    r.sentences = ss.size();
    Map<String, List<Sent>> sentsOf = new HashMap<>();             // слово → предложения с ним (без повторов)
    for (Sent s : ss) for (String w : new LinkedHashSet<>(Arrays.asList(s.tok))) sentsOf.computeIfAbsent(w, x -> new ArrayList<>()).add(s);

    // Слова — каждая встреча; связки по 2–3 слова — раз на предложение, с разговорами, где встречались.
    Map<String, Integer> wc = new HashMap<>(), cc = new HashMap<>();
    Map<String, Set<Long>> cchats = new HashMap<>();
    Map<String, List<String>> chunksOf = new HashMap<>();          // слово → связки, где оно есть
    for (Sent s : ss) {
      for (String w : s.tok) if (content(w, stop)) wc.merge(w, 1, Integer::sum);
      Set<String> seen = new HashSet<>();
      for (int k = 2; k <= 3; k++)
        for (int i = 0; i + k <= s.tok.length; i++) {
          boolean any = false;
          for (int j = i; j < i + k; j++) any |= content(s.tok[j], stop);
          if (!any || stop.contains(s.tok[i + k - 1])) continue;     // связка без значимого слова или на служебном — обрывок
          String g = String.join(" ", Arrays.copyOfRange(s.tok, i, i + k));
          if (!seen.add(g)) continue;
          if (cc.merge(g, 1, Integer::sum) == 1)
            for (int j = i; j < i + k; j++) if (content(s.tok[j], stop)) chunksOf.computeIfAbsent(s.tok[j], x -> new ArrayList<>()).add(g);
          cchats.computeIfAbsent(g, x -> new HashSet<>()).add(s.t.chat);
        }
    }

    // Фразы: целые предложения от двух слов. Свои — по тому, что вы сказали по-русски: один и тот же
    // русский переводится то «Espere», то «Aguarde», и по португальскому повторы раскалывались бы.
    Map<String, Card> ph = new LinkedHashMap<>();
    Map<String, Map<String, Integer>> forms = new HashMap<>();
    for (Sent s : ss) {
      String key = phraseKey(s);
      if (key == null) continue;
      Card c = ph.computeIfAbsent(key, k -> new Card(k, PHRASE));
      c.n++;
      c.voices.merge(s.t.heard ? s.t.who : Voices.OWNER, 1, Integer::sum);
      // форма для показа: самая частая; правка человека — главнее
      forms.computeIfAbsent(key, k -> new HashMap<>()).merge(s.text, "user".equals(s.t.by) ? 1000 : 1, Integer::sum);
    }
    Map<String, List<Sent>> byPhrase = new HashMap<>();
    for (Sent s : ss) {
      String key = phraseKey(s);
      if (key != null && ph.containsKey(key) && ph.get(key).n >= minPhrase) byPhrase.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
    }
    for (Card c : ph.values()) {
      if (c.n < minPhrase) continue;
      c.shown = Collections.max(forms.get(c.key).entrySet(), Map.Entry.comparingByValue()).getKey();
      // учат португальскую форму: в ней от двух слов («Подождите.» → «Aguarde, por favor.» — фраза, «Espere.» — слово)
      if (Phrasebook.norm(c.shown).split(" ").length < 2) continue;
      c.ex.addAll(pick(byPhrase.get(c.key), MAX_EX, false));   // одна и та же фраза разными голосами
      r.phrases.add(c);
    }

    // Слова и связки: слово, почти всегда стоящее в одной связке, показывается связкой.
    Map<String, Card> wcards = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> e : wc.entrySet()) {
      String w = e.getKey(), best = null; int n = e.getValue();
      for (String g : chunksOf.getOrDefault(w, Collections.emptyList())) {
        int c = cc.get(g);
        if (c < Math.max(MIN_CHUNK, ABSORB * n) || cchats.get(g).size() < CHUNK_CHATS) continue;
        if (best == null || c > cc.get(best) || c == cc.get(best) && g.length() > best.length()) best = g;
      }
      String key = best == null ? w : best;
      if (best != null && ph.containsKey(key) && ph.get(key).n >= minPhrase) continue;   // целая фраза — во «Фразах»
      Card c = wcards.get(key);
      if (c == null) { c = new Card(key, best == null ? WORD : CHUNK); c.n = best == null ? n : cc.get(best); wcards.put(key, c); }
    }
    Comparator<Card> byN = (a, b) -> a.n != b.n ? b.n - a.n : a.key.compareTo(b.key);
    List<Card> all = new ArrayList<>(wcards.values()); all.sort(byN);
    for (int i = 0; i < all.size(); i++) {
      Card c = all.get(i);
      if (i >= MAX_CARDS && (keep == null || !keep.contains(c.key))) continue;
      String[] g = c.key.split(" "); List<Sent> cand = new ArrayList<>();
      for (Sent s : sentsOf.getOrDefault(g[0], Collections.emptyList())) if (g.length == 1 || contains(s.tok, g)) cand.add(s);
      c.ex.addAll(pick(cand, MAX_EX, true));
      r.words.add(c);
    }
    r.phrases.sort(byN);
    return r;
  }

  /** Ключ фразы: речь собеседника — по португальскому (от двух слов); своя — по сказанному по-русски, только
   *  если перевод реплики делится на предложения так же (иначе неизвестно, какое русское — к какому). */
  static String phraseKey(Sent s) {
    if (s.t.heard) return s.tok.length < 2 ? null : s.key;
    if (s.ru == null) return null;
    String k = Phrasebook.norm(s.ru);
    return k.isEmpty() ? null : "ru:" + k;
  }

  static boolean contains(String[] tok, String[] g) {
    for (int i = 0; i + g.length <= tok.length; i++) {
      boolean ok = true;
      for (int j = 0; j < g.length && ok; j++) ok = tok[i + j].equals(g[j]);
      if (ok) return true;
    }
    return false;
  }

  /** Лучшие примеры: живой голос вперёд (ради него владелец и хранит звук), потом речь собеседника, потом
   *  удобная длина 3–12 слов, потом свежие. Разные люди: следующий пример — от другого голоса, а где голос не
   *  определён — из другого разговора; не нашлось — какой есть. */
  static List<Example> pick(List<Sent> cand, int max, boolean distinctText) {
    List<Sent> l = new ArrayList<>(cand == null ? Collections.<Sent>emptyList() : cand);
    l.sort((a, b) -> {
      int d = score(b) - score(a);
      return d != 0 ? d : Long.compare(b.t.at, a.t.at);
    });
    List<Example> out = new ArrayList<>(); Set<String> who = new HashSet<>(), texts = new HashSet<>();
    Set<Sent> used = Collections.newSetFromMap(new IdentityHashMap<>());
    for (int pass = 0; pass < 2 && out.size() < max; pass++)
      for (Sent s : l) {
        if (out.size() >= max) break;
        String v = voice(s.t);
        if (used.contains(s) || distinctText && texts.contains(s.key) || pass == 0 && who.contains(v)) continue;
        out.add(new Example(s.t, s.text, s.ru, s.i, s.n)); used.add(s); who.add(v); texts.add(s.key);
      }
    return out;
  }
  /** Кто сказал — для разнообразия примеров: голос разговора, «вы» или «не определён в разговоре N». */
  static String voice(Turn t) { return !t.heard ? Voices.OWNER : !t.who.isEmpty() ? t.chat + "/" + t.who : "?" + t.chat; }
  static int score(Sent s) {
    int len = s.tok.length;
    return (s.t.heard && !s.t.audio.isEmpty() ? 8 : 0) + (s.t.heard ? 4 : 0) + (len >= EX_MIN && len <= EX_MAX ? 2 : 0) + (s.ru != null ? 1 : 0);
  }
}
