#include <jni.h>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <link.h>
#include <linux/capability.h>
#include <linux/stat.h>
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
    int directory_fd = -1;
    int directory_errno = 0;
    long first_getdents = 0;
    int first_getdents_errno = 0;
    int seccomp_filters = -1;
    long raw_uid = -1;
    int raw_self_status_open = 0;
    int parent_dumpable = -1;
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

#if defined(__aarch64__)
__attribute__((noinline)) long raw_syscall6(
    long number,
    long arg0,
    long arg1,
    long arg2,
    long arg3,
    long arg4,
    long arg5
) {
    register long x0 asm("x0") = arg0;
    register long x1 asm("x1") = arg1;
    register long x2 asm("x2") = arg2;
    register long x3 asm("x3") = arg3;
    register long x4 asm("x4") = arg4;
    register long x5 asm("x5") = arg5;
    register long x8 asm("x8") = number;
    asm volatile(
        "svc #0"
        : "+r"(x0)
        : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8)
        : "memory"
    );
    return x0;
}
#define RAW_SYSCALL_SUPPORTED 1
#elif defined(__x86_64__)
__attribute__((noinline)) long raw_syscall6(
    long number,
    long arg0,
    long arg1,
    long arg2,
    long arg3,
    long arg4,
    long arg5
) {
    register long rax asm("rax") = number;
    register long rdi asm("rdi") = arg0;
    register long rsi asm("rsi") = arg1;
    register long rdx asm("rdx") = arg2;
    register long r10 asm("r10") = arg3;
    register long r8 asm("r8") = arg4;
    register long r9 asm("r9") = arg5;
    asm volatile(
        "syscall"
        : "+r"(rax)
        : "r"(rdi), "r"(rsi), "r"(rdx), "r"(r10), "r"(r8), "r"(r9)
        : "rcx", "r11", "memory"
    );
    return rax;
}
#define RAW_SYSCALL_SUPPORTED 1
#else
long raw_syscall6(
    long,
    long,
    long,
    long,
    long,
    long,
    long
) {
    return -ENOSYS;
}
#define RAW_SYSCALL_SUPPORTED 0
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

