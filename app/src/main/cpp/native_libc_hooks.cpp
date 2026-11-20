#include "native_environment_internal.h"

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/stat.h>
#include <sys/stat.h>
#include <sys/ptrace.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cinttypes>
#include <cstdarg>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

namespace nestspace {
namespace {

thread_local int g_path_hook_depth = 0;

struct PathHookScope {
    bool owner = false;

    PathHookScope() : owner(g_path_hook_depth++ == 0) {}
    ~PathHookScope() { --g_path_hook_depth; }

    bool enters_virtual_path() const { return owner; }
};

#if defined(SYS_newfstatat)
constexpr long kSysFstatat = SYS_newfstatat;
#elif defined(SYS_fstatat64)
constexpr long kSysFstatat = SYS_fstatat64;
#else
constexpr long kSysFstatat = -1;
#endif

ssize_t readlink_actual(
    const char* path,
    std::array<char, PATH_MAX + 1>* actual
) {
    const ssize_t length = g_real.readlink
        ? g_real.readlink(path, actual->data(), PATH_MAX)
        : readlink(path, actual->data(), PATH_MAX);
    if (length > 0) {
        (*actual)[static_cast<size_t>(length)] = '\0';
    }
    return length;
}

bool install_inline_jump(
    void* target,
    void* replacement,
    void** trampoline
) {
#if !defined(__aarch64__)
    (void)target;
    (void)replacement;
    (void)trampoline;
    return false;
#else
    constexpr size_t kPatchSize = 16;
    const long page_size = sysconf(_SC_PAGESIZE);
    if (target == nullptr || replacement == nullptr || trampoline == nullptr ||
        page_size <= 0) {
        return false;
    }

    auto* target_bytes = static_cast<uint8_t*>(target);
    auto* code = static_cast<uint8_t*>(mmap(
        nullptr,
        static_cast<size_t>(page_size),
        PROT_READ | PROT_WRITE | PROT_EXEC,
        MAP_PRIVATE | MAP_ANONYMOUS,
        -1,
        0
    ));
    if (code == MAP_FAILED) return false;

    memcpy(code, target_bytes, kPatchSize);
    const uint32_t branch_literal = 0x58000050;
    const uint32_t branch_register = 0xd61f0200;
    memcpy(code + kPatchSize, &branch_literal, sizeof(branch_literal));
    memcpy(code + kPatchSize + 4, &branch_register, sizeof(branch_register));
    const uint64_t return_address =
        reinterpret_cast<uintptr_t>(target) + kPatchSize;
    memcpy(code + kPatchSize + 8, &return_address, sizeof(return_address));
    __builtin___clear_cache(
        reinterpret_cast<char*>(code),
        reinterpret_cast<char*>(code + page_size)
    );
    *trampoline = code;

    const uintptr_t page = reinterpret_cast<uintptr_t>(target) &
        ~(static_cast<uintptr_t>(page_size) - 1U);
    if (mprotect(
            reinterpret_cast<void*>(page),
            static_cast<size_t>(page_size),
            PROT_READ | PROT_WRITE | PROT_EXEC
        ) != 0) {
        munmap(code, static_cast<size_t>(page_size));
        *trampoline = nullptr;
        return false;
    }

    const uint64_t replacement_address =
        reinterpret_cast<uintptr_t>(replacement);
    memcpy(target_bytes, &branch_literal, sizeof(branch_literal));
    memcpy(target_bytes + 4, &branch_register, sizeof(branch_register));
    memcpy(target_bytes + 8, &replacement_address, sizeof(replacement_address));
    __builtin___clear_cache(
        reinterpret_cast<char*>(target),
        reinterpret_cast<char*>(target_bytes + kPatchSize)
    );
    mprotect(
        reinterpret_cast<void*>(page),
        static_cast<size_t>(page_size),
        PROT_READ | PROT_EXEC
    );
    return true;
#endif
}

}  // namespace

RealFunctions g_real;
extern "C" volatile int g_nestspace_tgkill_pid = -1;

void initialize_real_functions() {
    static std::once_flag once;
    std::call_once(once, [] {
        g_real.getuid = reinterpret_cast<GetuidFunction>(
            dlsym(RTLD_DEFAULT, "getuid")
        );
        g_real.geteuid = reinterpret_cast<GetuidFunction>(
            dlsym(RTLD_DEFAULT, "geteuid")
        );
        g_real.open = reinterpret_cast<OpenFunction>(dlsym(RTLD_DEFAULT, "open"));
        g_real.open64 = reinterpret_cast<OpenFunction>(
            dlsym(RTLD_DEFAULT, "open64")
        );
        g_real.openat = reinterpret_cast<OpenatFunction>(
            dlsym(RTLD_DEFAULT, "openat")
        );
        g_real.openat64 = reinterpret_cast<OpenatFunction>(
            dlsym(RTLD_DEFAULT, "openat64")
        );
        g_real.access = reinterpret_cast<AccessFunction>(
            dlsym(RTLD_DEFAULT, "access")
        );
        g_real.faccessat = reinterpret_cast<FaccessatFunction>(
            dlsym(RTLD_DEFAULT, "faccessat")
        );
        g_real.stat = reinterpret_cast<StatFunction>(dlsym(RTLD_DEFAULT, "stat"));
        g_real.lstat = reinterpret_cast<StatFunction>(
            dlsym(RTLD_DEFAULT, "lstat")
        );
        g_real.fstatat = reinterpret_cast<FstatatFunction>(
            dlsym(RTLD_DEFAULT, "fstatat")
        );
        g_real.statx = reinterpret_cast<StatxFunction>(
            dlsym(RTLD_DEFAULT, "statx")
        );
        g_real.readlink = reinterpret_cast<ReadlinkFunction>(
            dlsym(RTLD_DEFAULT, "readlink")
        );
        g_real.readlinkat = reinterpret_cast<ReadlinkatFunction>(
            dlsym(RTLD_DEFAULT, "readlinkat")
        );
        g_real.opendir = reinterpret_cast<OpendirFunction>(
            dlsym(RTLD_DEFAULT, "opendir")
        );
        g_real.realpath = reinterpret_cast<RealpathFunction>(
            dlsym(RTLD_DEFAULT, "realpath")
        );
        g_real.canonicalize = reinterpret_cast<CanonicalizeFunction>(
            dlsym(RTLD_DEFAULT, "canonicalize_file_name")
        );
        g_real.close = reinterpret_cast<CloseFunction>(
            dlsym(RTLD_DEFAULT, "close")
        );
        g_real.dup = reinterpret_cast<DupFunction>(dlsym(RTLD_DEFAULT, "dup"));
        g_real.dup2 = reinterpret_cast<Dup2Function>(
            dlsym(RTLD_DEFAULT, "dup2")
        );
        g_real.dup3 = reinterpret_cast<Dup3Function>(
            dlsym(RTLD_DEFAULT, "dup3")
        );
        g_real.fcntl = reinterpret_cast<FcntlFunction>(
            dlsym(RTLD_DEFAULT, "fcntl")
        );
        g_real.syscall = reinterpret_cast<SyscallFunction>(
            dlsym(RTLD_DEFAULT, "syscall")
        );
        g_real.fopen = reinterpret_cast<FopenFunction>(
            dlsym(RTLD_DEFAULT, "fopen")
        );
        g_real.fopen64 = reinterpret_cast<FopenFunction>(
            dlsym(RTLD_DEFAULT, "fopen64")
        );
        g_real.fdopen = reinterpret_cast<FdopenFunction>(
            dlsym(RTLD_DEFAULT, "fdopen")
        );
        g_real.fclose = reinterpret_cast<FcloseFunction>(
            dlsym(RTLD_DEFAULT, "fclose")
        );
        g_real.fileno = reinterpret_cast<FilenoFunction>(
            dlsym(RTLD_DEFAULT, "fileno")
        );
        g_real.propertyGet = reinterpret_cast<PropertyGetFunction>(
            dlsym(RTLD_DEFAULT, "__system_property_get")
        );
        g_real.propertyFind = reinterpret_cast<PropertyFindFunction>(
            dlsym(RTLD_DEFAULT, "__system_property_find")
        );
        g_real.propertyReadCallback = reinterpret_cast<PropertyReadCallbackFunction>(
            dlsym(RTLD_DEFAULT, "__system_property_read_callback")
        );
        g_real.propertyRead = reinterpret_cast<PropertyReadFunction>(
            dlsym(RTLD_DEFAULT, "__system_property_read")
        );
        g_real.propertySerial = reinterpret_cast<PropertySerialFunction>(
            dlsym(RTLD_DEFAULT, "__system_property_serial")
        );
        g_real.dlopen = reinterpret_cast<DlopenFunction>(
            dlsym(RTLD_DEFAULT, "dlopen")
        );
        g_real.tgkill = reinterpret_cast<TgkillFunction>(
            dlsym(RTLD_DEFAULT, "tgkill")
        );
        g_real.exitProcess = reinterpret_cast<ExitFunction>(
            dlsym(RTLD_DEFAULT, "exit")
        );
        g_real.exitImmediately = reinterpret_cast<ExitFunction>(
            dlsym(RTLD_DEFAULT, "_exit")
        );
        g_real.stackCheckFail = reinterpret_cast<StackCheckFailFunction>(
            dlsym(RTLD_DEFAULT, "__stack_chk_fail")
        );
        g_real.ptraceCall = reinterpret_cast<PtraceFunction>(
            dlsym(RTLD_DEFAULT, "ptrace")
        );
        g_real.prctlCall = reinterpret_cast<PrctlFunction>(
            dlsym(RTLD_DEFAULT, "prctl")
        );
        g_real.dlsym = reinterpret_cast<DlsymFunction>(
            dlsym(RTLD_DEFAULT, "dlsym")
        );
        g_real.dlvsym = reinterpret_cast<DlvsymFunction>(
            dlsym(RTLD_DEFAULT, "dlvsym")
        );
        g_real.dlIteratePhdr = reinterpret_cast<DlIteratePhdrFunction>(
            dlsym(RTLD_DEFAULT, "dl_iterate_phdr")
        );
        g_real.dladdr = reinterpret_cast<DladdrFunction>(
            dlsym(RTLD_DEFAULT, "dladdr")
        );
        g_real.androidDlopenExt = reinterpret_cast<AndroidDlopenExtFunction>(
            dlsym(RTLD_DEFAULT, "android_dlopen_ext")
        );
    });
}

namespace {

void log_sensitive_path(const char* api, const char* path) {
    if (path == nullptr || current_environment() == nullptr) return;
    if (strstr(path, "/proc/self/status") == nullptr &&
        strstr(path, "/proc/self/maps") == nullptr &&
        strstr(path, "/proc/self/task") == nullptr &&
        strstr(path, "/proc/self/mountinfo") == nullptr &&
        strstr(path, "/dev/__properties__") == nullptr &&
        strstr(path, "ro.debuggable") == nullptr) {
        return;
    }
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "target-path api=%s path=%s",
        api,
        path
    );
}

}  // namespace

