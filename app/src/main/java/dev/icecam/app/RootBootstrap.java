package dev.icecam.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class RootBootstrap {
    public static final String FIXED_SERVICE_NAME = "privsam_service";
    public static final String CAMERA_DIR = "/data/camera";
    public static final String VCPLAX_EXEC = CAMERA_DIR + "/vcplax";

    private final Context ctx;
    private final AppLogger log;

    public RootBootstrap(Context c, AppLogger logger) {
        ctx = c.getApplicationContext();
        log = logger;
    }

    public String serverName() {
        SharedPreferences p = ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE);
        String s = p.getString("ServerName", FIXED_SERVICE_NAME);
        if (s == null || s.trim().isEmpty() || !FIXED_SERVICE_NAME.equals(s.trim())) {
            s = FIXED_SERVICE_NAME;
            p.edit().putString("ServerName", s).apply();
        }
        return s;
    }

    public String resetServerName() {
        ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit().putString("ServerName", FIXED_SERVICE_NAME).apply();
        log.log("root", "ServerName fixed=" + FIXED_SERVICE_NAME);
        return FIXED_SERVICE_NAME;
    }

    public String bootstrap() {
        NativeExtractor.Result ex = NativeExtractor.extract(ctx, log);
        String server = serverName();
        String src = ex.dir.getAbsolutePath();
        String script = "set -x\n" +
                "SRC=" + Shell.q(src) + "\n" +
                "SERVER=" + Shell.q(server) + "\n" +
                "CAMERA_DIR=" + Shell.q(CAMERA_DIR) + "\n" +
                "echo selected_abi=" + Shell.q(ex.abi) + " server=$SERVER src=$SRC\n" +
                "id\n" +
                SelinuxPolicy.applyLivePoliciesScript() +
                "killall vcplax 2>/dev/null || true\n" +
                "rm -rf $CAMERA_DIR /data/samera\n" +
                "mkdir -p $CAMERA_DIR /data/local/tmp/icecam/mirror\n" +
                "chattr -i $CAMERA_DIR 2>/dev/null || true\n" +
                deployFileScript() +
                "rm -f $CAMERA_DIR/vcplax.log $CAMERA_DIR/vcplax.err\n" +
                "export LD_LIBRARY_PATH=$CAMERA_DIR:/data:/system/lib64:/system_ext/lib64:/vendor/lib64:/system/lib:/system_ext/lib:/vendor/lib:$LD_LIBRARY_PATH\n" +
                "export ICECAM_SERVER=$SERVER\n" +
                "EXEC=" + Shell.q(VCPLAX_EXEC) + "\n" +
                "[ -x \"$EXEC\" ] || EXEC=/data/vcplax\n" +
                "echo ---launch $EXEC $SERVER---\n" +
                "nohup $EXEC $SERVER >$CAMERA_DIR/vcplax.log 2>$CAMERA_DIR/vcplax.err &\n" +
                "echo spawned_pid=$! exec=$EXEC\n" +
                "for i in 1 2 3 4 5 6 8 10; do sleep 1; service check $SERVER 2>&1 | grep -qi found && break; done\n" +
                "echo ---process---\nps -A | grep -i vcplax || ps | grep -i vcplax || true\n" +
                "echo ---expected-service---\nservice check $SERVER 2>&1 || true\n" +
                "echo ---service-list-filtered---\nservice list 2>/dev/null | grep -iE \"$SERVER|vlive|camera|media|ice|vcplax\" || true\n" +
                fileStatusScript() +
                "echo ---vcplax.log---\ncat $CAMERA_DIR/vcplax.log 2>/dev/null || true\n" +
                "echo ---vcplax.err---\ncat $CAMERA_DIR/vcplax.err 2>/dev/null || true\n" +
                "echo ---selinux-after---\ngetenforce 2>/dev/null || true\n";
        Shell.Result r = Shell.su(script);
        String all = r.all();
        log.logBlock("root", all);
        return all;
    }

  /**
   * Deploy native artifacts.
   *
   * vcplax injects cameraserver with /data/libvc.so and /data/libvc++.so. On Android 13+
   * direct writes under /data/ may fail; mirror copies under /data/camera and bind-mount
   * them to the legacy paths when needed.
   */
    private static String deployFileScript() {
        return ""
                + "deploy_copy() {\n"
                + "  src=\"$1\"; dst=\"$2\"; mode=\"$3\"\n"
                + "  mkdir -p \"$(dirname \"$dst\")\"\n"
                + "  if cp -f \"$src\" \"$dst\" 2>/dev/null; then\n"
                + "    chmod \"$mode\" \"$dst\" 2>/dev/null || true\n"
                + "    chcon u:object_r:system_data_file:s0 \"$dst\" 2>/dev/null || true\n"
                + "    echo deployed_copy $dst\n"
                + "    return 0\n"
                + "  fi\n"
                + "  mirror=\"/data/local/tmp/icecam/mirror/$(basename \"$dst\")\"\n"
                + "  cp -f \"$src\" \"$mirror\" || return 1\n"
                + "  chmod \"$mode\" \"$mirror\" 2>/dev/null || true\n"
                + "  touch \"$dst\" 2>/dev/null || true\n"
                + "  umount \"$dst\" 2>/dev/null || true\n"
                + "  if mount --bind \"$mirror\" \"$dst\" 2>/dev/null; then\n"
                + "    echo deployed_bind $dst\n"
                + "    return 0\n"
                + "  fi\n"
                + "  echo deploy_failed $dst >&2\n"
                + "  return 1\n"
                + "}\n"
                + "cp -f $SRC/libvc.so $CAMERA_DIR/libvc.so\n"
                + "cp -f $SRC/libshadowhook.so $CAMERA_DIR/libshadowhook.so\n"
                + "cp -f $SRC/vcplax.so $CAMERA_DIR/vcplax\n"
                + "chmod 700 $CAMERA_DIR/vcplax\n"
                + "chmod 644 $CAMERA_DIR/libvc.so $CAMERA_DIR/libshadowhook.so\n"
                + "deploy_copy $SRC/libvc.so /data/libvc.so 644 || cp -f $CAMERA_DIR/libvc.so /data/libvc.so 2>/dev/null || true\n"
                + "deploy_copy $SRC/libshadowhook.so /data/libvc++.so 644 || cp -f $CAMERA_DIR/libshadowhook.so /data/libvc++.so 2>/dev/null || true\n"
                + "deploy_copy $SRC/vcplax.so /data/vcplax 700 || echo compat_skip /data/vcplax\n"
                + "chcon -R u:object_r:system_data_file:s0 $CAMERA_DIR 2>/dev/null || true\n";
    }

    private static String fileStatusScript() {
        return ""
                + "echo ---files---\n"
                + "ls -l $CAMERA_DIR 2>&1\n"
                + "for f in /data/vcplax /data/libvc.so /data/libvc++.so; do\n"
                + "  if [ -e \"$f\" ]; then ls -l \"$f\" 2>&1; else echo \"compat missing: $f (using $CAMERA_DIR when needed)\"; fi\n"
                + "done\n";
    }

    public String restoreCamera() {
        String server = serverName();
        String script = "set -x\n" +
                "SERVER=" + Shell.q(server) + "\n" +
                "CAMERA_DIR=" + Shell.q(CAMERA_DIR) + "\n" +
                "echo restore_server=$SERVER\n" +
                "id\n" +
                "getenforce 2>/dev/null || true\n" +
                "echo ---soft-stop-binder---\n" +
                "service check $SERVER 2>&1 || true\n" +
                "echo ---kill-daemon---\n" +
                "killall vcplax 2>/dev/null || true\n" +
                "pkill -f /data/vcplax 2>/dev/null || true\n" +
                "pkill -f $CAMERA_DIR/vcplax 2>/dev/null || true\n" +
                "sleep 1\n" +
                "echo ---after-process---\n" +
                "ps -A | grep -i vcplax || ps | grep -i vcplax || true\n" +
                "echo ---after-service---\n" +
                "service check $SERVER 2>&1 || true\n" +
                "echo ---camera-services---\n" +
                "service list 2>/dev/null | grep -iE \"camera|media.camera|$SERVER|vcplax\" || true\n" +
                "echo restore_done\n";
        Shell.Result r = Shell.su(script);
        log.logBlock("restore", r.all());
        return r.all();
    }

    public String status() {
        String server = serverName();
        String script = "SERVER=" + Shell.q(server) + "\n" +
                "CAMERA_DIR=" + Shell.q(CAMERA_DIR) + "\n" +
                "id\n" +
                "echo server=$SERVER\n" +
                "echo ---selinux---\ngetenforce 2>/dev/null || true\n" +
                "echo ---process---\nps -A | grep -i vcplax || ps | grep -i vcplax || true\n" +
                "echo ---expected-service---\nservice check $SERVER 2>&1 || true\n" +
                "echo ---service-list-filtered---\nservice list 2>/dev/null | grep -iE \"$SERVER|vlive|camera|media|ice|vcplax\" || true\n" +
                fileStatusScript() +
                "echo ---vcplax-log---\ntail -160 $CAMERA_DIR/vcplax.log 2>/dev/null || true\n" +
                "echo ---vcplax-err---\ntail -160 $CAMERA_DIR/vcplax.err 2>/dev/null || true\n" +
                "echo ---logcat-native---\nlogcat -d -t 220 2>/dev/null | grep -iE \"icecam|vcplax|vlive|libvc|shadowhook|binder|servicemanager|avc: denied|Parcel\" || true\n";
        Shell.Result r = Shell.su(script);
        log.logBlock("status", r.all());
        return r.all();
    }
}
