std::atomic<int> g_user_notify_listener{-1};
std::atomic<bool> g_user_notify_started{false};
std::atomic<bool> g_identity_notify_installed{false};

void* user_notification_loop(void*);

bool notification_proc_path(const char* path, pid_t process_id) {
    if (path == nullptr) return false;
    if (strncmp(path, "/proc/self/", 11) == 0 ||
        strncmp(path, "/proc/thread-self/", 18) == 0) {
        return true;
    }
    const std::string prefix =
        "/proc/" + std::to_string(process_id) + "/";
    if (strncmp(path, prefix.c_str(), prefix.size()) == 0) return true;
    const auto* environment = nestspace::current_environment();
    if (environment == nullptr || environment->virtual_pid <= 0) {
        return false;
    }
    const std::string virtual_prefix =
        "/proc/" + std::to_string(environment->virtual_pid) + "/";
    return strncmp(
        path,
        virtual_prefix.c_str(),
        virtual_prefix.size()
    ) == 0;
}

bool gateway_proc_path(
    const char* path,
    pid_t process_id,
    std::string* rewritten
) {
    if (path == nullptr || rewritten == nullptr) return false;
    constexpr const char* kSelf = "/proc/self";
    constexpr const char* kThreadSelf = "/proc/thread-self";
    const char* suffix = nullptr;
    if (strncmp(path, kSelf, strlen(kSelf)) == 0) {
        suffix = path + strlen(kSelf);
    } else if (strncmp(
            path,
            kThreadSelf,
            strlen(kThreadSelf)
        ) == 0) {
        suffix = path + strlen(kThreadSelf);
    } else {
        const std::string actual_prefix =
            "/proc/" + std::to_string(process_id);
        if (strncmp(
                path,
                actual_prefix.c_str(),
                actual_prefix.size()
            ) == 0) {
            suffix = path + actual_prefix.size();
        } else {
            const auto* environment = nestspace::current_environment();
            const int virtual_pid = environment == nullptr
                ? -1
                : environment->virtual_pid;
            const std::string virtual_prefix =
                "/proc/" + std::to_string(virtual_pid);
            if (virtual_pid > 0 && strncmp(
                    path,
                    virtual_prefix.c_str(),
                    virtual_prefix.size()
                ) == 0) {
                suffix = path + virtual_prefix.size();
            }
        }
    }
    if (suffix == nullptr) return false;
    *rewritten = "/proc/" + std::to_string(process_id) + suffix;
    return true;
}

bool gateway_proc_directory_path(
    const char* path,
    pid_t process_id,
    std::string* rewritten
) {
    (void)process_id;
    if (path == nullptr) return false;
    const size_t length = strlen(path);
    const bool directory =
        (length >= 5 && strcmp(path + length - 5, "/task") == 0) ||
        (length >= 3 && strcmp(path + length - 3, "/fd") == 0);
    if (directory) {
        if (rewritten != nullptr) *rewritten = path;
        return true;
    }
    return false;
}

