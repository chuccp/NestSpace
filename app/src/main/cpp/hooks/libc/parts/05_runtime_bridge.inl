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
    const bool mapped = rewrite_target_path(path, &rewritten);
    const char* actual = mapped ? rewritten.c_str() : path;
    if (mapped) {
        struct stat value {};
        const int stat_result = g_real.lstat
            ? g_real.lstat(actual, &value)
            : lstat(actual, &value);
        if (stat_result == 0 && !S_ISLNK(value.st_mode)) {
            errno = EINVAL;
            return -1;
        }
    }
    const ssize_t length = g_real.readlink
        ? g_real.readlink(actual, buffer, size)
        : readlink(actual, buffer, size);
    return virtualize_readlink_result(buffer, size, length);
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
    const bool mapped = rewrite_target_path(path, &rewritten);
    const char* actual = mapped ? rewritten.c_str() : path;
    if (mapped) {
        struct stat value {};
        const int stat_result = g_real.fstatat
            ? g_real.fstatat(dirfd, actual, &value, AT_SYMLINK_NOFOLLOW)
            : fstatat(dirfd, actual, &value, AT_SYMLINK_NOFOLLOW);
        if (stat_result == 0 && !S_ISLNK(value.st_mode)) {
            errno = EINVAL;
            return -1;
        }
    }
    const ssize_t length = g_real.readlinkat
        ? g_real.readlinkat(dirfd, actual, buffer, size)
        : readlinkat(dirfd, actual, buffer, size);
    return virtualize_readlink_result(buffer, size, length);
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

int hook_symlink(const char* target, const char* link_path) {
    record_hook_event(kHookEventFile);
    std::string rewritten_target;
    std::string rewritten_link;
    const char* actual_target = rewrite_target_path(target, &rewritten_target)
        ? rewritten_target.c_str()
        : target;
    const char* actual_link = rewrite_target_path(link_path, &rewritten_link)
        ? rewritten_link.c_str()
        : link_path;
    return g_real.symlink
        ? g_real.symlink(actual_target, actual_link)
        : symlink(actual_target, actual_link);
}

int hook_symlinkat(
    const char* target,
    int new_dirfd,
    const char* link_path
) {
    record_hook_event(kHookEventFile);
    std::string rewritten_target;
    std::string rewritten_link;
    const char* actual_target = rewrite_target_path(target, &rewritten_target)
        ? rewritten_target.c_str()
        : target;
    const char* actual_link = rewrite_target_path(link_path, &rewritten_link)
        ? rewritten_link.c_str()
        : link_path;
    return g_real.symlinkat
        ? g_real.symlinkat(actual_target, new_dirfd, actual_link)
        : symlinkat(actual_target, new_dirfd, actual_link);
}

bool hook_android_base_readlink(
    const std::string& path,
    std::string* result
) {
    if (result == nullptr) {
        errno = EINVAL;
        return false;
    }
    result->clear();
    std::array<char, PATH_MAX + 1> output{};
    const ssize_t length = hook_readlink(
        path.c_str(),
        output.data(),
        PATH_MAX
    );
    if (length < 0) return false;
    result->assign(output.data(), static_cast<size_t>(length));
    return true;
}

