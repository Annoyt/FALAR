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
 *  для аудиофайла. Без своего экрана: копирует звук к себе (право читать чужой файл живёт, пока жив этот
 *  экран) и передаёт копию главному экрану Falar — в его собственной задаче, а не внутри мессенджера, где
 *  открылась бы вторая копия всего приложения. Кто прислал — по referrer (VoiceMsg.fromReferrer). */
public class ShareActivity extends Activity {
  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    final Intent in = getIntent();
    final Uri u = Intent.ACTION_SEND.equals(in.getAction()) ? (Uri) in.getParcelableExtra(Intent.EXTRA_STREAM) : in.getData();
    final String type = in.getType();
    final Uri ref = getReferrer();
    final String from = VoiceMsg.fromReferrer(ref == null ? null : ref.toString());
    if (u == null) { finish(); return; }
    new Thread(() -> {
      File f = copy(this, u, type);
      runOnUiThread(() -> {
        Intent go = new Intent(this, MainActivity.class).setAction(MainActivity.ACT_VOICE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (f != null) go.putExtra("voicefile", f.getPath()); else go.putExtra("voicefail", "1");
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
    File f = new File(dir, "voice-" + System.currentTimeMillis() + VoiceMsg.ext(type, name));
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