void install_loader_symbol_hooks() {
    if (current_environment() == nullptr) return;
    static std::once_flag once;
    std::call_once(once, [] {
        void* dlsym_trampoline = nullptr;
        void* dlvsym_trampoline = nullptr;
        void* dlsym_target = g_real.dlsym != nullptr
            ? reinterpret_cast<void*>(g_real.dlsym)
            : dlsym(RTLD_DEFAULT, "dlsym");
        void* dlvsym_target = g_real.dlvsym != nullptr
            ? reinterpret_cast<void*>(g_real.dlvsym)
            : dlsym(RTLD_DEFAULT, "dlvsym");
        if (install_inline_jump(
                dlsym_target,
                reinterpret_cast<void*>(hook_dlsym),
                &dlsym_trampoline
            )) {
            g_real.dlsym = reinterpret_cast<DlsymFunction>(dlsym_trampoline);
        }
        if (install_inline_jump(
                dlvsym_target,
                reinterpret_cast<void*>(hook_dlvsym),
                &dlvsym_trampoline
            )) {
            g_real.dlvsym = reinterpret_cast<DlvsymFunction>(dlvsym_trampoline);
        }
    });
}

void install_libc_path_hooks() {
    if (current_environment() == nullptr) return;
    static std::once_flag once;
    std::call_once(once, [] {
        initialize_real_functions();
        void* open_trampoline = nullptr;
        void* openat_trampoline = nullptr;
        void* open64_trampoline = nullptr;
        void* openat64_trampoline = nullptr;
        void* tgkill_trampoline = nullptr;
        void* exit_trampoline = nullptr;
        void* immediate_exit_trampoline = nullptr;
        const OpenFunction original_open = g_real.open;
        const OpenFunction original_open64 = g_real.open64;
        const OpenatFunction original_openat = g_real.openat;
        const OpenatFunction original_openat64 = g_real.openat64;

        if (original_open != nullptr &&
            install_inline_jump(
                reinterpret_cast<void*>(original_open),
                reinterpret_cast<void*>(hook_open),
                &open_trampoline
            )) {
            g_real.open = reinterpret_cast<OpenFunction>(open_trampoline);
        }
        if (original_open64 != nullptr) {
            if (original_open64 == original_open) {
                g_real.open64 = g_real.open;
            } else if (install_inline_jump(
                    reinterpret_cast<void*>(original_open64),
                    reinterpret_cast<void*>(hook_open64),
                    &open64_trampoline
                )) {
                g_real.open64 = reinterpret_cast<OpenFunction>(open64_trampoline);
            }
        }
        if (original_openat != nullptr &&
            install_inline_jump(
                reinterpret_cast<void*>(original_openat),
                reinterpret_cast<void*>(hook_openat),
                &openat_trampoline
            )) {
            g_real.openat = reinterpret_cast<OpenatFunction>(openat_trampoline);
        }
        if (original_openat64 != nullptr) {
            if (original_openat64 == original_openat) {
                g_real.openat64 = g_real.openat;
            } else if (install_inline_jump(
                    reinterpret_cast<void*>(original_openat64),
                    reinterpret_cast<void*>(hook_openat64),
                    &openat64_trampoline
                )) {
                g_real.openat64 = reinterpret_cast<OpenatFunction>(
                    openat64_trampoline
                );
            }
        }
        if (g_real.tgkill != nullptr &&
            install_inline_jump(
                reinterpret_cast<void*>(g_real.tgkill),
                reinterpret_cast<void*>(hook_tgkill_entry),
                &tgkill_trampoline
            )) {
            g_real.tgkill = reinterpret_cast<TgkillFunction>(
                tgkill_trampoline
            );
        }
        if (g_real.exitProcess != nullptr &&
            install_inline_jump(
                reinterpret_cast<void*>(g_real.exitProcess),
                reinterpret_cast<void*>(hook_exit),
                &exit_trampoline
            )) {
            g_real.exitProcess = reinterpret_cast<ExitFunction>(
                exit_trampoline
            );
        }
        if (g_real.exitImmediately != nullptr &&
            install_inline_jump(
                reinterpret_cast<void*>(g_real.exitImmediately),
                reinterpret_cast<void*>(hook_exit_immediately),
                &immediate_exit_trampoline
            )) {
            g_real.exitImmediately = reinterpret_cast<ExitFunction>(
                immediate_exit_trampoline
            );
        }
    });
}

