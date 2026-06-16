cmake_minimum_required(VERSION 3.10.2)
project(icecam)

# Находим все исходники внутри shadowhook
file(GLOB_RECURSE SHADOWHOOK_SOURCES "shadowhook/*.c" "shadowhook/*.cpp")

# Создаем библиотеку
add_library(shadowhook STATIC ${SHADOWHOOK_SOURCES})

# ВАЖНО: Указываем пути ко ВСЕМ подпапкам в shadowhook
target_include_directories(shadowhook PUBLIC 
    shadowhook
    shadowhook/src/main/cpp/shadowhook # Проверь этот путь в MT Manager!
)

add_library(icecam_hook SHARED hook.cpp)

target_link_libraries(icecam_hook shadowhook log)
