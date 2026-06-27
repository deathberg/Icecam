# IceCam — handover document (state after v28)

Generated 2026-06-27 from logs `IceCam_shared_text` (v26 / v27 / v28 sessions).

## Repository

- **GitHub:** https://github.com/deathberg/Icecam
- **Branch:** `cursor/xiaomi-selinux-binder-fix-e216`
- **Open PR:** https://github.com/deathberg/Icecam/pull/2
- **Target device:** Xiaomi M2102J20SG, Android 13 / SDK 33, abi `arm64-v8a`, KernelSU root, SELinux **Enforcing**

## Project architecture (reconstructed)

```text
MainActivity / FloatService (Java)
  -> RootBootstrap (su) ── deploys 3 native artifacts:
        /data/vcplax           (PIE executable, 12 MB)
        /data/libvc.so         (ShadowHook camera hook lib, 1.3 MB)
        /data/libvc++.so       (libshadowhook, 74 KB; renamed)
  -> /data/vcplax privsam_service       (root daemon, BBinder + FFmpeg)
        -> defaultServiceManager()->addService("privsam_service", svc)
        -> ptrace + dlopen → inject libvc.so into cameraserver
  -> libvc.so (inside cameraserver)
        -> ShadowHook on GraphicBuffer::lock / lockYCbCr
        -> overwrites YUV planes from a ring buffer that vcplax fills
  -> VliveBinderClient (Java, IceCam UI)
        TX22 setRange      (reset/loop)
        TX14 setModeString (mode=1 → source path)
        TX11 playSource    (path, mirrorFlag, loopFlag)
        TX24 setTransform  (color/debug on these builds)
```

## Native binaries (prebuilt, in `app/src/main/jniLibs/`)

These are the **original APK binaries**, not rebuilt. `reconstruction/native/` contains a documented C++ model only.

| File | Size | Role | Hooks |
|---|---:|---|---|
| `libshadowhook.so` | 74 KB | ByteDance ShadowHook v1.0.10 | inline-hook engine |
| `libvc.so` | 1.3 MB | Camera frame replacement | `GraphicBuffer::lock`, `GraphicBuffer::lockYCbCr` |
| `vcplax.so` | 12 MB | Root daemon + RTMP/FFmpeg + Binder | renamed to `vcplax` on disk |

Interface descriptor for every binder transaction: `com.xiaomi.vlive.IMyBinderService`.

## Java control plane (`app/src/main/java/dev/icecam/app/`)

| Class | Role |
|---|---|
| `MainActivity`, `FloatService` | UI / lifecycle |
| `RootBootstrap` | extract + deploy + launch + verify daemon |
| `SelinuxPolicy` | `magiskpolicy/supolicy/ksud` live rule attempts |
| `NativeExtractor` | unpack jniLibs from APK |
| `BackendHealth` | precheck `daemon + service + hook + staged` |
| `MediaStager` | copy user media → `/data/camera/source.<ext>` |
| `BackendApplyQueue` | serialized TX22 → TX14 → TX11 worker |
| `ReplacementWatchdog` | 4.5 s keep-alive poke loop |
| `VliveBinderClient` | direct Binder + root-shell fallback |
| `RootBinderShell` | `service call privsam_service ...` transport |
| `SmartDiagnostics` | staged trace summary (`EXTRACT → … → TX_APPLY`) |
| `DiagnosticDumper` | builds the `IceCam_shared_text` snapshot |
| `Shell` | thin `su` / `sh` wrapper |
| `runtime/` | reducer/command bus runtime (mostly UI-side) |

## Version history on this branch

| Ver | Commit | Result |
|---|---|---|
| v26 | `e081657` | SELinux rules split by whitespace → never applied; `service check` false-positive on `not found`; vcplax died immediately. |
| v27 | `18133e7` | Daemon, service, hook, TX_APPLY all OK. **Picture flashed briefly**, then live camera returned. |
| v28 | `4b1fe4b` | Idempotent bootstrap, media stager, watchdog. **Black screen now**. SELinux flipped back to **Enforcing**; Binder falls back to root-shell `service call`. TX returns look successful (`TX22=12 TX14=4 TX11=1`), but no frame data reaches the in-process hook. |
| ci  | `8356f5e` | Workflow uploads `IceCam-<version>-<sha>-apk` + source zip. |

