#include <jni.h>
#include <android/log.h>
#include "shadowhook.h"

#define LOG_TAG "ICECAM_CORE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Указатель для хранения оригинального адреса функции
static void *orig_dequeueOutputBuffer = NULL;

// Наша функция-перехватчик
static ssize_t my_dequeueOutputBuffer(void* encoder, void* info, long timeoutUs) {
    // Вызываем оригинал, чтобы приложение не упало
    ssize_t result = ((ssize_t (*)(void*, void*, long))orig_dequeueOutputBuffer)(encoder, info, timeoutUs);
    
    // Если результат >= 0, значит, в буфере есть данные (кадр)
    if (result >= 0) {
        LOGI("Кадр готов к обработке!");
    }
    
    return result;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    LOGI("IceCam Core v2.0 инициализирована");

    // Устанавливаем хук на libmediandk.so (там живет AMediaCodec)
    orig_dequeueOutputBuffer = shadowhook_hook_sym_name(
        "libmediandk.so", 
        "AMediaCodec_dequeueOutputBuffer", 
        (void *)my_dequeueOutputBuffer, 
        NULL
    );
    
    return JNI_VERSION_1_6;
}
