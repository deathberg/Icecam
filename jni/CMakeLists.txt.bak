cmake_minimum_required(VERSION 3.10.2)

# Добавь этот блок для подавления ошибок новых политик CMake
if(POLICY CMP0148)
  cmake_policy(SET CMP0148 OLD)
endif()
if(POLICY CMP0026)
  cmake_policy(SET CMP0026 OLD)
endif()

project(icecam)

# Подключаем ShadowHook
add_subdirectory(shadowhook_source)

# Твоя библиотека
add_library(icecam_hook SHARED hook.cpp)

target_include_directories(icecam_hook PRIVATE shadowhook_source/include)
target_link_libraries(icecam_hook shadowhook log)
