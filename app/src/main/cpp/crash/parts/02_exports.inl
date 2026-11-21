}  // namespace

namespace nestspace {

bool is_packer_relocation_action(const struct sigaction* action) {
    if (action == nullptr) return false;
    if ((action->sa_flags & SA_SIGINFO) != 0) {
        return action->sa_sigaction == packer_relocation_handler;
    }
    return action->sa_handler == reinterpret_cast<sighandler_t>(
        packer_relocation_handler
    );
}

bool packer_relocation_guard_active() {
    return g_packer_relocation_guard_installed;
}

bool is_syscall_gateway_action(const struct sigaction* action) {
    if (action == nullptr) return false;
    if ((action->sa_flags & SA_SIGINFO) != 0) {
        return action->sa_sigaction == syscall_guard_handler;
    }
    return action->sa_handler == reinterpret_cast<sighandler_t>(
        syscall_guard_handler
    );
}

bool syscall_gateway_active() {
    return g_syscall_gateway_active.load(std::memory_order_acquire);
}

void capture_previous_segv_action(const struct sigaction* action) {
    if (action != nullptr) {
        g_previous_segv_action = *action;
    }
}

}  // namespace nestspace

extern "C" void install_native_crash_trace(const char* path) {
    if (path == nullptr || path[0] == '\0') return;
    if (!diagnostic_enabled(
            "NESTSPACE_CRASH_TRACE",
            "debug.nestspace.crash_trace"
        )) {
        return;
    }
    g_trace_fd = open(path, O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC, 0600);
    if (g_trace_fd < 0) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "cannot open trace file: %s",
            path
        );
        return;
    }

    g_signal_stack.ss_sp = malloc(kSignalStackSize);
    g_signal_stack.ss_size = kSignalStackSize;
    g_signal_stack.ss_flags = 0;
    if (g_signal_stack.ss_sp == nullptr ||
        sigaltstack(&g_signal_stack, nullptr) != 0) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "cannot install signal stack"
        );
    }
    if (g_packer_relocation_guard_requested ||
        diagnostic_enabled(
            "NESTSPACE_PACKER_GUARD",
            "debug.nestspace.packer_guard"
        )) {
        install_packer_relocation_guard();
    }
    if (diagnostic_enabled(
            "NESTSPACE_FATAL_SIGNAL_TRACE",
            "debug.nestspace.fatal_signal_trace"
        )) {
        install_handler(SIGABRT);
        install_handler(SIGSEGV);
        install_handler(SIGBUS);
        install_handler(SIGILL);
    }
    install_syscall_handler();
    const bool kill_guard = install_kill_guard();
    __android_log_print(
        ANDROID_LOG_DEBUG,
        kLogTag,
        "kill guard=%d",
        kill_guard ? 1 : 0
    );
    const bool process_guard =
        getenv("NESTSPACE_PROCESS_GUARD") != nullptr &&
        install_process_guard();
    __android_log_print(
        ANDROID_LOG_DEBUG,
        kLogTag,
        "process guard=%d",
        process_guard ? 1 : 0
    );
    __android_log_print(ANDROID_LOG_DEBUG, kLogTag, "trace installed");
}

extern "C" void set_native_packer_guard(bool enabled) {
    g_packer_relocation_guard_requested = enabled;
}

extern "C" void arm_native_object_watch(void* address, size_t size) {
    if (address == nullptr || size == 0) return;
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return;
    constexpr uintptr_t kPointerTagMask = (1ULL << 56) - 1;
    const uintptr_t begin =
        reinterpret_cast<uintptr_t>(address) & kPointerTagMask;
    const uintptr_t page = begin & ~(static_cast<uintptr_t>(page_size) - 1);
    g_watch_start.store(begin, std::memory_order_release);
    g_watch_size.store(size, std::memory_order_release);
    g_watch_page.store(page, std::memory_order_release);
    if (mprotect(
            reinterpret_cast<void*>(page),
            static_cast<size_t>(page_size),
            PROT_READ
        ) != 0) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "object watch mprotect failed page=%p errno=%d",
            reinterpret_cast<void*>(page),
            errno
        );
        g_watch_page.store(0, std::memory_order_release);
    }
}

extern "C" bool install_native_kill_guard() {
    if (!diagnostic_enabled(
            "NESTSPACE_KILL_GUARD",
            "debug.nestspace.kill_guard"
        )) {
        return false;
    }
    install_syscall_handler();
    nestspace::install_process_signal_hooks();
    if (g_packer_relocation_guard_requested ||
        diagnostic_enabled(
            "NESTSPACE_PACKER_GUARD",
            "debug.nestspace.packer_guard"
        )) {
        install_packer_relocation_guard();
    }
    log_packer_relocation_guard_state("bootstrap");
    return install_kill_guard_filter();
}

extern "C" bool install_native_syscall_gateway() {
    if (!diagnostic_enabled(
            "NESTSPACE_SYSCALL_GATEWAY",
            "debug.nestspace.syscall_gateway"
        )) {
        return false;
    }
    return install_openat_gateway_filter();
}
