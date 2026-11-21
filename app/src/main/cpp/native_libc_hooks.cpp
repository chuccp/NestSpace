#include "native_environment_internal.h"

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/capability.h>
#include <linux/stat.h>
#include <sys/xattr.h>
#include <sys/stat.h>
#include <sys/ptrace.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <sys/uio.h>
#include <unwind.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cinttypes>
#include <cstdarg>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

extern "C" void arm_native_object_watch(void* address, size_t size);

namespace nestspace {
namespace {

thread_local int g_path_hook_depth = 0;
bool install_inline_jump(
    void* target,
    void* replacement,
    void** trampoline
);

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
        g_real.getgid = reinterpret_cast<GetgidFunction>(
            dlsym(RTLD_DEFAULT, "getgid")
        );
        g_real.getegid = reinterpret_cast<GetgidFunction>(
            dlsym(RTLD_DEFAULT, "getegid")
        );
        g_real.getresuid = reinterpret_cast<GetresuidFunction>(
            dlsym(RTLD_DEFAULT, "getresuid")
        );
        g_real.getresgid = reinterpret_cast<GetresgidFunction>(
            dlsym(RTLD_DEFAULT, "getresgid")
        );
        g_real.getgroups = reinterpret_cast<GetgroupsFunction>(
            dlsym(RTLD_DEFAULT, "getgroups")
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
        g_real.faccessat2 = reinterpret_cast<FaccessatFunction>(
            dlsym(RTLD_DEFAULT, "faccessat2")
        );
        g_real.getcwd = reinterpret_cast<GetcwdFunction>(
            dlsym(RTLD_DEFAULT, "getcwd")
        );
        g_real.getxattr = reinterpret_cast<XattrFunction>(
            dlsym(RTLD_DEFAULT, "getxattr")
        );
        g_real.lgetxattr = reinterpret_cast<XattrFunction>(
            dlsym(RTLD_DEFAULT, "lgetxattr")
        );
        g_real.fgetxattr = reinterpret_cast<FgetxattrFunction>(
            dlsym(RTLD_DEFAULT, "fgetxattr")
        );
        g_real.listxattr = reinterpret_cast<ListxattrFunction>(
            dlsym(RTLD_DEFAULT, "listxattr")
        );
        g_real.llistxattr = reinterpret_cast<ListxattrFunction>(
            dlsym(RTLD_DEFAULT, "llistxattr")
        );
        g_real.flistxattr = reinterpret_cast<FlistxattrFunction>(
            dlsym(RTLD_DEFAULT, "flistxattr")
        );
        g_real.stat = reinterpret_cast<StatFunction>(dlsym(RTLD_DEFAULT, "stat"));
        g_real.stat64 = reinterpret_cast<StatFunction>(
            dlsym(RTLD_DEFAULT, "stat64")
        );
        g_real.lstat = reinterpret_cast<StatFunction>(
            dlsym(RTLD_DEFAULT, "lstat")
        );
        g_real.lstat64 = reinterpret_cast<StatFunction>(
            dlsym(RTLD_DEFAULT, "lstat64")
        );
        g_real.statfs = reinterpret_cast<StatfsFunction>(
            dlsym(RTLD_DEFAULT, "statfs")
        );
        g_real.statvfs = reinterpret_cast<StatvfsFunction>(
            dlsym(RTLD_DEFAULT, "statvfs")
        );
        g_real.fstat = reinterpret_cast<FstatFunction>(
            dlsym(RTLD_DEFAULT, "fstat")
        );
        g_real.fstat64 = reinterpret_cast<FstatFunction>(
            dlsym(RTLD_DEFAULT, "fstat64")
        );
        g_real.fstatat = reinterpret_cast<FstatatFunction>(
            dlsym(RTLD_DEFAULT, "fstatat")
        );
        g_real.fstatat64 = reinterpret_cast<FstatatFunction>(
            dlsym(RTLD_DEFAULT, "fstatat64")
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
        g_real.mkdir = reinterpret_cast<MkdirFunction>(
            dlsym(RTLD_DEFAULT, "mkdir")
        );
        g_real.mkdirat = reinterpret_cast<MkdiratFunction>(
            dlsym(RTLD_DEFAULT, "mkdirat")
        );
        g_real.rmdir = reinterpret_cast<RmdirFunction>(
            dlsym(RTLD_DEFAULT, "rmdir")
        );
        g_real.chmod = reinterpret_cast<ChmodFunction>(
            dlsym(RTLD_DEFAULT, "chmod")
        );
        g_real.fchmodat = reinterpret_cast<FchmodatFunction>(
            dlsym(RTLD_DEFAULT, "fchmodat")
        );
        g_real.fchmod = reinterpret_cast<FchmodFunction>(
            dlsym(RTLD_DEFAULT, "fchmod")
        );
        g_real.unlink = reinterpret_cast<UnlinkFunction>(
            dlsym(RTLD_DEFAULT, "unlink")
        );
        g_real.unlinkat = reinterpret_cast<UnlinkatFunction>(
            dlsym(RTLD_DEFAULT, "unlinkat")
        );
        g_real.rename = reinterpret_cast<RenameFunction>(
            dlsym(RTLD_DEFAULT, "rename")
        );
        g_real.renameat = reinterpret_cast<RenameatFunction>(
            dlsym(RTLD_DEFAULT, "renameat")
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
        g_real.mallocCall = reinterpret_cast<MallocFunction>(
            dlsym(RTLD_DEFAULT, "malloc")
        );
        g_real.callocCall = reinterpret_cast<CallocFunction>(
            dlsym(RTLD_DEFAULT, "calloc")
        );
        g_real.reallocCall = reinterpret_cast<ReallocFunction>(
            dlsym(RTLD_DEFAULT, "realloc")
        );
        g_real.freeCall = reinterpret_cast<FreeFunction>(
            dlsym(RTLD_DEFAULT, "free")
        );
        g_real.memcpyCall = reinterpret_cast<MemcpyFunction>(
            dlsym(RTLD_DEFAULT, "memcpy")
        );
        g_real.memmoveCall = reinterpret_cast<MemmoveFunction>(
            dlsym(RTLD_DEFAULT, "memmove")
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
        g_real.sigactionCall = reinterpret_cast<SigactionFunction>(
            dlsym(RTLD_DEFAULT, "sigaction")
        );
        g_real.signalCall = reinterpret_cast<SignalFunction>(
            dlsym(RTLD_DEFAULT, "signal")
        );
        g_real.memsetCall = reinterpret_cast<MemsetFunction>(
            dlsym(RTLD_DEFAULT, "memset")
        );
        g_real.dlopen = reinterpret_cast<DlopenFunction>(
            dlsym(RTLD_DEFAULT, "dlopen")
        );
        g_real.tgkill = reinterpret_cast<TgkillFunction>(
            dlsym(RTLD_DEFAULT, "tgkill")
        );
        g_real.killProcess = reinterpret_cast<KillFunction>(
            dlsym(RTLD_DEFAULT, "kill")
        );
        g_real.raiseSignal = reinterpret_cast<RaiseFunction>(
            dlsym(RTLD_DEFAULT, "raise")
        );
        g_real.abortProcess = reinterpret_cast<AbortFunction>(
            dlsym(RTLD_DEFAULT, "abort")
        );
        g_real.exitProcess = reinterpret_cast<ExitFunction>(
            dlsym(RTLD_DEFAULT, "exit")
        );
        g_real.exitImmediately = reinterpret_cast<ExitFunction>(
            dlsym(RTLD_DEFAULT, "_exit")
        );
        g_real.mmapCall = reinterpret_cast<MmapFunction>(
            dlsym(RTLD_DEFAULT, "mmap")
        );
        g_real.munmapCall = reinterpret_cast<MunmapFunction>(
            dlsym(RTLD_DEFAULT, "munmap")
        );
        g_real.mprotectCall = reinterpret_cast<MprotectFunction>(
            dlsym(RTLD_DEFAULT, "mprotect")
        );
        g_real.clearCacheCall = reinterpret_cast<ClearCacheFunction>(
            dlsym(RTLD_DEFAULT, "__clear_cache")
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

void log_runtime_library_open(
    const char* api,
    const char* requested,
    const char* actual,
    int result
) {
    const Environment* environment = current_environment();
    if (requested == nullptr ||
        environment == nullptr ||
        strstr(requested, ".so") == nullptr ||
        strncmp(requested, environment->data_root.c_str(),
            environment->data_root.size()) != 0) {
        return;
    }
    const int saved_errno = errno;
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "runtime-library-open api=%s requested=%s actual=%s result=%d errno=%d",
        api,
        requested,
        actual == nullptr ? "?" : actual,
        result,
        result < 0 ? saved_errno : 0
    );
    errno = saved_errno;
}

void log_database_path(
    const char* api,
    const char* requested,
    const char* actual,
    int result
) {
    if (requested == nullptr ||
        strstr(requested, "/databases") == nullptr) {
        return;
    }
    const int saved_errno = errno;
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "target-database api=%s requested=%s actual=%s result=%d errno=%d",
        api,
        requested,
        actual == nullptr ? "?" : actual,
        result,
        result < 0 ? saved_errno : 0
    );
    errno = saved_errno;
}

void log_database_stat(
    const char* api,
    const char* path,
    int result,
    const struct stat* value
) {
    if (getenv("NESTSPACE_DATABASE_TRACE") == nullptr ||
        path == nullptr ||
        strstr(path, "/databases") == nullptr ||
        result != 0 ||
        value == nullptr) {
        return;
    }
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "target-database-stat api=%s path=%s dev=%llu ino=%llu "
        "uid=%u gid=%u mode=%o size=%lld",
        api,
        path,
        static_cast<unsigned long long>(value->st_dev),
        static_cast<unsigned long long>(value->st_ino),
        static_cast<unsigned int>(value->st_uid),
        static_cast<unsigned int>(value->st_gid),
        static_cast<unsigned int>(value->st_mode),
        static_cast<long long>(value->st_size)
    );
}

void log_database_fd_identity(
    const char* api,
    const char* requested,
    const char* actual,
    int fd
) {
    if (getenv("NESTSPACE_DATABASE_TRACE") == nullptr ||
        requested == nullptr ||
        strstr(requested, "/databases") == nullptr ||
        fd < 0) {
        return;
    }
    struct stat fd_stat {};
    struct stat path_stat {};
    const int fd_result = g_real.fstat
        ? g_real.fstat(fd, &fd_stat)
        : fstat(fd, &fd_stat);
    const int path_result = g_real.stat
        ? g_real.stat(actual, &path_stat)
        : stat(actual, &path_stat);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "target-database-identity api=%s fd=%d fd_result=%d "
        "fd_dev=%llu fd_ino=%llu path_result=%d path_dev=%llu path_ino=%llu",
        api,
        fd,
        fd_result,
        static_cast<unsigned long long>(fd_stat.st_dev),
        static_cast<unsigned long long>(fd_stat.st_ino),
        path_result,
        static_cast<unsigned long long>(path_stat.st_dev),
        static_cast<unsigned long long>(path_stat.st_ino)
    );
}

void ensure_parent_directory(const char* actual) {
    if (actual == nullptr || actual[0] == '\0' || g_real.mkdir == nullptr) {
        return;
    }
    std::string path(actual);
    const size_t slash = path.find_last_of('/');
    if (slash == std::string::npos || slash == 0) return;
    path.resize(slash);
    for (size_t index = 1; index <= path.size(); ++index) {
        if (index != path.size() && path[index] != '/') continue;
        const std::string directory = path.substr(0, index);
        if (!directory.empty()) {
            g_real.mkdir(directory.c_str(), 0700);
        }
    }
}

}  // namespace

void hook_clear_cache(void* begin, void* end);

namespace {

int hook_sigaction(
    int signal_number,
    const struct sigaction* action,
    struct sigaction* previous
) {
    if (nestspace::syscall_gateway_active() &&
        signal_number == SIGSYS &&
        action != nullptr &&
        !nestspace::is_syscall_gateway_action(action)) {
        if (g_real.sigactionCall == nullptr) {
            errno = ENOSYS;
            return -1;
        }
        const int result = g_real.sigactionCall(
            signal_number,
            nullptr,
            previous
        );
        if (result == 0) {
            __android_log_print(
                ANDROID_LOG_WARN,
                "NestSpaceNative",
                "preserved syscall gateway SIGSYS action flags=%08x",
                action->sa_flags
            );
        }
        return result;
    }
    if (packer_relocation_guard_active() &&
        signal_number == SIGSEGV &&
        action != nullptr &&
        !is_packer_relocation_action(action)) {
        if (g_real.sigactionCall == nullptr) {
            errno = ENOSYS;
            return -1;
        }
        const int result = g_real.sigactionCall(
            signal_number,
            nullptr,
            previous
        );
        if (result == 0) {
            capture_previous_segv_action(action);
            __android_log_print(
                ANDROID_LOG_WARN,
                "NestSpaceNative",
                "preserved packer SIGSEGV action flags=%08x",
                action->sa_flags
            );
        }
        return result;
    }
    return g_real.sigactionCall
        ? g_real.sigactionCall(signal_number, action, previous)
        : sigaction(signal_number, action, previous);
}

sighandler_t hook_signal(int signal_number, sighandler_t handler) {
    if (nestspace::syscall_gateway_active() &&
        signal_number == SIGSYS) {
        struct sigaction requested {};
        requested.sa_handler = handler;
        if (!nestspace::is_syscall_gateway_action(&requested)) {
            if (g_real.sigactionCall == nullptr) {
                errno = ENOSYS;
                return SIG_ERR;
            }
            struct sigaction current {};
            if (g_real.sigactionCall(
                    signal_number,
                    nullptr,
                    &current
                ) != 0) {
                return SIG_ERR;
            }
            __android_log_print(
                ANDROID_LOG_WARN,
                "NestSpaceNative",
                "preserved syscall gateway SIGSYS handler=%p",
                reinterpret_cast<void*>(handler)
            );
            if ((current.sa_flags & SA_SIGINFO) != 0) {
                return reinterpret_cast<sighandler_t>(
                    current.sa_sigaction
                );
            }
            return current.sa_handler;
        }
    }
    if (packer_relocation_guard_active() && signal_number == SIGSEGV) {
        struct sigaction action {};
        action.sa_handler = handler;
        if (!is_packer_relocation_action(&action)) {
            if (g_real.sigactionCall == nullptr) {
                errno = ENOSYS;
                return SIG_ERR;
            }
            struct sigaction current {};
            if (g_real.sigactionCall(
                    signal_number,
                    nullptr,
                    &current
                ) != 0) {
                return SIG_ERR;
            }
            capture_previous_segv_action(&action);
            __android_log_print(
                ANDROID_LOG_WARN,
                "NestSpaceNative",
                "preserved packer signal SIGSEGV handler=%p",
                reinterpret_cast<void*>(handler)
            );
            if ((current.sa_flags & SA_SIGINFO) != 0) {
                return reinterpret_cast<sighandler_t>(
                    current.sa_sigaction
                );
            }
            return current.sa_handler;
        }
    }
    return g_real.signalCall
        ? g_real.signalCall(signal_number, handler)
        : signal(signal_number, handler);
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
        void* sigaction_trampoline = nullptr;
        void* signal_trampoline = nullptr;
        void* open_trampoline = nullptr;
        void* openat_trampoline = nullptr;
        void* open64_trampoline = nullptr;
        void* openat64_trampoline = nullptr;
        void* mkdir_trampoline = nullptr;
        void* mkdirat_trampoline = nullptr;
        void* rmdir_trampoline = nullptr;
        void* unlink_trampoline = nullptr;
        void* unlinkat_trampoline = nullptr;
        void* rename_trampoline = nullptr;
        void* renameat_trampoline = nullptr;
        void* tgkill_trampoline = nullptr;
        void* exit_trampoline = nullptr;
        void* immediate_exit_trampoline = nullptr;
        const OpenFunction original_open = g_real.open;
        const OpenFunction original_open64 = g_real.open64;
        const OpenatFunction original_openat = g_real.openat;
        const OpenatFunction original_openat64 = g_real.openat64;

        if (g_real.sigactionCall != nullptr &&
            install_inline_jump(
                reinterpret_cast<void*>(g_real.sigactionCall),
                reinterpret_cast<void*>(hook_sigaction),
                &sigaction_trampoline
            )) {
            g_real.sigactionCall =
                reinterpret_cast<SigactionFunction>(sigaction_trampoline);
        }
        if (g_real.signalCall != nullptr &&
            install_inline_jump(
                reinterpret_cast<void*>(g_real.signalCall),
                reinterpret_cast<void*>(hook_signal),
                &signal_trampoline
            )) {
            g_real.signalCall =
                reinterpret_cast<SignalFunction>(signal_trampoline);
        }

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
        if (g_real.mkdir != nullptr &&
            install_inline_jump(
                reinterpret_cast<void*>(g_real.mkdir),
                reinterpret_cast<void*>(hook_mkdir),
                &mkdir_trampoline
            )) {
            g_real.mkdir = reinterpret_cast<MkdirFunction>(mkdir_trampoline);
        }
        if (g_real.mkdirat != nullptr &&
            install_inline_jump(
                reinterpret_cast<void*>(g_real.mkdirat),
                reinterpret_cast<void*>(hook_mkdirat),
                &mkdirat_trampoline
            )) {
            g_real.mkdirat = reinterpret_cast<MkdiratFunction>(
                mkdirat_trampoline
            );
        }
        if (g_real.rmdir != nullptr &&
            install_inline_jump(
                reinterpret_cast<void*>(g_real.rmdir),
                reinterpret_cast<void*>(hook_rmdir),
                &rmdir_trampoline
            )) {
            g_real.rmdir = reinterpret_cast<RmdirFunction>(rmdir_trampoline);
        }
        if (g_real.unlink != nullptr &&
            install_inline_jump(
                reinterpret_cast<void*>(g_real.unlink),
                reinterpret_cast<void*>(hook_unlink),
                &unlink_trampoline
            )) {
            g_real.unlink = reinterpret_cast<UnlinkFunction>(
                unlink_trampoline
            );
        }
        if (g_real.unlinkat != nullptr &&
            install_inline_jump(
                reinterpret_cast<void*>(g_real.unlinkat),
                reinterpret_cast<void*>(hook_unlinkat),
                &unlinkat_trampoline
            )) {
            g_real.unlinkat = reinterpret_cast<UnlinkatFunction>(
                unlinkat_trampoline
            );
        }
        if (g_real.rename != nullptr &&
            install_inline_jump(
                reinterpret_cast<void*>(g_real.rename),
                reinterpret_cast<void*>(hook_rename),
                &rename_trampoline
            )) {
            g_real.rename = reinterpret_cast<RenameFunction>(
                rename_trampoline
            );
        }
        if (g_real.renameat != nullptr &&
            install_inline_jump(
                reinterpret_cast<void*>(g_real.renameat),
                reinterpret_cast<void*>(hook_renameat),
                &renameat_trampoline
            )) {
            g_real.renameat = reinterpret_cast<RenameatFunction>(
                renameat_trampoline
            );
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
    if (getenv("NESTSPACE_INLINE_PROPERTY_HOOKS") == nullptr) {
        char value[PROP_VALUE_MAX] = {};
        __system_property_get(
            "debug.nestspace.inline_property_hooks",
            value
        );
        if (strcmp(value, "1") != 0) return;
    }
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

using RegisterNativesFunction = jint (*)(
    JNIEnv*,
    jclass,
    const JNINativeMethod*,
    jint
);
RegisterNativesFunction g_registerNatives = nullptr;
decltype(JNINativeInterface::GetMethodID) g_get_method_id = nullptr;
decltype(JNINativeInterface::GetFieldID) g_get_field_id = nullptr;
decltype(JNINativeInterface::ExceptionCheck) g_exception_check = nullptr;
decltype(JNINativeInterface::GetObjectClass) g_get_object_class = nullptr;
decltype(JNINativeInterface::GetObjectField) g_get_object_field = nullptr;
decltype(JNINativeInterface::FindClass) g_find_class = nullptr;
decltype(JNINativeInterface::GetStaticMethodID) g_get_static_method_id = nullptr;
decltype(JNINativeInterface::GetStaticFieldID) g_get_static_field_id = nullptr;
decltype(JNINativeInterface::CallObjectMethodV) g_call_object_method_v = nullptr;
decltype(JNINativeInterface::CallStaticObjectMethodV)
    g_call_static_object_method_v = nullptr;
decltype(JNINativeInterface::CallIntMethodV) g_call_int_method_v = nullptr;
decltype(JNINativeInterface::CallBooleanMethodV)
    g_call_boolean_method_v = nullptr;
decltype(JNINativeInterface::CallStaticBooleanMethodV)
    g_call_static_boolean_method_v = nullptr;
decltype(JNINativeInterface::CallStaticVoidMethodV)
    g_call_static_void_method_v = nullptr;
decltype(JNINativeInterface::CallStaticVoidMethodA)
    g_call_static_void_method_a = nullptr;
std::unordered_map<jfieldID, std::string> g_probe_fields;
std::unordered_map<jmethodID, std::string> g_probe_methods;

const char* probe_method_name(jmethodID method) {
    const auto found = g_probe_methods.find(method);
    return found == g_probe_methods.end() ? "?" : found->second.c_str();
}

void virtualize_package_info_uid(
    JNIEnv* env,
    jobject result,
    const char* method_name
) {
    if (env == nullptr || result == nullptr || method_name == nullptr ||
        strstr(method_name, "getPackageInfo") == nullptr) {
        return;
    }
    jclass package_info_class = env->GetObjectClass(result);
    if (package_info_class == nullptr) return;
    jfieldID application_info_field = env->GetFieldID(
        package_info_class,
        "applicationInfo",
        "Landroid/content/pm/ApplicationInfo;"
    );
    jobject application_info = application_info_field == nullptr
        ? nullptr
        : env->GetObjectField(result, application_info_field);
    jclass application_info_class = application_info == nullptr
        ? nullptr
        : env->GetObjectClass(application_info);
    jfieldID uid_field = application_info_class == nullptr
        ? nullptr
        : env->GetFieldID(application_info_class, "uid", "I");
    if (uid_field != nullptr) {
        const jint previous = env->GetIntField(application_info, uid_field);
        const jint effective = static_cast<jint>(getuid());
        env->SetIntField(application_info, uid_field, effective);
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "virtualized PackageInfo uid %d -> %d",
            previous,
            effective
        );
    }
    jfieldID signatures_field = env->GetFieldID(
        package_info_class,
        "signatures",
        "[Landroid/content/pm/Signature;"
    );
    auto signatures = signatures_field == nullptr
        ? static_cast<jobjectArray>(nullptr)
        : static_cast<jobjectArray>(
            env->GetObjectField(result, signatures_field)
        );
    if (signatures != nullptr && env->GetArrayLength(signatures) > 0) {
        jobject signature = env->GetObjectArrayElement(signatures, 0);
        jclass signature_class = signature == nullptr
            ? nullptr
            : env->GetObjectClass(signature);
        jmethodID hash_code = signature_class == nullptr
            ? nullptr
            : env->GetMethodID(signature_class, "hashCode", "()I");
        const jint hash = hash_code == nullptr
            ? 0
            : env->CallIntMethod(signature, hash_code);
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "virtualized PackageInfo signatureCount=%d firstHash=%d",
            env->GetArrayLength(signatures),
            hash
        );
        if (signature_class != nullptr) env->DeleteLocalRef(signature_class);
        if (signature != nullptr) env->DeleteLocalRef(signature);
    }
    if (signatures != nullptr) env->DeleteLocalRef(signatures);
    if (application_info_class != nullptr) {
        env->DeleteLocalRef(application_info_class);
    }
    if (application_info != nullptr) env->DeleteLocalRef(application_info);
    env->DeleteLocalRef(package_info_class);
}

jobject probe_call_object_method(
    JNIEnv* env,
    jobject object,
    jmethodID method,
    ...
) {
    va_list arguments;
    va_start(arguments, method);
    const jobject result = g_call_object_method_v(
        env,
        object,
        method,
        arguments
    );
    va_end(arguments);
    const char* method_name = probe_method_name(method);
    virtualize_package_info_uid(env, result, method_name);
    if (result != nullptr && strstr(method_name, "()Ljava/lang/String;") != nullptr) {
        const char* value = env->GetStringUTFChars(
            static_cast<jstring>(result),
            nullptr
        );
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe CallObjectMethod method=%s result=%p value=%s",
            method_name,
            result,
            value == nullptr ? "?" : value
        );
        if (value != nullptr) {
            env->ReleaseStringUTFChars(static_cast<jstring>(result), value);
        }
    } else {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe CallObjectMethod method=%s result=%p",
            method_name,
            result
        );
    }
    return result;
}

jobject probe_call_static_object_method(
    JNIEnv* env,
    jclass clazz,
    jmethodID method,
    ...
) {
    va_list arguments;
    va_start(arguments, method);
    const jobject result = g_call_static_object_method_v(
        env,
        clazz,
        method,
        arguments
    );
    va_end(arguments);
    const char* method_name = probe_method_name(method);
    if (result != nullptr && strstr(method_name, "()Ljava/lang/String;") != nullptr) {
        const char* value = env->GetStringUTFChars(
            static_cast<jstring>(result),
            nullptr
        );
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe CallStaticObjectMethod method=%s result=%p value=%s",
            method_name,
            result,
            value == nullptr ? "?" : value
        );
        if (value != nullptr) {
            env->ReleaseStringUTFChars(static_cast<jstring>(result), value);
        }
    } else {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe CallStaticObjectMethod method=%s result=%p",
            method_name,
            result
        );
    }
    return result;
}

