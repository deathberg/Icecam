package dev.icecam.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class RootBootstrap {
    public static final String FIXED_SERVICE_NAME = "privsam_service";
    public static final String CAMERA_DIR = "/data/camera";
    /** Original recovered APK path; vcplax expects this location. */
    public static final String VCPLAX_EXEC = "/data/vcplax";

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
        if (!ex.ok) SmartDiagnostics.trace(log, SmartDiagnostics.Stage.EXTRACT, false, ex.log);
        else SmartDiagnostics.trace(log, SmartDiagnostics.Stage.EXTRACT, "abi=" + ex.abi + " dir=" + ex.dir);

        String server = serverName();
        String src = ex.dir.getAbsolutePath();
        String script = "set -x\n" +
                "SRC=" + Shell.q(src) + "\n" +
                "SERVER=" + Shell.q(server) + "\n" +
                "CAMERA_DIR=" + Shell.q(CAMERA_DIR) + "\n" +
                "EXEC=" + Shell.q(VCPLAX_EXEC) + "\n" +
                "echo selected_abi=" + Shell.q(ex.abi) + " server=$SERVER src=$SRC\n" +
                "id\n" +
                SelinuxPolicy.applyLivePoliciesScript() +
                serviceFoundHelper() +
                "killall vcplax 2>/dev/null || true\n" +
                "pkill -f /data/vcplax 2>/dev/null || true\n" +
                "pkill -f $CAMERA_DIR/vcplax 2>/dev/null || true\n" +
                "rm -rf $CAMERA_DIR /data/samera\n" +
                "mkdir -p $CAMERA_DIR /data/local/tmp/icecam/mirror\n" +
                "chattr -i $CAMERA_DIR 2>/dev/null || true\n" +
                deployFileScript() +
                launchDaemonScript(server) +
                verifyDaemonScript(server) +
                verifyInjectionScript() +
                fileStatusScript() +
                "echo ---selinux-after---\ngetenforce 2>/dev/null || true\n";
        Shell.Result r = Shell.su(script);
        String all = r.all();
        log.logBlock("root", all);
        parseBootstrapResult(all);
        return all;
    }

    private void parseBootstrapResult(String all) {
        boolean daemon = all.contains("daemon_alive=yes");
        boolean service = all.contains("service_found=yes");
        boolean hook = all.contains("hook_maps=yes");
        SmartDiagnostics.trace(log, SmartDiagnostics.Stage.DAEMON_ALIVE, daemon,
                daemon ? "vcplax running" : "vcplax not running after launch");
        SmartDiagnostics.trace(log, SmartDiagnostics.Stage.BINDER_SERVICE, service,
                service ? "privsam_service registered" : "privsam_service missing");
        SmartDiagnostics.trace(log, SmartDiagnostics.Stage.INJECT_HOOK, hook,
                hook ? "libvc visible in cameraserver maps" : "libvc not mapped in cameraserver");
    }

    private static String serviceFoundHelper() {
        return ""
                + "service_found() {\n"
                + "  out=$(service check \"$1\" 2>&1)\n"
                + "  echo \"$out\"\n"
                + "  echo \"$out\" | grep -Eqi \"not found\" && return 1\n"
                + "  echo \"$out\" | grep -Eqi \": found\"\n"
                + "}\n";
    }

    private static String deployFileScript() {
        return ""
                + "deploy_copy() {\n"
                + "  src=\"$1\"; dst=\"$2\"; mode=\"$3\"\n"
                + "  mkdir -p \"$(dirname \"$dst\")\"\n"
                + "  if cp -f \"$src\" \"$dst\" 2>/dev/null; then\n"
                + "    chmod \"$mode\" \"$dst\" 2>/dev/null || true\n"
                + "    chcon u:object_r:system_data_file:s0 \"$dst\" 2>/dev/null || true\n"
                + "    restorecon \"$dst\" 2>/dev/null || true\n"
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
                + "chmod 644 $CAMERA_DIR/libvc.so $CAMERA_DIR/libshadowhook.so\n"
                + "deploy_copy $SRC/libvc.so /data/libvc.so 644 || cp -f $CAMERA_DIR/libvc.so /data/libvc.so\n"
                + "deploy_copy $SRC/libshadowhook.so /data/libvc++.so 644 || cp -f $CAMERA_DIR/libshadowhook.so /data/libvc++.so\n"
                + "deploy_copy $SRC/vcplax.so $EXEC 700 || cp -f $CAMERA_DIR/vcplax $EXEC\n"
                + "chmod 700 $EXEC 2>/dev/null || true\n"
                + "chcon u:object_r:system_data_file:s0 $EXEC /data/libvc.so /data/libvc++.so 2>/dev/null || true\n"
                + "restorecon -R $CAMERA_DIR $EXEC /data/libvc.so /data/libvc++.so 2>/dev/null || true\n"
                + "echo deploy_exec=$EXEC\n";
    }

    private static String launchDaemonScript(String server) {
        return ""
                + "rm -f $CAMERA_DIR/vcplax.log $CAMERA_DIR/vcplax.err /data/vcplax.log /data/vcplax.err\n"
                + "export LD_LIBRARY_PATH=$CAMERA_DIR:/data:/system/lib64:/system_ext/lib64:/vendor/lib64:/system/lib:/system_ext/lib:/vendor/lib\n"
                + "export ICECAM_SERVER=" + Shell.q(server) + "\n"
                + "cat > /data/local/tmp/icecam/launch_vcplax.sh <<'LAUNCH_EOF'\n"
                + "#!/system/bin/sh\n"
                + "set -x\n"
                + "SERVER=\"$1\"\n"
                + "export LD_LIBRARY_PATH=/data/camera:/data:/system/lib64:/system_ext/lib64:/vendor/lib64:/system/lib:/system_ext/lib:/vendor/lib\n"
                + "export ICECAM_SERVER=\"$SERVER\"\n"
                + "echo launch_begin server=$SERVER exec=/data/vcplax time=$(date)\n"
                + "exec /data/vcplax \"$SERVER\"\n"
                + "LAUNCH_EOF\n"
                + "chmod 700 /data/local/tmp/icecam/launch_vcplax.sh\n"
                + "echo ---launch $EXEC $SERVER---\n"
                + "if [ ! -x \"$EXEC\" ]; then echo launch_error=missing_exec path=$EXEC; exit 9; fi\n"
                + "nohup /data/local/tmp/icecam/launch_vcplax.sh " + Shell.q(server) + " >>$CAMERA_DIR/vcplax.log 2>>$CAMERA_DIR/vcplax.err &\n"
                + "LAUNCH_PID=$!\n"
                + "echo spawned_pid=$LAUNCH_PID exec=$EXEC\n"
                + "sleep 1\n"
                + "if kill -0 $LAUNCH_PID 2>/dev/null; then echo launch_child_alive=yes; else echo launch_child_alive=no; fi\n";
    }

    private static String verifyDaemonScript(String server) {
        return ""
                + "echo ---daemon-wait---\n"
                + "SERVICE_OK=no\n"
                + "DAEMON_OK=no\n"
                + "for i in 1 2 3 4 5 6 8 10 12 15; do\n"
                + "  sleep 1\n"
                + "  if ps -A 2>/dev/null | grep -i vcplax | grep -v grep >/dev/null || ps | grep -i vcplax | grep -v grep >/dev/null; then DAEMON_OK=yes; fi\n"
                + "  if service_found " + Shell.q(server) + " >/dev/null; then SERVICE_OK=yes; break; fi\n"
                + "done\n"
                + "echo daemon_alive=$DAEMON_OK\n"
                + "echo service_found=$SERVICE_OK\n"
                + "echo ---process---\nps -A | grep -i vcplax || ps | grep -i vcplax || true\n"
                + "echo ---expected-service---\nservice check " + Shell.q(server) + " 2>&1 || true\n"
                + "echo ---vcplax.log---\ncat $CAMERA_DIR/vcplax.log 2>/dev/null || true\n"
                + "echo ---vcplax.err---\ncat $CAMERA_DIR/vcplax.err 2>/dev/null || true\n";
    }

    private static String verifyInjectionScript() {
        return ""
                + "echo ---inject-check---\n"
                + "CAM_PID=$(pidof cameraserver 2>/dev/null)\n"
                + "echo cameraserver_pid=$CAM_PID\n"
                + "if [ -n \"$CAM_PID\" ] && grep -qi libvc /proc/$CAM_PID/maps 2>/dev/null; then echo hook_maps=yes; else echo hook_maps=no; fi\n"
                + "if [ -n \"$CAM_PID\" ]; then grep -iE 'libvc|libshadowhook|vcplax' /proc/$CAM_PID/maps 2>/dev/null || true; fi\n"
                + "file /system/bin/cameraserver 2>/dev/null || true\n";
    }

    private static String fileStatusScript() {
        return ""
                + "echo ---files---\n"
                + "ls -l $CAMERA_DIR 2>&1\n"
                + "for f in /data/vcplax /data/libvc.so /data/libvc++.so; do\n"
                + "  if [ -e \"$f\" ]; then ls -l \"$f\" 2>&1; else echo missing:$f; fi\n"
                + "done\n";
    }

    public String restoreCamera() {
        String server = serverName();
        String script = "set -x\n" +
                "SERVER=" + Shell.q(server) + "\n" +
                "CAMERA_DIR=" + Shell.q(CAMERA_DIR) + "\n" +
                "echo restore_server=$SERVER\n" +
                "killall vcplax 2>/dev/null || true\n" +
                "pkill -f /data/vcplax 2>/dev/null || true\n" +
                "pkill -f $CAMERA_DIR/vcplax 2>/dev/null || true\n" +
                "sleep 1\n" +
                "service check $SERVER 2>&1 || true\n" +
                "ps -A | grep -i vcplax || true\n" +
                "echo restore_done\n";
        Shell.Result r = Shell.su(script);
        log.logBlock("restore", r.all());
        return r.all();
    }

    public String status() {
        return SmartDiagnostics.healthCheck(ctx, log, null);
    }
}
