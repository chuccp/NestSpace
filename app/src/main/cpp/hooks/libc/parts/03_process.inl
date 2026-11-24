uid_t hook_geteuid() {
    record_hook_event(kHookEventUid);
    const Environment* environment = current_environment();
    if (environment != nullptr) {
        return static_cast<uid_t>(environment->observed_uid);
    }
    return g_real.geteuid ? g_real.geteuid() : geteuid();
}

gid_t hook_getgid() {
    record_hook_event(kHookEventUid);
    const Environment* environment = current_environment();
    if (environment != nullptr) {
        return static_cast<gid_t>(environment->observed_uid);
    }
    return g_real.getgid ? g_real.getgid() : getgid();
}

gid_t hook_getegid() {
    record_hook_event(kHookEventUid);
    const Environment* environment = current_environment();
    if (environment != nullptr) {
        return static_cast<gid_t>(environment->observed_uid);
    }
    return g_real.getegid ? g_real.getegid() : getegid();
}

int hook_getresuid(uid_t* real, uid_t* effective, uid_t* saved) {
    const Environment* environment = current_environment();
    if (environment == nullptr) {
        return g_real.getresuid
            ? g_real.getresuid(real, effective, saved)
            : getresuid(real, effective, saved);
    }
    const uid_t uid = static_cast<uid_t>(environment->observed_uid);
    if (real != nullptr) *real = uid;
    if (effective != nullptr) *effective = uid;
    if (saved != nullptr) *saved = uid;
    return 0;
}

int hook_getresgid(gid_t* real, gid_t* effective, gid_t* saved) {
    const Environment* environment = current_environment();
    if (environment == nullptr) {
        return g_real.getresgid
            ? g_real.getresgid(real, effective, saved)
            : getresgid(real, effective, saved);
    }
    const gid_t gid = static_cast<gid_t>(environment->observed_uid);
    if (real != nullptr) *real = gid;
    if (effective != nullptr) *effective = gid;
    if (saved != nullptr) *saved = gid;
    return 0;
}

int hook_getgroups(int size, gid_t* groups) {
    const Environment* environment = current_environment();
    if (environment == nullptr) {
        return g_real.getgroups
            ? g_real.getgroups(size, groups)
            : getgroups(size, groups);
    }
    const size_t configured_count =
        environment->supplementary_groups.size();
    const int count = static_cast<int>(
        configured_count == 0 ? 3 : configured_count
    );
    if (size == 0) {
        return count;
    }
    if (size < count) {
        errno = EINVAL;
        return -1;
    }
    if (groups == nullptr) {
        errno = EFAULT;
        return -1;
    }
    if (configured_count == 0) {
        groups[0] = 9997;
        groups[1] = static_cast<gid_t>(environment->observed_uid + 10000);
        groups[2] = static_cast<gid_t>(environment->observed_uid + 40000);
    } else {
        for (size_t index = 0; index < configured_count; ++index) {
            groups[index] = static_cast<gid_t>(
                environment->supplementary_groups[index]
            );
        }
    }
    return count;
}

int hook_capget(
    struct __user_cap_header_struct* header,
    struct __user_cap_data_struct* data
) {
    if (header == nullptr) {
        errno = EFAULT;
        return -1;
    }
    const Environment* environment = current_environment();
    const bool current_process = header->pid == 0 ||
        header->pid == getpid();
    if (environment == nullptr || !current_process) {
#ifdef SYS_capget
        return g_real.syscall
            ? static_cast<int>(g_real.syscall(SYS_capget, header, data))
            : static_cast<int>(syscall(SYS_capget, header, data));
#else
        errno = ENOSYS;
        return -1;
#endif
    }
    int count = 0;
    if (header->version == _LINUX_CAPABILITY_VERSION_1) {
        count = 1;
    } else if (
        header->version == _LINUX_CAPABILITY_VERSION_2 ||
        header->version == _LINUX_CAPABILITY_VERSION_3
    ) {
        count = 2;
    } else {
        header->version = _LINUX_CAPABILITY_VERSION_3;
        errno = EINVAL;
        return -1;
    }
    if (data == nullptr) {
        errno = EFAULT;
        return -1;
    }
    memset(data, 0, sizeof(*data) * static_cast<size_t>(count));
    return 0;
}

