# IceCam v31 — faithful, stable control model

## Why v30 was unstable (from the v30 log)

```
FAIL INJECT_HOOK: libvc not mapped in cameraserver
health-probe: daemon=true service=true hook=false staged=true
watchdog: running=true probeCount=7 recoveryCount=7
baked/ : 12 JPEGs written in 11 seconds
IceCamState=APPLYING_MEDIA   (stuck mid-apply)
```

Two compounding regressions:

1. **`(deleted)` hook detection (v29).** A working injected `libvc.so` is shown in
   `/proc/<cameraserver>/maps` as `/data/libvc.so (deleted)` because the on-disk inode is
   replaced after injection. v29 started treating `(deleted)` as "not injected", so health
   reported `hook=false` even though the hook was live.

2. **Health-gated bootstrap + watchdog.** Because `hook=false`, the 10 s watchdog ran a
   full destructive bootstrap every cycle (`recoveryCount=7`), and every control tap went
   through `BackendApplyQueue` which bootstrapped whenever `!fullyReady()`. Each bootstrap
   kills vcplax, deletes/redeploys libs and relaunches — restarting the stream. With the
   per-tap bake also firing, the daemon was redeployed continuously, so the stream reset on
   every button and injection sometimes failed outright.

## The original model (recovered from icecamtest.apk)

- `App.onCreate`: deploy native once, launch `/data/vcplax <ServerName>&` once.
- `D0/i` timer: every 1 s call **TX13** to refresh status, re-acquire binder via `t.E()` if
  the cached ref died. **No redeploy, no re-apply, ever.**
- Apply media: a single **TX14** `i(path,1)` (`t.a0`). Nothing else.
- Controls: **TX18** rotation, **TX19** mirror, **TX17** loop — single live calls.
- The native daemon loops the source on its own; no keep-alive traffic.

## v31 changes

| Component | Fix |
|---|---|
| `BackendHealth.hookMapped` | any `libvc` mapping (incl. `(deleted)`) counts as injected. |
| `ReplacementWatchdog` | rewritten as a **passive 2 s status poller** (TX15). Never bootstraps, re-bakes or re-applies. |
| `BackendApplyQueue` | bootstraps **only** when `service check` shows the daemon is gone — never on hook maps. Single TX14 + TX15. |
| `SideEffectRunner` | **Image:** bake full transform → one debounced (280 ms) TX14. **Video:** rotation→TX18, mirror→TX19, loop→TX17 live; pan/zoom/crop skipped. Never bootstraps. |
| `MainActivity` bootstrap on launch | idempotent (skips when daemon+service+hook healthy). |

## Control behaviour now

- **Image source** (jpg/png): every geometry op (pan/zoom/rotate/mirror/crop/fit) bakes a
  high-quality JPEG and swaps it with one TX14 after a 280 ms quiet period. Realtime preview
  is instant; the stream updates once when you stop adjusting. No daemon restart.
- **Video source** (mp4): rotation/mirror/loop are instant live TX with no reload. Pan/zoom
  are not supported by the native daemon (documented, preview still shows them).
- Status (`REPLACEMENT_ACTIVE`) no longer flips on rotation/mirror; it only shows
  `APPLYING_MEDIA` briefly during an actual image re-bake.

## Reconstructed original source (in `reconstruction/`)

- `reconstruction/java/d1/MyBinderProxy.java` — byte-faithful binder proxy (all 12 TX).
- `reconstruction/java/U/ServiceHelper.java` — `t.E()` SELinux-toggle binder acquire + `t.q()` su pipe.
- `reconstruction/java/com/xiaomi/vlive/App.java` — onCreate deploy + 1 s status poll.
- `reconstruction/TX_CODES.md` — verified transaction table.
- `reconstruction/BINARY_ANALYSIS.md` — libvc/vcplax/shadowhook analysis.

## Expected log (image, after Start then several control taps)

```
OK TX_APPLY: TX14=4 (4=ok) TX15=5 (5=playing)
[runtime] image baked -> .../baked/icecam_...jpg FIT pan=(-0.08,0.04) zoom=(1.25,1.25) ...
[poll] status poller ... lastStatus=5 (5=playing)
```

No `recoveryCount` growth, no per-tap bootstrap, `hook=true`.