void respond_user_notification(
    int listener_fd,
    const seccomp_notif& request,
    seccomp_notif_resp& response
) {
    const long syscall_number = request.data.nr;
    const auto* environment = nestspace::current_environment();
#if defined(SYS_getresuid)
    if (syscall_number == SYS_getresuid) {
        if (environment == nullptr) {
            response.error = -EPERM;
        } else {
            const uid_t uid =
                static_cast<uid_t>(environment->observed_uid);
            for (size_t index = 0; index < 3; ++index) {
                const auto destination = static_cast<uintptr_t>(
                    request.data.args[index]
                );
                if (destination != 0 &&
                    !write_user_memory(destination, &uid, sizeof(uid))) {
                    response.error = -EFAULT;
                    break;
                }
            }
        }
        ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
        return;
    }
#endif
#if defined(SYS_getresgid)
    if (syscall_number == SYS_getresgid) {
        if (environment == nullptr) {
            response.error = -EPERM;
        } else {
            const gid_t gid =
                static_cast<gid_t>(environment->observed_uid);
            for (size_t index = 0; index < 3; ++index) {
                const auto destination = static_cast<uintptr_t>(
                    request.data.args[index]
                );
                if (destination != 0 &&
                    !write_user_memory(destination, &gid, sizeof(gid))) {
                    response.error = -EFAULT;
                    break;
                }
            }
        }
        ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
        return;
    }
#endif
    if (syscall_number == SYS_getgroups) {
        if (environment == nullptr) {
            response.error = -EPERM;
        } else {
            const int count = static_cast<int>(request.data.args[0]);
            const auto destination = static_cast<uintptr_t>(
                request.data.args[1]
            );
            const auto& groups = environment->supplementary_groups;
            const int required = static_cast<int>(groups.size());
            if (count < 0 || (count != 0 && count < required)) {
                response.error = -EINVAL;
            } else if (count != 0) {
                std::array<gid_t, 64> values {};
                const size_t written = std::min(
                    groups.size(),
                    values.size()
                );
                for (size_t index = 0; index < written; ++index) {
                    values[index] = static_cast<gid_t>(groups[index]);
                }
                if (!write_user_memory(
                        destination,
                        values.data(),
                        written * sizeof(gid_t)
                    )) {
                    response.error = -EFAULT;
                } else {
                    response.val = static_cast<long>(required);
                }
            } else {
                response.val = required;
            }
        }
        ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
        return;
    }
    if (syscall_number == SYS_kill ||
        syscall_number == SYS_tkill ||
#ifdef SYS_tgkill
        syscall_number == SYS_tgkill ||
#endif
        false) {
        const bool is_tgkill = syscall_number == SYS_tgkill;
        const pid_t target = static_cast<pid_t>(request.data.args[0]);
        const pid_t caller_process =
            static_cast<pid_t>(request.pid);
        const int signal = static_cast<int>(
            request.data.args[is_tgkill ? 2 : 1]
        );
        if (signal == SIGKILL &&
            (target == caller_process || target == getpid())) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "ignored self SIGKILL via user-notify syscall=%lld"
                " pid=%d tid=%d",
                static_cast<long long>(syscall_number),
                getpid(),
                request.pid
            );
            response.val = 0;
        } else {
            response.flags = SECCOMP_USER_NOTIF_FLAG_CONTINUE;
        }
        ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
        return;
    }
#if defined(SYS_exit_group)
    if (syscall_number == SYS_exit_group) {
        if (diagnostic_enabled(
                "NESTSPACE_PAUSE_ON_EXIT",
                "debug.nestspace.pause_on_exit"
            )) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "paused target exit status=%lld tid=%d",
                static_cast<long long>(request.data.args[0]),
                request.pid
            );
            return;
        }
        response.flags = SECCOMP_USER_NOTIF_FLAG_CONTINUE;
        ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
        return;
    }
#endif
#if defined(SYS_munmap)
    if (syscall_number == SYS_munmap) {
        const long page_size = sysconf(_SC_PAGESIZE);
        const bool retain =
            nestspace::current_environment() != nullptr &&
            page_size > 0 &&
            request.data.args[1] == static_cast<uint64_t>(page_size) &&
            diagnostic_enabled(
                "NESTSPACE_RETAIN_SMALL_CODE_MAPPINGS",
                "debug.nestspace.retain_small_code_mappings"
            );
        if (retain) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "retained target code page address=%016llx",
                static_cast<unsigned long long>(request.data.args[0])
            );
            response.val = 0;
        } else {
            response.flags = SECCOMP_USER_NOTIF_FLAG_CONTINUE;
        }
        ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
        return;
    }
#endif
    if (syscall_number == SYS_getuid ||
        syscall_number == SYS_geteuid ||
        syscall_number == SYS_getgid ||
        syscall_number == SYS_getegid) {
        if (environment == nullptr) {
            response.error = -EPERM;
        } else {
            response.val = nestspace::identity_syscall_uses_target_identity(
                static_cast<uintptr_t>(
                    request.data.instruction_pointer
                )
            )
                ? environment->observed_uid
                : environment->host_uid;
        }
        ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
        return;
    }

