#include "nestspace/native_environment_internal.h"

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