void install_process_signal_hooks() {
    if (current_environment() == nullptr) return;
    static std::once_flag once;
    std::call_once(once, [] {
        initialize_real_functions();
        g_nestspace_tgkill_pid = getpid();
        void* tgkill_trampoline = nullptr;
        if (g_real.tgkill != nullptr &&
            install_inline_jump(
                reinterpret_cast<void*>(g_real.tgkill),
                reinterpret_cast<void*>(hook_tgkill_entry),
                &tgkill_trampoline
            )) {
            g_real.tgkill = reinterpret_cast<TgkillFunction>(
                tgkill_trampoline
            );
        }
    });
}

void install_property_hooks() {
    if (current_environment() == nullptr) return;
    static std::once_flag once;
    std::call_once(once, [] {
        initialize_real_functions();
        void* property_get_trampoline = nullptr;
        if (g_real.propertyGet != nullptr &&
            install_inline_jump(
                reinterpret_cast<void*>(g_real.propertyGet),
                reinterpret_cast<void*>(hook_property_get),
                &property_get_trampoline
            )) {
            g_real.propertyGet =
                reinterpret_cast<PropertyGetFunction>(
                    property_get_trampoline
                );
        }
    });
}

uid_t hook_getuid() {
    record_hook_event(kHookEventUid);
    const Environment* environment = current_environment();
    if (environment != nullptr) {
        return static_cast<uid_t>(environment->observed_uid);
    }
    return g_real.getuid ? g_real.getuid() : getuid();
}

uid_t hook_geteuid() {
    record_hook_event(kHookEventUid);
    const Environment* environment = current_environment();
    if (environment != nullptr) {
        return static_cast<uid_t>(environment->observed_uid);
    }
    return g_real.geteuid ? g_real.geteuid() : geteuid();
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

namespace {

void log_exit(const char* api, int status) {
    if (current_environment() == nullptr) return;
    const uintptr_t caller = reinterpret_cast<uintptr_t>(
        __builtin_return_address(0)
    );
    Dl_info info{};
    const bool resolved = g_real.dladdr != nullptr &&
        g_real.dladdr(reinterpret_cast<void*>(caller), &info) != 0;
    const uintptr_t base = resolved
        ? reinterpret_cast<uintptr_t>(info.dli_fbase)
        : 0;
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

}  // namespace

void hook_exit(int status) {
    log_exit("target-exit exit", status);
    if (g_real.exitProcess != nullptr) {
        g_real.exitProcess(status);
    }
    _exit(status);
}

void hook_exit_immediately(int status) {
    log_exit("target-exit _exit", status);
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
    static std::atomic<int> traceme_count{0};
    if (current_environment() != nullptr &&
        (request != PTRACE_TRACEME || traceme_count.fetch_add(1) == 0)) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "target-ptrace request=%d pid=%d",
            request,
            pid
        );
    }
    return g_real.ptraceCall
        ? g_real.ptraceCall(request, pid, address, data)
        : ptrace(request, pid, address, data);
}

int hook_prctl(
    int option,
    unsigned long argument2,
    unsigned long argument3,
    unsigned long argument4,
    unsigned long argument5
) {
    if (current_environment() != nullptr &&
        (option == PR_GET_DUMPABLE || option == PR_GET_NO_NEW_PRIVS)) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "target-prctl option=%d virtual=0",
            option
        );
        return 0;
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
    if ((flags & O_CREAT) != 0) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    PathHookScope scope;
    if (!scope.enters_virtual_path()) {
        return g_real.open ? g_real.open(path, flags, mode)
                           : open(path, flags, mode);
    }
    log_sensitive_path("open", path);
    const int virtual_fd = open_virtual_proc(path, flags);
    if (virtual_fd >= 0) return virtual_fd;
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const bool mapped = rewrite_target_path(path, &rewritten);
    const char* actual = mapped ? rewritten.c_str() : path;
    return g_real.open ? g_real.open(actual, flags, mode)
                       : open(actual, flags, mode);
}

int hook_open64(const char* path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) != 0) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    PathHookScope scope;
    if (!scope.enters_virtual_path()) {
        return g_real.open64 ? g_real.open64(path, flags, mode)
                             : open64(path, flags, mode);
    }
    log_sensitive_path("open64", path);
    const int virtual_fd = open_virtual_proc(path, flags);
    if (virtual_fd >= 0) return virtual_fd;
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const bool mapped = rewrite_target_path(path, &rewritten);
    const char* actual = mapped ? rewritten.c_str() : path;
    return g_real.open64 ? g_real.open64(actual, flags, mode)
                         : open64(actual, flags, mode);
}

