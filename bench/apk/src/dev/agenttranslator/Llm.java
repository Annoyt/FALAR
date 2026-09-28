package dev.agenttranslator;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Локальный llama-server (llama.cpp, официальная Android-сборка) как подпроцесс из nativeLibraryDir; OpenAI-совместимый чат по localhost. */
public class Llm {
  public final String nativeDir, model, logFile; Process proc; final int port = 8089; public volatile boolean ready = false;
  /** Как сервер держит веса: «mmap» — страницами файла, которые система под нагрузкой вытесняет и
   *  дочитывает при надобности; «none» — чтением в обычную память, которую можно только убить.
   *  С «none» на Redmi через 5–35 с после загрузки система каждый раз слала критический сигнал
   *  памяти: приложение вместе с уточнителем — 3,6 ГБ, и всё это невытесняемое. */
  public String loadMode = "mmap";
  public Llm(String nativeDir, String modelPath, String logFile) { this.nativeDir = nativeDir; this.model = modelPath; this.logFile = logFile; }
  public synchronized boolean start(int threads) throws Exception {
    if (ready) return true;
    ProcessBuilder pb = new ProcessBuilder(nativeDir + "/libllama-server.so", "--model", model, "--host", "127.0.0.1", "--port", "" + port, "-t", "" + threads, "-c", "" + Brief.CTX_TOKENS, "-np", "1", "-ngl", "0", "--jinja", "--no-webui", "--load-mode", loadMode);
    // -np 1: один слот. По умолчанию сервер держал буферы под четыре параллельных запроса
    // (n_slots = 4 в llama-server.log), а приложение шлёт по одному.
    pb.environment().put("LD_LIBRARY_PATH", nativeDir); pb.redirectErrorStream(true); pb.redirectOutput(new File(logFile));
    proc = pb.start();
    String lastErr = "";
    for (int i = 0; i < 360; i++) { Thread.sleep(500); try { HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/health").openConnection(); c.setConnectTimeout(300); c.setReadTimeout(1000); if (c.getResponseCode() == 200) { ready = true; return true; } } catch (IOException e) { lastErr = e.toString(); if (!proc.isAlive()) throw new IOException("llama-server завершился с кодом " + proc.exitValue() + ", см. " + logFile); } }
    stop(); throw new IOException("нет ответа /health за 180 с; последняя ошибка: " + lastErr);
  }
  public String chat(String system, String user, int maxTokens) throws Exception {
    JSONArray msgs = new JSONArray(); if (system != null) msgs.put(new JSONObject().put("role", "system").put("content", system)); msgs.put(new JSONObject().put("role", "user").put("content", user));
    JSONObject body = new JSONObject().put("temperature", 0).put("max_tokens", maxTokens).put("messages", msgs);
    HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/v1/chat/completions").openConnection();
    c.setRequestMethod("POST"); c.setRequestProperty("Content-Type", "application/json"); c.setDoOutput(true); c.setConnectTimeout(2000); c.setReadTimeout(120000);
    try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
    try (InputStream in = c.getResponseCode() == 200 ? c.getInputStream() : c.getErrorStream()) { String r = new String(readAll(in), StandardCharsets.UTF_8); if (c.getResponseCode() != 200) throw new IOException(r); return new JSONObject(r).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim(); }
  }
  /** Сколько токенов даёт текст — настоящим токенизатором модели. Нужен, чтобы бюджет контекста
   *  считался из замера, а не из догадки о знаках на токен. */
  public int tokens(String text) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/tokenize").openConnection();
    c.setRequestMethod("POST"); c.setRequestProperty("Content-Type", "application/json"); c.setDoOutput(true); c.setConnectTimeout(2000); c.setReadTimeout(10000);
    try (OutputStream o = c.getOutputStream()) { o.write(new JSONObject().put("content", text).toString().getBytes(StandardCharsets.UTF_8)); }
    try (InputStream in = c.getInputStream()) { return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8)).getJSONArray("tokens").length(); }
  }
  static byte[] readAll(InputStream in) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); return b.toByteArray(); }
  /** Остановить сервер наверняка: мягко, а если через две секунды жив — жёстко. Раньше был только
   *  destroy(), и если сервер его не обрабатывал, процесс на 1,1 ГБ оставался жить. */
  public synchronized void stop() {
    ready = false;
    Process p = proc; proc = null;
    if (p == null) return;
    p.destroy();
    try { if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly(); }
    catch (InterruptedException e) { p.destroyForcibly(); Thread.currentThread().interrupt(); }
  }
  /** Номера процессов llama-server по /proc. Процессы своего пользователя приложению видны, и если
   *  сервис убили жёстко (системой или «очисткой памяти»), onDestroy не вызывался и сервер остался
   *  сиротой: 1,1 ГБ, занятый порт, а новый сервер падает на нём же или, хуже, проверка /health
   *  отвечает от сироты, и приложение считает своим чужой процесс. */
  public static List<Integer> strays() {
    List<Integer> r = new ArrayList<>();
    File[] ps = new File("/proc").listFiles();
    if (ps == null) return r;
    for (File d : ps) {
      String n = d.getName(); if (n.isEmpty() || !Character.isDigit(n.charAt(0))) continue;
      try (InputStream in = new FileInputStream(new File(d, "cmdline"))) {
        byte[] b = new byte[512]; int k = in.read(b);
        if (k > 0 && new String(b, 0, k, StandardCharsets.UTF_8).contains("libllama-server.so")) r.add(Integer.parseInt(n));
      } catch (Exception ignore) {}
    }
    return r;
  }
}
