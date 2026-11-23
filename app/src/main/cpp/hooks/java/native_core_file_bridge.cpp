#include "nestspace/native_environment_internal.h"

#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <string>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>
#include <vector>

namespace {

constexpr const char* kTag = "NestSpaceCoreFiles";

std::string mapped_path(const char* path) {
    if (path == nullptr) return {};
    std::string mapped;
    return nestspace::rewrite_target_path(path, &mapped) ? mapped : path;
}

bool ensure_parent_directories(const std::string& path) {
    const size_t slash = path.find_last_of('/');
    if (slash == std::string::npos || slash == 0) return false;
    const std::string parent = path.substr(0, slash);
    for (size_t index = 1; index <= parent.size(); ++index) {
        if (index != parent.size() && parent[index] != '/') continue;
        const std::string component = parent.substr(0, index);
        if (::mkdir(component.c_str(), 0700) == 0 || errno == EEXIST) {
            continue;
        }
        return false;
    }
    return true;
}

std::string java_string(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string file_path(JNIEnv* env, jobject file) {
    if (file == nullptr) return {};
    jclass file_class = env->FindClass("java/io/File");
    if (file_class == nullptr) return {};
    jmethodID get_path = env->GetMethodID(
        file_class,
        "getPath",
        "()Ljava/lang/String;"
    );
    if (get_path == nullptr) {
        env->DeleteLocalRef(file_class);
        return {};
    }
    auto requested = static_cast<jstring>(
        env->CallObjectMethod(file, get_path)
    );
    env->DeleteLocalRef(file_class);
    if (env->ExceptionCheck()) return {};
    const std::string result = java_string(env, requested);
    if (requested != nullptr) env->DeleteLocalRef(requested);
    return result;
}

int actual_stat(const std::string& path, struct stat* value) {
#if defined(SYS_newfstatat)
    return static_cast<int>(
        ::syscall(SYS_newfstatat, AT_FDCWD, path.c_str(), value, 0)
    );
#elif defined(SYS_fstatat64)
    return static_cast<int>(
        ::syscall(SYS_fstatat64, AT_FDCWD, path.c_str(), value, 0)
    );
#else
    return ::stat(path.c_str(), value);
#endif
}

void throw_io_exception(JNIEnv* env, const std::string& path) {
    const int error = errno;
    const std::string message =
        path + ": " + std::strerror(error);
    jclass type = env->FindClass("java/io/IOException");
    if (type != nullptr) {
        env->ThrowNew(type, message.c_str());
        env->DeleteLocalRef(type);
    }
}

void throw_errno_exception(JNIEnv* env, const char* function, int error) {
    jclass type = env->FindClass("android/system/ErrnoException");
    if (type == nullptr) return;
    jmethodID constructor = env->GetMethodID(
        type,
        "<init>",
        "(Ljava/lang/String;I)V"
    );
    if (constructor != nullptr) {
        jstring name = env->NewStringUTF(function);
        jobject exception = env->NewObject(
            type,
            constructor,
            name,
            static_cast<jint>(error)
        );
        if (name != nullptr) env->DeleteLocalRef(name);
        if (exception != nullptr) {
            env->Throw(static_cast<jthrowable>(exception));
            env->DeleteLocalRef(exception);
        }
    }
    env->DeleteLocalRef(type);
}

jboolean create_file_exclusively(JNIEnv* env, jobject, jstring requested) {
    const std::string virtual_path = java_string(env, requested);
    if (virtual_path.empty()) return JNI_FALSE;
    const std::string actual = mapped_path(virtual_path.c_str());
    if (!ensure_parent_directories(actual)) {
        throw_io_exception(env, virtual_path);
        return JNI_FALSE;
    }
    const int fd = ::open(
        actual.c_str(),
        O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC,
        0666
    );
    if (fd >= 0) {
        ::close(fd);
        return JNI_TRUE;
    }
    if (errno == EEXIST) return JNI_FALSE;
    __android_log_print(
        ANDROID_LOG_WARN,
        kTag,
        "create failed requested=%s actual=%s errno=%d",
        virtual_path.c_str(),
        actual.c_str(),
        errno
    );
    throw_io_exception(env, virtual_path);
    return JNI_FALSE;
}

jboolean create_directory(JNIEnv* env, jobject, jobject file) {
    const std::string virtual_path = file_path(env, file);
    if (virtual_path.empty()) return JNI_FALSE;
    const std::string actual = mapped_path(virtual_path.c_str());
    if (::mkdir(actual.c_str(), 0777) == 0) return JNI_TRUE;
    const int mkdir_errno = errno;
    if (errno == EEXIST) {
        struct stat value {};
        const int stat_result = actual_stat(actual, &value);
        const bool directory = stat_result == 0 && S_ISDIR(value.st_mode);
        if (stat_result != 0) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kTag,
                "mkdir stat failed requested=%s actual=%s",
                virtual_path.c_str(),
                actual.c_str()
            );
        }
        return directory ? JNI_TRUE : JNI_FALSE;
    }
    __android_log_print(
        ANDROID_LOG_WARN,
        kTag,
        "mkdir failed requested=%s actual=%s errno=%d",
        virtual_path.c_str(),
        actual.c_str(),
        mkdir_errno
    );
    return JNI_FALSE;
}

