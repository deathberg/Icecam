package dev.icecam.app;

import android.content.Context;
import android.os.Build;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Structured trace points for root/native/binder pipeline debugging.
 */
public final class SmartDiagnostics {
    public enum Stage {
        EXTRACT,
        SELINUX,
        DEPLOY,
        DAEMON_START,
        DAEMON_ALIVE,
        BINDER_SERVICE,
        BINDER_CONNECT,
        INJECT_HOOK,
        TX_APPLY,
        CAMERA_VERIFY
    }

    private static final Map<Stage, String> LAST = new LinkedHashMap<>();

    private SmartDiagnostics() {}

    public static void trace(AppLogger log, Stage stage, boolean ok, String detail) {
        String line = (ok ? "OK " : "FAIL ") + stage.name() + ": " + detail;
        LAST.put(stage, line);
        if (log != null) log.log("trace", line);
    }

    public static void trace(AppLogger log, Stage stage, String detail) {
        trace(log, stage, true, detail);
    }

    public static String summary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Stage, String> e : LAST.entrySet()) {
            sb.append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    public static String deviceFingerprint() {
        return String.format(Locale.US, "SDK %d device=%s %s abi=%s",
                Build.VERSION.SDK_INT,
                Build.MANUFACTURER,
                Build.MODEL,
                Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");
    }

    public static String healthCheck(Context ctx, AppLogger log, VliveBinderClient binder) {
        StringBuilder sb = new StringBuilder();
        sb.append("device=").append(deviceFingerprint()).append('\n');
        sb.append("trace-summary:\n").append(summary()).append('\n');

        Shell.Result root = Shell.su(
                "echo ---vcplax-process---\n" +
                "ps -A | grep -i vcplax || ps | grep -i vcplax || echo none\n" +
                "echo ---service-check---\n" +
                "service check " + Shell.q(RootBootstrap.FIXED_SERVICE_NAME) + " 2>&1\n" +
                "echo ---cameraserver-maps---\n" +
                "pid=$(pidof cameraserver 2>/dev/null); echo pid=$pid\n" +
                "[ -n \"$pid\" ] && grep -iE 'libvc|libshadowhook|vcplax' /proc/$pid/maps 2>/dev/null || echo no_maps\n" +
                "echo ---vcplax-log-tail---\n" +
                "tail -80 /data/camera/vcplax.log 2>/dev/null || tail -80 /data/vcplax.log 2>/dev/null || echo empty\n" +
                "echo ---vcplax-err-tail---\n" +
                "tail -80 /data/camera/vcplax.err 2>/dev/null || tail -80 /data/vcplax.err 2>/dev/null || echo empty\n" +
                "echo ---files---\n" +
                "ls -l /data/vcplax /data/libvc.so /data/libvc++.so " + RootBootstrap.CAMERA_DIR + " 2>&1\n");
        sb.append("root-health:\n").append(root.all()).append('\n');

        if (binder != null) {
            sb.append("binder:\n");
            sb.append("connected=").append(binder.connected()).append('\n');
            sb.append("rootShell=").append(binder.usesRootShell()).append('\n');
            sb.append("serviceCheck=").append(RootBinderShell.serviceCheckOutput(RootBootstrap.FIXED_SERVICE_NAME)).append('\n');
            sb.append("lastError=").append(binder.lastError()).append('\n');
        }

        try {
            BackendHealth h = BackendHealth.probe();
            sb.append("health-probe: ").append(h.summary()).append('\n');
            sb.append("watchdog: ").append(ReplacementWatchdog.get(ctx).status()).append('\n');
        } catch (Throwable t) {
            sb.append("health-probe failed: ").append(t).append('\n');
        }

        if (log != null) log.logBlock("health", sb.toString());
        return sb.toString();
    }
}
