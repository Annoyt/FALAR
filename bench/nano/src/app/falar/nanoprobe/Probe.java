package app.falar.nanoprobe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import com.google.mlkit.genai.common.DownloadCallback;
import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.prompt.Candidate;
import com.google.mlkit.genai.prompt.GenerateContentRequest;
import com.google.mlkit.genai.prompt.GenerateContentResponse;
import com.google.mlkit.genai.prompt.Generation;
import com.google.mlkit.genai.prompt.GenerationConfig;
import com.google.mlkit.genai.prompt.ModelConfig;
import com.google.mlkit.genai.prompt.ModelPreference;
import com.google.mlkit.genai.prompt.ModelReleaseStage;
import com.google.mlkit.genai.prompt.SystemInstruction;
import com.google.mlkit.genai.prompt.TextPart;
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Стенд Gemini Nano через ML Kit GenAI Prompt API — отдельное приложение, не Falar.
 *
 *   am start -n app.falar.nanoprobe/.Probe --es cmd status|download|warmup|run|stop
 *     run: --es job job.tsv --es out out.tsv [--es sys sys.txt] [--ei gap мс] [--ef temp 0]
 *          [--ei topk 1] [--ei seed 0] [--ei maxtok 256] [--es pref full|fast] [--es stage stable|preview]
 *
 * Файлы — в getExternalFilesDir (Android/data/app.falar.nanoprobe/files). job.tsv: «id\tзапрос», в запросе
 * \n и \t экранированы. out.tsv дописывается построчно («id\tмс\tкод\tпричина\tответ»), уже сделанные id
 * пропускаются — прогон можно продолжить. Конец прогона — файл out.tsv.done со сводкой. Журнал — тег NANO.
 * Вывод возможен, только пока приложение на экране (иначе BACKGROUND_USE_BLOCKED): экран держим включённым.
 */