#if defined(SYS_newfstatat)
    if (syscall_number == SYS_newfstatat) {
        char virtual_path[PATH_MAX] = {};
        if (!read_user_string(
                static_cast<uintptr_t>(request.data.args[1]),
                virtual_path,
                sizeof(virtual_path)
            )) {
            response.error = -EFAULT;
            ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
            return;
        }
        std::string rewritten;
        const bool mapped = nestspace::rewrite_target_path(
            virtual_path,
            &rewritten
        );
        const bool virtual_proc = notification_proc_path(
            virtual_path,
            static_cast<pid_t>(request.pid)
        );
        std::string actual_proc_path;
        const bool proc_path = virtual_proc &&
            gateway_proc_path(
                virtual_path,
                static_cast<pid_t>(request.pid),
                &actual_proc_path
            );
        GatewayRequest broker_request {};
        broker_request.type = kGatewayFstatat;
        broker_request.client_pid = static_cast<pid_t>(request.pid);
        broker_request.dirfd = static_cast<int>(request.data.args[0]);
        broker_request.flags = static_cast<int>(request.data.args[3]);
        broker_request.rewrite_identity =
            mapped || virtual_proc ? 1 : 0;
        strncpy(
            broker_request.path,
            proc_path
                ? actual_proc_path.c_str()
                : (mapped ? rewritten.c_str() : virtual_path),
            sizeof(broker_request.path) - 1
        );
        GatewayResponse broker_response {};
        const long result = call_syscall_broker(
            &broker_request,
            nullptr,
            &broker_response
        );
        if (result < 0) {
            response.error = result;
        } else if (broker_response.payload_size != 0) {
            const auto destination = static_cast<uintptr_t>(
                request.data.args[2]
            );
            const size_t count = std::min(
                broker_response.payload_size,
                sizeof(broker_response.payload)
            );
            if (!write_user_memory(
                destination,
                broker_response.payload,
                count
            )) {
                response.error = -EFAULT;
            }
        }
        ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
        return;
    }
#endif

#if defined(SYS_statx)
    if (syscall_number == SYS_statx) {
        char virtual_path[PATH_MAX] = {};
        if (!read_user_string(
                static_cast<uintptr_t>(request.data.args[1]),
                virtual_path,
                sizeof(virtual_path)
            )) {
            response.error = -EFAULT;
            ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
            return;
        }
        std::string rewritten;
        const bool mapped = nestspace::rewrite_target_path(
            virtual_path,
            &rewritten
        );
        const bool virtual_proc = notification_proc_path(
            virtual_path,
            static_cast<pid_t>(request.pid)
        );
        std::string actual_proc_path;
        const bool proc_path = virtual_proc &&
            gateway_proc_path(
                virtual_path,
                static_cast<pid_t>(request.pid),
                &actual_proc_path
            );
        GatewayRequest broker_request {};
        broker_request.type = kGatewayStatx;
        broker_request.client_pid = static_cast<pid_t>(request.pid);
        broker_request.dirfd = static_cast<int>(request.data.args[0]);
        broker_request.flags = static_cast<int>(request.data.args[2]);
        broker_request.mask = static_cast<unsigned int>(
            request.data.args[3]
        );
        broker_request.rewrite_identity =
            mapped || virtual_proc ? 1 : 0;
        strncpy(
            broker_request.path,
            proc_path
                ? actual_proc_path.c_str()
                : (mapped ? rewritten.c_str() : virtual_path),
            sizeof(broker_request.path) - 1
        );
        GatewayResponse broker_response {};
        const long result = call_syscall_broker(
            &broker_request,
            nullptr,
            &broker_response
        );
        if (result < 0) {
            response.error = result;
        } else if (broker_response.payload_size != 0) {
            const size_t count = std::min(
                broker_response.payload_size,
                sizeof(broker_response.payload)
            );
            if (!write_user_memory(
                static_cast<uintptr_t>(request.data.args[4]),
                broker_response.payload,
                count
            )) {
                response.error = -EFAULT;
            }
        }
        ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
        return;
    }
#endif