jobject probe_call_object_method_v(
    JNIEnv* env,
    jobject object,
    jmethodID method,
    va_list arguments
) {
    const jobject result = g_call_object_method_v(
        env,
        object,
        method,
        arguments
    );
    const char* method_name = probe_method_name(method);
    virtualize_package_info_uid(env, result, method_name);
    if (result != nullptr && strstr(method_name, "()Ljava/lang/String;") != nullptr) {
        const char* value = env->GetStringUTFChars(
            static_cast<jstring>(result),
            nullptr
        );
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe CallObjectMethodV method=%s result=%p value=%s",
            method_name,
            result,
            value == nullptr ? "?" : value
        );
        if (value != nullptr) {
            env->ReleaseStringUTFChars(static_cast<jstring>(result), value);
        }
    } else {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe CallObjectMethodV method=%s result=%p",
            method_name,
            result
        );
    }
    return result;
}

jobject probe_call_static_object_method_v(
    JNIEnv* env,
    jclass clazz,
    jmethodID method,
    va_list arguments
) {
    const jobject result = g_call_static_object_method_v(
        env,
        clazz,
        method,
        arguments
    );
    const char* method_name = probe_method_name(method);
    if (result != nullptr && strstr(method_name, "()Ljava/lang/String;") != nullptr) {
        const char* value = env->GetStringUTFChars(
            static_cast<jstring>(result),
            nullptr
        );
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe CallStaticObjectMethodV method=%s result=%p value=%s",
            method_name,
            result,
            value == nullptr ? "?" : value
        );
        if (value != nullptr) {
            env->ReleaseStringUTFChars(static_cast<jstring>(result), value);
        }
    } else {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe CallStaticObjectMethodV method=%s result=%p",
            method_name,
            result
        );
    }
    return result;
}

