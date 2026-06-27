package dev.icecam.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps frame replacement alive after the initial TX11/TX14 apply.
 *
 * Symptom solved: user saw the substituted image flash for a brief moment, then
 * the live camera frame returned. Likely causes the watchdog mitigates:
 *  - vcplax may finish decoding a still image and stop pushing frames;
 *  - cameraserver may open/close camera sessions which invalidate the active source;
 *  - vcplax may be killed and restarted, requiring TX14/TX11 re-issue.
 *
 * Every WATCHDOG_INTERVAL_MS the watchdog:
 *  1. Probes daemon/service/inject health.
 *  2. If hook missing but daemon dead -> trigger bootstrap.
 *  3. Re-issues TX22 range reset + TX14 path + TX11 play so the daemon keeps looping.
 */
public final class ReplacementWatchdog {
    private static final long WATCHDOG_INTERVAL_MS = 4500L;
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
    private long lastPokeMs = 0L;
    private long pokeCount = 0L;

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
                pokeBackend(path);
            }
        } finally {
            running.set(false);
            log.log("watchdog", "stopped pokeCount=" + pokeCount);
        }
    }

    private void pokeBackend(String stagedPath) {
        try {
            BackendHealth h = BackendHealth.probe();
            if (!h.fullyReady()) {
                log.log("watchdog", "unhealthy " + h.summary() + "; bootstrap");
                root.bootstrap();
                binder.clearCache();
            }
            if (!binder.connected()) {
                binder.clearCache();
                if (!binder.connected()) {
                    log.log("watchdog", "binder unavailable: " + binder.lastError());
                    return;
                }
            }
            int range = binder.setRange(0L, -1L);
            int mode  = binder.setModeString(1, stagedPath);
            int play  = binder.playSource(stagedPath, false, prefs.getBoolean("PlayisLoop", true));
            pokeCount++;
            lastPokeMs = System.currentTimeMillis();
            log.log("watchdog", "poke #" + pokeCount + " TX22=" + range + " TX14=" + mode + " TX11=" + play + " path=" + stagedPath);
        } catch (Throwable t) {
            log.log("watchdog", "poke error: " + t);
        }
    }

    public String status() {
        return "running=" + running.get() + " path=" + activePath.get()
                + " pokeCount=" + pokeCount + " lastPokeMs=" + lastPokeMs;
    }
}
