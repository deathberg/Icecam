# IceCam v30 — correct, smooth media controls

v29 made the replacement appear. v30 fixes the control plane: buttons now map to the
real native capabilities, taps are debounced (no decoder restart per tap), and image
geometry is baked at full quality so FPS stays stable.

## Root cause of the v29 control problems

Every pan/zoom/rotate/mirror tap went through `SideEffectRunner.sendTransformBestEffort()`
which called **TX24** (`h(int,float,float,float,float,int)`). Reverse engineering of
`icecamtest.apk` proved TX24 is **AutoColor** (照射强度 intensity / 照射直径 diameter /
X,Y coordinates for the screen-capture three-color feature) — **not** geometry. So:

- Pan/zoom buttons sent color commands → no movement, sometimes color shift.
- COMMIT re-ran the full apply → decoder restart → lag and stream interruption.
- Rotation/mirror also went through TX24 instead of the real TX18/TX19.

## Real native control surface (binary-verified, `d1/f.java`)

| TX | Method | Meaning | Live? |
|----|--------|---------|-------|
| 14 | `i(path, mode)` | set source + start | reload |
| 17 | `a(bool)` | loop on/off | yes |
| 18 | `g(int)` | rotation degrees (0/90/180/270) | yes |
| 19 | `d(bool)` | horizontal mirror | yes |
| 22 | `e(long,long)` | video time range | yes |
| 24 | `h(...)` | AutoColor (NOT geometry) | yes |
| 25 | `l()` | stop | yes |

There is **no pan / zoom / crop** in the native protocol.

## v30 routing

`SideEffectRunner` now decides per source type:

### Image sources (jpg/png/webp…)
All geometry — pan, zoom, crop, fit/fill, rotation, mirror — is **baked into a JPEG**
by `MediaTransformer.bakeImage()` at high quality (≤2560 px, q=88, bilinear+dither),
then applied with a **single TX14**. Because the native side just loops one static
transformed frame, CPU/GPU cost is near zero and FPS is rock-stable. Live native
rotation/mirror are reset to 0/off first so the baked frame is not double-transformed.

### Video sources (mp4/mkv…)
- Rotation → **TX18** `g(angle)` (live, no restart)
- Mirror H → **TX19** `d(bool)` (live)
- Loop → **TX17** `a(bool)` (live)
- Pan/zoom/crop are not representable natively for video → skipped (the on-screen
  preview still shows them, and the log notes `[pan/zoom/crop not supported for video]`).

### Debounce
Every control tap saves the transform and schedules a single apply **320 ms** after the
last tap. A burst of taps collapses into one native update. The on-screen realtime
preview (ImageView `Matrix` in `RealtimePreviewView`) updates instantly, so the UI feels
responsive while the stream updates once when you stop adjusting.

### Change detection
- Image: re-bake only when `source + transform signature` changed.
- Video: re-send TX18/TX19 only when rotation/mirror changed.

This removes the per-tap TX storm entirely.

## Button → effect map (both Main UI and floating panel)

| Button | op | Effect |
|--------|----|--------|
| ZOOM +/- | `zoom+` / `zoom-` | bake zoom (image) |
| ↑ ↓ ← → | `up`/`down`/`left`/`right` | bake pan (image) |
| ROT +90 / -90 | `rot+90` / `rot-90` | TX18 (video) or baked rotation (image) |
| MIRROR X | `mirror-x` | TX19 (video) or baked mirror (image) |
| MIRROR Y | `mirror-y` | baked vertical mirror (image) |
| FIT/FILL | `fit-fill` | bake fit/fill (image) |
| CROP | `crop` | bake crop preset (image) |
| CENTER | `center` | reset pan |
| RESET VIEW | `reset` | reset all geometry |
| PLAY / COMMIT | commit | force one apply now |
| LOOP | SET_LOOP | TX17 |

## Expected log

```
[runtime] image geometry applied bake=/storage/.../baked/icecam_...jpg FIT pan=(-0.08,0.04) zoom=(1.25,1.25) rot=90 ...
[applyq]  apply done #N TX14=4 TX15=5 active=true
```

For video:

```
[runtime] video geometry TX18(rot=90)=0 TX19(mir=true)=0 TX17(loop)=0 [pan/zoom/crop not supported for video]
```

No more `TX24 transform` lines on control taps.
