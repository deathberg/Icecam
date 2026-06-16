#include <jni.h>
#include <android/log.h>
#include "shadowhook.h"

#define LOG_TAG "ICECAM_CORE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static void *orig_func = NULL;

static ssize_t my_hooked_func(void* encoder, void* info, long timeoutUs) {
    LOGI("Hook triggered!");
    // Вызов оригинальной функции через shadowhook
    return ((ssize_t (*)(void*, void*, long))orig_func)(encoder, info, timeoutUs);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    LOGI("Loading IceCam...");
    
    // Хукаем системную функцию камеры
    orig_func = shadowhook_hook_sym_name(
        "libmediandk.so", 
        "AMediaCodec_dequeueOutputBuffer", 
        (void *)my_hooked_func, 
        NULL
    );
    
    return JNI_VERSION_1_6;
}
