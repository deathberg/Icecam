package dev.icecam.app;

/**
 * Fast precheck for backend readiness used to make bootstrap idempotent.
 *
 * Each user tap previously triggered a full kill / redeploy / relaunch cycle.
 * That cycle deleted /data/libvc.so while it was mapped in cameraserver, turning the
 * library entries into (deleted) and breaking the active hook frame stream — the user
 * only saw the replaced image flash for a moment before the cycle wiped it out.
 *
 * BackendHealth detects "already running, already injected" and lets the caller skip
 * the destructive parts of bootstrap.
 */
public final class BackendHealth {
    public final boolean daemonRunning;
    public final boolean serviceRegistered;
    public final boolean hookMapped;
    public final boolean stagedSourcePresent;

    public BackendHealth(boolean d, boolean s, boolean h, boolean m) {
        this.daemonRunning = d; this.serviceRegistered = s; this.hookMapped = h; this.stagedSourcePresent = m;
    }

    public boolean fullyReady() { return daemonRunning && serviceRegistered && hookMapped; }

    public static BackendHealth probe() {
        String script = ""
                + "DAEMON=no\n"
                + "SERVICE=no\n"
                + "HOOK=no\n"
                + "STAGED=no\n"
                + "if ps -A 2>/dev/null | grep -i vcplax | grep -v grep >/dev/null; then DAEMON=yes; fi\n"
                + "if [ \"$DAEMON\" = no ] && ps 2>/dev/null | grep -i vcplax | grep -v grep >/dev/null; then DAEMON=yes; fi\n"
                + "out=$(service check " + Shell.q(RootBootstrap.FIXED_SERVICE_NAME) + " 2>&1)\n"
                + "if ! echo \"$out\" | grep -Eqi 'not found'; then\n"
                + "  echo \"$out\" | grep -Eqi ': found' && SERVICE=yes\n"
                + "fi\n"
                + "CAM_PID=$(pidof cameraserver 2>/dev/null)\n"
                + "if [ -n \"$CAM_PID\" ] && grep -qi libvc /proc/$CAM_PID/maps 2>/dev/null; then HOOK=yes; fi\n"
                + "for ext in jpg jpeg png mp4 bin; do\n"
                + "  if [ -f " + Shell.q(MediaStager.STAGE_DIR) + ".${ext} ]; then STAGED=yes; break; fi\n"
                + "done\n"
                + "echo health daemon=$DAEMON service=$SERVICE hook=$HOOK staged=$STAGED\n";
        Shell.Result r = Shell.su(script);
        String all = r.all();
        return new BackendHealth(
                all.contains("daemon=yes"),
                all.contains("service=yes"),
                all.contains("hook=yes"),
                all.contains("staged=yes"));
    }

    public String summary() {
        return "daemon=" + daemonRunning + " service=" + serviceRegistered
                + " hook=" + hookMapped + " staged=" + stagedSourcePresent;
    }
}
