# IceCam v29 — original-flow recovery from reverse engineering of `icecamtest.apk`

User uploaded the original APK (`icecamtest.apk`, 15.8 MB) and we decompiled it with jadx + apktool to recover the exact original control plane. v28 was black-screen on Xiaomi SDK 33; v29 changes the Java side to match the original exactly.

## Original package, classes, prefs

- `package="com.android.music"` (masked); SELinux SDK 35 target; min support unchanged.
- Real prefix: `com.xiaomi.vlive.*` (App, MainActivity, FloatService, ControllerFragment, MediaProjectionForegroundService).
- SharedPreferences file: `app_config`.
- Binder interface descriptor: `com.xiaomi.vlive.IMyBinderService`.
- Binder proxy lives at `d1/f.java` (interface `d1/h`, stub `d1/g`, helper `U/t.java`).

## Recovered TX codes (binary-verified)

```text
TX11 j(String path, boolean autoRotate, boolean loop)        play with explicit args
TX12 k()                                                     status int
TX13 c()                                                     int[]   debug info
TX14 i(String path, int mode)                                set path + START (returns 4 ok)
TX15 f()                                                     status int (5 = playing)
TX16 b(boolean) / TX17 a(boolean) / TX19 d(boolean)          toggles
TX18 g(int)                                                  rotation
TX22 e(long, long)                                           range
TX24 h(int, float, float, float, float, int)                 color AutoColor
TX25 l()                                                     stop
```

Helpers in `U/t.java`:

```java
t.a0(String path, int mode)  -> TX14, returns Boolean (i==4)
t.T()                        -> TX15, returns Boolean (f()==5)
t.R(long b, long e)          -> TX22
t.c0(Boolean mirror)         -> TX17 a()
t.d0(int angle)              -> TX18 g()
t.e(Boolean loop)            -> TX17 a()  (loop)
t.Y(int)                     -> TX24 h()  (auto color)
```

## The actual play sequence (from `f1/a.java` case 1)

When the user picks a gallery file:

```java
File play = new File(getCacheDir(), "play.mp4");
copyFromGalleryUri(uri, play);          // ~ /data/data/<pkg>/cache/play.mp4
prefs.put("PlayFileType", 1);
prefs.put("PlayFileMp4",  play.getAbsolutePath());

if (t.a0(path, 1).booleanValue()) {     // ONE TX14 call. mode=1 means "set+play"
    toast("file replacement success");
} else {
    toast("file set but not playing");
}
```

That is the complete apply path. There is **no TX22 reset, no TX11 follow-up, no watchdog poke**. The float panel "Play" button (`d1/b.java` case 0) only calls `t.T()` to check status.

## The SELinux trick in `U/t.E()`

```java
public static d1.h E() {
    if (f775c != null) return f775c;
    q("setenforce 0");                                            // permissive
    IBinder ib = ServiceManager.getService(App.f2107k.d());        // find binder
    if (ib == null) { ... retry/error ... return null; }
    ib.linkToDeath(deathRecipient, 0);
    q("setenforce 1");                                            // back to enforcing
    h proxy = (h) ib.queryLocalInterface("com.xiaomi.vlive.IMyBinderService");
    if (proxy == null) proxy = new d1.f(ib);
    f775c = proxy;
    return proxy;
}
```

The crucial trick: SELinux is permissive **only during `getService()`**. After the
binder is cached in the process, the kernel-level permission check inside
`transact()` no longer hits the `service_manager find` rule, so enforcement
can be turned back on and all subsequent TX calls succeed.

v28 toggled `setenforce 0` once during bootstrap, then SELinux flipped back to
Enforcing on its own; by the time Java called `getService` it was denied. v29
replicates the original toggle-around-getService idiom in
`VliveBinderClient.service()`.

## ServerName is randomized

```java
public final String d() {
    String s = prefs.getString("ServerName", "");
    if (s.isEmpty()) {
        String[] svcs = (String[]) ServiceManager.listServices.invoke(null);
        String base = svcs[new Random().nextInt(svcs.length)];
        s = base + j(1, 3);          // 1..3 random lowercase letters
        prefs.putString("ServerName", s);
    }
    return s;
}
```

Original picks a random existing service name and appends 1–3 lowercase letters.
Our `privsam_service` is fine as long as it stays consistent: the daemon is
launched with that name and the app looks it up with the same name. We keep it
fixed for now; randomization can be reintroduced later if needed for stealth.

## v28 → v29 deltas

| Module | v28 | v29 |
|---|---|---|
| `VliveBinderClient.service()` | direct `getService` once + root-shell fallback | wraps `getService` in `setenforce 0/1` + `linkToDeath` cached proxy |
| `BackendApplyQueue` apply | TX22 → TX14 → TX11 + 450 ms sleep | single TX14 + TX15 status (matches original) |
| `ReplacementWatchdog` | re-issues TX22+TX14+TX11 every 4.5 s | passive 10 s health probe; re-applies path only when daemon died |
| `BackendHealth.hookMapped` | matched `(deleted)` mappings → false ready | requires non-deleted libvc mapping |
| `RootBinderShell` | used as primary fallback | unused on this device; kept for diagnostics |
| TX11 arg #2 | `mirrorH()` (wrong, never matched original) | `PlayAutoRotate` (matches original) |

## Reconstructed Java in `reconstruction/java/com/xiaomi/vlive/`

- `App.java`             — Application onCreate + deploy script (already there, now annotated with exact code).
- `IMyBinderService.java` — interface token + TX codes.
- (planned) `d1/f.java`   — proxy with all 12 TX methods.
- (planned) `U/t.java`    — setenforce-around-getService helper.

## Expected log after v29 install + tap

```text
icecamBuild=IceCam Core v29 0.29-v29-original-flow code=29
ReplacementActive=true   IceCamState=REPLACEMENT_ACTIVE

OK DAEMON_ALIVE
OK BINDER_SERVICE
OK INJECT_HOOK
OK BINDER_CONNECT  service=privsam_service          ← not root-shell anymore
OK TX_APPLY: TX14=4 (4=ok) TX15=5 (5=playing) path=/data/camera/source.<ext>

watchdog: probeCount>=1 recoveryCount=0
```

The replacement frame should remain visible because no further TX traffic is sent
to the daemon. Watchdog only kicks in if `hook=no` is detected (cameraserver
respawn or libvc unloaded).