jint probe_call_int_method(
    JNIEnv* env,
    jobject object,
    jmethodID method,
    ...
) {
    va_list arguments;
    va_start(arguments, method);
    const jint result = g_call_int_method_v(env, object, method, arguments);
    va_end(arguments);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallIntMethod method=%s result=%d",
        probe_method_name(method),
        result
    );
    return result;
}

jint probe_call_int_method_v(
    JNIEnv* env,
    jobject object,
    jmethodID method,
    va_list arguments
) {
    const char* method_name = probe_method_name(method);
    const bool permission_check =
        strstr(method_name, "checkPermission") != nullptr;
    jstring permission_value = nullptr;
    const char* permission = nullptr;
    jint permission_pid = -1;
    jint permission_uid = -1;
    if (permission_check) {
        va_list copied;
        va_copy(copied, arguments);
        permission_value = va_arg(copied, jstring);
        permission_pid = va_arg(copied, jint);
        permission_uid = va_arg(copied, jint);
        permission = permission_value == nullptr
            ? nullptr
            : env->GetStringUTFChars(permission_value, nullptr);
        va_end(copied);
    }
    const jint result = g_call_int_method_v(env, object, method, arguments);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallIntMethodV method=%s result=%d permission=%s pid=%d uid=%d",
        method_name,
        result,
        permission == nullptr ? "?" : permission,
        permission_pid,
        permission_uid
    );
    if (permission != nullptr) {
        env->ReleaseStringUTFChars(
            permission_value,
            permission
        );
    }
    return result;
}

jboolean probe_call_boolean_method(
    JNIEnv* env,
    jobject object,
    jmethodID method,
    ...
) {
    va_list arguments;
    va_start(arguments, method);
    const jboolean result = g_call_boolean_method_v(
        env,
        object,
        method,
        arguments
    );
    va_end(arguments);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallBooleanMethod method=%s result=%d",
        probe_method_name(method),
        result
    );
    return result;
}

jboolean probe_call_static_boolean_method(
    JNIEnv* env,
    jclass clazz,
    jmethodID method,
    ...
) {
    va_list arguments;
    va_start(arguments, method);
    const jboolean result = g_call_static_boolean_method_v(
        env,
        clazz,
        method,
        arguments
    );
    va_end(arguments);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallStaticBooleanMethod method=%s result=%d",
        probe_method_name(method),
        result
    );
    return result;
}

jboolean probe_call_boolean_method_v(
    JNIEnv* env,
    jobject object,
    jmethodID method,
    va_list arguments
) {
    const jboolean result = g_call_boolean_method_v(
        env,
        object,
        method,
        arguments
    );
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallBooleanMethodV method=%s result=%d",
        probe_method_name(method),
        result
    );
    return result;
}

jboolean probe_call_static_boolean_method_v(
    JNIEnv* env,
    jclass clazz,
    jmethodID method,
    va_list arguments
) {
    const jboolean result = g_call_static_boolean_method_v(
        env,
        clazz,
        method,
        arguments
    );
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallStaticBooleanMethodV method=%s result=%d",
        probe_method_name(method),
        result
    );
    return result;
}

void probe_call_static_void_method_v(
    JNIEnv* env,
    jclass clazz,
    jmethodID method,
    va_list arguments
) {
    const char* method_name = probe_method_name(method);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallStaticVoidMethodV method=%s id=%p",
        method_name,
        method
    );
    if (strstr(method_name, "exit:(I)V") != nullptr ||
        strstr(method_name, "halt:(I)V") != nullptr) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "suppressed virtual machine termination method=%s",
            method_name
        );
        return;
    }
    g_call_static_void_method_v(env, clazz, method, arguments);
}

void probe_call_static_void_method(
    JNIEnv* env,
    jclass clazz,
    jmethodID method,
    ...
) {
    va_list arguments;
    va_start(arguments, method);
    const char* method_name = probe_method_name(method);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallStaticVoidMethod method=%s id=%p",
        method_name,
        method
    );
    if (strstr(method_name, "exit:(I)V") == nullptr &&
        strstr(method_name, "halt:(I)V") == nullptr) {
        g_call_static_void_method_v(env, clazz, method, arguments);
    }
    va_end(arguments);
}

void probe_call_static_void_method_a(
    JNIEnv* env,
    jclass clazz,
    jmethodID method,
    const jvalue* arguments
) {
    const char* method_name = probe_method_name(method);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallStaticVoidMethodA method=%s id=%p",
        method_name,
        method
    );
    if (strstr(method_name, "exit:(I)V") != nullptr ||
        strstr(method_name, "halt:(I)V") != nullptr) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "suppressed virtual machine termination method=%s",
            method_name
        );
        return;
    }
    g_call_static_void_method_a(env, clazz, method, arguments);
}

jclass probe_find_class(JNIEnv* env, const char* name) {
    const jclass result = g_find_class(env, name);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe FindClass name=%s result=%p",
        name == nullptr ? "?" : name,
        result
    );
    return result;
}

jmethodID probe_get_static_method_id(
    JNIEnv* env,
    jclass clazz,
    const char* name,
    const char* signature
) {
    const jmethodID result = g_get_static_method_id(env, clazz, name, signature);
    if (result != nullptr) {
        g_probe_methods[result] = std::string(name == nullptr ? "?" : name) +
            ":" + (signature == nullptr ? "?" : signature);
    }
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe GetStaticMethodID class=%p name=%s signature=%s result=%p",
        clazz,
        name == nullptr ? "?" : name,
        signature == nullptr ? "?" : signature,
        result
    );
    return result;
}

jfieldID probe_get_static_field_id(
    JNIEnv* env,
    jclass clazz,
    const char* name,
    const char* signature
) {
    const jfieldID result = g_get_static_field_id(env, clazz, name, signature);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe GetStaticFieldID class=%p name=%s signature=%s result=%p",
        clazz,
        name == nullptr ? "?" : name,
        signature == nullptr ? "?" : signature,
        result
    );
    return result;
}

jclass probe_get_object_class(JNIEnv* env, jobject object) {
    const jclass result = g_get_object_class(env, object);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe GetObjectClass object=%p result=%p",
        object,
        result
    );
    return result;
}

jmethodID probe_get_method_id(
    JNIEnv* env,
    jclass clazz,
    const char* name,
    const char* signature
) {
    const jmethodID result = g_get_method_id(env, clazz, name, signature);
    if (result != nullptr) {
        g_probe_methods[result] = std::string(name == nullptr ? "?" : name) +
            ":" + (signature == nullptr ? "?" : signature);
    }
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe GetMethodID class=%p name=%s signature=%s result=%p",
        clazz,
        name == nullptr ? "?" : name,
        signature == nullptr ? "?" : signature,
        result
    );
    return result;
}

jfieldID probe_get_field_id(
    JNIEnv* env,
    jclass clazz,
    const char* name,
    const char* signature
) {
    const jfieldID result = g_get_field_id(env, clazz, name, signature);
    if (result != nullptr) {
        g_probe_fields[result] = std::string(name == nullptr ? "?" : name) +
            ":" + (signature == nullptr ? "?" : signature);
    }
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe GetFieldID class=%p name=%s signature=%s result=%p",
        clazz,
        name == nullptr ? "?" : name,
        signature == nullptr ? "?" : signature,
        result
    );
    return result;
}

jobject probe_get_object_field(
    JNIEnv* env,
    jobject object,
    jfieldID field
) {
    const jobject result = g_get_object_field(env, object, field);
    const auto found = g_probe_fields.find(field);
    const char* field_name = found == g_probe_fields.end()
        ? "?"
        : found->second.c_str();
    if (result != nullptr &&
        found != g_probe_fields.end() &&
        found->second.find(":Ljava/lang/String;") != std::string::npos) {
        const char* value = env->GetStringUTFChars(
            static_cast<jstring>(result),
            nullptr
        );
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe GetObjectField field=%s object=%p result=%p value=%s",
            field_name,
            object,
            result,
            value == nullptr ? "?" : value
        );
        if (value != nullptr) {
            env->ReleaseStringUTFChars(static_cast<jstring>(result), value);
        }
    } else {
        const uintptr_t caller = reinterpret_cast<uintptr_t>(
            __builtin_return_address(0)
        );
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe GetObjectField field=%s object=%p result=%p caller=%p",
            field_name,
            object,
            result,
            reinterpret_cast<void*>(caller)
        );
    }
    return result;
}

jboolean probe_exception_check(JNIEnv* env) {
    const jboolean result = g_exception_check(env);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe ExceptionCheck result=%d",
        result
    );
    return result;
}

void suppress_native_abort(JNIEnv*, jobject) {
    __android_log_print(
        ANDROID_LOG_WARN,
        "NestSpaceNative",
        "native abort callback invoked"
    );
}

jint hook_register_natives(
    JNIEnv* env,
    jclass clazz,
    const JNINativeMethod* methods,
    jint count
) {
    std::vector<JNINativeMethod> registered_methods;
    bool changed = false;
    if (env != nullptr && clazz != nullptr && methods != nullptr) {
        jclass class_class = env->FindClass("java/lang/Class");
        jmethodID get_name = class_class == nullptr
            ? nullptr
            : env->GetMethodID(
                class_class,
                "getName",
                "()Ljava/lang/String;"
            );
        auto class_name = get_name == nullptr
            ? static_cast<jstring>(nullptr)
            : static_cast<jstring>(env->CallObjectMethod(clazz, get_name));
        const char* class_chars = class_name == nullptr
            ? nullptr
            : env->GetStringUTFChars(class_name, nullptr);
        for (jint index = 0; index < count; ++index) {
            __android_log_print(
                ANDROID_LOG_DEBUG,
                "NestSpaceNative",
                "RegisteredNative class=%s name=%s signature=%s address=%p",
                class_chars == nullptr ? "?" : class_chars,
                methods[index].name == nullptr ? "?" : methods[index].name,
                methods[index].signature == nullptr
                    ? "?"
                    : methods[index].signature,
                methods[index].fnPtr
            );
            if (methods[index].name != nullptr &&
                methods[index].signature != nullptr &&
                strcmp(methods[index].name, "nativeAbort") == 0 &&
                strcmp(methods[index].signature, "()V") == 0) {
                if (!changed) {
                    registered_methods.assign(methods, methods + count);
                    changed = true;
                }
                registered_methods[index].fnPtr =
                    reinterpret_cast<void*>(&suppress_native_abort);
                __android_log_print(
                    ANDROID_LOG_WARN,
                    "NestSpaceNative",
                    "suppressed native abort callback class=%s",
                    class_chars == nullptr ? "?" : class_chars
                );
            }
        }
        if (class_chars != nullptr) {
            env->ReleaseStringUTFChars(class_name, class_chars);
        }
        if (class_name != nullptr) env->DeleteLocalRef(class_name);
        if (class_class != nullptr) env->DeleteLocalRef(class_class);
    }
    return g_registerNatives != nullptr
        ? g_registerNatives(
            env,
            clazz,
            changed ? registered_methods.data() : methods,
            count
        )
        : JNI_ERR;
}

