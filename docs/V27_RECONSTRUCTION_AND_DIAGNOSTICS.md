# IceCam v27 reconstruction + smart diagnostics

## Root cause from user log (v26 on Xiaomi SDK 33)

1. **SELinux rules never applied** — shell `for rule in $RULES` split multiline string by whitespace, so `magiskpolicy --live binder` was executed instead of full rules.
2. **False binder connect** — `RootBinderShell.isServiceAvailable()` matched substring `found` inside `not found`.
3. **vcplax died immediately** — launched from `/data/camera/vcplax`, process gone, `privsam_service` not registered, empty logs.

## v27 fixes

- `SelinuxPolicy`: one `apply_icecam_rule` call per full rule string
- `RootBinderShell`: reject `not found`, require `service <name>: found`
- `RootBootstrap`: primary exec `/data/vcplax` (original APK path), launch wrapper script with logging, daemon/service wait loop, cameraserver maps check
- `SmartDiagnostics`: staged trace (`EXTRACT`, `DAEMON_ALIVE`, `BINDER_SERVICE`, `INJECT_HOOK`, `TX_APPLY`)
- `reconstruction/`: documented C++/Java model of native backend

## Expected trace after successful bootstrap

```text
OK EXTRACT: abi=arm64-v8a ...
OK DAEMON_ALIVE: vcplax running
OK BINDER_SERVICE: privsam_service registered
OK INJECT_HOOK: libvc visible in cameraserver maps
OK TX_APPLY: TX14=0 TX11=0 path=...
```

## Reconstruction archive

See `reconstruction/` and release artifact `IceCam-v27-reconstructed-source.zip`.
