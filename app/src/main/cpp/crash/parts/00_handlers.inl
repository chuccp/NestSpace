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
#include <poll.h>
#include <sys/prctl.h>
#include <sys/ioctl.h>
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
uintptr_t g_runtime_table_fallback_object = 0;

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
    kGatewayFstatat = 4,
    kGatewayVirtualFstatat = 5,
    kGatewayStatx = 6,
    kGatewayVirtualStatx = 7,
    kGatewayFaccessat = 8,
    kGatewayReadlinkat = 9,
};

struct GatewayRequest {
    int type;
    int dirfd;
    int flags;
    int signal_number;
    pid_t client_pid;
    mode_t mode;
    unsigned int mask;
    uintptr_t action_address;
    uintptr_t old_action_address;
    size_t signal_set_size;
    size_t action_size;
    unsigned char action[64];
    int rewrite_identity;
    char path[PATH_MAX];
};

struct GatewayResponse {
    long result;
    size_t action_size;
    unsigned char action[64];
    size_t payload_size;
    unsigned char payload[256];
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
    const bool resolved = nestspace::g_real.dladdr != nullptr &&
        nestspace::g_real.dladdr(
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

void write_code_window(const char* label, uintptr_t address) {
    if (address < 0x1000) return;
    const uintptr_t start =
        address & ~static_cast<uintptr_t>(0xfff);
    char buffer[0x1000];
    struct iovec local {
        buffer,
        sizeof(buffer)
    };
    struct iovec remote {
        reinterpret_cast<void*>(start),
        sizeof(buffer)
    };
    if (syscall(
            SYS_process_vm_readv,
            getpid(),
            &local,
            1,
            &remote,
            1,
            0
        ) != static_cast<long>(sizeof(buffer))) {
        return;
    }
    write(g_trace_fd, label, strlen(label));
    write(g_trace_fd, "\n", 1);
    write_hex(address);
    write_hex(start);
    write(g_trace_fd, buffer, sizeof(buffer));
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

bool find_mapping_bounds(
    uintptr_t address,
    uintptr_t* begin,
    uintptr_t* end
) {
    if (address == 0 || begin == nullptr || end == nullptr) return false;
    const int fd = static_cast<int>(syscall(
        SYS_openat,
        AT_FDCWD,
        "/proc/self/maps",
        O_RDONLY | O_CLOEXEC
    ));
    if (fd < 0) return false;
    char chunk[4096];
    char line[512];
    size_t length = 0;
    bool found = false;
    while (!found) {
        const long count = syscall(SYS_read, fd, chunk, sizeof(chunk));
        if (count <= 0) break;
        for (long index = 0; index < count && !found; ++index) {
            const char value = chunk[index];
            if (value != '\n') {
                if (length + 1 < sizeof(line)) line[length++] = value;
                continue;
            }
            line[length] = '\0';
            length = 0;
            size_t cursor = 0;
            uintptr_t range_begin = 0;
            while (cursor < sizeof(line) && line[cursor] != '-') {
                const char digit = line[cursor++];
                int hex = -1;
                if (digit >= '0' && digit <= '9') hex = digit - '0';
                else if (digit >= 'a' && digit <= 'f') hex = digit - 'a' + 10;
                else if (digit >= 'A' && digit <= 'F') hex = digit - 'A' + 10;
                else break;
                range_begin = (range_begin << 4) | static_cast<uintptr_t>(hex);
            }
            if (cursor >= sizeof(line) || line[cursor] != '-') continue;
            ++cursor;
            uintptr_t range_end = 0;
            while (cursor < sizeof(line) && line[cursor] != ' ') {
                const char digit = line[cursor++];
                int hex = -1;
                if (digit >= '0' && digit <= '9') hex = digit - '0';
                else if (digit >= 'a' && digit <= 'f') hex = digit - 'a' + 10;
                else if (digit >= 'A' && digit <= 'F') hex = digit - 'A' + 10;
                else break;
                range_end = (range_end << 4) | static_cast<uintptr_t>(hex);
            }
            if (address >= range_begin && address < range_end) {
                *begin = range_begin;
                *end = range_end;
                found = true;
            }
        }
    }
    syscall(SYS_close, fd);
    return found;
}

bool recover_runtime_oob_load(
    int signal_number,
    const siginfo_t* info,
    ucontext_t* context
) {
    if (signal_number == SIGSEGV && info != nullptr && context != nullptr) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kLogTag,
            "runtime OOB probe fault=%p pc=%p",
            info->si_addr,
            reinterpret_cast<void*>(context->uc_mcontext.pc)
        );
    }
    if (signal_number != SIGSEGV || info == nullptr ||
        context == nullptr ||
        !diagnostic_enabled(
            "NESTSPACE_RUNTIME_OOB_ZERO",
            "debug.nestspace.runtime_oob_zero"
        )) {
        return false;
    }
    constexpr uintptr_t kPointerTagMask = (1ULL << 56) - 1;
    const uintptr_t fault_address =
        reinterpret_cast<uintptr_t>(info->si_addr);
    const uintptr_t x8 =
        static_cast<uintptr_t>(context->uc_mcontext.regs[8]) &
        kPointerTagMask;
    const uintptr_t x9 =
        static_cast<uintptr_t>(context->uc_mcontext.regs[9]);
    char detail[PROP_VALUE_MAX] = {};
    __system_property_get(
        "debug.nestspace.runtime_oob_detail",
        detail
    );
    if (strcmp(detail, "1") == 0) {
        const uint32_t instruction =
            *reinterpret_cast<const uint32_t*>(
                context->uc_mcontext.pc
            );
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kLogTag,
            "runtime OOB detail pc=%p fault=%p x0=%p x8=%p x9=%p "
            "x1=%p x10=%p x19=%p x20=%p x21=%p x22=%p "
            "x23=%p x24=%p x25=%p x26=%p x27=%p x28=%p "
            "instruction=%08x",
            reinterpret_cast<void*>(context->uc_mcontext.pc),
            reinterpret_cast<void*>(fault_address),
            reinterpret_cast<void*>(context->uc_mcontext.regs[0]),
            reinterpret_cast<void*>(x8),
            reinterpret_cast<void*>(x9),
            reinterpret_cast<void*>(context->uc_mcontext.regs[1]),
            reinterpret_cast<void*>(context->uc_mcontext.regs[10]),
            reinterpret_cast<void*>(context->uc_mcontext.regs[19]),
            reinterpret_cast<void*>(context->uc_mcontext.regs[20]),
            reinterpret_cast<void*>(context->uc_mcontext.regs[21]),
            reinterpret_cast<void*>(context->uc_mcontext.regs[22]),
            reinterpret_cast<void*>(context->uc_mcontext.regs[23]),
            reinterpret_cast<void*>(context->uc_mcontext.regs[24]),
            reinterpret_cast<void*>(context->uc_mcontext.regs[25]),
            reinterpret_cast<void*>(context->uc_mcontext.regs[26]),
            reinterpret_cast<void*>(context->uc_mcontext.regs[27]),
            reinterpret_cast<void*>(context->uc_mcontext.regs[28]),
            instruction
        );
    }
    const uintptr_t pc = context->uc_mcontext.pc;
    uint32_t instruction =
        *reinterpret_cast<const uint32_t*>(pc);
    bool advance_pc = true;
    const bool is_64_bit_load =
        (instruction & 0xffe00c00U) == 0xf8600800U;
    const bool is_32_bit_load =
        (instruction & 0xffe00c00U) == 0xb8600800U;
    const bool is_16_bit_load =
        (instruction & 0xffe00c00U) == 0x78600800U;
    const bool is_8_bit_load =
        (instruction & 0xffe00c00U) == 0x38600800U;
    const bool is_64_bit_store =
        (instruction & 0xffe00c00U) == 0xf8200800U;
    const bool is_32_bit_store =
        (instruction & 0xffe00c00U) == 0xb8200800U;
    const bool is_16_bit_store =
        (instruction & 0xffe00c00U) == 0x78200800U;
    const bool is_8_bit_store =
        (instruction & 0xffe00c00U) == 0x38200800U;
    if (is_64_bit_store || is_32_bit_store ||
        is_16_bit_store || is_8_bit_store) {
        const uint32_t base = (instruction >> 5U) & 0x1fU;
        const uint32_t index = (instruction >> 16U) & 0x1fU;
        if (base != 31 && index != 31) {
            const uintptr_t base_address =
                static_cast<uintptr_t>(
                    context->uc_mcontext.regs[base]
                ) &
                kPointerTagMask;
            const uint32_t option =
                (instruction >> 12U) & 0x3U;
            const uint32_t shift = option == 3U
                ? (is_64_bit_store ? 3U :
                    is_32_bit_store ? 2U :
                    is_16_bit_store ? 1U : 0U)
                : 0U;
            const uintptr_t index_value =
                static_cast<uintptr_t>(
                    static_cast<uint32_t>(
                        context->uc_mcontext.regs[index]
                    )
                ) << shift;
            if (base_address + index_value == fault_address) {
                context->uc_mcontext.pc += 4;
                __android_log_print(
                    ANDROID_LOG_WARN,
                    kLogTag,
                    "runtime OOB store skipped base=x%u index=x%u "
                    "address=%p pc=%p",
                    base,
                    index,
                    info->si_addr,
                    reinterpret_cast<void*>(pc)
                );
                return true;
            }
        }
    }
    if (!is_64_bit_load && !is_32_bit_load &&
        !is_16_bit_load && !is_8_bit_load &&
        (instruction & 0xffc00000U) == 0xb9400000U &&
        g_runtime_table_fallback_object != 0) {
        const uint32_t target = instruction & 0x1fU;
        const uint32_t base = (instruction >> 5U) & 0x1fU;
        const uint32_t immediate =
            ((instruction >> 10U) & 0xfffU) << 3U;
        constexpr uintptr_t kPointerTagMask = (1ULL << 56) - 1;
        const uintptr_t base_address =
            static_cast<uintptr_t>(context->uc_mcontext.regs[base]) &
            kPointerTagMask;
        const uintptr_t fault_address =
            reinterpret_cast<uintptr_t>(info->si_addr);
        if (base_address < 0x40000000 &&
            base_address + immediate == fault_address) {
            context->uc_mcontext.regs[target] =
                g_runtime_table_fallback_object;
            context->uc_mcontext.pc += 4;
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "runtime invalid reference recovered base=x%u target=x%u "
                "faul=%p",
                base,
                target,
                info->si_addr
            );
            return true;
        }
    }
    if (!is_64_bit_load && !is_32_bit_load &&
        !is_16_bit_load && !is_8_bit_load && pc >= 4) {
        instruction =
            *reinterpret_cast<const uint32_t*>(pc - 4);
        advance_pc = false;
    }
    const uint32_t load_mask = instruction & 0xffe00c00U;
    const bool decoded_64_bit_load = load_mask == 0xf8600800U;
    const bool decoded_32_bit_load = load_mask == 0xb8600800U;
    const bool decoded_16_bit_load = load_mask == 0x78600800U;
    const bool decoded_8_bit_load = load_mask == 0x38600800U;
    if (!decoded_64_bit_load && !decoded_32_bit_load &&
        !decoded_16_bit_load && !decoded_8_bit_load) {
        return false;
    }
    const uint32_t target = instruction & 0x1fU;
    const uint32_t base = (instruction >> 5U) & 0x1fU;
    const uint32_t index = (instruction >> 16U) & 0x1fU;
    if (target == 31 || base == 31 || index == 31) return false;
    const uintptr_t base_address =
        static_cast<uintptr_t>(context->uc_mcontext.regs[base]) &
        kPointerTagMask;
    uintptr_t index_value =
        static_cast<uintptr_t>(context->uc_mcontext.regs[index]);
    const uint32_t option = (instruction >> 12U) & 0x3U;
    const uint32_t shift = option == 3U
        ? (decoded_64_bit_load ? 3U :
            decoded_32_bit_load ? 2U :
            decoded_16_bit_load ? 1U : 0U)
        : 0U;
    index_value = static_cast<uint32_t>(index_value) << shift;
    if (base_address + index_value != fault_address) return false;
    uintptr_t mapping_begin = 0;
    uintptr_t mapping_end = 0;
    if (g_runtime_table_fallback_object != 0 &&
        base_address != 0 &&
        !find_mapping_bounds(base_address, &mapping_begin, &mapping_end)) {
        context->uc_mcontext.regs[base] =
            g_runtime_table_fallback_object;
        context->uc_mcontext.regs[index] = 0;
        const uintptr_t walker =
            static_cast<uintptr_t>(context->uc_mcontext.regs[24]) &
            kPointerTagMask;
        if (walker != 0 &&
            !find_mapping_bounds(walker, &mapping_begin, &mapping_end)) {
            context->uc_mcontext.regs[24] =
                g_runtime_table_fallback_object;
        }
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "runtime OOB load moved invalid base=x%u to fallback",
            base
        );
    }
    if (target != base) {
        context->uc_mcontext.regs[target] = 0;
    }
    if (decoded_16_bit_load &&
        context->uc_mcontext.regs[22] == 0xffffffffU) {
        context->uc_mcontext.regs[22] = 0;
    }
    if (advance_pc) context->uc_mcontext.pc += 4;
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "runtime OOB load recovered target=x%u base=x%u index=x%u "
        "address=%p pc=%p",
        target,
        base,
        index,
        info->si_addr,
        reinterpret_cast<void*>(pc)
    );
    return true;
}

