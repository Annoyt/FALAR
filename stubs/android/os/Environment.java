package android.os;

import java.io.File;

public class Environment {
    public static final String DIRECTORY_DOWNLOADS = "Download";
    public static File getExternalStoragePublicDirectory(String type) {
        return new File("/sdcard/" + type);
    }
}
