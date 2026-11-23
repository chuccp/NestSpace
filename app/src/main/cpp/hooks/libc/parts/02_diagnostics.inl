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
    const uint32_t appid = environment->observed_uid >= 10000
        ? static_cast<uint32_t>(environment->observed_uid - 10000)
        : static_cast<uint32_t>(environment->observed_uid);
    const std::string app_categories = "s0:c" +
        std::to_string(appid & 0xffU) + ",c" +
        std::to_string(256U + ((appid >> 8U) & 0xffU)) +
        ",c512,c768";
    if (is_current_proc_identity_path(actual)) {
        return "u:r:untrusted_app:" + app_categories;
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
        return "u:object_r:app_data_file:" + app_categories;
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
constexpr uintptr_t kDiagnosticPointerTagMask =
    sizeof(uintptr_t) >= sizeof(uint64_t)
        ? static_cast<uintptr_t>((uint64_t{1} << 56) - 1)
        : ~uintptr_t{0};
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

bool string_trace_enabled() {
    static const bool enabled = [] {
        if (getenv("NESTSPACE_STRING_TRACE") != nullptr) return true;
        char value[PROP_VALUE_MAX] = {};
        __system_property_get("debug.nestspace.string_trace", value);
        return strcmp(value, "1") == 0;
    }();
    return enabled;
}

bool string_guard_enabled() {
    static const bool enabled = [] {
        if (getenv("NESTSPACE_DISABLE_STRING_GUARD") != nullptr) return false;
        char value[PROP_VALUE_MAX] = {};
        __system_property_get("debug.nestspace.string_guard", value);
        return strcmp(value, "0") != 0;
    }();
    return enabled;
}

bool retain_small_code_mappings_enabled() {
    static const bool enabled = [] {
        if (getenv("NESTSPACE_RETAIN_SMALL_CODE_MAPPINGS") != nullptr) {
            return true;
        }
        char value[PROP_VALUE_MAX] = {};
        __system_property_get(
            "debug.nestspace.retain_small_code_mappings",
            value
        );
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
    if (size >= 0x1000 && result != nullptr) {
        const uintptr_t caller = reinterpret_cast<uintptr_t>(
            __builtin_return_address(0)
        );
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "target-large-alloc size=%zu pointer=%p caller=%016" PRIxPTR,
            size,
            result,
            caller
        );
    }
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
        count * size >= 0x1000 &&
        result != nullptr) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "target-large-calloc count=%zu element=%zu pointer=%p caller=%016" PRIxPTR,
            count,
            size,
            result,
            reinterpret_cast<uintptr_t>(__builtin_return_address(0))
        );
    }
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
    if (size >= 0x10000 && result != nullptr) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "target-large-realloc old=%p size=%zu pointer=%p",
            pointer,
            size,
            result
        );
    }
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
