package dev.icecam.app.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import dev.icecam.app.AppLogger;
import dev.icecam.app.BackendApplyQueue;
import dev.icecam.app.MediaTransformer;
import dev.icecam.app.RootBootstrap;
import dev.icecam.app.TransformState;
import dev.icecam.app.VliveBinderClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Side effects for the runtime command bus, rewritten to the original
 * {@code icecamtest.apk} control model.
 *
 * Native daemon control surface (from {@code d1/f.java}):
 * <ul>
 *   <li>TX14 {@code i(path, mode)} — set source file + start (one call, like {@code t.a0}).</li>
 *   <li>TX18 {@code g(int)}        — rotation degrees. LIVE, no decoder restart.</li>
 *   <li>TX19 {@code d(bool)}       — horizontal mirror. LIVE.</li>
 *   <li>TX17 {@code a(bool)}       — loop. LIVE.</li>
 *   <li>TX24 {@code h(...)}        — AutoColor (NOT geometry). Unused here.</li>
 * </ul>
 *
 * Control routing:
 * <ul>
 *   <li><b>Rotation / mirrorH / loop</b> (any source): single live TX18/TX19/TX17. These
 *       never re-bake and never reload the source, so the stream is not interrupted.</li>
 *   <li><b>Pan / zoom / crop / fit-fill / mirrorV</b> (image sources only): baked into a
 *       JPEG (rotation + mirrorH excluded — handled live) and applied with one debounced
 *       TX14. Video cannot be re-baked, so these are skipped for video.</li>
 * </ul>
 *
 * Nothing here ever bootstraps/redeploys the daemon. Recovery is handled solely by
 * {@link BackendApplyQueue} and only when the binder service is genuinely gone.
 */
public final class SideEffectRunner {
    private static final long DEBOUNCE_MS = 280L;

