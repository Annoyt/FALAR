import dev.agenttranslator.Phrasebook;
import java.io.File;

/** Прогон настоящего Phrasebook из приложения на настольной JVM: загрузка, память, задержка поиска. */
public class PbTest {
  public static void main(String[] a) throws Exception {
    File dir = new File(a.length > 0 ? a[0] : "/tmp/pbtest/m");
    long t0 = System.currentTimeMillis();
    Phrasebook pb = new Phrasebook(dir);
    for (int i = 0; i < 600 && pb.minedCount == 0; i++) Thread.sleep(100);
    System.out.println("конструктор+загрузка: " + (System.currentTimeMillis() - t0) + " мс · " + pb.stats());
    System.gc(); Thread.sleep(300);
    Runtime r = Runtime.getRuntime();
    System.out.println("память после загрузки: " + (r.totalMemory() - r.freeMemory()) / 1048576 + " МБ");
    String[][] probe = {
      {"pt2ru", "Quanto custa isso?"}, {"pt2ru", "Não entendi."}, {"pt2ru", "bom dia"},
      {"pt2ru", "Onde fica o banheiro?"}, {"pt2ru", "Eu não falo português."},
      {"ru2pt", "Сколько это стоит?"}, {"ru2pt", "Я не понимаю"}, {"ru2pt", "Где туалет?"},
      {"ru2pt", "Помогите мне, пожалуйста"}, {"ru2pt", "Я заблудился"},
    };
    for (int w = 0; w < 3; w++) for (String[] p : probe) pb.lookup(p[0], p[1]);   // прогрев JIT
    for (String[] p : probe) {
      long s = System.nanoTime(); Phrasebook.Hit h = pb.lookup(p[0], p[1]); long d = System.nanoTime() - s;
      System.out.printf("  %-6s %-30s -> %-38s %s  (%.2f мс)%n", p[0], p[1],
          h == null ? "(промах -> MT)" : h.dst, h == null ? "" : h.kind, d / 1e6);
    }
    long s = System.nanoTime();
    for (int i = 0; i < 2000; i++) pb.lookup("pt2ru", "Uma frase que certamente nao esta no dicionario " + i);
    System.out.printf("промах: %.3f мс на поиск%n", (System.nanoTime() - s) / 1e6 / 2000);
    System.exit(0);
  }
}
