package dev.agenttranslator;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** Офлайн-распознавание без Android: пересчёт размера, разбор карты детектора, вырез строки,
 *  вход распознавателя, CTC, сборка строк и абзацев, цвета наложения.
 *  Часть G сверяет с эталоном tools/ocr_ref.py на снимках набора — если данные собраны
 *  (.venv/bin/python tools/ocr_golden.py); без них пропускается с пометкой.
 *  Запуск: bash bench/apk/test.sh. */
public class OcrCoreTest {
  static int fails = 0, checks = 0;
  static void ok(boolean c, String what) { checks++; if (!c) { fails++; System.out.println("  ПРОВАЛ: " + what); } }
  static boolean near(double a, double b, double eps) { return Math.abs(a - b) <= eps; }

  static double[] box(double... v) { return v; }
  static boolean nearBox(double[] a, double[] b, double eps) { if (a.length != b.length) return false; for (int i = 0; i < a.length; i++) if (!near(a[i], b[i], eps)) return false; return true; }
  static OcrCore.Item item(double x0, double y0, double x1, double y1, String t) {
    return new OcrCore.Item(new double[]{x0, y0, x1, y0, x1, y1, x0, y1}, t);
  }
  /** Наклонная рамка: левый верх (x, y), длина len, высота h, угол deg. */
  static OcrCore.Item slanted(double x, double y, double len, double h, double deg, String t) {
    double a = Math.toRadians(deg), ux = Math.cos(a), uy = Math.sin(a), nx = -uy, ny = ux;
    return new OcrCore.Item(new double[]{x, y, x + ux * len, y + uy * len, x + ux * len + nx * h, y + uy * len + ny * h, x + nx * h, y + ny * h}, t);
  }
  static List<String> texts(List<List<OcrCore.Row>> blocks) {
    List<String> out = new ArrayList<>(); for (List<OcrCore.Row> bl : blocks) for (OcrCore.Row r : bl) out.add(r.text); return out;
  }
  static List<String> paras(List<List<OcrCore.Row>> blocks) {
    List<String> out = new ArrayList<>(); for (List<OcrCore.Row> bl : blocks) for (OcrCore.Para p : OcrCore.paragraphs(bl)) out.add(p.text); return out;
  }

