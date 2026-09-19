import dev.agenttranslator.TextRules;
import java.io.*; import java.nio.file.*; import java.util.*;

/** Прогон нормализатора pt-PT -> pt-BR по всем португальским значениям словаря: ищем поломки. */
public class TrTest {
  public static void main(String[] a) throws Exception {
    Path p = Paths.get(a.length > 0 ? a[0] : "/tmp/pbtest/m/phrasebook_tatoeba.tsv");
    int n = 0, changed = 0; List<String> ex = new ArrayList<>();
    for (String line : Files.readAllLines(p)) {
      String[] c = line.split("\t");
      if (c.length < 4 || !c[0].equals("ru2pt")) continue;
      n++; String br = TextRules.toBrazilian(c[3]);
      if (!br.equals(c[3])) { changed++; if (ex.size() < 15) ex.add(c[3] + "  ->  " + br); }
    }
    System.out.printf("ru2pt значений: %d · нормализатор изменил: %d (%.2f%%)%n", n, changed, 100.0 * changed / n);
    for (String e : ex) System.out.println("   " + e);
  }
}
