#include "native_environment_internal.h"

#include <android/log.h>
#include <algorithm>
#include <csignal>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <linux/filter.h>
#include <linux/futex.h>
#include <linux/seccomp.h>
#include <limits.h>
#include <sys/prctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <sys/uio.h>
#include <pthread.h>
#include <sched.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

namespace {

constexpr char kLogTag[] = "NestSpaceCrash";
constexpr int kMaxFrames = 64;
constexpr int kSignalStackSize = 64 * 1024;

int g_trace_fd = -1;
stack_t g_signal_stack{};
struct sigaction g_previous_segv_action {};
bool g_packer_relocation_guard_installed = false;
bool g_packer_relocation_guard_requested = false;
std::atomic<uintptr_t> g_watch_start{0};
std::atomic<uintptr_t> g_watch_size{0};
std::atomic<uintptr_t> g_watch_page{0};
int g_syscall_broker_request_read = -1;
int g_syscall_broker_request_write = -1;
int g_syscall_broker_response_read = -1;
int g_syscall_broker_response_write = -1;
int g_self_mem_fd = -1;
pid_t g_syscall_broker_process = -1;
pid_t g_syscall_broker_child = -1;
std::atomic_flag g_syscall_broker_busy = ATOMIC_FLAG_INIT;
std::atomic<bool> g_syscall_gateway_active{false};
std::atomic<bool> g_syscall_gateway_pause{false};

struct GatewayFdTemplate {
    char path[PATH_MAX];
    int fd;
    int flags;
};

std::array<GatewayFdTemplate, 12> g_gateway_fd_templates{};
std::atomic<int> g_gateway_fd_template_count{0};

enum GatewayRequestType : int {
    kGatewayOpenat = 1,
    kGatewayRtSigaction = 2,
    kGatewayVirtualProc = 3,
};

struct GatewayRequest {
    int type;
    int dirfd;
    int flags;
    int signal_number;
    pid_t client_pid;
    mode_t mode;
    uintptr_t action_address;
    uintptr_t old_action_address;
    size_t signal_set_size;
    size_t action_size;
    unsigned char action[64];
    char path[PATH_MAX];
};

struct GatewayResponse {
    long result;
    size_t action_size;
    unsigned char action[64];
};

bool diagnostic_enabled(const char* environment, const char* property) {
    if (getenv(environment) != nullptr) return true;
    char value[PROP_VALUE_MAX] = {};
    __system_property_get(property, value);
    return strcmp(value, "1") == 0;
}

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

void write_module(
    const char* label,
    uintptr_t address
) {
    Dl_info info{};
    const bool resolved = dladdr(
        reinterpret_cast<void*>(address),
        &info
    ) != 0;
    const uintptr_t base = resolved
        ? reinterpret_cast<uintptr_t>(info.dli_fbase)
        : 0;
    const char marker[] = "module\n";
    write(g_trace_fd, marker, sizeof(marker) - 1);
    write(g_trace_fd, label, strlen(label));
    write(g_trace_fd, "\n", 1);
    write_hex(address);
    write_hex(base);
    write_hex(base == 0 ? 0 : address - base);
    if (resolved && info.dli_fname != nullptr) {
        write(g_trace_fd, info.dli_fname, strlen(info.dli_fname));
    }
    write(g_trace_fd, "\n", 1);
}

void write_maps() {
    static constexpr char kMapsPath[] = "/proc/self/maps";
    const long fd = syscall(
        SYS_openat,
        AT_FDCWD,
        kMapsPath,
        O_RDONLY | O_CLOEXEC
    );
    if (fd < 0) return;
    const char marker[] = "maps\n";
    write(g_trace_fd, marker, sizeof(marker) - 1);
    char buffer[4096];
    while (true) {
        const long count = syscall(SYS_read, fd, buffer, sizeof(buffer));
        if (count <= 0) break;
        write(g_trace_fd, buffer, static_cast<size_t>(count));
    }
    syscall(SYS_close, fd);
}

bool write_process_memory(
    const char* label,
    uintptr_t address,
    size_t size
) {
    if (address == 0 || size == 0) return false;
    char buffer[1024];
    const size_t count = std::min(size, sizeof(buffer));
    struct iovec local {
        buffer,
        count
    };
    struct iovec remote {
        reinterpret_cast<void*>(address),
        count
    };
    const long copied = syscall(
        SYS_process_vm_readv,
        getpid(),
        &local,
        1,
        &remote,
        1,
        0
    );
    if (copied <= 0) return false;
    write(g_trace_fd, label, strlen(label));
    write(g_trace_fd, "\n", 1);
    write_hex(address);
    write(g_trace_fd, buffer, static_cast<size_t>(copied));
    return true;
}

[[noreturn]] __attribute__((naked)) void guard_exit_thread() {
    __asm__ __volatile__(
        "bti c\n"
        "mov x0, #0\n"
        "mov x8, #93\n"
        "svc #0\n"
        "brk #0\n"
    );
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
            write_module("pc", context->uc_mcontext.pc);
            write_module("lr", context->uc_mcontext.regs[30]);
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
            struct iovec local {
                reinterpret_cast<void*>(code_start),
                0x300
            };
            struct iovec remote {
                reinterpret_cast<void*>(code_start),
                0x300
            };
            const long copied = syscall(
                SYS_process_vm_readv,
                getpid(),
                &local,
                1,
                &remote,
                1,
                0
            );
            if (copied == 0x300) {
                write(g_trace_fd, local.iov_base, 0x300);
            }
            const uintptr_t object = context->uc_mcontext.regs[0];
            write_process_memory("object", object, 0x100);
            struct iovec object_local {
                local.iov_base,
                0x100
            };
            struct iovec object_remote {
                reinterpret_cast<void*>(object),
                0x100
            };
            const long object_size = syscall(
                SYS_process_vm_readv,
                getpid(),
                &object_local,
                1,
                &object_remote,
                1,
                0
            );
            if (object_size >= 0x40) {
                const auto* object_words =
                    static_cast<const uintptr_t*>(local.iov_base);
                const uintptr_t table = object_words[0x38 / sizeof(uintptr_t)];
                const uintptr_t index = context->uc_mcontext.regs[1];
                write_process_memory(
                    "table",
                    table,
                    0x100
                );
                write_process_memory(
                    "table_entry",
                    table + index * sizeof(uintptr_t),
                    0x100
                );
            }
            write_process_memory("x19", context->uc_mcontext.regs[19], 0x100);
            write_process_memory("x21", context->uc_mcontext.regs[21], 0x100);
            write_process_memory("x22", context->uc_mcontext.regs[22], 0x100);
            write_process_memory("x27", context->uc_mcontext.regs[27], 0x100);
            const char stack_header[] = "stack\n";
            write(g_trace_fd, stack_header, sizeof(stack_header) - 1);
            write(
                g_trace_fd,
                reinterpret_cast<const void*>(context->uc_mcontext.sp),
                0x2000
            );
            write_maps();
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

void packer_relocation_handler(
    int signal_number,
    siginfo_t* info,
    void* raw_context
) {
    auto* context = static_cast<ucontext_t*>(raw_context);
    const uintptr_t instruction = context == nullptr
        ? 0
        : *reinterpret_cast<const uint32_t*>(context->uc_mcontext.pc);
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "packer guard entered signal=%d addr=%p pc=%p x8=%p insn=%08lx",
        signal_number,
        info == nullptr ? nullptr : info->si_addr,
        context == nullptr
            ? nullptr
            : reinterpret_cast<void*>(context->uc_mcontext.pc),
        context == nullptr
            ? nullptr
            : reinterpret_cast<void*>(context->uc_mcontext.regs[8]),
        static_cast<unsigned long>(instruction)
    );
    if (context != nullptr) {
        Dl_info module{};
        if (dladdr(
                reinterpret_cast<void*>(context->uc_mcontext.pc),
                &module
            ) != 0) {
            const uintptr_t base = module.dli_fbase == nullptr
                ? 0
                : reinterpret_cast<uintptr_t>(module.dli_fbase);
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "packer module=%s base=%p offset=%p",
                module.dli_fname == nullptr ? "?" : module.dli_fname,
                reinterpret_cast<void*>(base),
                reinterpret_cast<void*>(
                    context->uc_mcontext.pc - base
                )
            );
        }
    }
    if (info != nullptr) {
        const uintptr_t fault_address =
            reinterpret_cast<uintptr_t>(info->si_addr);
        const uintptr_t watch_start =
            g_watch_start.load(std::memory_order_acquire);
        const uintptr_t watch_size =
            g_watch_size.load(std::memory_order_acquire);
        const uintptr_t watch_page =
            g_watch_page.load(std::memory_order_acquire);
        if (watch_page != 0 &&
            fault_address >= watch_start &&
            fault_address < watch_start + watch_size) {
            const uint32_t fault_instruction = context == nullptr
                ? 0
                : *reinterpret_cast<const uint32_t*>(
                    context->uc_mcontext.pc
                );
            if (g_trace_fd >= 0) {
                static constexpr char kHeader[] = "object-watch\n";
                write(g_trace_fd, kHeader, sizeof(kHeader) - 1);
                write_hex(fault_address);
                write_hex(
                    context == nullptr
                        ? 0
                        : static_cast<uintptr_t>(
                            context->uc_mcontext.pc
                        )
                );
                write_hex(
                    static_cast<uintptr_t>(fault_instruction)
                );
                for (int index = 0; index < 31; ++index) {
                    write_hex(
                        context == nullptr
                            ? 0
                            : static_cast<uintptr_t>(
                                context->uc_mcontext.regs[index]
                            )
                    );
                }
                if (context != nullptr) {
                    const uintptr_t pc =
                        static_cast<uintptr_t>(context->uc_mcontext.pc);
                    if (pc >= 0x80) {
                        write_process_memory(
                            "object-watch-code",
                            pc - 0x80,
                            0x200
                        );
                    }
                    write_process_memory(
                        "object-watch-stack",
                        static_cast<uintptr_t>(context->uc_mcontext.sp),
                        0x1000
                    );
                    write_module("object-watch-pc", pc);
                }
                fsync(g_trace_fd);
            }
            const long page_size = sysconf(_SC_PAGESIZE);
            int restore_result = -1;
            int restore_errno = 0;
            if (page_size > 0) {
                restore_result = mprotect(
                    reinterpret_cast<void*>(watch_page),
                    static_cast<size_t>(page_size),
                    PROT_READ | PROT_WRITE
                );
                restore_errno = errno;
            }
            bool emulated = false;
            if (restore_result == 0 && context != nullptr) {
                const uint32_t opcode = fault_instruction & 0xffc00000U;
                const uint32_t source = fault_instruction & 0x1fU;
                const uint32_t base = (fault_instruction >> 5U) & 0x1fU;
                const uint32_t immediate =
                    (fault_instruction >> 10U) & 0xfffU;
                size_t width = 0;
                size_t scale = 0;
                if (opcode == 0xf9000000U) {
                    width = 8;
                    scale = 8;
                } else if (opcode == 0xb9000000U) {
                    width = 4;
                    scale = 4;
                } else if (opcode == 0x79000000U) {
                    width = 2;
                    scale = 2;
                } else if (opcode == 0x39000000U) {
                    width = 1;
                    scale = 1;
                }
                if (width != 0 && source != 31 && base != 31) {
                    constexpr uintptr_t kPointerTagMask =
                        (1ULL << 56) - 1;
                    const uintptr_t base_address =
                        static_cast<uintptr_t>(
                            context->uc_mcontext.regs[base]
                        ) & kPointerTagMask;
                    const uintptr_t tagged_base_address =
                        static_cast<uintptr_t>(
                            context->uc_mcontext.regs[base]
                        );
                    const uintptr_t destination =
                        base_address + immediate * scale;
                    const uintptr_t tagged_destination =
                        tagged_base_address + immediate * scale;
                    const uintptr_t value =
                        static_cast<uintptr_t>(
                            context->uc_mcontext.regs[source]
                        );
                    if (destination >= watch_page &&
                        destination < watch_page +
                            static_cast<uintptr_t>(page_size)) {
                        if (width == 8) {
                            __asm__ __volatile__(
                                "str %1, [%0]"
                                :
                                : "r"(tagged_destination), "r"(value)
                                : "memory"
                            );
                        } else if (width == 4) {
                            const uint32_t narrow =
                                static_cast<uint32_t>(value);
                            __asm__ __volatile__(
                                "str %w1, [%0]"
                                :
                                : "r"(tagged_destination), "r"(narrow)
                                : "memory"
                            );
                        } else if (width == 2) {
                            const uint16_t narrow =
                                static_cast<uint16_t>(value);
                            __asm__ __volatile__(
                                "strh %w1, [%0]"
                                :
                                : "r"(tagged_destination), "r"(narrow)
                                : "memory"
                            );
                        } else {
                            const uint8_t narrow =
                                static_cast<uint8_t>(value);
                            __asm__ __volatile__(
                                "strb %w1, [%0]"
                                :
                                : "r"(tagged_destination), "r"(narrow)
                                : "memory"
                            );
                        }
                        context->uc_mcontext.pc += 4;
                        emulated = true;
                    }
                } else if (width != 0 && source == 31 && base != 31) {
                    constexpr uintptr_t kPointerTagMask =
                        (1ULL << 56) - 1;
                    const uintptr_t tagged_base_address =
                        static_cast<uintptr_t>(
                            context->uc_mcontext.regs[base]
                        );
                    const uintptr_t destination =
                        (tagged_base_address & kPointerTagMask) +
                        immediate * scale;
                    const uintptr_t tagged_destination =
                        tagged_base_address + immediate * scale;
                    if (destination >= watch_page &&
                        destination < watch_page +
                            static_cast<uintptr_t>(page_size)) {
                        if (width == 8) {
                            __asm__ __volatile__(
                                "str xzr, [%0]"
                                :
                                : "r"(tagged_destination)
                                : "memory"
                            );
                        } else if (width == 4) {
                            __asm__ __volatile__(
                                "str wzr, [%0]"
                                :
                                : "r"(tagged_destination)
                                : "memory"
                            );
                        } else if (width == 2) {
                            __asm__ __volatile__(
                                "strh wzr, [%0]"
                                :
                                : "r"(tagged_destination)
                                : "memory"
                            );
                        } else {
                            __asm__ __volatile__(
                                "strb wzr, [%0]"
                                :
                                : "r"(tagged_destination)
                                : "memory"
                            );
                        }
                        context->uc_mcontext.pc += 4;
                        emulated = true;
                    }
                }
            }
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "object-watch restore page=%p result=%d errno=%d "
                "emulated=%d",
                reinterpret_cast<void*>(watch_page),
                restore_result,
                restore_errno,
                emulated ? 1 : 0
            );
            if (emulated) {
                mprotect(
                    reinterpret_cast<void*>(watch_page),
                    static_cast<size_t>(page_size),
                    PROT_READ
                );
            } else {
                g_watch_page.store(0, std::memory_order_release);
            }
            return;
        }
    }
    if (g_trace_fd >= 0 && context != nullptr) {
        static constexpr char kHeader[] = "runtime-fault\n";
        write(g_trace_fd, kHeader, sizeof(kHeader) - 1);
        write_hex(static_cast<uintptr_t>(signal_number));
        write_hex(
            info == nullptr
                ? 0
                : reinterpret_cast<uintptr_t>(info->si_addr)
        );
        write_hex(static_cast<uintptr_t>(context->uc_mcontext.pc));
        write_hex(static_cast<uintptr_t>(instruction));
        for (int index = 0; index < 31; ++index) {
            write_hex(static_cast<uintptr_t>(
                context->uc_mcontext.regs[index]
            ));
        }
        write_process_memory(
            "x19",
            static_cast<uintptr_t>(context->uc_mcontext.regs[19]),
            0x100
        );
        write_process_memory(
            "x0",
            static_cast<uintptr_t>(context->uc_mcontext.regs[0]),
            0x100
        );
        write_process_memory(
            "x21",
            static_cast<uintptr_t>(context->uc_mcontext.regs[21]),
            0x100
        );
        write_process_memory(
            "x27",
            static_cast<uintptr_t>(context->uc_mcontext.regs[27]),
            0x100
        );
        const uintptr_t pc =
            static_cast<uintptr_t>(context->uc_mcontext.pc);
        if (pc >= 0x100) {
            write_process_memory("runtime-code-before", pc - 0x100, 0x300);
        }
        write_process_memory(
            "runtime-code-at",
            pc,
            0x100
        );
        const uintptr_t caller =
            static_cast<uintptr_t>(context->uc_mcontext.regs[30]);
        if (caller >= 0x100) {
            write_process_memory("runtime-caller", caller - 0x100, 0x300);
        }
        Dl_info module{};
        if (dladdr(
                reinterpret_cast<void*>(context->uc_mcontext.pc),
                &module
            ) != 0 &&
            module.dli_fbase != nullptr) {
            write_module("runtime", context->uc_mcontext.pc);
        }
        write_maps();
        fsync(g_trace_fd);
    }
    if (g_previous_segv_action.sa_flags & SA_SIGINFO) {
        if (g_previous_segv_action.sa_sigaction != nullptr) {
            g_previous_segv_action.sa_sigaction(
                signal_number,
                info,
                raw_context
            );
            return;
        }
    } else if (
        g_previous_segv_action.sa_handler != SIG_DFL &&
        g_previous_segv_action.sa_handler != SIG_IGN &&
        g_previous_segv_action.sa_handler != nullptr
    ) {
        g_previous_segv_action.sa_handler(signal_number);
        return;
    }
    signal(signal_number, SIG_DFL);
    raise(signal_number);
}