DIR* hook_opendir(const char* path) {
    record_hook_event(kHookEventFile);
    log_sensitive_path("opendir", path);
    const int virtual_fd = open_virtual_proc(
        path,
        O_RDONLY | O_DIRECTORY | O_CLOEXEC
    );
    if (virtual_fd >= 0) {
        DIR* directory = fdopendir(virtual_fd);
        if (directory != nullptr) return directory;
        close(virtual_fd);
    }
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

size_t hook_strlen(const char* value) {
    const bool trace = string_trace_enabled();
    const bool page_aligned = value != nullptr &&
        (reinterpret_cast<uintptr_t>(value) & 0xfffU) == 0;
    if (trace || page_aligned) {
        const uintptr_t caller = reinterpret_cast<uintptr_t>(
            __builtin_return_address(0)
        );
        Dl_info info{};
        const bool resolved = g_real.dladdr != nullptr &&
            g_real.dladdr(reinterpret_cast<void*>(caller), &info) != 0;
        const uintptr_t base = resolved
            ? reinterpret_cast<uintptr_t>(info.dli_fbase)
            : 0;
        char bytes[32] = {};
        struct iovec local {
            bytes,
            sizeof(bytes)
        };
        struct iovec remote {
            const_cast<char*>(value),
            sizeof(bytes)
        };
        const long copied = value == nullptr
            ? -1
            : syscall(
                SYS_process_vm_readv,
                getpid(),
                &local,
                1,
                &remote,
                1,
                0
            );
        const int copied_errno = copied < 0 ? errno : 0;
        struct sigaction bus_action {};
        if (g_real.sigactionCall != nullptr) {
            g_real.sigactionCall(SIGBUS, nullptr, &bus_action);
        }
        if (trace) {
            __android_log_print(
                ANDROID_LOG_WARN,
                "NestSpaceNative",
                "target-strlen value=%p caller=%p offset=%016" PRIxPTR
                " bytes=%ld errno=%d bus_handler=%p bus_flags=%08x module=%s "
                "%02x%02x%02x%02x%02x%02x%02x%02x"
                "%02x%02x%02x%02x%02x%02x%02x%02x",
                value,
                reinterpret_cast<void*>(caller),
                base == 0 ? 0 : caller - base,
                copied,
                copied_errno,
                (bus_action.sa_flags & SA_SIGINFO) != 0
                    ? reinterpret_cast<void*>(bus_action.sa_sigaction)
                    : reinterpret_cast<void*>(bus_action.sa_handler),
                bus_action.sa_flags,
                resolved && info.dli_fname != nullptr ? info.dli_fname : "?",
                static_cast<unsigned char>(bytes[0]),
                static_cast<unsigned char>(bytes[1]),
                static_cast<unsigned char>(bytes[2]),
                static_cast<unsigned char>(bytes[3]),
                static_cast<unsigned char>(bytes[4]),
                static_cast<unsigned char>(bytes[5]),
                static_cast<unsigned char>(bytes[6]),
                static_cast<unsigned char>(bytes[7]),
                static_cast<unsigned char>(bytes[8]),
                static_cast<unsigned char>(bytes[9]),
                static_cast<unsigned char>(bytes[10]),
                static_cast<unsigned char>(bytes[11]),
                static_cast<unsigned char>(bytes[12]),
                static_cast<unsigned char>(bytes[13]),
                static_cast<unsigned char>(bytes[14]),
                static_cast<unsigned char>(bytes[15])
            );
        }
        if (page_aligned && copied < 0) {
            const long page_size = sysconf(_SC_PAGESIZE);
            if (page_size > 0) {
                void* replacement = mmap(
                    const_cast<char*>(value),
                    static_cast<size_t>(page_size),
                    PROT_READ | PROT_WRITE,
                    MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED,
                    -1,
                    0
                );
                if (replacement != MAP_FAILED) {
                    memset(
                        replacement,
                        0,
                        static_cast<size_t>(page_size)
                    );
                    __android_log_print(
                        ANDROID_LOG_WARN,
                        "NestSpaceNative",
                        "restored stale string page=%p",
                        replacement
                    );
                }
            }
        }
    }
    return g_real.strlenCall ? g_real.strlenCall(value) : strlen(value);
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
    const Environment* environment = current_environment();
    if (environment != nullptr && info->dlpi_name != nullptr &&
        nestspace::host_runtime_path_matches(
            info->dlpi_name,
            environment
        )) {
        // Keep loader enumeration consistent with a directly installed app:
        // host implementation modules are not part of the target process API.
        return 0;
    }
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
    char path[4096] = {};
    ssize_t mapped_length = -1;
    if (virtual_path_from_actual(
            info->dli_fname,
            path,
            sizeof(path),
            &mapped_length
        )) {
        char trace[PROP_VALUE_MAX] = {};
        __system_property_get(
            "debug.nestspace.loader_path_trace",
            trace
        );
        if (strcmp(trace, "1") == 0) {
            __android_log_print(
                ANDROID_LOG_WARN,
                "NestSpaceNative",
                "target-dladdr address=%p actual=%s virtual=%s",
                address,
                info->dli_fname,
                path
            );
        }
        // dladdr consumers may retain more than one module path and compare
        // pointers from separate calls. Keep each mapping stable for the
        // process lifetime instead of reusing one thread-local buffer.
        static std::mutex path_lock;
        static std::unordered_map<std::string, std::string> stable_paths;
        std::lock_guard<std::mutex> lock(path_lock);
        auto& stable = stable_paths[info->dli_fname];
        if (stable.empty()) stable = path;
        info->dli_fname = stable.c_str();
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
    if (handle == nullptr && actual != nullptr &&
        actual[0] == '/' && g_real.dlopen != nullptr) {
        if (preload_dependency_closure(actual, g_real.dlopen) > 0) {
            handle = g_real.dlopen(actual, flags);
        }
    }
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
