package dev.agenttranslator;

import java.util.*;

/** Карточки «Слов» и «Фраз» (Cards): связка вместо слова и когда — нет; примеры — живой голос вперёд,
 *  разные люди, перевод именно этого предложения; фразы разными голосами; свои фразы — по сказанному
 *  по-русски. Запуск: bash bench/apk/test.sh. */
public class CardsTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }
  static final Set<String> STOP = new HashSet<>(Arrays.asList("a", "o", "de", "um", "uma", "que", "e", "eu", "para", "não", "é", "está", "em", "no", "na", "se", "do", "da"));

  static Cards.Turn heard(long chat, long at, String pt, String ru, String who, String audio) { return new Cards.Turn(chat, "чат " + chat, at, true, pt, ru, who, audio, "cloud"); }
  static Cards.Turn own(long chat, long at, String pt, String ru, String by) { return new Cards.Turn(chat, "чат " + chat, at, false, pt, ru, null, null, by); }
  static Cards.Card find(List<Cards.Card> l, String key) { for (Cards.Card c : l) if (c.key.equals(key)) return c; return null; }

  public static int run() {
    fails = 0; checks = 0;
    List<Cards.Turn> t = new ArrayList<>();
    // «pouquinho» — всегда в «um pouquinho», в двух разговорах
    t.add(heard(1, 10, "Espera um pouquinho.", "Подождите немного.", "1", "1_10.wav"));
    t.add(heard(1, 11, "Fala um pouquinho mais devagar, por favor.", "Говорите чуть медленнее, пожалуйста.", "2", ""));
    t.add(heard(2, 20, "Só um pouquinho.", "Совсем чуть-чуть.", "", "2_20.wav"));
    // «luz» — всегда в «conta de luz», но только в одном разговоре: термин темы, остаётся словом
    t.add(heard(1, 12, "A conta de luz chegou hoje.", "Счёт за свет пришёл сегодня.", "1", ""));
    t.add(heard(1, 13, "Pague a conta de luz.", "Оплатите счёт за свет.", "1", ""));
    // одна и та же фраза двумя голосами, плюс свои фразы — по русскому исходнику
    t.add(heard(2, 21, "Tudo bem?", "Всё хорошо?", "1", "2_21.wav"));
    t.add(heard(2, 22, "Tudo bem?", "Как дела?", "2", ""));
    t.add(own(3, 30, "Espere.", "Подождите.", "cloud"));
    t.add(own(3, 31, "Aguarde, por favor.", "Подождите.", "llm"));
    t.add(own(3, 32, "Aguarde, por favor.", "Подождите.", "llm"));
    t.add(own(4, 40, "Espere aqui.", "Подождите здесь.", "user"));
    // реплика из двух предложений: перевод тоже из двух — пример берёт своё; из одного — перевода нет
    t.add(heard(4, 41, "Bom dia. Vou te mandar a foto amanhã.", "Доброе утро. Я пришлю вам фото завтра.", "1", "4_41.wav"));
    t.add(heard(4, 42, "Bom dia. Vou te mandar o endereço.", "Доброе утро, я пришлю вам адрес.", "1", ""));
    Cards.Result r = Cards.build(t, STOP, 2, Collections.emptySet());

    // R1 связка вместо слова: слово почти всегда в ней и она встречалась в двух разговорах
    Cards.Card ch = find(r.words, "um pouquinho");
    ok(ch != null && ch.kind == Cards.CHUNK && ch.n == 3, "R1 «pouquinho» показан связкой «um pouquinho» ×3");
    ok(find(r.words, "pouquinho") == null, "R1 само слово отдельно не показано");
    // R2 термин одного разговора — словом; связка на служебном слове («conta de») не выбирается
    Cards.Card parto = find(r.words, "luz");
    ok(parto != null && parto.kind == Cards.WORD, "R2 «luz» из одного разговора — словом");
    ok(find(r.words, "conta de") == null, "R2 обрыв «conta de» не карточка");
    // R3 примеры: живой голос (со звуком) первым, затем другие люди
    eq(ch.ex.size(), 3, "R3 у связки три примера");
    ok(ch.ex.get(0).live() && ch.ex.get(1).live(), "R3 первыми — примеры с живым голосом");
    ok(!ch.ex.get(2).live() && ch.ex.get(2).t.heard, "R3 дальше — речь собеседника без звука");
    Set<String> v = new HashSet<>(); for (Cards.Example e : ch.ex) v.add(Cards.voice(e.t));
    eq(v.size(), 3, "R3 три разных человека");
    // R4 перевод предложения, а не всей реплики
    Cards.Card mandar = find(r.words, "mandar");
    ok(mandar != null && !mandar.ex.isEmpty(), "R4 «mandar» есть");
    Cards.Example me = mandar.ex.get(0);
    eq(me.pt, "Vou te mandar a foto amanhã.", "R4 пример — само предложение, а не вся реплика");
    eq(me.ru, "Я пришлю вам фото завтра.", "R4 перевод — того же предложения");
    eq(me.sent + "/" + me.sents, "1/2", "R4 второе из двух предложений реплики");
    Cards.Example me2 = mandar.ex.get(1);
    eq(me2.ru, null, "R4 перевод реплики не делится так же — перевода предложения нет (переведёт фон)");
    // R5 фраза двумя голосами: счёт по голосам, пример от каждого
    Cards.Card tb = find(r.phrases, "tudo bem");
    ok(tb != null && tb.n == 2, "R5 «Tudo bem?» — фраза ×2");
    eq(tb == null ? null : tb.voices.toString(), "{1=1, 2=1}", "R5 голоса: собеседник 1 и 2");
    eq(tb == null ? 0 : tb.ex.size(), 2, "R5 два примера — оба голоса, хоть текст один");
    ok(tb != null && tb.ex.get(0).live(), "R5 первым — живой голос");
    ok(find(r.words, "tudo bem") == null, "R5 фраза не повторяется связкой в «Словах»");
    // R6 свои фразы — по сказанному по-русски; на карточке — самый частый перевод, правка человека главнее
    Cards.Card wait = find(r.phrases, "ru:подождите");
    ok(wait != null && wait.n == 3, "R6 «Подождите.» — одна фраза ×3, хоть переводы разные («Espere.», «Aguarde, por favor.»)");
    eq(wait == null ? null : wait.shown, "Aguarde, por favor.", "R6 на карточке — самый частый перевод, в нём от двух слов");
    Cards.Card waitHere = find(r.phrases, "ru:подождите здесь");
    ok(waitHere == null, "R6 фраза, сказанная раз, — не во «Фразах» (порог 2)");
    List<Cards.Turn> t2 = new ArrayList<>(t);
    t2.add(own(5, 50, "Fique aqui.", "Подождите здесь.", "cloud"));
    t2.add(own(5, 51, "Fique aqui.", "Подождите здесь.", "cloud"));
    Cards.Result r2 = Cards.build(t2, STOP, 2, Collections.emptySet());
    Cards.Card wh = find(r2.phrases, "ru:подождите здесь");
    ok(wh != null && wh.n == 3, "R6 «Подождите здесь» — одна фраза ×3 при двух разных переводах");
    eq(wh == null ? null : wh.shown, "Espere aqui.", "R6 правка человека главнее самого частого перевода");
    eq(wh == null ? null : wh.voices.toString(), "{owner=3}", "R6 голос — вы");
    // R7 порядок: чаще — выше
    ok(r.words.indexOf(ch) < r.words.indexOf(parto), "R7 связка ×3 выше слова ×2");

    System.out.println(fails == 0 ? "Cards: " + checks + " проверок, все прошли" : "Cards: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
