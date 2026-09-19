import dev.agenttranslator.Translit;

/** Транслитерация адресов: родовое слово переводится, имя транскрибируется. */
public class AddrTest {
  public static void main(String[] a) {
    String[][] c = {
      {"Rua Augusta", "ru"}, {"Rua das Flores", "ru"}, {"Avenida Paulista", "ru"},
      {"Praça da Sé", "ru"}, {"Rua Consolação", "ru"}, {"Estrada do Sol", "ru"},
      {"Rodovia Anhanguera", "ru"}, {"Travessa Bela", "ru"},
      {"улица Ленина", "pt"}, {"проспект Мира", "pt"}, {"улица Аугуста", "pt"},
      {"площадь Победы", "pt"}, {"улице Консоласан", "pt"},
      {"Rua Augusta", "pt"}, {"улица Ленина", "ru"},
    };
    for (String[] x : c) System.out.printf("%-24s -> %-30s (цель %s)%n", x[0], Translit.address(x[0], x[1]), x[1]);
  }
}