void install_packer_relocation_guard() {
    if (g_packer_relocation_guard_installed) return;
    struct sigaction action {};
    action.sa_sigaction = packer_relocation_handler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&action.sa_mask);
    if (sigaction(
            SIGSEGV,
            &action,
            &g_previous_segv_action
        ) == 0) {
        g_packer_relocation_guard_installed = true;
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "packer relocation guard installed handler=%p previous=%p",
            reinterpret_cast<void*>(packer_relocation_handler),
            reinterpret_cast<void*>(g_previous_segv_action.sa_sigaction)
        );
    }
}

void log_packer_relocation_guard_state(const char* stage) {
    struct sigaction current {};
    sigset_t blocked {};
    const int action_result = sigaction(SIGSEGV, nullptr, &current);
    const int mask_result = sigprocmask(SIG_SETMASK, nullptr, &blocked);
    const bool active = action_result == 0 &&
        (current.sa_flags & SA_SIGINFO) != 0 &&
        current.sa_sigaction == packer_relocation_handler;
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "packer guard state stage=%s active=%d handler=%p flags=%08x "
        "mask_result=%d segv_blocked=%d",
        stage,
        active ? 1 : 0,
        reinterpret_cast<void*>(current.sa_sigaction),
        current.sa_flags,
        mask_result,
        mask_result == 0 ? sigismember(&blocked, SIGSEGV) : -1
    );
}