void install_jni_registration_hook(JNIEnv* env) {
    if (env == nullptr || env->functions == nullptr) return;
    char enabled[PROP_VALUE_MAX] = {};
    __system_property_get(
        "debug.nestspace.jni_registration_hook",
        enabled
    );
    if (strcmp(enabled, "1") != 0) return;
    static std::once_flag once;
    std::call_once(once, [env] {
        void* trampoline = nullptr;
        auto* target = reinterpret_cast<void*>(
            env->functions->RegisterNatives
        );
        if (install_inline_jump(
                target,
                reinterpret_cast<void*>(hook_register_natives),
                &trampoline
            )) {
            g_registerNatives = reinterpret_cast<RegisterNativesFunction>(
                trampoline
            );
            __android_log_print(
                ANDROID_LOG_DEBUG,
                "NestSpaceNative",
                "JNI RegisterNatives probe target=%p",
                target
            );
        }
        static JNINativeInterface probe_functions;
        const JNINativeInterface* original_functions = env->functions;
        probe_functions = *original_functions;
        g_get_method_id = original_functions->GetMethodID;
        g_get_field_id = original_functions->GetFieldID;
        g_exception_check = original_functions->ExceptionCheck;
        g_get_object_class = original_functions->GetObjectClass;
        g_get_object_field = original_functions->GetObjectField;
        g_find_class = original_functions->FindClass;
        g_get_static_method_id = original_functions->GetStaticMethodID;
        g_get_static_field_id = original_functions->GetStaticFieldID;
        g_call_object_method_v = original_functions->CallObjectMethodV;
        g_call_static_object_method_v =
            original_functions->CallStaticObjectMethodV;
        g_call_int_method_v = original_functions->CallIntMethodV;
        g_call_boolean_method_v = original_functions->CallBooleanMethodV;
        g_call_static_boolean_method_v =
            original_functions->CallStaticBooleanMethodV;
        g_call_static_void_method_v =
            original_functions->CallStaticVoidMethodV;
        g_call_static_void_method_a =
            original_functions->CallStaticVoidMethodA;
        probe_functions.GetMethodID = probe_get_method_id;
        probe_functions.GetFieldID = probe_get_field_id;
        probe_functions.ExceptionCheck = probe_exception_check;
        probe_functions.GetObjectClass = probe_get_object_class;
        probe_functions.GetObjectField = probe_get_object_field;
        probe_functions.FindClass = probe_find_class;
        probe_functions.GetStaticMethodID = probe_get_static_method_id;
        probe_functions.GetStaticFieldID = probe_get_static_field_id;
        probe_functions.CallObjectMethod = probe_call_object_method;
        probe_functions.CallStaticObjectMethod = probe_call_static_object_method;
        probe_functions.CallIntMethod = probe_call_int_method;
        probe_functions.CallBooleanMethod = probe_call_boolean_method;
        probe_functions.CallStaticBooleanMethod =
            probe_call_static_boolean_method;
        probe_functions.CallObjectMethodV = probe_call_object_method_v;
        probe_functions.CallStaticObjectMethodV =
            probe_call_static_object_method_v;
        probe_functions.CallIntMethodV = probe_call_int_method_v;
        probe_functions.CallBooleanMethodV = probe_call_boolean_method_v;
        probe_functions.CallStaticBooleanMethodV =
            probe_call_static_boolean_method_v;
        probe_functions.CallStaticVoidMethodV =
            probe_call_static_void_method_v;
        probe_functions.CallStaticVoidMethod =
            probe_call_static_void_method;
        probe_functions.CallStaticVoidMethodA =
            probe_call_static_void_method_a;
    });
}

namespace {

bool has_path_prefix(const char* path, const std::string& prefix) {
    if (path == nullptr || prefix.empty()) return false;
    return strncmp(path, prefix.c_str(), prefix.size()) == 0 &&
        (path[prefix.size()] == '\0' || path[prefix.size()] == '/');
}

bool is_current_proc_identity_path(const char* path) {
    if (path == nullptr) return false;
    const char* suffix = nullptr;
    if (strcmp(path, "/proc/self") == 0) {
        return true;
    }
    if (strcmp(path, "/proc/thread-self") == 0) {
        return true;
    }
    if (strncmp(path, "/proc/self/", 11) == 0) {
        suffix = path + 11;
    } else if (strncmp(path, "/proc/thread-self/", 18) == 0) {
        suffix = path + 18;
    } else if (strncmp(path, "/proc/", 6) == 0) {
        char* end = nullptr;
        const long pid = strtol(path + 6, &end, 10);
        if ((pid != getpid() && pid != getppid()) ||
            end == path + 6 ||
            *end != '/') {
            return false;
        }
        suffix = end + 1;
    } else {
        return false;
    }
    return strncmp(suffix, "cwd", 3) != 0 &&
        strncmp(suffix, "exe", 3) != 0 &&
        strncmp(suffix, "root", 4) != 0 &&
        strncmp(suffix, "ns/", 3) != 0 &&
        strncmp(suffix, "fd/", 3) != 0 &&
        strncmp(suffix, "handle", 6) != 0 &&
        strncmp(suffix, "map_files/", 10) != 0;
}

std::string virtual_selinux_context(const char* actual) {
    const Environment* environment = current_environment();
    if (environment == nullptr || actual == nullptr) return {};
    if (is_current_proc_identity_path(actual)) {
        const int category = environment->observed_uid - 10000;
        if (category > 0) {
            return "u:r:untrusted_app:s0:c" +
                std::to_string(category) +
                ",c256,c512,c768";
        }
        return "u:r:untrusted_app:s0";
    }
    if (!environment->native_library_dir.empty() &&
        environment->native_library_dir !=
            environment->virtual_native_library_dir &&
        has_path_prefix(actual, environment->native_library_dir)) {
        return "u:object_r:apk_data_file:s0";
    }
    if (!environment->external_data_root.empty() &&
        has_path_prefix(actual, environment->external_data_root)) {
        return "u:object_r:fuse:s0";
    }
    if (has_path_prefix(actual, environment->data_root) ||
        has_path_prefix(actual, environment->actual_device_root)) {
        const int category = environment->observed_uid - 10000;
        if (category > 0) {
            return "u:object_r:app_data_file:s0:c" +
                std::to_string(category) +
                ",c256,c512,c768";
        }
        return "u:object_r:app_data_file:s0";
    }
    return {};
}

ssize_t copy_virtual_xattr(
    const std::string& value,
    void* buffer,
    size_t size
) {
    const size_t required = value.size() + 1;
    if (size == 0) return static_cast<ssize_t>(required);
    if (size < required) {
        errno = ERANGE;
        return -1;
    }
    if (buffer == nullptr) {
        errno = EFAULT;
        return -1;
    }
    memcpy(buffer, value.c_str(), required);
    return static_cast<ssize_t>(required);
}

bool virtualize_selinux_xattr(
    const char* actual,
    const char* name,
    void* buffer,
    size_t size,
    ssize_t* result
) {
    if (actual == nullptr || name == nullptr ||
        strcmp(name, "security.selinux") != 0) {
        return false;
    }
    const std::string context = virtual_selinux_context(actual);
    if (context.empty()) return false;
    static std::atomic<int> logged{0};
    if (logged.fetch_add(1, std::memory_order_relaxed) < 8) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "target-selinux-xattr path=%s value=%s",
            actual,
            context.c_str()
        );
    }
    *result = copy_virtual_xattr(context, buffer, size);
    return true;
}

std::string actual_path_for_fd(int fd) {
    if (fd < 0) return {};
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    std::array<char, PATH_MAX + 1> actual{};
    const ssize_t length = g_real.readlink
        ? g_real.readlink(path, actual.data(), PATH_MAX)
        : readlink(path, actual.data(), PATH_MAX);
    if (length <= 0) return {};
    actual[static_cast<size_t>(length)] = '\0';
    return actual.data();
}

}  // namespace

namespace {

constexpr size_t kDiagnosticObjectSize = 0x98;
constexpr size_t kDiagnosticTrackedObjects = 64;
constexpr uintptr_t kDiagnosticPointerTagMask = (1ULL << 56) - 1;
std::array<std::atomic<uintptr_t>, kDiagnosticTrackedObjects>
    g_diagnostic_objects{};
std::atomic<bool> g_diagnostic_watch_started{false};

bool object_diagnostics_enabled() {
    static const bool enabled = [] {
        if (getenv("NESTSPACE_OBJECT_TRACE") != nullptr) return true;
        char value[PROP_VALUE_MAX] = {};
        __system_property_get("debug.nestspace.object_trace", value);
        return strcmp(value, "1") == 0;
    }();
    return enabled;
}

constexpr size_t kJitPageSlots = 64;
std::array<std::atomic<uintptr_t>, kJitPageSlots> g_jit_pages{};
std::once_flag g_target_clear_cache_hook_once;

bool jit_trace_enabled() {
    static const bool enabled = [] {
        if (getenv("NESTSPACE_JIT_TRACE") != nullptr) return true;
        char value[PROP_VALUE_MAX] = {};
        __system_property_get("debug.nestspace.jit_trace", value);
        return strcmp(value, "1") == 0;
    }();
    return enabled;
}

bool property_trace_enabled() {
    static const bool enabled = [] {
        if (getenv("NESTSPACE_PROPERTY_TRACE") != nullptr) return true;
        char value[PROP_VALUE_MAX] = {};
        if (g_real.propertyGet != nullptr) {
            g_real.propertyGet(
                "debug.nestspace.property_trace",
                value
            );
        }
        return strcmp(value, "1") == 0;
    }();
    return enabled;
}

void install_target_clear_cache_hook(uintptr_t base) {
    if (base == 0 || !jit_trace_enabled()) return;
    std::call_once(g_target_clear_cache_hook_once, [base] {
        void* trampoline = nullptr;
        const uintptr_t target = base + 0x2763d8;
        if (install_inline_jump(
                reinterpret_cast<void*>(target),
                reinterpret_cast<void*>(hook_clear_cache),
                &trampoline
            )) {
            g_real.clearCacheCall =
                reinterpret_cast<ClearCacheFunction>(trampoline);
            __android_log_print(
                ANDROID_LOG_WARN,
                "NestSpaceNative",
                "target-clear-cache hooked target=%p trampoline=%p",
                reinterpret_cast<void*>(target),
                trampoline
            );
        }
    });
}

void track_jit_page(void* address) {
    if (address == nullptr || address == MAP_FAILED) return;
    const uintptr_t page = reinterpret_cast<uintptr_t>(address);
    for (auto& slot : g_jit_pages) {
        uintptr_t expected = 0;
        if (slot.compare_exchange_strong(
                expected,
                page,
                std::memory_order_acq_rel,
                std::memory_order_acquire
            ) ||
            expected == page) {
            return;
        }
    }
}

bool forget_jit_page(void* address) {
    if (address == nullptr) return false;
    const uintptr_t page = reinterpret_cast<uintptr_t>(address);
    for (auto& slot : g_jit_pages) {
        uintptr_t expected = page;
        if (slot.compare_exchange_strong(
                expected,
                0,
                std::memory_order_acq_rel,
                std::memory_order_acquire
            )) {
            return true;
        }
    }
    return false;
}

bool is_tracked_jit_page(void* address) {
    if (address == nullptr) return false;
    const uintptr_t page = reinterpret_cast<uintptr_t>(address);
    for (const auto& slot : g_jit_pages) {
        if (slot.load(std::memory_order_acquire) == page) return true;
    }
    return false;
}

void log_jit_page(const char* event, void* address, uintptr_t caller) {
    Dl_info info{};
    const bool resolved = g_real.dladdr != nullptr &&
        g_real.dladdr(reinterpret_cast<void*>(caller), &info) != 0;
    const uintptr_t base = resolved
        ? reinterpret_cast<uintptr_t>(info.dli_fbase)
        : 0;
    const auto* words = static_cast<const uint32_t*>(address);
    __android_log_print(
        ANDROID_LOG_WARN,
        "NestSpaceNative",
        "%s page=%p caller=%016" PRIxPTR " offset=%016" PRIxPTR
        " name=%s code=%08x,%08x,%08x,%08x,%08x,%08x,%08x,%08x",
        event,
        address,
        caller,
        base == 0 ? 0 : caller - base,
        resolved && info.dli_fname != nullptr ? info.dli_fname : "?",
        words[0],
        words[1],
        words[2],
        words[3],
        words[4],
        words[5],
        words[6],
        words[7]
    );
}

void log_tracked_jit_pages() {
    for (const auto& slot : g_jit_pages) {
        const uintptr_t page = slot.load(std::memory_order_acquire);
        if (page == 0) continue;
        uint32_t words[16] = {};
        struct iovec local {
            words,
            sizeof(words)
        };
        struct iovec remote {
            reinterpret_cast<void*>(page),
            sizeof(words)
        };
        const long copied = process_vm_readv(
            getpid(),
            &local,
            1,
            &remote,
            1,
            0
        );
        if (copied < 32) continue;
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "target-jit-code page=%p bytes=%ld"
            " code=%08x,%08x,%08x,%08x,%08x,%08x,%08x,%08x"
            ",%08x,%08x,%08x,%08x,%08x,%08x,%08x,%08x",
            reinterpret_cast<void*>(page),
            copied,
            words[0],
            words[1],
            words[2],
            words[3],
            words[4],
            words[5],
            words[6],
            words[7],
            words[8],
            words[9],
            words[10],
            words[11],
            words[12],
            words[13],
            words[14],
            words[15]
        );
    }
}