#if defined(SYS_faccessat)
    if (syscall_number == SYS_faccessat) {
        char virtual_path[PATH_MAX] = {};
        if (!read_user_string(
                static_cast<uintptr_t>(request.data.args[1]),
                virtual_path,
                sizeof(virtual_path)
            )) {
            response.error = -EFAULT;
            ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
            return;
        }
        std::string rewritten;
        const bool mapped = nestspace::rewrite_target_path(
            virtual_path,
            &rewritten
        );
        const bool virtual_proc = notification_proc_path(
            virtual_path,
            static_cast<pid_t>(request.pid)
        );
        std::string actual_proc_path;
        const bool proc_path = virtual_proc &&
            gateway_proc_path(
                virtual_path,
                static_cast<pid_t>(request.pid),
                &actual_proc_path
            );
        GatewayRequest broker_request {};
        broker_request.type = kGatewayFaccessat;
        broker_request.dirfd = static_cast<int>(request.data.args[0]);
        broker_request.mode = static_cast<mode_t>(request.data.args[2]);
        broker_request.flags = static_cast<int>(request.data.args[3]);
        strncpy(
            broker_request.path,
            proc_path
                ? actual_proc_path.c_str()
                : (mapped ? rewritten.c_str() : virtual_path),
            sizeof(broker_request.path) - 1
        );
        const long result = call_syscall_broker(&broker_request);
        if (result < 0) response.error = result;
        ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
        return;
    }
#endif

#if defined(SYS_readlinkat)
    if (syscall_number == SYS_readlinkat) {
        char virtual_path[PATH_MAX] = {};
        if (!read_user_string(
                static_cast<uintptr_t>(request.data.args[1]),
                virtual_path,
                sizeof(virtual_path)
            )) {
            response.error = -EFAULT;
            ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
            return;
        }
        std::string rewritten;
        const bool mapped = nestspace::rewrite_target_path(
            virtual_path,
            &rewritten
        );
        const bool virtual_proc = notification_proc_path(
            virtual_path,
            static_cast<pid_t>(request.pid)
        );
        std::string actual_proc_path;
        const bool proc_path = virtual_proc &&
            gateway_proc_path(
                virtual_path,
                static_cast<pid_t>(request.pid),
                &actual_proc_path
            );
        GatewayRequest broker_request {};
        broker_request.type = kGatewayReadlinkat;
        broker_request.dirfd = static_cast<int>(request.data.args[0]);
        strncpy(
            broker_request.path,
            proc_path
                ? actual_proc_path.c_str()
                : (mapped ? rewritten.c_str() : virtual_path),
            sizeof(broker_request.path) - 1
        );
        GatewayResponse broker_response {};
        const long result = call_syscall_broker(
            &broker_request,
            nullptr,
            &broker_response
        );
        if (result < 0) {
            response.error = result;
        } else {
            const size_t capacity = static_cast<size_t>(
                request.data.args[3]
            );
            const size_t count = std::min(
                capacity,
                broker_response.payload_size
            );
            if (!write_user_memory(
                static_cast<uintptr_t>(request.data.args[2]),
                broker_response.payload,
                count
            )) {
                response.error = -EFAULT;
            } else {
                response.val = static_cast<long>(count);
            }
        }
        ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
        return;
    }