struct TgkillDispatchResult {
    void* return_override;
    int result;
};

extern "C" TgkillDispatchResult hook_tgkill_dispatch(
    pid_t group,
    pid_t thread,
    int signal,
    uintptr_t caller
) {
    if (current_environment() != nullptr &&
        group == getpid() &&
        thread == getpid() &&
        signal == SIGKILL) {
        Dl_info info{};
        const bool resolved = g_real.dladdr != nullptr &&
            g_real.dladdr(
                reinterpret_cast<void*>(caller),
                &info
            ) != 0;
        const uintptr_t base = resolved
            ? reinterpret_cast<uintptr_t>(info.dli_fbase)
            : 0;
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "suppressed target SIGKILL caller=%016" PRIxPTR
            " module=%016" PRIxPTR " offset=%016" PRIxPTR " name=%s",
            caller,
            base,
            base == 0 ? 0 : caller - base,
            resolved && info.dli_fname != nullptr
                ? info.dli_fname
                : "?"
        );
        uintptr_t frame =
            reinterpret_cast<uintptr_t>(__builtin_frame_address(0));
        for (int depth = 0; depth < 10; ++depth) {
            if (frame < 0x10000 || (frame & 0x7) != 0) break;
            const auto* words = reinterpret_cast<const uintptr_t*>(frame);
            const uintptr_t previous = words[0];
            const uintptr_t return_address = words[1];
            if (return_address != 0) {
                Dl_info frame_info{};
                const bool frame_resolved = g_real.dladdr != nullptr &&
                    g_real.dladdr(
                        reinterpret_cast<void*>(return_address),
                        &frame_info
                    ) != 0;
                const uintptr_t frame_base = frame_resolved
                    ? reinterpret_cast<uintptr_t>(frame_info.dli_fbase)
                    : 0;
                __android_log_print(
                    ANDROID_LOG_WARN,
                    "NestSpaceNative",
                    "target-sigkill-frame depth=%d pc=%016" PRIxPTR
                    " offset=%016" PRIxPTR " name=%s",
                    depth,
                    return_address,
                    frame_base == 0
                        ? 0
                        : return_address - frame_base,
                    frame_resolved && frame_info.dli_fname != nullptr
                        ? frame_info.dli_fname
                        : "?"
                );
            }
            if (previous <= frame || previous - frame > 0x100000) break;
            frame = previous;
        }
        for (uintptr_t offset = 0; offset <= 0x40; offset += sizeof(uint32_t)) {
            const auto* instructions = reinterpret_cast<const uint32_t*>(caller);
            const uint32_t first = instructions[offset / sizeof(uint32_t)];
            const uint32_t second = instructions[offset / sizeof(uint32_t) + 1];
            if (first == 0xa8c17bf3U && second == 0xd65f03c0U) {
                const uintptr_t epilogue = caller + offset;
                __android_log_print(
                    ANDROID_LOG_WARN,
                    "NestSpaceNative",
                    "skip self-kill cleanup caller=%016" PRIxPTR
                    " epilogue=%016" PRIxPTR " offset=%016" PRIxPTR,
                    caller,
                    epilogue,
                    offset
                );
                return {
                    reinterpret_cast<void*>(epilogue),
                    0
                };
            }
        }
        return {nullptr, 0};
    }
    const int result = g_real.tgkill
        ? g_real.tgkill(group, thread, signal)
        : tgkill(group, thread, signal);
    return {nullptr, result};
}

