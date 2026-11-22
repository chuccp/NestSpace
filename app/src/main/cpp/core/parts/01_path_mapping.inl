bool rewrite_target_path(const char* path, std::string* rewritten) {
    if (path == nullptr || path[0] != '/') return false;
    const Environment* environment = current_environment();
    if (environment == nullptr) return false;

    // Internal container paths have already been translated. Running them
    // through the host-package rewrite again creates recursive paths such as
    // dataRoot/files/vspace/data/<target>/instance/files.
    if (starts_with_prefix(path, environment->data_root) ||
        (!environment->data_root_alias.empty() &&
            starts_with_prefix(path, environment->data_root_alias)) ||
        starts_with_prefix(path, environment->actual_device_root) ||
        (!environment->external_data_root.empty() &&
            starts_with_prefix(path, environment->external_data_root))) {
        return false;
    }

    if (!environment->host_base_apk_path.empty()) {
        const std::string base_prefix = environment->host_base_apk_path + "!";
        if (starts_with_prefix(path, base_prefix)) {
            const std::string suffix =
                path + environment->host_base_apk_path.size();
            const std::string* runtime_library =
                runtime_library_for_basename(environment, suffix.c_str());
            if (runtime_library != nullptr) {
                *rewritten = *runtime_library;
                return true;
            }
            *rewritten = environment->actual_base_apk_path + suffix;
            return true;
        }
        if (strcmp(path, environment->host_base_apk_path.c_str()) == 0) {
            *rewritten = environment->actual_base_apk_path;
            return true;
        }
    }
    if (!environment->host_native_library_dir.empty() &&
        environment->host_native_library_dir !=
            environment->native_library_dir &&
        starts_with_prefix(
            path,
            environment->host_native_library_dir
        )) {
        const std::string suffix =
            path + environment->host_native_library_dir.size();
        const std::string* runtime_library =
            runtime_library_for_basename(environment, suffix.c_str());
        if (runtime_library != nullptr) {
            *rewritten = *runtime_library;
        } else {
            *rewritten = environment->native_library_dir + suffix;
        }
        return true;
    }
    if (!environment->virtual_base_apk_path.empty() &&
        strcmp(path, environment->virtual_base_apk_path.c_str()) == 0) {
        *rewritten = environment->actual_base_apk_path;
        return true;
    }
    if (!environment->virtual_native_library_dir.empty() &&
        starts_with_prefix(
            path,
            environment->virtual_native_library_dir
        )) {
        *rewritten = environment->native_library_dir +
            (path + environment->virtual_native_library_dir.size());
        return true;
    }
    const size_t split_count = std::min(
        environment->actual_split_apk_paths.size(),
        environment->virtual_split_apk_paths.size()
    );
    for (size_t index = 0; index < split_count; ++index) {
        if (strcmp(
                path,
                environment->virtual_split_apk_paths[index].c_str()
            ) == 0) {
            *rewritten = environment->actual_split_apk_paths[index];
            return true;
        }
    }

    if (starts_with_prefix(path, environment->target_data_root)) {
        *rewritten = environment->data_root +
            (path + environment->target_data_root.size());
        return true;
    }
    if (starts_with_prefix(path, environment->target_data_alias)) {
        *rewritten = environment->data_root +
            (path + environment->target_data_alias.size());
        return true;
    }
    const std::string host_data_root =
        "/data/user/0/" + environment->host_package_name;
    const std::string host_data_alias =
        "/data/data/" + environment->host_package_name;
    const std::string host_runtime_root = host_data_root + "/files/vspace";
    const std::string host_runtime_alias = host_data_alias + "/files/vspace";
    if (starts_with_prefix(path, host_runtime_root) ||
        starts_with_prefix(path, host_runtime_alias)) {
        return false;
    }
    if (starts_with_prefix(path, host_data_root)) {
        *rewritten = environment->data_root +
            (path + host_data_root.size());
        return true;
    }
    if (starts_with_prefix(path, host_data_alias)) {
        *rewritten = environment->data_root +
            (path + host_data_alias.size());
        return true;
    }
    if (starts_with_prefix(path, environment->target_device_root)) {
        *rewritten = environment->actual_device_root +
            (path + environment->target_device_root.size());
        return true;
    }
    if (!environment->external_data_root.empty() &&
        starts_with_prefix(path, environment->target_external_root)) {
        *rewritten = environment->external_data_root +
            (path + environment->target_external_root.size());
        return true;
    }
    if (!environment->external_data_root.empty() &&
        starts_with_prefix(path, environment->target_external_alias)) {
        *rewritten = environment->external_data_root +
            (path + environment->target_external_alias.size());
        return true;
    }
    if (!environment->external_data_root.empty()) {
        const std::string target_obb =
            "/storage/emulated/0/Android/obb/" + environment->package_name;
        const std::string target_obb_alias =
            "/sdcard/Android/obb/" + environment->package_name;
        if (starts_with_prefix(path, target_obb)) {
            *rewritten = environment->external_data_root + "/obb" +
                (path + target_obb.size());
            return true;
        }
        if (starts_with_prefix(path, target_obb_alias)) {
            *rewritten = environment->external_data_root + "/obb" +
                (path + target_obb_alias.size());
            return true;
        }
        const std::string target_media =
            "/storage/emulated/0/Android/media/" + environment->package_name;
        const std::string target_media_alias =
            "/sdcard/Android/media/" + environment->package_name;
        if (starts_with_prefix(path, target_media)) {
            *rewritten = environment->external_data_root + "/media" +
                (path + target_media.size());
            return true;
        }
        if (starts_with_prefix(path, target_media_alias)) {
            *rewritten = environment->external_data_root + "/media" +
                (path + target_media_alias.size());
            return true;
        }
    }
    return false;
}