int hook_open_2(const char* path, int flags) {
    return hook_open(path, flags);
}

int hook_openat(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) != 0) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    PathHookScope scope;
    if (!scope.enters_virtual_path()) {
        return g_real.openat
            ? g_real.openat(dirfd, path, flags, mode)
            : openat(dirfd, path, flags, mode);
    }
    log_sensitive_path("openat", path);
    if (path != nullptr && path[0] == '/') {
        const int virtual_fd = open_virtual_proc(path, flags);
        if (virtual_fd >= 0) return virtual_fd;
    }
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const bool mapped = rewrite_target_path(path, &rewritten);
    const char* actual = mapped ? rewritten.c_str() : path;
    return g_real.openat ? g_real.openat(dirfd, actual, flags, mode)
                         : openat(dirfd, actual, flags, mode);
}

int hook_openat_2(int dirfd, const char* path, int flags) {
    return hook_openat(dirfd, path, flags);
}

int hook_openat64(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) != 0) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
    PathHookScope scope;
    if (!scope.enters_virtual_path()) {
        return g_real.openat64
            ? g_real.openat64(dirfd, path, flags, mode)
            : openat64(dirfd, path, flags, mode);
    }
    log_sensitive_path("openat64", path);
    if (path != nullptr && path[0] == '/') {
        const int virtual_fd = open_virtual_proc(path, flags);
        if (virtual_fd >= 0) return virtual_fd;
    }
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const bool mapped = rewrite_target_path(path, &rewritten);
    const char* actual = mapped ? rewritten.c_str() : path;
    return g_real.openat64 ? g_real.openat64(dirfd, actual, flags, mode)
                           : openat64(dirfd, actual, flags, mode);
}

int hook_access(const char* path, int mode) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    return g_real.access ? g_real.access(actual, mode) : access(actual, mode);
}

int hook_faccessat(int dirfd, const char* path, int mode, int flags) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    return g_real.faccessat ? g_real.faccessat(dirfd, actual, mode, flags)
                            : faccessat(dirfd, actual, mode, flags);
}

int hook_stat(const char* path, struct stat* value) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    const int result = g_real.stat
        ? g_real.stat(actual, value)
        : stat(actual, value);
    if (result == 0) virtualize_target_file_stat(actual, value);
    return result;
}

int hook_lstat(const char* path, struct stat* value) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    const int result = g_real.lstat
        ? g_real.lstat(actual, value)
        : lstat(actual, value);
    if (result == 0) virtualize_target_file_stat(actual, value);
    return result;
}

int hook_fstatat(int dirfd, const char* path, struct stat* value, int flags) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    const int result = g_real.fstatat
        ? g_real.fstatat(dirfd, actual, value, flags)
        : fstatat(dirfd, actual, value, flags);
    if (result == 0) virtualize_target_file_stat(actual, value);
    return result;
}

int hook_statx(
    int dirfd,
    const char* path,
    int flags,
    unsigned int mask,
    struct statx* value
) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    const int result = g_real.statx != nullptr
        ? g_real.statx(dirfd, actual, flags, mask, value)
#if defined(SYS_statx)
        : static_cast<int>(syscall(
            SYS_statx,
            dirfd,
            actual,
            flags,
            mask,
            value
        ));
#else
        : -1;
#endif
    if (result == 0) virtualize_target_statx(actual, value);
    return result;
}

ssize_t hook_readlink(const char* path, char* buffer, size_t size) {
    record_hook_event(kHookEventFile);
    int fd = -1;
    if (proc_fd_number(path, &fd)) {
        const char* virtual_path = fd_virtual_path(fd);
        if (virtual_path != nullptr) {
            if (size == 0) return -1;
            const size_t count = std::min(size - 1, strlen(virtual_path));
            memcpy(buffer, virtual_path, count);
            buffer[count] = '\0';
            return static_cast<ssize_t>(count);
        }
        std::array<char, PATH_MAX + 1> actual{};
        const ssize_t length = readlink_actual(path, &actual);
        if (length <= 0) return length;
        ssize_t mapped = -1;
        if (virtual_path_from_actual(actual.data(), buffer, size, &mapped)) {
            return mapped;
        }
        if (buffer != nullptr && size > 0) {
            const size_t count = std::min(size, static_cast<size_t>(length));
            memcpy(buffer, actual.data(), count);
            return static_cast<ssize_t>(count);
        }
        return size == 0 ? -1 : static_cast<ssize_t>(length);
    }
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    return g_real.readlink ? g_real.readlink(actual, buffer, size)
                           : readlink(actual, buffer, size);
}

ssize_t hook_readlinkat(
    int dirfd,
    const char* path,
    char* buffer,
    size_t size
) {
    record_hook_event(kHookEventFile);
    int fd = -1;
    if (proc_fd_number(path, &fd)) {
        return hook_readlink(path, buffer, size);
    }
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    return g_real.readlinkat
        ? g_real.readlinkat(dirfd, actual, buffer, size)
        : readlinkat(dirfd, actual, buffer, size);
}

ssize_t hook_readlink_chk(
    const char* path,
    char* buffer,
    size_t size,
    size_t
) {
    return hook_readlink(path, buffer, size);
}

ssize_t hook_readlinkat_chk(
    int dirfd,
    const char* path,
    char* buffer,
    size_t size,
    size_t
) {
    return hook_readlinkat(dirfd, path, buffer, size);
}

DIR* hook_opendir(const char* path) {
    record_hook_event(kHookEventFile);
    log_sensitive_path("opendir", path);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    return g_real.opendir ? g_real.opendir(actual) : opendir(actual);
}

char* hook_realpath(const char* path, char* resolved) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    if (path == nullptr ||
        !rewrite_target_path(path, &rewritten)) {
        return g_real.realpath ? g_real.realpath(path, resolved)
                               : realpath(path, resolved);
    }
    char* actual = g_real.realpath
        ? g_real.realpath(rewritten.c_str(), nullptr)
        : realpath(rewritten.c_str(), nullptr);
    if (actual == nullptr) return nullptr;
    free(actual);
    const size_t length = strlen(path);
    char* output = resolved != nullptr
        ? resolved
        : static_cast<char*>(malloc(length + 1));
    if (output == nullptr) return nullptr;
    memcpy(output, path, length + 1);
    return output;
}

char* hook_canonicalize_file_name(const char* path) {
    return hook_realpath(path, nullptr);
}

int hook_close(int fd) {
    erase_fd_path(fd);
    return g_real.close ? g_real.close(fd) : close(fd);
}