bool read_user_string(
    uintptr_t address,
    char* buffer,
    size_t capacity
) {
    if (address == 0 || buffer == nullptr || capacity < 2) return false;
    const auto* source = reinterpret_cast<const char*>(address);
    for (size_t index = 0; index + 1 < capacity; ++index) {
        const char value = source[index];
        buffer[index] = value;
        if (value == '\0') return true;
    }
    buffer[capacity - 1] = '\0';
    return false;
}

bool is_virtual_proc_gateway_path(const char* path) {
    if (path == nullptr) return false;
    if (strncmp(path, "/proc/self/", 11) == 0 ||
        strncmp(path, "/proc/thread-self/", 18) == 0) {
        return true;
    }
    char process_prefix[64] = {};
    snprintf(
        process_prefix,
        sizeof(process_prefix),
        "/proc/%d/",
        static_cast<int>(getpid())
    );
    return strncmp(
        path,
        process_prefix,
        strlen(process_prefix)
    ) == 0;
}

bool send_gateway_response(
    int socket_fd,
    const GatewayResponse* response,
    int result_fd
) {
    if (socket_fd < 0 || response == nullptr) return false;
    struct iovec io {
        const_cast<GatewayResponse*>(response),
        sizeof(*response)
    };
    char control[CMSG_SPACE(sizeof(int))] {};
    struct msghdr message {};
    message.msg_iov = &io;
    message.msg_iovlen = 1;
    if (result_fd >= 0) {
        message.msg_control = control;
        message.msg_controllen = sizeof(control);
        struct cmsghdr* header = CMSG_FIRSTHDR(&message);
        header->cmsg_level = SOL_SOCKET;
        header->cmsg_type = SCM_RIGHTS;
        header->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(header), &result_fd, sizeof(result_fd));
    }
    const long result = syscall(
        SYS_sendmsg,
        socket_fd,
        &message,
        0
    );
    return result == static_cast<long>(sizeof(*response));
}

