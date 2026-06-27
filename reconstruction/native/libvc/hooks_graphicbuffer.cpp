// RECONSTRUCTED — GraphicBuffer hook implementation model (libvc.so)

#include "hooks_graphicbuffer.h"
#include <shadowhook.h>
#include <cstring>

namespace icecam::recon {

static constexpr const char* kLibUi = "libui.so";
static constexpr const char* kSymLockYCbCr =
    "_ZN7android13GraphicBuffer9lockYCbCrEjP13android_ycbcr";
static constexpr const char* kSymLock =
    "_ZN7android13GraphicBuffer4lockEjPPvPiS3_";
static constexpr const char* kSymUnlock =
    "_ZN7android13GraphicBuffer6unlockEv";

GraphicBufferLockFn orig_lock = nullptr;
GraphicBufferLockYCbCrFn orig_lockYCbCr = nullptr;
GraphicBufferUnlockFn orig_unlock = nullptr;

static void push_yuv_to_ring_buffer(const android_ycbcr* ycbcr, uint32_t w, uint32_t h) {
    // Copies Y/Cb/Cr into shared ashmem ring; consumed by vcplax decode/replace pipeline.
    (void)ycbcr; (void)w; (void)h;
}

int hooked_lockYCbCr(android::GraphicBuffer* self, uint32_t usage, android_ycbcr* ycbcr) {
    int res = orig_lockYCbCr ? orig_lockYCbCr(self, usage, ycbcr) : -1;
    if (res == 0 && ycbcr && ycbcr->y) {
        push_yuv_to_ring_buffer(ycbcr, self->getWidth(), self->getHeight());
        // If replacement frame available, memcpy synthetic YUV over ycbcr planes here.
    }
    return res;
}

int hooked_lock(android::GraphicBuffer* self, uint32_t usage, void** vaddr, int* bpp, int* stride) {
    int res = orig_lock ? orig_lock(self, usage, vaddr, bpp, stride) : -1;
    // Optional RGBA replacement path
    return res;
}

void install_graphicbuffer_hooks() {
    shadowhook_init(0 /*UNIQUE*/, false);
    shadowhook_hook_sym_name(kLibUi, kSymLockYCbCr,
        reinterpret_cast<void*>(hooked_lockYCbCr),
        reinterpret_cast<void**>(&orig_lockYCbCr));
    shadowhook_hook_sym_name(kLibUi, kSymLock,
        reinterpret_cast<void*>(hooked_lock),
        reinterpret_cast<void**>(&orig_lock));
    shadowhook_hook_sym_name(kLibUi, kSymUnlock,
        reinterpret_cast<void*>(+[](android::GraphicBuffer* gb) {
            return orig_unlock ? orig_unlock(gb) : 0;
        }),
        reinterpret_cast<void**>(&orig_unlock));
}

}  // namespace icecam::recon

// JNI_OnLoad in prebuilt libvc.so likely calls install_graphicbuffer_hooks() after injection.
