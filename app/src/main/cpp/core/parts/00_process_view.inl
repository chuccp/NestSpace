#include "nestspace/native_environment_internal.h"

#include <android/log.h>
#include <fcntl.h>
#include <link.h>
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

std::string android_app_selinux_context(uint32_t uid) {
    // selinux_android_seapp maps the app id's low and high bytes into two
    // MLS categories, then appends the fixed untrusted-app categories.
    const uint32_t appid = uid >= 10000U ? uid - 10000U : uid;
    const uint32_t low = appid & 0xffU;
    const uint32_t high = 256U + ((appid >> 8U) & 0xffU);
    std::string value = "u:r:untrusted_app:s0:c" +
        std::to_string(low) + ",c" + std::to_string(high) +
        ",c512,c768";
    value.push_back('\0');
    return value;
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
    if (!name.empty()) {
        std::string lower = name;
        std::transform(
            lower.begin(),
            lower.end(),
            lower.begin(),
            [](unsigned char value) {
                return static_cast<char>(std::tolower(value));
            }
        );
        if (lower.find("nestspace") != std::string::npos ||
            lower.find("nsseccompnotify") != std::string::npos) {
            return "pool-" +
                std::to_string(
                    static_cast<unsigned int>(task_id) % 1000U
                ) + "-1";
        }
        return name;
    }
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

pid_t process_parent_pid(pid_t pid) {
    if (pid <= 0) return -1;
    const std::string path =
        "/proc/" + std::to_string(pid) + "/status";
    const std::string status = read_text_file(path.c_str());
    const size_t key = status.find("\nPPid:");
    if (key == std::string::npos) return -1;
    const char* value = status.c_str() + key + strlen("\nPPid:");
    while (*value == ' ' || *value == '\t') ++value;
    char* end = nullptr;
    const long parent = strtol(value, &end, 10);
    return end == value || parent <= 0
        ? -1
        : static_cast<pid_t>(parent);
}

bool is_target_descendant(pid_t pid) {
    if (pid == getpid()) return true;
    pid_t current = pid;
    for (int depth = 0; depth < 16 && current > 1; ++depth) {
        const pid_t parent = process_parent_pid(current);
        if (parent == getpid()) return true;
        if (parent <= 1) break;
        current = parent;
    }
    return false;
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
        if ((!is_target_descendant(static_cast<pid_t>(pid)) &&
                pid != getppid()) ||
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
    return (is_target_descendant(static_cast<pid_t>(pid)) ||
            pid == getppid()) &&
        end != path + 6 &&
        strcmp(end, "/task") == 0;
}

[[maybe_unused]] pid_t task_directory_pid(const char* path) {
    if (path == nullptr) return -1;
    if (strcmp(path, "/proc/self/task") == 0) return getpid();
    if (strcmp(path, "/proc/thread-self/task") == 0) return getpid();
    if (strncmp(path, "/proc/", 6) != 0) return -1;
    char* end = nullptr;
    const long pid = strtol(path + 6, &end, 10);
    if ((is_target_descendant(static_cast<pid_t>(pid)) ||
            pid == getppid()) &&
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
    if (strcmp(path, "/proc/self/mounts") == 0) return "/proc/self/mounts";
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
    if (strcmp(path, "/proc/thread-self/mounts") == 0) {
        return "/proc/self/mounts";
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
        (!is_target_descendant(static_cast<pid_t>(pid)) &&
            pid != getppid()) ||
        *end != '/') {
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
    if (strcmp(end, "/mounts") == 0) return "/proc/self/mounts";
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
    const std::array<std::string, 7> host_roots = {
        "/data/data/" + host,
        "/data/user/0/" + host,
        "/data/user_de/0/" + host,
        "/data/misc/profiles/cur/0/" + host,
        "/data/misc/profiles/ref/0/" + host,
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
    const std::string& thread_name = {},
    bool preserve_process_identity = false
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
            if (preserve_process_identity) {
                virtual_value += line;
                if (end == std::string::npos) break;
                virtual_value.push_back('\n');
                offset = end + 1;
                continue;
            }
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
            virtual_value += "FDSize:\t1024";
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
            if (preserve_process_identity) {
                virtual_value += line;
            } else {
                const size_t count = virtual_task_threads(
                    environment,
                    pid
                ).size();
                virtual_value += "Threads:\t" + std::to_string(count);
            }
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

bool host_runtime_path_matches(
    const std::string& value,
    const Environment* environment
) {
    return is_host_runtime_path(value, environment);
}

struct IdentitySyscallRange {
    uintptr_t start;
    uintptr_t end;
};

std::vector<IdentitySyscallRange> g_identity_system_ranges;

bool is_system_runtime_image(const char* path) {
    if (path == nullptr) return false;
    constexpr std::array<std::string_view, 5> roots = {
        "/apex/",
        "/system/",
        "/system_ext/",
        "/product/",
        "/vendor/",
    };
    for (const std::string_view root : roots) {
        if (root.size() <= strlen(path) &&
            strncmp(path, root.data(), root.size()) == 0) {
            return true;
        }
    }
    return false;
}

int collect_identity_syscall_ranges(
    dl_phdr_info* info,
    size_t,
    void*
) {
    if (info == nullptr || !is_system_runtime_image(info->dlpi_name)) return 0;
    for (ElfW(Half) index = 0; index < info->dlpi_phnum; ++index) {
        const ElfW(Phdr)& header = info->dlpi_phdr[index];
        if (header.p_type != PT_LOAD || (header.p_flags & PF_X) == 0) continue;
        const uintptr_t start =
            static_cast<uintptr_t>(info->dlpi_addr + header.p_vaddr);
        g_identity_system_ranges.push_back({
            start,
            start + static_cast<uintptr_t>(header.p_memsz),
        });
    }
    return 0;
}

void initialize_identity_syscall_ranges() {
    static std::once_flag once;
    std::call_once(once, [] {
        g_identity_system_ranges.reserve(64);
        dl_iterate_phdr(collect_identity_syscall_ranges, nullptr);
    });
}

bool identity_syscall_uses_target_identity(uintptr_t instruction_pointer) {
    if (current_environment() == nullptr || instruction_pointer == 0) {
        return false;
    }
    for (const IdentitySyscallRange& range : g_identity_system_ranges) {
        if (instruction_pointer >= range.start &&
            instruction_pointer < range.end) {
            return false;
        }
    }
    // Inline syscalls in target native code, JIT code and anonymous mappings
    // are not part of a system image and receive the virtual identity.
    return true;
}

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
