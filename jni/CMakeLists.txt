cmake_minimum_required(VERSION 3.10...3.25)

# Глобально отключаем старые политики, вызывающие ошибки в CMake 3.31+
if(POLICY CMP0148)
  cmake_policy(SET CMP0148 OLD)
endif()
if(POLICY CMP0153)
  cmake_policy(SET CMP0153 OLD)
endif()
if(POLICY CMP0144)
  cmake_policy(SET CMP0144 OLD)
endif()

project(icecam_hook)

# Указываем путь к shadowhook
add_subdirectory(shadowhook_source)

# Наша библиотека
add_library(icecam_hook SHARED hook.cpp)

# Линковка
target_include_directories(icecam_hook PRIVATE shadowhook_source/include)
target_link_libraries(icecam_hook shadowhook log dl)
