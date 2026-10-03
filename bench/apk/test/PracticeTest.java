package dev.agenttranslator;

import java.util.*;

/** «Скажите сами» (Practice): что говорить, какие слова распознаны, вердикт по слову карточки.
 *  Запуск: bash bench/apk/test.sh. */
public class PracticeTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static void eq(Object a, Object b, String what) { ok(Objects.equals(a, b), what + ": " + a + " ≠ " + b); }
  static String marks(Practice.Result r) { StringBuilder b = new StringBuilder(); for (boolean x : r.ok) b.append(x ? '+' : '-'); return b.toString(); }

  public static int run() {
    fails = 0; checks = 0;
    // P1 что говорить: короткое предложение — целиком, длинное — ±3 слова вокруг слова карточки
    eq(Practice.target("Espera um pouquinho, já volto.", "um pouquinho"), "Espera um pouquinho, já volto.", "P1 короткое — целиком");
    String longS = "Ontem à noite a gente foi até a praia e ficou um pouquinho olhando o mar com os amigos.";
    eq(Practice.target(longS, "um pouquinho"), "praia e ficou um pouquinho olhando o mar", "P1 длинное — ±3 слова вокруг связки");
    eq(Practice.target("Bom dia a todos vocês que vieram hoje aqui na nossa reunião de domingo!", "bom"), "Bom dia a todos", "P1 слово в начале — окно с начала");
    // P2 сверка: регистр, знаки, «tá» = «está», лишние слова не мешают
    Practice.Result r = Practice.check("Espera um pouquinho, já volto.", "espera um pouquinho já volto");
    eq(marks(r), "+++++", "P2 всё распознано");
    eq(r.share(), 1.0, "P2 доля — 1");
    r = Practice.check("Espera um pouquinho, já volto.", "espera um pokinho já volto");
    eq(marks(r), "++-++", "P2 искажённое слово — красное");
    r = Practice.check("Tá bom, obrigado.", "está bom muito obrigado");
    eq(marks(r), "+++", "P2 «tá» = «está», лишнее «muito» не штрафует");
    r = Practice.check("Vou te mandar a foto.", "");
    eq(marks(r), "-----", "P2 ничего не распознано — всё красное");
    r = Practice.check("Onde fica a farmácia?", "onde fica farmácia");
    eq(marks(r), "++-+", "P2 пропущенное слово — красное, остальные на месте");
    // P3 вердикт по слову карточки
    r = Practice.check("Espera um pouquinho, já volto.", "espera um pokinho já volto");
    ok(!r.keyOk("um pouquinho"), "P3 связка не распознана целиком — не засчитано");
    ok(r.keyOk("espera"), "P3 другое слово карточки распознано — засчитано");
    r = Practice.check("Vou te mandar a foto.", "vou te mandar a foto");
    ok(r.keyOk("mandar"), "P3 слово карточки распознано");
    ok(!Practice.check("Vou te mandar a foto.", "vou te dar a foto").keyOk("mandar"), "P3 слово карточки подменено — не засчитано");
    ok(Practice.check("Tudo bem?", "tudo bem").keyOk(""), "P3 фраза без ключа — засчитано, когда вся распознана");
    // P4 вслух: слово карточки спрятано во фразе, говорят всю фразу
    eq(Practice.gap("Espera um pouquinho, já volto.", "um pouquinho"), "Espera …, já volto.", "P4 связка спрятана, запятая на месте");
    eq(Practice.gap("Tá bom, obrigado.", "está"), "… bom, obrigado.", "P4 «tá» = «está»");
    eq(Practice.gap("Só um pouco.", "um pouquinho"), null, "P4 слова во фразе нет — null");
    eq(Practice.gap("Tudo bem?", ""), null, "P4 фраза без ключа — null");

    System.out.println(fails == 0 ? "Practice: " + checks + " проверок, все прошли" : "Practice: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) { System.exit(run() == 0 ? 0 : 1); }
}
