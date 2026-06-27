package com.xiaomi.vlive;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;

/**
 * RECONSTRUCTED stub of original com.xiaomi.vlive.App
 * Recovered bootstrap sequence from DEX strings + IceCam docs.
 */
public class App extends Application {
    public static final String PREF_SERVER = "ServerName";

    @Override public void onCreate() {
        super.onCreate();
        // Original: request root, extract jniLibs, deploy to /data/, start /data/vcplax <ServerName>
    }

    /** Recovered from App.onCreate / App.d() */
    public static String resolveServerName(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("app_config", MODE_PRIVATE);
        String s = p.getString(PREF_SERVER, "privsam_service");
        if (s == null || s.trim().isEmpty()) s = "privsam_service";
        return s.trim();
    }

    /** Recovered shell deployment */
    public static String buildBootstrapScript(File srcDir, String serverName) {
        return "cp " + srcDir + "/libvc.so /data/libvc.so\n"
                + "cp " + srcDir + "/libshadowhook.so /data/libvc++.so\n"
                + "cp " + srcDir + "/vcplax.so /data/vcplax\n"
                + "chmod 700 /data/vcplax\n"
                + "/data/vcplax " + serverName + " &\n";
    }
}