void dump_jit_page_now(
    const char* event,
    void* address,
    uintptr_t caller
) {
    if (address == nullptr || !jit_trace_enabled()) return;
    const Environment* environment = current_environment();
    if (environment == nullptr) return;
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return;
    std::vector<uint8_t> page(static_cast<size_t>(page_size));
    struct iovec local {
        page.data(),
        page.size()
    };
    struct iovec remote {
        address,
        page.size()
    };
    const long copied = process_vm_readv(
        getpid(),
        &local,
        1,
        &remote,
        1,
        0
    );
    if (copied < 32) return;
    const std::string directory =
        environment->data_root + "/.nestspace/jit-pages";
    if (g_real.mkdir != nullptr) {
        g_real.mkdir(directory.c_str(), 0700);
    }
    const std::string path = directory + "/" + event + "-" +
        std::to_string(reinterpret_cast<uintptr_t>(address)) +
        ".bin";
    const int fd = g_real.open
        ? g_real.open(
            path.c_str(),
            O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC,
            0600
        )
        : -1;
    if (fd >= 0) {
        write(fd, page.data(), static_cast<size_t>(copied));
        close(fd);
    }
    Dl_info info{};
    const bool resolved = g_real.dladdr != nullptr &&
        g_real.dladdr(reinterpret_cast<void*>(caller), &info) != 0;
    const uintptr_t base = resolved
        ? reinterpret_cast<uintptr_t>(info.dli_fbase)
        : 0;
    __android_log_print(
        ANDROID_LOG_WARN,
        "NestSpaceNative",
        "%s page=%p caller_offset=%016" PRIxPTR
        " bytes=%ld dump=%s",
        event,
        address,
        base == 0 ? 0 : caller - base,
        copied,
        fd >= 0 ? path.c_str() : "open-failed"
    );
}

void log_jit_page_later(void* address, uintptr_t caller) {
    const Environment* environment = current_environment();
    if (environment == nullptr) return;
    const std::string data_root = environment->data_root;
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return;
    try {
        std::thread([address, caller, data_root, page_size] {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            std::vector<uint8_t> page(static_cast<size_t>(page_size));
            struct iovec local {
                page.data(),
                page.size()
            };
            struct iovec remote {
                address,
                page.size()
            };
            const long copied = process_vm_readv(
                getpid(),
                &local,
                1,
                &remote,
                1,
                0
            );
            if (copied < 32) return;
            const std::string directory =
                data_root + "/.nestspace/jit-pages";
            if (g_real.mkdir != nullptr) {
                g_real.mkdir(directory.c_str(), 0700);
            }
            const std::string path = directory + "/" +
                std::to_string(reinterpret_cast<uintptr_t>(address)) +
                ".bin";
            const int fd = g_real.open
                ? g_real.open(
                    path.c_str(),
                    O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC,
                    0600
                )
                : -1;
            if (fd >= 0) {
                write(fd, page.data(), static_cast<size_t>(copied));
                close(fd);
            }
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
                "target-jit-later page=%p caller_offset=%016" PRIxPTR
                " bytes=%ld dump=%s",
                address,
                base == 0 ? 0 : caller - base,
                copied,
                fd >= 0 ? path.c_str() : "open-failed"
            );
        }).detach();
    } catch (...) {
    }
}

uintptr_t diagnostic_caller_offset(uintptr_t caller) {
    Dl_info info{};
    const bool resolved = g_real.dladdr != nullptr &&
        g_real.dladdr(reinterpret_cast<void*>(caller), &info) != 0;
    const uintptr_t base = resolved
        ? reinterpret_cast<uintptr_t>(info.dli_fbase)
        : 0;
    return base == 0 ? 0 : caller - base;
}

void log_diagnostic_caller(const char* event, uintptr_t address) {
    Dl_info info{};
    const bool resolved = g_real.dladdr != nullptr &&
        g_real.dladdr(reinterpret_cast<void*>(address), &info) != 0;
    const uintptr_t base = resolved
        ? reinterpret_cast<uintptr_t>(info.dli_fbase)
        : 0;
    __android_log_print(
        ANDROID_LOG_WARN,
        "NestSpaceNative",
        "%s caller=%016" PRIxPTR " base=%016" PRIxPTR
        " offset=%016" PRIxPTR " name=%s",
        event,
        address,
        base,
        base == 0 ? 0 : address - base,
        resolved && info.dli_fname != nullptr ? info.dli_fname : "?"
    );
}

void watch_diagnostic_object(void* pointer) {
    if (pointer == nullptr ||
        g_diagnostic_watch_started.exchange(true)) {
        return;
    }
    try {
        std::thread([pointer] {
            constexpr int kTimes[] = {
                0, 1, 5, 20, 50, 100, 200, 300, 320, 340, 360,
                380, 400, 420
            };
            int previous = 0;
            for (const int elapsed : kTimes) {
                if (elapsed > previous) {
                    std::this_thread::sleep_for(
                        std::chrono::milliseconds(elapsed - previous)
                    );
                    previous = elapsed;
                }
                uintptr_t words[4] = {};
                struct iovec local {
                    words,
                    sizeof(words)
                };
                struct iovec remote {
                    pointer,
                    sizeof(words)
                };
                const long copied = process_vm_readv(
                    getpid(),
                    &local,
                    1,
                    &remote,
                    1,
                    0
                );
                if (copied <= 0) {
                    __android_log_print(
                        ANDROID_LOG_WARN,
                        "NestSpaceNative",
                        "target-object-snapshot elapsed_ms=%d pointer=%p "
                        "read_error=%ld errno=%d",
                        elapsed,
                        pointer,
                        copied,
                        errno
                    );
                    continue;
                }
                __android_log_print(
                    ANDROID_LOG_WARN,
                    "NestSpaceNative",
                    "target-object-snapshot elapsed_ms=%d pointer=%p "
                    "w0=%016" PRIxPTR " w1=%016" PRIxPTR
                    " w2=%016" PRIxPTR " w3=%016" PRIxPTR,
                    elapsed,
                    pointer,
                    words[0],
                    words[1],
                    words[2],
                    words[3]
                );
            }
        }).detach();
    } catch (...) {
        g_diagnostic_watch_started.store(false);
    }
}

void track_diagnostic_object(
    void* pointer,
    const char* source,
    uintptr_t caller
) {
    if (pointer == nullptr || current_environment() == nullptr) return;
    const uintptr_t address =
        reinterpret_cast<uintptr_t>(pointer) & kDiagnosticPointerTagMask;
    bool tracked = false;
    for (auto& slot : g_diagnostic_objects) {
        uintptr_t expected = 0;
        if (slot.compare_exchange_strong(
                expected,
                address,
                std::memory_order_acq_rel,
                std::memory_order_acquire
            )) {
            tracked = true;
            break;
        }
        if (expected == address) {
            tracked = true;
            break;
        }
    }
    if (!tracked) return;
    if (diagnostic_caller_offset(caller) == 0x242fa8) {
        watch_diagnostic_object(pointer);
    }
    static std::atomic<int> logged{0};
    if (logged.fetch_add(1, std::memory_order_relaxed) < 48) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "target-alloc-0x98 source=%s pointer=%p",
            source,
            pointer
        );
        log_diagnostic_caller(
            "target-alloc-0x98",
            caller
        );
    }
}

bool is_tracked_diagnostic_range(const void* pointer, size_t size) {
    if (pointer == nullptr || size == 0) return false;
    const uintptr_t begin =
        reinterpret_cast<uintptr_t>(pointer) & kDiagnosticPointerTagMask;
    const uintptr_t end = begin + size;
    for (const auto& slot : g_diagnostic_objects) {
        const uintptr_t tracked = slot.load(std::memory_order_acquire);
        if (tracked != 0 &&
            begin < tracked + kDiagnosticObjectSize &&
            end > tracked) {
            return true;
        }
    }
    return false;
}

void forget_diagnostic_object(void* pointer) {
    if (pointer == nullptr) return;
    const uintptr_t address =
        reinterpret_cast<uintptr_t>(pointer) & kDiagnosticPointerTagMask;
    for (auto& slot : g_diagnostic_objects) {
        uintptr_t expected = address;
        slot.compare_exchange_strong(
            expected,
            0,
            std::memory_order_acq_rel,
            std::memory_order_acquire
        );
    }
}

void diagnose_object_write(
    const char* source,
    void* destination,
    size_t size,
    uintptr_t caller
) {
    if (!is_tracked_diagnostic_range(destination, size)) return;
    static std::atomic<int> logged{0};
    if (logged.fetch_add(1, std::memory_order_relaxed) >= 64) return;
    const auto* words = static_cast<const uintptr_t*>(destination);
    __android_log_print(
        ANDROID_LOG_WARN,
        "NestSpaceNative",
        "target-write-0x98 source=%s pointer=%p size=%zu first=%016" PRIxPTR,
        source,
        destination,
        size,
        words[0]
    );
    log_diagnostic_caller(
        "target-write-0x98",
        caller
    );
}

}  // namespace

void* hook_malloc(size_t size) {
    void* result = g_real.mallocCall
        ? g_real.mallocCall(size)
        : malloc(size);
    if (!object_diagnostics_enabled()) return result;
    if (size == kDiagnosticObjectSize) {
        track_diagnostic_object(
            result,
            "malloc",
            reinterpret_cast<uintptr_t>(__builtin_return_address(0))
        );
    }
    return result;
}

void* hook_calloc(size_t count, size_t size) {
    void* result = g_real.callocCall
        ? g_real.callocCall(count, size)
        : calloc(count, size);
    if (!object_diagnostics_enabled()) return result;
    if (count != 0 &&
        size != 0 &&
        count <= SIZE_MAX / size &&
        count * size == kDiagnosticObjectSize) {
        track_diagnostic_object(
            result,
            "calloc",
            reinterpret_cast<uintptr_t>(__builtin_return_address(0))
        );
    }
    return result;
}

void* hook_realloc(void* pointer, size_t size) {
    if (!object_diagnostics_enabled()) {
        return g_real.reallocCall
            ? g_real.reallocCall(pointer, size)
            : realloc(pointer, size);
    }
    const bool was_tracked =
        is_tracked_diagnostic_range(pointer, kDiagnosticObjectSize);
    if (was_tracked) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "target-realloc-0x98 old=%p new_size=%zu",
            pointer,
            size
        );
    }
    void* result = g_real.reallocCall
        ? g_real.reallocCall(pointer, size)
        : realloc(pointer, size);
    if (was_tracked) forget_diagnostic_object(pointer);
    if (size == kDiagnosticObjectSize) {
        track_diagnostic_object(
            result,
            "realloc",
            reinterpret_cast<uintptr_t>(__builtin_return_address(0))
        );
    }
    return result;
}

void hook_free(void* pointer) {
    if (!object_diagnostics_enabled()) {
        if (g_real.freeCall != nullptr) {
            g_real.freeCall(pointer);
        } else {
            free(pointer);
        }
        return;
    }
    if (is_tracked_diagnostic_range(pointer, kDiagnosticObjectSize)) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "target-free-0x98 pointer=%p",
            pointer
        );
        log_diagnostic_caller(
            "target-free-0x98",
            reinterpret_cast<uintptr_t>(__builtin_return_address(0))
        );
        forget_diagnostic_object(pointer);
    }
    if (g_real.freeCall != nullptr) {
        g_real.freeCall(pointer);
    } else {
        free(pointer);
    }
}

void* hook_memcpy(void* destination, const void* source, size_t size) {
    void* result = g_real.memcpyCall
        ? g_real.memcpyCall(destination, source, size)
        : memcpy(destination, source, size);
    if (!object_diagnostics_enabled()) return result;
    diagnose_object_write(
        "memcpy",
        destination,
        size,
        reinterpret_cast<uintptr_t>(__builtin_return_address(0))
    );
    return result;
}

void* hook_memmove(void* destination, const void* source, size_t size) {
    void* result = g_real.memmoveCall
        ? g_real.memmoveCall(destination, source, size)
        : memmove(destination, source, size);
    if (!object_diagnostics_enabled()) return result;
    diagnose_object_write(
        "memmove",
        destination,
        size,
        reinterpret_cast<uintptr_t>(__builtin_return_address(0))
    );
    return result;
}

