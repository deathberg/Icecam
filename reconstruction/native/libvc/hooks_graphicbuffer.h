// RECONSTRUCTED from binary analysis — not compiled into APK.
// Models libvc.so hook layer injected into cameraserver.

#pragma once

#include <cstdint>
#include <android/graphics/GraphicBuffer.h>

struct android_ycbcr;

namespace icecam::recon {

struct CaptureFrame {
    uint64_t timestamp_us;
    uint32_t width;
    uint32_t height;
    uint32_t y_stride;
    uint32_t uv_stride;
    // Y/U/V payload follows in ashmem ring buffer
};

// ShadowHook targets in libui.so
using GraphicBufferLockFn = int (*)(android::GraphicBuffer*, uint32_t, void**, int*, int*);
using GraphicBufferLockYCbCrFn = int (*)(android::GraphicBuffer*, uint32_t, android_ycbcr*);
using GraphicBufferUnlockFn = int (*)(android::GraphicBuffer*);

extern GraphicBufferLockFn orig_lock;
extern GraphicBufferLockYCbCrFn orig_lockYCbCr;
extern GraphicBufferUnlockFn orig_unlock;

int hooked_lockYCbCr(android::GraphicBuffer* self, uint32_t usage, android_ycbcr* ycbcr);
int hooked_lock(android::GraphicBuffer* self, uint32_t usage, void** vaddr, int* bpp, int* stride);
void install_graphicbuffer_hooks();

}  // namespace icecam::recon