int hook_dup(int fd) {
    const int duplicate = g_real.dup ? g_real.dup(fd) : dup(fd);
    if (duplicate >= 0) copy_fd_path(fd, duplicate);
    return duplicate;
}

int hook_dup2(int old_fd, int new_fd) {
    const int result = g_real.dup2 ? g_real.dup2(old_fd, new_fd)
                                   : dup2(old_fd, new_fd);
    if (result >= 0) copy_fd_path(old_fd, result);
    return result;
}

int hook_dup3(int old_fd, int new_fd, int flags) {
    const int result = g_real.dup3 ? g_real.dup3(old_fd, new_fd, flags)
                                   : dup3(old_fd, new_fd, flags);
    if (result >= 0) copy_fd_path(old_fd, result);
    return result;
}

int hook_fcntl(int fd, int command, ...) {
    uintptr_t argument = 0;
    const bool no_argument =
        command == F_GETFD || command == F_GETFL || command == F_GETOWN ||
        command == F_GETSIG || command == F_GETLEASE ||
        command == F_GETPIPE_SZ || command == F_GET_SEALS;
    if (!no_argument) {
        va_list args;
        va_start(args, command);
        argument = reinterpret_cast<uintptr_t>(va_arg(args, void*));
        va_end(args);
    }
    const int result = g_real.fcntl ? g_real.fcntl(fd, command, argument)
                                    : fcntl(fd, command, argument);
    if (result >= 0 &&
        (command == F_DUPFD || command == F_DUPFD_CLOEXEC)) {
        copy_fd_path(fd, result);
    }
    return result;
}

FILE* hook_fopen(const char* path, const char* mode) {
    if (path == nullptr || mode == nullptr) return nullptr;
    const bool reading = mode[0] == 'r' && strchr(mode, '+') == nullptr;
    int fd = -1;
    if (reading) fd = open_virtual_proc(path, O_RDONLY);
    if (fd < 0) {
        record_hook_event(kHookEventFile);
        std::string rewritten;
        const bool mapped = rewrite_target_path(path, &rewritten);
        const char* actual = mapped ? rewritten.c_str() : path;
        if (!mapped) {
            return g_real.fopen ? g_real.fopen(path, mode) : fopen(path, mode);
        }
        const int flags = (strchr(mode, 'w') != nullptr)
            ? O_WRONLY | O_CREAT | O_TRUNC
            : (strchr(mode, 'a') != nullptr)
                ? O_WRONLY | O_CREAT | O_APPEND
                : O_RDONLY;
        fd = g_real.open ? g_real.open(actual, flags, 0666)
                         : open(actual, flags, 0666);
        if (fd < 0) return nullptr;
    }
    FILE* file = g_real.fdopen ? g_real.fdopen(fd, mode) : fdopen(fd, mode);
    if (file == nullptr) {
        erase_fd_path(fd);
        if (g_real.close) g_real.close(fd); else close(fd);
    }
    return file;
}

FILE* hook_fopen64(const char* path, const char* mode) {
    if (path == nullptr || mode == nullptr) return nullptr;
    const bool reading = mode[0] == 'r' && strchr(mode, '+') == nullptr;
    int fd = -1;
    if (reading) fd = open_virtual_proc(path, O_RDONLY);
    if (fd < 0) {
        record_hook_event(kHookEventFile);
        std::string rewritten;
        const bool mapped = rewrite_target_path(path, &rewritten);
        const char* actual = mapped ? rewritten.c_str() : path;
        if (!mapped) {
            return g_real.fopen64 ? g_real.fopen64(path, mode) : fopen64(path, mode);
        }
        const int flags = (strchr(mode, 'w') != nullptr)
            ? O_WRONLY | O_CREAT | O_TRUNC
            : (strchr(mode, 'a') != nullptr)
                ? O_WRONLY | O_CREAT | O_APPEND
                : O_RDONLY;
        fd = g_real.open64 ? g_real.open64(actual, flags, 0666)
                           : open64(actual, flags, 0666);
        if (fd < 0) return nullptr;
    }
    FILE* file = g_real.fdopen ? g_real.fdopen(fd, mode) : fdopen(fd, mode);
    if (file == nullptr) {
        erase_fd_path(fd);
        if (g_real.close) g_real.close(fd); else close(fd);
    }
    return file;
}

int hook_fclose(FILE* file) {
    if (file != nullptr && g_real.fileno) {
        const int fd = g_real.fileno(file);
        if (fd >= 0) erase_fd_path(fd);
    }
    return g_real.fclose ? g_real.fclose(file) : fclose(file);
}

int hook_property_get(const char* name, char* value) {
    record_hook_event(kHookEventProperty);
    if (name != nullptr && strcmp(name, "ro.debuggable") == 0) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "target-property name=%s",
            name
        );
    }
    if (name != nullptr && value != nullptr) {
        const Environment* environment = current_environment();
        if (environment != nullptr && environment->property_snapshot != nullptr) {
            const auto* property = environment->property_snapshot->find(name);
            if (property != nullptr) {
                const size_t length = std::min(
                    property->value.size(),
                    static_cast<size_t>(PROP_VALUE_MAX - 1)
                );
                memcpy(value, property->value.data(), length);
                value[length] = '\0';
                g_property_reads.fetch_add(1, std::memory_order_relaxed);
                return static_cast<int>(length);
            }
        }
    }
    return g_real.propertyGet
        ? g_real.propertyGet(name, value)
        : __system_property_get(name, value);
}

VirtualPropertyInfo* virtual_property(const void* property) {
    const Environment* environment = current_environment();
    if (environment == nullptr || environment->property_snapshot == nullptr) {
        return nullptr;
    }
    return const_cast<VirtualPropertyInfo*>(
        environment->property_snapshot->find(property)
    );
}

const void* hook_property_find(const char* name) {
    record_hook_event(kHookEventProperty);
    if (name == nullptr) return nullptr;
    const Environment* environment = current_environment();
    if (environment != nullptr && environment->property_snapshot != nullptr) {
        const auto* property = environment->property_snapshot->find(name);
        if (property != nullptr) return property;
    }
    return g_real.propertyFind
        ? g_real.propertyFind(name)
        : __system_property_find(name);
}

void hook_property_read_callback(
    const void* property,
    PropertyCallback callback,
    void* cookie
) {
    record_hook_event(kHookEventProperty);
    VirtualPropertyInfo* virtual_info = property == nullptr
        ? nullptr
        : virtual_property(property);
    if (virtual_info != nullptr && callback != nullptr) {
        g_property_reads.fetch_add(1, std::memory_order_relaxed);
        callback(
            cookie,
            virtual_info->name.c_str(),
            virtual_info->value.c_str(),
            virtual_info->serial
        );
        return;
    }
    if (g_real.propertyReadCallback) {
        g_real.propertyReadCallback(property, callback, cookie);
    }
}

