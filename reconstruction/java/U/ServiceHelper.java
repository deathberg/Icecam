package U;

import android.os.IBinder;
import d1.MyBinderProxy;

/**
 * EXACT reconstruction of the original {@code U/t.java} root/binder helper from
 * {@code icecamtest.apk}. Only the binder-relevant static methods are reproduced;
 * the rest of {@code t} is unrelated material-design utility code merged by the
 * obfuscator into the same class.
 *
 * The two essential idioms recovered:
 *  1. A persistent {@code su} pipe ({@link #sh}) reused for all root commands.
 *  2. {@link #binder()} toggles SELinux permissive ONLY around getService, caches the
 *     proxy, and re-enables enforcing. linkToDeath clears the cache on daemon death.
 */
public final class ServiceHelper {
    private static Process suProc;
    private static java.io.DataOutputStream suOut;
    private static MyBinderProxy cached;
    private static int retry;

    /** original t.q(String): run a command on a persistent su shell, return stdout. */
    public static synchronized String sh(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            if (suProc == null || suOut == null) {
                suProc = Runtime.getRuntime().exec("su");
                suOut = new java.io.DataOutputStream(suProc.getOutputStream());
            }
            String mark = "EOF_MARK_" + System.currentTimeMillis();
            suOut.writeBytes(cmd + "\n");
            suOut.writeBytes("echo " + mark + "\n");
            suOut.flush();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(suProc.getInputStream()));
            String line;
            while ((line = br.readLine()) != null && !line.equals(mark)) { sb.append(line).append('\n'); }
        } catch (java.io.IOException e) { sb.append("ERROR: ").append(e.getMessage()); }
        return sb.toString().trim();
    }

    /** original t.E(): acquire the IMyBinderService proxy with the SELinux toggle trick. */
    public static synchronized MyBinderProxy binder(String serverName) {
        try {
            if (cached != null) return cached;
            sh("setenforce 0");
            IBinder ib = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, serverName);
            if (ib == null) {
                if (++retry > 5) { /* original shows a localized error toast here */ }
                return null;
            }
            retry = 0;
            ib.linkToDeath(() -> cached = null, 0);
            sh("setenforce 1");
            cached = new MyBinderProxy(ib);
            return cached;
        } catch (Exception e) { return null; }
    }

    // Recovered convenience wrappers (original letter names in comments):
    public static boolean setSource(String server, String path, int mode) {              // t.a0
        MyBinderProxy b = binder(server); return b != null && b.setSource(path, mode) == 4;
    }
    public static boolean isPlaying(String server) {                                      // t.T
        MyBinderProxy b = binder(server); return b != null && b.status() == 5;
    }
    public static void setRange(String server, long begin, long end) {                    // t.R
        MyBinderProxy b = binder(server); if (b != null) b.setRange(begin, end);
    }
    public static void setRotation(String server, int degrees) {                          // t.d0
        MyBinderProxy b = binder(server); if (b != null) b.rotation(degrees);
    }
    public static void setMirror(String server, boolean on) {                             // t.c0
        MyBinderProxy b = binder(server); if (b != null) b.mirrorH(on);
    }
    public static void setLoop(String server, boolean on) {                               // t.e
        MyBinderProxy b = binder(server); if (b != null) b.loop(on);
    }
}
