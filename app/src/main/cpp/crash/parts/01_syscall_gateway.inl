void handle_openat_syscall(ucontext_t* context) {
    if (context == nullptr) return;
    const int dirfd = static_cast<int>(context->uc_mcontext.regs[0]);
    const auto path_address = static_cast<uintptr_t>(
        context->uc_mcontext.regs[1]
    );
    const int flags = static_cast<int>(context->uc_mcontext.regs[2]);
    const mode_t mode = static_cast<mode_t>(context->uc_mcontext.regs[3]);
    char original[PATH_MAX] = {};
    if (!read_user_string(path_address, original, sizeof(original))) {
        context->uc_mcontext.regs[0] =
            static_cast<uintptr_t>(-EFAULT);
        return;
    }
    const long cached_fd = duplicate_gateway_fd_template(original, flags);
    if (cached_fd >= 0 || cached_fd != -ENOENT) {
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
    context->uc_mcontext.regs[0] =
        static_cast<uintptr_t>(returned);
}

bool resolve_gateway_path(
    const char* requested,
    pid_t client_pid,
    std::string* resolved,
    bool* rewrite_identity
) {
    if (requested == nullptr || resolved == nullptr ||
        rewrite_identity == nullptr) {
        return false;
    }
    std::string mapped;
    const bool path_mapped = nestspace::rewrite_target_path(
        requested,
        &mapped
    );
    const bool virtual_proc = notification_proc_path(
        requested,
        client_pid
    );
    std::string actual_proc;
    const bool proc_mapped = virtual_proc &&
        gateway_proc_path(requested, client_pid, &actual_proc);
    *resolved = proc_mapped
        ? actual_proc
        : (path_mapped ? mapped : requested);
    *rewrite_identity = path_mapped || virtual_proc;
    return true;
}

void handle_fstatat_syscall(ucontext_t* context) {
    if (context == nullptr) return;
    const int dirfd = static_cast<int>(context->uc_mcontext.regs[0]);
    const auto path_address = static_cast<uintptr_t>(
        context->uc_mcontext.regs[1]
    );
    const auto result_address = static_cast<uintptr_t>(
        context->uc_mcontext.regs[2]
    );
    const int flags = static_cast<int>(context->uc_mcontext.regs[3]);
    char original[PATH_MAX] = {};
    if (!read_user_string(path_address, original, sizeof(original))) {
        context->uc_mcontext.regs[0] = static_cast<uintptr_t>(-EFAULT);
        return;
    }
    std::string path;
    bool rewrite_identity = false;
    if (!resolve_gateway_path(
            original,
            getpid(),
            &path,
            &rewrite_identity
        )) {
        context->uc_mcontext.regs[0] = static_cast<uintptr_t>(-EINVAL);
        return;
    }
    GatewayRequest request {};
    request.type = notification_proc_path(original, getpid())
        ? kGatewayVirtualFstatat
        : kGatewayFstatat;
    request.client_pid = getpid();
    request.dirfd = dirfd;
    request.flags = flags;
    request.rewrite_identity = rewrite_identity ? 1 : 0;
    strncpy(request.path, path.c_str(), sizeof(request.path) - 1);
    GatewayResponse response {};
    const long result = call_syscall_broker(
        &request,
        nullptr,
        &response
    );
    if (result == 0 && response.payload_size != 0) {
        struct stat value {};
        memcpy(
            &value,
            response.payload,
            std::min(response.payload_size, sizeof(value))
        );
        nestspace::virtualize_target_file_stat(
            notification_proc_path(original, getpid())
                ? original
                : path.c_str(),
            &value
        );
        memcpy(
            reinterpret_cast<void*>(result_address),
            &value,
            std::min(response.payload_size, sizeof(value))
        );
    }
    context->uc_mcontext.regs[0] = static_cast<uintptr_t>(result);
}

void handle_statx_syscall(ucontext_t* context) {
    if (context == nullptr) return;
    const int dirfd = static_cast<int>(context->uc_mcontext.regs[0]);
    const auto path_address = static_cast<uintptr_t>(
        context->uc_mcontext.regs[1]
    );
    const int flags = static_cast<int>(context->uc_mcontext.regs[2]);
    const unsigned int mask = static_cast<unsigned int>(
        context->uc_mcontext.regs[3]
    );
    const auto result_address = static_cast<uintptr_t>(
        context->uc_mcontext.regs[4]
    );
    char original[PATH_MAX] = {};
    if (!read_user_string(path_address, original, sizeof(original))) {
        context->uc_mcontext.regs[0] = static_cast<uintptr_t>(-EFAULT);
        return;
    }
    std::string path;
    bool rewrite_identity = false;
    if (!resolve_gateway_path(
            original,
            getpid(),
            &path,
            &rewrite_identity
        )) {
        context->uc_mcontext.regs[0] = static_cast<uintptr_t>(-EINVAL);
        return;
    }
    GatewayRequest request {};
    request.type = notification_proc_path(original, getpid())
        ? kGatewayVirtualStatx
        : kGatewayStatx;
    request.client_pid = getpid();
    request.dirfd = dirfd;
    request.flags = flags;
    request.mask = mask;
    request.rewrite_identity = rewrite_identity ? 1 : 0;
    strncpy(request.path, path.c_str(), sizeof(request.path) - 1);
    GatewayResponse response {};
    const long result = call_syscall_broker(
        &request,
        nullptr,
        &response
    );
    if (result == 0 && response.payload_size != 0) {
        struct statx value {};
        memcpy(
            &value,
            response.payload,
            std::min(response.payload_size, sizeof(value))
        );
        nestspace::virtualize_target_statx(
            notification_proc_path(original, getpid())
                ? original
                : path.c_str(),
            &value
        );
        memcpy(
            reinterpret_cast<void*>(result_address),
            &value,
            std::min(response.payload_size, sizeof(value))
        );
    }
    context->uc_mcontext.regs[0] = static_cast<uintptr_t>(result);
}

void handle_faccessat_syscall(ucontext_t* context) {
    if (context == nullptr) return;
    const int dirfd = static_cast<int>(context->uc_mcontext.regs[0]);
    const auto path_address = static_cast<uintptr_t>(
        context->uc_mcontext.regs[1]
    );
    const int mode = static_cast<int>(context->uc_mcontext.regs[2]);
    char original[PATH_MAX] = {};
    if (!read_user_string(path_address, original, sizeof(original))) {
        context->uc_mcontext.regs[0] = static_cast<uintptr_t>(-EFAULT);
        return;
    }
    std::string path;
    bool rewrite_identity = false;
    if (!resolve_gateway_path(
            original,
            getpid(),
            &path,
            &rewrite_identity
        )) {
        context->uc_mcontext.regs[0] = static_cast<uintptr_t>(-EINVAL);
        return;
    }
    GatewayRequest request {};
    request.type = kGatewayFaccessat;
    request.client_pid = getpid();
    request.dirfd = dirfd;
    request.flags = mode;
    request.rewrite_identity = rewrite_identity ? 1 : 0;
    strncpy(request.path, path.c_str(), sizeof(request.path) - 1);
    const long result = call_syscall_broker(&request);
    context->uc_mcontext.regs[0] = static_cast<uintptr_t>(result);
}

void handle_readlinkat_syscall(ucontext_t* context) {
    if (context == nullptr) return;
    const int dirfd = static_cast<int>(context->uc_mcontext.regs[0]);
    const auto path_address = static_cast<uintptr_t>(
        context->uc_mcontext.regs[1]
    );
    const auto output_address = static_cast<uintptr_t>(
        context->uc_mcontext.regs[2]
    );
    const size_t output_size = static_cast<size_t>(
        context->uc_mcontext.regs[3]
    );
    char original[PATH_MAX] = {};
    if (!read_user_string(path_address, original, sizeof(original))) {
        context->uc_mcontext.regs[0] = static_cast<uintptr_t>(-EFAULT);
        return;
    }
    std::string path;
    bool rewrite_identity = false;
    if (!resolve_gateway_path(
            original,
            getpid(),
            &path,
            &rewrite_identity
        )) {
        context->uc_mcontext.regs[0] = static_cast<uintptr_t>(-EINVAL);
        return;
    }
    GatewayRequest request {};
    request.type = kGatewayReadlinkat;
    request.client_pid = getpid();
    request.dirfd = dirfd;
    request.rewrite_identity = rewrite_identity ? 1 : 0;
    strncpy(request.path, path.c_str(), sizeof(request.path) - 1);
    GatewayResponse response {};
    const long result = call_syscall_broker(
        &request,
        nullptr,
        &response
    );
    if (result >= 0 && response.payload_size != 0) {
        const size_t count = std::min(
            std::min(
                static_cast<size_t>(result),
                response.payload_size
            ),
            output_size
        );
        memcpy(
            reinterpret_cast<void*>(output_address),
            response.payload,
            count
        );
    }
    context->uc_mcontext.regs[0] = static_cast<uintptr_t>(result);
}

bool handle_identity_syscall(ucontext_t* context) {
    if (context == nullptr || nestspace::current_environment() == nullptr) {
        return false;
    }
    const uintptr_t number = static_cast<uintptr_t>(
        context->uc_mcontext.regs[8]
    );
    if (number == SYS_getuid ||
        number == SYS_geteuid ||
        number == SYS_getgid ||
        number == SYS_getegid) {
        const uintptr_t instruction_pointer =
            static_cast<uintptr_t>(context->uc_mcontext.pc);
        context->uc_mcontext.regs[0] = static_cast<uintptr_t>(
            nestspace::identity_syscall_uses_target_identity(
                instruction_pointer
            )
                ? nestspace::current_environment()->observed_uid
                : nestspace::current_environment()->host_uid
        );
        return true;
    }
#ifdef SYS_getresuid
    if (number == SYS_getresuid) {
        context->uc_mcontext.regs[0] = static_cast<uintptr_t>(
            nestspace::hook_getresuid(
                reinterpret_cast<uid_t*>(context->uc_mcontext.regs[0]),
                reinterpret_cast<uid_t*>(context->uc_mcontext.regs[1]),
                reinterpret_cast<uid_t*>(context->uc_mcontext.regs[2])
            )
        );
        return true;
    }
#endif
#ifdef SYS_getresgid
    if (number == SYS_getresgid) {
        context->uc_mcontext.regs[0] = static_cast<uintptr_t>(
            nestspace::hook_getresgid(
                reinterpret_cast<gid_t*>(context->uc_mcontext.regs[0]),
                reinterpret_cast<gid_t*>(context->uc_mcontext.regs[1]),
                reinterpret_cast<gid_t*>(context->uc_mcontext.regs[2])
            )
        );
        return true;
    }
#endif
    if (number == SYS_getgroups) {
        context->uc_mcontext.regs[0] = static_cast<uintptr_t>(
            nestspace::hook_getgroups(
                static_cast<int>(context->uc_mcontext.regs[0]),
                reinterpret_cast<gid_t*>(context->uc_mcontext.regs[1])
            )
        );
        return true;
    }
    return false;
}

[[maybe_unused]] void handle_rt_sigaction_syscall(ucontext_t* context) {
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
    context->uc_mcontext.regs[0] = static_cast<uintptr_t>(returned);
}

[[maybe_unused]] void handle_rt_sigprocmask_syscall(ucontext_t* context) {
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
    if (syscall_number == SYS_openat &&
        g_syscall_gateway_pause.exchange(
            false,
            std::memory_order_acq_rel
        )) {
        syscall(SYS_tgkill, getpid(), gettid(), SIGSTOP);
    }
    if (handle_identity_syscall(context)) {
        return;
    }
    if (syscall_number == SYS_openat) {
        handle_openat_syscall(context);
        return;
    }
#if defined(SYS_newfstatat)
    if (syscall_number == SYS_newfstatat) {
        handle_fstatat_syscall(context);
        return;
    }
#elif defined(SYS_fstatat64)
    if (syscall_number == SYS_fstatat64) {
        handle_fstatat_syscall(context);
        return;
    }
#endif
#if defined(SYS_statx)
    if (syscall_number == SYS_statx) {
        handle_statx_syscall(context);
        return;
    }
#endif
#if defined(SYS_faccessat)
    if (syscall_number == SYS_faccessat) {
        handle_faccessat_syscall(context);
        return;
    }
#endif
#if defined(SYS_readlinkat)
    if (syscall_number == SYS_readlinkat) {
        handle_readlinkat_syscall(context);
        return;
    }
#endif
    if (syscall_number == __NR_close && context != nullptr) {
        const int fd = static_cast<int>(context->uc_mcontext.regs[0]);
        if (fd == g_syscall_broker_request_write ||
            fd == g_syscall_broker_response_read) {
            context->uc_mcontext.regs[0] = 0;
            return;
        }
    }
#if defined(__NR_close_range)
    if (syscall_number == __NR_close_range && context != nullptr) {
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
        context->uc_mcontext.regs[0] =
            static_cast<uintptr_t>(-EPERM);
        return;
    }
#endif
    if (syscall_number == __NR_prctl && context != nullptr) {
        const int option = static_cast<int>(context->uc_mcontext.regs[0]);
        if (option == PR_SET_SECCOMP) {
            context->uc_mcontext.regs[0] =
                static_cast<uintptr_t>(-EPERM);
            return;
        }
    }
#if defined(__NR_execve)
    if (syscall_number == __NR_execve && context != nullptr) {
        context->uc_mcontext.regs[0] =
            static_cast<uintptr_t>(-ENOENT);
        return;
    }
#endif
#if defined(__NR_execveat)
    if (syscall_number == __NR_execveat && context != nullptr) {
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
    const uintptr_t arg0 = context == nullptr
        ? 0
        : static_cast<uintptr_t>(context->uc_mcontext.regs[0]);
    const uintptr_t arg1 = context == nullptr
        ? 0
        : static_cast<uintptr_t>(context->uc_mcontext.regs[1]);
    const uintptr_t arg2 = context == nullptr
        ? 0
        : static_cast<uintptr_t>(context->uc_mcontext.regs[2]);
    if (g_trace_fd >= 0 && context != nullptr) {
        static constexpr char kHeader[] = "exit-syscall\n";
        write(g_trace_fd, kHeader, sizeof(kHeader) - 1);
        write_hex(syscall_number);
        write_hex(arg0);
        write_hex(arg1);
        write_hex(arg2);
        write_hex(static_cast<uintptr_t>(gettid()));
        write_hex(static_cast<uintptr_t>(context->uc_mcontext.pc));
        write_module("pc", context->uc_mcontext.pc);
        write_module("lr", context->uc_mcontext.regs[30]);
        write_maps();
        fsync(g_trace_fd);
    }
    if (context != nullptr) {
        context->uc_mcontext.regs[0] = 0;
        const bool kills_this_process =
            (syscall_number == SYS_kill &&
                static_cast<pid_t>(arg0) == getpid()) ||
            (syscall_number == SYS_tgkill &&
                static_cast<pid_t>(arg0) == getpid() &&
                static_cast<pid_t>(arg1) == getpid());
        if (signal_number == SIGKILL && kills_this_process) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "forwarding self SIGKILL syscall=%llu"
                " pid=%d tid=%d caller=%016llx",
                static_cast<unsigned long long>(syscall_number),
                getpid(),
                gettid(),
                static_cast<unsigned long long>(
                    context->uc_mcontext.pc
                )
            );
        }
        const bool fatal_thread_signal =
            (syscall_number == SYS_kill ||
             syscall_number == SYS_tgkill) &&
            signal_number == SIGKILL;
        if (fatal_thread_signal) {
            context->uc_mcontext.pc = reinterpret_cast<uintptr_t>(
                kills_this_process ? &guard_exit_process : &guard_exit_thread
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

bool install_identity_gateway_filter() {
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getuid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_geteuid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getgid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getegid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#if defined(__NR_getresuid)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getresuid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
#if defined(__NR_getresgid)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getresgid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
#if defined(__NR_getgroups)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getgroups, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
#if defined(__NR_kill)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_kill, 0, 3),
        BPF_STMT(
            BPF_LD | BPF_W | BPF_ABS,
            offsetof(seccomp_data, args[1])
        ),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
#if defined(__NR_tgkill)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_tgkill, 0, 3),
        BPF_STMT(
            BPF_LD | BPF_W | BPF_ABS,
            offsetof(seccomp_data, args[2])
        ),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program {
        static_cast<unsigned short>(
            sizeof(filter) / sizeof(filter[0])
        ),
        filter
    };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        return false;
    }
    install_syscall_handler();
    sigset_t unblock {};
    sigemptyset(&unblock);
    sigaddset(&unblock, SIGSYS);
    sigprocmask(SIG_UNBLOCK, &unblock, nullptr);
    const long result = syscall(
        SYS_seccomp,
        SECCOMP_SET_MODE_FILTER,
        // Keep the legacy SIGSYS fallback thread-local. A synchronized
        // SECCOMP_RET_TRAP is unsafe for framework threads that block SIGSYS;
        // the identity user-notification gateway is the synchronized path.
        0,
        &program
    );
    g_syscall_gateway_active.store(
        result == 0,
        std::memory_order_release
    );
    __android_log_print(
        result == 0 ? ANDROID_LOG_DEBUG : ANDROID_LOG_WARN,
        kLogTag,
        "identity syscall gateway result=%ld errno=%d",
        result,
        result == 0 ? 0 : errno
    );
    return result == 0;
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
#if defined(__NR_newfstatat)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_newfstatat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#elif defined(__NR_fstatat64)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_fstatat64, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
#if defined(__NR_statx)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_statx, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
#if defined(__NR_faccessat)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_faccessat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
#if defined(__NR_readlinkat)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_readlinkat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getuid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_geteuid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getgid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getegid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#if defined(__NR_getresuid)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getresuid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
#if defined(__NR_getresgid)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getresgid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
#if defined(__NR_getgroups)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getgroups, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
#endif
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
    // attachBaseContext has finished creating the target's storage and
    // platform worker threads. Synchronize the identity filter across those
    // existing threads as well as the current thread so direct syscalls made
    // by device-id and networking SDKs see the same virtual identity.
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
