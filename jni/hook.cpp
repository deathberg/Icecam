#include <jni.h>
#include <android/log.h>
#include "shadowhook.h"

#define LOG_TAG "ICECAM"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static void* orig_takePicture = nullptr;

static void my_takePicture(void* instance, int msgType) {
    LOGD("ICECAM: ХУК СРАБОТАЛ! Перехвачен takePicture");
    // Вызов оригинала, чтобы не сломать логику камеры
    ((void (*)(void*, int))orig_takePicture)(instance, msgType);
}

extern "C" JNIEXPORT void JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGD("ICECAM: Загрузка модуля...");
    
    // Хукаем takePicture
    shadowhook_hook_sym_name("libcamera_client.so", 
                             "_ZN7android6Camera11takePictureEi", 
                             (void*)my_takePicture, 
                             (void**)&orig_takePicture);
}
