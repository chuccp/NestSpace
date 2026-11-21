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
