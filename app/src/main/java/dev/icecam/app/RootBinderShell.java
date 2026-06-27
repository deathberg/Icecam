package dev.icecam.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Root-shell Binder transport used when Java ServiceManager is blocked by SELinux.
 *
 * The daemon still registers privsam_service in servicemanager, but only root/shell
 * context can find it on many Android 13 ROMs. Root can invoke service call safely.
 */
public final class RootBinderShell {
    private static final Pattern HEX_WORD = Pattern.compile("0x[0-9a-f]+:\\s+([0-9a-f]{8})");
    private static final Pattern PARCEL_HEAD = Pattern.compile("parcel\\(\\s*([0-9a-f]{8})");

    private RootBinderShell() {}

    public static boolean isServiceAvailable(String service) {
        if (service == null || service.trim().isEmpty()) return false;
        Shell.Result r = Shell.su("service check " + Shell.q(service.trim()) + " 2>&1");
        String all = (r.out + "\n" + r.err).toLowerCase(Locale.US);
        return all.contains("found");
    }

    public static int transactInt(String service, int code, String descriptor, String... args) {
        StringBuilder cmd = new StringBuilder();
        cmd.append("service call ").append(Shell.q(service)).append(' ').append(code);
        if (descriptor != null && descriptor.length() > 0) {
            cmd.append(" s16 ").append(Shell.q(descriptor));
        }
        for (int i = 0; i + 1 < args.length; i += 2) {
            String type = args[i];
            String value = args[i + 1];
            if ("i32".equals(type)) cmd.append(" i32 ").append(value);
            else if ("i64".equals(type)) cmd.append(" i64 ").append(value);
            else if ("f".equals(type)) cmd.append(" f ").append(value);
            else if ("s16".equals(type)) cmd.append(" s16 ").append(Shell.q(value));
            else cmd.append(' ').append(type).append(' ').append(value);
        }
        cmd.append(" 2>&1");
        Shell.Result r = Shell.su(cmd.toString());
        return parseServiceCallInt(r.out + "\n" + r.err);
    }

    public static int playSource(String service, String descriptor, String path, boolean loop) {
        return transactInt(service, VliveBinderClient.TX_PLAY_SOURCE, descriptor,
                "s16", path, "i32", "0", "i32", loop ? "1" : "0");
    }

    public static int setModeString(String service, String descriptor, int mode, String value) {
        return transactInt(service, VliveBinderClient.TX_MODE_STRING, descriptor,
                "i32", String.valueOf(mode), "s16", value);
    }

    public static int statusCode(String service, String descriptor) {
        return transactInt(service, VliveBinderClient.TX_STATUS, descriptor);
    }

    public static int setTransform(String service, String descriptor, int mode,
            float panX, float panY, float zoomX, float zoomY, int flags) {
        return transactInt(service, VliveBinderClient.TX_TRANSFORM, descriptor,
                "i32", String.valueOf(mode),
                "f", String.format(Locale.US, "%f", panX),
                "f", String.format(Locale.US, "%f", panY),
                "f", String.format(Locale.US, "%f", zoomX),
                "f", String.format(Locale.US, "%f", zoomY),
                "i32", String.format(Locale.US, "%d", flags));
    }

    public static int setRange(String service, String descriptor, long start, long end) {
        return transactInt(service, VliveBinderClient.TX_RANGE, descriptor,
                "i64", String.valueOf(start), "i64", String.valueOf(end));
    }

    public static int sendIntCode(String service, String descriptor, int code, int value) {
        return transactInt(service, code, descriptor, "i32", String.valueOf(value));
    }

    public static int simple(String service, String descriptor, int code) {
        return transactInt(service, code, descriptor);
    }

    static int parseServiceCallInt(String output) {
        if (output == null) return -997;
        String lo = output.toLowerCase(Locale.US);
        if (lo.contains("error") || lo.contains("not found") || lo.contains("permission denied")) return -999;
        Matcher m = HEX_WORD.matcher(lo);
        if (m.find()) return (int) Long.parseLong(m.group(1), 16);
        m = PARCEL_HEAD.matcher(lo);
        if (m.find()) return (int) Long.parseLong(m.group(1), 16);
        if (lo.contains("result: parcel")) return 0;
        return -997;
    }
}