  public static int run(File golden) throws Exception {
    fails = 0; checks = 0;

    // --- R: пересчёт размера ---
    OcrCore.Taps id = new OcrCore.Taps(5, 5);
    boolean ident = true; for (int i = 0; i < 5; i++) ident &= id.start[i] == i && id.w[i][0] == 1f && (id.w[i].length == 1 || id.w[i][1] == 0f);
    ok(ident, "R1 один к одному — веса тождественные");
    OcrCore.Taps dn = new OcrCore.Taps(100, 7); boolean sum1 = true;
    for (float[] k : dn.w) { double s = 0; for (float v : k) s += v; sum1 &= near(s, 1, 1e-5); }
    ok(sum1, "R2 при уменьшении веса каждой точки в сумме дают 1");
    ok(dn.w[3].length > 20, "R3 при уменьшении в 14 раз фильтр захватывает десятки точек (сглаживание по площади)");
    int[] flat = new int[40 * 30]; Arrays.fill(flat, 0xFF102030);
    float[] fr = OcrCore.resize(flat, 40, 0, 0, 40, 30, 7, 5); boolean same = true;
    for (int i = 0; i < fr.length; i += 3) same &= near(fr[i], 0x10, 1e-3) && near(fr[i + 1], 0x20, 1e-3) && near(fr[i + 2], 0x30, 1e-3);
    ok(same, "R4 ровный цвет после пересчёта не меняется");
    ok(Arrays.equals(OcrCore.detSize(1280, 960), new int[]{960, 704}), "R5 1280×960 → 960×704: 22,5 округляется к чётному, как в эталоне");
    ok(Arrays.equals(OcrCore.detSize(1280, 1707), new int[]{704, 960}), "R5 портрет 1280×1707 → 704×960");
    ok(Arrays.equals(OcrCore.detSize(300, 200), new int[]{288, 192}), "R6 мелкий снимок не растягивается, только кратность 32");
    ok(Arrays.equals(OcrCore.detSize(10, 10), new int[]{32, 32}), "R6 и не меньше 32");
    float[] di = OcrCore.detInput(flat, 40, 30, 32, 32);
    ok(near(di[0], (0x30 / 255f - 0.485f) / 0.229f, 1e-4) && near(di[2 * 32 * 32], (0x10 / 255f - 0.406f) / 0.225f, 1e-4),
        "R7 вход детектора в порядке BGR: первый канал — синий, третий — красный");

    // --- B: рамки ---
    double[] ob = OcrCore.orderBox(box(10, 20, 10, 0, 0, 20, 0, 0));
    ok(Arrays.equals(ob, box(0, 0, 10, 0, 10, 20, 0, 20)), "B1 порядок углов: левый верх, правый верх, правый низ, левый низ");
    List<double[]> sq = new ArrayList<>();
    for (int y = 5; y <= 9; y++) { sq.add(new double[]{3, y}); sq.add(new double[]{20, y}); }
    ok(Arrays.equals(OcrCore.minAreaRect(sq), box(3, 5, 20, 5, 20, 9, 3, 9)), "B2 прямоугольник по осям — ровно по крайним точкам");
    List<double[]> rot = new ArrayList<>(); double a = Math.toRadians(20);
    for (int i = 0; i <= 40; i++) for (int j = 0; j <= 8; j++) rot.add(new double[]{50 + i * Math.cos(a) - j * Math.sin(a), 10 + i * Math.sin(a) + j * Math.cos(a)});
    double[] rr = OcrCore.minAreaRect(rot), sd = OcrCore.sides(rr);
    ok(near(sd[0], 40, 1e-6) && near(sd[1], 8, 1e-6), "B3 наклон 20° — стороны 40 и 8, а не охват по осям");
    ok(near(Math.toDegrees(Math.atan2(rr[3] - rr[1], rr[2] - rr[0])), 20, 1e-6), "B3 угол верхней стороны — 20°");
    List<double[]> line = new ArrayList<>(); for (int x = 0; x < 10; x++) line.add(new double[]{x, 4});
    ok(Arrays.equals(OcrCore.minAreaRect(line), box(0, 4, 9, 4, 9, 4, 0, 4)), "B4 точки на одной линии — охват по осям, без деления на ноль");
    double[] ex = OcrCore.expand(box(0, 0, 10, 0, 10, 4, 0, 4), 2);
    ok(Arrays.equals(ex, box(-2, -2, 12, -2, 12, 6, -2, 6)), "B5 расширение на d со всех сторон");
    float[] pm = new float[10 * 10]; for (int y = 2; y <= 5; y++) for (int x = 2; x <= 7; x++) pm[y * 10 + x] = 1f;
    ok(near(OcrCore.polyMean(pm, 10, 10, box(2, 2, 7, 2, 7, 5, 2, 5)), 1.0, 1e-9), "B6 средняя вероятность внутри рамки — по центрам пикселей, границы включены");
    ok(near(OcrCore.polyMean(pm, 10, 10, box(0, 0, 9, 0, 9, 9, 0, 9)), 24 / 100.0, 1e-9), "B6 рамка на всё поле — доля текста");

    // карта: полоса 40×8 под углом и мелкое пятно из трёх точек
    int pw = 120, ph = 60; float[] prob = new float[pw * ph];
    for (int y = 0; y < ph; y++) for (int x = 0; x < pw; x++) {
      double u = (x - 20) * Math.cos(a) + (y - 10) * Math.sin(a), v = -(x - 20) * Math.sin(a) + (y - 10) * Math.cos(a);
      if (u >= 0 && u <= 40 && v >= 0 && v <= 8) prob[y * pw + x] = 0.9f;
    }
    prob[50 * pw + 100] = prob[50 * pw + 101] = prob[51 * pw + 100] = 0.9f;
    List<double[]> bx = OcrCore.boxes(prob, pw, ph, pw * 2, ph * 2);
    ok(bx.size() == 1, "B7 пятно из трёх точек — шум, рамка одна (" + bx.size() + ")");
    if (!bx.isEmpty()) {
      double[] s2 = OcrCore.sides(bx.get(0));
      ok(s2[0] > 2 * 40 && s2[1] > 2 * 8, "B8 рамка расширена (unclip) и пересчитана в размер снимка ×2: " + (int) s2[0] + "×" + (int) s2[1]);
    }
    float[] faint = new float[pw * ph]; for (int y = 10; y < 20; y++) for (int x = 10; x < 60; x++) faint[y * pw + x] = 0.3f;
    ok(OcrCore.boxes(faint, pw, ph, pw, ph).isEmpty(), "B9 бледная область (0,3 < 0,45) — не текст");

    // --- C: вырез, вход распознавателя, CTC ---
    int[] img = new int[8 * 4]; for (int i = 0; i < img.length; i++) img[i] = 0xFF000000 | (i * 7);
    int[] wh = new int[2]; int[] cr = OcrCore.crop(img, 8, 4, box(0, 0, 8, 0, 8, 4, 0, 4), wh);
    ok(wh[0] == 8 && wh[1] == 4 && Arrays.equals(cr, img), "C1 рамка по краю снимка — вырез совпадает с источником");
    int[] tall = new int[3 * 9]; for (int i = 0; i < tall.length; i++) tall[i] = 0xFF000000 | i;
    int[] tr = OcrCore.crop(tall, 3, 9, box(0, 0, 3, 0, 3, 9, 0, 9), wh);
    ok(wh[0] == 9 && wh[1] == 3 && tr[0] == tall[2] && tr[wh[0] - 1] == tall[26], "C2 вертикальная надпись повёрнута на 90° против часовой");
    List<int[]> shapes = Arrays.asList(new int[]{100, 20}, new int[]{30, 20}, new int[]{60, 30}, new int[]{40, 20});
    ok(Arrays.equals(OcrCore.recOrder(shapes), new Integer[]{1, 2, 3, 0}), "C3 порядок по отношению сторон, равные — в исходном порядке");
    ok(OcrCore.recWidth(shapes) == 240 + 80, "C4 ширина пачки: не уже 320");
    ok(OcrCore.recWidth(Collections.singletonList(new int[]{1000, 20})) == 2400, "C4 длинная строка — по её отношению сторон");
    ok(OcrCore.recWidth(Collections.singletonList(new int[]{5000, 20})) == 3200, "C4 и не шире 3200");
    float[] xr = new float[2 * 3 * 48 * 320]; int[] white = new int[20 * 10]; Arrays.fill(white, 0xFFFFFFFF);
    OcrCore.recInput(white, 20, 10, xr, 1, 320);
    int plane = 48 * 320, base = 3 * plane;
    ok(near(xr[base], 1f, 1e-5) && near(xr[base + 95], 1f, 1e-5) && xr[base + 96] == 0f, "C5 строка 20×10 → 96 точек белого (1,0), дальше добивка нулями");
    ok(xr[0] == 0f, "C5 соседний элемент пачки не тронут");
    String[] chars = OcrCore.dict("a\nb\nc\n", 5);
    ok(chars.length == 5 && chars[0].isEmpty() && chars[1].equals("a") && chars[4].equals(" "), "C6 словарь: пустой, знаки, пробел — перевод строки в конце не даёт пустого знака");
    float[] p = new float[7 * 5]; int[] seq = {1, 1, 0, 1, 4, 2, 2};
    for (int t = 0; t < 7; t++) p[t * 5 + seq[t]] = 0.8f;
    float[] sc = new float[1];
    ok(OcrCore.ctc(p, 0, 7, 5, chars, sc).equals("aa b") && near(sc[0], 0.8, 1e-6), "C7 CTC: повторы схлопываются, пустой разделяет одинаковые знаки");

    // --- L: строки, блоки, абзацы ---
    List<OcrCore.Item> its = new ArrayList<>(Arrays.asList(
        item(100, 10, 200, 30, "MOTORISTA"), item(10, 10, 90, 30, "ATENÇÃO"),
        item(10, 40, 200, 60, "RODOVIA ESTREITA E"), item(10, 64, 200, 84, "EXTREMAMENTE SINUOSA")));
    List<List<OcrCore.Row>> bl = OcrCore.layout(its);
    ok(texts(bl).equals(Arrays.asList("ATENÇÃO MOTORISTA", "RODOVIA ESTREITA E", "EXTREMAMENTE SINUOSA")), "L1 строки слева направо, сверху вниз: " + texts(bl));
    ok(paras(bl).equals(Arrays.asList("ATENÇÃO MOTORISTA", "RODOVIA ESTREITA E EXTREMAMENTE SINUOSA")), "L2 строка на «E» продолжается следующей: " + paras(bl));
    List<OcrCore.Item> slant = new ArrayList<>(Arrays.asList(
        slanted(10, 10, 300, 20, 8, "Segunda a sexta das 9 às 17"), slanted(10, 36, 300, 20, 8, "Sábados das 9 às 13")));
    ok(texts(OcrCore.layout(slant)).size() == 2, "L3 снятая под углом вывеска: строки не склеиваются, хотя их охваты по вертикали перекрыты");
    List<OcrCore.Item> cols = new ArrayList<>(Arrays.asList(item(10, 10, 110, 30, "Guapíara"), item(300, 10, 350, 30, "4 km")));
    ok(texts(OcrCore.layout(cols)).size() == 2, "L4 надписи на одной высоте через просвет — разные строки (колонки)");
    List<OcrCore.Item> near2 = new ArrayList<>(Arrays.asList(item(10, 10, 110, 30, "Adulto"), item(118, 10, 200, 30, "R$ 25,00")));
    ok(texts(OcrCore.layout(near2)).equals(Collections.singletonList("Adulto R$ 25,00")), "L5 рядом стоящие рамки одной строки — одна строка");
    List<OcrCore.Item> two = new ArrayList<>(Arrays.asList(
        item(300, 10, 400, 30, "Direita"), item(300, 34, 400, 54, "coluna"), item(10, 12, 110, 32, "Esquerda"), item(10, 36, 110, 56, "texto")));
    ok(texts(OcrCore.layout(two)).equals(Arrays.asList("Esquerda", "texto", "Direita", "coluna")), "L6 две колонки: сначала левая целиком: " + texts(OcrCore.layout(two)));
    List<OcrCore.Item> big = new ArrayList<>(Arrays.asList(item(10, 10, 100, 22, "HORÁRIO DE"), item(10, 26, 200, 56, "FUNCIONAMENTO")));
    ok(paras(OcrCore.layout(big)).equals(Collections.singletonList("HORÁRIO DE FUNCIONAMENTO")), "L7 после служебного слова продолжается и строка крупнее");
    List<OcrCore.Item> big2 = new ArrayList<>(Arrays.asList(item(10, 10, 100, 22, "HORÁRIO"), item(10, 26, 200, 56, "FUNCIONAMENTO")));
    ok(paras(OcrCore.layout(big2)).size() == 2, "L7 без служебного слова строки разного кегля — разные блоки");
    List<OcrCore.Row> rows = new ArrayList<>();
    for (String t : new String[]{"NÃO SÃO PERMITIDOS JOGOS COM BO-", "LAS E APARELHOS.", "Foi inaugurada em 1867", "para atender", "Adulto R$ 25,00", "Criança R$ 12,50", "Das 9:00,", "às 16:00"}) {
      OcrCore.Row r = new OcrCore.Row(); r.items.add(item(0, 0, 10, 10, t)); r.close(); rows.add(r);
    }
    List<String> pt = new ArrayList<>(); for (OcrCore.Para pp : OcrCore.paragraphs(rows)) pt.add(pp.text);
    ok(pt.equals(Arrays.asList("NÃO SÃO PERMITIDOS JOGOS COM BOLAS E APARELHOS.", "Foi inaugurada em 1867 para atender", "Adulto R$ 25,00", "Criança R$ 12,50", "Das 9:00, às 16:00")),
        "L8 перенос со знаком «-» склеивается без пробела, строчная буква и запятая продолжают фразу, цены остаются отдельно: " + pt);
    // этикетка соуса: штрихкод слева стоит вровень с последней строкой колонки справа
    List<OcrCore.Item> label = new ArrayList<>(Arrays.asList(
        item(300, 10, 560, 34, "conservar em geladeira (5°C -"), item(300, 40, 560, 64, "10°C) e consumir em"),
        item(300, 70, 560, 94, "30 dias. Após o uso, feche a"), item(60, 100, 290, 124, "7 896025 804067"), item(300, 100, 380, 124, "tampa.")));
    List<List<OcrCore.Row>> lb = OcrCore.layout(label);
    ok(texts(lb).contains("7 896025 804067") && texts(lb).contains("tampa."), "L10 цифры штрихкода не встают в строку с текстом рядом: " + texts(lb));
    ok(paras(lb).contains("conservar em geladeira (5°C - 10°C) e consumir em 30 dias. Após o uso, feche a tampa."),
        "L10 открытая скобка и тире в конце строки продолжают фразу, штрихкод в неё не попадает: " + paras(lb));
    ok(OcrCore.barcode("7 896025804067\"") && OcrCore.barcode("78960258") && OcrCore.barcode("\"896025'804067\"") && !OcrCore.barcode("7896025") && !OcrCore.barcode("3242-3300")
        && !OcrCore.barcode("Lote 78960258") && !OcrCore.barcode("(11) 3242 330"), "L11 штрихкод — от восьми цифр подряд и без букв; телефон и лот — нет");
    rows.clear();
    for (String t : new String[]{"feche a", "7 896025 804067", "tampa.", "Entre 5 -", "10 graus", "Preço (sem", "taxa) R$ 5"}) {
      OcrCore.Row r = new OcrCore.Row(); r.items.add(item(0, 0, 10, 10, t)); r.close(); rows.add(r);
    }
    pt.clear(); for (OcrCore.Para pp : OcrCore.paragraphs(rows)) pt.add(pp.text);
    ok(pt.equals(Arrays.asList("feche a", "7 896025 804067", "tampa.", "Entre 5 - 10 graus", "Preço (sem taxa) R$ 5")),
        "L12 штрихкод не склеивается ни со служебным словом до, ни со строчной после; тире и скобка склеивают: " + pt);
    OcrCore.Para para = OcrCore.paragraphs(OcrCore.layout(its).get(0)).get(0);
    double[] f = para.frame();
    ok(near(f[0], 105, 1e-9) && near(f[1], 20, 1e-9) && near(f[4], 190, 1e-9) && near(f[5], 20, 1e-9), "L9 прямоугольник абзаца — по его рамкам");

    // --- S: изогнутые и тесные строки — полоса вдоль средней линии ---
    int SW = 200, SH = 70; float[] curved = new float[SW * SH];
    for (int x = 20; x <= 180; x++) { double c = 20 + 0.004 * (x - 100) * (x - 100); for (int y = 0; y < SH; y++) if (Math.abs(y - c) <= 3) curved[y * SW + x] = 0.9f; }
    List<OcrCore.Strip> cs = new ArrayList<>(); List<double[]> cb = OcrCore.boxes(curved, SW, SH, 2 * SW, 2 * SH, cs);
    OcrCore.Strip s1 = cs.isEmpty() ? null : cs.get(0);
    ok(cb.size() == 1 && s1 != null && !s1.crowded && s1.sag > 20 && Math.abs(s1.T - 7) <= 1,
        "S1 дуга с прогибом 25 при толщине 7 — полоса: " + (s1 == null ? "нет" : "прогиб " + String.format("%.1f", s1.sag) + ", толщина " + s1.T));
    ok(OcrCore.polyMean(curved, SW, SH, OcrCore.minAreaRect(Arrays.asList(new double[]{20, 45.6}, new double[]{180, 45.6}, new double[]{100, 17}, new double[]{100, 23}))) < OcrCore.BOX_THRESH
        && OcrCore.boxes(curved, SW, SH, SW, SH).size() == 1,
        "S1 её прямоугольник заполнен меньше чем наполовину — раньше строка отбрасывалась, теперь остаётся");
    float[] blob = new float[SW * SH];                           // не дуга, а редкое пятно — отбрасывается, как раньше
    for (int x = 20; x <= 180; x += 2) for (int y = 20; y <= 40; y += 2) blob[y * SW + x] = 0.9f;
    for (int x = 20; x <= 180; x++) blob[30 * SW + x] = 0.9f;
    for (int y = 20; y <= 40; y++) for (int x = 20; x <= 180; x += 2) blob[y * SW + x] = Math.max(blob[y * SW + x], 0.25f);
    ok(OcrCore.boxes(blob, SW, SH, SW, SH).isEmpty(), "S1 редкая область без дуги с низкой средней — по-прежнему не строка");
    if (s1 != null) {                                           // снимок ×2: тёмная дуга на белом
      int[] im = new int[4 * SW * SH]; for (int y = 0; y < 2 * SH; y++) for (int x = 0; x < 2 * SW; x++) {
        double c = 20 + 0.004 * (x / 2.0 - 100) * (x / 2.0 - 100); im[y * 2 * SW + x] = Math.abs(y / 2.0 - c) <= 3 ? 0xFF000000 : 0xFFFFFFFF; }
      int[] swh = new int[2]; int[] sp = OcrCore.cropStrip(im, 2 * SW, 2 * SH, s1, swh);
      int cw2 = swh[0], ch2 = swh[1], mid = 0, top = 0, scols = 0;
      for (int x = cw2 / 8; x < cw2 - cw2 / 8; x++) { scols++; if ((sp[(ch2 / 2) * cw2 + x] & 255) < 64) mid++; if ((sp[x] & 255) > 192) top++; }
      ok(cw2 > 2 * 160 && mid >= 0.95 * scols && top >= 0.95 * scols,
          "S2 вырез полосы — дуга выпрямлена: середина тёмная на " + mid + "/" + scols + ", верх светлый на " + top + "/" + scols + " (" + cw2 + "×" + ch2 + ")");
      int[] rwh = new int[2]; int[] rp = OcrCore.crop(im, 2 * SW, 2 * SH, cb.get(0), rwh); int rmid = 0;
      for (int x = rwh[0] / 8; x < rwh[0] - rwh[0] / 8; x++) if ((rp[(rwh[1] / 2) * rwh[0] + x] & 255) < 64) rmid++;
      ok(rmid < 0.6 * scols, "S2 рамкой та же дуга по середине выреза тёмная лишь местами: " + rmid + "/" + scols);
    }
    float[] sflat = new float[SW * SH]; for (int y = 27; y <= 33; y++) for (int x = 20; x <= 180; x++) sflat[y * SW + x] = 0.9f;
    List<OcrCore.Strip> fs = new ArrayList<>(); OcrCore.boxes(sflat, SW, SH, SW, SH, fs);
    ok(fs.size() == 1 && fs.get(0) == null, "S3 прямая строка без соседей — рамкой, как раньше");
    float[] stwo = new float[SW * SH];
    for (int x = 20; x <= 180; x++) { for (int y = 17; y <= 23; y++) stwo[y * SW + x] = 0.9f; for (int y = 25; y <= 31; y++) stwo[y * SW + x] = 0.9f; }
    List<OcrCore.Strip> tws = new ArrayList<>(); OcrCore.boxes(stwo, SW, SH, SW, SH, tws);
    ok(tws.size() == 2 && tws.get(0) != null && tws.get(1) != null && tws.get(0).crowded && tws.get(1).crowded && tws.get(0).sag < 0.5,
        "S4 две прямые строки через пустой ряд — тесные, обе полосой (сосед ниже ещё не разобран — разметка целиком)");
    float[] apart = new float[SW * SH];
    for (int x = 20; x <= 180; x++) { for (int y = 10; y <= 16; y++) apart[y * SW + x] = 0.9f; for (int y = 40; y <= 46; y++) apart[y * SW + x] = 0.9f; }
    List<OcrCore.Strip> as = new ArrayList<>(); OcrCore.boxes(apart, SW, SH, SW, SH, as);
    ok(as.size() == 2 && as.get(0) == null && as.get(1) == null, "S5 строки с просторным интервалом — рамкой");
    float[] shortc = new float[SW * SH];
    for (int x = 95; x <= 110; x++) { double c = 30 + 0.04 * (x - 102) * (x - 102); for (int y = 0; y < SH; y++) if (Math.abs(y - c) <= 3) shortc[y * SW + x] = 0.9f; }
    List<OcrCore.Strip> ss = new ArrayList<>(); OcrCore.boxes(shortc, SW, SH, SW, SH, ss);
    ok(ss.size() == 1 && ss.get(0) == null, "S6 короткая область (длина меньше трёх высот) — рамкой, хоть и изогнута");
    double[] sol = OcrCore.solve3(new double[][]{{0, 2, 1}, {1, 1, 1}, {2, 1, 0}}, new double[]{5, 4, 4});
    ok(sol != null && near(sol[0], 1, 1e-12) && near(sol[1], 2, 1e-12) && near(sol[2], 1, 1e-12)
        && OcrCore.solve3(new double[][]{{1, 2, 3}, {2, 4, 6}, {1, 1, 1}}, new double[]{1, 2, 3}) == null, "S7 система 3×3: с перестановкой строк; вырожденная — null");
    double[] xp = {0, 1, 3}, fp = {10, 20, 40};
    ok(OcrCore.interp(-1, xp, fp) == 10 && OcrCore.interp(0.5, xp, fp) == 15 && OcrCore.interp(2, xp, fp) == 30 && OcrCore.interp(1, xp, fp) == 20
        && OcrCore.interp(9, xp, fp) == 40, "S8 линейная интерполяция как np.interp: края — крайние значения");
    OcrCore.Row pr = new OcrCore.Row(); pr.items.add(new OcrCore.Item(box(0, 0, 10, 0, 10, 5, 0, 5), "abcd", 0.9)); pr.items.add(new OcrCore.Item(box(12, 0, 20, 0, 20, 5, 12, 5), "ab", 0.6)); pr.close();
    OcrCore.Para spp = OcrCore.paragraphs(Collections.singletonList(pr)).get(0);
    ok(near(spp.score(), (4 * 0.9 + 2 * 0.6) / 6, 1e-12) && OcrCore.CONF_MIN == 0.80, "S9 уверенность абзаца — средняя с весом длины текста; порог перевода 0,80");

    // --- K: цвета наложения ---
    int W = 60, H = 40; int[] sign = new int[W * H]; Arrays.fill(sign, 0xFF2040C0);
    for (int y = 16; y < 24; y++) for (int x = 12; x < 48; x += 3) sign[y * W + x] = 0xFFFFFFFF;
    int[] col = OcrCore.colors(sign, W, H, new double[]{30, 20, 1, 0, 40, 10});
    ok(col[0] == 0xFF2040C0 && col[3] == 0xFF2040C0, "K1 фон — цвет вывески вокруг текста");
    ok(col[4] == 0xFFFFFFFF, "K2 цвет текста — самый отличный от фона внутри рамки");
    int[] grad = new int[W * H];
    for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) { int v = x * 4; grad[y * W + x] = 0xFF000000 | (v << 16) | (v << 8) | v; }
    int[] gc = OcrCore.colors(grad, W, H, new double[]{30, 20, 1, 0, 40, 10});
    ok(((gc[2] >> 16) & 255) - ((gc[0] >> 16) & 255) > 60 && ((gc[3] >> 16) & 255) - ((gc[1] >> 16) & 255) > 60,
        "K3 свет вдоль вывески меняется — левые четверти темнее правых, заливка пойдёт градиентом");
    ok(gc[4] == 0xFF000000 || gc[4] == 0xFFFFFFFF, "K4 без контраста внутри — чёрный или белый текст");

