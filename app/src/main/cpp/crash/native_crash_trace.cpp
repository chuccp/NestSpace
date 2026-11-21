// Crash reporting and the syscall gateway share signal-handler state, so they
// stay in one translation unit. Files are split by execution phase.
#include "parts/00_handlers.inl"
#include "parts/01_syscall_gateway.inl"
#include "parts/02_exports.inl"
