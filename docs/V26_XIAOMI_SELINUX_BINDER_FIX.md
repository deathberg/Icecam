# IceCam v26 Xiaomi SELinux / Binder fix

## Root cause on Android 13 (SDK 33, KernelSU)

1. `vcplax` runs as root (`u:r:ksu:s0`) and registers `privsam_service` in servicemanager.
2. The IceCam app runs as `untrusted_app` and SELinux denies `service_manager find` for `default_android_service`.
3. Java `ServiceManager.getService("privsam_service")` therefore returns null even when `service check privsam_service` succeeds from root shell.
4. Direct writes to `/data/vcplax` and `/data/libvc.so` may fail on newer ROMs; injection still expects those legacy paths.

## Fixes in v26

### SELinux live policy bootstrap

`SelinuxPolicy.applyLivePoliciesScript()` is executed during root bootstrap and before backend apply retries. It tries, in order:

- `magiskpolicy --live`
- `supolicy --live`
- `ksud supolicy --live`

Key rules:

- `allow untrusted_app default_android_service service_manager find`
- `allow untrusted_app default_android_service binder { call transfer use }`
- `allow ksu default_android_service service_manager add`

### Deployment path hardening

- Primary daemon path: `/data/camera/vcplax`
- Native hook libs deployed under `/data/camera` first
- Legacy `/data/libvc.so`, `/data/libvc++.so`, `/data/vcplax` use copy-or-bind-mount fallback
- Diagnostics no longer treat missing `/data/vcplax` as fatal when `/data/camera/vcplax` exists

### Root-shell Binder fallback

`RootBinderShell` proxies TX11/TX12/TX14/TX22/TX24 via `service call` from `su` when Java Binder lookup is blocked.

`VliveBinderClient` automatically falls back after direct Binder failure.

## Expected logs after fix

```
selinux ok magisk: allow untrusted_app default_android_service service_manager find
deployed_copy /data/camera/vcplax
[binder] connected raw service=privsam_service
```

Or, if SELinux policy tools are unavailable but the daemon is running:

```
[binder] connected root-shell service=privsam_service
[binder] TX11 -> 0 via root-shell privsam_service
```

## Native hook note

`libvc.so` is a prebuilt binary using ShadowHook against `cameraserver`. It is injected by `vcplax` via `ptrace` + `dlopen`. v26 does not modify native hook code; it fixes the control plane so TX11/TX14 reach the daemon and ensures hook libraries are present at the legacy `/data/` paths used during injection.