uid_t hook_getuid() {
    record_hook_event(kHookEventUid);
    const Environment* environment = current_environment();
    if (environment != nullptr) {
        static std::atomic<int> logged{0};
        if (logged.fetch_add(1, std::memory_order_relaxed) < 4) {
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
                "target-getuid observed=%d real=%d caller=%016" PRIxPTR
                " module=%016" PRIxPTR " offset=%016" PRIxPTR " name=%s",
                environment->observed_uid,
                g_real.getuid ? static_cast<int>(g_real.getuid())
                               : static_cast<int>(getuid()),
                caller,
                base,
                base == 0 ? 0 : caller - base,
                resolved && info.dli_fname != nullptr
                    ? info.dli_fname
                    : "?"
            );
        }
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
    std::vector<gid_t> virtual_groups;
    virtual_groups.reserve(environment->supplementary_groups.size());
    for (const int group : environment->supplementary_groups) {
        virtual_groups.push_back(static_cast<gid_t>(group));
    }
    if (virtual_groups.empty()) {
        virtual_groups = {
            9997,
            static_cast<gid_t>(environment->observed_uid + 10000),
            static_cast<gid_t>(environment->observed_uid + 40000),
        };
    }
    if (size == 0) {
        return static_cast<int>(virtual_groups.size());
    }
    if (size < static_cast<int>(virtual_groups.size())) {
        errno = EINVAL;
        return -1;
    }
    if (groups == nullptr) {
        errno = EFAULT;
        return -1;
    }
    std::copy(virtual_groups.begin(), virtual_groups.end(), groups);
    return static_cast<int>(virtual_groups.size());
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
        if (signal == SIGKILL) return 0;
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
    if (signal == SIGKILL) {
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
            return 0;
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
    if (current_environment() != nullptr &&
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
    if (mapped && (flags & O_CREAT) != 0) {
        ensure_parent_directory(actual);
    }
    const int result = g_real.open
        ? g_real.open(actual, flags, mode)
        : open(actual, flags, mode);
    log_runtime_library_open("open", path, actual, result);
    log_database_path("open", path, actual, result);
    log_database_fd_identity("open", path, actual, result);
    return result;
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
    if (mapped && (flags & O_CREAT) != 0) {
        ensure_parent_directory(actual);
    }
    const int result = g_real.open64
        ? g_real.open64(actual, flags, mode)
        : open64(actual, flags, mode);
    log_runtime_library_open("open64", path, actual, result);
    log_database_path("open64", path, actual, result);
    log_database_fd_identity("open64", path, actual, result);
    return result;
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
    if (mapped && (flags & O_CREAT) != 0) {
        ensure_parent_directory(actual);
    }
    const int result = g_real.openat
        ? g_real.openat(dirfd, actual, flags, mode)
        : openat(dirfd, actual, flags, mode);
    log_runtime_library_open("openat", path, actual, result);
    log_database_path("openat", path, actual, result);
    log_database_fd_identity("openat", path, actual, result);
    return result;
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
    if (mapped && (flags & O_CREAT) != 0) {
        ensure_parent_directory(actual);
    }
    const int result = g_real.openat64
        ? g_real.openat64(dirfd, actual, flags, mode)
        : openat64(dirfd, actual, flags, mode);
    log_runtime_library_open("openat64", path, actual, result);
    log_database_path("openat64", path, actual, result);
    log_database_fd_identity("openat64", path, actual, result);
    return result;
}

int hook_mkdir(const char* path, mode_t mode) {
    record_hook_event(kHookEventFile);
    log_sensitive_path("mkdir", path);
    std::string rewritten;
    const bool mapped = rewrite_target_path(path, &rewritten);
    const char* actual = mapped
        ? rewritten.c_str()
        : path;
    const int result = g_real.mkdir
        ? g_real.mkdir(actual, mode)
        : mkdir(actual, mode);
    const Environment* environment = current_environment();
    const bool in_target_data = environment != nullptr && path != nullptr &&
        !environment->target_data_root.empty() &&
        strncmp(
            path,
            environment->target_data_root.c_str(),
            environment->target_data_root.size()
        ) == 0;
    const bool in_target_external = environment != nullptr && path != nullptr &&
        !environment->target_external_root.empty() &&
        strncmp(
            path,
            environment->target_external_root.c_str(),
            environment->target_external_root.size()
        ) == 0;
    if (in_target_data || in_target_external) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "target-mkdir requested=%s actual=%s mapped=%d result=%d errno=%d",
            path,
            actual,
            mapped ? 1 : 0,
            result,
            errno
        );
    }
    return result;
}

int hook_mkdirat(int dirfd, const char* path, mode_t mode) {
    record_hook_event(kHookEventFile);
    log_sensitive_path("mkdirat", path);
    std::string rewritten;
    const bool mapped = rewrite_target_path(path, &rewritten);
    const char* actual = mapped
        ? rewritten.c_str()
        : path;
    const int result = g_real.mkdirat
        ? g_real.mkdirat(dirfd, actual, mode)
        : mkdirat(dirfd, actual, mode);
    const Environment* environment = current_environment();
    const bool in_target_data = environment != nullptr && path != nullptr &&
        !environment->target_data_root.empty() &&
        strncmp(
            path,
            environment->target_data_root.c_str(),
            environment->target_data_root.size()
        ) == 0;
    const bool in_target_external = environment != nullptr && path != nullptr &&
        !environment->target_external_root.empty() &&
        strncmp(
            path,
            environment->target_external_root.c_str(),
            environment->target_external_root.size()
        ) == 0;
    if (in_target_data || in_target_external) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "target-mkdirat requested=%s actual=%s mapped=%d result=%d errno=%d",
            path,
            actual,
            mapped ? 1 : 0,
            result,
            errno
        );
    }
    return result;
}

int hook_rmdir(const char* path) {
    record_hook_event(kHookEventFile);
    log_sensitive_path("rmdir", path);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    return g_real.rmdir ? g_real.rmdir(actual) : rmdir(actual);
}

int hook_chmod(const char* path, mode_t mode) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const bool mapped = rewrite_target_path(path, &rewritten);
    const char* actual = mapped ? rewritten.c_str() : path;
    const int result = g_real.chmod
        ? g_real.chmod(actual, mode)
        : chmod(actual, mode);
    if (mapped) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "target-chmod requested=%s actual=%s result=%d errno=%d",
            path == nullptr ? "?" : path,
            actual == nullptr ? "?" : actual,
            result,
            result < 0 ? errno : 0
        );
    }
    return result;
}

int hook_fchmodat(int dirfd, const char* path, mode_t mode, int flags) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const bool mapped = path != nullptr &&
        rewrite_target_path(path, &rewritten);
    const char* actual = mapped ? rewritten.c_str() : path;
    const int result = g_real.fchmodat
        ? g_real.fchmodat(dirfd, actual, mode, flags)
        : fchmodat(dirfd, actual, mode, flags);
    if (mapped) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "target-fchmodat requested=%s actual=%s result=%d errno=%d",
            path,
            actual,
            result,
            result < 0 ? errno : 0
        );
    }
    return result;
}

int hook_fchmod(int fd, mode_t mode) {
    record_hook_event(kHookEventFile);
    return g_real.fchmod
        ? g_real.fchmod(fd, mode)
        : fchmod(fd, mode);
}

void* hook_memset(void* destination, int value, size_t size) {
    void* result = g_real.memsetCall
        ? g_real.memsetCall(destination, value, size)
        : memset(destination, value, size);
    if (!object_diagnostics_enabled()) return result;
    diagnose_object_write(
        "memset",
        destination,
        size,
        reinterpret_cast<uintptr_t>(__builtin_return_address(0))
    );
    return result;
}

void* hook_mmap(
    void* address,
    size_t length,
    int protection,
    int flags,
    int fd,
    off_t offset
) {
    const uintptr_t caller =
        reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    void* result = g_real.mmapCall
        ? g_real.mmapCall(address, length, protection, flags, fd, offset)
        : MAP_FAILED;
    if (result == MAP_FAILED ||
        !jit_trace_enabled() ||
        (protection & PROT_EXEC) == 0) {
        return result;
    }
    Dl_info info{};
    const bool target = g_real.dladdr != nullptr &&
        g_real.dladdr(reinterpret_cast<void*>(caller), &info) != 0 &&
        info.dli_fname != nullptr &&
        is_runtime_module_path(info.dli_fname);
    if (!target) return result;
    install_target_clear_cache_hook(
        reinterpret_cast<uintptr_t>(info.dli_fbase)
    );
    track_jit_page(result);
    log_jit_page("target-jit-mmap", result, caller);
    log_jit_page_later(result, caller);
    return result;
}

int hook_munmap(void* address, size_t length) {
    const uintptr_t caller =
        reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    const bool tracked =
        jit_trace_enabled() && forget_jit_page(address);
    if (tracked) log_jit_page("target-jit-munmap", address, caller);
    return g_real.munmapCall
        ? g_real.munmapCall(address, length)
        : munmap(address, length);
}

int hook_mprotect(void* address, size_t length, int protection) {
    const uintptr_t caller =
        reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    if (is_tracked_jit_page(address)) {
        dump_jit_page_now(
            "target-jit-mprotect",
            address,
            caller
        );
    }
    return g_real.mprotectCall
        ? g_real.mprotectCall(address, length, protection)
        : mprotect(address, length, protection);
}

void hook_clear_cache(void* begin, void* end) {
    const uintptr_t caller =
        reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    if (begin != nullptr &&
        end != nullptr &&
        end > begin &&
        jit_trace_enabled()) {
        dump_jit_page_now(
            "target-jit-clear-cache",
            begin,
            caller
        );
    }
    if (g_real.clearCacheCall != nullptr) {
        g_real.clearCacheCall(begin, end);
        return;
    }
    __builtin___clear_cache(
        static_cast<char*>(begin),
        static_cast<char*>(end)
    );
}

int hook_unlink(const char* path) {
    record_hook_event(kHookEventFile);
    log_sensitive_path("unlink", path);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    return g_real.unlink ? g_real.unlink(actual) : unlink(actual);
}

int hook_unlinkat(int dirfd, const char* path, int flags) {
    record_hook_event(kHookEventFile);
    log_sensitive_path("unlinkat", path);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    return g_real.unlinkat
        ? g_real.unlinkat(dirfd, actual, flags)
        : unlinkat(dirfd, actual, flags);
}

int hook_rename(const char* old_path, const char* new_path) {
    record_hook_event(kHookEventFile);
    std::string rewritten_old;
    std::string rewritten_new;
    const char* actual_old = rewrite_target_path(old_path, &rewritten_old)
        ? rewritten_old.c_str()
        : old_path;
    const char* actual_new = rewrite_target_path(new_path, &rewritten_new)
        ? rewritten_new.c_str()
        : new_path;
    return g_real.rename
        ? g_real.rename(actual_old, actual_new)
        : rename(actual_old, actual_new);
}

int hook_renameat(
    int old_dirfd,
    const char* old_path,
    int new_dirfd,
    const char* new_path
) {
    record_hook_event(kHookEventFile);
    std::string rewritten_old;
    std::string rewritten_new;
    const char* actual_old = rewrite_target_path(old_path, &rewritten_old)
        ? rewritten_old.c_str()
        : old_path;
    const char* actual_new = rewrite_target_path(new_path, &rewritten_new)
        ? rewritten_new.c_str()
        : new_path;
    return g_real.renameat
        ? g_real.renameat(
            old_dirfd,
            actual_old,
            new_dirfd,
            actual_new
        )
        : renameat(old_dirfd, actual_old, new_dirfd, actual_new);
}

int hook_access(const char* path, int mode) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    const int result = g_real.access
        ? g_real.access(actual, mode)
        : access(actual, mode);
    log_database_path("access", path, actual, result);
    return result;
}

int hook_faccessat(int dirfd, const char* path, int mode, int flags) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    const int result = g_real.faccessat
        ? g_real.faccessat(dirfd, actual, mode, flags)
        : faccessat(dirfd, actual, mode, flags);
    log_database_path("faccessat", path, actual, result);
    return result;
}

int hook_faccessat2(int dirfd, const char* path, int mode, int flags) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    if (g_real.faccessat2 != nullptr) {
        return g_real.faccessat2(dirfd, actual, mode, flags);
    }
#ifdef SYS_faccessat2
    return g_real.syscall
        ? static_cast<int>(g_real.syscall(
            SYS_faccessat2,
            dirfd,
            actual,
            mode,
            flags
        ))
        : static_cast<int>(syscall(
            SYS_faccessat2,
            dirfd,
            actual,
            mode,
            flags
        ));
#else
    errno = ENOSYS;
    return -1;
#endif
}

char* hook_getcwd(char* buffer, size_t size) {
    char* actual = g_real.getcwd
        ? g_real.getcwd(buffer, size)
        : getcwd(buffer, size);
    if (actual == nullptr || current_environment() == nullptr) return actual;
    if (buffer != nullptr) {
        ssize_t mapped = -1;
        if (virtual_path_from_actual(actual, buffer, size, &mapped)) {
            return buffer;
        }
        return actual;
    }
    ssize_t mapped = -1;
    auto* allocated = static_cast<char*>(malloc(PATH_MAX + 1));
    if (allocated == nullptr) {
        free(actual);
        errno = ENOMEM;
        return nullptr;
    }
    if (virtual_path_from_actual(actual, allocated, PATH_MAX + 1, &mapped)) {
        free(actual);
        return allocated;
    }
    free(allocated);
    return actual;
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
    if (result == 0) {
        virtualize_target_file_stat(actual, value);
        log_database_stat("stat", actual, result, value);
    }
    return result;
}

int hook_stat64(const char* path, struct stat* value) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    const int result = g_real.stat64
        ? g_real.stat64(actual, value)
        : stat(actual, value);
    if (result == 0) {
        virtualize_target_file_stat(actual, value);
        log_database_stat("stat64", actual, result, value);
    }
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
    if (result == 0) {
        virtualize_target_file_stat(actual, value);
        log_database_stat("lstat", actual, result, value);
    }
    return result;
}