bool receive_gateway_response(
    int socket_fd,
    GatewayResponse* response,
    int* result_fd
) {
    if (socket_fd < 0 || response == nullptr || result_fd == nullptr) {
        return false;
    }
    *result_fd = -1;
    struct iovec io {
        response,
        sizeof(*response)
    };
    char control[CMSG_SPACE(sizeof(int))] {};
    struct msghdr message {};
    message.msg_iov = &io;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    const long result = syscall(
        SYS_recvmsg,
        socket_fd,
        &message,
#if defined(MSG_CMSG_CLOEXEC)
        MSG_CMSG_CLOEXEC
#else
        0
#endif
    );
    if (result != static_cast<long>(sizeof(*response))) return false;
    for (struct cmsghdr* header = CMSG_FIRSTHDR(&message);
         header != nullptr;
         header = CMSG_NXTHDR(&message, header)) {
        if (header->cmsg_level == SOL_SOCKET &&
            header->cmsg_type == SCM_RIGHTS &&
            header->cmsg_len >= CMSG_LEN(sizeof(int))) {
            int received_fd = -1;
            memcpy(&received_fd, CMSG_DATA(header), sizeof(received_fd));
            *result_fd = received_fd;
            break;
        }
    }
    return true;
}

bool send_gateway_request(
    int socket_fd,
    const GatewayRequest* request,
    int reply_socket
) {
    if (socket_fd < 0 || request == nullptr || reply_socket < 0) {
        return false;
    }
    struct iovec io {
        const_cast<GatewayRequest*>(request),
        sizeof(*request)
    };
    char control[CMSG_SPACE(sizeof(int))] {};
    struct msghdr message {};
    message.msg_iov = &io;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    struct cmsghdr* header = CMSG_FIRSTHDR(&message);
    header->cmsg_level = SOL_SOCKET;
    header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(header), &reply_socket, sizeof(reply_socket));
    const long result = syscall(
        SYS_sendmsg,
        socket_fd,
        &message,
        0
    );
    return result == static_cast<long>(sizeof(*request));
}

bool receive_gateway_request(
    int socket_fd,
    GatewayRequest* request,
    int* reply_socket
) {
    if (socket_fd < 0 || request == nullptr || reply_socket == nullptr) {
        return false;
    }
    *reply_socket = -1;
    struct iovec io {
        request,
        sizeof(*request)
    };
    char control[CMSG_SPACE(sizeof(int))] {};
    struct msghdr message {};
    message.msg_iov = &io;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    const long result = syscall(
        SYS_recvmsg,
        socket_fd,
        &message,
#if defined(MSG_CMSG_CLOEXEC)
        MSG_CMSG_CLOEXEC
#else
        0
#endif
    );
    if (result != static_cast<long>(sizeof(*request))) return false;
    for (struct cmsghdr* header = CMSG_FIRSTHDR(&message);
         header != nullptr;
         header = CMSG_NXTHDR(&message, header)) {
        if (header->cmsg_level == SOL_SOCKET &&
            header->cmsg_type == SCM_RIGHTS &&
            header->cmsg_len >= CMSG_LEN(sizeof(int))) {
            memcpy(reply_socket, CMSG_DATA(header), sizeof(*reply_socket));
            return true;
        }
    }
    return false;
}

void add_gateway_fd_template(const std::string& path, int flags) {
    if (path.empty()) return;
    const int index = g_gateway_fd_template_count.load(
        std::memory_order_acquire
    );
    if (index >= static_cast<int>(g_gateway_fd_templates.size())) return;
    const int fd = static_cast<int>(syscall(
        SYS_openat,
        AT_FDCWD,
        path.c_str(),
        flags,
        0
    ));
    if (fd < 0) return;
    auto& slot = g_gateway_fd_templates[static_cast<size_t>(index)];
    strncpy(slot.path, path.c_str(), sizeof(slot.path) - 1);
    slot.fd = fd;
    slot.flags = flags;
    g_gateway_fd_template_count.store(index + 1, std::memory_order_release);
}

long duplicate_gateway_fd_template(const char* path, int flags) {
    if (path == nullptr) return -ENOENT;
    constexpr int kFlagsMask = O_ACCMODE | O_PATH | O_DIRECTORY;
    const int count = g_gateway_fd_template_count.load(
        std::memory_order_acquire
    );
    for (int index = 0; index < count; ++index) {
        const auto& slot = g_gateway_fd_templates[
            static_cast<size_t>(index)
        ];
        if (strcmp(path, slot.path) != 0 ||
            (flags & kFlagsMask) != (slot.flags & kFlagsMask)) {
            continue;
        }
        errno = 0;
        const long duplicate = syscall(
            SYS_fcntl,
            slot.fd,
            F_DUPFD_CLOEXEC,
            0
        );
        if (duplicate >= 0 || errno != EINVAL) return duplicate;
        return syscall(SYS_dup, slot.fd);
    }
    return -ENOENT;
}

