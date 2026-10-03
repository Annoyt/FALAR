package dev.agenttranslator;

import java.io.File;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Резервная копия Android (решение владельца 03.10): в облако Google не уходит ничего — разговоры,
 *  отпечатки голосов, живой звук, ключи облака; на новый телефон при переносе — всё. Правило легко
 *  сломать одним <include> в облачном разделе, поэтому разбор файлов — тестом. Запуск: bash bench/apk/test.sh. */
public class BackupRulesTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static final List<String> DOMAINS = Arrays.asList("root", "file", "database", "sharedpref", "external");
  static final String NS = "http://schemas.android.com/apk/res/android";

  static Element read(File f) throws Exception {
    DocumentBuilderFactory fac = DocumentBuilderFactory.newInstance(); fac.setNamespaceAware(true);
    return fac.newDocumentBuilder().parse(f).getDocumentElement();
  }
  static List<Element> kids(Element e, String tag) {
    List<Element> out = new ArrayList<>(); NodeList n = e.getElementsByTagName(tag);
    for (int k = 0; k < n.getLength(); k++) out.add((Element) n.item(k));
    return out;
  }

  /** apk — каталог bench/apk (манифест и res/xml). */
  public static int run(File apk) throws Exception {
    fails = 0; checks = 0;
    Element app = kids(read(new File(apk, "AndroidManifest.xml")), "application").get(0);
    ok("true".equals(app.getAttributeNS(NS, "allowBackup")), "B1 allowBackup=true — иначе на Android 9–11 не будет и переноса");
    ok("@xml/data_extraction_rules".equals(app.getAttributeNS(NS, "dataExtractionRules")), "B1 правила для Android 12+");
    ok("@xml/backup_rules".equals(app.getAttributeNS(NS, "fullBackupContent")), "B1 правила для Android 9–11");

    Element dx = read(new File(apk, "res/xml/data_extraction_rules.xml"));
    List<Element> cloud = kids(dx, "cloud-backup");
    ok(cloud.size() == 1, "B2 облачный раздел один");
    if (cloud.size() == 1) {
      ok(kids(cloud.get(0), "include").isEmpty(), "B2 в облако ничего не включено явно");
      Set<String> ex = new HashSet<>();
      for (Element e : kids(cloud.get(0), "exclude")) if (".".equals(e.getAttribute("path"))) ex.add(e.getAttribute("domain"));
      ok(ex.containsAll(DOMAINS), "B2 из облака исключены целиком все области: " + ex);
    }
    for (Element t : kids(dx, "device-transfer"))
      ok(kids(t, "exclude").isEmpty() && kids(t, "include").isEmpty(), "B3 перенос на новый телефон не ограничен");

    Element fb = read(new File(apk, "res/xml/backup_rules.xml"));
    Set<String> d2d = new HashSet<>();
    for (Element e : kids(fb, "include")) {
      ok(e.getAttribute("requireFlags").contains("deviceToDeviceTransfer"), "B4 включение только для переноса: " + e.getAttribute("domain"));
      if (".".equals(e.getAttribute("path"))) d2d.add(e.getAttribute("domain"));
    }
    ok(d2d.containsAll(DOMAINS), "B4 на Android 9–11 переносится всё: " + d2d);
    ok(kids(fb, "exclude").isEmpty(), "B4 исключений нет — переносится всё");

    System.out.println(fails == 0 ? "BackupRules: " + checks + " проверок, все прошли" : "BackupRules: провалов " + fails + " из " + checks);
    return fails;
  }
  public static void main(String[] a) throws Exception { System.exit(run(new File(a[0])) == 0 ? 0 : 1); }
}
