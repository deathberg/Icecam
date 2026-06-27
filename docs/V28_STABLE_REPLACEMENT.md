# IceCam v28 — stable replacement

## What changed since v27

User saw the substituted image **flash briefly** and then disappear. v27 confirmed the full pipeline works (daemon, binder, hook injection, TX_APPLY all OK), but the replacement was unstable.

### Root causes found in logs

1. **Bootstrap was destructive on every action.** Each tap ran `rm -rf /data/camera` and overwrote `/data/libvc.so` while it was still mapped in `cameraserver` — producing `(deleted)` mappings and breaking the active hook frame stream.
2. **Media path was in `/storage/emulated/0/Android/data/.../files/`.** While vcplax (root) can read it, the path is fragile across SELinux contexts and replacement sessions.
3. **No keep-alive.** TX14 + TX11 were issued once. If the daemon finished decoding the still image, replacement stopped. Original recovered sequence included TX22 range reset and looping behaviour.
4. **State drift.** `ReplacementActive=false` while `IceCamState=REPLACEMENT_ACTIVE` due to state being cleared on stale exceptions.

## Fixes

| Module | Change |
|--------|--------|
| `BackendHealth` | Fast root probe of daemon / service / `/proc/<cameraserver>/maps` / staged media. |
| `RootBootstrap.bootstrap()` | Idempotent: skip kill/redeploy when `BackendHealth.fullyReady()` returns true. New `bootstrapForced()` for explicit reset. |
| `MediaStager` | Copies user media to `/data/camera/source.<ext>` with `system_data_file` context, returns root-readable path. |
| `BackendApplyQueue` | Uses staged path for TX14/TX11. Issues TX22 range reset before TX14. Wires `ReplacementWatchdog`. |
| `ReplacementWatchdog` | Background thread that every 4.5s probes health and re-issues TX22 + TX14 + TX11 while replacement is active. |
| `SmartDiagnostics` / `DiagnosticDumper` | Include `BackendHealth` and watchdog stats in exports. |

## Expected behaviour

- First tap: full bootstrap, frame replacement starts.
- Subsequent taps / slot changes: bootstrap is skipped (precheck OK), only staged media path is re-applied. No `(deleted)` mappings.
- Image stays visible: watchdog re-asserts TX22+TX14+TX11 every few seconds.
- If `cameraserver` restarts, watchdog detects `hook=no`, runs full bootstrap to re-inject.

## How to verify

In the next exported `IceCam_shared_text`:

```
--- trace ---
OK DAEMON_ALIVE: skip-redeploy (precheck OK)
OK BINDER_SERVICE: skip-redeploy (precheck OK)
OK INJECT_HOOK: skip-redeploy (precheck OK)
OK TX_APPLY: TX22=... TX14=... TX11=... path=/data/camera/source.png

health-probe: daemon=true service=true hook=true staged=true
watchdog: running=true path=/data/camera/source.png pokeCount>=1
```

And in `--- root-native ---` the mappings should remain `/data/libvc.so` (no `(deleted)` suffix) across repeated taps.
