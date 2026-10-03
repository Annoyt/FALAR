package dev.agenttranslator;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Звук реплики собеседника на диске — сжатие и обратно.
 *
 *  Хранится ровно то, что слышал распознаватель (сегмент без подставленной тишины), 16 кГц моно:
 *  по нему человек переслушивает реплику и слышит живой голос в примерах «Слов». Формат — по
 *  расширению файла: .ogg (Opus), .m4a (AAC-LC), .wav (PCM16). Какой из них пишет приложение, решил
 *  замер на Redmi (results/2026-10-03-clip-codec.md); читается любой.
 *
 *  Кодек системный (MediaCodec): программные кодеки Android работают в процессе media.swcodec, и
 *  в APK не прибавляется ни одной библиотеки. Тот же класс гоняет замер bench/audio/ClipBench.java
 *  через app_process — мерится тот код, что работает в приложении. */
final class ClipCodec {
  static final String OPUS = "audio/opus", AAC = MediaFormat.MIMETYPE_AUDIO_AAC;
  /** Сколько ждать один буфер кодека и весь кусочек: зависший кодек не должен держать поток вечно. */
  static final long WAIT_US = 10_000, LIMIT_MS = 20_000;

  /** Уступить живому пути: вызывается между буферами кодека и возвращается, когда можно продолжать
   *  (в приложении — ждёт затишья, TranslatorService.quietFor). Время ожидания в предел кусочка не идёт. */
  interface Yield { void pause() throws InterruptedException; }

  private ClipCodec() {}

  /** Сжать pcm (−1…1, моно) в файл; формат — по расширению out. bitrate для .wav не нужен.
   *  Пишется во временный файл и переименовывается: недописанный кусочек не выглядит готовым. */
  static void encode(float[] pcm, int rate, File out, int bitrate) throws IOException { encode(pcm, rate, out, bitrate, -1, null); }
  /** complexity — сложность кодирования Opus 0…10 (−1 — как у кодека); yield — уступка между буферами. */
  static void encode(float[] pcm, int rate, File out, int bitrate, int complexity, Yield yield) throws IOException {
    if (pcm == null || pcm.length < rate / 10) throw new IOException("кусочек короче 0,1 с");
    File tmp = new File(out.getPath() + ".part");
    String n = out.getName();
    if (n.endsWith(".wav")) writeWav(pcm, rate, tmp);
    else if (n.endsWith(".ogg")) encodeCodec(pcm, rate, tmp, OPUS, bitrate, complexity, yield, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG);
    else if (n.endsWith(".m4a")) encodeCodec(pcm, rate, tmp, AAC, bitrate, -1, yield, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    else throw new IOException("неизвестный формат: " + n);
    if (!tmp.renameTo(out)) { tmp.delete(); throw new IOException("не переименовать " + tmp.getName()); }
  }

  /** Цикл кодера. Первый замер 03.10 (2,8 с на кусочек 5 с у Opus) — во многом цикл: после каждого
   *  крошечного входного буфера (кадр Opus — 20 мс) он ждал выход до 10 мс. Теперь выход ждётся, только
   *  когда кодек не взял вход: пока вход идёт, ожидания нет. */
  static void encodeCodec(float[] pcm, int rate, File out, String mime, int bitrate, int complexity, Yield yield, int container) throws IOException {
    MediaFormat f = MediaFormat.createAudioFormat(mime, rate, 1);
    f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
    if (AAC.equals(mime)) f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
    if (complexity >= 0) f.setInteger(MediaFormat.KEY_COMPLEXITY, complexity);
    MediaCodec c = MediaCodec.createEncoderByType(mime);
    MediaMuxer mx = null; boolean started = false;
    try {
      c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      c.start();
      mx = new MediaMuxer(out.getPath(), container);
      MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
      int track = -1, pos = 0; boolean inDone = false;
      long until = System.currentTimeMillis() + LIMIT_MS;
      while (true) {
        if (yield != null) { long t0 = System.currentTimeMillis(); try { yield.pause(); } catch (InterruptedException e) { throw new InterruptedIOException("сжатие прервано"); } until += System.currentTimeMillis() - t0; }
        if (System.currentTimeMillis() > until) throw new IOException("кодек не ответил за " + LIMIT_MS / 1000 + " с");
        boolean fed = false;
        if (!inDone) {
          int ii = c.dequeueInputBuffer(0);
          if (ii >= 0) {
            ByteBuffer b = c.getInputBuffer(ii); b.clear(); b.order(ByteOrder.LITTLE_ENDIAN);
            int k = Math.min(b.remaining() / 2, pcm.length - pos);
            for (int j = 0; j < k; j++) b.putShort(pcm16(pcm[pos + j]));
            long pts = pos * 1_000_000L / rate;
            pos += k; inDone = pos >= pcm.length; fed = true;
            c.queueInputBuffer(ii, 0, k * 2, pts, inDone ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
          }
        }
        int oi = c.dequeueOutputBuffer(bi, fed ? 0 : WAIT_US);
        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mx.addTrack(c.getOutputFormat()); mx.start(); started = true; }
        else if (oi >= 0) {
          // Служебные буферы (заголовок Opus, описание AAC) приходят в формате дорожки — сам контейнер их пишет.
          if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0 && started) {
            ByteBuffer ob = c.getOutputBuffer(oi); ob.position(bi.offset); ob.limit(bi.offset + bi.size);
            mx.writeSampleData(track, ob, bi);
          }
          c.releaseOutputBuffer(oi, false);
          if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
      }
      mx.stop();
    } finally {
      try { c.stop(); } catch (Throwable ignore) {}
      c.release();
      if (mx != null) try { mx.release(); } catch (Throwable ignore) {}
    }
  }

  /** Раскрыть файл в моно −1…1; rateOut[0] — частота, на которой он звучит (Opus отдаёт 48 кГц). */
  static float[] decode(File f, int[] rateOut) throws IOException {
    if (f.getName().endsWith(".wav")) return readWav(f, rateOut);
    MediaExtractor ex = new MediaExtractor(); MediaCodec d = null;
    try {
      ex.setDataSource(f.getPath());
      MediaFormat fmt = null;
      for (int k = 0; k < ex.getTrackCount() && fmt == null; k++) {
        MediaFormat m = ex.getTrackFormat(k); String mime = m.getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("audio/")) { fmt = m; ex.selectTrack(k); }
      }
      if (fmt == null) throw new IOException("нет звуковой дорожки: " + f.getName());
      int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      d = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
      d.configure(fmt, null, null, 0); d.start();
      MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
      float[] out = new float[rate * 4]; int n = 0; boolean inDone = false;
      long until = System.currentTimeMillis() + LIMIT_MS;
      while (true) {
        if (System.currentTimeMillis() > until) throw new IOException("кодек не ответил за " + LIMIT_MS / 1000 + " с");
        boolean fed = false;
        if (!inDone) {
          int ii = d.dequeueInputBuffer(0);
          if (ii >= 0) {
            ByteBuffer b = d.getInputBuffer(ii);
            int sz = ex.readSampleData(b, 0);
            if (sz < 0) { d.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
            else { d.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0); ex.advance(); }
            fed = true;
          }
        }
        int oi = d.dequeueOutputBuffer(bi, fed ? 0 : WAIT_US);   // как у кодера: пока вход идёт, выход не ждём
        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat of = d.getOutputFormat();
          rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        } else if (oi >= 0) {
          ByteBuffer ob = d.getOutputBuffer(oi);
          if (bi.size > 0) {
            ob.position(bi.offset); ob.limit(bi.offset + bi.size); ob.order(ByteOrder.LITTLE_ENDIAN);
            int frames = bi.size / 2 / Math.max(1, ch);
            if (n + frames > out.length) out = java.util.Arrays.copyOf(out, Math.max(out.length * 2, n + frames));
            for (int k = 0; k < frames; k++) {
              float s = 0; for (int c = 0; c < ch; c++) s += ob.getShort() / 32768f;
              out[n++] = s / ch;
            }
          }
          d.releaseOutputBuffer(oi, false);
          if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
      }
      rateOut[0] = rate;
      return java.util.Arrays.copyOf(out, n);
    } finally {
      if (d != null) { try { d.stop(); } catch (Throwable ignore) {} d.release(); }
      ex.release();
    }
  }