#if defined(__aarch64__)
extern "C" __attribute__((naked, noinline))
int hook_tgkill_entry(
    pid_t,
    pid_t,
    int
) {
    __asm__ __volatile__(
        "adrp x9, g_nestspace_tgkill_pid\n"
        "ldr w9, [x9, :lo12:g_nestspace_tgkill_pid]\n"
        "cmp w0, w9\n"
        "b.ne 1f\n"
        "cmp w1, w9\n"
        "b.ne 1f\n"
        "cmp w2, #9\n"
        "b.eq 2f\n"
        "1:\n"
        "stp x29, x30, [sp, #-16]!\n"
        "mov x29, sp\n"
        "mov x3, x30\n"
        "bl hook_tgkill_dispatch\n"
        "ldr x2, [x29, #8]\n"
        "cmp x0, #0\n"
        "csel x4, x0, x2, ne\n"
        "mov x0, x1\n"
        "mov sp, x29\n"
        "ldp x29, x30, [sp], #16\n"
        "mov x30, x4\n"
        "ret\n"
        "2:\n"
        "mov w0, wzr\n"
        "ret\n"
    );
}
#else
extern "C" int hook_tgkill_entry(
    pid_t group,
    pid_t thread,
    int signal
) {
    return hook_tgkill_dispatch(
        group,
        thread,
        signal,
        reinterpret_cast<uintptr_t>(__builtin_return_address(0))
    ).result;
}
#endif

int hook_kill(pid_t pid, int signal) {
    if (current_environment() != nullptr && pid == getpid()) {
        const uintptr_t caller = reinterpret_cast<uintptr_t>(
            __builtin_return_address(0)
        );
        Dl_info info{};
        const bool resolved = g_real.dladdr != nullptr &&
            g_real.dladdr(
                reinterpret_cast<void*>(caller),
                &info
            ) != 0;
        const uintptr_t base = resolved
            ? reinterpret_cast<uintptr_t>(info.dli_fbase)
            : 0;
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "target-kill pid=%d signal=%d caller=%016" PRIxPTR
            " module=%016" PRIxPTR " offset=%016" PRIxPTR " name=%s",
            pid,
            signal,
            caller,
            base,
            base == 0 ? 0 : caller - base,
            resolved && info.dli_fname != nullptr
                ? info.dli_fname
                : "?"
        );
    }
    return g_real.killProcess
        ? g_real.killProcess(pid, signal)
        : kill(pid, signal);
}

namespace {

void log_exit(const char* api, int status, uintptr_t caller);
void log_call_frames(const char* api);
void log_unwind_frames(const char* api);

}  // namespace

