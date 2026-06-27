package dev.icecam.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide serialized backend apply queue.
 *
 * v19 replaces the old pendingApplyPath/pendingApplySource/pendingApplyForce triplet with
 * one immutable request snapshot. New requests coalesce by replacing the pending request;
 * the worker drains the latest request via getAndSet(null), so rapid transform taps cannot
 * produce mixed path/source/force state.
 */
public final class BackendApplyQueue {
    private static final long POST_REPLAY_COOLDOWN_MS = 650L;
    private static volatile BackendApplyQueue instance;

    public static BackendApplyQueue get(Context context) {
        Context app = context.getApplicationContext();
        BackendApplyQueue local = instance;
        if (local == null) {
            synchronized (BackendApplyQueue.class) {
                local = instance;
                if (local == null) {
                    local = new BackendApplyQueue(app);
                    instance = local;
                }
            }
        }
        return local;
    }

    public static final class ApplyRequest {
        public final String path;
        public final String source;
        public final boolean force;
        public final long sequence;
        public final long createdAtMs;

        ApplyRequest(String path, String source, boolean force, long sequence) {
            this.path = path;
            this.source = source == null ? "queued" : source;
            this.force = force;
            this.sequence = sequence;
            this.createdAtMs = System.currentTimeMillis();
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final AppLogger log;
    private final RootBootstrap root;
    private final VliveBinderClient binder;
    private final Object backendLock = new Object();
    private final AtomicReference<ApplyRequest> pending = new AtomicReference<>();
    private final AtomicBoolean worker = new AtomicBoolean(false);
    private long nextSeq = 1L;

    private BackendApplyQueue(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("app_config", Context.MODE_PRIVATE);
        this.log = new AppLogger(context);
        this.root = new RootBootstrap(context, log);
        this.binder = new VliveBinderClient(log);
        this.binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
    }

    public boolean isRunning() { return worker.get(); }

    public void enqueue(String path, String source, boolean force) {
        if (path == null || path.trim().isEmpty()) return;
        ApplyRequest req;
        synchronized (this) {
            req = new ApplyRequest(path, source, force, nextSeq++);
        }
        pending.set(req);
        prefs.edit().putString("IceCamState", "APPLY_QUEUED").apply();
        log.log("applyq", "queued #" + req.sequence + " source=" + req.source + " force=" + req.force + " running=" + worker.get() + " path=" + req.path);
        startWorkerIfNeeded();
    }

    private void startWorkerIfNeeded() {
        if (!worker.compareAndSet(false, true)) return;
        new Thread(this::drain, "icecam-backend-applyq").start();
    }

    private void drain() {
        try {
            while (true) {
                ApplyRequest req = pending.getAndSet(null);
                if (req == null) break;
                if (!req.force && !prefs.getBoolean("ReplacementActive", false)) {
                    log.log("applyq", "skip inactive #" + req.sequence + " path=" + req.path);
                    continue;
                }

                boolean ok = legacyApplyMediaOnce(req, false);
                if (!ok) {
                    log.log("applyq", "first apply failed for #" + req.sequence + "; restarting daemon");
                    binder.clearCache();
                    prefs.edit().putString("IceCamState", "RECOVERING_BACKEND").apply();
                    root.bootstrap();
                    sleepMs(700);
                    binder.clearCache();

                    ApplyRequest latest = pending.getAndSet(null);
                    ApplyRequest retry = latest != null ? latest : req;
                    ok = legacyApplyMediaOnce(retry, true);
                    log.log("applyq", "retry result ok=" + ok + " request=#" + retry.sequence + " path=" + retry.path);
                }

                sleepMs(POST_REPLAY_COOLDOWN_MS);
            }
        } finally {
            worker.set(false);
            if (pending.get() != null) startWorkerIfNeeded();
        }
    }

    /**
     * Original IceCam apply flow recovered from {@code f1/a.java} case 1:
     *
     * <pre>
     *   File play = new File(cacheDir, "play.mp4");
     *   copyFromGalleryUri(uri, play);
     *   prefs.put("PlayFileType", 1);
     *   prefs.put("PlayFileMp4",  play.absolutePath);
     *   if (t.a0(path, 1).booleanValue()) toast("file changed successfully");
     *   else                              toast("file set but not playing");
     * </pre>
     *
     * That is: a single TX14 with (mode=1, path) starts the playback. There is no
     * TX22 / TX11 sequence on play. Our previous v28 sent TX22 + TX14 + TX11 and a
     * 4.5 s watchdog re-issued them — that restarted the decoder repeatedly and is
     * what caused the briefly-flashing replacement frame.
     */
    private boolean legacyApplyMediaOnce(ApplyRequest req, boolean retry) {
        synchronized (backendLock) {
            try {
                prefs.edit().putString("IceCamState", "APPLYING_MEDIA").apply();
                File f = new File(req.path);
                long t0 = android.os.SystemClock.elapsedRealtime();
                log.log("applyq", "apply " + (retry ? "retry" : "start") + " #" + req.sequence + " source=" + req.source + " exists=" + f.exists() + " size=" + (f.exists() ? f.length() : -1L) + " path=" + req.path);

                String staged = MediaStager.stage(req.path, log);
                String txPath = staged != null ? staged : req.path;
                if (staged != null) log.log("applyq", "staged " + req.path + " -> " + staged);

                binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
                BackendHealth h = BackendHealth.probe();
                if (!h.fullyReady()) {
                    SmartDiagnostics.trace(log, SmartDiagnostics.Stage.BINDER_SERVICE, false, "precheck " + h.summary());
                    log.log("applyq", "backend not ready; bootstrap before TX (" + h.summary() + ")");
                    root.bootstrap();
                    sleepMs(900);
                    binder.clearCache();
                }
                if (!binder.connected()) {
                    binder.clearCache();
                    sleepMs(250);
                }
                if (!binder.connected()) {
                    SmartDiagnostics.trace(log, SmartDiagnostics.Stage.BINDER_CONNECT, false, binder.lastError());
                    log.log("applyq", "binder still unavailable: " + binder.lastError());
                    setState(false, "PLAY_ERROR");
                    return false;
                }
                SmartDiagnostics.trace(log, SmartDiagnostics.Stage.BINDER_CONNECT, true, binder.lastError());

                int mode = binder.setModeString(1, txPath); // TX14 (path, mode=1) starts playback (returns 4 on success)
                int status = binder.getInt15();             // TX15 status (5 = playing)

                boolean active = mode == 4;
                setState(active, active ? "REPLACEMENT_ACTIVE" : "PLAY_ERROR");
                if (active) {
                    prefs.edit().putString("StagedPath", txPath).apply();
                    ReplacementWatchdog.get(context).markActive(txPath);
                } else {
                    ReplacementWatchdog.get(context).markInactive();
                    binder.clearCache();
                }
                log.log("applyq", "apply done #" + req.sequence + " TX14=" + mode + " TX15=" + status + " total=" + (android.os.SystemClock.elapsedRealtime() - t0) + "ms active=" + active + " staged=" + (staged != null));
                SmartDiagnostics.trace(log, SmartDiagnostics.Stage.TX_APPLY, active,
                        "TX14=" + mode + " (4=ok) TX15=" + status + " (5=playing) path=" + txPath);
                return active;
            } catch (Throwable t) {
                setState(false, "PLAY_ERROR");
                binder.clearCache();
                log.log("applyq", "apply exception #" + req.sequence + ": " + t);
                return false;
            }
        }
    }

    private void setState(boolean active, String state) {
        prefs.edit().putBoolean("ReplacementActive", active).putString("IceCamState", state).apply();
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
