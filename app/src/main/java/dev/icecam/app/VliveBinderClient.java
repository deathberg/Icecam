package dev.icecam.app;

import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Binder client modeled directly on the original IceCam {@code U/t.E()} helper.
 *
 * The original APK works around SELinux Enforcing by toggling permissive briefly
 * around the {@code ServiceManager.getService} call:
 *
 * <pre>
 *   q("setenforce 0");
 *   IBinder iBinder = ServiceManager.getService(ServerName);
 *   iBinder.linkToDeath(...);
 *   q("setenforce 1");
 * </pre>
 *
 * After getService() returns, the binder reference stays valid for the lifetime
 * of the daemon, so subsequent {@code transact()} calls do not need SELinux
 * permissive again. {@code linkToDeath} clears the cached reference when the
 * daemon dies.
 *
 * Transaction codes recovered from {@code d1/f.java}:
 *
 * <pre>
 *   TX11 j(String path, boolean autoRotate, boolean loop)  // start playback
 *   TX12 k()                                               // status int
 *   TX13 c()                                               // int[] info
 *   TX14 i(String path, int mode)                          // set path + start (returns 4 on success)
 *   TX15 f()                                               // status int (5 = playing)
 *   TX16 b(boolean)                                        // toggle X
 *   TX17 a(boolean)                                        // toggle Y
 *   TX18 g(int)                                            // rotation
 *   TX19 d(boolean)                                        // toggle
 *   TX22 e(long, long)                                     // range
 *   TX24 h(int, float, float, float, float, int)           // color/transform
 *   TX25 l()                                               // stop
 * </pre>
 */
public final class VliveBinderClient {
    public static final String DESCRIPTOR = "com.xiaomi.vlive.IMyBinderService";
    public static final int TX_PLAY_SOURCE = 11, TX_STATUS = 12, TX_INT_ARRAY = 13, TX_MODE_STRING = 14,
            TX_GET_INT = 15, TX_ZERO_16 = 16, TX_ZERO_17 = 17, TX_INT_18 = 18, TX_ZERO_19 = 19,
            TX_RANGE = 22, TX_TRANSFORM = 24, TX_25 = 25;

    private final AppLogger log;
    private String preferredService = RootBootstrap.FIXED_SERVICE_NAME;
    private String lastError = "not connected";
    private IBinder cachedBinder = null;
    private String cachedName = null;
    private IBinder.DeathRecipient deathRecipient = null;

    private final List<String> candidates = new ArrayList<>(Arrays.asList(
            RootBootstrap.FIXED_SERVICE_NAME,
            "com.xiaomi.vlive.IMyBinderService",
            "vlive",
            "vlive_service",
            "vcplax",
            "MyBinderService"));

    public VliveBinderClient(AppLogger logger) { log = logger; }

    public void setPreferredService(String s) {
        if (s != null && s.trim().length() > 0) {
            String n = s.trim();
            if (!n.equals(preferredService)) {
                cachedBinder = null;
                cachedName = null;
            }
            preferredService = n;
        }
    }

    public String preferredService() { return preferredService; }
    public String lastError() { return lastError; }
    public boolean usesRootShell() { return false; }

    public void clearCache() {
        if (cachedBinder != null && deathRecipient != null) {
            try { cachedBinder.unlinkToDeath(deathRecipient, 0); } catch (Throwable ignored) {}
        }
        cachedBinder = null;
        cachedName = null;
        deathRecipient = null;
        lastError = "cache cleared";
    }