    private final Context context;
    private final AppLogger log;
    private final SharedPreferences prefs;
    private final RootBootstrap root;
    private final VliveBinderClient binder;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "icecam-runtime-effects"));
    private final ScheduledExecutorService debounce = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "icecam-runtime-debounce"));

    private ScheduledFuture<?> pendingBake;
    private volatile int lastRotationDeg = Integer.MIN_VALUE;
    private volatile int lastMirrorH = Integer.MIN_VALUE;
    private volatile String lastBakeSig = "";

    public SideEffectRunner(Context context, AppLogger log) {
        this.context = context.getApplicationContext();
        this.log = log;
        this.prefs = this.context.getSharedPreferences("app_config", Context.MODE_PRIVATE);
        this.root = new RootBootstrap(this.context, log);
        this.binder = new VliveBinderClient(log);
        this.binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
    }

    public void run(RuntimeCommand c, AppState state, CommandBus bus) {
        switch (c.type) {
            case MUTATE_TRANSFORM:
                state.transform.save(prefs);
                if (isVideoSource(state)) {
                    // Video: rotation/mirror/loop are live; pan/zoom/crop unsupported.
                    io.execute(() -> applyLiveOrientation(state.transform));
                } else {
                    // Image: all geometry is baked into one debounced TX14 (no desync, no live TX).
                    scheduleBake(state);
                }
                break;
            case COMMIT:
                cancelBake();
                io.execute(() -> {
                    String opId = "commit-" + c.id;
                    boolean ok = false;
                    try {
                        state.transform.save(prefs);
                        ok = applyMedia(state, true);
                        if (isVideoSource(state)) applyLiveOrientation(state.transform);
                    } catch (Throwable t) { if (log != null) log.log("runtime", "commit failed #" + c.id + ": " + t); }
                    bus.dispatch(RuntimeCommand.opFinished(opId, ok));
                });
                break;
            case START_REPLACEMENT:
                cancelBake();
                io.execute(() -> {
                    String opId = "start-" + c.id;
                    boolean ok = false;
                    try {
                        state.transform.save(prefs);
                        resetLiveCache();
                        ok = applyMedia(state, true);
                        if (isVideoSource(state)) applyLiveOrientation(state.transform);
                    } catch (Throwable t) { if (log != null) log.log("runtime", "start failed #" + c.id + ": " + t); }
                    bus.dispatch(RuntimeCommand.opFinished(opId, ok));
                });
                break;
            case RESTORE_CAMERA:
                cancelBake();
                io.execute(() -> {
                    String opId = "restore-" + c.id;
                    boolean ok = false;
                    try { root.restoreCamera(); binder.clearCache(); resetLiveCache(); ok = true; }
                    catch (Throwable t) { if (log != null) log.log("runtime", "restore failed #" + c.id + ": " + t); }
                    bus.dispatch(RuntimeCommand.opFinished(opId, ok));
                });
                break;
            case SET_LOOP:
                io.execute(() -> {
                    try {
                        if (binder.connected()) {
                            int r = binder.sendBoolCode(VliveBinderClient.TX_ZERO_17, state.media.loop);
                            if (log != null) log.log("runtime", "TX17 loop=" + state.media.loop + " -> " + r);
                        }
                    } catch (Throwable t) { if (log != null) log.log("runtime", "loop failed: " + t); }
                });
                break;
            default: break;
        }
    }

    /** Rotation + mirrorH via live TX18/TX19. Cheap, idempotent, never restarts the stream. */
    private void applyLiveOrientation(TransformState s) {
        if (!prefs.getBoolean("ReplacementActive", false)) return;
        try {
            if (!binder.connected()) return;
            int angle = s.rotationQuadrant() * 90;
            int mir = s.mirrorH() ? 1 : 0;
            if (angle != lastRotationDeg) {
                int r = binder.sendIntCode(VliveBinderClient.TX_INT_18, angle); // TX18 g(angle)
                lastRotationDeg = angle;
                if (log != null) log.log("runtime", "TX18 rotation=" + angle + " -> " + r);
            }
            if (mir != lastMirrorH) {
                int r = binder.sendBoolCode(VliveBinderClient.TX_ZERO_19, mir == 1); // TX19 d(mirror)
                lastMirrorH = mir;
                if (log != null) log.log("runtime", "TX19 mirrorH=" + (mir == 1) + " -> " + r);
            }
        } catch (Throwable t) { if (log != null) log.log("runtime", "live orientation failed: " + t); }
    }

    private void scheduleBake(AppState state) {
        cancelBake();
        pendingBake = debounce.schedule(() -> io.execute(() -> {
            try { applyMedia(state, false); }
            catch (Throwable t) { if (log != null) log.log("runtime", "debounced bake failed: " + t); }
        }), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelBake() {
        if (pendingBake != null) { pendingBake.cancel(false); pendingBake = null; }
    }

    private void resetLiveCache() {
        lastRotationDeg = Integer.MIN_VALUE;
        lastMirrorH = Integer.MIN_VALUE;
        lastBakeSig = "";
    }

    private boolean isVideoSource(AppState state) {
        return MediaTransformer.isVideoPath(mediaPath(state));
    }

    private String mediaPath(AppState state) {
        String path = state.media.originalPath.length() > 0 ? state.media.originalPath : state.media.playPath;
        if (path.length() == 0) path = prefs.getString("PlayFileMp4", "");
        return path;
    }

    /**
     * Apply the current media + geometry with a single TX14.
     *
     * Image: bake the full transform (pan/zoom/crop/fit/rotate/mirror) into a JPEG so
     * the daemon loops one self-contained frame — no live TX, no desync, stable FPS.
     * Video: load the file directly (geometry handled live elsewhere).
     */
    private boolean applyMedia(AppState state, boolean force) {
        if (!force && !prefs.getBoolean("ReplacementActive", false)) return false;
        String path = mediaPath(state);
        if (path.length() == 0) { if (log != null) log.log("runtime", "applyMedia: no media path"); return false; }

        if (MediaTransformer.isVideoPath(path)) {
            BackendApplyQueue.get(context).enqueue(path, force ? "start-video" : "video", force);
            if (log != null) log.log("runtime", "video apply via TX14 path=" + path);
            return true;
        }

        String sig = path + "|" + state.transform.summary();
        if (!force && sig.equals(lastBakeSig)) {
            if (log != null) log.log("runtime", "bake unchanged; skip");
            return true;
        }
        String baked = MediaTransformer.bakeImage(context, path, state.transform, log);
        if (baked == null) baked = path;
        prefs.edit().putString("BakedPlayFileMp4", baked).apply();
        BackendApplyQueue.get(context).enqueue(baked, force ? "start-image" : "geometry-image", force);
        lastBakeSig = sig;
        if (log != null) log.log("runtime", "image baked -> " + baked + " " + state.transform.summary());
        return true;
    }
}
