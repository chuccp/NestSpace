#include "native_environment_internal.h"

#include <android/log.h>
#include <csignal>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

namespace {

constexpr char kLogTag[] = "NestSpaceCrash";
constexpr int kMaxFrames = 64;
constexpr int kSignalStackSize = 64 * 1024;

int g_trace_fd = -1;
stack_t g_signal_stack{};

struct BacktraceState {
    uintptr_t* frames;
    int count;
    int capacity;
};

_Unwind_Reason_Code collect_frame(
    struct _Unwind_Context* context,
    void* user_data
) {
    auto* state = static_cast<BacktraceState*>(user_data);
    if (state->count >= state->capacity) return _URC_END_OF_STACK;
    const uintptr_t frame = static_cast<uintptr_t>(_Unwind_GetIP(context));
    if (frame != 0) state->frames[state->count++] = frame;
    return _URC_NO_REASON;
}

void write_hex(uintptr_t value) {
    if (g_trace_fd < 0) return;
    char buffer[2 + sizeof(uintptr_t) * 2 + 2];
    buffer[0] = '0';
    buffer[1] = 'x';
    size_t offset = 2;
    bool started = false;
    for (int shift = static_cast<int>(sizeof(uintptr_t) * 8) - 4;
         shift >= 0;
         shift -= 4) {
        const unsigned int digit = (value >> shift) & 0xf;
        if (!started && digit == 0 && shift != 0) continue;
        started = true;
        buffer[offset++] = static_cast<char>(
            digit < 10 ? '0' + digit : 'a' + digit - 10
        );
    }
    buffer[offset++] = '\n';
    write(g_trace_fd, buffer, offset);
}

void crash_handler(int sig, siginfo_t* info, void* raw_context) {
    if (g_trace_fd >= 0) {
        const char header[] = "signal\n";
        write(g_trace_fd, header, sizeof(header) - 1);
        write_hex(static_cast<uintptr_t>(sig));
        if (info != nullptr) {
            const char fault_header[] = "fault\n";
            write(g_trace_fd, fault_header, sizeof(fault_header) - 1);
            write_hex(reinterpret_cast<uintptr_t>(info->si_addr));
        }
        if (raw_context != nullptr) {
            auto* context = static_cast<ucontext_t*>(raw_context);
            const char pc_header[] = "pc\n";
            const char sp_header[] = "sp\n";
            const char regs_header[] = "regs\n";
            const char code_header[] = "code\n";
            write(g_trace_fd, pc_header, sizeof(pc_header) - 1);
            write_hex(static_cast<uintptr_t>(context->uc_mcontext.pc));
            write(g_trace_fd, sp_header, sizeof(sp_header) - 1);
            write_hex(static_cast<uintptr_t>(context->uc_mcontext.sp));
            write(g_trace_fd, regs_header, sizeof(regs_header) - 1);
            for (int index = 0; index < 31; ++index) {
                write_hex(
                    static_cast<uintptr_t>(context->uc_mcontext.regs[index])
                );
            }
            const uintptr_t pc = context->uc_mcontext.pc;
            const uintptr_t code_start = (pc & ~0xffU) - 0x100;
            write(g_trace_fd, code_header, sizeof(code_header) - 1);
            write(
                g_trace_fd,
                reinterpret_cast<const void*>(code_start),
                0x300
            );
        }

        uintptr_t frames[kMaxFrames]{};
        BacktraceState state{frames, 0, kMaxFrames};
        _Unwind_Backtrace(collect_frame, &state);
        for (int index = 0; index < state.count; ++index) {
            write_hex(frames[index]);
        }
        fsync(g_trace_fd);
    }

    signal(sig, SIG_DFL);
    raise(sig);
}

void install_handler(int signal_number) {
    struct sigaction action {};
    action.sa_sigaction = crash_handler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&action.sa_mask);
    sigaction(signal_number, &action, nullptr);
}

bool install_process_guard() {
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_tgkill, 1, 0),
        BPF_STMT(
            BPF_RET | BPF_K,
            SECCOMP_RET_ERRNO | (EPERM & SECCOMP_RET_DATA)
        ),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program {
        static_cast<unsigned short>(
            sizeof(filter) / sizeof(filter[0])
        ),
        filter
    };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "PR_SET_NO_NEW_PRIVS failed: %s",
            strerror(errno)
        );
        return false;
    }
    if (syscall(
            SYS_seccomp,
            SECCOMP_SET_MODE_FILTER,
            SECCOMP_FILTER_FLAG_TSYNC,
            &program
        ) == 0) {
        return true;
    }
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) != 0) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "PR_SET_SECCOMP failed: %s",
            strerror(errno)
        );
        return false;
    }
    return true;
}

bool install_kill_guard() {
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_kill, 2, 0),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_tgkill, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(
            BPF_RET | BPF_K,
            SECCOMP_RET_ERRNO | (EPERM & SECCOMP_RET_DATA)
        ),
    };
    struct sock_fprog program {
        static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0])),
        filter
    };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) return false;
    if (syscall(
            SYS_seccomp,
            SECCOMP_SET_MODE_FILTER,
            SECCOMP_FILTER_FLAG_TSYNC,
            &program
        ) == 0) {
        return true;
    }
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "seccomp TSYNC failed: %s; falling back to current thread",
        strerror(errno)
    );
    return prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) == 0;
}

}  // namespace

extern "C" void install_native_crash_trace(const char* path) {
    if (path == nullptr || path[0] == '\0') return;
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
    install_handler(SIGABRT);
    install_handler(SIGSEGV);
    install_handler(SIGBUS);
    install_handler(SIGILL);
    nestspace::install_process_signal_hooks();
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

extern "C" bool install_native_kill_guard() {
    return install_kill_guard();
}