jint file_attributes_file(JNIEnv* env, jobject, jobject file) {
    const std::string virtual_path = file_path(env, file);
    if (virtual_path.empty()) return 0;
    const std::string actual = mapped_path(virtual_path.c_str());
    struct stat value {};
    if (actual_stat(actual, &value) != 0) return 0;
    jint attributes = 1;
    if (S_ISREG(value.st_mode)) attributes |= 2;
    if (S_ISDIR(value.st_mode)) attributes |= 4;
    return attributes;
}

jint file_attributes_string(JNIEnv* env, jobject, jstring requested) {
    const std::string virtual_path = java_string(env, requested);
    if (virtual_path.empty()) return 0;
    const std::string actual = mapped_path(virtual_path.c_str());
    struct stat value {};
    if (actual_stat(actual, &value) != 0) return 0;
    jint attributes = 1;
    if (S_ISREG(value.st_mode)) attributes |= 2;
    if (S_ISDIR(value.st_mode)) attributes |= 4;
    return attributes;
}

jlong file_space(JNIEnv* env, jobject, jobject file, jint kind) {
    const std::string virtual_path = file_path(env, file);
    if (virtual_path.empty()) return 0;
    const std::string actual = mapped_path(virtual_path.c_str());
    struct statvfs value {};
    if (::statvfs(actual.c_str(), &value) != 0) return 0;
    const uint64_t units = kind == 0
        ? value.f_blocks
        : (kind == 1 ? value.f_bfree : value.f_bavail);
    return static_cast<jlong>(units * value.f_frsize);
}

jstring canonicalize_path(
    JNIEnv* env,
    jobject,
    jstring requested,
    jboolean
) {
    const std::string virtual_path = java_string(env, requested);
    const std::string actual = mapped_path(virtual_path.c_str());
    char resolved[PATH_MAX] = {};
    if (::realpath(actual.c_str(), resolved) == nullptr) {
        // Java's canonicalize contract differs from POSIX realpath: the final
        // components do not have to exist yet. Providers commonly canonicalize
        // configured cache subdirectories before creating them. Canonicalize
        // the nearest existing ancestor and append the remaining components.
        std::string existing = actual;
        std::string suffix;
        while (::realpath(existing.c_str(), resolved) == nullptr) {
            if (existing == "/" || existing.empty()) {
                throw_io_exception(env, virtual_path);
                return nullptr;
            }
            const size_t separator = existing.find_last_of('/');
            if (separator == std::string::npos) {
                throw_io_exception(env, virtual_path);
                return nullptr;
            }
            suffix = "/" + existing.substr(separator + 1) + suffix;
            existing = separator == 0
                ? "/"
                : existing.substr(0, separator);
        }
        const size_t base_length = strlen(resolved);
        if (base_length + suffix.size() >= sizeof(resolved)) {
            throw_io_exception(env, virtual_path);
            return nullptr;
        }
        memcpy(resolved + base_length, suffix.data(), suffix.size() + 1);
    }
    char virtual_result[PATH_MAX + 1] = {};
    ssize_t length = 0;
    const char* result = nestspace::virtual_path_from_actual(
        resolved,
        virtual_result,
        sizeof(virtual_result),
        &length
    ) ? virtual_result : resolved;
    return env->NewStringUTF(result);
}

jlong last_modified_time(JNIEnv* env, jobject, jobject file) {
    const std::string virtual_path = file_path(env, file);
    const std::string actual = mapped_path(virtual_path.c_str());
    struct stat value {};
    if (actual_stat(actual, &value) != 0) return 0;
    return static_cast<jlong>(value.st_mtime) * 1000;
}

