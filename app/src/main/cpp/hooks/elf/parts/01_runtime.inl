void scan_maps_for_target_modules() {
    static bool logged_read = false;
    static bool logged_paths = false;
    const std::string maps = read_file_bytes("/proc/self/maps");
    if (!logged_read) {
        logged_read = true;
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kLogTag,
            "maps scan bytes=%zu",
            maps.size()
        );
    }
    if (maps.empty()) return;

    std::unordered_map<std::string, MappedModule> modules;
    size_t offset = 0;
    while (offset < maps.size()) {
        const size_t end = maps.find('\n', offset);
        const size_t length = end == std::string::npos
            ? maps.size() - offset
            : end - offset;
        const std::string line = maps.substr(offset, length);
        unsigned long start = 0;
        unsigned long stop = 0;
        unsigned long file_offset = 0;
        if (sscanf(
                line.c_str(),
                "%lx-%lx %*s %lx",
                &start,
                &stop,
                &file_offset
            ) == 3) {
            const size_t path_start = line.find('/');
            if (path_start != std::string::npos) {
                std::string path = line.substr(path_start);
                const std::string deleted = " (deleted)";
                if (path.size() >= deleted.size() &&
                    path.compare(
                        path.size() - deleted.size(),
                        deleted.size(),
                        deleted
                    ) == 0) {
                    path.resize(path.size() - deleted.size());
                }
                if (path.size() < 3 ||
                    path.compare(path.size() - 3, 3, ".so") != 0) {
                    if (end == std::string::npos) break;
                    offset = end + 1;
                    continue;
                }
                const bool target = target_object(path.c_str());
                if (!logged_paths && path.find("/vspace/") != std::string::npos) {
                    logged_paths = true;
                    __android_log_print(
                        ANDROID_LOG_DEBUG,
                        kLogTag,
                        "maps path target=%d path=%s",
                        target ? 1 : 0,
                        path.c_str()
                    );
                }
                if (target) {
                    auto& module = modules[path];
                    module.path = path;
                    module.base = std::min(
                        module.base,
                        static_cast<uintptr_t>(start - file_offset)
                    );
                    module.end = std::max(
                        module.end,
                        static_cast<uintptr_t>(stop)
                    );
                }
            }
        }
        if (end == std::string::npos) break;
        offset = end + 1;
    }

    for (const auto& item : modules) {
        const auto& module = item.second;
        const ModuleKey key{
            module.base,
            std::hash<std::string_view>{}(module.path)
        };
        if (!g_scanned_modules.insert(key).second) continue;
        g_target_modules.insert(key);
        if (hook_elf_file(module.path, module.base)) {
            g_hooked_modules.insert(key);
        }
    }
}

bool loader_object(const char* name) {
    if (name == nullptr || name[0] == '\0') return false;
    const std::string_view path(name);
    constexpr std::string_view kSuffixes[] = {
        "libart.so",
        "libnativeloader.so",
        "libandroid_runtime.so",
    };
    for (const auto suffix : kSuffixes) {
        if (path.size() >= suffix.size() &&
            path.compare(path.size() - suffix.size(), suffix.size(), suffix) == 0) {
            return true;
        }
    }
    return false;
}

int hook_callback(dl_phdr_info* info, size_t, void*) {
    if (info == nullptr) return 0;
    const ModuleKey key = module_key(*info);
    if (!g_scanned_modules.insert(key).second) return 0;
    if (current_environment() != nullptr && loader_object(info->dlpi_name)) {
        hook_object(*info, true);
        return 0;
    }
    if (!target_object(info->dlpi_name)) return 0;
    g_target_modules.insert(key);
    if (hook_object(*info, false)) g_hooked_modules.insert(key);
    return 0;
}

HookState snapshot_locked() {
    return HookState{
        static_cast<int>(g_patched_slots.size()),
        static_cast<int>(g_hooked_modules.size()),
        static_cast<int>(g_scanned_modules.size()),
        static_cast<int>(g_target_modules.size()),
        g_counters.errors,
        g_virtual_proc_reads.load(std::memory_order_relaxed),
        g_property_reads.load(std::memory_order_relaxed),
        g_dlopen_calls.load(std::memory_order_relaxed),
        g_last_hook_event.load(std::memory_order_relaxed),
        g_last_syscall_number.load(std::memory_order_relaxed),
    };
}

}  // namespace

void record_hook_event(jint event) {
    g_last_hook_event.store(event, std::memory_order_relaxed);
}

void record_syscall_event(jlong number) {
    g_last_syscall_number.store(number, std::memory_order_relaxed);
}

HookState refresh_hooks(bool scan_elf_fallback) {
    initialize_real_functions();
    install_property_hooks();
    std::lock_guard<std::mutex> guard(g_hook_lock);
    dl_iterate_phdr(hook_callback, nullptr);
    // The file-backed maps fallback cannot safely patch every hardened
    // module. Only use it when the normal linker enumeration missed the
    // target entirely.
    if (scan_elf_fallback && g_target_modules.empty()) {
        scan_maps_for_target_modules();
    }
    return snapshot_locked();
}

void refresh_loaded_modules() {
    initialize_real_functions();
    std::lock_guard<std::mutex> guard(g_hook_lock);
    dl_iterate_phdr(hook_callback, nullptr);
}

void request_loaded_modules_refresh() {
    {
        std::lock_guard<std::mutex> guard(g_refresh_queue_lock);
        g_refresh_requested = true;
        if (!g_refresh_worker_started) {
            g_refresh_worker_started = true;
            std::thread([] {
                for (;;) {
                    {
                        std::unique_lock<std::mutex> lock(g_refresh_queue_lock);
                        g_refresh_queue_ready.wait(lock, [] {
                            return g_refresh_requested;
                        });
                        g_refresh_requested = false;
                    }
                    refresh_loaded_modules();
                }
            }).detach();
        }
    }
    g_refresh_queue_ready.notify_one();
}

HookState hook_state() {
    std::lock_guard<std::mutex> guard(g_hook_lock);
    return snapshot_locked();
}

void restore_hooks() {
    {
        std::lock_guard<std::mutex> queue_guard(g_refresh_queue_lock);
        g_refresh_requested = false;
    }
    std::lock_guard<std::mutex> guard(g_hook_lock);
    for (const auto& patched : g_patched_slots) {
        write_slot(patched.address, patched.original);
    }
    g_patched_slots.clear();
    g_patched_addresses.clear();
    g_scanned_modules.clear();
    g_target_modules.clear();
    g_hooked_modules.clear();
    g_counters.errors = 0;
    g_virtual_proc_reads.store(0, std::memory_order_relaxed);
    g_property_reads.store(0, std::memory_order_relaxed);
    g_dlopen_calls.store(0, std::memory_order_relaxed);
    g_last_hook_event.store(kHookEventNone, std::memory_order_relaxed);
    g_last_syscall_number.store(-1, std::memory_order_relaxed);
    clear_fd_paths();
}

}  // namespace nestspace
