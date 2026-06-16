cmake_minimum_required(VERSION 3.4.1)
project(icecam_hook)

# Путь к ShadowHook
set(SHADOWHOOK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/shadowhook)

# ---- 1. Рекурсивно добавляем все поддиректории ShadowHook ----
# Добавляем все папки с исходниками
include_directories(
    ${SHADOWHOOK_DIR}
    ${SHADOWHOOK_DIR}/include
    ${SHADOWHOOK_DIR}/common
    ${SHADOWHOOK_DIR}/arch
    ${SHADOWHOOK_DIR}/third_party
)

# ---- 2. Добавляем библиотеку ShadowHook ----
add_library(shadowhook STATIC
    ${SHADOWHOOK_DIR}/sh_elf.c
    ${SHADOWHOOK_DIR}/sh_enter.c
    ${SHADOWHOOK_DIR}/sh_hub.c
    ${SHADOWHOOK_DIR}/common/sh_log.c
    ${SHADOWHOOK_DIR}/common/sh_util.c
    ${SHADOWHOOK_DIR}/arch/arm/sh_hub_arm.c
    ${SHADOWHOOK_DIR}/arch/arm64/sh_hub_arm64.c
    # Добавь остальные .c файлы, которые есть в shadowhook/
)

# Если есть файлы .S (ассемблер) — добавь их тоже
target_sources(shadowhook PRIVATE
    ${SHADOWHOOK_DIR}/arch/arm/sh_enter_arm.S
    ${SHADOWHOOK_DIR}/arch/arm64/sh_enter_arm64.S
)

# ---- 3. Создаём основную библиотеку ----
add_library(icecam_hook SHARED hook.cpp)

# Подключаем ShadowHook
target_link_libraries(icecam_hook shadowhook)

# Включаем отладочную информацию
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -g -O0")