## v28 log evidence (the critical lines)

```text
icecamBuild=IceCam Core v28 0.28-v28-stable-replacement code=28
ReplacementActive=true   IceCamState=REPLACEMENT_ACTIVE

--- trace ---
OK DAEMON_ALIVE / BINDER_SERVICE / INJECT_HOOK / BINDER_CONNECT
OK TX_APPLY: TX22=12 TX14=4 TX11=1 path=/data/camera/source.jpg
health-probe: daemon=true service=true hook=true staged=true
watchdog: running=true pokeCount=2

--- binder-java ---
connected=true   rootShell=true     ← Java falls back to root-shell !
lastError=connected root-shell service=privsam_service

--- root-native ---
getenforce: Enforcing                ← SELinux back to Enforcing
service check: Service privsam_service: found
cameraserver_pid=32067
70a0ec1000-... /data/libvc.so      (deleted)   ← old mapping
70a0ff3000-... /data/libvc.so      (deleted)
70a5716000-... /data/libvc++.so    (deleted)
process: root 29503 ... vcplax     ← daemon alive
data-camera: libvc.so libshadowhook.so source.jpg vcplax (all OK on disk)
---data-vcplax---
missing:/data/vcplax               ← compat paths are missing!
missing:/data/libvc.so
missing:/data/libvc++.so

logcat avc: denied { find } ... privsam_service ... permissive=0
       (Java cannot reach servicemanager.find)
```

## Known bugs (priority ordered)

### 1. `BackendHealth.hookMapped` false-positive on `(deleted)` mappings — CRITICAL

`grep -qi libvc /proc/$CAM_PID/maps` matches `/data/libvc.so (deleted)` and returns `hook=yes`. Result: `bootstrap()` skips redeploy + re-injection forever, even when the injected library is from a previous install and `/data/libvc.so` is gone.

**Fix sketch in `BackendHealth.java`:**

```java
"if grep -qi libvc /proc/$CAM_PID/maps && ! grep -E 'libvc(\\\\.so|\\\\+\\\\+\\\\.so) \\\\(deleted\\\\)' /proc/$CAM_PID/maps >/dev/null; then HOOK=yes; fi"
```

### 2. Hook is alive but ring buffer is empty → black screen — CRITICAL

The injected `libvc.so` is overwriting YUV planes in `cameraserver` with zero/uninitialised buffer because `vcplax` is not delivering decoded frames. Possible reasons:

- TX14 path argument is wrong when sent via root-shell `service call ... s16 <path>`: writeStrongBinder/String16 encoding via `service call` may not match the parcel that vcplax's `onTransact` expects.
- TX11 expects a real video container; passing a JPG path may make the decoder produce zero frames after the first decode round.
- `setRange(0, -1)` may not enable loop (original recovered semantics unknown).
- `vcplax.log` is one line; no decoder progress lines. Daemon may need additional TX (TX13 int-array, TX18, etc.) before TX11 plays.

### 3. SELinux flipped to Enforcing again — HIGH

```
+ echo ---selinux-before---
+ Enforcing                          ← already Enforcing on next launch
+ apply_icecam_rule ...
+ selinux skip: allow ...             ← all magiskpolicy/supolicy/ksud fail
```

`setenforce 0` is run inside the script but evidently bounces. None of the three policy tools exist or use the syntax we send. Result: Java `ServiceManager.getService("privsam_service")` is hard-blocked (`permissive=0`), Java path falls back to root-shell.

Possible fixes:

- Detect KernelSU version and use `/data/adb/ksud sepolicy patch '<rule>'` (KSU ≥ 1.0).
- Or write rules to `/data/adb/ksu/profile/template_ext.te` and reload.
- Or stop trying to allow `untrusted_app` and instead register the service under a name that already has `find` in vendor policy (e.g., extend daemon to expose multiple `addService` aliases on names already accessible).

### 4. Root-shell binder transport parameter encoding — HIGH

`RootBinderShell.transactInt` builds `service call <name> <code> s16 <descriptor> [type value]...` and parses the hex reply with regex. Two issues:

