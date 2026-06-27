package dev.icecam.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Passive status poller — modeled on the original {@code App} 1-second timer
 * ({@code D0/i.java} case 9) which only calls TX13 {@code c()} to refresh status
 * and re-acquires the binder via {@code U/t.E()} when needed.
 *
 * IMPORTANT: this watchdog must NEVER re-deploy, re-bootstrap, re-bake or re-apply
 * media. Earlier builds did, which restarted the native decoder every few seconds
 * and on every button press, resetting the stream. The native daemon keeps looping
 * the source on its own; no keep-alive traffic is required.
 *
 * Recovery (a single bootstrap) happens only when the binder service has genuinely
 * disappeared (daemon died) — detected by {@code service check}, not by hook maps.
 */
public final class ReplacementWatchdog {
    private static final long POLL_INTERVAL_MS = 2000L;
    private static volatile ReplacementWatchdog instance;

    public static ReplacementWatchdog get(Context ctx) {
        ReplacementWatchdog local = instance;
        if (local == null) {
            synchronized (ReplacementWatchdog.class) {
                local = instance;
                if (local == null) {
                    local = new ReplacementWatchdog(ctx.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private final SharedPreferences prefs;
    private final AppLogger log;
    private final VliveBinderClient binder;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> activePath = new AtomicReference<>(null);
    private long pollCount = 0L;
    private int lastStatus = -1;

    private ReplacementWatchdog(Context appCtx) {
        this.prefs = appCtx.getSharedPreferences("app_config", Context.MODE_PRIVATE);
        this.log = new AppLogger(appCtx);
        this.binder = new VliveBinderClient(log);
        this.binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
    }

    public void markActive(String stagedPath) {
        activePath.set(stagedPath);
        prefs.edit().putString("StagedPath", stagedPath).apply();
        startIfNeeded();
    }

    public void markInactive() { activePath.set(null); }

    private void startIfNeeded() {
        if (!running.compareAndSet(false, true)) return;
        new Thread(this::loop, "icecam-status-poll").start();
    }

    private void loop() {
        try {
            log.log("poll", "status poller started interval=" + POLL_INTERVAL_MS + "ms");
            while (activePath.get() != null) {
                try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                poll();
            }
        } finally {
            running.set(false);
            log.log("poll", "status poller stopped pollCount=" + pollCount);
        }
    }

    /** Read-only status refresh. Re-acquires binder if the cached ref died. No bootstrap, no re-apply. */
    private void poll() {
        try {
            pollCount++;
            if (!binder.connected()) {
                // Binder ref died. Try a cheap re-acquire (setenforce 0/getService/setenforce 1).
                binder.clearCache();
                if (!binder.connected()) {
                    lastStatus = -1;
                    return;
                }
            }
            int status = binder.getInt15(); // TX15 f() — 5 means playing
            lastStatus = status;
        } catch (Throwable t) {
            log.log("poll", "poll error: " + t);
        }
    }

    public int lastStatus() { return lastStatus; }

    public String status() {
        return "running=" + running.get() + " path=" + activePath.get()
                + " pollCount=" + pollCount + " lastStatus=" + lastStatus + " (5=playing)";
    }
}
