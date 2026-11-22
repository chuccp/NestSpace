#include "nestspace/native_environment_internal.h"

#include <android/log.h>
#include <csignal>
#include <cstddef>
#include <cstdint>

namespace nestspace {

bool is_packer_relocation_action(const struct sigaction* action) {
    (void)action;
    return false;
}

bool packer_relocation_guard_active() {
    return false;
}

bool is_syscall_gateway_action(const struct sigaction* action) {
    (void)action;
    return false;
}

bool syscall_gateway_active() {
    return false;
}

void capture_previous_segv_action(const struct sigaction* action) {
    (void)action;
}

}  // namespace nestspace

extern "C" void install_native_crash_trace(const char* path) {
    if (path == nullptr || path[0] == '\0') return;
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceCrash",
        "ARM64-specific crash recovery is unavailable for this ABI"
    );
}

extern "C" void set_native_packer_guard(bool enabled) {
    (void)enabled;
}

extern "C" void arm_native_object_watch(void* address, size_t size) {
    (void)address;
    (void)size;
}

extern "C" bool install_native_kill_guard() {
    return false;
}

extern "C" bool install_native_syscall_gateway() {
    return false;
}
