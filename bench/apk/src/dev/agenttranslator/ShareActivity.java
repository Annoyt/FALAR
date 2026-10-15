package dev.agenttranslator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/** Голосовое пришло из другого приложения: «Поделиться» → Falar (WhatsApp, Telegram…) или «Открыть с помощью»
 *  для аудиофайла. Несколько сразу (выделили несколько голосовых) — все, по порядку; видеосообщение («кружочек»
 *  Telegram) — его звук. Без своего экрана: копирует звук к себе (право читать чужой файл живёт, пока жив этот
 *  экран) и передаёт копию главному экрану Falar — в его собственной задаче, а не внутри мессенджера, где
 *  открылась бы вторая копия всего приложения. Кто прислал — по referrer (VoiceMsg.fromReferrer). */
public class ShareActivity extends Activity {
  static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();
  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    final Intent in = getIntent();
    final java.util.ArrayList<Uri> us = new java.util.ArrayList<>();
    if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
      java.util.ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
      if (l != null) for (Uri x : l) if (x != null) us.add(x);
    } else {
      Uri u = Intent.ACTION_SEND.equals(in.getAction()) ? (Uri) in.getParcelableExtra(Intent.EXTRA_STREAM) : in.getData();
      if (u != null) us.add(u);
    }
    final String type = in.getType();
    final Uri ref = getReferrer();
    final String from = VoiceMsg.fromReferrer(ref == null ? null : ref.toString());
    if (us.isEmpty()) { finish(); return; }
    new Thread(() -> {
      java.util.ArrayList<String> paths = new java.util.ArrayList<>(); int failed = 0;
      for (Uri u : us) { File f = copy(this, u, us.size() == 1 ? type : null); if (f != null) paths.add(f.getPath()); else failed++; }
      final int bad = failed;
      runOnUiThread(() -> {
        Intent go = new Intent(this, MainActivity.class).setAction(MainActivity.ACT_VOICE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!paths.isEmpty()) go.putExtra("voicefiles", paths.toArray(new String[0]));
        if (bad > 0) go.putExtra("voicefail", String.valueOf(bad));
        if (from != null) go.putExtra("voicefrom", from);
        startActivity(go);
        finish();
      });
    }, "voicecopy").start();
  }

  /** Копия звука в cache/voice — имя по времени, расширение по типу или имени файла. null — не прочиталось. */
  static File copy(Context c, Uri u, String type) {
    File dir = new File(c.getCacheDir(), "voice");
    if (!dir.isDirectory() && !dir.mkdirs()) return null;
    String name = null;
    try (android.database.Cursor cur = c.getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cur != null && cur.moveToFirst()) name = cur.getString(0);
    } catch (Throwable ignore) {}
    if (type == null) try { type = c.getContentResolver().getType(u); } catch (Throwable ignore) {}
    File f = new File(dir, "voice-" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet() + VoiceMsg.ext(type, name));   // несколько за миллисекунду — не затереть
    try (InputStream is = c.getContentResolver().openInputStream(u); OutputStream os = new java.io.FileOutputStream(f)) {
      if (is == null) return null;
      byte[] buf = new byte[1 << 16]; long n = 0; int r;
      while ((r = is.read(buf)) > 0) {
        os.write(buf, 0, r); n += r;
        if (n > 200L << 20) throw new java.io.IOException("больше 200 МБ");   // не голосовое — и не место им в кэше
      }
      return n > 0 ? f : null;
    } catch (Throwable t) { f.delete(); return null; }
  }
}