std::string raw_read_file(const char* path, size_t limit = 256 * 1024) {
#if RAW_SYSCALL_SUPPORTED
    const long raw_fd = raw_syscall6(
        SYS_openat,
        AT_FDCWD,
        reinterpret_cast<long>(path),
        O_RDONLY | O_CLOEXEC,
        0,
        0,
        0
    );
    if (raw_fd < 0) return "error=" + std::to_string(-raw_fd);
    std::string result;
    std::array<char, 4096> buffer{};
    while (result.size() < limit) {
        const long count = raw_syscall6(
            SYS_read,
            raw_fd,
            reinterpret_cast<long>(buffer.data()),
            buffer.size(),
            0,
            0,
            0
        );
        if (count <= 0) break;
        result.append(buffer.data(), static_cast<size_t>(count));
    }
    raw_syscall6(SYS_close, raw_fd, 0, 0, 0, 0, 0);
    return result;
#else
    (void)path;
    (void)limit;
    return "unsupported";
#endif
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

std::string compact_stat(const struct stat& value);

std::string raw_syscall_summary(const char* data_root) {
#if RAW_SYSCALL_SUPPORTED
    const long uid = raw_syscall6(SYS_getuid, 0, 0, 0, 0, 0, 0);
    const long euid = raw_syscall6(SYS_geteuid, 0, 0, 0, 0, 0, 0);
    const long gid = raw_syscall6(SYS_getgid, 0, 0, 0, 0, 0, 0);
    const long egid = raw_syscall6(SYS_getegid, 0, 0, 0, 0, 0, 0);

    std::string files_path = data_root == nullptr ? "" : data_root;
    if (!files_path.empty()) files_path += "/files";
    const long files_fd = files_path.empty()
        ? -ENOENT
        : raw_syscall6(
            SYS_openat,
            AT_FDCWD,
            reinterpret_cast<long>(files_path.c_str()),
            O_RDONLY | O_DIRECTORY | O_CLOEXEC,
            0,
            0,
            0
        );
    if (files_fd >= 0) {
        raw_syscall6(SYS_close, files_fd, 0, 0, 0, 0, 0);
    }

    struct stat raw_status {};
    const char* status_path = "/proc/self/status";
    const long stat_result = raw_syscall6(
        kSysFstatat,
        AT_FDCWD,
        reinterpret_cast<long>(status_path),
        reinterpret_cast<long>(&raw_status),
        0,
        0,
        0
    );

    char exe[PATH_MAX] = {};
    const char* exe_path = "/proc/self/exe";
    const long exe_length = raw_syscall6(
        SYS_readlinkat,
        AT_FDCWD,
        reinterpret_cast<long>(exe_path),
        reinterpret_cast<long>(exe),
        sizeof(exe) - 1,
        0,
        0
    );
    if (exe_length >= 0) exe[exe_length] = '\0';

    struct statx raw_statx {};
    const long statx_result = files_path.empty()
        ? -ENOENT
        : raw_syscall6(
            SYS_statx,
            AT_FDCWD,
            reinterpret_cast<long>(files_path.c_str()),
            0,
            STATX_BASIC_STATS,
            reinterpret_cast<long>(&raw_statx),
            0
        );

    const long access_result = files_path.empty()
        ? -ENOENT
        : raw_syscall6(
            SYS_faccessat,
            AT_FDCWD,
            reinterpret_cast<long>(files_path.c_str()),
            F_OK,
            0,
            0,
            0
        );

    const long self_task_fd = raw_syscall6(
        SYS_openat,
        AT_FDCWD,
        reinterpret_cast<long>("/proc/self/task"),
        O_RDONLY | O_DIRECTORY | O_CLOEXEC,
        0,
        0,
        0
    );
    long self_task_entries = 0;
    int self_task_errno = 0;
    if (self_task_fd >= 0) {
        std::array<char, 4096> entries {};
        errno = 0;
        self_task_entries = raw_syscall6(
            SYS_getdents64,
            self_task_fd,
            reinterpret_cast<long>(entries.data()),
            entries.size(),
            0,
            0,
            0
        );
        if (self_task_entries < 0) self_task_errno = errno;
        raw_syscall6(SYS_close, self_task_fd, 0, 0, 0, 0, 0);
    } else {
        self_task_errno = static_cast<int>(-self_task_fd);
    }

    const long real_pid = raw_syscall6(
        SYS_getpid,
        0,
        0,
        0,
        0,
        0,
        0
    );
    char own_task_path[96] = {};
    snprintf(
        own_task_path,
        sizeof(own_task_path),
        "/proc/%ld/task",
        real_pid
    );
    const long own_task_fd = raw_syscall6(
        SYS_openat,
        AT_FDCWD,
        reinterpret_cast<long>(own_task_path),
        O_RDONLY | O_DIRECTORY | O_CLOEXEC,
        0,
        0,
        0
    );
    long own_task_entries = 0;
    int own_task_errno = 0;
    if (own_task_fd >= 0) {
        std::array<char, 4096> entries {};
        errno = 0;
        own_task_entries = raw_syscall6(
            SYS_getdents64,
            own_task_fd,
            reinterpret_cast<long>(entries.data()),
            entries.size(),
            0,
            0,
            0
        );
        if (own_task_entries < 0) own_task_errno = errno;
        raw_syscall6(SYS_close, own_task_fd, 0, 0, 0, 0, 0);
    } else {
        own_task_errno = static_cast<int>(-own_task_fd);
    }

    return "raw.syscall.supported=1"
        "\nraw.syscall.getuid=" + std::to_string(uid) +
        "\nraw.syscall.geteuid=" + std::to_string(euid) +
        "\nraw.syscall.getgid=" + std::to_string(gid) +
        "\nraw.syscall.getegid=" + std::to_string(egid) +
        "\nraw.syscall.openat.files=" + (files_fd >= 0
            ? "ok"
            : "error=" + std::to_string(files_fd)) +
        "\nraw.syscall.newfstatat.procStatus=" + (stat_result == 0
            ? compact_stat(raw_status)
            : "error=" + std::to_string(stat_result)) +
        "\nraw.syscall.readlinkat.procExe=" + (exe_length >= 0
            ? std::string(exe, static_cast<size_t>(exe_length))
            : "error=" + std::to_string(exe_length)) +
        "\nraw.syscall.statx.files=" + (statx_result == 0
            ? "dev=" + std::to_string(raw_statx.stx_dev_major) + ":" +
                std::to_string(raw_statx.stx_dev_minor) +
                " ino=" + std::to_string(raw_statx.stx_ino) +
                " mode=" + std::to_string(raw_statx.stx_mode) +
                " uid=" + std::to_string(raw_statx.stx_uid) +
                " gid=" + std::to_string(raw_statx.stx_gid)
            : "error=" + std::to_string(statx_result)) +
        "\nraw.syscall.faccessat.files=" + (access_result == 0
            ? "ok"
            : "error=" + std::to_string(access_result)) +
        "\nraw.syscall.selfTask=" + (self_task_fd >= 0
            ? "getdents=" + std::to_string(self_task_entries) +
                " errno=" + std::to_string(self_task_errno)
            : "open_error=" + std::to_string(self_task_errno)) +
        "\nraw.syscall.ownTaskPid=" + std::to_string(real_pid) +
        "\nraw.syscall.ownTask=" + (own_task_fd >= 0
            ? "getdents=" + std::to_string(own_task_entries) +
                " errno=" + std::to_string(own_task_errno)
            : "open_error=" + std::to_string(own_task_errno));
#else
    (void)data_root;
    return "raw.syscall.supported=0";
#endif
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

bool host_marker(const std::string& value) {
    return value.find("com.coke.nestspace") != std::string::npos ||
        value.find("/nestspace") != std::string::npos ||
        value.find("libnestspace") != std::string::npos ||
        value.find("NestSpace") != std::string::npos;
}

std::string join_samples(
    const std::vector<std::string>& samples,
    size_t limit = 4
) {
    std::string result;
    for (size_t index = 0; index < samples.size() && index < limit; ++index) {
        if (!result.empty()) result += "; ";
        result += samples[index];
    }
    return result;
}

struct TextLeakScan {
    int count = 0;
    std::vector<std::string> samples;
};

TextLeakScan scan_text_for_host(const std::string& value) {
    TextLeakScan scan;
    size_t offset = 0;
    while (offset < value.size()) {
        const size_t end = value.find('\n', offset);
        const std::string line = value.substr(
            offset,
            end == std::string::npos ? std::string::npos : end - offset
        );
        if (host_marker(line)) {
            ++scan.count;
            if (scan.samples.size() < 4) scan.samples.push_back(line);
        }
        if (end == std::string::npos) break;
        offset = end + 1;
    }
    return scan;
}

TextLeakScan scan_fd_links() {
    TextLeakScan scan;
    DIR* directory = opendir("/proc/self/fd");
    if (directory == nullptr) {
        scan.count = -1;
        return scan;
    }
    while (dirent* entry = readdir(directory)) {
        char* end = nullptr;
        const long fd = strtol(entry->d_name, &end, 10);
        if (end == entry->d_name || *end != '\0' || fd < 0) continue;
        const std::string path = "/proc/self/fd/" + std::to_string(fd);
        char target[4096] = {};
        const ssize_t length = readlink(
            path.c_str(),
            target,
            sizeof(target) - 1
        );
        if (length <= 0) continue;
        target[length] = '\0';
        const std::string value(target);
        if (host_marker(value)) {
            ++scan.count;
            if (scan.samples.size() < 4) {
                scan.samples.push_back(std::to_string(fd) + "->" + value);
            }
        }
    }
    closedir(directory);
    return scan;
}

struct ThreadScan {
    int total = 0;
    int host_names = 0;
    int uid_mismatches = 0;
    std::vector<std::string> host_samples;
    std::vector<std::string> uid_samples;
};

ThreadScan scan_threads(uid_t expected_uid) {
    ThreadScan scan;
    DIR* directory = opendir("/proc/self/task");
    if (directory == nullptr) {
        scan.total = -1;
        return scan;
    }
    while (dirent* entry = readdir(directory)) {
        char* end = nullptr;
        const long tid = strtol(entry->d_name, &end, 10);
        if (end == entry->d_name || *end != '\0' || tid <= 0) continue;
        ++scan.total;
        const std::string base =
            "/proc/self/task/" + std::to_string(tid);
        char comm[128] = {};
        read_into_buffer((base + "/comm").c_str(), comm, sizeof(comm));
        for (char* cursor = comm; *cursor != '\0'; ++cursor) {
            if (*cursor == '\n' || *cursor == '\r') {
                *cursor = '\0';
                break;
            }
        }
        const std::string name(comm);
        if (host_marker(name)) {
            ++scan.host_names;
            if (scan.host_samples.size() < 4) {
                scan.host_samples.push_back(
                    std::to_string(tid) + ":" + name
                );
            }
        }
        const std::string status = read_file(
            (base + "/status").c_str(),
            16 * 1024
        );
        const size_t uid_offset = status.find("Uid:");
        if (uid_offset == std::string::npos) continue;
        long real_uid = -1;
        if (sscanf(
            status.c_str() + uid_offset + 4,
            "%ld",
            &real_uid
        ) != 1) {
            continue;
        }
        if (real_uid >= 0 &&
            static_cast<uid_t>(real_uid) != expected_uid) {
            ++scan.uid_mismatches;
            if (scan.uid_samples.size() < 4) {
                scan.uid_samples.push_back(
                    std::to_string(tid) + "=" +
                    std::to_string(real_uid)
                );
            }
        }
    }
    closedir(directory);
    return scan;
}

struct ElfScanState {
    int modules = 0;
    int host_modules = 0;
    std::vector<std::string> samples;
};

int scan_elf_module(
    struct dl_phdr_info* info,
    size_t,
    void* data
) {
    auto* state = static_cast<ElfScanState*>(data);
    if (state == nullptr) return 0;
    ++state->modules;
    const std::string name =
        info->dlpi_name == nullptr ? "" : info->dlpi_name;
    if (host_marker(name)) {
        ++state->host_modules;
        if (state->samples.size() < 4) state->samples.push_back(name);
    }
    return 0;
}

TextLeakScan scan_environment() {
    TextLeakScan scan;
    for (char** entry = environ; entry != nullptr && *entry != nullptr; ++entry) {
        const std::string value(*entry);
        if (host_marker(value)) {
            ++scan.count;
            if (scan.samples.size() < 4) scan.samples.push_back(value);
        }
    }
    return scan;
}

void run_child_proc_probe(std::string* report) {
    if (report == nullptr) return;
    int pipe_fds[2] = {-1, -1};
    if (pipe2(pipe_fds, O_CLOEXEC) != 0) {
        *report += "\nchild-proc=pipe-error=" + std::to_string(errno);
        return;
    }
    const int previous_dumpable = prctl(PR_GET_DUMPABLE);
    prctl(PR_SET_DUMPABLE, 1);
    const pid_t child_pid = fork();
    if (child_pid == 0) {
        close(pipe_fds[0]);
        ChildProcProbe probe;
        probe.parent_dumpable = previous_dumpable;
        probe.parent_pid = static_cast<int>(raw_syscall6(
            SYS_getppid,
            0,
            0,
            0,
            0,
            0,
            0
        ));
        probe.uid = getuid();
        probe.gid = getgid();
        probe.group_count = getgroups(
            static_cast<int>(std::size(probe.groups)),
            probe.groups
        );
        probe.raw_uid = raw_syscall6(
            SYS_getuid,
            0,
            0,
            0,
            0,
            0,
            0
        );
        const long raw_status_fd = raw_syscall6(
            SYS_openat,
            AT_FDCWD,
            reinterpret_cast<long>("/proc/self/status"),
            O_RDONLY | O_CLOEXEC,
            0,
            0,
            0
        );
        probe.raw_self_status_open = raw_status_fd >= 0
            ? 0
            : static_cast<int>(-raw_status_fd);
        if (raw_status_fd >= 0) {
            raw_syscall6(SYS_close, raw_status_fd, 0, 0, 0, 0, 0);
        }
        char own_status[2048] = {};
        read_into_buffer(
            "/proc/self/status",
            own_status,
            sizeof(own_status)
        );
        const char* filters = strstr(own_status, "Seccomp_filters:");
        if (filters != nullptr) {
            probe.seccomp_filters = atoi(filters + 16);
        }

        const std::string parent = "/proc/" +
            std::to_string(probe.parent_pid);
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
        const long raw_directory_fd = raw_syscall6(
            SYS_openat,
            AT_FDCWD,
            reinterpret_cast<long>(task_root.c_str()),
            O_RDONLY | O_DIRECTORY | O_CLOEXEC,
            0,
            0,
            0
        );
        const int directory_fd = raw_directory_fd >= 0
            ? static_cast<int>(raw_directory_fd)
            : -1;
        probe.directory_fd = directory_fd;
        probe.directory_errno = directory_fd < 0
            ? static_cast<int>(-raw_directory_fd)
            : 0;
        if (directory_fd >= 0) {
            std::array<char, 4096> entries{};
            size_t formatted = 0;
            for (int pass = 0;; ++pass) {
                errno = 0;
                const long count = syscall(
                    SYS_getdents64,
                    directory_fd,
                    entries.data(),
                    entries.size()
                );
                if (pass == 0) {
                    probe.first_getdents = count;
                    probe.first_getdents_errno = count <= 0 ? errno : 0;
                }
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
        errno = 0;
        probe.trace_result = ptrace(PTRACE_TRACEME, 0, nullptr, nullptr);
        probe.trace_errno = probe.trace_result < 0 ? errno : 0;
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
    prctl(PR_SET_DUMPABLE, previous_dumpable);
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
        "\nchild-proc.directory_fd=" +
            std::to_string(probe.directory_fd) +
        "\nchild-proc.directory_errno=" +
            std::to_string(probe.directory_errno) +
        "\nchild-proc.first_getdents=" +
            std::to_string(probe.first_getdents) +
        "\nchild-proc.first_getdents_errno=" +
            std::to_string(probe.first_getdents_errno) +
        "\nchild-proc.seccomp_filters=" +
            std::to_string(probe.seccomp_filters) +
        "\nchild-proc.raw_uid=" +
            std::to_string(probe.raw_uid) +
        "\nchild-proc.raw_self_status_open=" +
            std::to_string(probe.raw_self_status_open) +
        "\nchild-proc.parent_dumpable=" +
            std::to_string(probe.parent_dumpable) +
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
        "\n" + raw_syscall_summary(data_root.c_str()) +
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

    const TextLeakScan fd_scan = scan_fd_links();
    const ThreadScan thread_scan = scan_threads(getuid());
    ElfScanState elf_scan;
    dl_iterate_phdr(scan_elf_module, &elf_scan);
    const std::string raw_maps = raw_read_file("/proc/self/maps");
    const std::string raw_mountinfo =
        raw_read_file("/proc/self/mountinfo");
    const TextLeakScan maps_scan = scan_text_for_host(raw_maps);
    const TextLeakScan mountinfo_scan = scan_text_for_host(raw_mountinfo);
    const TextLeakScan environment_scan = scan_environment();
    report +=
        std::string("\nnative.fd.scanned=") +
            (fd_scan.count < 0 ? "error" : "ok") +
        "\nnative.fd.hostLeaks=" + std::to_string(fd_scan.count) +
        "\nnative.fd.hostSamples=" + join_samples(fd_scan.samples) +
        "\nnative.task.total=" + std::to_string(thread_scan.total) +
        "\nnative.task.hostNameLeaks=" +
            std::to_string(thread_scan.host_names) +
        "\nnative.task.hostNameSamples=" +
            join_samples(thread_scan.host_samples) +
        "\nnative.task.uidMismatches=" +
            std::to_string(thread_scan.uid_mismatches) +
        "\nnative.task.uidSamples=" +
            join_samples(thread_scan.uid_samples) +
        "\nnative.elf.modules=" + std::to_string(elf_scan.modules) +
        "\nnative.elf.hostModules=" +
            std::to_string(elf_scan.host_modules) +
        "\nnative.elf.hostSamples=" +
            join_samples(elf_scan.samples) +
        "\nnative.rawMaps.read=" +
            (starts_with(raw_maps, "error=") ? raw_maps : "ok") +
        "\nnative.rawMaps.hostLeaks=" +
            std::to_string(maps_scan.count) +
        "\nnative.rawMaps.hostSamples=" +
            join_samples(maps_scan.samples) +
        "\nnative.rawMountinfo.read=" +
            (starts_with(raw_mountinfo, "error=")
                ? raw_mountinfo
                : "ok") +
        "\nnative.rawMountinfo.hostLeaks=" +
            std::to_string(mountinfo_scan.count) +
        "\nnative.rawMountinfo.hostSamples=" +
            join_samples(mountinfo_scan.samples) +
        "\nnative.environment.hostLeaks=" +
            std::to_string(environment_scan.count) +
        "\nnative.environment.hostSamples=" +
            join_samples(environment_scan.samples);
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
