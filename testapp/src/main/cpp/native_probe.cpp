#include <jni.h>
#include <fcntl.h>
#include <linux/capability.h>
#include <sched.h>
#include <sys/ptrace.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <iterator>
#include <string>
#include <utility>
#include <vector>

namespace {

struct ChildProcProbe {
    int parent_pid = -1;
    long trace_result = -1;
    int trace_errno = 0;
    uid_t uid = 0;
    gid_t gid = 0;
    gid_t groups[16] = {};
    int group_count = 0;
    int task_count = 0;
    char parent_comm[64] = {};
    char first_tasks[512] = {};
    char task_details[2048] = {};
    char parent_status[1024] = {};
    char parent_cmdline[256] = {};
    char parent_attr[256] = {};
};

struct LinuxDirent64 {
    uint64_t inode;
    int64_t offset;
    unsigned short record_length;
    unsigned char type;
    char name[];
};

#if defined(SYS_newfstatat)
constexpr long kSysFstatat = SYS_newfstatat;
#elif defined(SYS_fstatat64)
constexpr long kSysFstatat = SYS_fstatat64;
#else
constexpr long kSysFstatat = -1;
#endif

std::string read_file(const char* path, size_t limit = 64 * 1024) {
    FILE* file = fopen(path, "re");
    if (file == nullptr) return {};
    std::string result;
    std::array<char, 4096> buffer{};
    while (result.size() < limit) {
        const size_t count = fread(buffer.data(), 1, buffer.size(), file);
        if (count == 0) break;
        result.append(buffer.data(), count);
    }
    fclose(file);
    return result;
}

std::string read_fd(int fd, size_t limit = 64 * 1024) {
    if (fd < 0) return {};
    std::string result;
    std::array<char, 4096> buffer{};
    while (result.size() < limit) {
        const ssize_t count = read(fd, buffer.data(), buffer.size());
        if (count <= 0) break;
        result.append(buffer.data(), static_cast<size_t>(count));
    }
    close(fd);
    return result;
}

jstring to_string(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string property(const char* name) {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get(name, value);
    return value;
}

std::string exception_summary(JNIEnv* env) {
    if (!env->ExceptionCheck()) return {};
    jthrowable exception = env->ExceptionOccurred();
    env->ExceptionClear();
    if (exception == nullptr) return "unknown";
    jclass throwable_class = env->FindClass("java/lang/Throwable");
    jmethodID class_method = throwable_class == nullptr
        ? nullptr
        : env->GetMethodID(throwable_class, "getClass", "()Ljava/lang/Class;");
    jclass class_class = env->FindClass("java/lang/Class");
    jmethodID name_method = class_class == nullptr
        ? nullptr
        : env->GetMethodID(class_class, "getName", "()Ljava/lang/String;");
    jobject class_object = class_method == nullptr
        ? nullptr
        : env->CallObjectMethod(exception, class_method);
    jstring class_name = class_object == nullptr || name_method == nullptr
        ? nullptr
        : static_cast<jstring>(env->CallObjectMethod(class_object, name_method));
    std::string result = "exception";
    if (class_name != nullptr) {
        const char* chars = env->GetStringUTFChars(class_name, nullptr);
        if (chars != nullptr) {
            result = chars;
            env->ReleaseStringUTFChars(class_name, chars);
        }
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        result = "exception";
    }
    if (class_name != nullptr) env->DeleteLocalRef(class_name);
    if (class_object != nullptr) env->DeleteLocalRef(class_object);
    if (class_class != nullptr) env->DeleteLocalRef(class_class);
    if (throwable_class != nullptr) env->DeleteLocalRef(throwable_class);
    env->DeleteLocalRef(exception);
    return result;
}

std::string android_thread_string(JNIEnv* env, const char* method_name) {
    jclass thread_class = env->FindClass("android/app/ActivityThread");
    if (thread_class == nullptr) {
        return "find-class:" + exception_summary(env);
    }
    jmethodID method = env->GetStaticMethodID(
        thread_class,
        method_name,
        "()Ljava/lang/String;"
    );
    if (method == nullptr) {
        std::string error = exception_summary(env);
        env->DeleteLocalRef(thread_class);
        return error.empty() ? "missing-method" : error;
    }
    auto value = static_cast<jstring>(env->CallStaticObjectMethod(thread_class, method));
    std::string result = "exception:" + exception_summary(env);
    if (value != nullptr) {
        const char* chars = env->GetStringUTFChars(value, nullptr);
        if (chars != nullptr) {
            result = chars;
            env->ReleaseStringUTFChars(value, chars);
        }
        env->DeleteLocalRef(value);
    } else if (!env->ExceptionCheck()) {
        result = "null";
    }
    env->DeleteLocalRef(thread_class);
    return result;
}

std::string android_application_package(JNIEnv* env, const char* method_name) {
    jclass thread_class = env->FindClass("android/app/ActivityThread");
    jclass application_class = env->FindClass("android/app/Application");
    if (thread_class == nullptr || application_class == nullptr) {
        env->ExceptionClear();
        if (thread_class != nullptr) env->DeleteLocalRef(thread_class);
        if (application_class != nullptr) env->DeleteLocalRef(application_class);
        return "find-class-failed";
    }
    jmethodID current_application = env->GetStaticMethodID(
        thread_class,
        "currentApplication",
        "()Landroid/app/Application;"
    );
    jmethodID package_method = env->GetMethodID(
        application_class,
        method_name,
        "()Ljava/lang/String;"
    );
    if (current_application == nullptr || package_method == nullptr) {
        std::string error = exception_summary(env);
        env->DeleteLocalRef(application_class);
        env->DeleteLocalRef(thread_class);
        return error.empty() ? "missing-method" : error;
    }
    jobject application = env->CallStaticObjectMethod(
        thread_class,
        current_application
    );
    if (application == nullptr) {
        std::string result = exception_summary(env);
        env->DeleteLocalRef(application_class);
        env->DeleteLocalRef(thread_class);
        return result.empty() ? "null" : result;
    }
    auto value = static_cast<jstring>(env->CallObjectMethod(
        application,
        package_method
    ));
    std::string result = "exception:" + exception_summary(env);
    if (value != nullptr) {
        const char* chars = env->GetStringUTFChars(value, nullptr);
        if (chars != nullptr) {
            result = chars;
            env->ReleaseStringUTFChars(value, chars);
        }
        env->DeleteLocalRef(value);
    } else if (!env->ExceptionCheck()) {
        result = "null";
    }
    env->DeleteLocalRef(application);
    env->DeleteLocalRef(application_class);
    env->DeleteLocalRef(thread_class);
    return result;
}

std::string fd_link(const char* path) {
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return "open-failed";
    std::string proc_path = "/proc/self/fd/" + std::to_string(fd);
    char value[4096] = {};
    const ssize_t length = readlink(proc_path.c_str(), value, sizeof(value) - 1);
    close(fd);
    if (length <= 0) return "readlink-failed";
    value[length] = '\0';
    return value;
}

bool starts_with(const std::string& value, const char* prefix) {
    return value.rfind(prefix, 0) == 0;
}

std::string stat_summary(const char* label, const struct stat& value) {
    return std::string(label) +
        " uid=" + std::to_string(value.st_uid) +
        " gid=" + std::to_string(value.st_gid) +
        " mode=" + std::to_string(value.st_mode) +
        " dev=" + std::to_string(value.st_dev) +
        " ino=" + std::to_string(value.st_ino);
}

std::string xattr_summary(const char* label, const char* path) {
    const ssize_t size = getxattr(path, "security.selinux", nullptr, 0);
    if (size < 0) {
        return std::string(label) + " error=" + std::to_string(errno);
    }
    std::vector<char> value(static_cast<size_t>(size) + 1, '\0');
    const ssize_t result = getxattr(
        path,
        "security.selinux",
        value.data(),
        value.size()
    );
    if (result < 0) {
        return std::string(label) + " error=" + std::to_string(errno);
    }
    return std::string(label) + "=" + std::string(value.data());
}

std::string syscall_xattr_summary(const char* label, const char* path) {
    const long size = syscall(
        SYS_getxattr,
        path,
        "security.selinux",
        nullptr,
        0
    );
    if (size < 0) {
        return std::string(label) + " error=" + std::to_string(errno);
    }
    std::vector<char> value(static_cast<size_t>(size) + 1, '\0');
    const long result = syscall(
        SYS_getxattr,
        path,
        "security.selinux",
        value.data(),
        value.size()
    );
    if (result < 0) {
        return std::string(label) + " error=" + std::to_string(errno);
    }
    return std::string(label) + "=" + std::string(value.data());
}

std::string capability_summary() {
    struct __user_cap_header_struct header {};
    header.version = _LINUX_CAPABILITY_VERSION_3;
    header.pid = 0;
    std::array<struct __user_cap_data_struct, 2> data{};
    if (syscall(SYS_capget, &header, data.data()) != 0) {
        return "capget error=" + std::to_string(errno);
    }
    return "capget eff=" + std::to_string(data[0].effective) +
        ":" + std::to_string(data[1].effective) +
        " prm=" + std::to_string(data[0].permitted) +
        ":" + std::to_string(data[1].permitted) +
        " inh=" + std::to_string(data[0].inheritable) +
        ":" + std::to_string(data[1].inheritable);
}

std::string compact_stat(const struct stat& value) {
    return "dev=" + std::to_string(value.st_dev) +
        " ino=" + std::to_string(value.st_ino) +
        " mode=" + std::to_string(value.st_mode) +
        " uid=" + std::to_string(value.st_uid) +
        " gid=" + std::to_string(value.st_gid) +
        " nlink=" + std::to_string(value.st_nlink) +
        " size=" + std::to_string(value.st_size);
}

std::string readlink_summary(const char* path) {
    char value[4096] = {};
    const ssize_t length = readlink(path, value, sizeof(value) - 1);
    if (length < 0) {
        return "error=" + std::to_string(errno);
    }
    return value;
}

std::string realpath_summary(const char* path) {
    char value[4096] = {};
    return realpath(path, value) == nullptr
        ? "error=" + std::to_string(errno)
        : std::string(value);
}

std::string statfs_summary(const char* path) {
    struct statfs value {};
    if (statfs(path, &value) != 0) {
        return "error=" + std::to_string(errno);
    }
    return "type=" + std::to_string(value.f_type) +
        " bsize=" + std::to_string(value.f_bsize) +
        " blocks=" + std::to_string(value.f_blocks) +
        " bavail=" + std::to_string(value.f_bavail);
}

std::string statvfs_summary(const char* path) {
    struct statvfs value {};
    if (statvfs(path, &value) != 0) {
        return "error=" + std::to_string(errno);
    }
    return "bsize=" + std::to_string(value.f_bsize) +
        " frsize=" + std::to_string(value.f_frsize) +
        " blocks=" + std::to_string(value.f_blocks) +
        " bavail=" + std::to_string(value.f_bavail) +
        " fsid=" + std::to_string(value.f_fsid);
}

std::string path_summary(
    const char* label,
    const char* path,
    bool directory
) {
    if (path == nullptr || path[0] == '\0') return std::string(label) + "=<empty>";
    struct stat value {};
    const int result = lstat(path, &value);
    std::string output = std::string(label) +
        " lstat=" + (result == 0 ? compact_stat(value) : "error=" + std::to_string(errno)) +
        " accessR=" + std::to_string(access(path, R_OK)) +
        " realpath=" + realpath_summary(path);
    if (directory) {
        output += " statfs=" + statfs_summary(path) +
            " statvfs=" + statvfs_summary(path);
    }
    return output;
}

void read_into_buffer(
    const char* path,
    char* buffer,
    size_t size
) {
    if (buffer == nullptr || size == 0) return;
    buffer[0] = '\0';
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    const ssize_t count = read(fd, buffer, size - 1);
    if (count > 0) buffer[count] = '\0';
    close(fd);
}

void run_child_proc_probe(std::string* report) {
    if (report == nullptr) return;
    int pipe_fds[2] = {-1, -1};
    if (pipe2(pipe_fds, O_CLOEXEC) != 0) {
        *report += "\nchild-proc=pipe-error=" + std::to_string(errno);
        return;
    }
    const pid_t parent_pid = getpid();
    const pid_t child_pid = fork();
    if (child_pid == 0) {
        close(pipe_fds[0]);
        ChildProcProbe probe;
        probe.parent_pid = static_cast<int>(parent_pid);
        probe.uid = getuid();
        probe.gid = getgid();
        probe.group_count = getgroups(
            static_cast<int>(std::size(probe.groups)),
            probe.groups
        );
        errno = 0;
        probe.trace_result = ptrace(PTRACE_TRACEME, 0, nullptr, nullptr);
        probe.trace_errno = probe.trace_result < 0 ? errno : 0;

        const std::string parent = "/proc/" +
            std::to_string(parent_pid);
        read_into_buffer(
            (parent + "/comm").c_str(),
            probe.parent_comm,
            sizeof(probe.parent_comm)
        );
        read_into_buffer(
            (parent + "/status").c_str(),
            probe.parent_status,
            sizeof(probe.parent_status)
        );
        read_into_buffer(
            (parent + "/cmdline").c_str(),
            probe.parent_cmdline,
            sizeof(probe.parent_cmdline)
        );
        read_into_buffer(
            (parent + "/attr/current").c_str(),
            probe.parent_attr,
            sizeof(probe.parent_attr)
        );

        const std::string task_root = parent + "/task";
        const int directory_fd = open(
            task_root.c_str(),
            O_RDONLY | O_DIRECTORY | O_CLOEXEC
        );
        if (directory_fd >= 0) {
            std::array<char, 4096> entries{};
            size_t formatted = 0;
            for (;;) {
                const long count = syscall(
                    SYS_getdents64,
                    directory_fd,
                    entries.data(),
                    entries.size()
                );
                if (count <= 0) break;
                size_t offset = 0;
                while (offset < static_cast<size_t>(count)) {
                    auto* entry = reinterpret_cast<LinuxDirent64*>(
                        entries.data() + offset
                    );
                    char* end = nullptr;
                    const long tid = strtol(entry->name, &end, 10);
                    if (end != entry->name &&
                        *end == '\0' &&
                        tid > 0) {
                        ++probe.task_count;
                        const size_t needed = strlen(entry->name) + 2;
                        if (formatted + needed <
                            sizeof(probe.first_tasks)) {
                            if (formatted != 0) {
                                probe.first_tasks[formatted++] = ' ';
                            }
                            memcpy(
                                probe.first_tasks + formatted,
                                entry->name,
                                strlen(entry->name)
                            );
                            formatted += strlen(entry->name);
                            probe.first_tasks[formatted] = '\0';
                        }
                        char task_comm_path[96] = {};
                        char task_comm[64] = {};
                        snprintf(
                            task_comm_path,
                            sizeof(task_comm_path),
                            "/proc/%d/task/%ld/comm",
                            probe.parent_pid,
                            tid
                        );
                        read_into_buffer(
                            task_comm_path,
                            task_comm,
                            sizeof(task_comm)
                        );
                        for (char* p = task_comm; *p != '\0'; ++p) {
                            if (*p == '\n' || *p == '\r') {
                                *p = '\0';
                                break;
                            }
                        }
                        char detail[128] = {};
                        snprintf(
                            detail,
                            sizeof(detail),
                            "%ld:%s ",
                            tid,
                            task_comm
                        );
                        const size_t details_length =
                            strlen(probe.task_details);
                        if (details_length + strlen(detail) <
                            sizeof(probe.task_details)) {
                            memcpy(
                                probe.task_details + details_length,
                                detail,
                                strlen(detail)
                            );
                            probe.task_details[
                                details_length + strlen(detail)
                            ] = '\0';
                        }
                    }
                    if (entry->record_length == 0) break;
                    offset += entry->record_length;
                }
            }
            close(directory_fd);
        }
        size_t written = 0;
        const auto* bytes = reinterpret_cast<const char*>(&probe);
        while (written < sizeof(probe)) {
            const ssize_t count = write(
                pipe_fds[1],
                bytes + written,
                sizeof(probe) - written
            );
            if (count <= 0) break;
            written += static_cast<size_t>(count);
        }
        close(pipe_fds[1]);
        _exit(0);
    }
    close(pipe_fds[1]);
    ChildProcProbe probe;
    size_t received = 0;
    auto* bytes = reinterpret_cast<char*>(&probe);
    while (received < sizeof(probe)) {
        const ssize_t count = read(
            pipe_fds[0],
            bytes + received,
            sizeof(probe) - received
        );
        if (count <= 0) break;
        received += static_cast<size_t>(count);
    }
    close(pipe_fds[0]);
    if (child_pid > 0) {
        int status = 0;
        waitpid(child_pid, &status, 0);
    }
    if (received != sizeof(probe)) {
        *report += "\nchild-proc=incomplete=" +
            std::to_string(received);
        return;
    }
    std::string group_value;
    for (int index = 0; index < probe.group_count; ++index) {
        if (!group_value.empty()) group_value.push_back(' ');
        group_value += std::to_string(probe.groups[index]);
    }
    std::string status(probe.parent_status);
    std::replace(status.begin(), status.end(), '\n', '|');
    std::string cmdline(probe.parent_cmdline);
    std::replace(cmdline.begin(), cmdline.end(), '\0', '|');
    std::string attr(probe.parent_attr);
    std::replace(attr.begin(), attr.end(), '\0', '?');
    *report +=
        "\nchild-proc.parent_pid=" + std::to_string(probe.parent_pid) +
        "\nchild-proc.ptrace_traceme=" +
            std::to_string(probe.trace_result) +
        "\nchild-proc.ptrace_errno=" +
            std::to_string(probe.trace_errno) +
        "\nchild-proc.uid=" + std::to_string(probe.uid) +
        "\nchild-proc.gid=" + std::to_string(probe.gid) +
        "\nchild-proc.groups=" + group_value +
        "\nchild-proc.parent_tasks=" +
            std::to_string(probe.task_count) +
        "\nchild-proc.parent_task_ids=" + probe.first_tasks +
        "\nchild-proc.parent_task_details=" + probe.task_details +
        "\nchild-proc.parent_comm=" + probe.parent_comm +
        "\nchild-proc.parent_status=" + status +
        "\nchild-proc.parent_cmdline=" + cmdline +
        "\nchild-proc.parent_attr=" + attr;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_testapp_NativeProbe_nativeEnvironmentSummary(
    JNIEnv* env,
    jclass
) {
    char cmdline[4096] = {};
    const int cmd_fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (cmd_fd >= 0) {
        const ssize_t length = read(cmd_fd, cmdline, sizeof(cmdline) - 1);
        if (length > 0) cmdline[length] = '\0';
        close(cmd_fd);
    }
    const std::string status = read_file("/proc/self/status", 16 * 1024);
    std::string uid_line;
    std::string name_line;
    size_t offset = 0;
    while (offset < status.size()) {
        const size_t end = status.find('\n', offset);
        const std::string line = status.substr(
            offset,
            end == std::string::npos ? std::string::npos : end - offset
        );
        if (starts_with(line, "Uid:")) uid_line = line;
        if (starts_with(line, "Name:")) name_line = line;
        if (end == std::string::npos) break;
        offset = end + 1;
    }
    const std::string summary =
        "getuid=" + std::to_string(getuid()) +
        "\ngeteuid=" + std::to_string(geteuid()) +
        "\nsyscall_getuid=" + std::to_string(syscall(SYS_getuid)) +
        "\npid=" + std::to_string(getpid()) +
        "\ncmdline=" + std::string(cmdline) +
        "\n" + name_line +
        "\n" + uid_line +
        "\nabi=" + property("ro.product.cpu.abi") +
        "\nsdk=" + property("ro.build.version.sdk") +
        "\nmodel=" + property("ro.product.model");
    return to_string(env, summary);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_testapp_NativeProbe_nativeHookProbe(
    JNIEnv* env,
    jclass,
    jstring data_dir,
    jstring apk_path,
    jstring external_dir
) {
    const char* data_chars = data_dir == nullptr
        ? nullptr
        : env->GetStringUTFChars(data_dir, nullptr);
    const char* apk_chars = apk_path == nullptr
        ? nullptr
        : env->GetStringUTFChars(apk_path, nullptr);
    const char* external_chars = external_dir == nullptr
        ? nullptr
        : env->GetStringUTFChars(external_dir, nullptr);

    uid_t real_uid = 0;
    uid_t effective_uid = 0;
    uid_t saved_uid = 0;
    gid_t real_gid = 0;
    gid_t effective_gid = 0;
    gid_t saved_gid = 0;
    getresuid(&real_uid, &effective_uid, &saved_uid);
    getresgid(&real_gid, &effective_gid, &saved_gid);
    std::array<gid_t, 16> groups{};
    const int group_count = getgroups(
        static_cast<int>(groups.size()),
        groups.data()
    );
    std::string group_value;
    for (int index = 0; index < group_count; ++index) {
        if (!group_value.empty()) group_value.push_back(' ');
        group_value += std::to_string(groups[static_cast<size_t>(index)]);
    }

    char process_name[17] = {};
    prctl(PR_GET_NAME, process_name);
    char cwd[4096] = {};
    getcwd(cwd, sizeof(cwd));

    struct stat proc_stat {};
    struct stat data_stat {};
    struct stat apk_stat {};
    struct stat raw_proc_stat {};
    lstat("/proc/self", &proc_stat);
    if (data_chars != nullptr) lstat(data_chars, &data_stat);
    if (apk_chars != nullptr) lstat(apk_chars, &apk_stat);
    if (data_chars != nullptr && kSysFstatat >= 0) {
        syscall(
            kSysFstatat,
            AT_FDCWD,
            data_chars,
            &raw_proc_stat,
            0
        );
    }

    std::string status = read_file("/proc/self/status", 32 * 1024);
    std::string attr = read_file("/proc/self/attr/current", 4096);
    std::string cgroup = read_file("/proc/self/cgroup", 8192);
    std::string cmdline = read_file("/proc/self/cmdline", 4096);
    std::replace(status.begin(), status.end(), '\n', '|');
    std::replace(attr.begin(), attr.end(), '\0', '?');
    std::replace(cgroup.begin(), cgroup.end(), '\n', '|');
    std::replace(cmdline.begin(), cmdline.end(), '\0', '|');

    const std::string data_root = data_chars == nullptr ? "" : data_chars;
    const std::string apk_root = apk_chars == nullptr ? "" : apk_chars;
    const std::string external_root =
        external_chars == nullptr ? "" : external_chars;
    const std::string files_dir = data_root + "/files";
    const std::string cache_dir = data_root + "/cache";
    const std::string code_cache_dir = data_root + "/code_cache";
    const std::string databases_dir = data_root + "/databases";
    const std::string shared_prefs_dir = data_root + "/shared_prefs";
    const std::string data_alias = data_root.empty()
        ? ""
        : "/data/data/" + std::string(data_root.substr(
            data_root.find_last_of('/') + 1
        ));
    const std::string files_alias = data_alias + "/files";
    const std::string proc_self_task =
        "/proc/self/task/" + std::to_string(syscall(SYS_gettid));
    std::string external_sdcard_alias;
    if (!external_root.empty()) {
        constexpr const char* kStoragePrefix = "/storage/emulated/0/";
        if (external_root.rfind(kStoragePrefix, 0) == 0) {
            external_sdcard_alias =
                "/sdcard/" + external_root.substr(strlen(kStoragePrefix));
        }
    }

    std::string report =
        "jni.activityThread.currentOpPackageName=" +
            android_thread_string(env, "currentOpPackageName") +
        "\njni.activityThread.currentPackageName=" +
            android_thread_string(env, "currentPackageName") +
        "\njni.currentApplication.getPackageName=" +
            android_application_package(env, "getPackageName") +
        "\njni.currentApplication.getOpPackageName=" +
            android_application_package(env, "getOpPackageName") +
        "\n" +
        "libc.getuid=" + std::to_string(getuid()) +
        "\nlibc.geteuid=" + std::to_string(geteuid()) +
        "\nlibc.getgid=" + std::to_string(getgid()) +
        "\nlibc.getegid=" + std::to_string(getegid()) +
        "\ngetresuid=" + std::to_string(real_uid) + "," +
            std::to_string(effective_uid) + "," +
            std::to_string(saved_uid) +
        "\ngetresgid=" + std::to_string(real_gid) + "," +
            std::to_string(effective_gid) + "," +
            std::to_string(saved_gid) +
        "\ngetgroups=" + group_value +
        "\nsyscall.getuid=" + std::to_string(syscall(SYS_getuid)) +
        "\nsyscall.geteuid=" + std::to_string(syscall(SYS_geteuid)) +
        "\nsyscall.getgid=" + std::to_string(syscall(SYS_getgid)) +
        "\nsyscall.getegid=" + std::to_string(syscall(SYS_getegid)) +
        "\nprctl.dumpable=" + std::to_string(prctl(PR_GET_DUMPABLE)) +
        "\nprctl.noNewPrivs=" + std::to_string(prctl(PR_GET_NO_NEW_PRIVS)) +
        "\nprctl.seccomp=" + std::to_string(prctl(PR_GET_SECCOMP)) +
        "\nprctl.name=" + process_name +
        "\n" + capability_summary() +
        "\npid=" + std::to_string(getpid()) +
        "\nppid=" + std::to_string(getppid()) +
        "\ntid=" + std::to_string(syscall(SYS_gettid)) +
        "\nsid=" + std::to_string(getsid(0)) +
        "\npgid=" + std::to_string(getpgid(0)) +
        "\ngetcwd=" + cwd +
        "\n" + stat_summary("stat.procSelf", proc_stat) +
        "\n" + stat_summary("stat.dataDir", data_stat) +
        "\n" + stat_summary("stat.apk", apk_stat) +
        "\n" + stat_summary("syscall.stat.dataDir", raw_proc_stat);
    cpu_set_t cpu_set;
    CPU_ZERO(&cpu_set);
    const int affinity_result = sched_getaffinity(
        0,
        sizeof(cpu_set),
        &cpu_set
    );
    report += "\nsched.affinity=" +
        (affinity_result == 0
            ? std::to_string(CPU_COUNT(&cpu_set))
            : "error=" + std::to_string(errno));
    struct utsname system_name {};
    if (uname(&system_name) == 0) {
        report += "\nuname=" + std::string(system_name.sysname) +
            "/" + system_name.release +
            "/" + system_name.machine;
    } else {
        report += "\nuname=error=" + std::to_string(errno);
    }

    const std::vector<std::pair<std::string, std::pair<std::string, bool>>> paths = {
        {"dataDir", {data_root, true}},
        {"dataAlias", {data_alias, true}},
        {"filesDir", {files_dir, true}},
        {"filesAlias", {files_alias, true}},
        {"cacheDir", {cache_dir, true}},
        {"codeCacheDir", {code_cache_dir, true}},
        {"databaseDir", {databases_dir, true}},
        {"sharedPrefsDir", {shared_prefs_dir, true}},
        {"externalFiles", {external_root, true}},
        {"externalSdcardAlias", {external_sdcard_alias, true}},
        {"apk", {apk_root, false}},
        {"procSelf", {"/proc/self", false}},
        {"procStatus", {"/proc/self/status", false}},
        {"procCmdline", {"/proc/self/cmdline", false}},
        {"procAttrCurrent", {"/proc/self/attr/current", false}},
        {"procThreadSelfStatus", {"/proc/thread-self/status", false}},
        {"procThreadSelfAttr", {"/proc/thread-self/attr/current", false}},
        {"procSelfTaskRoot", {"/proc/self/task", true}},
        {"procSelfTask", {proc_self_task, true}},
        {"procSelfTaskComm", {proc_self_task + "/comm", false}},
        {"procSelfTaskStatus", {proc_self_task + "/status", false}},
        {"procCwd", {"/proc/self/cwd", false}},
        {"procExe", {"/proc/self/exe", false}},
        {"procRoot", {"/proc/self/root", false}},
        {"procUserNs", {"/proc/self/ns/user", false}},
    };
    for (const auto& entry : paths) {
        report += "\npath." + entry.first + "=" +
            path_summary(
                entry.first.c_str(),
                entry.second.first.c_str(),
                entry.second.second
            );
    }
    report +=
        "\nreadlink.procCwd=" + readlink_summary("/proc/self/cwd") +
        "\nreadlink.procExe=" + readlink_summary("/proc/self/exe") +
        "\nreadlink.procRoot=" + readlink_summary("/proc/self/root") +
        "\nreadlink.procUserNs=" + readlink_summary("/proc/self/ns/user");
    if (!data_root.empty()) {
        const int directory_fd = open(
            data_root.c_str(),
            O_RDONLY | O_DIRECTORY | O_CLOEXEC
        );
        if (directory_fd >= 0) {
            struct stat value {};
            const int result = fstat(directory_fd, &value);
            report += "\nfstat.dataDir=" +
                (result == 0 ? compact_stat(value) : "error=" + std::to_string(errno));
            close(directory_fd);
        } else {
            report += "\nfstat.dataDir=open-error=" + std::to_string(errno);
        }
        report +=
            "\nstatfs.dataDir=" + statfs_summary(data_root.c_str()) +
            "\nstatvfs.dataDir=" + statvfs_summary(data_root.c_str());
    }
    if (!external_root.empty()) {
        report +=
            "\nstatfs.external=" + statfs_summary(external_root.c_str()) +
            "\nstatvfs.external=" + statvfs_summary(external_root.c_str());
    }
    if (data_chars != nullptr) {
        report += "\n" + xattr_summary("xattr.dataDir", data_chars) +
            "\n" + syscall_xattr_summary("syscall.xattr.dataDir", data_chars);
    }
    if (apk_chars != nullptr) {
        report += "\n" + xattr_summary("xattr.apk", apk_chars) +
            "\n" + syscall_xattr_summary("syscall.xattr.apk", apk_chars);
    }
    if (external_chars != nullptr && external_chars[0] != '\0') {
        report += "\n" + xattr_summary("xattr.external", external_chars) +
            "\n" + syscall_xattr_summary(
                "syscall.xattr.external",
                external_chars
            );
    }
    report +=
        "\nproc.status=" + status +
        "\nproc.attr.current=" + attr +
        "\nproc.cgroup=" + cgroup +
        "\nproc.cmdline=" + cmdline;
    run_child_proc_probe(&report);

    if (data_dir != nullptr) {
        env->ReleaseStringUTFChars(data_dir, data_chars);
    }
    if (apk_path != nullptr) {
        env->ReleaseStringUTFChars(apk_path, apk_chars);
    }
    if (external_dir != nullptr) {
        env->ReleaseStringUTFChars(external_dir, external_chars);
    }
    return to_string(env, report);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_testapp_NativeProbe_nativeOpenPath(
    JNIEnv* env,
    jclass,
    jstring path
) {
    if (path == nullptr) return nullptr;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return nullptr;
    const int fd = open(chars, O_RDONLY | O_CLOEXEC);
    env->ReleaseStringUTFChars(path, chars);
    if (fd < 0) return to_string(env, "open-failed");
    return to_string(env, read_fd(fd));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_testapp_NativeProbe_nativeFopenPath(
    JNIEnv* env,
    jclass,
    jstring path
) {
    if (path == nullptr) return nullptr;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return nullptr;
    const std::string value = read_file(chars, 64 * 1024);
    env->ReleaseStringUTFChars(path, chars);
    return to_string(env, value.empty() ? "fopen-failed" : value);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_testapp_NativeProbe_nativeSyscallOpenPath(
    JNIEnv* env,
    jclass,
    jstring path
) {
    if (path == nullptr) return nullptr;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return nullptr;
    const long fd = syscall(
        SYS_openat,
        AT_FDCWD,
        chars,
        O_RDONLY | O_CLOEXEC,
        0
    );
    env->ReleaseStringUTFChars(path, chars);
    if (fd < 0) return to_string(env, "syscall-open-failed");
    return to_string(env, read_fd(static_cast<int>(fd)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_testapp_NativeProbe_nativeFdPath(
    JNIEnv* env,
    jclass,
    jstring path
) {
    if (path == nullptr) return nullptr;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return nullptr;
    const std::string value = fd_link(chars);
    env->ReleaseStringUTFChars(path, chars);
    return to_string(env, value);
}