int hook_property_read(const void* property, char* name, char* value) {
    record_hook_event(kHookEventProperty);
    VirtualPropertyInfo* virtual_info = property == nullptr
        ? nullptr
        : virtual_property(property);
    if (virtual_info != nullptr) {
        if (name != nullptr) {
            const size_t length = std::min(
                virtual_info->name.size(),
                static_cast<size_t>(PROP_NAME_MAX - 1)
            );
            memcpy(name, virtual_info->name.data(), length);
            name[length] = '\0';
        }
        if (value != nullptr) {
            const size_t length = std::min(
                virtual_info->value.size(),
                static_cast<size_t>(PROP_VALUE_MAX - 1)
            );
            memcpy(value, virtual_info->value.data(), length);
            value[length] = '\0';
        }
        g_property_reads.fetch_add(1, std::memory_order_relaxed);
        return static_cast<int>(virtual_info->value.size());
    }
    return g_real.propertyRead
        ? g_real.propertyRead(property, name, value)
        : __system_property_read(
            static_cast<const prop_info*>(property),
            name,
            value
        );
}

uint32_t hook_property_serial(const void* property) {
    VirtualPropertyInfo* virtual_info = property == nullptr
        ? nullptr
        : virtual_property(property);
    if (virtual_info != nullptr) return virtual_info->serial;
    return g_real.propertySerial ? g_real.propertySerial(property) : 0;
}

struct IteratePhdrContext {
    DlIteratePhdrCallback callback;
    void* data;
    char path[4096];
};

int iterate_phdr_callback(
    struct dl_phdr_info* info,
    size_t size,
    void* data
) {
    auto* context = static_cast<IteratePhdrContext*>(data);
    if (context == nullptr || context->callback == nullptr) return 0;
    struct dl_phdr_info copy = *info;
    ssize_t mapped_length = -1;
    if (info->dlpi_name != nullptr &&
        virtual_path_from_actual(
            info->dlpi_name,
            context->path,
            sizeof(context->path),
            &mapped_length
        )) {
        copy.dlpi_name = context->path;
    }
    return context->callback(&copy, size, context->data);
}

int hook_dl_iterate_phdr(DlIteratePhdrCallback callback, void* data) {
    record_hook_event(kHookEventLoader);
    IteratePhdrContext context{callback, data, {}};
    return g_real.dlIteratePhdr
        ? g_real.dlIteratePhdr(iterate_phdr_callback, &context)
        : dl_iterate_phdr(iterate_phdr_callback, &context);
}

int hook_dladdr(const void* address, Dl_info* info) {
    record_hook_event(kHookEventLoader);
    const int result = g_real.dladdr
        ? g_real.dladdr(address, info)
        : dladdr(address, info);
    if (result == 0 || info == nullptr || info->dli_fname == nullptr) {
        return result;
    }
    static thread_local char path[4096];
    ssize_t mapped_length = -1;
    if (virtual_path_from_actual(
            info->dli_fname,
            path,
            sizeof(path),
            &mapped_length
        )) {
        info->dli_fname = path;
    }
    return result;
}

void* hook_dlopen(const char* filename, int flags) {
    record_hook_event(kHookEventLoader);
    g_dlopen_calls.fetch_add(1, std::memory_order_relaxed);
    std::string rewritten;
    const char* actual = rewrite_target_path(filename, &rewritten)
        ? rewritten.c_str()
        : filename;
    void* handle = g_real.dlopen
        ? g_real.dlopen(actual, flags)
        : dlopen(actual, flags);
    if (handle == nullptr &&
        filename != nullptr &&
        strstr(filename, "libjgdtc.so") != nullptr) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "optional packer diagnostic library unavailable: %s",
            filename
        );
        handle = g_real.dlopen
            ? g_real.dlopen(nullptr, RTLD_NOW)
            : dlopen(nullptr, RTLD_NOW);
    }
    if (handle != nullptr && current_environment() != nullptr) {
        refresh_loaded_modules();
    }
    return handle;
}

void* hook_dlsym(void* handle, const char* name) {
    record_hook_event(kHookEventLoader);
    if (name != nullptr) {
        if (void* replacement = replacement_for(name)) return replacement;
    }
    return g_real.dlsym
        ? g_real.dlsym(handle, name)
        : dlsym(handle, name);
}

void* hook_dlvsym(void* handle, const char* name, const char* version) {
    record_hook_event(kHookEventLoader);
    if (name != nullptr) {
        if (void* replacement = replacement_for(name)) return replacement;
    }
    return g_real.dlvsym
        ? g_real.dlvsym(handle, name, version)
        : dlvsym(handle, name, version);
}

void* hook_android_dlopen_ext(
    const char* filename,
    int flags,
    const android_dlextinfo* extinfo
) {
    record_hook_event(kHookEventLoader);
    g_dlopen_calls.fetch_add(1, std::memory_order_relaxed);
    std::string rewritten;
    const char* actual = rewrite_target_path(filename, &rewritten)
        ? rewritten.c_str()
        : filename;
    void* handle = g_real.androidDlopenExt
        ? g_real.androidDlopenExt(actual, flags, extinfo)
        : android_dlopen_ext(actual, flags, extinfo);
    if (handle == nullptr &&
        filename != nullptr &&
        strstr(filename, "libjgdtc.so") != nullptr) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "optional packer diagnostic library unavailable: %s",
            filename
        );
        handle = g_real.dlopen
            ? g_real.dlopen(nullptr, RTLD_NOW)
            : dlopen(nullptr, RTLD_NOW);
    }
    if (handle != nullptr && current_environment() != nullptr) {
        refresh_loaded_modules();
    }
    return handle;
}

