import dev.agenttranslator.TextRules;

/** Точечные случаи нормализатора, на которых он раньше ломался. */
public class TrCase { public static void main(String[] a) {
  String[] c = {"Onde fica a casa de banho?", "A casa de banho é ali.", "Preciso da casa de banho.",
    "Quero ver a ementa, por favor.", "Onde está o frigorífico?", "Coloque no frigorífico.",
    "Comprei um bilhete de comboio.", "O bilhete custa caro.", "Perdi o meu telemóvel no autocarro.",
    "Tem de outra cor?", "Não tem de quê.", "Tenho de ir.", "Tens de falar com ele.",
    "Apressa-te!", "Sinto-me melhor.", "Ela chamou-me ontem.", "A farmácia encontra-se aqui.",
    "Estou a comer.", "Quero um sumo de laranja."};
  for (String s : c) System.out.printf("%-38s -> %s%n", s, TextRules.toBrazilian(s)); } }
