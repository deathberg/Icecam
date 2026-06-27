package dev.icecam.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Health-only watchdog.
 *
 * The original IceCam APK does NOT poll the daemon. Apply path was a single
 * {@code t.a0(path, 1)} call (TX14) — once playback was started, no further
 * binder traffic was needed.
 *
 * v28's watchdog re-issued TX22+TX14+TX11 every 4.5 s, which restarted the
 * native decoder and is the most likely cause of the "image flashes for a
 * moment, then disappears" symptom.
 *
 * v29 keeps a passive watcher that only:
 *   - probes {@link BackendHealth} every 10 s while replacement is active,
 *   - triggers a one-shot {@link RootBootstrap#bootstrap()} if cameraserver lost
 *     the hook (e.g. cameraserver process was killed and respawned),
 *   - re-issues TX14 ONLY if the daemon process restarted (binder death recipient
 *     fired) and the path needs to be reset.
 */
public final class ReplacementWatchdog {
    private static final long WATCHDOG_INTERVAL_MS = 10_000L;
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

    private final Context ctx;
    private final SharedPreferences prefs;
    private final AppLogger log;
    private final RootBootstrap root;
    private final VliveBinderClient binder;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> activePath = new AtomicReference<>(null);
    private long lastProbeMs = 0L;
    private long probeCount = 0L;
    private long recoveryCount = 0L;

    private ReplacementWatchdog(Context appCtx) {
        this.ctx = appCtx;
        this.prefs = ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE);
        this.log = new AppLogger(ctx);
        this.root = new RootBootstrap(ctx, log);
        this.binder = new VliveBinderClient(log);
        this.binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
    }

    public void markActive(String stagedPath) {
        activePath.set(stagedPath);
        prefs.edit().putString("StagedPath", stagedPath).apply();
        startIfNeeded();
    }

    public void markInactive() {
        activePath.set(null);
    }

    private void startIfNeeded() {
        if (!running.compareAndSet(false, true)) return;
        new Thread(this::loop, "icecam-watchdog").start();
    }

    private void loop() {
        try {
            log.log("watchdog", "started interval=" + WATCHDOG_INTERVAL_MS + "ms");
            while (true) {
                String path = activePath.get();
                if (path == null) break;
                try { Thread.sleep(WATCHDOG_INTERVAL_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                probe(path);
            }
        } finally {
            running.set(false);
            log.log("watchdog", "stopped probeCount=" + probeCount + " recoveryCount=" + recoveryCount);
        }
    }

    private void probe(String stagedPath) {
        try {
            probeCount++;
            BackendHealth h = BackendHealth.probe();
            lastProbeMs = System.currentTimeMillis();
            log.log("watchdog", "probe #" + probeCount + " " + h.summary());
            if (h.fullyReady()) return;

            recoveryCount++;
            log.log("watchdog", "unhealthy " + h.summary() + "; bootstrap + re-apply path");
            root.bootstrap();
            binder.clearCache();
            if (binder.connected()) {
                int mode = binder.setModeString(1, stagedPath);
                int status = binder.getInt15();
                log.log("watchdog", "recovery TX14=" + mode + " TX15=" + status + " path=" + stagedPath);
            } else {
                log.log("watchdog", "recovery skipped: " + binder.lastError());
            }
        } catch (Throwable t) {
            log.log("watchdog", "probe error: " + t);
        }
    }

    public String status() {
        return "running=" + running.get() + " path=" + activePath.get()
                + " probeCount=" + probeCount + " recoveryCount=" + recoveryCount
                + " lastProbeMs=" + lastProbeMs;
    }
}