namespace {

bool is_target_runtime_path(
    const char* path,
    const Environment* environment
) {
    if (path == nullptr || environment == nullptr) return false;
    return starts_with_prefix(path, environment->data_root) ||
        (!environment->data_root_alias.empty() &&
            starts_with_prefix(path, environment->data_root_alias)) ||
        starts_with_prefix(path, environment->actual_device_root) ||
        (!environment->external_data_root.empty() &&
            starts_with_prefix(path, environment->external_data_root));
}

bool is_current_proc_path(const char* path) {
    if (path == nullptr) return false;
    if (strcmp(path, "/proc/self") == 0 ||
        strncmp(path, "/proc/self/", 11) == 0 ||
        strcmp(path, "/proc/thread-self") == 0 ||
        strncmp(path, "/proc/thread-self/", 18) == 0) {
        return true;
    }
    if (strncmp(path, "/proc/", 6) != 0) return false;
    char* end = nullptr;
    const long pid = strtol(path + 6, &end, 10);
    return (pid == getpid() || pid == getppid()) &&
        (*end == '\0' || *end == '/');
}

bool is_virtual_native_library_path(
    const char* actual,
    const Environment* environment
) {
    if (actual == nullptr || environment == nullptr) return false;
    // Only the installed APK's native-library directory is system-owned.
    // Libraries unpacked beside application data (including runtime boot
    // helpers) remain app-private and must not be presented as 1000:1000.
    return !environment->native_library_dir.empty() &&
        environment->native_library_dir !=
            environment->virtual_native_library_dir &&
        starts_with_prefix(actual, environment->native_library_dir);
}

}  // namespace