long call_syscall_broker(GatewayRequest* request) {
    if (request == nullptr) return -EINVAL;
    long returned = -ENOSYS;
    if (g_syscall_broker_request_write < 0) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            kLogTag,
            "syscall broker unavailable pid=%d owner=%d request=%d type=%d",
            static_cast<int>(getpid()),
            static_cast<int>(g_syscall_broker_process),
            g_syscall_broker_request_write,
            request->type
        );
        return returned;
    }
    while (g_syscall_broker_busy.test_and_set(
            std::memory_order_acquire
        )) {
        asm volatile("yield");
    }
    for (int attempt = 0; attempt < 3; ++attempt) {
        int reply_sockets[2] = {-1, -1};
        const bool reply_created = socketpair(
            AF_UNIX,
            SOCK_DGRAM | SOCK_CLOEXEC,
            0,
            reply_sockets
        ) == 0;
        const bool request_written = reply_created &&
            send_gateway_request(
                g_syscall_broker_request_write,
                request,
                reply_sockets[0]
            );
        if (reply_sockets[0] >= 0) close(reply_sockets[0]);
        const int request_errno = request_written ? 0 : errno;
        if (!request_written) {
            __android_log_print(
                ANDROID_LOG_ERROR,
                kLogTag,
                "syscall broker request failed fd=%d errno=%d type=%d",
                g_syscall_broker_request_write,
                request_errno,
                request->type
            );
            if (reply_sockets[1] >= 0) close(reply_sockets[1]);
            break;
        }

        GatewayResponse response {};
        response.result = -EIO;
        int received_fd = -1;
        const bool response_read = receive_gateway_response(
            reply_sockets[1],
            &response,
            &received_fd
        );
        const int response_errno = response_read ? 0 : errno;
        if (response_read) {
            int resolved_fd = received_fd;
            if ((request->type == kGatewayOpenat ||
                 request->type == kGatewayVirtualProc) &&
                response.result >= 0 &&
                resolved_fd < 0 &&
                g_syscall_broker_child > 0
#if defined(SYS_pidfd_open) && defined(SYS_pidfd_getfd)
                ) {
                const long process_fd = syscall(
                    SYS_pidfd_open,
                    g_syscall_broker_child,
                    0
                );
                if (process_fd >= 0) {
                    resolved_fd = static_cast<int>(syscall(
                        SYS_pidfd_getfd,
                        process_fd,
                        response.result,
                        0
                    ));
                    close(static_cast<int>(process_fd));
                }
            }
#else
                ) {
            }
#endif
            if ((request->type == kGatewayOpenat ||
                 request->type == kGatewayVirtualProc) &&
                response.result >= 0 &&
                resolved_fd < 0 &&
                attempt < 2) {
                __android_log_print(
                    ANDROID_LOG_WARN,
                    kLogTag,
                    "syscall broker missing fd, retrying type=%d "
                    "broker_result=%ld attempt=%d",
                    request->type,
                    response.result,
                    attempt + 1
                );
                if (reply_sockets[1] >= 0) {
                    const char acknowledged = 'A';
                    syscall(
                        SYS_write,
                        reply_sockets[1],
                        &acknowledged,
                        sizeof(acknowledged)
                    );
                }
                if (reply_sockets[1] >= 0) close(reply_sockets[1]);
                continue;
            }
            returned = resolved_fd >= 0
                ? static_cast<long>(resolved_fd)
                : response.result;
            if (returned >= 0 &&
                (request->type == kGatewayOpenat ||
                 request->type == kGatewayVirtualProc)) {
                struct stat status {};
                const long stat_result = syscall(
                    SYS_fstat,
                    returned,
                    &status
                );
                __android_log_print(
                    ANDROID_LOG_WARN,
                    kLogTag,
                    "syscall-openat fd result=%ld received=%d broker=%ld "
                    "stat=%ld mode=%o size=%lld",
                    returned,
                    resolved_fd,
                    response.result,
                    stat_result,
                    stat_result == 0
                        ? static_cast<unsigned int>(status.st_mode)
                        : 0,
                    stat_result == 0
                        ? static_cast<long long>(status.st_size)
                        : -1LL
                );
            }
            if (request->type == kGatewayRtSigaction &&
                request->old_action_address != 0 &&
                response.action_size != 0) {
                auto* destination = reinterpret_cast<unsigned char*>(
                    request->old_action_address
                );
                const size_t count = std::min(
                    response.action_size,
                    sizeof(response.action)
                );
                for (size_t index = 0; index < count; ++index) {
                    destination[index] = response.action[index];
                }
            }
            if (reply_sockets[1] >= 0) {
                const char acknowledged = 'A';
                syscall(
                    SYS_write,
                    reply_sockets[1],
                    &acknowledged,
                    sizeof(acknowledged)
                );
            }
            if (reply_sockets[1] >= 0) close(reply_sockets[1]);
            break;
        } else {
            __android_log_print(
                ANDROID_LOG_ERROR,
                kLogTag,
                "syscall broker response failed fd=%d errno=%d type=%d",
                reply_sockets[1],
                response_errno,
                request->type
            );
        }
        if (reply_sockets[1] >= 0) close(reply_sockets[1]);
        break;
    }
    g_syscall_broker_busy.clear(std::memory_order_release);
    return returned;
}

void handle_openat_syscall(ucontext_t* context) {
    if (context == nullptr) return;
    const int dirfd = static_cast<int>(context->uc_mcontext.regs[0]);
    const auto path_address = static_cast<uintptr_t>(
        context->uc_mcontext.regs[1]
    );
    const int flags = static_cast<int>(context->uc_mcontext.regs[2]);
    const mode_t mode = static_cast<mode_t>(context->uc_mcontext.regs[3]);
    const uintptr_t pc = static_cast<uintptr_t>(context->uc_mcontext.pc);
    const uintptr_t window_start = (pc & ~3U) - 16;
    unsigned int code[12] {};
    if (g_self_mem_fd >= 0) {
        syscall(
            SYS_pread64,
            g_self_mem_fd,
            code,
            sizeof(code),
            window_start
        );
    }
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "syscall-openat code pc=%p lr=%p "
        "%08x %08x %08x %08x %08x %08x "
        "%08x %08x %08x %08x %08x %08x",
        reinterpret_cast<void*>(pc),
        reinterpret_cast<void*>(context->uc_mcontext.regs[30]),
        code[0],
        code[1],
        code[2],
        code[3],
        code[4],
        code[5],
        code[6],
        code[7],
        code[8],
        code[9],
        code[10],
        code[11]
    );

    char original[PATH_MAX] = {};
    if (!read_user_string(path_address, original, sizeof(original))) {
        context->uc_mcontext.regs[0] =
            static_cast<uintptr_t>(-EFAULT);
        return;
    }
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "syscall-openat entered path=%s dirfd=%d flags=%x mode=%o",
        original,
        dirfd,
        static_cast<unsigned int>(flags),
        static_cast<unsigned int>(mode)
    );

    const long cached_fd = duplicate_gateway_fd_template(original, flags);
    if (cached_fd >= 0 || cached_fd != -ENOENT) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "syscall-openat cached path=%s flags=%x result=%ld",
            original,
            static_cast<unsigned int>(flags),
            cached_fd
        );
        context->uc_mcontext.regs[0] =
            static_cast<uintptr_t>(cached_fd);
        return;
    }

    std::string rewritten;
    const bool mapped = nestspace::rewrite_target_path(
        original,
        &rewritten
    );
    const char* actual = mapped ? rewritten.c_str() : original;
    GatewayRequest request {};
    request.type = is_virtual_proc_gateway_path(original)
        ? kGatewayVirtualProc
        : kGatewayOpenat;
    request.client_pid = getpid();
    request.dirfd = dirfd;
    request.flags = flags;
    request.mode = mode;
    strncpy(request.path, actual, sizeof(request.path) - 1);
    const long returned = call_syscall_broker(&request);
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "syscall-openat requested=%s actual=%s flags=%x mode=%o "
        "result=%ld mapped=%d",
        original,
        actual,
        static_cast<unsigned int>(flags),
        static_cast<unsigned int>(mode),
        returned,
        mapped ? 1 : 0
    );
    context->uc_mcontext.regs[0] =
        static_cast<uintptr_t>(returned);
}

void handle_rt_sigaction_syscall(ucontext_t* context) {
    if (context == nullptr) return;
    GatewayRequest request {};
    request.type = kGatewayRtSigaction;
    request.signal_number = static_cast<int>(context->uc_mcontext.regs[0]);
    request.action_address = static_cast<uintptr_t>(
        context->uc_mcontext.regs[1]
    );
    request.old_action_address = static_cast<uintptr_t>(
        context->uc_mcontext.regs[2]
    );
    request.signal_set_size = static_cast<size_t>(
        context->uc_mcontext.regs[3]
    );
    request.action_size = std::min(
        std::max(request.signal_set_size + 24, sizeof(size_t) * 4),
        sizeof(request.action)
    );
    if (request.action_address != 0) {
        const auto* source = reinterpret_cast<const unsigned char*>(
            request.action_address
        );
        for (size_t index = 0; index < request.action_size; ++index) {
            request.action[index] = source[index];
        }
    }
    const long returned = call_syscall_broker(&request);
    if (request.signal_number == SIGSYS) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "syscall-rt_sigaction signal=%d action=%p old_action=%p "
            "size=%zu result=%ld",
            request.signal_number,
            reinterpret_cast<void*>(request.action_address),
            reinterpret_cast<void*>(request.old_action_address),
            request.signal_set_size,
            returned
        );
    }
    context->uc_mcontext.regs[0] = static_cast<uintptr_t>(returned);
}

