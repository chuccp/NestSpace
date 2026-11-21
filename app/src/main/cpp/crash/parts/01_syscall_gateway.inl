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
        } else if (request.type == kGatewayFstatat ||
                   request.type == kGatewayVirtualFstatat) {
            struct stat status {};
            if (request.type == kGatewayVirtualFstatat) {
                const int fd = nestspace::open_virtual_proc_for_pid(
                    request.path,
                    O_RDONLY | O_CLOEXEC,
                    request.client_pid
                );
                if (fd >= 0) {
                    result = syscall(SYS_fstat, fd, &status);
                    syscall(SYS_close, fd);
                } else {
                    result = fd;
                }
            } else {
                result = syscall(
                    SYS_newfstatat,
                    request.dirfd,
                    request.path,
                    &status,
                    request.flags
                );
            }
            if (result == 0) {
                const auto* environment = nestspace::current_environment();
                if (request.rewrite_identity && environment != nullptr) {
                    status.st_uid = static_cast<uid_t>(
                        environment->observed_uid
                    );
                    status.st_gid = static_cast<gid_t>(
                        environment->observed_uid
                    );
                }
                response.payload_size = sizeof(status);
                memcpy(response.payload, &status, sizeof(status));
            }
        } else if (request.type == kGatewayStatx ||
                   request.type == kGatewayVirtualStatx) {
            struct statx status {};
            if (request.type == kGatewayVirtualStatx) {
                const int fd = nestspace::open_virtual_proc_for_pid(
                    request.path,
                    O_RDONLY | O_CLOEXEC,
                    request.client_pid
                );
                if (fd >= 0) {
                    result = syscall(
                        SYS_statx,
                        fd,
                        "",
                        AT_EMPTY_PATH | AT_SYMLINK_NOFOLLOW,
                        request.mask,
                        &status
                    );
                    syscall(SYS_close, fd);
                } else {
                    result = fd;
                }
            } else {
                result = syscall(
                    SYS_statx,
                    request.dirfd,
                    request.path,
                    request.flags,
                    request.mask,
                    &status
                );
            }
            if (result == 0) {
                const auto* environment = nestspace::current_environment();
                if (request.rewrite_identity && environment != nullptr) {
                    status.stx_uid = static_cast<uint32_t>(
                        environment->observed_uid
                    );
                    status.stx_gid = static_cast<uint32_t>(
                        environment->observed_uid
                    );
                }
                response.payload_size = sizeof(status);
                memcpy(response.payload, &status, sizeof(status));
            }
        } else if (request.type == kGatewayFaccessat) {
            result = syscall(
                SYS_faccessat,
                request.dirfd,
                request.path,
                request.mode
            );
        } else if (request.type == kGatewayReadlinkat) {
            const long length = syscall(
                SYS_readlinkat,
                request.dirfd,
                request.path,
                response.payload,
                sizeof(response.payload)
            );
            result = length;
            if (length >= 0) {
                response.payload_size = static_cast<size_t>(length);
            }
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

[[maybe_unused]] bool install_openat_gateway_filter() {
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