    // --- M: границы и точные значения (мутационное тестирование нашло места, где тесты код
    //     исполняли, но поведение не проверяли) ---
    // M1 точная рамка: полоса 20×5 точек, расширение на d, пересчёт в снимок ×2
    float[] pm1 = new float[60 * 30]; for (int y = 5; y <= 9; y++) for (int x = 10; x <= 29; x++) pm1[y * 60 + x] = 0.9f;
    List<double[]> b1 = OcrCore.boxes(pm1, 60, 30, 120, 60);
    double d1 = 19 * 4 * (double) OcrCore.UNCLIP / (2 * 23);
    ok(b1.size() == 1 && nearBox(b1.get(0), box(2 * (10 - d1), 2 * (5 - d1), 2 * (29 + d1), 2 * (5 - d1), 2 * (29 + d1), 2 * (9 + d1), 2 * (10 - d1), 2 * (9 + d1)), 1e-6),
        "M1 рамка полосы точно: охват по крайним точкам, расширение на d = S·1,4/(2P), ×2 в снимок: " + (b1.isEmpty() ? "нет" : Arrays.toString(b1.get(0))));
    // M2 у края снимка рамка прижимается к краю, а не уходит за него
    float[] pm2 = new float[60 * 30];
    for (int y = 0; y <= 3; y++) for (int x = 0; x <= 9; x++) pm2[y * 60 + x] = 0.9f;
    for (int y = 26; y <= 29; y++) for (int x = 50; x <= 59; x++) pm2[y * 60 + x] = 0.9f;
    List<double[]> b2 = OcrCore.boxes(pm2, 60, 30, 60, 30); double d2 = 9 * 3 * (double) OcrCore.UNCLIP / (2 * 12);
    ok(b2.size() == 2 && nearBox(b2.get(0), box(0, 0, 9 + d2, 0, 9 + d2, 3 + d2, 0, 3 + d2), 1e-6)
        && nearBox(b2.get(1), box(50 - d2, 26 - d2, 60, 26 - d2, 60, 30, 50 - d2, 30), 1e-6), "M2 рамки у краёв прижаты к 0 и к размеру снимка");
    // M3 соседство не переходит через конец строки карты
    float[] pm3 = new float[20 * 10];
    for (int y = 2; y <= 5; y++) for (int x = 16; x <= 19; x++) pm3[y * 20 + x] = 0.9f;
    for (int y = 3; y <= 6; y++) for (int x = 0; x <= 3; x++) pm3[y * 20 + x] = 0.9f;
    ok(OcrCore.boxes(pm3, 20, 10, 20, 10).size() == 2, "M3 пятно у правого края и пятно у левого края следующей строки — разные области");
    // M4 порог: точка ровно на пороге — не текст; рамка ровно на пороге уверенности — текст
    float[] pm4 = new float[60 * 30];
    for (int y = 9; y <= 15; y++) for (int x = 19; x <= 40; x++) pm4[y * 60 + x] = OcrCore.THRESH;
    for (int y = 10; y <= 14; y++) for (int x = 20; x <= 39; x++) pm4[y * 60 + x] = 0.9f;
    List<double[]> b4 = OcrCore.boxes(pm4, 60, 30, 60, 30); double d4 = 19 * 4 * (double) OcrCore.UNCLIP / (2 * 23);
    ok(b4.size() == 1 && nearBox(b4.get(0), box(20 - d4, 10 - d4, 39 + d4, 10 - d4, 39 + d4, 14 + d4, 20 - d4, 14 + d4), 1e-6), "M4 кайма ровно на пороге 0,2 в область не входит");
    float[] pm5 = new float[60 * 30]; for (int y = 5; y <= 9; y++) for (int x = 10; x <= 29; x++) pm5[y * 60 + x] = OcrCore.BOX_THRESH;
    ok(OcrCore.boxes(pm5, 60, 30, 60, 30).size() == 1, "M4 средняя уверенность ровно 0,45 — строка остаётся");
    // M5 наименьшая сторона: 2 — шум, 3 — строка
    float[] pm6 = new float[60 * 30];
    for (int y = 2; y <= 4; y++) for (int x = 5; x <= 30; x++) pm6[y * 60 + x] = 0.9f;
    for (int y = 12; y <= 15; y++) for (int x = 5; x <= 30; x++) pm6[y * 60 + x] = 0.9f;
    List<double[]> b6 = OcrCore.boxes(pm6, 60, 30, 60, 30);
    ok(b6.size() == 1 && b6.get(0)[1] > 8, "M5 полоса в 3 точки высотой (сторона 2) отброшена, в 4 точки (сторона 3) — строка");
    float[] pm7 = new float[20 * 20]; for (int k = 0; k < 6; k++) pm7[(2 + k) * 20 + 2 + k] = 0.9f;
    ok(OcrCore.boxes(pm7, 20, 20, 20, 20).isEmpty(), "M5 диагональ в одну точку толщиной — не строка (отрезок нулевой ширины)");
    // M6 выпуклая оболочка: только углы, без внутренних и лежащих на сторонах точек
    List<double[]> hp = new ArrayList<>(Arrays.asList(new double[]{5, 5}, new double[]{10, 10}, new double[]{0, 5}, new double[]{5, 0}, new double[]{0, 0},
        new double[]{10, 5}, new double[]{3, 7}, new double[]{0, 10}, new double[]{5, 10}, new double[]{10, 0}, new double[]{0, 3}));
    List<String> hv = new ArrayList<>(); for (double[] q : OcrCore.hull(hp)) hv.add((int) q[0] + "," + (int) q[1]); Collections.sort(hv);
    ok(hv.equals(Arrays.asList("0,0", "0,10", "10,0", "10,10")), "M6 оболочка квадрата с точками внутри и на сторонах — четыре угла: " + hv);
    // M7 три точки не на одной прямой — настоящий прямоугольник, а не отрезок
    double c30 = Math.cos(Math.toRadians(30)), s30 = Math.sin(Math.toRadians(30));
    double[] tri = OcrCore.minAreaRect(Arrays.asList(new double[]{0, 0}, new double[]{10 * c30, 10 * s30}, new double[]{-s30, c30}));
    double[] ts = OcrCore.sides(tri); double tmin = Math.min(ts[0], ts[1]), tmax = Math.max(ts[0], ts[1]);
    ok(near(tmin, 1, 1e-9) && near(tmax, 10, 1e-9), "M7 узкий треугольник под 30° — прямоугольник 10×1: " + tmin + "×" + tmax);
    // M8 порядок углов у повёрнутой рамки
    double[] rb = OcrCore.orderBox(box(10 * c30 - s30, 10 * s30 + c30, 0, 0, -s30, c30, 10 * c30, 10 * s30));
    ok(nearBox(rb, box(0, 0, 10 * c30, 10 * s30, 10 * c30 - s30, 10 * s30 + c30, -s30, c30), 1e-12), "M8 повёрнутая рамка: левый верх, правый верх, правый низ, левый низ");
    // M9 средняя уверенность: рамка вне карты и рамка без центров пикселей — 0, не NaN
    float[] one = new float[4 * 4]; Arrays.fill(one, 1f);
    ok(OcrCore.polyMean(one, 4, 4, box(-10, -10, -5, -10, -5, -5, -10, -5)) == 0, "M9 рамка целиком вне карты — 0");
    ok(OcrCore.polyMean(one, 4, 4, box(0.2, 0.2, 0.8, 0.2, 0.8, 0.8, 0.2, 0.8)) == 0, "M9 рамка между центрами пикселей — 0");
    ok(near(OcrCore.polyMean(one, 4, 4, box(-2, -2, 3, -2, 3, 3, -2, 3)), 1, 1e-12), "M9 рамка частью за краем — среднее по тому, что внутри");
    float[] half = new float[4 * 4]; for (int y = 0; y < 4; y++) for (int x = 0; x < 2; x++) half[y * 4 + x] = 1f;
    ok(near(OcrCore.polyMean(half, 4, 4, box(0, 0, 3, 0, 3, 3, 0, 3)), 0.5, 1e-12), "M9 половина рамки — текст: 0,5");
    // M10 расширение повёрнутой рамки — точно
    double[] rr0 = box(0, 0, 10 * c30, 10 * s30, 10 * c30 - s30, 10 * s30 + c30, -s30, c30);
    double[] rx = OcrCore.expand(rr0, 1);
    double cxx = (rr0[0] + rr0[2] + rr0[4] + rr0[6]) / 4, cyy = (rr0[1] + rr0[3] + rr0[5] + rr0[7]) / 4;
    double[] want = OcrCore.orderBox(box(cxx - c30 * 6 + s30 * 1.5, cyy - s30 * 6 - c30 * 1.5, cxx + c30 * 6 + s30 * 1.5, cyy + s30 * 6 - c30 * 1.5,
        cxx + c30 * 6 - s30 * 1.5, cyy + s30 * 6 + c30 * 1.5, cxx - c30 * 6 - s30 * 1.5, cyy - s30 * 6 + c30 * 1.5));
    ok(nearBox(rx, want, 1e-9), "M10 повёрнутая рамка 10×1, расширение на 1 — 12×3 с тем же центром и углом");
    // M11 вырез по дробной рамке и по повёрнутой — точно по билинейной выборке линейной картинки
    int[] lin = new int[10 * 6];
    for (int y = 0; y < 6; y++) for (int x = 0; x < 10; x++) lin[y * 10 + x] = 0xFF000000 | ((20 * x + y) << 16) | ((10 + x) << 8) | (5 * y);
    int[] cw = new int[2]; int[] c1 = OcrCore.crop(lin, 10, 6, box(2.5, 1, 6.5, 1, 6.5, 4, 2.5, 4), cw); boolean cok = cw[0] == 4 && cw[1] == 3;
    for (int v = 0; v < 3 && cok; v++) for (int u = 0; u < 4 && cok; u++) cok = c1[v * 4 + u] == (0xFF000000 | ((51 + 20 * u + v) << 16) | ((13 + u) << 8) | (5 + 5 * v));
    ok(cok, "M11 вырез 4×3 со сдвигом на полпикселя — значения билинейной выборки");
    int[] c2 = OcrCore.crop(lin, 10, 6, box(2, 1, 6, 3, 5, 5, 1, 3), cw); boolean rok = cw[0] == 4 && cw[1] == 2;
    for (int v = 0; v < 2 && rok; v++) for (int u = 0; u < 4 && rok; u++) {
      double X = 2 + (u + 0.5) * 1.0 + (v + 0.5) * -0.5 - 0.5, Y = 1 + (u + 0.5) * 0.5 + (v + 0.5) * 1.0 - 0.5;
      rok = c2[v * 4 + u] == (0xFF000000 | ((int) (20 * X + Y + 0.5) << 16) | ((int) (10 + X + 0.5) << 8) | (int) (5 * Y + 0.5));
    }
    ok(rok, "M11 вырез по рамке под углом — по её сторонам, а не по осям");
    OcrCore.crop(lin, 10, 6, box(1, 1, 3, 1, 3, 4, 1, 4), cw);
    ok(cw[0] == 3 && cw[1] == 2, "M11 рамка 2×3 (высота ровно в 1,5 ширины) — уже вертикальная надпись, повёрнута");
    // M12 выборка: дробная точка и выход за край — край
    ok(OcrCore.sample(lin, 10, 6, 1.25, 2.5) == (0xFF000000 | (28 << 16) | (11 << 8) | 13), "M12 выборка в дробной точке: 27,5 → 28, 11,25 → 11, 12,5 → 13");
    ok(OcrCore.sample(lin, 10, 6, -3, -3) == lin[0] && OcrCore.sample(lin, 10, 6, 100, 100) == lin[59], "M12 за краем — ближайший край");
    // M13 вход распознавателя: все три плоскости BGR, строка и столбец, добивка
    int[] col3 = new int[4 * 2]; Arrays.fill(col3, 0xFF000000 | (200 << 16) | (100 << 8) | 50);
    float[] xr3 = new float[3 * 48 * 320]; OcrCore.recInput(col3, 4, 2, xr3, 0, 320);
    int pl = 48 * 320, at = 47 * 320 + 95, pad = 47 * 320 + 96;
    ok(near(xr3[at], (50 / 255f - 0.5f) / 0.5f, 1e-5) && near(xr3[pl + at], (100 / 255f - 0.5f) / 0.5f, 1e-5) && near(xr3[2 * pl + at], (200 / 255f - 0.5f) / 0.5f, 1e-5),
        "M13 синий, зелёный, красный — по своим плоскостям до последней строки и столбца");
    ok(xr3[pad] == 0f && xr3[pl + pad] == 0f && xr3[2 * pl + pad] == 0f, "M13 справа добивка нулями во всех плоскостях");
    // M14 CTC: ничья — меньший номер, знак вне словаря — без текста, пусто — уверенность 0
    String[] ch3 = {"", "a", "b"};
    float[] tie = {0f, 0.5f, 0.5f, 0f, 0f}; float[] sc3 = new float[1];
    ok(OcrCore.ctc(tie, 0, 1, 5, ch3, sc3).equals("a"), "M14 равные вероятности — первый класс");
    float[] beyond = {0f, 0f, 0f, 0f, 0.9f, 0f, 0.6f, 0f, 0f, 0f};
    ok(OcrCore.ctc(beyond, 0, 2, 5, ch3, sc3).equals("a") && near(sc3[0], 0.75, 1e-6), "M14 класс за словарём — без знака, но в уверенности считается");
    float[] blank = {0.9f, 0.1f, 0f, 0.8f, 0.2f, 0f};
    ok(OcrCore.ctc(blank, 0, 2, 3, ch3, sc3).isEmpty() && sc3[0] == 0f, "M14 одни пустые — пусто, уверенность 0");
    float[] pair = {0f, 0.6f, 0f, 0f, 0f, 0.8f};
    ok(OcrCore.ctc(pair, 0, 2, 3, ch3, sc3).equals("ab") && near(sc3[0], 0.7, 1e-6), "M14 уверенность — среднее оставленных знаков");
    ok(OcrCore.ctc(pair, 3, 1, 3, ch3, null).equals("b"), "M14 сдвиг в пачке и без счёта уверенности");
    ok(OcrCore.dict("a\nb", 3).length == 3, "M15 классов столько же, сколько знаков с пустым, — пробел не добавляется");
    // M16 границы сборки строк (высота рамок 20)
    ok(texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "A"), item(123.9, 0, 200, 20, "B"))))).size() == 1, "M16 просвет 23,9 < 1,2 высоты — одна строка");
    ok(texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "A"), item(124, 0, 200, 20, "B"))))).size() == 2, "M16 просвет ровно 1,2 высоты — уже колонка");
    ok(texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "A"), item(90.1, 0, 200, 20, "B"))))).size() == 1, "M16 наезд 9,9 < полувысоты — одна строка");
    ok(texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "A"), item(90, 0, 200, 20, "B"))))).size() == 2, "M16 наезд ровно в полвысоты — разные");
    ok(texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "A"), item(102, 9.9, 200, 29.9, "B"))))).size() == 1, "M16 сдвиг по вертикали 9,9 — одна строка");
    ok(texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "A"), item(102, 10, 200, 30, "B"))))).size() == 2, "M16 сдвиг ровно в полвысоты — разные строки");
    ok(texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 5, 100, 15, "A"), item(101, 0, 200, 20, "B"))))).size() == 1, "M16 кегль вдвое больше — ещё одна строка");
    ok(texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 5, 100, 15, "A"), item(101, -0.5, 200, 20.5, "B"))))).size() == 2, "M16 кегль больше чем вдвое — разные");
    ok(texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "A"), slanted(102, 0, 40, 20, 14.8, "B"))))).size() == 1, "M16 наклон 14,8° — одна строка");
    ok(texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "A"), slanted(102, 0, 40, 20, 15.2, "B"))))).size() == 2, "M16 наклон 15,2° — разные");
    List<String> close = texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "A"), item(1, 16, 101, 36, "C"), item(103, 7, 150, 27, "B")))));
    ok(close.equals(Arrays.asList("A B", "C")), "M16 рамка между двумя строками — к той, чья средняя линия ближе: " + close);
    ok(texts(OcrCore.layout(new ArrayList<>(Arrays.asList(new OcrCore.Item(box(0, 0, 50, 0, 50, 20, 0, 20), "   "), item(60, 0, 100, 20, "A"))))).equals(Collections.singletonList("A")),
        "M16 рамка без текста в строки не идёт");
    List<String> ord = OcrCore.lines(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 100, 100, 120, "низ"), item(50, 0, 150, 20, "верх"))))).lines().collect(java.util.stream.Collectors.toList());
    ok(ord.equals(Arrays.asList("верх", "низ")), "M16 строки сверху вниз, хотя нижняя начинается левее: " + ord);
    // M16 строки внутри блока — по высоте, а не по порядку создания: нижняя строка абзаца
    // начинается левее и создаётся первой; без упорядочения абзац рвался на два блока
    List<String> para2 = paras(OcrCore.layout(new ArrayList<>(Arrays.asList(item(50, 0, 150, 20, "Rodovia estreita e"), item(0, 25, 150, 45, "extremamente sinuosa")))));
    ok(para2.equals(Collections.singletonList("Rodovia estreita e extremamente sinuosa")), "M16 абзац, где нижняя строка левее, — одним куском: " + para2);
    // M17 блоки — по верхнему краю: высокий блок, начатый выше, идёт первым, хотя его середина ниже
    List<String> hiTop = texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 15, 100, 25, "fino"), item(0, 2, 100, 42, "ALTO")))));
    ok(hiTop.equals(Arrays.asList("ALTO", "fino")), "M17 блоки упорядочены по верхнему краю: " + hiTop);
    // M17 границы блоков
    ok(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "Aaa"), item(0, 30, 100, 60, "Bbb")))).size() == 1, "M17 кегль в 1,5 раза — один блок");
    ok(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "Aaa"), item(0, 30, 100, 61, "Bbb")))).size() == 2, "M17 больше чем в 1,5 раза — разные блоки");
    ok(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "Aaa de"), item(0, 22, 100, 72, "Bbb")))).size() == 1, "M17 после служебного слова — и в 2,5 раза");
    ok(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "Aaa de"), item(0, 22, 100, 73, "Bbb")))).size() == 2, "M17 но не больше");
    ok(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "Aaa"), item(0, 35.9, 100, 55.9, "Bbb")))).size() == 1, "M17 шаг 35,9 < 1,8 высоты — один блок");
    ok(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "Aaa"), item(0, 36, 100, 56, "Bbb")))).size() == 2, "M17 шаг ровно 1,8 высоты — разные");
    ok(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "Aaa"), item(100, 25, 200, 45, "Bbb")))).size() == 2, "M17 строки встык по горизонтали, без перекрытия — разные блоки");
    ok(OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "Aaa"), item(99, 25, 200, 45, "Bbb")))).size() == 1, "M17 перекрытие в точку — один блок");
    List<List<OcrCore.Row>> once = OcrCore.layout(new ArrayList<>(Arrays.asList(item(0, 0, 100, 20, "Esq"), item(150, 0, 250, 20, "Dir"), item(0, 25, 250, 45, "largo"))));
    ok(OcrCore.lines(once).split("largo", -1).length == 2, "M17 строка под двумя колонками попадает в один блок, а не в оба");
    // M18 колонки: перестановка только при перекрытии по высоте больше 0,3 и если левая не заходит
    // за левый край правой больше чем на 1. Левая — крупным кеглем (41 против 20): иначе рамки
    // собрались бы в одну строку или один блок, и переставлять было бы нечего.
    List<String> sw = texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(300, 0, 400, 20, "Dir"), item(0, 13.9, 300, 54.9, "Esq")))));
    ok(sw.equals(Arrays.asList("Esq", "Dir")), "M18 перекрытие по высоте 6,1 из 20 — левая колонка первой: " + sw);
    List<String> ns = texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(300, 0, 400, 20, "Dir"), item(0, 14, 300, 55, "Esq")))));
    ok(ns.equals(Arrays.asList("Dir", "Esq")), "M18 перекрытие ровно 0,3 высоты — порядок сверху вниз: " + ns);
    List<String> e1 = texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(300, 0, 400, 20, "Dir"), item(0, 1, 301, 42, "Esq")))));
    ok(e1.equals(Arrays.asList("Esq", "Dir")), "M18 левая заходит за край правой на 1 — ещё колонка: " + e1);
    List<String> e2 = texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(300, 0, 400, 20, "Dir"), item(0, 1, 302, 42, "Esq")))));
    ok(e2.equals(Arrays.asList("Dir", "Esq")), "M18 заходит на 2 — уже не колонка: " + e2);
    List<String> three = texts(OcrCore.layout(new ArrayList<>(Arrays.asList(item(600, 0, 700, 20, "C"), item(300, 2, 400, 22, "B"), item(0, 4, 100, 24, "A")))));
    ok(three.equals(Arrays.asList("A", "B", "C")), "M18 три колонки, выше та, что правее, — слева направо за несколько проходов: " + three);
    // M19 абзацы: где склейки нет
    List<String> pz = new ArrayList<>();
    List<OcrCore.Row> prs = new ArrayList<>();
    for (String t : new String[]{"Tel. 3244-", "4445", "-", "abc", "Proibido fumar.", "e beber", "Aberto das 9 às 17:", "sábados"}) { OcrCore.Row r = new OcrCore.Row(); r.items.add(item(0, 0, 10, 10, t)); r.close(); prs.add(r); }
    for (OcrCore.Para pp : OcrCore.paragraphs(prs)) pz.add(pp.text);
    ok(pz.equals(Arrays.asList("Tel. 3244-", "4445", "- abc", "Proibido fumar.", "e beber", "Aberto das 9 às 17:", "sábados")),
        "M19 перенос после цифры — не перенос; точка и двоеточие фразу заканчивают, даже если дальше строчная: " + pz);
    // M20 цвета: четверти по сторонам рамки, в том числе у повёрнутой; рамка вне снимка; тёмный фон
    int R = 0xFFFF0000, G = 0xFF00FF00, B = 0xFF0000FF, Y = 0xFFFFFF00;
    int[] q4 = new int[40 * 40];
    for (int y = 0; y < 40; y++) for (int x = 0; x < 40; x++) q4[y * 40 + x] = x < 20 ? (y < 20 ? R : G) : (y < 20 ? B : Y);
    int[] qc = OcrCore.colors(q4, 40, 40, new double[]{20, 20, 1, 0, 16, 8});
    ok(qc[0] == R && qc[1] == G && qc[2] == B && qc[3] == Y, "M20 четверти: лево-верх, лево-низ, право-верх, право-низ");
    int[] qr = OcrCore.colors(q4, 40, 40, new double[]{20, 20, 0, 1, 16, 8});
    ok(qr[0] == B && qr[1] == R && qr[2] == Y && qr[3] == G, "M20 рамка повёрнута на 90° — четверти по её сторонам, а не по осям снимка");
    int[] out = OcrCore.colors(q4, 40, 40, new double[]{500, 500, 1, 0, 16, 8});
    ok(out[0] == 0xFFFFFFFF && out[3] == 0xFFFFFFFF && out[4] == 0xFF000000, "M20 рамка вне снимка — белый фон, чёрный текст");
    int[] dark = new int[40 * 40]; Arrays.fill(dark, 0xFF282828);
    for (int y = 17; y < 23; y++) for (int x = 13; x < 27; x += 2) dark[y * 40 + x] = 0xFF3C3C3C;
    ok(OcrCore.colors(dark, 40, 40, new double[]{20, 20, 1, 0, 16, 8})[4] == 0xFFFFFFFF, "M20 тёмная вывеска, текст почти не отличается — белым");
    int[] ink = new int[40 * 40]; Arrays.fill(ink, 0xFFC8C8C8);
    for (int y = 16; y <= 24; y++) for (int x = 12; x <= 28; x++) ink[y * 40 + x] = (x % 4 == 0) ? 0xFF102040 : 0xFFFFFFFF;
    ok(OcrCore.colors(ink, 40, 40, new double[]{20, 20, 1, 0, 16, 8})[4] == 0xFF102040, "M20 цвет текста — самая отличная от фона четверть пикселей внутри");
    // M21 медиана и квантиль
    int[] h2 = new int[256]; h2[1] = 1; h2[3] = 1;
    int[] h3 = new int[256]; h3[1] = 1; h3[2] = 1; h3[9] = 1;
    int[] h1 = new int[256]; h1[7] = 1;
    ok(OcrCore.median(h2, 2) == 2 && OcrCore.median(h3, 3) == 2 && OcrCore.median(h1, 1) == 7, "M21 медиана: чётное — среднее двух, нечётное — середина, одно — оно");
    ok(near(OcrCore.quantile(new double[]{0, 10, 20, 30}, 0.75), 22.5, 1e-12) && OcrCore.quantile(new double[]{5}, 0.75) == 5, "M21 квантиль с интерполяцией, как np.quantile");

    // --- G: сверка с эталоном ---
    int done = 0;
    if (golden != null && golden.isDirectory()) {
      File[] dirs = golden.listFiles(File::isDirectory); if (dirs != null) Arrays.sort(dirs);
      for (File d : dirs == null ? new File[0] : dirs) { if (new File(d, "meta.json").exists()) { golden(d); done++; } }
    }
    if (done == 0) System.out.println("  (сверка с эталоном пропущена: нет bench/ocr/runs/golden — .venv/bin/python tools/ocr_golden.py)");
    System.out.println(fails == 0 ? "OcrCore: " + checks + " проверок, все прошли" + (done > 0 ? " (эталон: " + done + " снимка)" : "") : "OcrCore: провалов " + fails + " из " + checks);
    return fails;
  }

  static float[] f32(File f) throws Exception {
    ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(f.toPath())).order(ByteOrder.LITTLE_ENDIAN);
    float[] out = new float[b.remaining() / 4]; b.asFloatBuffer().get(out); return out;
  }

  static void golden(File d) throws Exception {
    String id = d.getName();
    JSONObject m = new JSONObject(new String(Files.readAllBytes(new File(d, "meta.json").toPath()), "UTF-8"));
    int w = m.getInt("w"), h = m.getInt("h"), pw = m.getInt("pw"), ph = m.getInt("ph");
    byte[] raw = Files.readAllBytes(new File(d, "rgb.u8").toPath()); int[] argb = new int[w * h];
    for (int i = 0; i < argb.length; i++) argb[i] = 0xFF000000 | ((raw[3 * i] & 255) << 16) | ((raw[3 * i + 1] & 255) << 8) | (raw[3 * i + 2] & 255);
    ok(Arrays.equals(OcrCore.detSize(w, h), new int[]{pw, ph}), "G1 " + id + ": размер входа детектора как в эталоне");
    float[] din = OcrCore.detInput(argb, w, h, pw, ph), pin = f32(new File(d, "det_in.f32")); double md = 0;
    for (int i = 0; i < din.length; i++) md = Math.max(md, Math.abs(din[i] - pin[i]));
    ok(din.length == pin.length && md < 2e-3, "G2 " + id + ": вход детектора совпадает с эталоном (наибольшее расхождение " + String.format("%.1e", md) + ")");
    List<OcrCore.Strip> st = new ArrayList<>();
    List<double[]> bx = OcrCore.boxes(f32(new File(d, "prob.f32")), pw, ph, w, h, st);
    JSONArray eb = m.getJSONArray("boxes"); double mb = 0; boolean cnt = bx.size() == eb.length();
    for (int i = 0; cnt && i < bx.size(); i++) for (int k = 0; k < 8; k++) mb = Math.max(mb, Math.abs(bx.get(i)[k] - eb.getJSONArray(i).getDouble(k)));
    ok(cnt && mb < 1e-3, "G3 " + id + ": рамки строк как в эталоне (" + bx.size() + " против " + eb.length() + ", расхождение " + String.format("%.1e", mb) + " px)");
    if (!cnt || bx.isEmpty()) return;
    int[] wh = new int[2]; int[] c0 = OcrCore.crop(argb, w, h, bx.get(0), wh);
    JSONArray ec = m.getJSONArray("crop0"); int diff = 0;
    for (int i = 0; i < c0.length && 3 * i + 2 < ec.length(); i++)
      for (int c = 0; c < 3; c++) diff = Math.max(diff, Math.abs(((c0[i] >> (16 - 8 * c)) & 255) - ec.getInt(3 * i + c)));
    ok(ec.length() == 3 * c0.length && diff <= 1, "G4 " + id + ": вырез первой строки совпадает (до единицы яркости: " + diff + ")");
    // полосы изогнутых и тесных строк: те же строки и те же параметры, что у эталона
    JSONArray es = m.optJSONArray("strips");
    if (es == null) { ok(false, "G9 " + id + ": эталон старого формата, без полос — пересоберите: .venv/bin/python tools/ocr_cylinder.py && .venv/bin/python tools/ocr_golden.py"); return; }
    boolean sameSet = st.size() == es.length(); double mq = 0; int ns = 0;
    for (int i = 0; sameSet && i < st.size(); i++) {
      OcrCore.Strip a = st.get(i); boolean isNull = es.isNull(i);
      if ((a == null) != isNull) { sameSet = false; break; }
      if (a == null) continue;
      ns++; JSONObject e = es.getJSONObject(i); JSONArray q = e.getJSONArray("q");
      mq = Math.max(mq, Math.max(Math.abs(a.q0 - q.getDouble(0)), Math.max(Math.abs(a.q1 - q.getDouble(1)), Math.abs(a.q2 - q.getDouble(2)))));
      mq = Math.max(mq, Math.max(Math.abs(a.T - e.getDouble("T")), Math.max(Math.abs(a.d - e.getDouble("d")), Math.abs(a.s1 - e.getDouble("s1")))));
      if (a.crowded != e.getBoolean("crowded")) sameSet = false;
    }
    ok(sameSet && mq < 1e-6, "G9 " + id + ": полосы изогнутых и тесных строк как у эталона (" + ns + ", расхождение " + String.format("%.1e", mq) + ")");
    if (!m.isNull("strip0")) {
      JSONObject s0 = m.getJSONObject("strip0"); int[] swh = new int[2];
      int[] sc = OcrCore.cropStrip(argb, w, h, st.get(s0.getInt("k")), swh);
      JSONArray px = s0.getJSONArray("px"); int sdiff = 0, over = 0;
      for (int i = 0; i < sc.length && 3 * i + 2 < px.length(); i++)
        for (int c = 0; c < 3; c++) { int dd = Math.abs(((sc[i] >> (16 - 8 * c)) & 255) - px.getInt(3 * i + c)); sdiff = Math.max(sdiff, dd); if (dd > 1) over++; }
      ok(swh[0] == s0.getInt("w") && swh[1] == s0.getInt("h") && px.length() == 3 * sc.length && sdiff <= 1,
          "G10 " + id + ": вырез первой полосы " + swh[0] + "×" + swh[1] + " совпадает (до единицы яркости: " + sdiff + ")");
    }
    List<int[]> shapes = new ArrayList<>(); List<int[]> crops = new ArrayList<>();
    for (int i = 0; i < bx.size(); i++) { int[] s = new int[2]; crops.add(st.get(i) != null ? OcrCore.cropStrip(argb, w, h, st.get(i), s) : OcrCore.crop(argb, w, h, bx.get(i), s)); shapes.add(s); }
    Integer[] ord = OcrCore.recOrder(shapes); int n = Math.min(OcrCore.REC_BATCH, ord.length);
    List<int[]> first = new ArrayList<>(); for (int j = 0; j < n; j++) first.add(shapes.get(ord[j]));
    int W = OcrCore.recWidth(first);
    ok(W == m.getJSONObject("rec0").getInt("W"), "G5 " + id + ": ширина первой пачки распознавателя как в эталоне (" + W + ")");
    float[] xr = new float[n * 3 * OcrCore.REC_H * W];
    for (int j = 0; j < n; j++) { int k = ord[j]; OcrCore.recInput(crops.get(k), shapes.get(k)[0], shapes.get(k)[1], xr, j, W); }
    float[] er = f32(new File(d, "rec0.f32")); double mr = 0;
    for (int i = 0; i < Math.min(xr.length, er.length); i++) mr = Math.max(mr, Math.abs(xr[i] - er[i]));
    ok(xr.length == er.length && mr < 2e-2, "G6 " + id + ": вход распознавателя совпадает с эталоном (расхождение " + String.format("%.1e", mr) + ")");
    List<OcrCore.Item> items = new ArrayList<>(); JSONArray tx = m.getJSONArray("texts");
    for (int i = 0; i < bx.size(); i++) {
      JSONArray t = tx.getJSONArray(i);
      if (t.getDouble(1) >= OcrCore.MIN_SCORE && !t.getString(0).trim().isEmpty()) items.add(new OcrCore.Item(bx.get(i), t.getString(0), t.getDouble(1)));
    }
    List<List<OcrCore.Row>> blocks = OcrCore.layout(items);
    ok(OcrCore.lines(blocks).equals(m.getString("lines")), "G7 " + id + ": строки и их порядок как в эталоне");
    ok(String.join("\n", paras(blocks)).equals(m.getString("text")), "G8 " + id + ": абзацы для перевода как в эталоне");
    JSONArray ecf = m.getJSONArray("conf"); List<Double> cf = new ArrayList<>();
    for (List<OcrCore.Row> bl : blocks) for (OcrCore.Para p : OcrCore.paragraphs(bl)) cf.add(p.score());
    double mc = cf.size() == ecf.length() ? 0 : 1;
    for (int i = 0; i < Math.min(cf.size(), ecf.length()); i++) mc = Math.max(mc, Math.abs(cf.get(i) - ecf.getDouble(i)));
    ok(mc < 1e-9, "G11 " + id + ": уверенность абзацев как у эталона");
  }

  public static void main(String[] a) throws Exception { System.exit(run(a.length > 0 ? new File(a[0]) : null) == 0 ? 0 : 1); }
}
