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
 * Side effects for the runtime command bus.
 *
 * Reverse engineering of the original {@code icecamtest.apk} established the real
 * native capabilities of the {@code vcplax} daemon:
 *
 * <ul>
 *   <li>TX14 {@code i(path, mode)} — set source file and start playback.</li>
 *   <li>TX18 {@code g(int)}        — rotation in degrees (0/90/180/270). LIVE, no restart.</li>
 *   <li>TX19 {@code d(bool)}       — horizontal mirror. LIVE, no restart.</li>
 *   <li>TX17 {@code a(bool)}       — loop on/off. LIVE.</li>
 *   <li>TX24 {@code h(...)}        — AutoColor (irradiation), NOT geometry.</li>
 * </ul>
 *
 * The native daemon has <b>no pan / zoom / crop</b> support. Previous builds sent
 * TX24 on every control tap (interpreted as color) and re-ran the full TX14 apply,
 * which restarted the decoder and caused lag / stream interruption / wrong mapping.
 *
 * v30 routing:
 * <ul>
 *   <li>Pan / zoom / crop / fit-fill / mirrorV (image only): baked into a JPEG via
 *       {@link MediaTransformer}, applied with a single debounced TX14. Rotation and
 *       mirrorH are also baked for images so the rendered frame matches the preview.</li>
 *   <li>Video sources: rotation -> TX18, mirrorH -> TX19, loop -> TX17 live. Pan/zoom/crop
 *       are not supported natively for video and are skipped (preview still reflects them).</li>
 * </ul>
 *
 * All native traffic is debounced so a burst of taps collapses into one apply.
 */
public final class SideEffectRunner {
    private static final long DEBOUNCE_MS = 320L;

