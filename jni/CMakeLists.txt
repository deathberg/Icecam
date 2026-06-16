cmake_minimum_required(VERSION 3.10.2)

# CMP0148 (Find Python-модули) — ещё поддерживается как deprecated OLD в CMake 4.x,
# оставляем на случай, если какой-то третьесторонний скрипт внутри shadowhook его использует.
# CMP0026 убран: начиная с CMake 4.x OLD-поведение для него запрещено жёстко (ошибка, не warning).
set(CMAKE_POLICY_DEFAULT_CMP0148 OLD CACHE STRING "" FORCE)

if(POLICY CMP0148)
  cmake_policy(SET CMP0148 OLD)
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

set_target_properties(icecam_hook PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib/${ANDROID_ABI}
)