void virtualize_target_file_stat(const char* actual, struct stat* value) {
    const Environment* environment = current_environment();
    if (value == nullptr || environment == nullptr) {
        return;
    }
    if (is_virtual_native_library_path(actual, environment)) {
        value->st_uid = 1000;
        value->st_gid = 1000;
        value->st_mode = (value->st_mode & S_IFMT) | 0755;
        return;
    }
    const bool target_runtime = is_target_runtime_path(actual, environment);
    const bool process_owned_by_host =
        is_current_proc_path(actual) &&
        value->st_uid == static_cast<uid_t>(environment->host_uid);
    if (!target_runtime && !process_owned_by_host) return;
    value->st_uid = static_cast<uid_t>(environment->observed_uid);
    if (!environment->external_data_root.empty() &&
        starts_with_prefix(actual, environment->external_data_root)) {
        const std::string obb_prefix =
            environment->external_data_root + "/obb";
        value->st_gid = starts_with_prefix(actual, obb_prefix) ? 1079 : 1078;
    } else {
        const std::string cache_prefix = environment->data_root + "/cache";
        const std::string code_cache_prefix =
            environment->data_root + "/code_cache";
        const bool cache_path =
            strcmp(actual, cache_prefix.c_str()) == 0 ||
            starts_with_prefix(actual, cache_prefix) ||
            strcmp(actual, code_cache_prefix.c_str()) == 0 ||
            starts_with_prefix(actual, code_cache_prefix);
        value->st_gid = cache_path
            ? static_cast<gid_t>(environment->observed_uid + 10000)
            : static_cast<gid_t>(environment->observed_uid);
    }
}

void virtualize_target_statx(const char* actual, struct statx* value) {
    const Environment* environment = current_environment();
    if (value == nullptr || environment == nullptr) {
        return;
    }
    if (is_virtual_native_library_path(actual, environment)) {
        value->stx_uid = 1000;
        value->stx_gid = 1000;
        value->stx_mode =
            (value->stx_mode & S_IFMT) | 0755;
        return;
    }
    const bool target_runtime = is_target_runtime_path(actual, environment);
    const bool process_owned_by_host =
        is_current_proc_path(actual) &&
        value->stx_uid == static_cast<uid_t>(environment->host_uid);
    if (!target_runtime && !process_owned_by_host) return;
    value->stx_uid = static_cast<uid_t>(environment->observed_uid);
    if (!environment->external_data_root.empty() &&
        starts_with_prefix(actual, environment->external_data_root)) {
        const std::string obb_prefix =
            environment->external_data_root + "/obb";
        value->stx_gid = starts_with_prefix(actual, obb_prefix) ? 1079 : 1078;
    } else {
        const std::string cache_prefix = environment->data_root + "/cache";
        const std::string code_cache_prefix =
            environment->data_root + "/code_cache";
        const bool cache_path =
            strcmp(actual, cache_prefix.c_str()) == 0 ||
            starts_with_prefix(actual, cache_prefix) ||
            strcmp(actual, code_cache_prefix.c_str()) == 0 ||
            starts_with_prefix(actual, code_cache_prefix);
        value->stx_gid = cache_path
            ? static_cast<gid_t>(environment->observed_uid + 10000)
            : static_cast<gid_t>(environment->observed_uid);
    }
}

bool virtual_path_from_actual(
    const char* actual,
    char* buffer,
    size_t size,
    ssize_t* result
) {
    if (actual == nullptr || result == nullptr) return false;
    const Environment* environment = current_environment();
    if (environment == nullptr) return false;

    const auto exact = [&](const std::string& source, const std::string& target) {
        if (source.empty() || strcmp(actual, source.c_str()) != 0) return false;
        *result = copy_virtual_path(buffer, size, target, "");
        return true;
    };
    if (exact(
            environment->actual_base_apk_path,
            environment->virtual_base_apk_path
        )) {
        return true;
    }
    if (starts_with_prefix(
            actual,
            environment->native_library_dir
        ) &&
        !environment->virtual_native_library_dir.empty()) {
        *result = copy_virtual_path(
            buffer,
            size,
            environment->virtual_native_library_dir,
            actual + environment->native_library_dir.size()
        );
        return true;
    }
    const size_t split_count = std::min(
        environment->actual_split_apk_paths.size(),
        environment->virtual_split_apk_paths.size()
    );
    for (size_t index = 0; index < split_count; ++index) {
        if (exact(
                environment->actual_split_apk_paths[index],
                environment->virtual_split_apk_paths[index]
            )) {
            return true;
        }
    }

    const auto rewrite = [&](const std::string& source, const std::string& target) {
        if (!starts_with_prefix(actual, source)) return false;
        *result = copy_virtual_path(
            buffer,
            size,
            target,
            actual + source.size()
        );
        return true;
    };

    if (rewrite(environment->actual_device_root, environment->target_device_root)) {
        return true;
    }
    if (rewrite(environment->data_root, environment->target_data_root)) {
        return true;
    }
    if (!environment->data_root_alias.empty() &&
        rewrite(environment->data_root_alias, environment->target_data_alias)) {
        return true;
    }
    if (!environment->external_data_root.empty() &&
        rewrite(environment->external_data_root, environment->target_external_root)) {
        return true;
    }
    if (!environment->external_data_root.empty()) {
        const std::string target_obb =
            "/storage/emulated/0/Android/obb/" + environment->package_name;
        const std::string target_media =
            "/storage/emulated/0/Android/media/" + environment->package_name;
        if (rewrite(
                environment->external_data_root + "/obb",
                target_obb
            )) {
            return true;
        }
        if (rewrite(
                environment->external_data_root + "/media",
                target_media
            )) {
            return true;
        }
    }
    return false;
}