jboolean set_permission(
    JNIEnv* env,
    jobject,
    jobject file,
    jint access,
    jboolean enable,
    jboolean owner_only
) {
    const std::string virtual_path = file_path(env, file);
    const std::string actual = mapped_path(virtual_path.c_str());
    struct stat value {};
    if (actual_stat(actual, &value) != 0) return JNI_FALSE;
    mode_t mask = 0;
    if (access == 4) {
        mask = owner_only == JNI_TRUE ? 0400 : 0444;
    } else if (access == 2) {
        mask = owner_only == JNI_TRUE ? 0200 : 0222;
    } else if (access == 1) {
        mask = owner_only == JNI_TRUE ? 0100 : 0111;
    } else {
        return JNI_FALSE;
    }
    const mode_t mode = enable == JNI_TRUE
        ? value.st_mode | mask
        : value.st_mode & ~mask;
    return ::chmod(actual.c_str(), mode) == 0 ? JNI_TRUE : JNI_FALSE;
}

jboolean set_last_modified(
    JNIEnv* env,
    jobject,
    jobject file,
    jlong milliseconds
) {
    const std::string virtual_path = file_path(env, file);
    const std::string actual = mapped_path(virtual_path.c_str());
    const struct timespec times[2] = {
        {0, UTIME_OMIT},
        {
            static_cast<time_t>(milliseconds / 1000),
            static_cast<long>((milliseconds % 1000) * 1000000)
        }
    };
    return ::utimensat(AT_FDCWD, actual.c_str(), times, 0) == 0
        ? JNI_TRUE
        : JNI_FALSE;
}

jboolean set_read_only(JNIEnv* env, jobject, jobject file) {
    const std::string virtual_path = file_path(env, file);
    const std::string actual = mapped_path(virtual_path.c_str());
    struct stat value {};
    if (actual_stat(actual, &value) != 0) return JNI_FALSE;
    return ::chmod(actual.c_str(), value.st_mode & ~0222) == 0
        ? JNI_TRUE
        : JNI_FALSE;
}

