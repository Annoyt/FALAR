package dev.agenttranslator;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Локальный llama-server (llama.cpp, официальная Android-сборка) как подпроцесс из nativeLibraryDir; OpenAI-совместимый чат по localhost. */
public class Llm {
  public final String nativeDir, model, logFile; Process proc; final int port = 8089; public volatile boolean ready = false;
  public Llm(String nativeDir, String modelPath, String logFile) { this.nativeDir = nativeDir; this.model = modelPath; this.logFile = logFile; }
  public synchronized boolean start(int threads) throws Exception {
    if (ready) return true;
    ProcessBuilder pb = new ProcessBuilder(nativeDir + "/libllama-server.so", "--model", model, "--host", "127.0.0.1", "--port", "" + port, "-t", "" + threads, "-c", "2048", "-ngl", "0", "--jinja", "--no-webui", "--load-mode", "none");
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
  static byte[] readAll(InputStream in) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); return b.toByteArray(); }
  public synchronized void stop() { ready = false; if (proc != null) { proc.destroy(); proc = null; } }
}