- `service call` writes the descriptor as the first `s16` arg, but real binder transactions write the **interface token** via `writeInterfaceToken`, which in raw parcel format includes additional header bytes (strict mode policy, work source). The daemon may reject or misinterpret the parcel.
- The parse uses `0x[0-9a-f]+:\s+([0-9a-f]{8})` and falls back to `parcel(([0-9a-f]{8})`. TX14=4 / TX11=1 look like success codes but could be misparsed status from a partial parcel.

Worth: capturing `service call` raw output and comparing to a direct `BBinder::transact` from a small native helper that runs inside `cameraserver` context (using `am instrument` or a small root JNI tool).

### 5. `setenforce 0` window vs Java `getService` — MEDIUM

`setenforce 0` only stays until next selinux load or boot complete. We should:

- Detect Enforcing at every health probe and re-`setenforce 0` from the watchdog.
- Or switch to a model where the daemon proxies all camera control via a local Unix domain socket under `/data/local/tmp/icecam/` with `system_data_file` context; the app would talk to vcplax via a socket file descriptor passed through `pm grant`/MediaProjection token instead of binder.

### 6. Workflow / artifact naming — DONE

`8356f5e` already fixes this. Artifacts now appear as `IceCam-0.28-v28-stable-replacement-<sha>-apk` and `…-source` on the run page.

## Critical next steps (recommended order)

1. **Patch `BackendHealth.hookMapped`** to ignore `(deleted)` lines. Then a clean force-bootstrap on each new install will actually redeploy `/data/libvc.so` and ptrace inject.
2. **Capture vcplax stderr/stdout in `/data/camera/vcplax.err`** at higher verbosity — pass `ICECAM_DEBUG=1` env var in launch script; investigate whether vcplax even loaded the file. Currently vcplax.err only shows the wrapper echo.
3. **Send raw `am broadcast` or a small native helper** (root JNI) to exercise TX14 + TX11 with a properly-encoded Parcel that matches what the original APK Java sent, then compare with `service call` output.
4. **Try writing a video MP4 instead of JPG** to staged path to rule out "single image → no loop frames".
5. **Force re-inject after fresh deploy:** kill `cameraserver` once (`killall cameraserver`), it respawns clean and `vcplax` re-injects new libvc.so.
6. **KernelSU SELinux policy:** use `ksud sepolicy patch` (without `--live`) and persist rules into `/data/adb/ksu/.system.te` for next boot. Or extend daemon to bind a `system_app` service name with vendor allow-rule.

## Where to look in source for each fix

| Bug | File |
|---|---|
| `(deleted)` false positive | `app/src/main/java/dev/icecam/app/BackendHealth.java` |
| Ring buffer empty | native side (`libvc.so` ↔ `vcplax`), instrument vcplax.err via launch wrapper in `RootBootstrap.launchDaemonScript()` |
| SELinux Enforcing | `app/src/main/java/dev/icecam/app/SelinuxPolicy.java`, `RootBootstrap` (apply rules early + at every poke from watchdog) |
| Binder param encoding | `app/src/main/java/dev/icecam/app/RootBinderShell.java`, `VliveBinderClient.transactDirect()` |
| Re-inject after deploy | `RootBootstrap.verifyInjectionScript()` — add a path to trigger a controlled `cameraserver` restart |

## Artifacts (current)

- `artifacts/IceCam-v28-stable-replacement-debug.apk`
- `artifacts/IceCam-v28-reconstructed-source.zip`
- Latest workflow APK download (recommended):
  https://github.com/deathberg/Icecam/actions/runs/28273925362
  Artifacts: `IceCam-0.28-v28-stable-replacement-8356f5e-apk`, `…-source`

## Diagnostic export semantics

Every "Export" button in the app dumps:

```
===== ICECAM DIAGNOSTIC SNAPSHOT =====
--- prefs ---            ← SharedPreferences
--- files ---            ← internal + external files dirs
--- trace ---            ← SmartDiagnostics summary
health-probe + watchdog
--- binder-java ---      ← VliveBinderClient.diagnostics()
--- root-native ---      ← su output: getenforce, service check, /proc/cameraserver/maps,
                            /data/camera listing, vcplax.log/err, recent logcat
--- runtime-log-ring ---
```

Diagnostic builder lives in `DiagnosticDumper.build(Context, AppLogger, VliveBinderClient)`.