#endif

    if (syscall_number != SYS_openat) {
        response.flags = SECCOMP_USER_NOTIF_FLAG_CONTINUE;
        ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
        return;
    }

    char virtual_path[PATH_MAX] = {};
    if (!read_user_string(
            static_cast<uintptr_t>(request.data.args[1]),
            virtual_path,
            sizeof(virtual_path)
        )) {
        response.error = -EFAULT;
        ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
        return;
    }

    std::string rewritten;
    const bool mapped = nestspace::rewrite_target_path(
        virtual_path,
        &rewritten
    );
    std::string proc_directory;
    const bool directory = gateway_proc_directory_path(
            virtual_path,
            static_cast<pid_t>(request.pid),
            &proc_directory
        );
    const bool self_directory = directory &&
        (strncmp(virtual_path, "/proc/self/", 11) == 0 ||
         strncmp(virtual_path, "/proc/thread-self/", 18) == 0);
    if (self_directory) {
        response.flags = SECCOMP_USER_NOTIF_FLAG_CONTINUE;
        ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
        return;
    }
    const bool virtual_proc_file =
        notification_proc_path(
            virtual_path,
            static_cast<pid_t>(request.pid)
        ) && !directory;
    int source_fd = -1;
    const char* actual_path = directory
        ? proc_directory.c_str()
        : (mapped ? rewritten.c_str() : virtual_path);
    if (virtual_proc_file) {
        source_fd = nestspace::open_virtual_proc_for_pid(
            virtual_path,
            static_cast<int>(request.data.args[2]),
            static_cast<pid_t>(request.pid)
        );
    } else {
        // The listener thread predates the filter and retains an unfiltered
        // syscall context when TSYNC is unavailable, so open directly and
        // inject the descriptor with SECCOMP_IOCTL_NOTIF_ADDFD.
        source_fd = static_cast<int>(syscall(
            SYS_openat,
            static_cast<int>(request.data.args[0]),
            actual_path,
            static_cast<int>(request.data.args[2]),
            static_cast<mode_t>(request.data.args[3])
        ));
    }
    if (source_fd < 0) {
        response.error = -errno;
        ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
        return;
    }

    // The notification thread was created before the filter was installed.
    // When TSYNC is unavailable it retains the unfiltered syscall context, so
    // opening and injecting the descriptor here avoids SCM_RIGHTS entirely.
    seccomp_notif_addfd add_fd {};
    add_fd.id = request.id;
    add_fd.flags = directory ? SECCOMP_ADDFD_FLAG_SEND : 0;
    add_fd.srcfd = static_cast<__u32>(source_fd);
    add_fd.newfd_flags = O_CLOEXEC;
    const long installed = ioctl(
        listener_fd,
        SECCOMP_IOCTL_NOTIF_ADDFD,
        &add_fd
    );
    const int installed_errno = installed < 0 ? errno : 0;
    close(source_fd);
    if (installed < 0) {
        response.error = -installed_errno;
    } else {
        response.val = installed;
    }
    if (directory && installed >= 0) return;
    ioctl(listener_fd, SECCOMP_IOCTL_NOTIF_SEND, &response);
}

[[maybe_unused]] bool install_identity_user_notify_gateway() {
#if defined(SYS_seccomp)
    if (g_identity_notify_installed.load(std::memory_order_acquire)) {
        return true;
    }
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        return false;
    }
    if (!start_syscall_broker()) return false;
    if (!g_user_notify_started.exchange(
            true,
            std::memory_order_acq_rel
        )) {
        pthread_t thread {};
        if (pthread_create(
                &thread,
                nullptr,
                user_notification_loop,
                nullptr
            ) != 0) {
            g_user_notify_started.store(false, std::memory_order_release);
            return false;
        }
        pthread_detach(thread);
    }

    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getuid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_geteuid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getgid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getegid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#if defined(__NR_getresuid)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getresuid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#endif
#if defined(__NR_getresgid)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getresgid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#endif
#if defined(__NR_getgroups)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getgroups, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#endif
#if defined(__NR_newfstatat)
        BPF_JUMP(
            BPF_JMP | BPF_JEQ | BPF_K,
            __NR_newfstatat,
            0,
            1
        ),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#endif
#if defined(__NR_statx)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_statx, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#endif
#if defined(__NR_faccessat)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_faccessat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#endif
#if defined(__NR_readlinkat)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_readlinkat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#endif
#if defined(__NR_kill)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_kill, 0, 3),
        BPF_STMT(
            BPF_LD | BPF_W | BPF_ABS,
            offsetof(seccomp_data, args[1])
        ),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#endif
#if defined(__NR_tgkill)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_tgkill, 0, 3),
        BPF_STMT(
            BPF_LD | BPF_W | BPF_ABS,
            offsetof(seccomp_data, args[2])
        ),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#endif
