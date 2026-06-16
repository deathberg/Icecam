cmake_minimum_required(VERSION 3.10)
project(icecam_hook)

set(SHADOWHOOK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/shadowhook)

# Определяем архитектуру по ABI
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(SHADOWHOOK_ARCH "arm64")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(SHADOWHOOK_ARCH "arm")
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

# Пути для заголовков (все нужные)
include_directories(
    ${SHADOWHOOK_DIR}
    ${SHADOWHOOK_DIR}/include
    ${SHADOWHOOK_DIR}/common
    ${SHADOWHOOK_DIR}/arch
    ${SHADOWHOOK_DIR}/third_party
    ${SHADOWHOOK_DIR}/third_party/bsd
    ${SHADOWHOOK_DIR}/third_party/lss
    ${SHADOWHOOK_DIR}/third_party/xdl
)

# Собираем все .c и .S файлы
file(GLOB_RECURSE ALL_SOURCES
    ${SHADOWHOOK_DIR}/*.c
    ${SHADOWHOOK_DIR}/*.S
)

# Исключаем ненужную архитектурную папку
if(SHADOWHOOK_ARCH STREQUAL "arm64")
    list(FILTER ALL_SOURCES EXCLUDE REGEX "${SHADOWHOOK_DIR}/arch/arm/")
else()
    list(FILTER ALL_SOURCES EXCLUDE REGEX "${SHADOWHOOK_DIR}/arch/arm64/")
endif()

# Статическая библиотека ShadowHook
add_library(shadowhook STATIC ${ALL_SOURCES})

# Твоя основная библиотека
add_library(icecam_hook SHARED hook.cpp)

# Линковка
target_link_libraries(icecam_hook shadowhook)

# Отладочные флаги
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -g -O0")