    private final Context context;
    private final AppLogger log;
    private final SharedPreferences prefs;
    private final RootBootstrap root;
    private final VliveBinderClient binder;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "icecam-runtime-effects"));
    private final ScheduledExecutorService debounce = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "icecam-runtime-debounce"));

    private ScheduledFuture<?> pendingApply;
    private volatile long lastAppliedFlags = Long.MIN_VALUE;
    private volatile String lastAppliedSig = "";

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
                // Persist new transform so bake / native apply read the latest values.
                // On-screen realtime preview is driven by the StateStore listener, so the
                // user already sees instant feedback. The native stream updates once after
                // the debounce window so rapid taps never restart the decoder mid-burst.
                state.transform.save(prefs);
                scheduleGeometryApply(state, c.source);
                break;
            case COMMIT:
                cancelPending();
                io.execute(() -> {
                    String opId = "commit-" + c.id;
                    boolean ok = false;
                    try {
                        state.transform.save(prefs);
                        ok = applyGeometry(state, true);
                    } catch (Throwable t) { if (log != null) log.log("runtime", "commit side effect failed #" + c.id + ": " + t); }
                    bus.dispatch(RuntimeCommand.opFinished(opId, ok));
                });
                break;
            case START_REPLACEMENT:
                cancelPending();
                io.execute(() -> {
                    String opId = "start-" + c.id;
                    boolean ok = false;
                    try {
                        binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
                        if (!binder.connected()) { root.bootstrap(); binder.clearCache(); sleep(350); }
                        state.transform.save(prefs);
                        lastAppliedFlags = Long.MIN_VALUE; // force a fresh native apply
                        lastAppliedSig = "";
                        ok = applyGeometry(state, true);
                    } catch (Throwable t) { if (log != null) log.log("runtime", "start side effect failed #" + c.id + ": " + t); }
                    bus.dispatch(RuntimeCommand.opFinished(opId, ok));
                });
                break;
            case RESTORE_CAMERA:
                cancelPending();
                io.execute(() -> {
                    String opId = "restore-" + c.id;
                    boolean ok = false;
                    try { root.restoreCamera(); binder.clearCache(); lastAppliedSig = ""; lastAppliedFlags = Long.MIN_VALUE; ok = true; }
                    catch (Throwable t) { if (log != null) log.log("runtime", "restore side effect failed #" + c.id + ": " + t); }
                    bus.dispatch(RuntimeCommand.opFinished(opId, ok));
                });
                break;
            case SET_LOOP:
                io.execute(() -> {
                    try {
                        if (binder.connected()) {
                            int r = binder.sendBoolCode(VliveBinderClient.TX_ZERO_17, state.media.loop); // TX17 a(loop)
                            if (log != null) log.log("runtime", "TX17 loop=" + state.media.loop + " -> " + r);
                        }
                    } catch (Throwable t) { if (log != null) log.log("runtime", "loop side effect failed: " + t); }
                });
                break;
            default: break;
        }
    }

    private void scheduleGeometryApply(AppState state, RuntimeTypes.Source source) {
        cancelPending();
        pendingApply = debounce.schedule(() -> io.execute(() -> {
            try { applyGeometry(state, false); }
            catch (Throwable t) { if (log != null) log.log("runtime", "debounced apply failed: " + t); }
        }), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelPending() {
        if (pendingApply != null) { pendingApply.cancel(false); pendingApply = null; }
    }

    /**
     * Apply current geometry to the live stream using the correct native channel.
     * Returns true if the backend accepted the update (or nothing needed to change).
     */
    private boolean applyGeometry(AppState state, boolean force) {
        if (!force && !prefs.getBoolean("ReplacementActive", false)) {
            if (log != null) log.log("runtime", "geometry apply skipped: replacement inactive");
            return false;
        }
        String path = state.media.originalPath.length() > 0 ? state.media.originalPath : state.media.playPath;
        if (path.length() == 0) path = prefs.getString("PlayFileMp4", "");
        if (path.length() == 0) { if (log != null) log.log("runtime", "geometry apply: no media path"); return false; }

        TransformState s = TransformState.load(prefs);

        if (MediaTransformer.isVideoPath(path)) {
            return applyVideoGeometry(path, s, force);
        }
        return applyImageGeometry(path, s, force);
    }

    /**
     * Image sources: bake every geometric operation into a JPEG and apply with a
     * single TX14. Skip work when nothing changed since the last apply.
     */
    private boolean applyImageGeometry(String sourcePath, TransformState s, boolean force) {
        String sig = sourcePath + "|" + s.summary();
        if (!force && sig.equals(lastAppliedSig)) {
            if (log != null) log.log("runtime", "image geometry unchanged; skip re-bake");
            return true;
        }
        String baked = MediaTransformer.bakeImage(context, sourcePath, s, log);
        if (baked == null) baked = sourcePath;
        prefs.edit().putString("BakedPlayFileMp4", baked).apply();
        // Neutralize any live native rotation/mirror left over from a video session so
        // the baked frame (which already contains rotation/mirror) is not double-applied.
        try {
            if (binder.connected()) {
                binder.sendIntCode(VliveBinderClient.TX_INT_18, 0);   // TX18 rotation = 0
                binder.sendBoolCode(VliveBinderClient.TX_ZERO_19, false); // TX19 mirror = off
            }
        } catch (Throwable ignored) {}
        BackendApplyQueue.get(context).enqueue(baked, "geometry-image", force);
        lastAppliedSig = sig;
        if (log != null) log.log("runtime", "image geometry applied bake=" + baked + " " + s.summary());
        return true;
    }

    /**
     * Video sources: native daemon supports only rotation (TX18) and mirror (TX19)
     * as live geometry. Pan/zoom/crop are not representable; preview still reflects them.
     */
    private boolean applyVideoGeometry(String sourcePath, TransformState s, boolean force) {
        long flags = packLiveFlags(s);
        if (!force && flags == lastAppliedFlags) {
            if (log != null) log.log("runtime", "video geometry unchanged; skip");
            return true;
        }
        try {
            binder.setPreferredService(RootBootstrap.FIXED_SERVICE_NAME);
            if (!binder.connected()) { if (log != null) log.log("runtime", "video geometry: binder not connected"); return false; }
            int angle = s.rotationQuadrant() * 90;
            int rot = binder.sendIntCode(VliveBinderClient.TX_INT_18, angle);     // TX18 g(angle)
            int mir = binder.sendBoolCode(VliveBinderClient.TX_ZERO_19, s.mirrorH()); // TX19 d(mirror)
            int loop = binder.sendBoolCode(VliveBinderClient.TX_ZERO_17, prefs.getBoolean("PlayisLoop", true)); // TX17 a(loop)
            lastAppliedFlags = flags;
            if (log != null) log.log("runtime", "video geometry TX18(rot=" + angle + ")=" + rot + " TX19(mir=" + s.mirrorH() + ")=" + mir + " TX17(loop)=" + loop
                    + (hasUnsupported(s) ? " [pan/zoom/crop not supported for video]" : ""));
            return rot >= 0 || mir >= 0;
        } catch (Throwable t) {
            if (log != null) log.log("runtime", "video geometry failed: " + t);
            return false;
        }
    }

    private static boolean hasUnsupported(TransformState s) {
        return Math.abs(s.panX) > 0.001f || Math.abs(s.panY) > 0.001f
                || Math.abs(s.zoomX - 1f) > 0.001f || Math.abs(s.zoomY - 1f) > 0.001f
                || s.cropPreset() != 0;
    }

    private static long packLiveFlags(TransformState s) {
        long v = s.rotationQuadrant() & 0x3;
        if (s.mirrorH()) v |= 1L << 4;
        return v;
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