int hook_raise(int signal) {
    if (signal == SIGKILL &&
        current_environment() != nullptr &&
        gettid() != getpid()) {
        const uintptr_t caller = reinterpret_cast<uintptr_t>(
            __builtin_return_address(0)
        );
        Dl_info info{};
        const bool resolved = g_real.dladdr != nullptr &&
            g_real.dladdr(
                reinterpret_cast<void*>(caller),
                &info
            ) != 0;
        const uintptr_t base = resolved
            ? reinterpret_cast<uintptr_t>(info.dli_fbase)
            : 0;
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "contained helper SIGKILL tid=%d caller=%016" PRIxPTR
            " offset=%016" PRIxPTR " name=%s",
            static_cast<int>(gettid()),
            caller,
            base == 0 ? 0 : caller - base,
            resolved && info.dli_fname != nullptr
                ? info.dli_fname
                : "?"
        );
    }
    if (signal == SIGKILL &&
        current_environment() != nullptr &&
        gettid() == getpid()) {
        char pause[PROP_VALUE_MAX] = {};
        __system_property_get(
            "debug.nestspace.pause_packer_raise",
            pause
        );
        if (strcmp(pause, "1") == 0) {
            syscall(SYS_tgkill, getpid(), gettid(), SIGSTOP);
        }
        if (current_environment() != nullptr) {
            const uintptr_t caller = reinterpret_cast<uintptr_t>(
                __builtin_return_address(0)
            );
            Dl_info info{};
            const bool resolved = g_real.dladdr != nullptr &&
                g_real.dladdr(
                    reinterpret_cast<void*>(caller),
                    &info
                ) != 0;
            const uintptr_t base = resolved
                ? reinterpret_cast<uintptr_t>(info.dli_fbase)
                : 0;
            __android_log_print(
                ANDROID_LOG_WARN,
                "NestSpaceNative",
                "contained target SIGKILL via raise tid=%d caller=%016" PRIxPTR
                " offset=%016" PRIxPTR " name=%s",
                static_cast<int>(gettid()),
                caller,
                base == 0 ? 0 : caller - base,
                resolved && info.dli_fname != nullptr
                    ? info.dli_fname
                    : "?"
            );
        }
    }
    __android_log_print(
        ANDROID_LOG_WARN,
        "NestSpaceNative",
        "target-raise signal=%d caller=%016" PRIxPTR,
        signal,
        reinterpret_cast<uintptr_t>(__builtin_return_address(0))
    );
    if (signal == SIGKILL || signal == SIGSYS) {
        log_call_frames("target-raise");
        log_unwind_frames("target-raise-unwind");
        log_tracked_jit_pages();
    }
    log_exit(
        "target-raise",
        signal,
        reinterpret_cast<uintptr_t>(__builtin_return_address(0))
    );
    return g_real.raiseSignal
        ? g_real.raiseSignal(signal)
        : raise(signal);
}

void hook_abort() {
    log_exit(
        "target-abort",
        SIGABRT,
        reinterpret_cast<uintptr_t>(__builtin_return_address(0))
    );
    if (g_real.abortProcess != nullptr) {
        g_real.abortProcess();
    }
    abort();
}

namespace {

bool is_runtime_module_path(const char* path) {
    const Environment* environment = current_environment();
    if (path == nullptr || environment == nullptr) return false;
    const auto starts_with = [path](const std::string& prefix) {
        return !prefix.empty() &&
            strncmp(path, prefix.c_str(), prefix.size()) == 0 &&
            (path[prefix.size()] == '\0' || path[prefix.size()] == '/');
    };
    return starts_with(environment->data_root) ||
        std::any_of(
            environment->runtime_library_dirs.begin(),
            environment->runtime_library_dirs.end(),
            starts_with
        );
}

void log_exit(const char* api, int status, uintptr_t caller) {
    if (current_environment() == nullptr) return;
    char diagnostics[PROP_VALUE_MAX] = {};
    __system_property_get(
        "debug.nestspace.exit_diagnostics",
        diagnostics
    );
    if (strcmp(diagnostics, "1") != 0) return;
    Dl_info info{};
    const bool resolved = g_real.dladdr != nullptr &&
        g_real.dladdr(reinterpret_cast<void*>(caller), &info) != 0;
    const uintptr_t base = resolved
        ? reinterpret_cast<uintptr_t>(info.dli_fbase)
        : 0;
    if (resolved &&
        info.dli_fname != nullptr &&
        is_runtime_module_path(info.dli_fname) &&
        (getenv("NESTSPACE_CALLER_DUMP") != nullptr ||
            [] {
                char value[PROP_VALUE_MAX] = {};
                __system_property_get(
                    "debug.nestspace.caller_dump",
                    value
                );
                return strcmp(value, "1") == 0;
            }())) {
        static std::atomic<bool> dumped{false};
        if (!dumped.exchange(true)) {
            const Environment* environment = current_environment();
            const std::string path = environment->data_root +
                "/.nestspace/runtime-termination-caller.bin";
            const uintptr_t start = (caller & ~uintptr_t{3}) - 0x1000;
            const int fd = g_real.open
                ? g_real.open(
                    path.c_str(),
                    O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC,
                    0600
                )
                : -1;
            if (fd >= 0) {
                const auto* bytes =
                    reinterpret_cast<const uint8_t*>(start);
                write(fd, bytes, 0x3000);
                close(fd);
                __android_log_print(
                    ANDROID_LOG_WARN,
                    "NestSpaceNative",
                    "termination caller code dumped path=%s caller=%016"
                    PRIxPTR " start=%016" PRIxPTR,
                    path.c_str(),
                    caller,
                    start
                );
            }
        }
    }
    __android_log_print(
        ANDROID_LOG_WARN,
        "NestSpaceNative",
        "%s status=%d caller=%016" PRIxPTR
        " module=%016" PRIxPTR " offset=%016" PRIxPTR " name=%s",
        api,
        status,
        caller,
        base,
        base == 0 ? 0 : caller - base,
        resolved && info.dli_fname != nullptr ? info.dli_fname : "?"
    );
}

void log_call_frames(const char* api) {
    if (current_environment() == nullptr) return;
    uintptr_t frame =
        reinterpret_cast<uintptr_t>(__builtin_frame_address(0));
    for (int depth = 0; depth < 16; ++depth) {
        if (frame < 0x10000 || (frame & 0x7) != 0) break;
        const auto* words = reinterpret_cast<const uintptr_t*>(frame);
        const uintptr_t previous = words[0];
        const uintptr_t return_address = words[1];
        if (return_address == 0) break;
        Dl_info info{};
        const bool resolved = g_real.dladdr != nullptr &&
            g_real.dladdr(
                reinterpret_cast<void*>(return_address),
                &info
            ) != 0;
        const uintptr_t base = resolved
            ? reinterpret_cast<uintptr_t>(info.dli_fbase)
            : 0;
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "%s-frame depth=%d pc=%016" PRIxPTR
            " offset=%016" PRIxPTR " name=%s",
            api,
            depth,
            return_address,
            base == 0 ? 0 : return_address - base,
            resolved && info.dli_fname != nullptr
                ? info.dli_fname
                : "?"
        );
        if (previous <= frame || previous - frame > 0x100000) break;
        frame = previous;
    }
}