void crash_handler(int sig, siginfo_t* info, void* raw_context) {
    if (raw_context != nullptr &&
        recover_runtime_oob_load(
            sig,
            info,
            static_cast<ucontext_t*>(raw_context)
        )) {
        return;
    }
    if (g_trace_fd >= 0) {
        const char header[] = "signal\n";
        write(g_trace_fd, header, sizeof(header) - 1);
        write_hex(static_cast<uintptr_t>(sig));
        if (info != nullptr) {
            const char fault_header[] = "fault\n";
            write(g_trace_fd, fault_header, sizeof(fault_header) - 1);
            write_hex(reinterpret_cast<uintptr_t>(info->si_addr));
            const char code_header[] = "signal_code\n";
            write(g_trace_fd, code_header, sizeof(code_header) - 1);
            write_hex(static_cast<uintptr_t>(info->si_code));
        }
        if (raw_context != nullptr) {
            auto* context = static_cast<ucontext_t*>(raw_context);
            const char pc_header[] = "pc\n";
            const char sp_header[] = "sp\n";
            const char regs_header[] = "regs\n";
            const char code_header[] = "pc_code\n";
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
            const uintptr_t code_start =
                (pc & ~static_cast<uintptr_t>(0xff)) - 0x100;
            write(g_trace_fd, code_header, sizeof(code_header) - 1);
            char code[0x300];
            struct iovec code_local {
                code,
                sizeof(code)
            };
            struct iovec code_remote {
                reinterpret_cast<void*>(code_start),
                sizeof(code)
            };
            if (syscall(
                    SYS_process_vm_readv,
                    getpid(),
                    &code_local,
                    1,
                    &code_remote,
                    1,
                    0
                ) == static_cast<long>(sizeof(code))) {
                write(g_trace_fd, code, sizeof(code));
            }
            write_code_window(
                "lr_code",
                context->uc_mcontext.regs[30]
            );
            const uintptr_t object = context->uc_mcontext.regs[0];
            write_process_memory("object", object, 0x100);
            char object_buffer[0x100];
            struct iovec object_local {
                object_buffer,
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
                    static_cast<const uintptr_t*>(
                        static_cast<const void*>(object_buffer)
                    );
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
            write_process_memory("x8", context->uc_mcontext.regs[8], 0x100);
            write_process_memory("x29", context->uc_mcontext.regs[29], 0x400);
            uintptr_t interpreter_pc = 0;
            struct iovec interpreter_local {
                &interpreter_pc,
                sizeof(interpreter_pc)
            };
            struct iovec interpreter_remote {
                reinterpret_cast<void*>(
                    static_cast<uintptr_t>(
                        context->uc_mcontext.regs[22]
                    )
                ),
                sizeof(interpreter_pc)
            };
            if (syscall(
                    SYS_process_vm_readv,
                    getpid(),
                    &interpreter_local,
                    1,
                    &interpreter_remote,
                    1,
                    0
                ) == static_cast<long>(sizeof(interpreter_pc)) &&
                interpreter_pc > 0x40) {
                write_process_memory(
                    "interpreter-code",
                    interpreter_pc - 0x20,
                    0x80
                );
            }
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
    if (recover_runtime_oob_load(signal_number, info, context)) return;
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
        if (nestspace::g_real.dladdr != nullptr &&
            nestspace::g_real.dladdr(
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
        char table_guard[PROP_VALUE_MAX] = {};
        __system_property_get(
            "debug.nestspace.runtime_table_guard",
            table_guard
        );
        if (context != nullptr &&
            signal_number == SIGSEGV &&
            info->si_code == SEGV_ACCERR &&
            strcmp(table_guard, "1") == 0 &&
            g_runtime_table_fallback_object != 0) {
            constexpr uintptr_t kPointerTagMask = (1ULL << 56) - 1;
            const uintptr_t table =
                static_cast<uintptr_t>(context->uc_mcontext.regs[8]) &
                kPointerTagMask;
            const uintptr_t delta = fault_address >= table
                ? fault_address - table
                : table - fault_address;
            if (table != 0 && delta >= 0x1000 && delta < 0x100000) {
                uintptr_t mapping_begin = 0;
                uintptr_t mapping_end = 0;
                if (find_mapping_bounds(
                        table,
                        &mapping_begin,
                        &mapping_end
                    ) &&
                    mapping_end > mapping_begin) {
                    const uintptr_t mapping_size =
                        mapping_end - mapping_begin;
                    const uintptr_t wrapped_address =
                        mapping_begin +
                        ((fault_address - mapping_begin) % mapping_size);
                    const uintptr_t wrapped =
                        wrapped_address - mapping_begin;
                    __android_log_print(
                        ANDROID_LOG_WARN,
                        "NestSpaceNative",
                        "runtime-table-guard table=%p mapping=%p-%p "
                        "fault=%p delta=%p wrapped=%p pc=%p",
                        reinterpret_cast<void*>(table),
                        reinterpret_cast<void*>(mapping_begin),
                        reinterpret_cast<void*>(mapping_end),
                        reinterpret_cast<void*>(fault_address),
                        reinterpret_cast<void*>(delta),
                        reinterpret_cast<void*>(wrapped),
                        reinterpret_cast<void*>(context->uc_mcontext.pc)
                    );
                    context->uc_mcontext.regs[8] = mapping_begin;
                    context->uc_mcontext.regs[9] = wrapped;
                    context->uc_mcontext.pc -= 4;
                    return;
                }
            }
        }
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
        if (nestspace::g_real.dladdr != nullptr &&
            nestspace::g_real.dladdr(
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
    char table_guard[PROP_VALUE_MAX] = {};
    __system_property_get(
        "debug.nestspace.runtime_table_guard",
        table_guard
    );
    if (strcmp(table_guard, "1") == 0 &&
        g_runtime_table_fallback_object == 0) {
        const long page_size = sysconf(_SC_PAGESIZE);
        const size_t size = page_size > 0
            ? static_cast<size_t>(page_size) * 2
            : 0x2000;
        void* fallback = mmap(
            nullptr,
            size,
            PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS,
            -1,
            0
        );
        if (fallback != MAP_FAILED) {
            memset(fallback, 0, size);
            g_runtime_table_fallback_object =
                reinterpret_cast<uintptr_t>(fallback);
        }
    }
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
    struct iovec local {
        buffer,
        capacity - 1
    };
    struct iovec remote {
        reinterpret_cast<void*>(address),
        capacity - 1
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
    for (size_t index = 0; index < static_cast<size_t>(copied); ++index) {
        if (buffer[index] == '\0') return true;
    }
    return false;
}

bool write_user_memory(
    uintptr_t address,
    const void* source,
    size_t size
) {
    if (address == 0 || source == nullptr || size == 0) return false;
    struct iovec local {
        const_cast<void*>(source),
        size
    };
    struct iovec remote {
        reinterpret_cast<void*>(address),
        size
    };
    return syscall(
        SYS_process_vm_writev,
        getpid(),
        &local,
        1,
        &remote,
        1,
        0
    ) == static_cast<long>(size);
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

long call_syscall_broker(
    GatewayRequest* request,
    int* returned_fd = nullptr,
    GatewayResponse* caller_response = nullptr
) {
    if (request == nullptr) return -EINVAL;
    if (returned_fd != nullptr) *returned_fd = -1;
    if (caller_response != nullptr) *caller_response = {};
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
    constexpr int kBrokerBusySpinLimit = 10000;
    int busy_spins = 0;
    while (g_syscall_broker_busy.test_and_set(
            std::memory_order_acquire
        )) {
        if (++busy_spins >= kBrokerBusySpinLimit) {
            __android_log_print(
                ANDROID_LOG_ERROR,
                kLogTag,
                "syscall broker busy timeout type=%d",
                request->type
            );
            return -EBUSY;
        }
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
        struct pollfd waiter {};
        waiter.fd = reply_sockets[1];
        waiter.events = POLLIN;
        constexpr int kBrokerReplyTimeoutMs = 250;
        const int ready = poll(
            &waiter,
            1,
            kBrokerReplyTimeoutMs
        );
        if (ready <= 0) {
            __android_log_print(
                ANDROID_LOG_ERROR,
                kLogTag,
                "syscall broker reply timeout ready=%d errno=%d type=%d",
                ready,
                ready < 0 ? errno : 0,
                request->type
            );
            if (reply_sockets[1] >= 0) close(reply_sockets[1]);
            continue;
        }
        const bool response_read = receive_gateway_response(
            reply_sockets[1],
            &response,
            &received_fd
        );
        const int response_errno = response_read ? 0 : errno;
        if (response_read) {
            if (caller_response != nullptr) {
                *caller_response = response;
            }
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
            if (returned_fd != nullptr && resolved_fd >= 0) {
                *returned_fd = resolved_fd;
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
