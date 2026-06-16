cmake_minimum_required(VERSION 3.10.2)
project(icecam)

# 1. Автоматически находим все файлы .c в папке shadowhook
file(GLOB SHADOWHOOK_SOURCES "shadowhook/*.c")

# 2. Создаем из них библиотеку
add_library(shadowhook STATIC ${SHADOWHOOK_SOURCES})

# 3. Наша библиотека, которая будет содержать хуки
add_library(icecam_hook SHARED hook.cpp)

# 4. Настройка путей для заголовков
target_include_directories(icecam_hook PRIVATE shadowhook)
target_include_directories(shadowhook PUBLIC shadowhook)

# 5. Связываем всё
target_link_libraries(icecam_hook shadowhook log)
