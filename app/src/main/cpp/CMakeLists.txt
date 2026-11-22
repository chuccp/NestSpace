cmake_minimum_required(VERSION 3.22.1)

project(nestspace_runtime LANGUAGES CXX)

set(NESTSPACE_RUNTIME_SOURCES
    core/native_environment.cpp
    core/native_environment_state.cpp
    hooks/libc/native_libc_hooks.cpp
    hooks/java/native_core_file_bridge.cpp
    hooks/elf/native_elf_hooks.cpp
    core/dex_file_trace.cpp
    core/complete_static_field.cpp
)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    list(APPEND NESTSPACE_RUNTIME_SOURCES
        crash/native_crash_trace.cpp
        core/late_jni_trace.cpp
    )
else()
    list(APPEND NESTSPACE_RUNTIME_SOURCES
        crash/native_crash_trace_generic.cpp
        core/late_jni_trace_generic.cpp
    )
endif()

add_library(
    nestspace_runtime
    SHARED
    ${NESTSPACE_RUNTIME_SOURCES}
)

target_include_directories(
    nestspace_runtime
    PRIVATE
    core/include
)

target_compile_features(nestspace_runtime PRIVATE cxx_std_17)
target_compile_options(
    nestspace_runtime
    PRIVATE
    -Wall
    -Wextra
    -Werror
    -fvisibility=hidden
)
target_link_options(
    nestspace_runtime
    PRIVATE
    "-Wl,-z,max-page-size=16384"
)
target_link_libraries(nestspace_runtime PRIVATE log)