int hook_lstat64(const char* path, struct stat* value) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    const int result = g_real.lstat64
        ? g_real.lstat64(actual, value)
        : lstat(actual, value);
    if (result == 0) {
        virtualize_target_file_stat(actual, value);
        log_database_stat("lstat64", actual, result, value);
    }
    return result;
}

int hook_statfs(const char* path, struct statfs* value) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    return g_real.statfs
        ? g_real.statfs(actual, value)
        : statfs(actual, value);
}

int hook_statvfs(const char* path, struct statvfs* value) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    return g_real.statvfs
        ? g_real.statvfs(actual, value)
        : statvfs(actual, value);
}

ssize_t hook_getxattr(
    const char* path,
    const char* name,
    void* value,
    size_t size
) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    ssize_t virtual_result = -1;
    if (virtualize_selinux_xattr(
            actual,
            name,
            value,
            size,
            &virtual_result
        )) {
        const ssize_t exists = g_real.getxattr
            ? g_real.getxattr(actual, name, nullptr, 0)
            : getxattr(actual, name, nullptr, 0);
        return exists < 0 ? exists : virtual_result;
    }
    const ssize_t result = g_real.getxattr
        ? g_real.getxattr(actual, name, value, size)
        : getxattr(actual, name, value, size);
    return result;
}

ssize_t hook_lgetxattr(
    const char* path,
    const char* name,
    void* value,
    size_t size
) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    ssize_t virtual_result = -1;
    if (virtualize_selinux_xattr(
            actual,
            name,
            value,
            size,
            &virtual_result
        )) {
        const ssize_t exists = g_real.lgetxattr
            ? g_real.lgetxattr(actual, name, nullptr, 0)
            : lgetxattr(actual, name, nullptr, 0);
        return exists < 0 ? exists : virtual_result;
    }
    const ssize_t result = g_real.lgetxattr
        ? g_real.lgetxattr(actual, name, value, size)
        : lgetxattr(actual, name, value, size);
    return result;
}

ssize_t hook_fgetxattr(int fd, const char* name, void* value, size_t size) {
    record_hook_event(kHookEventFile);
    const std::string actual = actual_path_for_fd(fd);
    ssize_t virtual_result = -1;
    if (!actual.empty() &&
        virtualize_selinux_xattr(
            actual.c_str(),
            name,
            value,
            size,
            &virtual_result
        )) {
        const ssize_t exists = g_real.fgetxattr
            ? g_real.fgetxattr(fd, name, nullptr, 0)
            : fgetxattr(fd, name, nullptr, 0);
        return exists < 0 ? exists : virtual_result;
    }
    const ssize_t result = g_real.fgetxattr
        ? g_real.fgetxattr(fd, name, value, size)
        : fgetxattr(fd, name, value, size);
    return result;
}

ssize_t hook_listxattr(const char* path, char* list, size_t size) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    return g_real.listxattr
        ? g_real.listxattr(actual, list, size)
        : listxattr(actual, list, size);
}

ssize_t hook_llistxattr(const char* path, char* list, size_t size) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    return g_real.llistxattr
        ? g_real.llistxattr(actual, list, size)
        : llistxattr(actual, list, size);
}

ssize_t hook_flistxattr(int fd, char* list, size_t size) {
    record_hook_event(kHookEventFile);
    return g_real.flistxattr
        ? g_real.flistxattr(fd, list, size)
        : flistxattr(fd, list, size);
}

int hook_fstat(int fd, struct stat* value) {
    record_hook_event(kHookEventFile);
    const int result = g_real.fstat
        ? g_real.fstat(fd, value)
        : fstat(fd, value);
    if (result == 0) {
        char proc_path[64];
        snprintf(proc_path, sizeof(proc_path), "/proc/self/fd/%d", fd);
        char actual[PATH_MAX + 1];
        const ssize_t length = g_real.readlink
            ? g_real.readlink(proc_path, actual, PATH_MAX)
            : readlink(proc_path, actual, PATH_MAX);
        if (length > 0) {
            actual[length] = '\0';
            virtualize_target_file_stat(actual, value);
            log_database_stat("fstat", actual, result, value);
        }
    }
    return result;
}

int hook_fstat64(int fd, struct stat* value) {
    record_hook_event(kHookEventFile);
    const int result = g_real.fstat64
        ? g_real.fstat64(fd, value)
        : fstat(fd, value);
    if (result == 0) {
        char proc_path[64];
        snprintf(proc_path, sizeof(proc_path), "/proc/self/fd/%d", fd);
        char actual[PATH_MAX + 1];
        const ssize_t length = g_real.readlink
            ? g_real.readlink(proc_path, actual, PATH_MAX)
            : readlink(proc_path, actual, PATH_MAX);
        if (length > 0) {
            actual[length] = '\0';
            virtualize_target_file_stat(actual, value);
            log_database_stat("fstat64", actual, result, value);
        }
    }
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
    if (result == 0) {
        virtualize_target_file_stat(actual, value);
        log_database_stat("fstatat", actual, result, value);
    }
    return result;
}

