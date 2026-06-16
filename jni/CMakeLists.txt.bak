cmake_minimum_required(VERSION 3.10.2)
project(icecam)

# Подключаем ShadowHook как подпроект из папки shadowhook_source
add_subdirectory(shadowhook_source)

# Создаем твою библиотеку
add_library(icecam_hook SHARED hook.cpp)

# Указываем пути к заголовкам shadowhook
target_include_directories(icecam_hook PRIVATE shadowhook_source/include)

# Линкуем
target_link_libraries(icecam_hook shadowhook log)