struct UnwindLogState {
    const char* api;
    int depth;
};

_Unwind_Reason_Code log_unwind_frame(
    struct _Unwind_Context* context,
    void* argument
) {
    auto* state = static_cast<UnwindLogState*>(argument);
    if (state == nullptr || context == nullptr) {
        return _URC_END_OF_STACK;
    }
    const uintptr_t address =
        static_cast<uintptr_t>(_Unwind_GetIP(context));
    if (address != 0) {
        Dl_info info{};
        const bool resolved = g_real.dladdr != nullptr &&
            g_real.dladdr(
                reinterpret_cast<void*>(address),
                &info
            ) != 0;
        const uintptr_t base = resolved
            ? reinterpret_cast<uintptr_t>(info.dli_fbase)
            : 0;
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "%s depth=%d pc=%016" PRIxPTR
            " offset=%016" PRIxPTR " name=%s",
            state->api,
            state->depth,
            address,
            base == 0 ? 0 : address - base,
            resolved && info.dli_fname != nullptr
                ? info.dli_fname
                : "?"
        );
    }
    ++state->depth;
    return state->depth >= 24 ? _URC_END_OF_STACK : _URC_NO_REASON;
}

void log_unwind_frames(const char* api) {
    if (current_environment() == nullptr) return;
    UnwindLogState state{api, 0};
    _Unwind_Backtrace(log_unwind_frame, &state);
}

}  // namespace

void hook_exit(int status) {
    log_exit(
        "target-exit exit",
        status,
        reinterpret_cast<uintptr_t>(__builtin_return_address(0))
    );
    if (g_real.exitProcess != nullptr) {
        g_real.exitProcess(status);
    }
    _exit(status);
}

void hook_exit_immediately(int status) {
    log_exit(
        "target-exit _exit",
        status,
        reinterpret_cast<uintptr_t>(__builtin_return_address(0))
    );
    if (g_real.exitImmediately != nullptr) {
        g_real.exitImmediately(status);
    }
    _exit(status);
}

