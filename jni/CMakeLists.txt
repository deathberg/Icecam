cmake_minimum_required(VERSION 3.10)
project(icecam_hook)

set(SHADOWHOOK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/shadowhook)

# Пути для заголовков
include_directories(
    ${SHADOWHOOK_DIR}
    ${SHADOWHOOK_DIR}/include
    ${SHADOWHOOK_DIR}/common
    ${SHADOWHOOK_DIR}/arch
    ${SHADOWHOOK_DIR}/third_party
)

# Автоматически собрать все .c и .S файлы ShadowHook
file(GLOB_RECURSE SHADOWHOOK_SOURCES
    ${SHADOWHOOK_DIR}/*.c
    ${SHADOWHOOK_DIR}/*.S
)

add_library(shadowhook STATIC ${SHADOWHOOK_SOURCES})

add_library(icecam_hook SHARED hook.cpp)

target_link_libraries(icecam_hook shadowhook)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -g -O0")