  static short pcm16(float v) { return (short) Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f); }

  static void writeWav(float[] pcm, int rate, File out) throws IOException {
    ByteBuffer b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes("US-ASCII")).putInt(36 + pcm.length * 2).put("WAVE".getBytes("US-ASCII"));
    b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
    b.put("data".getBytes("US-ASCII")).putInt(pcm.length * 2);
    for (float v : pcm) b.putShort(pcm16(v));
    try (FileOutputStream o = new FileOutputStream(out)) { o.write(b.array()); }
  }

  /** PCM16 WAV, моно или стерео (стерео сводится); прочие куски заголовка пропускаются. */
  static float[] readWav(File f, int[] rateOut) throws IOException {
    byte[] all = new byte[(int) f.length()];
    try (DataInputStream in = new DataInputStream(new FileInputStream(f))) { in.readFully(all); }
    ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
    if (all.length < 12 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) throw new IOException("не WAV: " + f.getName());
    int pos = 12, ch = 1, rate = 16000, bits = 16;
    while (pos + 8 <= all.length) {
      int id = b.getInt(pos), len = b.getInt(pos + 4);
      if (id == 0x20746d66) { ch = b.getShort(pos + 10); rate = b.getInt(pos + 12); bits = b.getShort(pos + 22); }
      else if (id == 0x61746164) {
        if (bits != 16) throw new IOException("не 16 бит: " + f.getName());
        int frames = Math.min(len, all.length - pos - 8) / 2 / Math.max(1, ch);
        float[] out = new float[frames];
        for (int k = 0; k < frames; k++) {
          float s = 0; for (int c = 0; c < ch; c++) s += b.getShort(pos + 8 + (k * ch + c) * 2) / 32768f;
          out[k] = s / ch;
        }
        rateOut[0] = rate; return out;
      }
      pos += 8 + len + (len & 1);
    }
    throw new IOException("в WAV нет данных: " + f.getName());
  }
}
