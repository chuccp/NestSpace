#include "native_environment_internal.h"

#include <android/log.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <linux/stat.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cctype>
#include <climits>
#include <cstdarg>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

namespace nestspace {
namespace {

struct LinuxDirent64 {
    uint64_t inode;
    int64_t offset;
    unsigned short record_length;
    unsigned char type;
    char name[];
};

std::atomic<const Environment*> g_environment{nullptr};
std::mutex g_environment_publish_lock;
std::vector<std::unique_ptr<Environment>> g_environment_history;
std::mutex g_virtual_proc_cache_lock;
std::mutex g_thread_view_lock;
std::unordered_map<pid_t, std::string> g_thread_view_names;

constexpr auto kVirtualProcCacheTtl = std::chrono::milliseconds(200);

struct VirtualProcCacheEntry {
    const Environment* environment = nullptr;
    std::string path;
    std::string content;
    std::chrono::steady_clock::time_point sampled_at{};
};

std::array<VirtualProcCacheEntry, 4> g_virtual_proc_cache{};

constexpr int kTrackedFdLimit = 65536;
struct FdPathSlot {
    std::atomic<const char*> path{nullptr};
};
std::array<FdPathSlot, kTrackedFdLimit> g_fd_paths{};

std::string read_text_file(const char* path);

std::string process_thread_name(const Environment* environment) {
    if (environment == nullptr) return {};
    std::string name = environment->process_name;
    const size_t separator = name.find_last_of(":/");
    if (separator != std::string::npos) name = name.substr(separator + 1);
    if (name.size() > 15) name.erase(0, name.size() - 15);
    return name;
}

std::vector<std::pair<pid_t, std::string>> virtual_task_threads(
    const Environment* environment,
    pid_t process_pid = getpid()
) {
    std::vector<std::pair<pid_t, std::string>> result;
    if (environment == nullptr) return result;
    const std::string process_name = process_thread_name(environment);
    const pid_t base = process_pid;
    const std::string task_root =
        "/proc/" + std::to_string(base) + "/task";
    const int directory_fd = static_cast<int>(syscall(
        SYS_openat,
        AT_FDCWD,
        task_root.c_str(),
        O_RDONLY | O_DIRECTORY | O_CLOEXEC,
        0
    ));
    std::vector<pid_t> actual_tasks;
    if (directory_fd >= 0) {
        std::array<char, 8192> buffer{};
        for (;;) {
            const long count = syscall(
                SYS_getdents64,
                directory_fd,
                buffer.data(),
                buffer.size()
            );
            if (count <= 0) break;
            size_t offset = 0;
            while (offset < static_cast<size_t>(count)) {
                auto* entry = reinterpret_cast<LinuxDirent64*>(
                    buffer.data() + offset
                );
                char* end = nullptr;
                const long task_id = strtol(entry->name, &end, 10);
                if (end != entry->name &&
                    *end == '\0' &&
                    task_id > 0) {
                    actual_tasks.push_back(static_cast<pid_t>(task_id));
                }
                if (entry->record_length == 0) break;
                offset += entry->record_length;
            }
        }
        close(directory_fd);
    }
    std::sort(actual_tasks.begin(), actual_tasks.end());
    if (!actual_tasks.empty()) {
        for (size_t index = 0; index < actual_tasks.size(); ++index) {
            const pid_t task_id = actual_tasks[index];
            std::string name;
            if (index == 0) {
                name = process_name;
            } else {
                const std::string path =
                    "/proc/" + std::to_string(base) +
                    "/task/" + std::to_string(task_id) + "/comm";
                name = read_text_file(path.c_str());
                while (!name.empty() &&
                       (name.back() == '\n' || name.back() == '\r')) {
                    name.pop_back();
                }
            }
            result.emplace_back(task_id, name);
        }
        return result;
    }
    const std::string binder_prefix =
        "binder:" + std::to_string(base) + "_";
    const std::string names[] = {
        process_name,
        "Signal Catcher",
        "perfetto_hprof_",
        "Jit thread pool",
        "HeapTaskDaemon",
        "ReferenceQueueD",
        "FinalizerDaemon",
        "FinalizerWatchd",
        binder_prefix + "1",
        binder_prefix + "2",
        binder_prefix + "3",
        "Profile Saver",
    };
    std::lock_guard<std::mutex> guard(g_thread_view_lock);
    g_thread_view_names.clear();
    for (size_t index = 0; index < std::size(names); ++index) {
        const pid_t task_id = base + static_cast<pid_t>(index);
        g_thread_view_names[task_id] = names[index];
        result.emplace_back(task_id, names[index]);
    }
    return result;
}

std::string ensure_thread_view(const Environment* environment) {
    if (environment == nullptr) return {};
    const std::string parent = environment->data_root +
        "/.nestspace/task-view";
    mkdir(parent.c_str(), 0700);
    const std::string root = parent + "/" + std::to_string(getpid());
    mkdir(root.c_str(), 0700);
    for (const auto& [task_id, name] : virtual_task_threads(environment)) {
        const std::string task_dir = root + "/" + std::to_string(task_id);
        mkdir(task_dir.c_str(), 0700);
        const std::string comm_path = task_dir + "/comm";
        const int fd = open(
            comm_path.c_str(),
            O_CREAT | O_WRONLY | O_TRUNC | O_CLOEXEC,
            0600
        );
        if (fd < 0) continue;
        const std::string value = name + "\n";
        write(fd, value.data(), value.size());
        close(fd);
    }
    return root;
}

std::string virtual_task_comm(pid_t task_id) {
    std::lock_guard<std::mutex> guard(g_thread_view_lock);
    const auto found = g_thread_view_names.find(task_id);
    return found == g_thread_view_names.end() ? std::string{} : found->second;
}

std::string target_task_comm(
    const Environment* environment,
    pid_t process_pid,
    pid_t task_id
) {
    if (task_id == process_pid) return process_thread_name(environment);
    const std::string path =
        "/proc/" + std::to_string(process_pid) +
        "/task/" + std::to_string(task_id) + "/comm";
    std::string name = read_text_file(path.c_str());
    while (!name.empty() &&
           (name.back() == '\n' || name.back() == '\r')) {
        name.pop_back();
    }
    if (!name.empty()) return name;
    return virtual_task_comm(task_id);
}

bool starts_with_prefix(const char* path, const std::string& prefix) {
    if (path == nullptr || prefix.empty()) return false;
    const size_t length = prefix.size();
    return strncmp(path, prefix.c_str(), length) == 0 &&
        (path[length] == '\0' || path[length] == '/');
}

std::string path_basename(const char* path) {
    if (path == nullptr) return {};
    const char* separator = strrchr(path, '/');
    return separator == nullptr ? std::string(path) : std::string(separator + 1);
}

const std::string* runtime_library_for_basename(
    const Environment* environment,
    const char* path
) {
    if (environment == nullptr || path == nullptr) return nullptr;
    const std::string basename = path_basename(path);
    if (basename.size() <= 3 ||
        basename.compare(basename.size() - 3, 3, ".so") != 0) {
        return nullptr;
    }
    for (const auto& candidate : environment->runtime_library_paths) {
        if (path_basename(candidate.c_str()) == basename) return &candidate;
    }
    return nullptr;
}

std::string data_alias_path(const std::string& path) {
    constexpr std::string_view kUserPrefix = "/data/user/0/";
    if (path.compare(0, kUserPrefix.size(), kUserPrefix) != 0) return {};
    return "/data/data/" + path.substr(kUserPrefix.size());
}

bool virtual_task_suffix(
    const char* path,
    pid_t* process_pid,
    pid_t* tid,
    const char** suffix
) {
    if (path == nullptr ||
        process_pid == nullptr ||
        tid == nullptr ||
        suffix == nullptr) {
        return false;
    }
    const char* cursor = nullptr;
    if (strncmp(path, "/proc/self/task/", 16) == 0) {
        *process_pid = getpid();
        cursor = path + 16;
    } else if (strncmp(path, "/proc/thread-self/task/", 23) == 0) {
        *process_pid = getpid();
        cursor = path + 23;
    } else if (strncmp(path, "/proc/", 6) == 0) {
        char* pid_end = nullptr;
        const long pid = strtol(path + 6, &pid_end, 10);
        if ((pid != getpid() && pid != getppid()) ||
            pid_end == path + 6 ||
            strncmp(pid_end, "/task/", 6) != 0) {
            return false;
        }
        *process_pid = static_cast<pid_t>(pid);
        cursor = pid_end + 6;
    } else {
        return false;
    }
    char* task_end = nullptr;
    const long parsed_tid = strtol(cursor, &task_end, 10);
    if (task_end == cursor || parsed_tid <= 0 || *task_end != '/') {
        return false;
    }
    *tid = static_cast<pid_t>(parsed_tid);
    *suffix = task_end;
    return true;
}

bool is_task_directory(const char* path) {
    if (path == nullptr) return false;
    if (strcmp(path, "/proc/self/task") == 0) return true;
    if (strcmp(path, "/proc/thread-self/task") == 0) return true;
    if (strncmp(path, "/proc/", 6) != 0) return false;
    char* end = nullptr;
    const long pid = strtol(path + 6, &end, 10);
    return (pid == getpid() || pid == getppid()) &&
        end != path + 6 &&
        strcmp(end, "/task") == 0;
}

pid_t task_directory_pid(const char* path) {
    if (path == nullptr) return -1;
    if (strcmp(path, "/proc/self/task") == 0) return getpid();
    if (strcmp(path, "/proc/thread-self/task") == 0) return getpid();
    if (strncmp(path, "/proc/", 6) != 0) return -1;
    char* end = nullptr;
    const long pid = strtol(path + 6, &end, 10);
    if ((pid == getpid() || pid == getppid()) &&
        end != path + 6 &&
        strcmp(end, "/task") == 0) {
        return static_cast<pid_t>(pid);
    }
    return -1;
}

const char* virtual_proc_path(const char* path) {
    if (path == nullptr) return nullptr;
    if (strcmp(path, "/proc/self/status") == 0) return "/proc/self/status";
    if (strcmp(path, "/proc/self/cmdline") == 0) return "/proc/self/cmdline";
    if (strcmp(path, "/proc/self/comm") == 0) return "/proc/self/comm";
    if (strcmp(path, "/proc/self/maps") == 0) return "/proc/self/maps";
    if (strcmp(path, "/proc/self/smaps") == 0) return "/proc/self/smaps";
    if (strcmp(path, "/proc/self/stat") == 0) return "/proc/self/stat";
    if (strcmp(path, "/proc/self/statm") == 0) return "/proc/self/statm";
    if (strcmp(path, "/proc/self/sched") == 0) return "/proc/self/sched";
    if (strcmp(path, "/proc/self/mountinfo") == 0) return "/proc/self/mountinfo";
    if (strcmp(path, "/proc/self/cgroup") == 0) return "/proc/self/cgroup";
    if (strcmp(path, "/proc/self/attr/current") == 0) {
        return "/proc/self/attr/current";
    }
    if (strcmp(path, "/proc/self/attr/prev") == 0) {
        return "/proc/self/attr/prev";
    }
    if (strcmp(path, "/proc/thread-self/status") == 0) {
        return "/proc/self/status";
    }
    if (strcmp(path, "/proc/thread-self/cmdline") == 0) {
        return "/proc/self/cmdline";
    }
    if (strcmp(path, "/proc/thread-self/comm") == 0) {
        return "/proc/self/comm";
    }
    if (strcmp(path, "/proc/thread-self/maps") == 0) {
        return "/proc/self/maps";
    }
    if (strcmp(path, "/proc/thread-self/smaps") == 0) {
        return "/proc/self/smaps";
    }
    if (strcmp(path, "/proc/thread-self/stat") == 0) {
        return "/proc/self/stat";
    }
    if (strcmp(path, "/proc/thread-self/statm") == 0) {
        return "/proc/self/statm";
    }
    if (strcmp(path, "/proc/thread-self/sched") == 0) {
        return "/proc/self/sched";
    }
    if (strcmp(path, "/proc/thread-self/mountinfo") == 0) {
        return "/proc/self/mountinfo";
    }
    if (strcmp(path, "/proc/thread-self/cgroup") == 0) {
        return "/proc/self/cgroup";
    }
    if (strcmp(path, "/proc/thread-self/attr/current") == 0) {
        return "/proc/self/attr/current";
    }
    if (strcmp(path, "/proc/thread-self/attr/prev") == 0) {
        return "/proc/self/attr/prev";
    }
    if (is_task_directory(path)) return "/proc/self/task";
    pid_t process_pid = 0;
    pid_t tid = 0;
    const char* task_suffix = nullptr;
    if (virtual_task_suffix(
            path,
            &process_pid,
            &tid,
            &task_suffix
        )) {
        (void)process_pid;
        if (strcmp(task_suffix, "/comm") == 0 ||
            strcmp(task_suffix, "/status") == 0 ||
            strcmp(task_suffix, "/stat") == 0 ||
            strcmp(task_suffix, "/statm") == 0 ||
            strcmp(task_suffix, "/sched") == 0 ||
            strcmp(task_suffix, "/schedstat") == 0 ||
            strcmp(task_suffix, "/cgroup") == 0 ||
            strcmp(task_suffix, "/attr/current") == 0 ||
            strcmp(task_suffix, "/attr/prev") == 0) {
            return "/proc/self/task";
        }
    }
    if (strncmp(path, "/proc/", 6) != 0) return nullptr;
    char* end = nullptr;
    const long pid = strtol(path + 6, &end, 10);
    if (end == path + 6 || pid <= 0 ||
        (pid != getpid() && pid != getppid()) || *end != '/') {
        return nullptr;
    }
    if (strcmp(end, "/status") == 0) return "/proc/self/status";
    if (strcmp(end, "/cmdline") == 0) return "/proc/self/cmdline";
    if (strcmp(end, "/comm") == 0) return "/proc/self/comm";
    if (strcmp(end, "/maps") == 0) return "/proc/self/maps";
    if (strcmp(end, "/smaps") == 0) return "/proc/self/smaps";
    if (strcmp(end, "/stat") == 0) return "/proc/self/stat";
    if (strcmp(end, "/statm") == 0) return "/proc/self/statm";
    if (strcmp(end, "/sched") == 0) return "/proc/self/sched";
    if (strcmp(end, "/mountinfo") == 0) return "/proc/self/mountinfo";
    if (strcmp(end, "/cgroup") == 0) return "/proc/self/cgroup";
    if (strcmp(end, "/attr/current") == 0) return "/proc/self/attr/current";
    if (strcmp(end, "/attr/prev") == 0) return "/proc/self/attr/prev";
    return nullptr;
}

bool proc_readable(const char* path) {
    return virtual_proc_path(path) != nullptr;
}

std::string proc_path(pid_t pid, const char* name) {
    return "/proc/" + std::to_string(pid) + "/" + name;
}

bool proc_suffix(const char* path, const char* suffix) {
    if (path == nullptr) return false;
    const size_t length = strlen(path);
    const size_t suffix_length = strlen(suffix);
    return length >= suffix_length &&
        strcmp(path + length - suffix_length, suffix) == 0;
}

void record_fd_path(int fd, const char* path) {
    if (fd < 0 || fd >= kTrackedFdLimit) return;
    const char* tracked = virtual_proc_path(path);
    if (tracked == nullptr) return;
    g_fd_paths[fd].path.store(tracked, std::memory_order_release);
}

std::string read_text_file(const char* path) {
    // This is the container's source reader for the real /proc view. Using
    // fopen/open here re-enters the target proc hooks and makes thread names
    // read back as the virtual process name. Call the saved libc open function
    // directly so /proc/<pid>/task/<tid>/comm keeps its real thread name.
    const long opened = g_real.syscall
        ? g_real.syscall(
            SYS_openat,
            AT_FDCWD,
            path,
            O_RDONLY | O_CLOEXEC,
            0
        )
        : -1;
    if (opened < 0) return {};
    const int fd = static_cast<int>(opened);
    std::string result;
    std::array<char, 8192> buffer{};
    while (true) {
        const long count = g_real.syscall
            ? g_real.syscall(SYS_read, fd, buffer.data(), buffer.size())
            : -1;
        if (count > 0) result.append(buffer.data(), static_cast<size_t>(count));
        if (count <= 0) break;
    }
    close(fd);
    return result;
}

void replace_all(
    std::string* value,
    const std::string& from,
    const std::string& to
) {
    if (from.empty()) return;
    size_t offset = 0;
    while ((offset = value->find(from, offset)) != std::string::npos) {
        value->replace(offset, from.size(), to);
        offset += to.size();
    }
}

bool is_host_runtime_path(
    const std::string& line,
    const Environment* environment
) {
    if (environment == nullptr || environment->host_package_name.empty()) {
        return false;
    }
    const std::string& host = environment->host_package_name;
    if (line.find("/data/app/") != std::string::npos &&
        line.find(host) != std::string::npos) {
        return true;
    }
    const std::array<std::string, 5> host_roots = {
        "/data/data/" + host,
        "/data/user/0/" + host,
        "/data/user_de/0/" + host,
        "/storage/emulated/0/Android/data/" + host,
        "/sdcard/Android/data/" + host,
    };
    for (const auto& root : host_roots) {
        if (line.find(root) != std::string::npos) return true;
    }
    return false;
}

std::string filter_host_runtime_lines(
    const std::string& value,
    const Environment* environment
) {
    std::string filtered;
    filtered.reserve(value.size());
    size_t offset = 0;
    while (offset < value.size()) {
        const size_t end = value.find('\n', offset);
        const size_t length = end == std::string::npos
            ? value.size() - offset
            : end - offset;
        const std::string line = value.substr(offset, length);
        if (!is_host_runtime_path(line, environment)) {
            filtered.append(line);
            if (end != std::string::npos) filtered.push_back('\n');
        }
        if (end == std::string::npos) break;
        offset = end + 1;
    }
    return filtered;
}

std::string virtualized_status(
    const Environment* environment,
    pid_t pid,
    pid_t reported_pid = -1,
    const std::string& thread_name = {}
) {
    const std::string path = proc_path(pid, "status");
    const std::string real = read_text_file(path.c_str());
    if (real.empty() || environment == nullptr) return {};
    std::string virtual_value;
    virtual_value.reserve(real.size() + 64);
    size_t offset = 0;
    while (offset < real.size()) {
        bool omit_line_break = false;
        const size_t end = real.find('\n', offset);
        const size_t length = end == std::string::npos ? real.size() - offset
                                                       : end - offset;
        const std::string line = real.substr(offset, length);
        if (line.rfind("Name:", 0) == 0) {
            std::string name = thread_name.empty()
                ? environment->process_name
                : thread_name;
            const size_t separator = name.find_last_of(":/");
            if (separator != std::string::npos) name = name.substr(separator + 1);
            if (name.size() > 15) name.erase(0, name.size() - 15);
            virtual_value += "Name:\t" + name;
        } else if (line.rfind("Uid:", 0) == 0) {
            const std::string uid = std::to_string(environment->observed_uid);
            virtual_value += "Uid:\t" + uid + "\t" + uid + "\t" + uid + "\t" + uid;
        } else if (line.rfind("Gid:", 0) == 0) {
            const std::string gid = std::to_string(environment->observed_uid);
            virtual_value += "Gid:\t" + gid + "\t" + gid + "\t" + gid + "\t" + gid;
        } else if (line.rfind("Groups:", 0) == 0) {
            virtual_value += "Groups:\t";
            if (environment->supplementary_groups.empty()) {
                virtual_value += "9997 " +
                    std::to_string(environment->observed_uid + 10000) + " " +
                    std::to_string(environment->observed_uid + 40000) + " ";
            } else {
                for (size_t index = 0;
                     index < environment->supplementary_groups.size();
                     ++index) {
                    if (index != 0) virtual_value.push_back(' ');
                    virtual_value += std::to_string(
                        environment->supplementary_groups[index]
                    );
                }
                virtual_value.push_back(' ');
            }
        } else if (
            line.rfind("CapInh:", 0) == 0 ||
            line.rfind("CapPrm:", 0) == 0 ||
            line.rfind("CapEff:", 0) == 0 ||
            line.rfind("CapBnd:", 0) == 0 ||
            line.rfind("CapAmb:", 0) == 0
        ) {
            virtual_value += line;
        } else if (line.rfind("FDSize:", 0) == 0) {
            virtual_value += "FDSize:\t256";
        } else if (reported_pid > 0 &&
                   (line.rfind("Pid:", 0) == 0 ||
                    line.rfind("NSpid:", 0) == 0)) {
            virtual_value += line.substr(0, line.find(':') + 1) +
                "\t" + std::to_string(reported_pid);
        } else if (line.rfind("TracerPid:", 0) == 0) {
            virtual_value += "TracerPid:\t0";
        } else if (line.rfind("NoNewPrivs:", 0) == 0) {
            virtual_value += "NoNewPrivs:\t0";
        } else if (line.rfind("Seccomp_filters:", 0) == 0) {
            virtual_value += "Seccomp_filters:\t1";
        } else if (line.rfind("Threads:", 0) == 0) {
            const size_t count = virtual_task_threads(
                environment,
                pid
            ).size();
            virtual_value += "Threads:\t" + std::to_string(count);
        } else {
            virtual_value += line;
        }
        if (end == std::string::npos) break;
        if (!omit_line_break) virtual_value.push_back('\n');
        offset = end + 1;
    }
    return virtual_value;
}

ssize_t copy_virtual_path(
    char* buffer,
    size_t size,
    const std::string& prefix,
    const char* suffix
) {
    if (size == 0) return -1;
    const size_t suffix_length = strlen(suffix);
    const size_t full_length = prefix.size() + suffix_length;
    const size_t count = std::min(size - 1, full_length);
    const size_t prefix_count = std::min(count, prefix.size());
    memcpy(buffer, prefix.data(), prefix_count);
    if (count > prefix_count) {
        memcpy(buffer + prefix_count, suffix, count - prefix_count);
    }
    buffer[count] = '\0';
    return static_cast<ssize_t>(count);
}

}  // namespace

std::atomic<int> g_virtual_proc_reads{0};
std::atomic<int> g_property_reads{0};
std::atomic<int> g_dlopen_calls{0};

const VirtualPropertyInfo* PropertySnapshot::find(std::string_view name) const {
    const auto found = std::lower_bound(
        by_name.begin(),
        by_name.end(),
        name,
        [](const VirtualPropertyInfo* info, std::string_view value) {
            return std::string_view(info->name) < value;
        }
    );
    if (found == by_name.end() ||
        std::string_view((*found)->name) != name) {
        return nullptr;
    }
    return *found;
}

const VirtualPropertyInfo* PropertySnapshot::find(const void* pointer) const {
    const auto found = by_pointer.find(pointer);
    return found == by_pointer.end() ? nullptr : found->second;
}

const Environment* current_environment() {
    return g_environment.load(std::memory_order_acquire);
}

void publish_environment(std::unique_ptr<Environment> environment) {
    Environment* pointer = environment.get();
    std::lock_guard<std::mutex> guard(g_environment_publish_lock);
    g_environment_history.push_back(std::move(environment));
    g_environment.store(pointer, std::memory_order_release);
}

void clear_environment() {
    g_environment.store(nullptr, std::memory_order_release);
}

std::shared_ptr<const PropertySnapshot> build_property_snapshot(
    const std::unordered_map<std::string, std::string>& properties
) {
    auto snapshot = std::make_shared<PropertySnapshot>();
    snapshot->owned.reserve(properties.size());
    snapshot->by_name.reserve(properties.size());
    snapshot->by_pointer.reserve(properties.size());
    for (const auto& [name, value] : properties) {
        auto info = std::make_unique<VirtualPropertyInfo>();
        info->name = name;
        info->value = value;
        const auto* pointer = info.get();
        snapshot->by_name.push_back(pointer);
        snapshot->by_pointer.emplace(pointer, pointer);
        snapshot->owned.push_back(std::move(info));
    }
    std::sort(
        snapshot->by_name.begin(),
        snapshot->by_name.end(),
        [](const VirtualPropertyInfo* left, const VirtualPropertyInfo* right) {
            return left->name < right->name;
        }
    );
    return snapshot;
}

bool can_open_for_read(const char* path) {
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    close(fd);
    return true;
}

bool can_syscall_open_for_read(const char* path) {
    const long fd = syscall(
        SYS_openat,
        AT_FDCWD,
        path,
        O_RDONLY | O_CLOEXEC,
        0
    );
    if (fd < 0) return false;
    close(static_cast<int>(fd));
    return true;
}

bool can_syscall_stat(const char* path) {
    struct statx value = {};
    return syscall(
        SYS_statx,
        AT_FDCWD,
        path,
        AT_STATX_SYNC_AS_STAT,
        STATX_TYPE,
        &value
    ) == 0;
}

jint read_status_uid() {
    FILE* file = fopen("/proc/self/status", "re");
    if (file == nullptr) return -1;
    jint uid = -1;
    char line[256];
    while (fgets(line, sizeof(line), file) != nullptr) {
        if (strncmp(line, "Uid:", 4) == 0) {
            long parsed = -1;
            if (sscanf(line + 4, "%ld", &parsed) == 1) {
                uid = static_cast<jint>(parsed);
            }
            break;
        }
    }
    fclose(file);
    return uid;
}

bool proc_fd_number(const char* path, int* fd) {
    if (path == nullptr || fd == nullptr) return false;
    constexpr char kSelfPrefix[] = "/proc/self/fd/";
    constexpr char kThreadPrefix[] = "/proc/thread-self/fd/";
    const char* number = nullptr;
    if (strncmp(path, kSelfPrefix, sizeof(kSelfPrefix) - 1) == 0) {
        number = path + sizeof(kSelfPrefix) - 1;
    } else if (strncmp(path, kThreadPrefix, sizeof(kThreadPrefix) - 1) == 0) {
        number = path + sizeof(kThreadPrefix) - 1;
    }
    if (number == nullptr || *number == '\0') return false;
    char* end = nullptr;
    const long parsed = strtol(number, &end, 10);
    if (end == number || *end != '\0' || parsed < 0 || parsed > INT_MAX) {
        return false;
    }
    *fd = static_cast<int>(parsed);
    return true;
}

const char* fd_virtual_path(int fd) {
    if (fd < 0 || fd >= kTrackedFdLimit) return nullptr;
    return g_fd_paths[fd].path.load(std::memory_order_acquire);
}

void copy_fd_path(int old_fd, int new_fd) {
    if (new_fd < 0 || new_fd >= kTrackedFdLimit) return;
    const char* path = old_fd >= 0 && old_fd < kTrackedFdLimit
        ? g_fd_paths[old_fd].path.load(std::memory_order_acquire)
        : nullptr;
    g_fd_paths[new_fd].path.store(path, std::memory_order_release);
}

void erase_fd_path(int fd) {
    if (fd < 0 || fd >= kTrackedFdLimit) return;
    g_fd_paths[fd].path.store(nullptr, std::memory_order_release);
}

void clear_fd_paths() {
    for (auto& slot : g_fd_paths) {
        slot.path.store(nullptr, std::memory_order_release);
    }
}

bool rewrite_target_path(const char* path, std::string* rewritten) {
    if (path == nullptr || path[0] != '/') return false;
    const Environment* environment = current_environment();
    if (environment == nullptr) return false;

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
    if (!environment->native_library_dir.empty() &&
        environment->native_library_dir !=
            environment->virtual_native_library_dir &&
        starts_with_prefix(actual, environment->native_library_dir)) {
        return true;
    }
    return std::any_of(
        environment->runtime_library_dirs.begin(),
        environment->runtime_library_dirs.end(),
        [actual](const std::string& directory) {
            return starts_with_prefix(actual, directory);
        }
    );
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
        return environment->process_name + '\0';
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
