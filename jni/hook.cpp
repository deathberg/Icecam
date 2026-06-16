#include <android/log.h>
#define LOG_TAG "IceHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" void icecam_init() {
    LOGI("Ice hook initialized!");
}