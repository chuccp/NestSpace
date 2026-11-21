// Libc/JNI compatibility is kept as one translation unit so internal state
// and anonymous-namespace helpers remain process-local. The implementation is
// split by responsibility to keep individual source files reviewable.
#include "parts/00_core.inl"
#include "parts/01_jni.inl"
#include "parts/02_diagnostics.inl"
#include "parts/03_process.inl"
#include "parts/04_filesystem.inl"
#include "parts/05_runtime_bridge.inl"
#include "parts/06_syscall.inl"
#include "parts/07_dispatch.inl"
