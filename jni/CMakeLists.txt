cmake_minimum_required(VERSION 3.10.2)
project(icecam)

# 1. Автоматически находим все .c файлы в папке shadowhook
file(GLOB SHADOWHOOK_SOURCES "shadowhook/*.c")

# 2. Создаем статическую библиотеку из всех найденных файлов
add_library(shadowhook STATIC ${SHADOWHOOK_SOURCES})

# 3. Наша основная библиотека
add_library(icecam_hook SHARED hook.cpp)

# 4. Указываем пути к заголовочным файлам (.h)
target_include_directories(icecam_hook PRIVATE shadowhook)
target_include_directories(shadowhook PUBLIC shadowhook)

# 5. Связываем всё вместе
target_link_libraries(icecam_hook shadowhook log)
