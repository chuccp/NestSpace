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
#ifdef SYS_symlink
    if (number == SYS_symlink) {
        auto* target = reinterpret_cast<const char*>(next_arg());
        auto* link_path = reinterpret_cast<const char*>(next_arg());
        va_end(args);
        return hook_symlink(target, link_path);
    }
#endif
#ifdef SYS_symlinkat
    if (number == SYS_symlinkat) {
        auto* target = reinterpret_cast<const char*>(next_arg());
        const int dirfd = static_cast<int>(next_arg());
        auto* link_path = reinterpret_cast<const char*>(next_arg());
        va_end(args);
        return hook_symlinkat(target, dirfd, link_path);
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
        if (group == getpid() &&
            thread == getpid() &&
            signal == SIGKILL) {
            return 0;
        }
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
        const char* actual = mapped ? rewritten.c_str() : path;
        const long result = g_real.syscall
            ? g_real.syscall(
                number,
                dirfd,
                actual,
                flags,
                mode
            )
            : syscall(
                number,
                dirfd,
                actual,
                flags,
                mode
            );
        log_target_open("syscall-openat", path, actual, static_cast<int>(result));
        return result;
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
        return hook_readlinkat(dirfd, path, buffer, size);
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
