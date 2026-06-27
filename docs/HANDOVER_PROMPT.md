# Prompt to continue IceCam in a fresh chat

Copy everything below the `---` line as the first message of the new chat. Attach the latest `IceCam_shared_text*.txt` log alongside it.

---

You are continuing the **IceCam** project (camera-frame replacement on Xiaomi Android 13 / SDK 33 / KernelSU root). Repository: `https://github.com/deathberg/Icecam`. Active branch and PR: `cursor/xiaomi-selinux-binder-fix-e216` → PR #2. Read `docs/HANDOVER_v28.md` first — it contains full architecture, version history, every file's role, and root-cause analysis of the current black-screen bug.

## What works (verified in log)

- `vcplax` root daemon starts, registers `privsam_service` in servicemanager.
- `libvc.so` injects into `cameraserver` (ShadowHook hooks on `GraphicBuffer::lock`, `lockYCbCr`).
- `VliveBinderClient` reaches the daemon (root-shell fallback when SELinux Enforcing blocks Java).
- `TX22 → TX14 → TX11` apply sequence returns success values.
- Media is staged to `/data/camera/source.jpg` with `system_data_file` SELinux context.
- `ReplacementWatchdog` keeps re-poking the daemon every 4.5 s.
- GitHub Actions builds `IceCam-<versionName>-<sha>-apk` artifact on every push.

## What's broken (current symptom: black camera)

1. **`BackendHealth.hookMapped` matches `(deleted)` mappings.** `/proc/cameraserver/maps` shows `/data/libvc.so (deleted)` from a previous install but precheck still returns `hook=yes`, so `RootBootstrap.bootstrap()` skips redeploy and re-injection forever. Old libvc.so is still in memory but `/data/libvc.so` no longer exists on disk.
2. **Frame replacement is active but the ring buffer is empty → black frames.** The hook overwrites YUV planes with zeroed data because `vcplax` is not feeding decoded frames into the shared buffer. Suspected: TX14 path arg encoding via `service call ... s16 <path>` does not match the parcel layout `vcplax::onTransact` expects, OR JPG decode path needs different TX sequence than MP4.
3. **SELinux flipped back to Enforcing.** `setenforce 0` is issued in the bootstrap script but next launch shows `Enforcing` again; all `magiskpolicy/supolicy/ksud` live-rule attempts return code 1 (tools missing or wrong syntax for this KSU build). Java `ServiceManager.getService("privsam_service")` is denied with `permissive=0`, forcing root-shell fallback.
4. **vcplax.err only shows the launch wrapper echo.** No decoder progress lines from vcplax itself — need higher verbosity / `ICECAM_DEBUG=1` env or strace.

## First three concrete tasks

1. **Patch `BackendHealth.probe()` (app/src/main/java/dev/icecam/app/BackendHealth.java)** to set `HOOK=yes` only if there is at least one libvc mapping line that does *not* contain `(deleted)`. After this fix, force a single `bootstrapForced()` from `MainActivity` first launch to wipe the stale mapping.
2. **Instrument `RootBootstrap.launchDaemonScript()`** to:
   - export `ICECAM_DEBUG=1` (and `LIBVC_LOG=verbose`) before exec,
   - tee `/data/vcplax` stdout/stderr,
   - dump `/proc/<vcplax-pid>/maps` and `/proc/<cameraserver>/maps` (non-deleted only) into the next diagnostic snapshot.
3. **Test with a tiny MP4 instead of JPG.** Stage a 1-sec H.264 sample to `/data/camera/source.mp4` and run TX14/TX11 against it; this isolates whether the dead frames are due to JPG single-shot decode vs. parcel encoding.

If those three reveal the parcel-encoding theory, write a small native binary (under `reconstruction/native/probe/`) that does a real `BBinder::transact` against `privsam_service` with a hand-crafted parcel matching the recovered descriptor (`com.xiaomi.vlive.IMyBinderService`) — compare what arrives at vcplax vs. what `service call` sends.

## Build & artifacts

- CI workflow `.github/workflows/android.yml` already builds debug APK and uploads `IceCam-<versionName>-<sha>-apk` + `…-source` on every push.
- Local build: `gradle --no-daemon assembleDebug` (Gradle 8.11.1, JDK 17, Android SDK 35).
- Version number is read from `app/build.gradle` `versionName`; bump it when changing semantics.

## Working agreement

- Commit each logical change separately on the same branch `cursor/xiaomi-selinux-binder-fix-e216`.
- Update PR #2 description (do not open a new PR) after each push.
- Never delete or rebuild the prebuilt `app/src/main/jniLibs/*/*.so` — they are the original APK binaries. The C++ in `reconstruction/native/` is a documented model only.
- All diagnostic information must remain inside the in-app "Export diagnostic snapshot" output (`DiagnosticDumper.java`) so the user can paste a single `IceCam_shared_text*.txt` and you can debug from it.

Read `docs/HANDOVER_v28.md` now and propose the first patch.