long hook_syscall(long number, ...) {
    record_hook_event(kHookEventDirectSyscall);
    record_syscall_event(number);
    va_list args;
    va_start(args, number);
    if (number == SYS_getuid || number == SYS_geteuid) {
        va_end(args);
        const Environment* environment = current_environment();
        return environment ? environment->observed_uid
                           : (g_real.syscall ? g_real.syscall(number)
                                             : syscall(number));
    }

    const auto next_arg = [&args]() -> uintptr_t {
        return reinterpret_cast<uintptr_t>(va_arg(args, void*));
    };

    if (number == SYS_ptrace) {
        const int request = static_cast<int>(next_arg());
        const pid_t pid = static_cast<pid_t>(next_arg());
        auto* address = reinterpret_cast<void*>(next_arg());
        auto* data = reinterpret_cast<void*>(next_arg());
        va_end(args);
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "target-syscall ptrace request=%d pid=%d",
            request,
            pid
        );
        return g_real.syscall
            ? g_real.syscall(number, request, pid, address, data)
            : syscall(number, request, pid, address, data);
    }
    if (number == SYS_tgkill) {
        const pid_t group = static_cast<pid_t>(next_arg());
        const pid_t thread = static_cast<pid_t>(next_arg());
        const int signal = static_cast<int>(next_arg());
        va_end(args);
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "target-syscall tgkill group=%d thread=%d signal=%d",
            group,
            thread,
            signal
        );
        if (group == getpid() && signal == SIGKILL) return 0;
        return g_real.syscall
            ? g_real.syscall(number, group, thread, signal)
            : syscall(number, group, thread, signal);
    }
    if (number == SYS_kill) {
        const pid_t pid = static_cast<pid_t>(next_arg());
        const int signal = static_cast<int>(next_arg());
        va_end(args);
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "target-syscall kill pid=%d signal=%d",
            pid,
            signal
        );
        if (pid == getpid() && signal == SIGKILL) return 0;
        return g_real.syscall
            ? g_real.syscall(number, pid, signal)
            : syscall(number, pid, signal);
    }
    if (number == SYS_openat) {
        const int dirfd = static_cast<int>(next_arg());
        auto* path = reinterpret_cast<const char*>(next_arg());
        const int flags = static_cast<int>(next_arg());
        const mode_t mode = static_cast<mode_t>(next_arg());
        va_end(args);
        PathHookScope scope;
        if (scope.enters_virtual_path() &&
            path != nullptr && path[0] == '/') {
            log_sensitive_path("syscall-openat", path);
            const int virtual_fd = open_virtual_proc(path, flags);
            if (virtual_fd >= 0) return virtual_fd;
        }
        std::string rewritten;
        const bool mapped = rewrite_target_path(path, &rewritten);
        return g_real.syscall
            ? g_real.syscall(
                number,
                dirfd,
                mapped ? rewritten.c_str() : path,
                flags,
                mode
            )
            : syscall(
                number,
                dirfd,
                mapped ? rewritten.c_str() : path,
                flags,
                mode
            );
    }
    if (number == kSysFstatat) {
        const int dirfd = static_cast<int>(next_arg());
        auto* path = reinterpret_cast<const char*>(next_arg());
        auto* value = reinterpret_cast<struct stat*>(next_arg());
        const int flags = static_cast<int>(next_arg());
        va_end(args);
        std::string rewritten;
        const bool mapped = rewrite_target_path(path, &rewritten);
        return g_real.syscall
            ? g_real.syscall(
                number,
                dirfd,
                mapped ? rewritten.c_str() : path,
                value,
                flags
            )
            : syscall(
                number,
                dirfd,
                mapped ? rewritten.c_str() : path,
                value,
                flags
            );
    }
    if (number == SYS_faccessat) {
        const int dirfd = static_cast<int>(next_arg());
        auto* path = reinterpret_cast<const char*>(next_arg());
        const int mode = static_cast<int>(next_arg());
        va_end(args);
        std::string rewritten;
        const bool mapped = rewrite_target_path(path, &rewritten);
        return g_real.syscall
            ? g_real.syscall(number, dirfd, mapped ? rewritten.c_str() : path, mode)
            : syscall(number, dirfd, mapped ? rewritten.c_str() : path, mode);
    }
#ifdef SYS_statx
    if (number == SYS_statx) {
        const int dirfd = static_cast<int>(next_arg());
        auto* path = reinterpret_cast<const char*>(next_arg());
        const int flags = static_cast<int>(next_arg());
        const unsigned int mask = static_cast<unsigned int>(next_arg());
        auto* value = reinterpret_cast<struct statx*>(next_arg());
        va_end(args);
        std::string rewritten;
        const bool mapped = rewrite_target_path(path, &rewritten);
        return g_real.syscall
            ? g_real.syscall(
                number,
                dirfd,
                mapped ? rewritten.c_str() : path,
                flags,
                mask,
                value
            )
            : syscall(
                number,
                dirfd,
                mapped ? rewritten.c_str() : path,
                flags,
                mask,
                value
            );
    }
#endif
    if (number == SYS_readlinkat) {
        const int dirfd = static_cast<int>(next_arg());
        auto* path = reinterpret_cast<const char*>(next_arg());
        auto* buffer = reinterpret_cast<char*>(next_arg());
        const size_t size = static_cast<size_t>(next_arg());
        va_end(args);
        int fd = -1;
        const char* virtual_path = proc_fd_number(path, &fd)
            ? fd_virtual_path(fd)
            : nullptr;
        if (virtual_path != nullptr) {
            if (size == 0) return -1;
            const size_t count = std::min(size - 1, strlen(virtual_path));
            memcpy(buffer, virtual_path, count);
            buffer[count] = '\0';
            return static_cast<long>(count);
        }
        if (fd >= 0) {
            std::array<char, PATH_MAX + 1> actual{};
            const long length = g_real.syscall
                ? g_real.syscall(number, dirfd, path, actual.data(), PATH_MAX)
                : syscall(number, dirfd, path, actual.data(), PATH_MAX);
            if (length > 0) {
                actual[static_cast<size_t>(length)] = '\0';
                ssize_t mapped = -1;
                if (virtual_path_from_actual(actual.data(), buffer, size, &mapped)) {
                    return mapped;
                }
                const size_t count = std::min(size, static_cast<size_t>(length));
                if (count > 0) memcpy(buffer, actual.data(), count);
                return size == 0 ? -1 : static_cast<long>(count);
            }
            return length;
        }
        std::string rewritten;
        const bool mapped = rewrite_target_path(path, &rewritten);
        return g_real.syscall
            ? g_real.syscall(
                number,
                dirfd,
                mapped ? rewritten.c_str() : path,
                buffer,
                size
            )
            : syscall(
                number,
                dirfd,
                mapped ? rewritten.c_str() : path,
                buffer,
                size
            );
    }
    if (number == SYS_close) {
        const int fd = static_cast<int>(next_arg());
        va_end(args);
        erase_fd_path(fd);
        return g_real.syscall ? g_real.syscall(number, fd) : syscall(number, fd);
    }
    if (number == SYS_dup) {
        const int fd = static_cast<int>(next_arg());
        va_end(args);
        const long result = g_real.syscall
            ? g_real.syscall(number, fd)
            : syscall(number, fd);
        if (result >= 0) copy_fd_path(fd, static_cast<int>(result));
        return result;
    }
    if (number == SYS_dup3) {
        const int old_fd = static_cast<int>(next_arg());
        const int new_fd = static_cast<int>(next_arg());
        const int flags = static_cast<int>(next_arg());
        va_end(args);
        const long result = g_real.syscall
            ? g_real.syscall(number, old_fd, new_fd, flags)
            : syscall(number, old_fd, new_fd, flags);
        if (result >= 0) copy_fd_path(old_fd, static_cast<int>(result));
        return result;
    }

    const uintptr_t argument1 = next_arg();
    const uintptr_t argument2 = next_arg();
    const uintptr_t argument3 = next_arg();
    const uintptr_t argument4 = next_arg();
    const uintptr_t argument5 = next_arg();
    const uintptr_t argument6 = next_arg();
    va_end(args);
    return g_real.syscall
        ? g_real.syscall(
            number,
            argument1,
            argument2,
            argument3,
            argument4,
            argument5,
            argument6
        )
        : syscall(
            number,
            argument1,
            argument2,
            argument3,
            argument4,
            argument5,
            argument6
        );
}