std::string virtualized_proc_content(
    const char* path,
    pid_t self_pid
) {
    const Environment* environment = current_environment();
    if (environment == nullptr || !proc_readable(path)) return {};
    const bool thread_self =
        strncmp(path, "/proc/thread-self/", 18) == 0;
    std::string normalized_path;
    if (thread_self) {
        normalized_path = std::string("/proc/self/") + (path + 18);
        path = normalized_path.c_str();
    }
    pid_t process_pid = 0;
    pid_t task_id = 0;
    const char* task_suffix = nullptr;
    const pid_t pid = self_pid > 0 ? self_pid : getpid();
    if (virtual_task_suffix(
            path,
            &process_pid,
            &task_id,
            &task_suffix
        )) {
        if (strcmp(task_suffix, "/comm") == 0) {
            std::string value = target_task_comm(
                environment,
                process_pid,
                task_id
            );
            if (value.empty()) return {};
            __android_log_print(
                ANDROID_LOG_DEBUG,
                "NestSpaceNative",
                "target-thread-comm tid=%d name=%s",
                task_id,
                value.c_str()
            );
            value.push_back('\n');
            return value;
        }
        if (strcmp(task_suffix, "/status") == 0) {
            const std::string thread_name = target_task_comm(
                environment,
                process_pid,
                task_id
            );
            if (thread_name.empty()) return {};
            return virtualized_status(
                environment,
                process_pid,
                task_id,
                thread_name
            );
        }
        const std::string task_path =
            "/proc/" + std::to_string(process_pid) + "/task/" +
            std::to_string(task_id) + task_suffix;
        if (strcmp(task_suffix, "/attr/current") == 0 ||
            strcmp(task_suffix, "/attr/prev") == 0) {
            const int category = environment->observed_uid - 10000;
            if (category > 0) {
                std::string value = "u:r:untrusted_app:s0:c" +
                    std::to_string(category) +
                    ",c256,c512,c768";
                value.push_back('\0');
                return value;
            }
            return std::string(
                "u:r:untrusted_app:s0",
                sizeof("u:r:untrusted_app:s0")
            );
        }
        if (strcmp(task_suffix, "/cgroup") == 0) {
            std::string cgroup = read_text_file(task_path.c_str());
            replace_all(
                &cgroup,
                "uid_" + std::to_string(environment->host_uid),
                "uid_" + std::to_string(environment->observed_uid)
            );
            return cgroup;
        }
        if (strcmp(task_suffix, "/stat") == 0) {
            std::string stat = read_text_file(task_path.c_str());
            const size_t open = stat.find('(');
            const size_t close = stat.find(')', open == std::string::npos
                ? 0
                : open + 1);
            if (open != std::string::npos && close != std::string::npos) {
                stat.replace(
                    open + 1,
                    close - open - 1,
                    process_thread_name(environment)
                );
            }
            return stat;
        }
        if (strcmp(task_suffix, "/statm") == 0 ||
            strcmp(task_suffix, "/sched") == 0 ||
            strcmp(task_suffix, "/schedstat") == 0) {
            return read_text_file(task_path.c_str());
        }
    }
    if (thread_self) {
        const pid_t tid = static_cast<pid_t>(syscall(SYS_gettid));
        const std::string thread_name = target_task_comm(
            environment,
            pid,
            tid
        );
        if (proc_suffix(path, "/status")) {
            return virtualized_status(
                environment,
                pid,
                tid,
                thread_name
            );
        }
        if (proc_suffix(path, "/comm")) {
            return thread_name + "\n";
        }
    }
    pid_t process_id = pid;
    if (strncmp(path, "/proc/self/", 11) != 0) {
        char* end = nullptr;
        const long parsed = strtol(path + 6, &end, 10);
        if (parsed <= 0 ||
            (parsed != pid && parsed != getppid())) {
            return {};
        }
        process_id = static_cast<pid_t>(parsed);
    }
    if (proc_suffix(path, "/attr/current") ||
        proc_suffix(path, "/attr/prev")) {
        const int category = environment->observed_uid - 10000;
        if (category > 0) {
            std::string value = "u:r:untrusted_app:s0:c" +
                std::to_string(category) +
                ",c256,c512,c768";
            value.push_back('\0');
            return value;
        }
        return std::string(
            "u:r:untrusted_app:s0",
            sizeof("u:r:untrusted_app:s0")
        );
    }
    if (proc_suffix(path, "/cgroup")) {
        std::string cgroup = read_text_file(
            proc_path(process_id, "cgroup").c_str()
        );
        replace_all(
            &cgroup,
            "uid_" + std::to_string(environment->host_uid),
            "uid_" + std::to_string(environment->observed_uid)
        );
        return cgroup;
    }
    if (proc_suffix(path, "/status")) {
        return virtualized_status(environment, process_id);
    }
    if (proc_suffix(path, "/cmdline")) {
        // Android zygotes keep the argv buffer padded with NUL bytes. Readers
        // can observe its full length, so virtualize only the first argument
        // and preserve the process's real cmdline length.
        const std::string actual = read_text_file(
            proc_path(process_id, "cmdline").c_str()
        );
        std::string value = environment->process_name;
        if (actual.size() > value.size()) {
            value.resize(actual.size(), '\0');
        } else {
            value.push_back('\0');
        }
        return value;
    }
    if (proc_suffix(path, "/comm")) {
        return process_thread_name(environment) + "\n";
    }
    if (proc_suffix(path, "/stat") ||
        proc_suffix(path, "/statm") ||
        proc_suffix(path, "/sched")) {
        const char* name = proc_suffix(path, "/stat")
            ? "stat"
            : (proc_suffix(path, "/statm") ? "statm" : "sched");
        return read_text_file(proc_path(process_id, name).c_str());
    }
    if (proc_suffix(path, "/smaps")) {
        const std::string proc_smaps = proc_path(process_id, "smaps");
        std::string value = read_text_file(proc_smaps.c_str());
        replace_all(
            &value,
            environment->actual_base_apk_path,
            environment->virtual_base_apk_path
        );
        replace_all(
            &value,
            data_alias_path(environment->actual_base_apk_path),
            environment->virtual_base_apk_path
        );
        if (!environment->native_library_dir.empty() &&
            !environment->virtual_native_library_dir.empty()) {
            replace_all(
                &value,
                environment->native_library_dir,
                environment->virtual_native_library_dir
            );
        }
        const size_t runtime_count = std::min(
            environment->runtime_library_dirs.size(),
            environment->virtual_runtime_library_dirs.size()
        );
        for (size_t index = 0; index < runtime_count; ++index) {
            replace_all(
                &value,
                environment->runtime_library_dirs[index],
                environment->virtual_runtime_library_dirs[index]
            );
        }
        replace_all(
            &value,
            environment->data_root,
            environment->target_data_root
        );
        replace_all(
            &value,
            environment->data_root_alias,
            environment->target_data_alias
        );
        return filter_host_runtime_lines(value, environment);
    }
    if (proc_suffix(path, "/maps")) {
        const std::string proc_maps = proc_path(process_id, "maps");
        std::string value = read_text_file(proc_maps.c_str());
        replace_all(
            &value,
            environment->actual_base_apk_path,
            environment->virtual_base_apk_path
        );
        replace_all(
            &value,
            data_alias_path(environment->actual_base_apk_path),
            environment->virtual_base_apk_path
        );
        const size_t split_count = std::min(
            environment->actual_split_apk_paths.size(),
            environment->virtual_split_apk_paths.size()
        );
        for (size_t index = 0; index < split_count; ++index) {
            replace_all(
                &value,
                environment->actual_split_apk_paths[index],
                environment->virtual_split_apk_paths[index]
            );
            replace_all(
                &value,
                data_alias_path(environment->actual_split_apk_paths[index]),
                environment->virtual_split_apk_paths[index]
            );
        }
        if (!environment->native_library_dir.empty() &&
            !environment->virtual_native_library_dir.empty()) {
            replace_all(
                &value,
                environment->native_library_dir,
                environment->virtual_native_library_dir
            );
        }
        const size_t runtime_count = std::min(
            environment->runtime_library_dirs.size(),
            environment->virtual_runtime_library_dirs.size()
        );
        for (size_t index = 0; index < runtime_count; ++index) {
            replace_all(
                &value,
                environment->runtime_library_dirs[index],
                environment->virtual_runtime_library_dirs[index]
            );
        }
        replace_all(&value, environment->data_root, environment->target_data_root);
        replace_all(
            &value,
            environment->data_root_alias,
            environment->target_data_alias
        );
        return filter_host_runtime_lines(value, environment);
    }
    if (proc_suffix(path, "/mountinfo")) {
        const std::string proc_mountinfo = proc_path(
            process_id,
            "mountinfo"
        );
        std::string value = read_text_file(proc_mountinfo.c_str());
        replace_all(
            &value,
            environment->actual_base_apk_path,
            environment->virtual_base_apk_path
        );
        replace_all(
            &value,
            data_alias_path(environment->actual_base_apk_path),
            environment->virtual_base_apk_path
        );
        if (!environment->native_library_dir.empty() &&
            !environment->virtual_native_library_dir.empty()) {
            replace_all(
                &value,
                environment->native_library_dir,
                environment->virtual_native_library_dir
            );
        }
        const size_t runtime_count = std::min(
            environment->runtime_library_dirs.size(),
            environment->virtual_runtime_library_dirs.size()
        );
        for (size_t index = 0; index < runtime_count; ++index) {
            replace_all(
                &value,
                environment->runtime_library_dirs[index],
                environment->virtual_runtime_library_dirs[index]
            );
        }
        replace_all(&value, environment->data_root, environment->target_data_root);
        replace_all(
            &value,
            environment->data_root_alias,
            environment->target_data_alias
        );
        return filter_host_runtime_lines(value, environment);
    }
    return {};
}

