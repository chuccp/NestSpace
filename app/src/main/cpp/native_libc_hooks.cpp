#include "native_environment_internal.h"

#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/stat.h>
#include <sys/stat.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

namespace nestspace {
namespace {

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

}  // namespace

RealFunctions g_real;

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

int hook_open(const char* path, int flags, ...) {
    mode_t mode = 0;
    if ((flags & O_CREAT) != 0) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }
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
    if (handle != nullptr && current_environment() != nullptr) {
        refresh_loaded_modules();
    }
    return handle;
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

    if (number == SYS_openat) {
        const int dirfd = static_cast<int>(next_arg());
        auto* path = reinterpret_cast<const char*>(next_arg());
        const int flags = static_cast<int>(next_arg());
        const mode_t mode = static_cast<mode_t>(next_arg());
        va_end(args);
        if (path != nullptr && path[0] == '/') {
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
    return nullptr;
}

void* loader_replacement_for(const char* name) {
    if (name == nullptr) return nullptr;
    if (strcmp(name, "dlopen") == 0 || strcmp(name, "__loader_dlopen") == 0) {
        return reinterpret_cast<void*>(hook_dlopen);
    }
    if (strcmp(name, "android_dlopen_ext") == 0 ||
        strcmp(name, "__loader_android_dlopen_ext") == 0) {
        return reinterpret_cast<void*>(hook_android_dlopen_ext);
    }
    return nullptr;
}

}  // namespace nestspace