void handle_rt_sigprocmask_syscall(ucontext_t* context) {
    if (context == nullptr) return;
    const int how = static_cast<int>(context->uc_mcontext.regs[0]);
    const auto set_address = static_cast<uintptr_t>(
        context->uc_mcontext.regs[1]
    );
    const auto old_set_address = static_cast<uintptr_t>(
        context->uc_mcontext.regs[2]
    );
    const size_t set_size = static_cast<size_t>(
        context->uc_mcontext.regs[3]
    );
    if (set_size != sizeof(uint64_t)) {
        context->uc_mcontext.regs[0] =
            static_cast<uintptr_t>(-EINVAL);
        return;
    }
    uint64_t current = 0;
    memcpy(&current, &context->uc_sigmask, sizeof(current));
    if (old_set_address != 0) {
        memcpy(
            reinterpret_cast<void*>(old_set_address),
            &current,
            sizeof(current)
        );
    }
    uint64_t requested = 0;
    if (set_address != 0) {
        memcpy(
            &requested,
            reinterpret_cast<const void*>(set_address),
            sizeof(requested)
        );
    }
    uint64_t updated = current;
    if (how == SIG_BLOCK) {
        updated |= requested;
    } else if (how == SIG_UNBLOCK) {
        updated &= ~requested;
    } else if (how == SIG_SETMASK) {
        updated = requested;
    } else {
        context->uc_mcontext.regs[0] =
            static_cast<uintptr_t>(-EINVAL);
        return;
    }
    updated &= ~(1ULL << (SIGSYS - 1));
    memcpy(&context->uc_sigmask, &updated, sizeof(updated));
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "syscall rt_sigprocmask how=%d requested=%016llx"
        " current=%016llx updated=%016llx",
        how,
        static_cast<unsigned long long>(requested),
        static_cast<unsigned long long>(current),
        static_cast<unsigned long long>(updated)
    );
    context->uc_mcontext.regs[0] = 0;
}

[[noreturn]] void syscall_broker_main() {
    prctl(PR_SET_NAME, "nssyscallbroker");
    for (;;) {
        GatewayRequest request {};
        int reply_socket = -1;
        if (!receive_gateway_request(
            g_syscall_broker_request_read,
            &request,
            &reply_socket
        )) {
            break;
        }
        errno = 0;
        long result = -EINVAL;
        GatewayResponse response {};
        if (request.type == kGatewayOpenat) {
            result = syscall(
                SYS_openat,
                request.dirfd,
                request.path,
                request.flags,
                request.mode
            );
        } else if (request.type == kGatewayVirtualProc) {
            result = nestspace::open_virtual_proc_for_pid(
                request.path,
                request.flags,
                request.client_pid
            );
        } else if (request.type == kGatewayRtSigaction) {
            unsigned char old_action[sizeof(response.action)] {};
            if (request.signal_number == SIGSYS &&
                request.action_address != 0) {
                result = 0;
                if (request.old_action_address != 0) {
                    result = syscall(
                        SYS_rt_sigaction,
                        request.signal_number,
                        nullptr,
                        old_action,
                        request.signal_set_size
                    );
                }
            } else {
                result = syscall(
                    SYS_rt_sigaction,
                    request.signal_number,
                    request.action,
                    request.old_action_address == 0
                        ? 0
                        : reinterpret_cast<uintptr_t>(old_action),
                    request.signal_set_size
                );
            }
            if (request.old_action_address != 0 &&
                result >= 0) {
                response.action_size = request.action_size;
                memcpy(
                    response.action,
                    old_action,
                    request.action_size
                );
            }
        }
        if (result < 0) result = -errno;
        response.result = result;
        const int result_fd =
            (request.type == kGatewayOpenat ||
             request.type == kGatewayVirtualProc) &&
                result >= 0
                ? static_cast<int>(result)
                : -1;
        if (reply_socket < 0 || !send_gateway_response(
            reply_socket,
            &response,
            result_fd
        )) {
            if (reply_socket >= 0) {
                syscall(SYS_close, reply_socket);
            }
            break;
        }
        if (result_fd >= 0) {
            char acknowledged = 0;
            syscall(
                SYS_read,
                reply_socket,
                &acknowledged,
                sizeof(acknowledged)
            );
        }
        syscall(SYS_close, reply_socket);
        if (result_fd >= 0) {
            syscall(SYS_close, result_fd);
        }
    }
    syscall(SYS_exit_group, 0);
    __builtin_unreachable();
}

bool start_syscall_broker() {
    if (g_syscall_broker_request_write >= 0) return true;
    int requests[2] = {-1, -1};
    if (socketpair(
            AF_UNIX,
            SOCK_SEQPACKET | SOCK_CLOEXEC,
            0,
            requests
        ) != 0) {
        return false;
    }
    g_syscall_broker_request_read = requests[0];
    g_syscall_broker_request_write = requests[1];
    g_syscall_broker_response_read = -1;
    g_syscall_broker_response_write = -1;
    g_syscall_broker_process = getpid();
    const pid_t child = fork();
    if (child < 0) {
        close(requests[0]);
        close(requests[1]);
        g_syscall_broker_request_read = -1;
        g_syscall_broker_request_write = -1;
        g_syscall_broker_response_read = -1;
        g_syscall_broker_response_write = -1;
        g_syscall_broker_process = -1;
        g_syscall_broker_child = -1;
        return false;
    }
    if (child == 0) {
        close(g_syscall_broker_request_write);
        syscall_broker_main();
    }
    close(g_syscall_broker_request_read);
    g_syscall_broker_request_read = -1;
    g_syscall_broker_child = child;
    return true;
}

void syscall_guard_handler(int sig, siginfo_t* info, void* raw_context) {
    (void)sig;
    (void)info;
    auto* context = static_cast<ucontext_t*>(raw_context);
    const uintptr_t syscall_number = context == nullptr
        ? 0
        : static_cast<uintptr_t>(context->uc_mcontext.regs[8]);
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "syscall gateway trap number=%lu tid=%d handler=%p",
        static_cast<unsigned long>(syscall_number),
        static_cast<int>(gettid()),
        reinterpret_cast<void*>(syscall_guard_handler)
    );
    if (syscall_number == SYS_openat &&
        g_syscall_gateway_pause.exchange(
            false,
            std::memory_order_acq_rel
        )) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "syscall gateway pausing at pc=%p lr=%p",
            context == nullptr
                ? nullptr
                : reinterpret_cast<void*>(context->uc_mcontext.pc),
            context == nullptr
                ? nullptr
                : reinterpret_cast<void*>(context->uc_mcontext.regs[30])
        );
        syscall(SYS_tgkill, getpid(), gettid(), SIGSTOP);
    }
    if (syscall_number == SYS_openat) {
        handle_openat_syscall(context);
        return;
    }
    if (syscall_number == __NR_close && context != nullptr) {
        const int fd = static_cast<int>(context->uc_mcontext.regs[0]);
        if (fd == g_syscall_broker_request_write ||
            fd == g_syscall_broker_response_read) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "preserved syscall broker fd=%d from target close",
                fd
            );
            context->uc_mcontext.regs[0] = 0;
            return;
        }
    }
