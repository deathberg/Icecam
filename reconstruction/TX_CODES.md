# Binder transaction codes (VERIFIED from icecamtest.apk d1/f.java)

Reverse engineered byte-faithfully. Original method letters in `d1/f.java`:

| TX | d1/f method | Parcel after token | Returns | Meaning |
|----|-------------|--------------------|---------|---------|
| 11 | `j(s,z,z)`  | string, int, int   | int | play(path, autoRotate, loop) |
| 12 | `k()`       | —                  | int | stop when active |
| 13 | `c()`       | —                  | int[] | status array (polled every 1s) |
| 14 | `i(s,i)`    | int(mode), string(path) | int (4=ok) | **set source + start** (primary apply) |
| 15 | `f()`       | —                  | int (5=playing) | status |
| 16 | `b(z)`      | int                | int | unused in UI |
| 17 | `a(z)`      | int                | int | **loop on/off** |
| 18 | `g(i)`      | int(degrees)       | int | **rotation** (live) |
| 19 | `d(z)`      | int                | int | **mirror H** (live) |
| 22 | `e(l,l)`    | long, long         | int | video time range (begin,end) |
| 24 | `h(i,f,f,f,f,i)` | int,float,float,float,float,int | int (14=ok) | **AutoColor** (NOT geometry) |
| 25 | `l()`       | —                  | int | stop |

Helper wrappers in `U/t.java`: `a0`→TX14, `T`→TX15, `R`→TX22, `d0`→TX18, `c0`→TX19, `e`→TX17, `Y`→TX24.

App background timer (`D0/i` case 9) calls TX13 `c()` every 1000 ms to refresh status and
re-acquires the binder via `t.E()` if the cached reference died. It never re-deploys.

---

# Binder transaction codes (recovered)

Interface descriptor (always written first in Parcel):

```text
com.xiaomi.vlive.IMyBinderService
```

## Java-side mapping (IceCam VliveBinderClient)

| TX | Method | Parcel payload |
|----|--------|----------------|
| 11 | playSource | token, path:String, int(0), loop:int |
| 12 | statusCode | token |
| 13 | int array | token, ... |
| 14 | setModeString | token, mode:int, value:String |
| 15 | getInt15 | token |
| 16-19 | legacy/debug | token, optional int |
| 22 | setRange | token, start:long, end:long |
| 24 | setTransform | token, mode:int, panX:f, panY:f, zoomX:f, zoomY:f, flags:int |
| 25 | stop/reset | token, optional int |

## Native-side notes (reconstructed)

Original spyware builds also used RTMP URL TX and memory heap setup TX.
IceCam camera-replacement mode primarily needs **TX14 (path) + TX11 (play)**.

TX24 on tested builds behaves as **color correction / debug**, not geometric pan/zoom.