    public String[] listServices() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method m = sm.getDeclaredMethod("listServices");
            String[] arr = (String[]) m.invoke(null);
            if (arr != null) return arr;
        } catch (Throwable t) { lastError = "listServices: " + t; }
        return new String[0];
    }

    /**
     * Match {@code U/t.E()} exactly: setenforce 0 around the {@link android.os.ServiceManager#getService}
     * lookup so SELinux Enforcing cannot block the find. Cache the binder + linkToDeath.
     */
    public IBinder service() {
        if (cachedBinder != null && cachedBinder.isBinderAlive()) {
            return cachedBinder;
        }
        // setenforce 0 — give Java getService permissive window. Original APK does exactly this.
        try { Shell.su("setenforce 0\n"); } catch (Throwable ignored) {}

        ArrayList<String> names = new ArrayList<>();
        names.add(preferredService);
        for (String c : candidates) if (!names.contains(c)) names.add(c);
        IBinder found = null;
        String foundName = null;
        for (String name : names) {
            IBinder b = getServiceByName(name);
            if (b != null && b.isBinderAlive()) {
                found = b;
                foundName = name;
                break;
            }
        }

        if (found != null) {
            IBinder.DeathRecipient dr = new IBinder.DeathRecipient() {
                @Override public void binderDied() {
                    log.log("binder", "death: " + cachedName);
                    cachedBinder = null;
                    deathRecipient = null;
                }
            };
            try { found.linkToDeath(dr, 0); } catch (Throwable t) { log.log("binder", "linkToDeath failed: " + t); }
            cachedBinder = found;
            cachedName = foundName;
            preferredService = foundName;
            deathRecipient = dr;
            lastError = "connected service=" + foundName;
            log.log("binder", lastError);
        } else {
            lastError = "VLive binder not found. service=" + preferredService + " not available";
        }

        // setenforce 1 — restore enforcing immediately. Original APK does exactly this.
        try { Shell.su("setenforce 1\n"); } catch (Throwable ignored) {}
        return found;
    }

    private IBinder getServiceByName(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method m = sm.getDeclaredMethod("getService", String.class);
            IBinder b = (IBinder) m.invoke(null, name);
            if (b != null && b.isBinderAlive()) return b;
        } catch (Throwable t) { lastError = "getService(" + name + "): " + t; }
        return null;
    }

    public boolean connected() {
        if (cachedBinder != null && cachedBinder.isBinderAlive()) return true;
        return service() != null;
    }

    private int transact(int code, Parcel data) {
        Parcel reply = Parcel.obtain();
        try {
            IBinder b = service();
            if (b == null) {
                log.log("binder", "TX" + code + " skipped: " + lastError);
                return -999;
            }
            boolean ok = b.transact(code, data, reply, 0);
            if (!ok) { lastError = "transact returned false code=" + code; return -997; }
            reply.readException();
            int value = reply.dataAvail() >= 4 ? reply.readInt() : 0;
            log.log("binder", "TX" + code + " -> " + value + " via " + preferredService);
            return value;
        } catch (Throwable t) {
            lastError = "TX" + code + ": " + t.getClass().getSimpleName() + ": " + t.getMessage();
            log.log("binder", lastError);
            // Daemon may have died between service() and transact(); drop cache.
            clearCache();
            return -998;
        } finally { reply.recycle(); data.recycle(); }
    }

    /** Original {@code d1/f.j(path, autoRotate, loop)} — TX11. */
    public int playSource(String path, boolean autoRotate, boolean loop) {
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(DESCRIPTOR);
        p.writeString(path);
        p.writeInt(autoRotate ? 1 : 0);
        p.writeInt(loop ? 1 : 0);
        return transact(TX_PLAY_SOURCE, p);
    }

    /** Original {@code d1/f.i(path, mode)} — TX14. Returns 4 on success. */
    public int setModeString(int mode, String path) {
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(DESCRIPTOR);
        p.writeInt(mode);
        p.writeString(path);
        return transact(TX_MODE_STRING, p);
    }

    /** Original {@code d1/f.k()} — TX12 status int. */
    public int statusCode() {
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(DESCRIPTOR);
        return transact(TX_STATUS, p);
    }

    /** Original {@code d1/f.f()} — TX15. Returns 5 when playing. */
    public int getInt15() {
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(DESCRIPTOR);
        return transact(TX_GET_INT, p);
    }

    public int setRange(long start, long end) {
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(DESCRIPTOR);
        p.writeLong(start);
        p.writeLong(end);
        return transact(TX_RANGE, p);
    }

    public int setTransform(int mode, float panX, float panY, float zoomX, float zoomY, int flags) {
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(DESCRIPTOR);
        p.writeInt(mode);
        p.writeFloat(panX);
        p.writeFloat(panY);
        p.writeFloat(zoomX);
        p.writeFloat(zoomY);
        p.writeInt(flags);
        return transact(TX_TRANSFORM, p);
    }

    public int setTransform(TransformState s) { return setTransform(s.mode, s.panX, s.panY, s.zoomX, s.zoomY, s.flags); }

    public int sendBoolCode(int code, boolean v) {
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(DESCRIPTOR);
        p.writeInt(v ? 1 : 0);
        return transact(code, p);
    }

    public int sendIntCode(int code, int v) {
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(DESCRIPTOR);
        p.writeInt(v);
        return transact(code, p);
    }

    public int simple(int code) {
        Parcel p = Parcel.obtain();
        p.writeInterfaceToken(DESCRIPTOR);
        return transact(code, p);
    }

    public String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("preferred=").append(preferredService).append('\n');
        sb.append("connected=").append(cachedBinder != null && cachedBinder.isBinderAlive()).append('\n');
        sb.append("lastError=").append(lastError).append('\n');
        sb.append("expected descriptor=").append(DESCRIPTOR).append('\n');
        sb.append("candidate services=\n");
        for (String c : candidates) sb.append("  ").append(c).append('\n');
        sb.append("filtered Android services=\n");
        for (String s : listServices()) {
            String lo = s.toLowerCase();
            if (lo.contains("vlive") || lo.contains("camera") || lo.contains("media") || lo.contains("vcplax") || lo.contains("ice") || lo.contains("privsam"))
                sb.append("  ").append(s).append('\n');
        }
        return sb.toString();
    }
}
