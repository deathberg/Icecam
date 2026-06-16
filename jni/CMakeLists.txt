cmake_minimum_required(VERSION 3.15)
project(icecam_hook)

# Подавление предупреждений политик для совместимости
if(POLICY CMP0148)
  cmake_policy(SET CMP0148 OLD)
endif()

# Подключаем ShadowHook 2.x (исходники должны лежать в shadowhook_source)
add_subdirectory(shadowhook_source)

# Определяем нашу библиотеку
add_library(icecam_hook SHARED hook.cpp)

# Указываем пути к инклудам ShadowHook
target_include_directories(icecam_hook PRIVATE shadowhook_source/include)

# Линкуем ShadowHook и системные библиотеки
target_link_libraries(icecam_hook shadowhook log dl)
