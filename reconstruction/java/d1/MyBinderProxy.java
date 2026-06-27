package d1;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * EXACT reconstruction of the original binder proxy {@code d1/f.java} from
 * {@code icecamtest.apk} (jadx). Method names restored to readable form; transaction
 * codes and parcel layout are byte-faithful to the decompiled original.
 *
 * Interface token written first in every parcel: "com.xiaomi.vlive.IMyBinderService".
 */
public final class MyBinderProxy {
    public static final String DESCRIPTOR = "com.xiaomi.vlive.IMyBinderService";
    private final IBinder remote;

    public MyBinderProxy(IBinder remote) { this.remote = remote; }
    public IBinder asBinder() { return remote; }

    // ---- original method letter -> transaction code mapping ----------------
    // a(z)  -> TX17   (loop on/off)             original t.e(Boolean)
    // b(z)  -> TX16   (unused in UI)            original t.i(Boolean)
    // c()   -> TX13   (int[] status array)      polled every 1s by App timer
    // d(z)  -> TX19   (horizontal mirror)       original t.c0(Boolean)
    // e(l,l)-> TX22   (begin,end time range)    original t.R(long,long)
    // f()   -> TX15   (int status; 5 = playing) original t.T()
    // g(i)  -> TX18   (rotation degrees)        original t.d0(int)
    // h(...)-> TX24   (AutoColor; NOT geometry) original t.Y(int)
    // i(s,i)-> TX14   (set path + start; ret 4) original t.a0(String,int)
    // j(s,z,z)->TX11  (play with autoRotate,loop)
    // k()   -> TX12   (stop when active)
    // l()   -> TX25   (stop)

    public int loop(boolean on) { return callBool(17, on); }                 // a
    public int toggle16(boolean on) { return callBool(16, on); }             // b
    public int[] statusArray() throws RemoteException { return callIntArray(13); } // c
    public int mirrorH(boolean on) { return callBool(19, on); }              // d
    public int setRange(long begin, long end) { return callRange(22, begin, end); } // e
    public int status() { return callVoid(15); }                            // f  (5 = playing)
    public int rotation(int degrees) { return callInt(18, degrees); }        // g
    public int autoColor(int mode, float x, float y, float intensity, float diameter, int colorMode) { // h
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeInt(mode); d.writeFloat(x); d.writeFloat(y);
            d.writeFloat(intensity); d.writeFloat(diameter); d.writeInt(colorMode);
            remote.transact(24, d, r, 0); r.readException(); return r.readInt();
        } catch (RemoteException e) { return -1; } finally { r.recycle(); d.recycle(); }
    }
    public int setSource(String path, int mode) { return callStringInt(14, mode, path); } // i (ret 4 ok)
    public int play(String path, boolean autoRotate, boolean loop) {        // j
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeString(path); d.writeInt(autoRotate ? 1 : 0); d.writeInt(loop ? 1 : 0);
            remote.transact(11, d, r, 0); r.readException(); return r.readInt();
        } catch (RemoteException e) { return -1; } finally { r.recycle(); d.recycle(); }
    }
    public int stopWhenActive() { return callVoid(12); }                    // k
    public int stop() { return callVoid(25); }                              // l

    // ---- parcel helpers ----------------------------------------------------
    private int callVoid(int code) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try { d.writeInterfaceToken(DESCRIPTOR); remote.transact(code, d, r, 0); r.readException(); return r.readInt(); }
        catch (RemoteException e) { return -1; } finally { r.recycle(); d.recycle(); }
    }
    private int callInt(int code, int v) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try { d.writeInterfaceToken(DESCRIPTOR); d.writeInt(v); remote.transact(code, d, r, 0); r.readException(); return r.readInt(); }
        catch (RemoteException e) { return -1; } finally { r.recycle(); d.recycle(); }
    }
    private int callBool(int code, boolean v) { return callInt(code, v ? 1 : 0); }
    private int callStringInt(int code, int i, String s) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try { d.writeInterfaceToken(DESCRIPTOR); d.writeInt(i); d.writeString(s); remote.transact(code, d, r, 0); r.readException(); return r.readInt(); }
        catch (RemoteException e) { return -1; } finally { r.recycle(); d.recycle(); }
    }
    private int callRange(int code, long a, long b) {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try { d.writeInterfaceToken(DESCRIPTOR); d.writeLong(a); d.writeLong(b); remote.transact(code, d, r, 0); r.readException(); return r.readInt(); }
        catch (RemoteException e) { return -1; } finally { r.recycle(); d.recycle(); }
    }
    private int[] callIntArray(int code) throws RemoteException {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try { d.writeInterfaceToken(DESCRIPTOR); remote.transact(code, d, r, 0); r.readException(); return r.createIntArray(); }
        finally { r.recycle(); d.recycle(); }
    }
}