int hook_fstatat64(
    int dirfd,
    const char* path,
    struct stat* value,
    int flags
) {
    record_hook_event(kHookEventFile);
    std::string rewritten;
    const char* actual = rewrite_target_path(path, &rewritten)
        ? rewritten.c_str()
        : path;
    const int result = g_real.fstatat64
        ? g_real.fstatat64(dirfd, actual, value, flags)
        : fstatat(dirfd, actual, value, flags);
    if (result == 0) {
        virtualize_target_file_stat(actual, value);
        log_database_stat("fstatat64", actual, result, value);
    }
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
    log_database_path(
        "realpath",
        path,
        actual == nullptr ? rewritten.c_str() : actual,
        actual == nullptr ? -1 : 0
    );
    if (actual == nullptr) return nullptr;
    free(actual);
    std::string virtual_path(path);
    const Environment* environment = current_environment();
    if (environment != nullptr &&
        has_path_prefix(path, environment->target_data_root)) {
        virtual_path = environment->target_data_alias +
            (path + environment->target_data_root.size());
    } else if (
        environment != nullptr &&
        !environment->target_external_alias.empty() &&
        has_path_prefix(path, environment->target_external_alias)
    ) {
        virtual_path = environment->target_external_root +
            (path + environment->target_external_alias.size());
    }
    const size_t length = virtual_path.size();
    char* output = resolved != nullptr
        ? resolved
        : static_cast<char*>(malloc(length + 1));
    if (output == nullptr) return nullptr;
    memcpy(output, virtual_path.c_str(), length + 1);
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
    if (name != nullptr && property_trace_enabled()) {
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
            "target-property name=%s caller_offset=%016" PRIxPTR
            " module=%s",
            name,
            base == 0 ? 0 : caller - base,
            resolved && info.dli_fname != nullptr
                ? info.dli_fname
                : "?"
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
    if (actual != nullptr && strstr(actual, "libart.so") != nullptr) {
        actual = "libart.so";
    }
    void* handle = g_real.dlopen
        ? g_real.dlopen(actual, flags)
        : dlopen(actual, flags);
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
    if (actual != nullptr && strstr(actual, "libart.so") != nullptr) {
        actual = "libart.so";
    }
    void* handle = g_real.androidDlopenExt
        ? g_real.androidDlopenExt(actual, flags, extinfo)
        : android_dlopen_ext(actual, flags, extinfo);
    if (handle != nullptr && current_environment() != nullptr) {
        refresh_loaded_modules();
    }
    return handle;
}

long hook_syscall(long number, ...) {
    record_hook_event(kHookEventDirectSyscall);
    record_syscall_event(number);
    if (current_environment() != nullptr && number >= 0 && number < 512) {
        static std::array<std::atomic<bool>, 512> logged{};
        auto& seen = logged[static_cast<size_t>(number)];
        if (!seen.exchange(true, std::memory_order_relaxed)) {
            __android_log_print(
                ANDROID_LOG_DEBUG,
                "NestSpaceNative",
                "target-syscall-first number=%ld",
                number
            );
        }
    }
    va_list args;
    va_start(args, number);
    if (number == SYS_getuid ||
        number == SYS_geteuid ||
        number == SYS_getgid ||
        number == SYS_getegid) {
        va_end(args);
        const Environment* environment = current_environment();
        return environment ? environment->observed_uid
                           : (g_real.syscall ? g_real.syscall(number)
                                             : syscall(number));
    }

    const auto next_arg = [&args]() -> uintptr_t {
        return reinterpret_cast<uintptr_t>(va_arg(args, void*));
    };

#ifdef SYS_getresuid
    if (number == SYS_getresuid) {
        auto* real = reinterpret_cast<uid_t*>(next_arg());
        auto* effective = reinterpret_cast<uid_t*>(next_arg());
        auto* saved = reinterpret_cast<uid_t*>(next_arg());
        va_end(args);
        return hook_getresuid(real, effective, saved);
    }
#endif
#ifdef SYS_getresgid
    if (number == SYS_getresgid) {
        auto* real = reinterpret_cast<gid_t*>(next_arg());
        auto* effective = reinterpret_cast<gid_t*>(next_arg());
        auto* saved = reinterpret_cast<gid_t*>(next_arg());
        va_end(args);
        return hook_getresgid(real, effective, saved);
    }
#endif
    if (number == SYS_getgroups) {
        const int size = static_cast<int>(next_arg());
        auto* groups = reinterpret_cast<gid_t*>(next_arg());
        va_end(args);
        return hook_getgroups(size, groups);
    }
#ifdef SYS_capget
    if (number == SYS_capget) {
        auto* header = reinterpret_cast<struct __user_cap_header_struct*>(
            next_arg()
        );
        auto* data = reinterpret_cast<struct __user_cap_data_struct*>(
            next_arg()
        );
        va_end(args);
        return hook_capget(header, data);
    }
#endif
    if (number == SYS_getcwd) {
        auto* buffer = reinterpret_cast<char*>(next_arg());
        const size_t size = static_cast<size_t>(next_arg());
        va_end(args);
        char* result = hook_getcwd(buffer, size);
        return result == nullptr ? -1 : static_cast<long>(strlen(result) + 1);
    }
#ifdef SYS_getxattr
    if (number == SYS_getxattr) {
        auto* path = reinterpret_cast<const char*>(next_arg());
        auto* name = reinterpret_cast<const char*>(next_arg());
        auto* value = reinterpret_cast<void*>(next_arg());
        const size_t size = static_cast<size_t>(next_arg());
        va_end(args);
        return hook_getxattr(path, name, value, size);
    }
#endif
#ifdef SYS_lgetxattr
    if (number == SYS_lgetxattr) {
        auto* path = reinterpret_cast<const char*>(next_arg());
        auto* name = reinterpret_cast<const char*>(next_arg());
        auto* value = reinterpret_cast<void*>(next_arg());
        const size_t size = static_cast<size_t>(next_arg());
        va_end(args);
        return hook_lgetxattr(path, name, value, size);
    }
#endif
#ifdef SYS_fgetxattr
    if (number == SYS_fgetxattr) {
        const int fd = static_cast<int>(next_arg());
        auto* name = reinterpret_cast<const char*>(next_arg());
        auto* value = reinterpret_cast<void*>(next_arg());
        const size_t size = static_cast<size_t>(next_arg());
        va_end(args);
        return hook_fgetxattr(fd, name, value, size);
    }
#endif
#ifdef SYS_listxattr
    if (number == SYS_listxattr) {
        auto* path = reinterpret_cast<const char*>(next_arg());
        auto* list = reinterpret_cast<char*>(next_arg());
        const size_t size = static_cast<size_t>(next_arg());
        va_end(args);
        return hook_listxattr(path, list, size);
    }
#endif
#ifdef SYS_llistxattr
    if (number == SYS_llistxattr) {
        auto* path = reinterpret_cast<const char*>(next_arg());
        auto* list = reinterpret_cast<char*>(next_arg());
        const size_t size = static_cast<size_t>(next_arg());
        va_end(args);
        return hook_llistxattr(path, list, size);
    }
#endif
#ifdef SYS_flistxattr
    if (number == SYS_flistxattr) {
        const int fd = static_cast<int>(next_arg());
        auto* list = reinterpret_cast<char*>(next_arg());
        const size_t size = static_cast<size_t>(next_arg());
        va_end(args);
        return hook_flistxattr(fd, list, size);
    }
#endif
#ifdef SYS_faccessat2
    if (number == SYS_faccessat2) {
        const int dirfd = static_cast<int>(next_arg());
        auto* path = reinterpret_cast<const char*>(next_arg());
        const int mode = static_cast<int>(next_arg());
        const int flags = static_cast<int>(next_arg());
        va_end(args);
        return hook_faccessat2(dirfd, path, mode, flags);
    }
#endif
    if (number == SYS_fstat) {
        const int fd = static_cast<int>(next_arg());
        auto* value = reinterpret_cast<struct stat*>(next_arg());
        va_end(args);
        return hook_fstat(fd, value);
    }
#ifdef SYS_fchmodat
    if (number == SYS_fchmodat) {
        const int dirfd = static_cast<int>(next_arg());
        auto* path = reinterpret_cast<const char*>(next_arg());
        const mode_t mode = static_cast<mode_t>(next_arg());
        const int flags = static_cast<int>(next_arg());
        va_end(args);
        return hook_fchmodat(dirfd, path, mode, flags);
    }
#endif
#ifdef SYS_chmod
    if (number == SYS_chmod) {
        auto* path = reinterpret_cast<const char*>(next_arg());
        const mode_t mode = static_cast<mode_t>(next_arg());
        va_end(args);
        return hook_chmod(path, mode);
    }
#endif
#ifdef SYS_readlink
    if (number == SYS_readlink) {
        auto* path = reinterpret_cast<const char*>(next_arg());
        auto* buffer = reinterpret_cast<char*>(next_arg());
        const size_t size = static_cast<size_t>(next_arg());
        va_end(args);
        return hook_readlink(path, buffer, size);
    }
#endif
#ifdef SYS_prctl
    if (number == SYS_prctl) {
        const int option = static_cast<int>(next_arg());
        const unsigned long argument2 =
            static_cast<unsigned long>(next_arg());
        const unsigned long argument3 =
            static_cast<unsigned long>(next_arg());
        const unsigned long argument4 =
            static_cast<unsigned long>(next_arg());
        const unsigned long argument5 =
            static_cast<unsigned long>(next_arg());
        va_end(args);
        return hook_prctl(
            option,
            argument2,
            argument3,
            argument4,
            argument5
        );
    }
#endif

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
#ifdef SYS_mkdirat
    if (number == SYS_mkdirat) {
        const int dirfd = static_cast<int>(next_arg());
        auto* path = reinterpret_cast<const char*>(next_arg());
        const mode_t mode = static_cast<mode_t>(next_arg());
        va_end(args);
        std::string rewritten;
        const bool mapped = rewrite_target_path(path, &rewritten);
        return g_real.syscall
            ? g_real.syscall(
                number,
                dirfd,
                mapped ? rewritten.c_str() : path,
                mode
            )
            : syscall(
                number,
                dirfd,
                mapped ? rewritten.c_str() : path,
                mode
            );
    }
#endif
#ifdef SYS_statfs
    if (number == SYS_statfs) {
        auto* path = reinterpret_cast<const char*>(next_arg());
        auto* value = reinterpret_cast<struct statfs*>(next_arg());
        va_end(args);
        std::string rewritten;
        const bool mapped = rewrite_target_path(path, &rewritten);
        return g_real.syscall
            ? g_real.syscall(
                number,
                mapped ? rewritten.c_str() : path,
                value
            )
            : syscall(
                number,
                mapped ? rewritten.c_str() : path,
                value
            );
    }
#endif
    if (number == SYS_unlinkat) {
        const int dirfd = static_cast<int>(next_arg());
        auto* path = reinterpret_cast<const char*>(next_arg());
        const int flags = static_cast<int>(next_arg());
        va_end(args);
        std::string rewritten;
        const bool mapped = rewrite_target_path(path, &rewritten);
        return g_real.syscall
            ? g_real.syscall(
                number,
                dirfd,
                mapped ? rewritten.c_str() : path,
                flags
            )
            : syscall(
                number,
                dirfd,
                mapped ? rewritten.c_str() : path,
                flags
            );
    }
    if (number == SYS_renameat) {
        const int old_dirfd = static_cast<int>(next_arg());
        auto* old_path = reinterpret_cast<const char*>(next_arg());
        const int new_dirfd = static_cast<int>(next_arg());
        auto* new_path = reinterpret_cast<const char*>(next_arg());
        va_end(args);
        std::string rewritten_old;
        std::string rewritten_new;
        const bool old_mapped = rewrite_target_path(old_path, &rewritten_old);
        const bool new_mapped = rewrite_target_path(new_path, &rewritten_new);
        return g_real.syscall
            ? g_real.syscall(
                number,
                old_dirfd,
                old_mapped ? rewritten_old.c_str() : old_path,
                new_dirfd,
                new_mapped ? rewritten_new.c_str() : new_path
            )
            : syscall(
                number,
                old_dirfd,
                old_mapped ? rewritten_old.c_str() : old_path,
                new_dirfd,
                new_mapped ? rewritten_new.c_str() : new_path
            );
    }
#ifdef SYS_renameat2
    if (number == SYS_renameat2) {
        const int old_dirfd = static_cast<int>(next_arg());
        auto* old_path = reinterpret_cast<const char*>(next_arg());
        const int new_dirfd = static_cast<int>(next_arg());
        auto* new_path = reinterpret_cast<const char*>(next_arg());
        const unsigned int flags = static_cast<unsigned int>(next_arg());
        va_end(args);
        std::string rewritten_old;
        std::string rewritten_new;
        const bool old_mapped = rewrite_target_path(old_path, &rewritten_old);
        const bool new_mapped = rewrite_target_path(new_path, &rewritten_new);
        return g_real.syscall
            ? g_real.syscall(
                number,
                old_dirfd,
                old_mapped ? rewritten_old.c_str() : old_path,
                new_dirfd,
                new_mapped ? rewritten_new.c_str() : new_path,
                flags
            )
            : syscall(
                number,
                old_dirfd,
                old_mapped ? rewritten_old.c_str() : old_path,
                new_dirfd,
                new_mapped ? rewritten_new.c_str() : new_path,
                flags
            );
    }
#endif
    if (number == kSysFstatat) {
        const int dirfd = static_cast<int>(next_arg());
        auto* path = reinterpret_cast<const char*>(next_arg());
        auto* value = reinterpret_cast<struct stat*>(next_arg());
        const int flags = static_cast<int>(next_arg());
        va_end(args);
        std::string rewritten;
        const bool mapped = rewrite_target_path(path, &rewritten);
        const long result = g_real.syscall
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
        if (result == 0) {
            virtualize_target_file_stat(
                mapped ? rewritten.c_str() : path,
                value
            );
        }
        return result;
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
        const long result = g_real.syscall
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
        if (result == 0) {
            virtualize_target_statx(
                mapped ? rewritten.c_str() : path,
                value
            );
        }
        return result;
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
    if (strcmp(name, "getgid") == 0) return reinterpret_cast<void*>(hook_getgid);
    if (strcmp(name, "getegid") == 0) return reinterpret_cast<void*>(hook_getegid);
    if (strcmp(name, "getresuid") == 0) {
        return reinterpret_cast<void*>(hook_getresuid);
    }
    if (strcmp(name, "getresgid") == 0) {
        return reinterpret_cast<void*>(hook_getresgid);
    }
    if (strcmp(name, "getgroups") == 0) {
        return reinterpret_cast<void*>(hook_getgroups);
    }
    if (strcmp(name, "open") == 0) return reinterpret_cast<void*>(hook_open);
    if (strcmp(name, "open64") == 0) return reinterpret_cast<void*>(hook_open64);
    if (strcmp(name, "__open_2") == 0) return reinterpret_cast<void*>(hook_open_2);
    if (strcmp(name, "openat") == 0) return reinterpret_cast<void*>(hook_openat);
    if (strcmp(name, "openat64") == 0) return reinterpret_cast<void*>(hook_openat64);
    if (strcmp(name, "__openat_2") == 0) return reinterpret_cast<void*>(hook_openat_2);
    if (strcmp(name, "access") == 0) return reinterpret_cast<void*>(hook_access);
    if (strcmp(name, "faccessat") == 0) return reinterpret_cast<void*>(hook_faccessat);
    if (strcmp(name, "faccessat2") == 0) {
        return reinterpret_cast<void*>(hook_faccessat2);
    }
    if (strcmp(name, "getcwd") == 0) {
        return reinterpret_cast<void*>(hook_getcwd);
    }
    if (strcmp(name, "getxattr") == 0) {
        return reinterpret_cast<void*>(hook_getxattr);
    }
    if (strcmp(name, "lgetxattr") == 0) {
        return reinterpret_cast<void*>(hook_lgetxattr);
    }
    if (strcmp(name, "fgetxattr") == 0) {
        return reinterpret_cast<void*>(hook_fgetxattr);
    }
    if (strcmp(name, "listxattr") == 0) {
        return reinterpret_cast<void*>(hook_listxattr);
    }
    if (strcmp(name, "llistxattr") == 0) {
        return reinterpret_cast<void*>(hook_llistxattr);
    }
    if (strcmp(name, "flistxattr") == 0) {
        return reinterpret_cast<void*>(hook_flistxattr);
    }
    if (strcmp(name, "stat") == 0) return reinterpret_cast<void*>(hook_stat);
    if (strcmp(name, "stat64") == 0) return reinterpret_cast<void*>(hook_stat64);
    if (strcmp(name, "lstat") == 0) return reinterpret_cast<void*>(hook_lstat);
    if (strcmp(name, "lstat64") == 0) return reinterpret_cast<void*>(hook_lstat64);
    if (strcmp(name, "statfs") == 0) return reinterpret_cast<void*>(hook_statfs);
    if (strcmp(name, "statvfs") == 0) return reinterpret_cast<void*>(hook_statvfs);
    if (strcmp(name, "fstat") == 0) return reinterpret_cast<void*>(hook_fstat);
    if (strcmp(name, "fstat64") == 0) {
        return reinterpret_cast<void*>(hook_fstat64);
    }
    if (strcmp(name, "fstatat") == 0) return reinterpret_cast<void*>(hook_fstatat);
    if (strcmp(name, "fstatat64") == 0) {
        return reinterpret_cast<void*>(hook_fstatat64);
    }
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
    if (strcmp(name, "mkdir") == 0) return reinterpret_cast<void*>(hook_mkdir);
    if (strcmp(name, "mkdirat") == 0) return reinterpret_cast<void*>(hook_mkdirat);
    if (strcmp(name, "rmdir") == 0) return reinterpret_cast<void*>(hook_rmdir);
    if (strcmp(name, "chmod") == 0) return reinterpret_cast<void*>(hook_chmod);
    if (strcmp(name, "fchmodat") == 0) {
        return reinterpret_cast<void*>(hook_fchmodat);
    }
    if (strcmp(name, "fchmod") == 0) {
        return reinterpret_cast<void*>(hook_fchmod);
    }
    if (strcmp(name, "unlink") == 0) return reinterpret_cast<void*>(hook_unlink);
    if (strcmp(name, "unlinkat") == 0) {
        return reinterpret_cast<void*>(hook_unlinkat);
    }
    if (strcmp(name, "rename") == 0) return reinterpret_cast<void*>(hook_rename);
    if (strcmp(name, "renameat") == 0) {
        return reinterpret_cast<void*>(hook_renameat);
    }
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
    if (strcmp(name, "malloc") == 0 && object_diagnostics_enabled()) {
        return reinterpret_cast<void*>(hook_malloc);
    }
    if (strcmp(name, "calloc") == 0 && object_diagnostics_enabled()) {
        return reinterpret_cast<void*>(hook_calloc);
    }
    if (strcmp(name, "realloc") == 0 && object_diagnostics_enabled()) {
        return reinterpret_cast<void*>(hook_realloc);
    }
    if (strcmp(name, "free") == 0 && object_diagnostics_enabled()) {
        return reinterpret_cast<void*>(hook_free);
    }
    if (strcmp(name, "memcpy") == 0 && object_diagnostics_enabled()) {
        return reinterpret_cast<void*>(hook_memcpy);
    }
    if (strcmp(name, "memmove") == 0 && object_diagnostics_enabled()) {
        return reinterpret_cast<void*>(hook_memmove);
    }
    if (strcmp(name, "memset") == 0 && object_diagnostics_enabled()) {
        return reinterpret_cast<void*>(hook_memset);
    }
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
    if (strcmp(name, "kill") == 0) {
        return reinterpret_cast<void*>(hook_kill);
    }
    if (strcmp(name, "raise") == 0) {
        return reinterpret_cast<void*>(hook_raise);
    }
    if (strcmp(name, "abort") == 0) {
        return reinterpret_cast<void*>(hook_abort);
    }
    if (strcmp(name, "exit") == 0) {
        return reinterpret_cast<void*>(hook_exit);
    }
    if (strcmp(name, "_exit") == 0) {
        return reinterpret_cast<void*>(hook_exit_immediately);
    }
    if (strcmp(name, "mmap") == 0 && jit_trace_enabled()) {
        return reinterpret_cast<void*>(hook_mmap);
    }
    if (strcmp(name, "munmap") == 0 && jit_trace_enabled()) {
        return reinterpret_cast<void*>(hook_munmap);
    }
    if (strcmp(name, "mprotect") == 0 && jit_trace_enabled()) {
        return reinterpret_cast<void*>(hook_mprotect);
    }
    if (strcmp(name, "__clear_cache") == 0 && jit_trace_enabled()) {
        return reinterpret_cast<void*>(hook_clear_cache);
    }
    if (strcmp(name, "prctl") == 0) {
        return reinterpret_cast<void*>(hook_prctl);
    }
    if (strcmp(name, "ptrace") == 0) {
        return reinterpret_cast<void*>(hook_ptrace);
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
