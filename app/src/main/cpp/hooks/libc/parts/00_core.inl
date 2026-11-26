#include "nestspace/native_environment_internal.h"

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
ssize_t virtualize_readlink_result(
    char* buffer,
    size_t size,
    ssize_t length
) {
    if (buffer == nullptr || size == 0 || length <= 0) return length;
    std::array<char, PATH_MAX + 1> actual{};
    const size_t actual_length = std::min(
        static_cast<size_t>(length),
        static_cast<size_t>(PATH_MAX)
    );
    memcpy(actual.data(), buffer, actual_length);
    actual[actual_length] = '\0';
    ssize_t mapped = -1;
    if (virtual_path_from_actual(
            actual.data(),
            buffer,
            size,
            &mapped
        )) {
        return mapped;
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
    if (mprotect(
            code,
            static_cast<size_t>(page_size),
            PROT_READ | PROT_EXEC
        ) != 0) {
        munmap(code, static_cast<size_t>(page_size));
        return false;
    }
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
    if (mprotect(
            reinterpret_cast<void*>(page),
            static_cast<size_t>(page_size),
            PROT_READ | PROT_EXEC
        ) != 0 &&
        syscall(
            SYS_mprotect,
            page,
            static_cast<size_t>(page_size),
            PROT_READ | PROT_EXEC
        ) != 0) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "restore patched page failed page=%p errno=%d",
            reinterpret_cast<void*>(page),
            errno
        );
    }
    return true;
#endif
}

}  // namespace

ssize_t hook_readlink(const char* path, char* buffer, size_t size);
ssize_t hook_readlinkat(
    int dirfd,
    const char* path,
    char* buffer,
    size_t size
);

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
        g_real.symlink = reinterpret_cast<SymlinkFunction>(
            dlsym(RTLD_DEFAULT, "symlink")
        );
        g_real.symlinkat = reinterpret_cast<SymlinkatFunction>(
            dlsym(RTLD_DEFAULT, "symlinkat")
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
        g_real.strlenCall = reinterpret_cast<StrlenFunction>(
            dlsym(RTLD_DEFAULT, "strlen")
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
        g_real.loaderDlopen = reinterpret_cast<LoaderDlopenFunction>(
            dlsym(RTLD_DEFAULT, "__loader_dlopen")
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

void log_target_open(
    const char* api,
    const char* requested,
    const char* actual,
    int result
) {
    const Environment* environment = current_environment();
    if (requested == nullptr || environment == nullptr) return;
    char enabled[PROP_VALUE_MAX] = {};
    __system_property_get(
        "debug.nestspace.target_open_trace",
        enabled
    );
    const bool target_data_path = strncmp(
            requested,
            environment->target_data_root
                .c_str(),
            environment->target_data_root.size()
        ) == 0 ||
        strncmp(
            requested,
            environment->target_data_alias.c_str(),
            environment->target_data_alias.size()
        ) == 0;
    const bool installed_package_path =
        !environment->virtual_base_apk_path.empty() &&
        strncmp(
            requested,
            environment->virtual_base_apk_path.c_str(),
            environment->virtual_base_apk_path.size()
        ) == 0;
    if (strcmp(enabled, "1") != 0 ||
        (!target_data_path && !installed_package_path)) {
        return;
    }
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "target-open api=%s requested=%s actual=%s result=%d errno=%d",
        api,
        requested,
        actual == nullptr ? "?" : actual,
        result,
        result < 0 ? errno : 0
    );
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

bool install_shared_inline_hook(
    void* target,
    void* replacement,
    void** trampoline
) {
    return install_inline_jump(target, replacement, trampoline);
}
