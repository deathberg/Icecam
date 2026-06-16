cmake_minimum_required(VERSION 3.10.2)

set(CMAKE_POLICY_DEFAULT_CMP0148 OLD CACHE STRING "" FORCE)
if(POLICY CMP0148)
  cmake_policy(SET CMP0148 OLD)
endif()

project(icecam CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- ShadowHook (вендорный код) ----
add_subdirectory(shadowhook_source)

# Форк shadowhook включает -Werror + новые диагностики Clang
# (-Wunsafe-buffer-usage, -Wdeclaration-after-statement), которых
# не было, когда писался оригинальный C-код. Патчить исходники
# не будем — просто понижаем эти варнинги обратно до warning,
# и только для таргетов из shadowhook_source.
get_property(_sh_targets DIRECTORY shadowhook_source PROPERTY BUILDSYSTEM_TARGETS)
foreach(_t ${_sh_targets})
  target_compile_options(${_t} PRIVATE
      -Wno-error
      -Wno-unsafe-buffer-usage
      -Wno-declaration-after-statement
  )
endforeach()

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