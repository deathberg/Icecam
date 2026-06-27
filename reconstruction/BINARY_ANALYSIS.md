# Binary analysis summary (arm64-v8a)

## libshadowhook.so (~74 KB)

- ByteDance ShadowHook v1.0.10
- Exports: `shadowhook_init`, `shadowhook_hook_sym_name`, `shadowhook_unhook`, ...
- Used by libvc.so for inline hooks in cameraserver process

## libvc.so (~1.3 MB)

### Imported hook targets (demangled)

| Symbol | Library | Role |
|--------|---------|------|
| `_ZN7android13GraphicBuffer4lockEjPPvPiS3_` | libui.so | RGBA lock |
| `_ZN7android13GraphicBuffer9lockYCbCrEjP13android_ycbcr` | libui.so | YUV lock (primary capture path) |
| `_ZN7android13GraphicBuffer6unlockEv` | libui.so | unlock |
| `_ZN7android13GraphicBuffer4fromEP19ANativeWindowBuffer` | libui.so | slot -> GraphicBuffer* |

### Media / IPC

- NDK MediaCodec (`AMediaCodec_*`)
- libjpeg-turbo NEON (`jsimd_*`)
- Binder (`BBinder`, `Parcel`, `defaultServiceManager`)

### Hook strategy (confirmed by reverse reports)

Original analysis proposed `IGraphicBufferProducer::queueBuffer`, but refined reconstruction
shows **global hooks on `GraphicBuffer::lock` / `lockYCbCr`** are sufficient to intercept
frames when any camera consumer locks the buffer.

## vcplax.so (~12 MB, PIE executable)

- ELF executable launched as `/data/vcplax <ServerName>`
- Embeds FFmpeg + librtmp (strings reference RTMP, FLV, H.264)
- Registers Binder service with token `com.xiaomi.vlive.IMyBinderService`
- Strings: `cameraserver`, `ptrace`, `dlopen`, `libvc.so`, `libvc++.so`
- Depends on: libbinder, libmediandk, libEGL, libGLESv2

### Expected startup sequence

1. Parse argv[1] = service name (`privsam_service`)
2. `defaultServiceManager()->addService(name, binder)`
3. Inject `/data/libvc.so` + `/data/libvc++.so` into `cameraserver` via ptrace/dlopen
4. Serve TX11/TX14/TX24 from Java control plane

## Deployment paths (from original DEX strings)

```text
/data/vcplax
/data/libvc.so
/data/libvc++.so
/data/camera/libshadowhook.so
/data/camera/libvc.so
chattr -i /data/camera
chmod 700 /data/vcplax
killall vcplax
file /system/bin/cameraserver
```

## Android 13 / Xiaomi issues

1. `untrusted_app` cannot `service_manager find` on `default_android_service`
2. vcplax must stay alive; if it exits immediately, no service and no injection
3. False positive `service check` if matching substring `found` inside `not found`