int open_virtual_proc(const char* path, int flags) {
    return open_virtual_proc_for_pid(path, flags, getpid());
}

int open_virtual_proc_for_pid(
    const char* path,
    int flags,
    pid_t process_id
) {
    if ((flags & O_ACCMODE) == O_WRONLY || (flags & O_ACCMODE) == O_RDWR) {
        return -1;
    }
    const char* virtual_path = virtual_proc_path(path);
    if (virtual_path == nullptr) return -1;
    const Environment* environment = current_environment();
    if (environment == nullptr) return -1;
    record_hook_event(kHookEventProc);
    if (is_task_directory(path)) {
        const pid_t process_pid = task_directory_pid(path);
        std::string root;
        if (process_pid > 0) {
            root = "/proc/" + std::to_string(process_pid) + "/task";
        }
        if (root.empty() || access(root.c_str(), R_OK) != 0) {
            root = ensure_thread_view(environment);
        }
        if (root.empty()) return -1;
        const int fd = open(
            root.c_str(),
            O_RDONLY | O_DIRECTORY | O_CLOEXEC
        );
        if (fd < 0) return -1;
        record_fd_path(fd, path);
        return fd;
    }
    std::string content;
    const auto now = std::chrono::steady_clock::now();
    const bool use_cache = process_id == getpid();
    if (use_cache) {
        std::lock_guard<std::mutex> guard(g_virtual_proc_cache_lock);
        for (const auto& cached : g_virtual_proc_cache) {
            if (cached.environment == environment &&
                cached.path == path &&
                now - cached.sampled_at <= kVirtualProcCacheTtl) {
                content = cached.content;
                break;
            }
        }
    }
    if (content.empty()) {
        content = virtualized_proc_content(path, process_id);
    }
    if (content.empty()) return -1;
    char debug_maps[PROP_VALUE_MAX] = {};
    __system_property_get("debug.nestspace.debug_virtual_maps", debug_maps);
    if (strcmp(debug_maps, "1") == 0 &&
        strcmp(path, "/proc/self/maps") == 0) {
        const std::string dump_path =
            environment->data_root + "/.nestspace/virtual-maps.txt";
        const int dump_fd = static_cast<int>(syscall(
            SYS_openat,
            AT_FDCWD,
            dump_path.c_str(),
            O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC,
            0600
        ));
        if (dump_fd >= 0) {
            size_t written = 0;
            while (written < content.size()) {
                const long count = syscall(
                    SYS_write,
                    dump_fd,
                    content.data() + written,
                    content.size() - written
                );
                if (count <= 0) break;
                written += static_cast<size_t>(count);
            }
            syscall(SYS_close, dump_fd);
        }
        static std::atomic<int> map_dump_count{0};
        const int dump_index =
            map_dump_count.fetch_add(1, std::memory_order_relaxed);
        if (dump_index < 4) {
            constexpr size_t kChunk = 3000;
            for (size_t offset = 0; offset < content.size(); offset += kChunk) {
                const size_t length = std::min(
                    kChunk,
                    content.size() - offset
                );
                const std::string chunk = content.substr(offset, length);
                __android_log_print(
                    ANDROID_LOG_DEBUG,
                    "NestSpaceNative",
                    "virtual-maps-chunk index=%d %s",
                    dump_index,
                    chunk.c_str()
                );
            }
        }
    }
    if (use_cache) {
        std::lock_guard<std::mutex> guard(g_virtual_proc_cache_lock);
        auto* slot = &g_virtual_proc_cache.front();
        for (auto& cached : g_virtual_proc_cache) {
            if (cached.path == path && cached.environment == environment) {
                slot = &cached;
                break;
            }
            if (cached.path.empty()) slot = &cached;
        }
        slot->environment = environment;
        slot->path = path;
        slot->content = content;
        slot->sampled_at = now;
    }

    int fd = -1;
#ifdef SYS_memfd_create
    fd = static_cast<int>(syscall(
        SYS_memfd_create,
        "nestspace-proc",
        MFD_CLOEXEC
    ));
#endif
    if (fd < 0) {
        const std::string directory = environment->data_root + "/.nestspace/proc";
        mkdir(directory.c_str(), 0700);
        std::string pattern = directory + "/read-XXXXXX";
        std::vector<char> path_buffer(pattern.begin(), pattern.end());
        path_buffer.push_back('\0');
        fd = mkstemp(path_buffer.data());
        if (fd >= 0) {
            unlink(path_buffer.data());
            fcntl(fd, F_SETFD, FD_CLOEXEC);
        }
    }
    if (fd < 0) return -1;

    size_t written = 0;
    while (written < content.size()) {
        const ssize_t count = write(
            fd,
            content.data() + written,
            content.size() - written
        );
        if (count <= 0) {
            close(fd);
            return -1;
        }
        written += static_cast<size_t>(count);
    }
    if (lseek(fd, 0, SEEK_SET) < 0) {
        close(fd);
        return -1;
    }
    g_virtual_proc_reads.fetch_add(1, std::memory_order_relaxed);
    record_fd_path(fd, path);
    return fd;
}

}  // namespace nestspace