void* replacement_for(const char* name) {
    if (strcmp(name, "getuid") == 0) return reinterpret_cast<void*>(hook_getuid);
    if (strcmp(name, "geteuid") == 0) return reinterpret_cast<void*>(hook_geteuid);
    if (strcmp(name, "open") == 0) return reinterpret_cast<void*>(hook_open);
    if (strcmp(name, "open64") == 0) return reinterpret_cast<void*>(hook_open64);
    if (strcmp(name, "__open_2") == 0) return reinterpret_cast<void*>(hook_open_2);
    if (strcmp(name, "openat") == 0) return reinterpret_cast<void*>(hook_openat);
    if (strcmp(name, "openat64") == 0) return reinterpret_cast<void*>(hook_openat64);
    if (strcmp(name, "__openat_2") == 0) return reinterpret_cast<void*>(hook_openat_2);
    if (strcmp(name, "access") == 0) return reinterpret_cast<void*>(hook_access);
    if (strcmp(name, "faccessat") == 0) return reinterpret_cast<void*>(hook_faccessat);
    if (strcmp(name, "stat") == 0) return reinterpret_cast<void*>(hook_stat);
    if (strcmp(name, "lstat") == 0) return reinterpret_cast<void*>(hook_lstat);
    if (strcmp(name, "fstatat") == 0) return reinterpret_cast<void*>(hook_fstatat);
    if (strcmp(name, "statx") == 0) return reinterpret_cast<void*>(hook_statx);
    if (strcmp(name, "readlink") == 0) return reinterpret_cast<void*>(hook_readlink);
    if (strcmp(name, "readlinkat") == 0) {
        return reinterpret_cast<void*>(hook_readlinkat);
    }
    if (strcmp(name, "__readlink_chk") == 0) {
        return reinterpret_cast<void*>(hook_readlink_chk);
    }
    if (strcmp(name, "__readlinkat_chk") == 0) {
        return reinterpret_cast<void*>(hook_readlinkat_chk);
    }
    if (strcmp(name, "opendir") == 0) return reinterpret_cast<void*>(hook_opendir);
    if (strcmp(name, "realpath") == 0) return reinterpret_cast<void*>(hook_realpath);
    if (strcmp(name, "canonicalize_file_name") == 0) {
        return reinterpret_cast<void*>(hook_canonicalize_file_name);
    }
    if (strcmp(name, "close") == 0) return reinterpret_cast<void*>(hook_close);
    if (strcmp(name, "dup") == 0) return reinterpret_cast<void*>(hook_dup);
    if (strcmp(name, "dup2") == 0) return reinterpret_cast<void*>(hook_dup2);
    if (strcmp(name, "dup3") == 0) return reinterpret_cast<void*>(hook_dup3);
    if (strcmp(name, "fcntl") == 0) return reinterpret_cast<void*>(hook_fcntl);
    if (strcmp(name, "syscall") == 0) return reinterpret_cast<void*>(hook_syscall);
    if (strcmp(name, "fopen") == 0) return reinterpret_cast<void*>(hook_fopen);
    if (strcmp(name, "fopen64") == 0) return reinterpret_cast<void*>(hook_fopen64);
    if (strcmp(name, "fclose") == 0) return reinterpret_cast<void*>(hook_fclose);
    if (strcmp(name, "__system_property_get") == 0) {
        return reinterpret_cast<void*>(hook_property_get);
    }
    if (strcmp(name, "__system_property_find") == 0) {
        return reinterpret_cast<void*>(hook_property_find);
    }
    if (strcmp(name, "__system_property_read_callback") == 0) {
        return reinterpret_cast<void*>(hook_property_read_callback);
    }
    if (strcmp(name, "__system_property_read") == 0) {
        return reinterpret_cast<void*>(hook_property_read);
    }
    if (strcmp(name, "__system_property_serial") == 0) {
        return reinterpret_cast<void*>(hook_property_serial);
    }
    if (strcmp(name, "dlopen") == 0 || strcmp(name, "__loader_dlopen") == 0) {
        return reinterpret_cast<void*>(hook_dlopen);
    }
    if (strcmp(name, "dlsym") == 0) {
        return reinterpret_cast<void*>(hook_dlsym);
    }
    if (strcmp(name, "dlvsym") == 0) {
        return reinterpret_cast<void*>(hook_dlvsym);
    }
    if (strcmp(name, "android_dlopen_ext") == 0 ||
        strcmp(name, "__loader_android_dlopen_ext") == 0) {
        return reinterpret_cast<void*>(hook_android_dlopen_ext);
    }
    if (strcmp(name, "dl_iterate_phdr") == 0) {
        return reinterpret_cast<void*>(hook_dl_iterate_phdr);
    }
    if (strcmp(name, "dladdr") == 0) {
        return reinterpret_cast<void*>(hook_dladdr);
    }
    if (strcmp(name, "tgkill") == 0) {
        return reinterpret_cast<void*>(hook_tgkill_entry);
    }
    return nullptr;
}

void* loader_replacement_for(const char* name) {
    if (name == nullptr) return nullptr;
    if (strcmp(name, "dlopen") == 0 || strcmp(name, "__loader_dlopen") == 0) {
        return reinterpret_cast<void*>(hook_dlopen);
    }
    if (strcmp(name, "dlsym") == 0) {
        return reinterpret_cast<void*>(hook_dlsym);
    }
    if (strcmp(name, "dlvsym") == 0) {
        return reinterpret_cast<void*>(hook_dlvsym);
    }
    if (strcmp(name, "android_dlopen_ext") == 0 ||
        strcmp(name, "__loader_android_dlopen_ext") == 0) {
        return reinterpret_cast<void*>(hook_android_dlopen_ext);
    }
    return nullptr;
}

}  // namespace nestspace
