cmake_minimum_required(VERSION 3.22.1)

project(nestspace_runtime LANGUAGES CXX)

add_library(
    nestspace_runtime
    SHARED
    core/native_environment.cpp
    core/native_environment_state.cpp
    hooks/libc/native_libc_hooks.cpp
    hooks/elf/native_elf_hooks.cpp
    crash/native_crash_trace.cpp
    core/dex_file_trace.cpp
    core/process_dex_capture.cpp
    core/complete_static_field.cpp
    core/late_jni_trace.cpp
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