#if defined(__NR_close_range)
    if (syscall_number == __NR_close_range && context != nullptr) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "preserved syscall broker descriptors from close_range "
            "first=%lu last=%lu flags=%lu",
            static_cast<unsigned long>(context->uc_mcontext.regs[0]),
            static_cast<unsigned long>(context->uc_mcontext.regs[1]),
            static_cast<unsigned long>(context->uc_mcontext.regs[2])
        );
        context->uc_mcontext.regs[0] = 0;
        return;
    }
#endif
#if defined(__NR_rt_sigaction)
    if (syscall_number == __NR_rt_sigaction) {
        handle_rt_sigaction_syscall(context);
        return;
    }
#endif
#if defined(__NR_rt_sigprocmask)
    if (syscall_number == __NR_rt_sigprocmask) {
        handle_rt_sigprocmask_syscall(context);
        return;
    }
#endif
#if defined(__NR_seccomp)
    if (syscall_number == __NR_seccomp && context != nullptr) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "blocked target seccomp operation=%lu flags=%lu",
            static_cast<unsigned long>(context->uc_mcontext.regs[0]),
            static_cast<unsigned long>(context->uc_mcontext.regs[1])
        );
        context->uc_mcontext.regs[0] =
            static_cast<uintptr_t>(-EPERM);
        return;
    }
#endif
    if (syscall_number == __NR_prctl && context != nullptr) {
        const int option = static_cast<int>(context->uc_mcontext.regs[0]);
        if (option == PR_SET_SECCOMP) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "blocked target seccomp prctl"
            );
            context->uc_mcontext.regs[0] =
                static_cast<uintptr_t>(-EPERM);
            return;
        }
    }
#if defined(__NR_execve)
    if (syscall_number == __NR_execve && context != nullptr) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "blocked target execve path=%p",
            reinterpret_cast<void*>(context->uc_mcontext.regs[0])
        );
        context->uc_mcontext.regs[0] =
            static_cast<uintptr_t>(-ENOENT);
        return;
    }
#endif
#if defined(__NR_execveat)
    if (syscall_number == __NR_execveat && context != nullptr) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "blocked target execveat dirfd=%ld path=%p",
            static_cast<long>(context->uc_mcontext.regs[0]),
            reinterpret_cast<void*>(context->uc_mcontext.regs[1])
        );
        context->uc_mcontext.regs[0] =
            static_cast<uintptr_t>(-ENOENT);
        return;
    }
#endif
    const bool signal_in_arg1 =
        syscall_number == SYS_kill ||
        syscall_number == SYS_tkill
#ifdef SYS_pidfd_send_signal
        || syscall_number == SYS_pidfd_send_signal
#endif
        ;
    const uintptr_t signal_number = context == nullptr
        ? 0
        : static_cast<uintptr_t>(
            context->uc_mcontext.regs[signal_in_arg1 ? 1 : 2]
        );
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "guarded target syscall=%lu tid=%d arg0=%016lx arg1=%016lx "
        "arg2=%016lx pc=%016lx",
        static_cast<unsigned long>(syscall_number),
        static_cast<int>(syscall(SYS_gettid)),
        context == nullptr
            ? 0UL
            : static_cast<unsigned long>(context->uc_mcontext.regs[0]),
        context == nullptr
            ? 0UL
            : static_cast<unsigned long>(context->uc_mcontext.regs[1]),
        context == nullptr
            ? 0UL
            : static_cast<unsigned long>(context->uc_mcontext.regs[2]),
        context == nullptr
            ? 0UL
            : static_cast<unsigned long>(context->uc_mcontext.pc)
    );
    if (g_trace_fd >= 0 && context != nullptr) {
        static constexpr char kHeader[] = "exit-syscall\n";
        write(g_trace_fd, kHeader, sizeof(kHeader) - 1);
        write_hex(syscall_number);
        write_hex(static_cast<uintptr_t>(context->uc_mcontext.pc));
        write_module("pc", context->uc_mcontext.pc);
        write_module("lr", context->uc_mcontext.regs[30]);
        write_maps();
        fsync(g_trace_fd);
    }
    if (context != nullptr) {
        context->uc_mcontext.regs[0] = 0;
        const bool fatal_thread_signal =
            (syscall_number == SYS_kill ||
             syscall_number == SYS_tgkill) &&
            signal_number == SIGKILL;
        if (fatal_thread_signal) {
            context->uc_mcontext.pc = reinterpret_cast<uintptr_t>(
                &guard_exit_thread
            );
        }
    }
}

void install_handler(int signal_number) {
    struct sigaction action {};
    action.sa_sigaction = crash_handler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&action.sa_mask);
    sigaction(signal_number, &action, nullptr);
}

void install_syscall_handler() {
    struct sigaction action {};
    action.sa_sigaction = syscall_guard_handler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&action.sa_mask);
    sigaction(SIGSYS, &action, nullptr);
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

bool install_kill_guard_filter() {
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_kill, 0, 3),
        BPF_STMT(
            BPF_LD | BPF_W | BPF_ABS,
            offsetof(seccomp_data, args[1])
        ),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_tgkill, 0, 3),
        BPF_STMT(
            BPF_LD | BPF_W | BPF_ABS,
            offsetof(seccomp_data, args[2])
        ),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#if defined(__NR_close_range)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_close_range, 0, 1),
        BPF_STMT(
            BPF_RET | BPF_K,
            SECCOMP_RET_ERRNO | (EINVAL & SECCOMP_RET_DATA)
        ),
#endif
#if defined(__NR_rt_sigprocmask)
        BPF_JUMP(
            BPF_JMP | BPF_JEQ | BPF_K,
            __NR_rt_sigprocmask,
            0,
            1
        ),
        BPF_STMT(
            BPF_RET | BPF_K,
            SECCOMP_RET_ERRNO | (EPERM & SECCOMP_RET_DATA)
        ),
#endif
#if defined(__NR_execve)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_execve, 0, 1),
        BPF_STMT(
            BPF_RET | BPF_K,
            SECCOMP_RET_ERRNO | (ENOENT & SECCOMP_RET_DATA)
        ),
#endif
#if defined(__NR_execveat)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_execveat, 0, 1),
        BPF_STMT(
            BPF_RET | BPF_K,
            SECCOMP_RET_ERRNO | (ENOENT & SECCOMP_RET_DATA)
        ),
#endif
#if defined(__NR_seccomp)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_seccomp, 0, 1),
        BPF_STMT(
            BPF_RET | BPF_K,
            SECCOMP_RET_ERRNO | (EPERM & SECCOMP_RET_DATA)
        ),
#endif
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_prctl, 0, 3),
        BPF_STMT(
            BPF_LD | BPF_W | BPF_ABS,
            offsetof(seccomp_data, args[0])
        ),
        BPF_JUMP(
            BPF_JMP | BPF_JEQ | BPF_K,
            PR_SET_SECCOMP,
            0,
            1
        ),
        BPF_STMT(
            BPF_RET | BPF_K,
            SECCOMP_RET_ERRNO | (EPERM & SECCOMP_RET_DATA)
        ),
