cmake_minimum_required(VERSION 3.10.2)

# Фиксируем поведение политик ДО входа в любые поддиректории.
# CACHE-переменные применяются даже если shadowhook_source/CMakeLists.txt
# вызывает свой собственный cmake_minimum_required() с другой версией.
set(CMAKE_POLICY_DEFAULT_CMP0148 OLD CACHE STRING "" FORCE)
set(CMAKE_POLICY_DEFAULT_CMP0026 OLD CACHE STRING "" FORCE)

if(POLICY CMP0148)
  cmake_policy(SET CMP0148 OLD)
endif()
if(POLICY CMP0026)
  cmake_policy(SET CMP0026 OLD)
endif()

project(icecam CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- ShadowHook ----
add_subdirectory(shadowhook_source)

# ---- Твоя библиотека ----
add_library(icecam_hook SHARED hook.cpp)

target_include_directories(icecam_hook PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/shadowhook_source/include
)

target_link_libraries(icecam_hook
    shadowhook
    log
    dl
)

# Складываем готовый .so в предсказуемое место
set_target_properties(icecam_hook PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib/${ANDROID_ABI}
)