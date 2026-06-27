# IceCam Reconstruction (v27)

Этот каталог содержит максимально полную реконструкцию исходного проекта `com.xiaomi.vlive` / IceCam
на основе:

- бинарного анализа `libvc.so`, `libshadowhook.so`, `vcplax.so`
- отчётов reverse engineering (chinacam static analysis, source recovery report)
- восстановленного Java control plane в `app/src/main/java/dev/icecam/app/`

## Архитектура (восстановленная)

```text
MainActivity / FloatService
  -> RootBootstrap (su)
      -> extract native from APK
      -> cp /data/libvc.so /data/libvc++.so /data/vcplax
      -> /data/vcplax privsam_service
  -> vcplax (root daemon, FFmpeg + Binder BBinder)
      -> registers privsam_service
      -> ptrace/dlopen inject into cameraserver
  -> libvc.so inside cameraserver
      -> ShadowHook on GraphicBuffer::lock / lockYCbCr
      -> replaces camera frames from media source (TX11/TX14)
  -> VliveBinderClient (Java)
      -> TX11 play source, TX14 set path, TX24 color/transform
```

## Файлы реконструкции

| Путь | Описание |
|------|----------|
| `native/libvc/hooks_graphicbuffer.cpp` | Реконструкция хуков GraphicBuffer |
| `native/libvc/ring_buffer.h` | Ring buffer кадров YUV |
| `native/vcplax/binder_service.cpp` | Реконструкция IMyBinderService / onTransact |
| `native/vcplax/inject_cameraserver.cpp` | Реконструкция ptrace/dlopen инъекции |
| `java/com/xiaomi/vlive/` | Реконструкция Java API оригинала |
| `TX_CODES.md` | Таблица Binder transaction codes |
| `BINARY_ANALYSIS.md` | Сводка по .so |

## Важно

Нативные `.so` в `app/src/main/jniLibs/` — **оригинальные prebuilt бинарники**.
Реконструированный C++ код в `reconstruction/native/` — это документированная модель поведения,
а не побайтовое восстановление. Его цель — продолжать разработку без «пустого места».

## Известные TX коды (восстановлены из IceCam Java)

| Code | Назначение |
|------|------------|
| 11 | playSource(path, mirror, loop) |
| 12 | status |
| 14 | setModeString(mode, value/path) |
| 22 | setRange(start, end) |
| 24 | setTransform (color correction / debug on original builds) |
| 25 | stop/reset |

## Диагностика v27

`SmartDiagnostics` пишет стадии:

`EXTRACT -> SELINUX -> DEPLOY -> DAEMON_START -> DAEMON_ALIVE -> BINDER_SERVICE -> INJECT_HOOK -> TX_APPLY`

Экспорт диагностики в приложении включает trace summary и проверку `/proc/<cameraserver>/maps`.
