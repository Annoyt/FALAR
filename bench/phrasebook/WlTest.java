import dev.agenttranslator.*;
import java.io.*; import java.nio.file.*; import java.util.*;

/** Прогон списка своих слов по настоящим ошибкам распознавания, снятым с телефона. */
public class WlTest {
  public static void main(String[] a) throws Exception {
    File dir = new File("/tmp/wltest"); dir.mkdirs();
    Files.write(new File(dir, "wordlist.json").toPath(), ("[" +
      "{\"pt\":\"Rua Augusta\"},{\"pt\":\"Copacabana Palace\"},{\"ru\":\"Артём\",\"pt\":\"Artiom\"}," +
      "{\"pt\":\"Fogo de Chão\"},{\"pt\":\"Avenida Paulista\"},{\"pt\":\"caipirinha\"}," +
      "{\"pt\":\"Ipanema\"},{\"pt\":\"Consolação\"},{\"pt\":\"Guarulhos\"},{\"pt\":\"feijoada\"}," +
      "{\"pt\":\"Hotel Ibis\",\"ru\":\"отель Ибис\"}]").getBytes("UTF-8"));
    WordList wl = new WordList(dir);
    System.out.println(wl.stats());
    for (WordList.Entry e : wl.entries) System.out.printf("   %-20s <-> %s%n", e.pt, e.ru);

    String[][] cases = {
      {"pt", "ru", "O hotel fica na rua Augusta, perto do metrô."},
      {"pt", "ru", "Vamos almoçar no cupa acabando o palácio."},
      {"pt", "ru", "Meu nome é Ation, sou da Rússia."},
      {"pt", "ru", "Você conhece o restaurante Fogo de Chão?"},
      {"pt", "ru", "Era o provar feijoada e capirinha."},
      {"pt", "ru", "O ônibus para em Ipanema."},
      {"ru", "pt", "Ехать до Капокапанная."},
      {"ru", "pt", "Меня зовут Арт<unk>м, я из России."},
      {"ru", "pt", "где находится Авенедополиста."},
      {"ru", "pt", "Отвезите меня в аэропорт, Гарулев."},
      {"ru", "pt", "Я хочу попробовать фишу одной коперенью."},
      // отрицательные: ни одного слова из списка
      {"pt", "ru", "Quanto custa isso?"},
      {"pt", "ru", "Eu não falo português."},
      {"ru", "pt", "Сколько это стоит?"},
      {"ru", "pt", "он покинул комнату не сказав ни слова"},
    };
    System.out.println();
    for (String[] c : cases) {
      String src = c[0], tgt = c[1];
      String asr = TextRules.fixAsr(c[2], src);
      List<String[]> slots = new ArrayList<>();
      List<WordList.Hit> hits = new ArrayList<>();
      WordList.Result wr = wl.apply(asr, src, tgt, slots, hits);
      TextRules.Masked mk = TextRules.mask(wr.masked, src, slots);
      String out = TextRules.unmask(mk.text, mk, tgt);   // MT имитируем: плейсхолдеры сохраняются
      StringBuilder h = new StringBuilder();
      for (WordList.Hit x : hits) h.append(h.length() > 0 ? ", " : "").append('«').append(x.found).append("» -> ").append(x.e.side(tgt));
      System.out.printf("%s%n  в историю: %s%n  для MT: %s%n  после: %s%n  нашли: %s%n%n", c[2], wr.readable, mk.text, out, h.length() == 0 ? "—" : h);
    }
  }
}