jobjectArray list_directory(JNIEnv* env, jobject, jobject file) {
    const std::string virtual_path = file_path(env, file);
    const std::string actual = mapped_path(virtual_path.c_str());
    DIR* directory = ::opendir(actual.c_str());
    if (directory == nullptr) return nullptr;
    std::vector<std::string> names;
    while (dirent* entry = ::readdir(directory)) {
        const char* name = entry->d_name;
        if (name == nullptr ||
            strcmp(name, ".") == 0 ||
            strcmp(name, "..") == 0) {
            continue;
        }
        names.emplace_back(name);
    }
    ::closedir(directory);
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(names.size()),
        string_class,
        nullptr
    );
    env->DeleteLocalRef(string_class);
    if (result == nullptr) return nullptr;
    for (jsize index = 0; index < static_cast<jsize>(names.size()); ++index) {
        jstring value = env->NewStringUTF(
            names[static_cast<size_t>(index)].c_str()
        );
        if (value == nullptr) continue;
        env->SetObjectArrayElement(result, index, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

jboolean linux_access(JNIEnv* env, jobject, jstring requested, jint mode) {
    const std::string virtual_path = java_string(env, requested);
    const std::string actual = mapped_path(virtual_path.c_str());
    const int result = ::access(actual.c_str(), mode);
    if (result == 0) return JNI_TRUE;
    if (errno == EACCES) return JNI_FALSE;
    throw_errno_exception(env, "access", errno);
    return JNI_FALSE;
}

void linux_mkdir(JNIEnv* env, jobject, jstring requested, jint mode) {
    const std::string virtual_path = java_string(env, requested);
    const std::string actual = mapped_path(virtual_path.c_str());
    if (::mkdir(actual.c_str(), static_cast<mode_t>(mode)) == 0) {
        return;
    }
    const int error = errno;
    if (error == EEXIST) {
        struct stat value {};
        if (actual_stat(actual, &value) == 0 && S_ISDIR(value.st_mode)) {
            return;
        }
    }
    __android_log_print(
        ANDROID_LOG_WARN,
        kTag,
        "libcore mkdir failed requested=%s actual=%s errno=%d",
        virtual_path.c_str(),
        actual.c_str(),
        error
    );
    throw_errno_exception(env, "mkdir", error);
}

void linux_remove(JNIEnv* env, jobject, jstring requested) {
    const std::string virtual_path = java_string(env, requested);
    const std::string actual = mapped_path(virtual_path.c_str());
    if (::remove(actual.c_str()) == 0) return;
    throw_errno_exception(env, "remove", errno);
}

void linux_rename(
    JNIEnv* env,
    jobject,
    jstring requested_old,
    jstring requested_new
) {
    const std::string virtual_old = java_string(env, requested_old);
    const std::string virtual_new = java_string(env, requested_new);
    const std::string actual_old = mapped_path(virtual_old.c_str());
    const std::string actual_new = mapped_path(virtual_new.c_str());
    if (::rename(actual_old.c_str(), actual_new.c_str()) == 0) return;
    throw_errno_exception(env, "rename", errno);
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeInstallCoreFileBridge(
    JNIEnv* env,
    jclass
) {
    jclass file_system = env->FindClass("java/io/UnixFileSystem");
    if (file_system == nullptr) return JNI_FALSE;
    const auto register_one = [env](
        jclass owner,
        const char* name,
        const char* signature,
        void* replacement
    ) {
        JNINativeMethod method = {
            const_cast<char*>(name),
            const_cast<char*>(signature),
            replacement
        };
        const jint result = env->RegisterNatives(owner, &method, 1);
        if (result != JNI_OK) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            __android_log_print(
                ANDROID_LOG_WARN,
                kTag,
                "native method registration failed name=%s signature=%s",
                name,
                signature
            );
        }
        return result == JNI_OK;
    };
    bool unix_installed = register_one(
        file_system,
        "createFileExclusively0",
        "(Ljava/lang/String;)Z",
        reinterpret_cast<void*>(create_file_exclusively)
    );
    unix_installed &= register_one(
        file_system,
        "createDirectory0",
        "(Ljava/io/File;)Z",
        reinterpret_cast<void*>(create_directory)
    );
    bool attributes_installed = register_one(
        file_system,
        "getBooleanAttributes0",
        "(Ljava/lang/String;)I",
        reinterpret_cast<void*>(file_attributes_string)
    );
    if (!attributes_installed) {
        attributes_installed = register_one(
            file_system,
            "getBooleanAttributes0",
            "(Ljava/io/File;)I",
            reinterpret_cast<void*>(file_attributes_file)
        );
    }
    unix_installed &= attributes_installed;
    unix_installed &= register_one(
        file_system,
        "getSpace0",
        "(Ljava/io/File;I)J",
        reinterpret_cast<void*>(file_space)
    );
    unix_installed &= register_one(
        file_system,
        "canonicalize0",
        "(Ljava/lang/String;Z)Ljava/lang/String;",
        reinterpret_cast<void*>(canonicalize_path)
    );
    unix_installed &= register_one(
        file_system,
        "getLastModifiedTime0",
        "(Ljava/io/File;)J",
        reinterpret_cast<void*>(last_modified_time)
    );
    unix_installed &= register_one(
        file_system,
        "setPermission0",
        "(Ljava/io/File;IZZ)Z",
        reinterpret_cast<void*>(set_permission)
    );
    unix_installed &= register_one(
        file_system,
        "setLastModifiedTime0",
        "(Ljava/io/File;J)Z",
        reinterpret_cast<void*>(set_last_modified)
    );
    unix_installed &= register_one(
        file_system,
        "setReadOnly0",
        "(Ljava/io/File;)Z",
        reinterpret_cast<void*>(set_read_only)
    );
    unix_installed &= register_one(
        file_system,
        "list0",
        "(Ljava/io/File;)[Ljava/lang/String;",
        reinterpret_cast<void*>(list_directory)
    );
    env->DeleteLocalRef(file_system);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        kTag,
        "native core file bridge installed=%d",
        unix_installed ? 1 : 0
    );

    jclass libcore_class = env->FindClass("libcore/io/Linux");
    if (libcore_class == nullptr) return unix_installed ? JNI_TRUE : JNI_FALSE;
    bool libcore_installed = register_one(
        libcore_class,
        "access",
        "(Ljava/lang/String;I)Z",
        reinterpret_cast<void*>(linux_access)
    );
    libcore_installed &= register_one(
        libcore_class,
        "mkdir",
        "(Ljava/lang/String;I)V",
        reinterpret_cast<void*>(linux_mkdir)
    );
    libcore_installed &= register_one(
        libcore_class,
        "remove",
        "(Ljava/lang/String;)V",
        reinterpret_cast<void*>(linux_remove)
    );
    libcore_installed &= register_one(
        libcore_class,
        "rename",
        "(Ljava/lang/String;Ljava/lang/String;)V",
        reinterpret_cast<void*>(linux_rename)
    );
    env->DeleteLocalRef(libcore_class);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        kTag,
        "libcore path bridge installed=%d",
        libcore_installed ? 1 : 0
    );
    return unix_installed && libcore_installed ? JNI_TRUE : JNI_FALSE;
}
