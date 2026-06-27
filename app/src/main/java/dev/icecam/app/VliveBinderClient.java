package dev.icecam.app;

import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    private boolean rootShellMode = false;

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
                rootShellMode = false;
            }
            preferredService = n;
        }
    }

    public String preferredService() { return preferredService; }
    public String lastError() { return lastError; }
    public boolean usesRootShell() { return rootShellMode; }

    public void clearCache() {
        cachedBinder = null;
        cachedName = null;
        rootShellMode = false;
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

    private IBinder getServiceByName(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method m = sm.getDeclaredMethod("getService", String.class);
            IBinder b = (IBinder) m.invoke(null, name);
            if (b != null && b.isBinderAlive()) return b;
        } catch (Throwable t) { lastError = "getService(" + name + "): " + t; }
        return null;
    }

    private boolean tryRootShellConnection(String name) {
        if (!RootBinderShell.isServiceAvailable(name)) return false;
        rootShellMode = true;
        cachedBinder = null;
        cachedName = name;
        preferredService = name;
        lastError = "connected root-shell service=" + name;
        log.log("binder", lastError);
        return true;
    }

    public IBinder service() {
        if (cachedBinder != null && cachedBinder.isBinderAlive()) {
            preferredService = cachedName != null ? cachedName : preferredService;
            lastError = "connected cached service=" + preferredService;
            return cachedBinder;
        }
        if (rootShellMode && RootBinderShell.isServiceAvailable(preferredService)) {
            lastError = "connected root-shell service=" + preferredService;
            return null;
        }

        ArrayList<String> names = new ArrayList<>();
        names.add(preferredService);
        for (String c : candidates) if (!names.contains(c)) names.add(c);
        for (String name : names) {
            IBinder b = getServiceByName(name);
            if (b == null) continue;

            if (RootBootstrap.FIXED_SERVICE_NAME.equals(name) || "vcplax".equals(name) || name.equals(preferredService)) {
                cachedBinder = b;
                cachedName = name;
                preferredService = name;
                rootShellMode = false;
                lastError = "connected raw service=" + name;
                log.log("binder", lastError);
                return b;
            }

            if (probeDescriptor(b, name)) {
                cachedBinder = b;
                cachedName = name;
                preferredService = name;
                rootShellMode = false;
                lastError = "connected probed service=" + name;
                log.log("binder", lastError);
                return b;
            }
            log.log("binder", "reject service=" + name + " descriptor/probe mismatch");
        }

        for (String name : names) {
            if (tryRootShellConnection(name)) return null;
        }

        lastError = "VLive binder not found. service=" + preferredService + " not available";
        return null;
    }

    private boolean probeDescriptor(IBinder b, String name) {
        try {
            String d = b.getInterfaceDescriptor();
            if (DESCRIPTOR.equals(d)) return true;
            if (d != null && d.length() > 0 && !d.equals(DESCRIPTOR)) {
                lastError = "service " + name + " has descriptor " + d;
                return false;
            }
        } catch (Throwable ignored) {}
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            boolean ok = b.transact(TX_STATUS, data, reply, 0);
            if (!ok) return false;
            reply.readException();
            return true;
        } catch (Throwable t) {
            lastError = "probe(" + name + "): " + t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        } finally { data.recycle(); reply.recycle(); }
    }

    public boolean connected() {
        if (cachedBinder != null && cachedBinder.isBinderAlive()) return true;
        if (rootShellMode && RootBinderShell.isServiceAvailable(preferredService)) return true;
        IBinder b = service();
        if (b != null) return true;
        return rootShellMode && RootBinderShell.isServiceAvailable(preferredService);
    }

    private int transactDirect(int code, Parcel data) {
        Parcel reply = Parcel.obtain();
        try {
            IBinder b = cachedBinder != null && cachedBinder.isBinderAlive() ? cachedBinder : service();
            if (b == null) return -999;
            boolean ok = b.transact(code, data, reply, 0);
            if (!ok) { lastError = "transact returned false code=" + code; return -997; }
            reply.readException();
            int value = reply.dataAvail() >= 4 ? reply.readInt() : 0;
            log.log("binder", "TX" + code + " -> " + value + " via " + preferredService);
            return value;
        } catch (Throwable t) {
            lastError = "TX" + code + ": " + t.getClass().getSimpleName() + ": " + t.getMessage();
            log.log("binder", lastError);
            return -998;
        } finally { reply.recycle(); data.recycle(); }
    }

    private int transactIntWithFallback(int code, FallbackCall fallback) {
        if (rootShellMode && RootBinderShell.isServiceAvailable(preferredService)) {
            return fallback.callRootShell();
        }

        service();
        if (!rootShellMode && cachedBinder != null && cachedBinder.isBinderAlive()) {
            int direct = transactDirect(code, fallback.buildDirectParcel());
            if (direct != -999 && direct != -998) return direct;
        }

        ArrayList<String> names = new ArrayList<>();
        names.add(preferredService);
        for (String c : candidates) if (!names.contains(c)) names.add(c);
        for (String name : names) {
            if (tryRootShellConnection(name)) return fallback.callRootShell();
        }

        log.log("binder", "TX" + code + " skipped: " + lastError);
        return -999;
    }

    public int playSource(String path, boolean mirrorFlagIgnoredByOriginal, boolean loopFlag) {
        return transactIntWithFallback(TX_PLAY_SOURCE, new FallbackCall() {
            @Override public Parcel buildDirectParcel() {
                Parcel p = Parcel.obtain();
                p.writeInterfaceToken(DESCRIPTOR);
                p.writeString(path);
                p.writeInt(0);
                p.writeInt(loopFlag ? 1 : 0);
                return p;
            }
            @Override public int callRootShell() {
                int value = RootBinderShell.playSource(preferredService, DESCRIPTOR, path, loopFlag);
                log.log("binder", "TX" + TX_PLAY_SOURCE + " -> " + value + " via root-shell " + preferredService);
                return value;
            }
        });
    }

    public int setModeString(int mode, String value) {
        return transactIntWithFallback(TX_MODE_STRING, new FallbackCall() {
            @Override public Parcel buildDirectParcel() {
                Parcel p = Parcel.obtain();
                p.writeInterfaceToken(DESCRIPTOR);
                p.writeInt(mode);
                p.writeString(value);
                return p;
            }
            @Override public int callRootShell() {
                int v = RootBinderShell.setModeString(preferredService, DESCRIPTOR, mode, value);
                log.log("binder", "TX" + TX_MODE_STRING + " -> " + v + " via root-shell " + preferredService);
                return v;
            }
        });
    }

    public int statusCode() {
        return transactIntWithFallback(TX_STATUS, new FallbackCall() {
            @Override public Parcel buildDirectParcel() {
                Parcel p = Parcel.obtain();
                p.writeInterfaceToken(DESCRIPTOR);
                return p;
            }
            @Override public int callRootShell() {
                int v = RootBinderShell.statusCode(preferredService, DESCRIPTOR);
                log.log("binder", "TX" + TX_STATUS + " -> " + v + " via root-shell " + preferredService);
                return v;
            }
        });
    }

    public int getInt15() {
        return transactIntWithFallback(TX_GET_INT, new FallbackCall() {
            @Override public Parcel buildDirectParcel() {
                Parcel p = Parcel.obtain();
                p.writeInterfaceToken(DESCRIPTOR);
                return p;
            }
            @Override public int callRootShell() {
                return RootBinderShell.simple(preferredService, DESCRIPTOR, TX_GET_INT);
            }
        });
    }

    public int setRange(long start, long end) {
        return transactIntWithFallback(TX_RANGE, new FallbackCall() {
            @Override public Parcel buildDirectParcel() {
                Parcel p = Parcel.obtain();
                p.writeInterfaceToken(DESCRIPTOR);
                p.writeLong(start);
                p.writeLong(end);
                return p;
            }
            @Override public int callRootShell() {
                int v = RootBinderShell.setRange(preferredService, DESCRIPTOR, start, end);
                log.log("binder", "TX" + TX_RANGE + " -> " + v + " via root-shell " + preferredService);
                return v;
            }
        });
    }

    public int setTransform(int mode, float panX, float panY, float zoomX, float zoomY, int flags) {
        log.log("tx24", String.format(java.util.Locale.US,
                "send mode=%d pan=(%.2f,%.2f) zoom=(%.2f,%.2f) flags=0x%08X",
                mode, panX, panY, zoomX, zoomY, flags));
        return transactIntWithFallback(TX_TRANSFORM, new FallbackCall() {
            @Override public Parcel buildDirectParcel() {
                Parcel p = Parcel.obtain();
                p.writeInterfaceToken(DESCRIPTOR);
                p.writeInt(mode);
                p.writeFloat(panX);
                p.writeFloat(panY);
                p.writeFloat(zoomX);
                p.writeFloat(zoomY);
                p.writeInt(flags);
                return p;
            }
            @Override public int callRootShell() {
                int v = RootBinderShell.setTransform(preferredService, DESCRIPTOR, mode, panX, panY, zoomX, zoomY, flags);
                log.log("binder", "TX" + TX_TRANSFORM + " -> " + v + " via root-shell " + preferredService);
                return v;
            }
        });
    }

    public int setTransform(TransformState s) { return setTransform(s.mode, s.panX, s.panY, s.zoomX, s.zoomY, s.flags); }

    public int sendBoolCode(int code, boolean v) {
        return transactIntWithFallback(code, new FallbackCall() {
            @Override public Parcel buildDirectParcel() {
                Parcel p = Parcel.obtain();
                p.writeInterfaceToken(DESCRIPTOR);
                p.writeInt(v ? 1 : 0);
                return p;
            }
            @Override public int callRootShell() {
                return RootBinderShell.sendIntCode(preferredService, DESCRIPTOR, code, v ? 1 : 0);
            }
        });
    }

    public int sendIntCode(int code, int v) {
        return transactIntWithFallback(code, new FallbackCall() {
            @Override public Parcel buildDirectParcel() {
                Parcel p = Parcel.obtain();
                p.writeInterfaceToken(DESCRIPTOR);
                p.writeInt(v);
                return p;
            }
            @Override public int callRootShell() {
                return RootBinderShell.sendIntCode(preferredService, DESCRIPTOR, code, v);
            }
        });
    }

    public int simple(int code) {
        return transactIntWithFallback(code, new FallbackCall() {
            @Override public Parcel buildDirectParcel() {
                Parcel p = Parcel.obtain();
                p.writeInterfaceToken(DESCRIPTOR);
                return p;
            }
            @Override public int callRootShell() {
                return RootBinderShell.simple(preferredService, DESCRIPTOR, code);
            }
        });
    }

    public String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("preferred=").append(preferredService).append('\n');
        sb.append("connected=").append(connected()).append('\n');
        sb.append("rootShell=").append(rootShellMode).append('\n');
        sb.append("rootServiceFound=").append(RootBinderShell.isServiceAvailable(preferredService)).append('\n');
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

    private interface FallbackCall {
        Parcel buildDirectParcel();
        int callRootShell();
    }
}
