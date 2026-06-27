package dev.icecam.app;

import java.io.File;
import java.util.Locale;

/**
 * Stages user-selected media into a path that cameraserver / vcplax can reliably read.
 *
 * The original media is in /storage/emulated/0/Android/data/<pkg>/files/, which has
 * media_rw_data_file SELinux context. After SELinux audits and after the daemon copies
 * file descriptors across processes, that path may become unreadable from the camera HAL
 * context. Copying it once into /data/camera/source.<ext> with system_data_file context
 * is far more reliable and survives daemon restarts.
 */
public final class MediaStager {
    public static final String STAGE_DIR = RootBootstrap.CAMERA_DIR + "/source";

    private MediaStager() {}

    public static String stage(String userPath, AppLogger log) {
        if (userPath == null || userPath.trim().isEmpty()) return null;
        File src = new File(userPath);
        if (!src.exists()) {
            if (log != null) log.log("stage", "source missing: " + userPath);
            return null;
        }
        String ext = "bin";
        int dot = src.getName().lastIndexOf('.');
        if (dot > 0) ext = src.getName().substring(dot + 1).toLowerCase(Locale.US);
        if (ext.length() > 8) ext = "bin";
        String dst = STAGE_DIR + "." + ext;

        String script = "set -x\n" +
                "SRC=" + Shell.q(userPath) + "\n" +
                "DST=" + Shell.q(dst) + "\n" +
                "mkdir -p " + Shell.q(RootBootstrap.CAMERA_DIR) + "\n" +
                "cp -f \"$SRC\" \"$DST\" || exit 1\n" +
                "chmod 644 \"$DST\"\n" +
                "chcon u:object_r:system_data_file:s0 \"$DST\" 2>/dev/null || true\n" +
                "restorecon \"$DST\" 2>/dev/null || true\n" +
                "ls -lZ \"$DST\" 2>/dev/null || ls -l \"$DST\"\n" +
                "echo stage_ok=$DST size=$(stat -c %s \"$DST\" 2>/dev/null)\n";
        Shell.Result r = Shell.su(script);
        if (log != null) log.logBlock("stage", r.all());
        if (r.code != 0 || !r.all().contains("stage_ok=")) return null;
        return dst;
    }

    public static boolean exists(String stagedPath) {
        if (stagedPath == null) return false;
        Shell.Result r = Shell.su("[ -f " + Shell.q(stagedPath) + " ] && echo yes || echo no");
        return r.all().contains("yes");
    }
}
