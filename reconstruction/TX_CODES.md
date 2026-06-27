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