void hook_stack_check_fail() {
    if (current_environment() == nullptr) return;
    uintptr_t stack = 0;
    uintptr_t canary = 0;
#if defined(__aarch64__)
    asm volatile("mov %0, sp" : "=r"(stack));
    asm volatile(
        "mrs %0, tpidr_el0\n"
        "ldr %0, [%0, #0x28]"
        : "=&r"(canary)
    );
#endif
    const uintptr_t saved_canary = stack == 0
        ? 0
        : *reinterpret_cast<const uintptr_t*>(stack + 0x20);
    const uintptr_t caller = reinterpret_cast<uintptr_t>(
        __builtin_return_address(0)
    );
    __android_log_print(
        ANDROID_LOG_ERROR,
        "NestSpaceNative",
        "target-stack-check caller=%016" PRIxPTR
        " stack=%016" PRIxPTR " canary=%016" PRIxPTR
        " saved=%016" PRIxPTR,
        caller,
        stack,
        canary,
        saved_canary
    );
    if (g_real.stackCheckFail != nullptr) {
        g_real.stackCheckFail();
    }
    abort();
}

long hook_ptrace(int request, pid_t pid, void* address, void* data) {
    char block_antidebug[PROP_VALUE_MAX] = {};
    __system_property_get(
        "debug.nestspace.block_antidebug_ptrace",
        block_antidebug
    );
    if (current_environment() != nullptr &&
        strcmp(block_antidebug, "1") == 0 &&
        (request == PTRACE_TRACEME ||
         request == PTRACE_ATTACH ||
         request == PTRACE_SEIZE)) {
        errno = EPERM;
        return -1;
    }
    const long result = g_real.ptraceCall
        ? g_real.ptraceCall(request, pid, address, data)
        : ptrace(request, pid, address, data);
    const long real_result = result;
    const int saved_errno = errno;
    static std::atomic<int> traceme_count{0};
    char ptrace_diagnostics[PROP_VALUE_MAX] = {};
    __system_property_get(
        "debug.nestspace.ptrace_diagnostics",
        ptrace_diagnostics
    );
    if (current_environment() != nullptr &&
        strcmp(ptrace_diagnostics, "1") == 0 &&
        (request != PTRACE_TRACEME || traceme_count.fetch_add(1) < 8)) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "target-ptrace request=%d pid=%d real=%ld result=%ld errno=%d",
            request,
            pid,
            real_result,
            result,
            real_result < 0 ? saved_errno : 0
        );
    }
    errno = saved_errno;
    return result;
}

int hook_prctl(
    int option,
    unsigned long argument2,
    unsigned long argument3,
    unsigned long argument4,
    unsigned long argument5
) {
    const Environment* environment = current_environment();
    if (environment != nullptr && option == PR_GET_NAME) {
        const int result = g_real.prctlCall
            ? g_real.prctlCall(
                option,
                argument2,
                argument3,
                argument4,
                argument5
            )
            : prctl(
                option,
                argument2,
                argument3,
                argument4,
                argument5
            );
        if (result == 0 && argument2 != 0 && gettid() == getpid()) {
            std::string name = environment->process_name;
            const size_t separator = name.find_last_of(":/");
            if (separator != std::string::npos) {
                name = name.substr(separator + 1);
            }
            if (name.size() > 15) name.erase(0, name.size() - 15);
            auto* output = reinterpret_cast<char*>(argument2);
            memset(output, 0, 16);
            memcpy(output, name.c_str(), std::min<size_t>(name.size(), 15));
        }
        return result;
    }
    return g_real.prctlCall
        ? g_real.prctlCall(
            option,
            argument2,
            argument3,
            argument4,
            argument5
        )
        : prctl(
            option,
            argument2,
            argument3,
            argument4,
            argument5
        );
}

int hook_open(const char* path, int flags, ...) {
    mode_t mode = 0;
