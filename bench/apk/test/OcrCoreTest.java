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
    OcrCore.Para para = OcrCore.paragraphs(OcrCore.layout(its).get(0)).get(0);
    double[] f = para.frame();
    ok(near(f[0], 105, 1e-9) && near(f[1], 20, 1e-9) && near(f[4], 190, 1e-9) && near(f[5], 20, 1e-9), "L9 прямоугольник абзаца — по его рамкам");

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
    List<double[]> bx = OcrCore.boxes(f32(new File(d, "prob.f32")), pw, ph, w, h);
    JSONArray eb = m.getJSONArray("boxes"); double mb = 0; boolean cnt = bx.size() == eb.length();
    for (int i = 0; cnt && i < bx.size(); i++) for (int k = 0; k < 8; k++) mb = Math.max(mb, Math.abs(bx.get(i)[k] - eb.getJSONArray(i).getDouble(k)));
    ok(cnt && mb < 1e-3, "G3 " + id + ": рамки строк как в эталоне (" + bx.size() + " против " + eb.length() + ", расхождение " + String.format("%.1e", mb) + " px)");
    if (!cnt || bx.isEmpty()) return;
    int[] wh = new int[2]; int[] c0 = OcrCore.crop(argb, w, h, bx.get(0), wh);
    JSONArray ec = m.getJSONArray("crop0"); int diff = 0;
    for (int i = 0; i < c0.length && 3 * i + 2 < ec.length(); i++)
      for (int c = 0; c < 3; c++) diff = Math.max(diff, Math.abs(((c0[i] >> (16 - 8 * c)) & 255) - ec.getInt(3 * i + c)));
    ok(ec.length() == 3 * c0.length && diff <= 1, "G4 " + id + ": вырез первой строки совпадает (до единицы яркости: " + diff + ")");
    List<int[]> shapes = new ArrayList<>(); List<int[]> crops = new ArrayList<>();
    for (double[] b : bx) { int[] s = new int[2]; crops.add(OcrCore.crop(argb, w, h, b, s)); shapes.add(s); }
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
      if (t.getDouble(1) >= OcrCore.MIN_SCORE && !t.getString(0).trim().isEmpty()) items.add(new OcrCore.Item(bx.get(i), t.getString(0)));
    }
    List<List<OcrCore.Row>> blocks = OcrCore.layout(items);
    ok(OcrCore.lines(blocks).equals(m.getString("lines")), "G7 " + id + ": строки и их порядок как в эталоне");
    ok(String.join("\n", paras(blocks)).equals(m.getString("text")), "G8 " + id + ": абзацы для перевода как в эталоне");
  }

  public static void main(String[] a) throws Exception { System.exit(run(a.length > 0 ? new File(a[0]) : null) == 0 ? 0 : 1); }
}