public class Probe extends Activity {
  static final String TAG = "NANO";
  TextView tv;
  File dir;
  volatile boolean visible, stop;
  Thread worker;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    tv = new TextView(this);
    tv.setTextSize(15);
    tv.setPadding(32, 96, 32, 32);
    setContentView(tv);
    dir = getExternalFilesDir(null);
    show("Стенд Gemini Nano. Каталог: " + dir);
    handle(getIntent());
  }

  @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handle(i); }
  @Override protected void onResume() { super.onResume(); visible = true; log("на экране"); }
  @Override protected void onPause() { visible = false; log("ушло с экрана"); super.onPause(); }

  void log(String s) { Log.i(TAG, s); }
  void show(String s) { log(s); runOnUiThread(() -> tv.setText(s)); }

  GenerativeModelFutures model(Intent i) {
    String pref = str(i, "pref", "full"), stage = str(i, "stage", "stable");
    ModelConfig.Builder mb = new ModelConfig.Builder();
    mb.setPreference("fast".equals(pref) ? ModelPreference.FAST : ModelPreference.FULL);
    mb.setReleaseStage("preview".equals(stage) ? ModelReleaseStage.PREVIEW : ModelReleaseStage.STABLE);
    GenerationConfig.Builder gb = new GenerationConfig.Builder();
    gb.setModelConfig(mb.build());
    return GenerativeModelFutures.from(Generation.INSTANCE.getClient(gb.build()));
  }

  static String str(Intent i, String k, String d) { String v = i.getStringExtra(k); return v == null ? d : v; }

  void handle(Intent i) {
    String cmd = str(i, "cmd", "");
    if (cmd.isEmpty()) return;
    if ("stop".equals(cmd)) { stop = true; log("стоп"); return; }
    if (worker != null && worker.isAlive()) { log("занят — " + cmd + " не начинаю"); return; }
    stop = false;
    Intent copy = new Intent(i);
    worker = new Thread(() -> {
      try {
        switch (cmd) {
          case "status": status(copy); break;
          case "download": download(copy); break;
          case "warmup": warmup(copy); break;
          case "run": run(copy); break;
          default: log("не знаю cmd=" + cmd);
        }
      } catch (Throwable t) {
        Log.e(TAG, "сбой " + cmd, t);
        write(new File(dir, cmd + ".err"), Log.getStackTraceString(t));
      }
    }, "nano-" + cmd);
    worker.start();
  }

  static String errText(Throwable t) {
    Throwable c = t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t;
    if (c instanceof GenAiException) return "GenAiException код " + ((GenAiException) c).getErrorCode() + ": " + c.getMessage();
    return c.getClass().getSimpleName() + ": " + c.getMessage();
  }

  static <T> String tryGet(com.google.common.util.concurrent.ListenableFuture<T> f) {
    try { return String.valueOf(f.get(60, TimeUnit.SECONDS)); } catch (Throwable t) { return "ошибка (" + errText(t) + ")"; }
  }

  void status(Intent i) {
    GenerativeModelFutures m = model(i);
    StringBuilder sb = new StringBuilder();
    sb.append("status=").append(tryGet(m.checkStatus())).append(" (0 нет, 1 можно скачать, 2 качается, 3 готово)\n");
    sb.append("model=").append(tryGet(m.getBaseModelName())).append('\n');
    sb.append("token_limit=").append(tryGet(m.getTokenLimit())).append('\n');
    sb.append("system_prompt=").append(tryGet(m.isSystemPromptAvailable())).append('\n');
    sb.append("thinking=").append(tryGet(m.isThinkingModeAvailable())).append('\n');
    sb.append("pref=").append(str(i, "pref", "full")).append(" stage=").append(str(i, "stage", "stable")).append('\n');
    write(new File(dir, "status.txt"), sb.toString());
    show(sb.toString());
  }

  void download(Intent i) throws Exception {
    GenerativeModelFutures m = model(i);
    File f = new File(dir, "download.txt");
    final long[] total = {0};
    m.download(new DownloadCallback() {
      @Override public void onDownloadStarted(long bytes) { total[0] = bytes; write(f, "started " + bytes); show("загрузка: " + bytes + " байт"); }
      @Override public void onDownloadProgress(long bytes) { write(f, "progress " + bytes + " / " + total[0]); show("загрузка: " + bytes + " / " + total[0]); }
      @Override public void onDownloadCompleted() { write(f, "completed"); show("модель загружена"); }
      @Override public void onDownloadFailed(GenAiException e) { write(f, "failed " + e.getErrorCode() + " " + e.getMessage()); show("загрузка не удалась: " + e.getErrorCode()); }
    }).get();
  }

  void warmup(Intent i) {
    long t0 = System.nanoTime();
    String r = tryGet(model(i).warmup());
    String s = String.format(Locale.ROOT, "warmup %s за %d мс", r, (System.nanoTime() - t0) / 1_000_000);
    write(new File(dir, "warmup.txt"), s);
    show(s);
  }

  void run(Intent i) throws Exception {
    File job = new File(dir, str(i, "job", "job.tsv")), out = new File(dir, str(i, "out", "out.tsv"));
    String sysName = i.getStringExtra("sys");
    String sys = sysName == null ? null : read(new File(dir, sysName)).trim();
    int gap = i.getIntExtra("gap", 0), topk = i.getIntExtra("topk", 1), seed = i.getIntExtra("seed", 0),
        maxtok = i.getIntExtra("maxtok", 256);
    float temp = i.getFloatExtra("temp", 0f);
    List<String[]> items = new ArrayList<>();
    for (String line : read(job).split("\n")) {
      int t = line.indexOf('\t');
      if (t > 0) items.add(new String[] {line.substring(0, t), unesc(line.substring(t + 1))});
    }
    Set<String> done = new HashSet<>();
    if (out.exists()) for (String line : read(out).split("\n")) { int t = line.indexOf('\t'); if (t > 0) done.add(line.substring(0, t)); }
    GenerativeModelFutures m = model(i);
    log(String.format(Locale.ROOT, "прогон %s: %d заданий, уже есть %d · temp %.2f topk %d seed %d maxtok %d gap %d · sys %s",
        job.getName(), items.size(), done.size(), temp, topk, seed, maxtok, gap, sys == null ? "нет" : sysName));
    TreeMap<Integer, Integer> codes = new TreeMap<>();
    int ok = 0, busy = 0, n = 0;
    long tAll = System.nanoTime(), msSum = 0;
    try (Writer w = new OutputStreamWriter(new FileOutputStream(out, true), StandardCharsets.UTF_8)) {
      for (String[] it : items) {
        if (stop) { log("остановлено"); break; }
        if (done.contains(it[0])) continue;
        GenerateContentRequest.Builder rb = sys == null
            ? new GenerateContentRequest.Builder(new TextPart(it[1]))
            : new GenerateContentRequest.Builder(new SystemInstruction(sys), new TextPart(it[1]));
        rb.setTemperature(temp); rb.setTopK(topk); rb.setSeed(seed); rb.setMaxOutputTokens(maxtok); rb.setCandidateCount(1);
        GenerateContentRequest req = rb.build();
        int tries = 0;
        while (true) {
          while (!visible && !stop) Thread.sleep(1000);  // вне экрана вывод запрещён — ждём возвращения
          long t0 = System.nanoTime();
          try {
            GenerateContentResponse r = m.generateContent(req).get(180, TimeUnit.SECONDS);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Candidate c = r.getCandidates().isEmpty() ? null : r.getCandidates().get(0);
            String text = c == null || c.getText() == null ? "" : c.getText();
            w.write(it[0] + "\t" + ms + "\t0\t" + (c == null ? "" : c.getFinishReason()) + "\t" + esc(text) + "\n");
            w.flush();
            ok++; msSum += ms;
            break;
          } catch (ExecutionException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int code = e.getCause() instanceof GenAiException ? ((GenAiException) e.getCause()).getErrorCode() : -1;
            if ((code == GenAiException.ErrorCode.BUSY || code == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED
                || code == GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED) && tries < 12 && !stop) {
              tries++;
              if (code == GenAiException.ErrorCode.BUSY) busy++;
              java.time.Duration rd = ((GenAiException) e.getCause()).getRetryDelay();
              long wait = Math.max(rd == null ? 0 : rd.toMillis(), Math.min(300_000L, 2000L << Math.min(tries, 7)));
              if (code == GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED) wait = Math.max(wait, 60_000L);
              log(String.format(Locale.ROOT, "%s: код %d, жду %d мс (попытка %d)", it[0], code, wait, tries));
              Thread.sleep(wait);
              continue;
            }
            codes.merge(code, 1, Integer::sum);
            w.write(it[0] + "\t" + ms + "\t" + code + "\t\t" + esc(errText(e)) + "\n");
            w.flush();
            break;
          }
        }
        n++;
        if (n % 10 == 0) show(String.format(Locale.ROOT, "%s: %d из %d (ok %d, BUSY %d, ошибки %s), среднее %d мс",
            job.getName(), done.size() + n, items.size(), ok, busy, codes, ok == 0 ? 0 : msSum / ok));
        if (gap > 0) Thread.sleep(gap);
      }
    }
    String sum = String.format(Locale.ROOT, "сделано %d, ok %d, BUSY-повторов %d, ошибки %s, среднее %d мс, всего %d с",
        n, ok, busy, codes, ok == 0 ? 0 : msSum / ok, (System.nanoTime() - tAll) / 1_000_000_000L);
    write(new File(dir, out.getName() + ".done"), sum);
    show(job.getName() + ": " + sum);
  }

  static String esc(String s) { return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", ""); }

  static String unesc(String s) {
    StringBuilder b = new StringBuilder();
    for (int k = 0; k < s.length(); k++) {
      char c = s.charAt(k);
      if (c == '\\' && k + 1 < s.length()) {
        char d = s.charAt(++k);
        b.append(d == 'n' ? '\n' : d == 't' ? '\t' : d);
      } else b.append(c);
    }
    return b.toString();
  }

  static String read(File f) throws Exception {
    StringBuilder b = new StringBuilder();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
      for (String l; (l = r.readLine()) != null; ) b.append(l).append('\n');
    }
    return b.toString();
  }

  static void write(File f, String s) {
    try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) { w.write(s + "\n"); }
    catch (Exception e) { Log.e(TAG, "не записал " + f, e); }
  }
}