#if defined(__NR_close_range)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_close_range, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
#if defined(__NR_rt_sigprocmask)
        BPF_JUMP(
            BPF_JMP | BPF_JEQ | BPF_K,
            __NR_rt_sigprocmask,
            0,
            1
        ),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
#if defined(__NR_execve)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_execve, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
#if defined(__NR_execveat)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_execveat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
#if defined(__NR_seccomp)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_seccomp, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_prctl, 0, 3),
        BPF_STMT(
            BPF_LD | BPF_W | BPF_ABS,
            offsetof(seccomp_data, args[0])
        ),
        BPF_JUMP(
            BPF_JMP | BPF_JEQ | BPF_K,
            PR_SET_SECCOMP,
            0,
            1
        ),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
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

bool install_openat_gateway_filter() {
#if defined(__NR_openat)
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "syscall gateway bootstrap begin"
    );
    if (!start_syscall_broker()) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "syscall broker start failed: %s",
            strerror(errno)
        );
        return false;
    }
    const nestspace::Environment* environment =
        nestspace::current_environment();
    if (environment != nullptr) {
        add_gateway_fd_template(
            "/data",
            O_RDONLY | O_CLOEXEC | O_PATH
        );
        add_gateway_fd_template(
            "/mnt/expand",
            O_RDONLY | O_CLOEXEC | O_PATH
        );
        add_gateway_fd_template(
            "/data/user/0/" + environment->host_package_name,
            O_RDONLY | O_CLOEXEC | O_PATH
        );
        add_gateway_fd_template(
            environment->actual_base_apk_path,
            O_RDONLY | O_CLOEXEC
        );
        add_gateway_fd_template(
            environment->actual_base_apk_path,
            O_RDONLY | O_CLOEXEC | O_PATH
        );
        for (const auto& directory : environment->runtime_library_dirs) {
            add_gateway_fd_template(
                directory,
                O_RDONLY | O_CLOEXEC | O_PATH
            );
        }
        for (const auto& library : environment->runtime_library_paths) {
            add_gateway_fd_template(
                library,
                O_RDONLY | O_CLOEXEC
            );
        }
    }
    if (g_self_mem_fd < 0) {
        g_self_mem_fd = static_cast<int>(syscall(
            SYS_openat,
            AT_FDCWD,
            "/proc/self/mem",
            O_RDONLY | O_CLOEXEC,
            0
        ));
    }
    g_syscall_gateway_pause.store(
        diagnostic_enabled(
            "NESTSPACE_SYSCALL_GATEWAY_PAUSE",
            "debug.nestspace.syscall_gateway_pause"
        ),
        std::memory_order_release
    );
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "syscall gateway broker ready request=%d response=%d",
        g_syscall_broker_request_write,
        g_syscall_broker_response_read
    );
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#if defined(__NR_rt_sigaction)
        BPF_JUMP(
            BPF_JMP | BPF_JEQ | BPF_K,
            __NR_rt_sigaction,
            0,
            1
        ),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_close, 0, 4),
        BPF_STMT(
            BPF_LD | BPF_W | BPF_ABS,
            offsetof(seccomp_data, args[0])
        ),
        BPF_JUMP(
            BPF_JMP | BPF_JEQ | BPF_K,
            static_cast<__u32>(g_syscall_broker_request_write),
            0,
            2
        ),
        BPF_JUMP(
            BPF_JMP | BPF_JEQ | BPF_K,
            static_cast<__u32>(g_syscall_broker_response_read),
            0,
            1
        ),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#if defined(__NR_close_range)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_close_range, 0, 1),
        BPF_STMT(
            BPF_RET | BPF_K,
            SECCOMP_RET_ERRNO | (EINVAL & SECCOMP_RET_DATA)
        ),
#endif
#if defined(__NR_rt_sigprocmask)
        BPF_JUMP(
            BPF_JMP | BPF_JEQ | BPF_K,
            __NR_rt_sigprocmask,
            0,
            1
        ),
        BPF_STMT(
            BPF_RET | BPF_K,
            SECCOMP_RET_ERRNO | (EPERM & SECCOMP_RET_DATA)
        ),
#endif
#if defined(__NR_execve)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_execve, 0, 1),
        BPF_STMT(
            BPF_RET | BPF_K,
            SECCOMP_RET_ERRNO | (ENOENT & SECCOMP_RET_DATA)
        ),
#endif
#if defined(__NR_execveat)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_execveat, 0, 1),
        BPF_STMT(
            BPF_RET | BPF_K,
            SECCOMP_RET_ERRNO | (ENOENT & SECCOMP_RET_DATA)
        ),
#endif
#if defined(__NR_seccomp)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_seccomp, 0, 1),
        BPF_STMT(
            BPF_RET | BPF_K,
            SECCOMP_RET_ERRNO | (EPERM & SECCOMP_RET_DATA)
        ),
#endif
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_prctl, 0, 3),
        BPF_STMT(
            BPF_LD | BPF_W | BPF_ABS,
            offsetof(seccomp_data, args[0])
        ),
        BPF_JUMP(
            BPF_JMP | BPF_JEQ | BPF_K,
            PR_SET_SECCOMP,
            0,
            1
        ),
        BPF_STMT(
            BPF_RET | BPF_K,
            SECCOMP_RET_ERRNO | (EPERM & SECCOMP_RET_DATA)
        ),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program {
        static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0])),
        filter
    };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "syscall gateway no-new-privs failed: %s",
            strerror(errno)
        );
        return false;
    }
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "syscall gateway installing handler"
    );
    install_syscall_handler();
    g_syscall_gateway_active.store(true, std::memory_order_release);
    sigset_t unblock {};
    sigemptyset(&unblock);
    sigaddset(&unblock, SIGSYS);
    const int mask_result = sigprocmask(
        SIG_UNBLOCK,
        &unblock,
        nullptr
    );
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "syscall gateway installing filter instructions=%zu mask=%d",
        sizeof(filter) / sizeof(filter[0]),
        mask_result
    );
    struct sigaction current {};
    const int action_result = sigaction(SIGSYS, nullptr, &current);
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "syscall gateway handler state result=%d handler=%p flags=%08x "
        "expected=%p",
        action_result,
        action_result == 0
            ? reinterpret_cast<void*>(current.sa_sigaction)
            : nullptr,
        action_result == 0 ? current.sa_flags : 0,
        reinterpret_cast<void*>(syscall_guard_handler)
    );
    const long install_result = syscall(
        SYS_seccomp,
        SECCOMP_SET_MODE_FILTER,
        SECCOMP_FILTER_FLAG_TSYNC,
        &program
    );
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "syscall gateway synchronized filter result=%ld errno=%d "
        "broker_pid=%d",
        install_result,
        install_result == 0 ? 0 : errno,
        g_syscall_broker_child
    );
    if (install_result != 0) {
        g_syscall_gateway_active.store(false, std::memory_order_release);
    }
    return install_result == 0;
#else
    return false;
#endif
}

bool install_kill_guard() {
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "kill guard deferred until application bootstrap"
    );
    return false;
}

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