#if defined(__NR_tkill)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_tkill, 0, 3),
        BPF_STMT(
            BPF_LD | BPF_W | BPF_ABS,
            offsetof(seccomp_data, args[1])
        ),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SIGKILL, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#endif
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program {
        static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0])),
        filter
    };

    errno = 0;
    long listener_fd = syscall(
        SYS_seccomp,
        SECCOMP_SET_MODE_FILTER,
        SECCOMP_FILTER_FLAG_NEW_LISTENER |
            SECCOMP_FILTER_FLAG_TSYNC,
        &program
    );
    const int synchronized_errno = listener_fd >= 0 ? 0 : errno;
    if (listener_fd < 0) {
        errno = 0;
        listener_fd = syscall(
            SYS_seccomp,
            SECCOMP_SET_MODE_FILTER,
            SECCOMP_FILTER_FLAG_NEW_LISTENER,
            &program
        );
        if (listener_fd < 0) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "identity user notify failed sync_errno=%d errno=%d",
                synchronized_errno,
                errno
            );
            return false;
        }
    }
    g_user_notify_listener.store(
        static_cast<int>(listener_fd),
        std::memory_order_release
    );
    g_syscall_gateway_active.store(true, std::memory_order_release);
    g_identity_notify_installed.store(true, std::memory_order_release);
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "identity user notify ready listener=%ld sync_errno=%d",
        listener_fd,
        synchronized_errno
    );
    return true;
#else
    return false;
#endif
}

void* user_notification_loop(void*) {
    prctl(PR_SET_NAME, "nsseccompnotify");
    for (;;) {
        const int listener_fd = g_user_notify_listener.load(
            std::memory_order_acquire
        );
        if (listener_fd < 0) {
            sched_yield();
            continue;
        }
        seccomp_notif request {};
        if (ioctl(
                listener_fd,
                SECCOMP_IOCTL_NOTIF_RECV,
                &request
            ) != 0) {
            if (errno == EINTR) continue;
            if (errno == EBADF || errno == ENOENT) return nullptr;
            sched_yield();
            continue;
        }
        seccomp_notif_resp response {};
        response.id = request.id;
        respond_user_notification(listener_fd, request, response);
    }
}

[[maybe_unused]] bool install_user_notify_gateway() {
#if defined(SYS_seccomp) && defined(__NR_openat)
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        return false;
    }
    if (!start_syscall_broker()) return false;
    if (!g_user_notify_started.exchange(
            true,
            std::memory_order_acq_rel
        )) {
        pthread_t thread {};
        if (pthread_create(
                &thread,
                nullptr,
                user_notification_loop,
                nullptr
            ) != 0) {
            g_user_notify_started.store(false, std::memory_order_release);
            return false;
        }
        pthread_detach(thread);
    }

    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(seccomp_data, nr)),
#if defined(SYS_exit_group)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_exit_group, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#endif
#if defined(SYS_munmap)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_munmap, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#endif
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_openat, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#if defined(__NR_newfstatat)
        BPF_JUMP(
            BPF_JMP | BPF_JEQ | BPF_K,
            __NR_newfstatat,
            0,
            1
        ),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#endif
#if defined(__NR_statx)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_statx, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#endif
#if defined(__NR_faccessat)
        BPF_JUMP(
            BPF_JMP | BPF_JEQ | BPF_K,
            __NR_faccessat,
            0,
            1
        ),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#endif
#if defined(__NR_readlinkat)
        BPF_JUMP(
            BPF_JMP | BPF_JEQ | BPF_K,
            __NR_readlinkat,
            0,
            1
        ),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
#endif
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getuid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_geteuid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getgid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getegid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program {
        static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0])),
        filter
    };

    errno = 0;
    long listener_fd = syscall(
        SYS_seccomp,
        SECCOMP_SET_MODE_FILTER,
        SECCOMP_FILTER_FLAG_NEW_LISTENER |
            SECCOMP_FILTER_FLAG_TSYNC,
        &program
    );
    const int synchronized_errno = listener_fd >= 0 ? 0 : errno;
    if (listener_fd < 0) {
        errno = 0;
        listener_fd = syscall(
            SYS_seccomp,
            SECCOMP_SET_MODE_FILTER,
            SECCOMP_FILTER_FLAG_NEW_LISTENER,
            &program
        );
    }
    if (listener_fd < 0) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "user notify gateway failed sync_errno=%d errno=%d",
            synchronized_errno,
            errno
        );
        return false;
    }
    g_syscall_gateway_active.store(true, std::memory_order_release);
    g_user_notify_listener.store(
        static_cast<int>(listener_fd),
        std::memory_order_release
    );
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "user notify gateway ready listener=%ld sync_errno=%d",
        listener_fd,
        synchronized_errno
    );
    return true;
#else
    return false;
#endif
}
