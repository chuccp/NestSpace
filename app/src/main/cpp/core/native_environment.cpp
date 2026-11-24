#include "nestspace/native_environment_internal.h"

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <memory>
#include <string>
#include <thread>
#include <unordered_map>
#include <utility>

extern "C" void install_native_crash_trace(const char* path);
extern "C" void set_native_packer_guard(bool enabled);
extern "C" bool install_native_kill_guard();
extern "C" bool install_native_syscall_gateway();

namespace {

thread_local jint g_deferred_binder_calling_pid = 0;

std::string to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::unordered_map<std::string, std::string> to_properties(
    JNIEnv* env,
    jobjectArray values
) {
    std::unordered_map<std::string, std::string> result;
    if (values == nullptr) return result;
    const jsize length = env->GetArrayLength(values);
    for (jsize index = 0; index + 1 < length; index += 2) {
        auto key = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, index + 1));
        const std::string name = to_string(env, key);
        const std::string content = to_string(env, value);
        if (key != nullptr) env->DeleteLocalRef(key);
        if (value != nullptr) env->DeleteLocalRef(value);
        if (!name.empty()) result[name] = content;
    }
    return result;
}

std::vector<std::string> to_strings(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> result;
    if (values == nullptr) return result;
    const jsize length = env->GetArrayLength(values);
    result.reserve(static_cast<size_t>(length));
    for (jsize index = 0; index < length; ++index) {
        auto value = static_cast<jstring>(
            env->GetObjectArrayElement(values, index)
        );
        const std::string content = to_string(env, value);
        if (value != nullptr) env->DeleteLocalRef(value);
        if (!content.empty()) result.push_back(content);
    }
    return result;
}

std::vector<int> to_ints(JNIEnv* env, jintArray values) {
    std::vector<int> result;
    if (values == nullptr) return result;
    const jsize length = env->GetArrayLength(values);
    result.resize(static_cast<size_t>(length));
    if (length > 0) {
        env->GetIntArrayRegion(
            values,
            0,
            length,
            result.data()
        );
    }
    return result;
}

std::string data_alias(const std::string& path) {
    static constexpr char kUser[] = "/data/user/0/";
    static constexpr char kData[] = "/data/data/";
    if (path.rfind(kUser, 0) == 0) {
        return std::string(kData) + path.substr(sizeof(kUser) - 1);
    }
    if (path.rfind(kData, 0) == 0) {
        return std::string(kUser) + path.substr(sizeof(kData) - 1);
    }
    return {};
}

jlongArray hook_state_array(JNIEnv* env, nestspace::HookState state) {
    const jlong values[10] = {
        static_cast<jlong>(state.patch_count),
        static_cast<jlong>(state.hooked_modules),
        static_cast<jlong>(state.scanned_modules),
        static_cast<jlong>(state.target_modules),
        static_cast<jlong>(state.errors),
        static_cast<jlong>(state.virtual_proc_reads),
        static_cast<jlong>(state.property_reads),
        static_cast<jlong>(state.dlopen_calls),
        static_cast<jlong>(state.last_hook_event),
        static_cast<jlong>(state.last_syscall_number),
    };
    jlongArray result = env->NewLongArray(10);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 10, values);
    }
    return result;
}

struct PropertyCallbackCookie {
    std::string value;
};

void property_callback_probe(
    void* cookie,
    const char*,
    const char* value,
    uint32_t
) {
    if (cookie != nullptr && value != nullptr) {
        static_cast<PropertyCallbackCookie*>(cookie)->value = value;
    }
}

jint virtual_binder_calling_uid(JNIEnv*, jclass) {
    const nestspace::Environment* environment =
        nestspace::current_environment();
    return environment == nullptr
        ? static_cast<jint>(getuid())
        : environment->observed_uid;
}

jint virtual_binder_calling_pid(JNIEnv*, jclass) {
    using IpcThreadStateSelf = void* (*)();
    using IpcThreadStateCallingPid = jint (*)(const void*);
    static const auto self = []() -> IpcThreadStateSelf {
        void* binder = dlopen("libbinder.so", RTLD_NOW | RTLD_NOLOAD);
        return binder == nullptr
            ? nullptr
            : reinterpret_cast<IpcThreadStateSelf>(dlsym(
                binder,
                "_ZN7android14IPCThreadState4selfEv"
            ));
    }();
    static const auto get_calling_pid =
        []() -> IpcThreadStateCallingPid {
            void* binder = dlopen(
                "libbinder.so",
                RTLD_NOW | RTLD_NOLOAD
            );
            return binder == nullptr
                ? nullptr
                : reinterpret_cast<IpcThreadStateCallingPid>(dlsym(
                    binder,
                    "_ZNK7android14IPCThreadState13getCallingPidEv"
                ));
        }();
    if (self == nullptr || get_calling_pid == nullptr) {
        return g_deferred_binder_calling_pid;
    }
    const void* thread_state = self();
    const jint kernel_pid = thread_state == nullptr
        ? 0
        : get_calling_pid(thread_state);
    return kernel_pid > 0
        ? kernel_pid
        : g_deferred_binder_calling_pid;
}

jint virtual_process_group(JNIEnv*, jclass, jint pid) {
    if (pid <= 0) pid = static_cast<jint>(getpid());
    const pid_t group = getpgid(static_cast<pid_t>(pid));
    if (group > 0) return static_cast<jint>(group);
    const pid_t self_group = getpgid(0);
    return self_group > 0 ? static_cast<jint>(self_group) : pid;
}

void virtual_process_signal(JNIEnv*, jclass, jint pid, jint signal) {
    const bool self = pid == static_cast<jint>(getpid());
    if (self && signal == SIGKILL &&
        nestspace::current_environment() != nullptr) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "forwarding Process.sendSignal self SIGKILL pid=%d tid=%d",
            getpid(),
            gettid()
        );
    }
    syscall(
        SYS_kill,
        static_cast<pid_t>(pid),
        static_cast<int>(signal)
    );
}

void virtual_process_tg_signal(
    JNIEnv*,
    jclass,
    jint group,
    jint thread,
    jint signal
) {
    if (group == static_cast<jint>(getpid()) &&
        signal == SIGKILL &&
        nestspace::current_environment() != nullptr) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "forwarding Process.sendTgSignal self SIGKILL pid=%d tid=%d"
            " target=%d",
            getpid(),
            gettid(),
            thread
        );
    }
    syscall(
        SYS_tgkill,
        static_cast<pid_t>(group),
        static_cast<pid_t>(thread),
        static_cast<int>(signal)
    );
}

void virtual_process_read_proc_lines(
    JNIEnv* env,
    jclass,
    jstring path_value,
    jobjectArray labels,
    jlongArray values
) {
    if (path_value == nullptr || labels == nullptr || values == nullptr) return;
    const std::string path = to_string(env, path_value);
    std::string content = nestspace::virtualized_proc_content(
        path.c_str(),
        getpid()
    );
    if (content.empty()) {
        const long opened = syscall(
            SYS_openat,
            AT_FDCWD,
            path.c_str(),
            O_RDONLY | O_CLOEXEC,
            0
        );
        if (opened < 0) return;
        std::array<char, 32768> buffer{};
        const long count = syscall(
            SYS_read,
            static_cast<int>(opened),
            buffer.data(),
            buffer.size() - 1
        );
        syscall(SYS_close, static_cast<int>(opened));
        if (count > 0) {
            content.assign(buffer.data(), static_cast<size_t>(count));
        }
    }
    const jsize label_count = env->GetArrayLength(labels);
    const jsize value_count = env->GetArrayLength(values);
    std::vector<jlong> output(static_cast<size_t>(value_count), -1);
    for (jsize index = 0; index < label_count; ++index) {
        auto label = static_cast<jstring>(
            env->GetObjectArrayElement(labels, index)
        );
        const std::string name = to_string(env, label);
        if (label != nullptr) env->DeleteLocalRef(label);
        if (name.empty()) continue;
        const std::string prefix =
            !name.empty() && name.back() == ':' ? name : name + ":";
        size_t offset = 0;
        while (offset < content.size()) {
            const size_t end = content.find('\n', offset);
            const std::string line = content.substr(
                offset,
                end == std::string::npos
                    ? std::string::npos
                    : end - offset
            );
            if (line.rfind(prefix, 0) == 0 && index < value_count) {
                const char* value = line.c_str() + prefix.size();
                while (*value == ' ' || *value == '\t') ++value;
                char* parsed_end = nullptr;
                const long long parsed = strtoll(value, &parsed_end, 10);
                if (parsed_end != value) {
                    output[static_cast<size_t>(index)] =
                        static_cast<jlong>(parsed);
                }
                break;
            }
            if (end == std::string::npos) break;
            offset = end + 1;
        }
    }
    if (value_count > 0) {
        env->SetLongArrayRegion(
            values,
            0,
            value_count,
            output.data()
        );
    }
}

void install_identity_jni_hooks(JNIEnv* env) {
    static std::once_flag once;
    std::call_once(once, [env] {
        jclass binder = env->FindClass("android/os/Binder");
        if (binder != nullptr) {
            JNINativeMethod methods[] = {
                {
                const_cast<char*>("getCallingUid"),
                const_cast<char*>("()I"),
                reinterpret_cast<void*>(virtual_binder_calling_uid)
                },
                {
                const_cast<char*>("getCallingPid"),
                const_cast<char*>("()I"),
                reinterpret_cast<void*>(virtual_binder_calling_pid)
                }
            };
            const jint result = env->RegisterNatives(
                binder,
                methods,
                static_cast<jint>(sizeof(methods) / sizeof(methods[0]))
            );
            env->DeleteLocalRef(binder);
            __android_log_print(
                result == JNI_OK ? ANDROID_LOG_DEBUG : ANDROID_LOG_WARN,
                "NestSpaceNative",
                "Binder identity natives installed result=%d",
                result
            );
        }

        jclass process = env->FindClass("android/os/Process");
        if (process != nullptr) {
            JNINativeMethod methods[] = {
                {
                    const_cast<char*>("getProcessGroup"),
                    const_cast<char*>("(I)I"),
                    reinterpret_cast<void*>(virtual_process_group)
                },
                {
                    const_cast<char*>("sendSignal"),
                    const_cast<char*>("(II)V"),
                    reinterpret_cast<void*>(virtual_process_signal)
                },
                {
                    const_cast<char*>("sendSignalQuiet"),
                    const_cast<char*>("(II)V"),
                    reinterpret_cast<void*>(virtual_process_signal)
                },
                {
                    const_cast<char*>("sendSignalThrows"),
                    const_cast<char*>("(II)V"),
                    reinterpret_cast<void*>(virtual_process_signal)
                },
                {
                    const_cast<char*>("sendTgSignalThrows"),
                    const_cast<char*>("(III)V"),
                    reinterpret_cast<void*>(virtual_process_tg_signal)
                },
            };
            const jint result = env->RegisterNatives(
                process,
                methods,
                static_cast<jint>(sizeof(methods) / sizeof(methods[0]))
            );
            __android_log_print(
                result == JNI_OK ? ANDROID_LOG_DEBUG : ANDROID_LOG_WARN,
                "NestSpaceNative",
                "Process group native installed result=%d",
                result
            );
            JNINativeMethod proc_method{
                const_cast<char*>("readProcLines"),
                const_cast<char*>(
                    "(Ljava/lang/String;[Ljava/lang/String;[J)V"
                ),
                reinterpret_cast<void*>(virtual_process_read_proc_lines)
            };
            const jint uid_result = env->RegisterNatives(
                process,
                &proc_method,
                1
            );
            if (env->ExceptionCheck()) env->ExceptionClear();
            __android_log_print(
                uid_result == JNI_OK
                    ? ANDROID_LOG_DEBUG
                    : ANDROID_LOG_WARN,
                "NestSpaceNative",
                "Process.readProcLines native installed result=%d",
                uid_result
            );
            env->DeleteLocalRef(process);
        }
    });
}

void suppress_runtime_native_exit(JNIEnv* env, jclass, jint status) {
    __android_log_print(
        ANDROID_LOG_WARN,
        "NestSpaceNative",
        "contained target Runtime.nativeExit status=%d",
        static_cast<int>(status)
    );
    if (env == nullptr || env->ExceptionCheck()) return;
    jclass throwable_class = env->FindClass("java/lang/Throwable");
    jclass log_class = env->FindClass("android/util/Log");
    jmethodID throwable_init = throwable_class == nullptr
        ? nullptr
        : env->GetMethodID(throwable_class, "<init>", "()V");
    jmethodID stack_trace = log_class == nullptr
        ? nullptr
        : env->GetStaticMethodID(
            log_class,
            "getStackTraceString",
            "(Ljava/lang/Throwable;)Ljava/lang/String;"
        );
    jobject throwable = throwable_init == nullptr
        ? nullptr
        : env->NewObject(throwable_class, throwable_init);
    auto text = stack_trace == nullptr || throwable == nullptr
        ? static_cast<jstring>(nullptr)
        : static_cast<jstring>(
            env->CallStaticObjectMethod(log_class, stack_trace, throwable)
        );
    if (text != nullptr) {
        const char* chars = env->GetStringUTFChars(text, nullptr);
        if (chars != nullptr) {
            __android_log_print(
                ANDROID_LOG_WARN,
                "NestSpaceNative",
                "contained exit stack=%s",
                chars
            );
            env->ReleaseStringUTFChars(text, chars);
        }
        env->DeleteLocalRef(text);
    }
    if (throwable != nullptr) env->DeleteLocalRef(throwable);
    if (log_class != nullptr) env->DeleteLocalRef(log_class);
    if (throwable_class != nullptr) env->DeleteLocalRef(throwable_class);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void install_runtime_exit_guard(JNIEnv* env) {
    if (env == nullptr) return;
    jclass runtime_class = env->FindClass("java/lang/Runtime");
    if (runtime_class == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return;
    }
    const JNINativeMethod method{
        const_cast<char*>("nativeExit"),
        const_cast<char*>("(I)V"),
        reinterpret_cast<void*>(suppress_runtime_native_exit)
    };
    const jint result = env->RegisterNatives(runtime_class, &method, 1);
    if (env->ExceptionCheck()) env->ExceptionClear();
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "contained Runtime.nativeExit installed=%d",
        static_cast<int>(result)
    );
    env->DeleteLocalRef(runtime_class);
}

void virtualize_environment_path(const char* name) {
    if (name == nullptr) return;
    const char* current = getenv(name);
    if (current == nullptr || current[0] == '\0') return;
    char mapped[PATH_MAX] = {};
    ssize_t mapped_length = -1;
    if (!nestspace::virtual_path_from_actual(
            current,
            mapped,
            sizeof(mapped),
            &mapped_length
        )) {
        return;
    }
    if (setenv(name, mapped, 1) != 0) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "virtualize environment failed name=%s errno=%d",
            name,
            errno
        );
    }
}

void virtualize_process_environment() {
    static constexpr const char* kPathVariables[] = {
        "TMPDIR",
        "TMP",
        "TEMP",
        "HOME",
        "CLASSPATH",
        "LD_PRELOAD",
        "LD_LIBRARY_PATH",
        "JAVA_TOOL_OPTIONS",
    };
    for (const char* name : kPathVariables) {
        virtualize_environment_path(name);
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeSetBinderCallingPid(
    JNIEnv*,
    jclass,
    jint pid
) {
    const jint previous = g_deferred_binder_calling_pid;
    g_deferred_binder_calling_pid = pid > 0 ? pid : 0;
    return previous;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeInstall(
    JNIEnv* env,
    jclass,
    jstring instance_id,
    jstring package_name,
    jstring host_package_name,
    jstring data_root,
    jstring external_data_root,
    jstring process_name,
    jstring host_base_apk_path,
    jstring host_native_library_dir,
    jstring native_library_dir,
    jstring virtual_native_library_dir,
    jobjectArray runtime_library_dirs,
    jobjectArray runtime_library_paths,
    jstring actual_base_apk_path,
    jstring virtual_base_apk_path,
    jobjectArray actual_split_apk_paths,
    jobjectArray virtual_split_apk_paths,
    jobjectArray properties,
    jintArray supplementary_groups,
    jint host_uid,
    jint observed_uid,
    jint virtual_pid,
    jlong identity_revision,
    jlong capability_mask
) {
    auto environment = std::make_unique<nestspace::Environment>();
    environment->instance_id = to_string(env, instance_id);
    environment->package_name = to_string(env, package_name);
    environment->host_package_name = to_string(env, host_package_name);
    environment->data_root = to_string(env, data_root);
    environment->external_data_root = to_string(env, external_data_root);
    environment->process_name = to_string(env, process_name);
    environment->host_base_apk_path = to_string(env, host_base_apk_path);
    environment->host_native_library_dir = to_string(
        env,
        host_native_library_dir
    );
    environment->native_library_dir = to_string(env, native_library_dir);
    environment->virtual_native_library_dir = to_string(
        env,
        virtual_native_library_dir
    );
    environment->runtime_library_dirs = to_strings(
        env,
        runtime_library_dirs
    );
    environment->runtime_library_paths = to_strings(
        env,
        runtime_library_paths
    );
    environment->actual_base_apk_path = to_string(env, actual_base_apk_path);
    environment->virtual_base_apk_path = to_string(env, virtual_base_apk_path);
    environment->actual_split_apk_paths = to_strings(
        env,
        actual_split_apk_paths
    );
    environment->virtual_split_apk_paths = to_strings(
        env,
        virtual_split_apk_paths
    );
    environment->supplementary_groups = to_ints(
        env,
        supplementary_groups
    );
    environment->target_data_root =
        "/data/user/0/" + environment->package_name;
    environment->target_data_alias =
        "/data/data/" + environment->package_name;
    environment->target_device_root =
        "/data/user_de/0/" + environment->package_name;
    environment->target_external_root =
        "/storage/emulated/0/Android/data/" + environment->package_name;
    environment->target_external_alias =
        "/sdcard/Android/data/" + environment->package_name;
    environment->virtual_runtime_library_dirs.clear();
    environment->virtual_runtime_library_dirs.reserve(
        environment->runtime_library_dirs.size()
    );
    for (const auto& directory : environment->runtime_library_dirs) {
        if (directory.rfind(environment->data_root, 0) == 0) {
            environment->virtual_runtime_library_dirs.push_back(
                environment->target_data_root +
                    directory.substr(environment->data_root.size())
            );
        } else {
            environment->virtual_runtime_library_dirs.push_back(directory);
        }
    }
    environment->virtual_runtime_library_paths.clear();
    environment->virtual_runtime_library_paths.reserve(
        environment->runtime_library_paths.size()
    );
    for (const auto& path : environment->runtime_library_paths) {
        if (path.rfind(environment->data_root, 0) == 0) {
            environment->virtual_runtime_library_paths.push_back(
                environment->target_data_root +
                    path.substr(environment->data_root.size())
            );
        } else {
            environment->virtual_runtime_library_paths.push_back(path);
        }
    }
    environment->actual_device_root =
        environment->data_root + "/device_protected";
    environment->native_library_dir_alias =
        data_alias(environment->native_library_dir);
    environment->data_root_alias = data_alias(environment->data_root);
    environment->property_snapshot = nestspace::build_property_snapshot(
        to_properties(env, properties)
    );
    environment->host_uid = host_uid;
    environment->observed_uid = observed_uid;
    environment->virtual_pid = virtual_pid;
    environment->identity_revision = identity_revision;
    environment->capability_mask = capability_mask;

    nestspace::restore_hooks();
    nestspace::publish_environment(std::move(environment));
    nestspace::initialize_identity_syscall_ranges();
    virtualize_process_environment();
    char disable_binder_identity[PROP_VALUE_MAX] = {};
    __system_property_get(
        "debug.nestspace.disable_binder_identity",
        disable_binder_identity
    );
    if (strcmp(disable_binder_identity, "1") != 0) {
        install_identity_jni_hooks(env);
    }
    install_runtime_exit_guard(env);
    nestspace::install_jni_registration_hook(env);
    nestspace::refresh_hooks(false);
    static std::mutex watcher_lock;
    static bool watcher_started = false;
    {
        std::lock_guard<std::mutex> guard(watcher_lock);
        if (!watcher_started) {
            watcher_started = true;
            std::thread([] {
                const auto deadline = std::chrono::steady_clock::now() +
                    std::chrono::seconds(15);
                while (nestspace::current_environment() != nullptr &&
                       std::chrono::steady_clock::now() < deadline) {
                    nestspace::refresh_loaded_modules();
                    if (nestspace::hook_state().target_modules > 0) break;
                    std::this_thread::sleep_for(std::chrono::milliseconds(10));
                }
            }).detach();
        }
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeIdentity(
    JNIEnv* env,
    jclass
) {
    const nestspace::Environment* environment = nestspace::current_environment();
    jint values[4] = {
        environment ? environment->host_uid : -1,
        environment ? environment->observed_uid : -1,
        environment ? environment->virtual_pid : -1,
        static_cast<jint>(getuid()),
    };
    jintArray result = env->NewIntArray(4);
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, 4, values);
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeProcessName(
    JNIEnv* env,
    jclass
) {
    const nestspace::Environment* environment = nestspace::current_environment();
    if (environment == nullptr) return nullptr;
    return env->NewStringUTF(environment->process_name.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativePageSize(
    JNIEnv*,
    jclass
) {
    const long page_size = sysconf(_SC_PAGESIZE);
    return page_size > 0 ? static_cast<jlong>(page_size) : 0;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeProbe(
    JNIEnv* env,
    jclass
) {
    const long page_size = sysconf(_SC_PAGESIZE);
    jlong flags = 0;
    if (nestspace::can_open_for_read("/proc/self/status")) {
        flags |= nestspace::kProbeStatusReadable;
    }
    if (nestspace::can_open_for_read("/proc/self/maps")) {
        flags |= nestspace::kProbeMapsReadable;
    }
    if (nestspace::can_open_for_read("/proc/self/mountinfo")) {
        flags |= nestspace::kProbeMountInfoReadable;
    }
    if (nestspace::can_open_for_read("/proc/self/attr/current")) {
        flags |= nestspace::kProbeSelinuxReadable;
    }
    if (nestspace::can_syscall_open_for_read("/proc/self/status")) {
        flags |= nestspace::kProbeDirectOpenReadable;
    }
    if (nestspace::can_syscall_stat("/proc/self/status")) {
        flags |= nestspace::kProbeDirectStatReadable;
    }

    char abi[PROP_VALUE_MAX] = {};
    const int abi_length = __system_property_get("ro.product.cpu.abi", abi);
    if (abi_length > 0 && abi_length < PROP_VALUE_MAX) {
        flags |= nestspace::kProbePropertyReadable;
    }

    const jlong values[6] = {
        static_cast<jlong>(getuid()),
        static_cast<jlong>(syscall(SYS_getuid)),
        static_cast<jlong>(nestspace::read_status_uid()),
        page_size > 0 ? static_cast<jlong>(page_size) : 0,
        flags,
        0,
    };
    jlongArray result = env->NewLongArray(6);
    if (result != nullptr) {
        env->SetLongArrayRegion(result, 0, 6, values);
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeProductAbi(
    JNIEnv* env,
    jclass
) {
    char abi[PROP_VALUE_MAX] = {};
    const int length = __system_property_get("ro.product.cpu.abi", abi);
    if (length <= 0 || length >= PROP_VALUE_MAX) return nullptr;
    return env->NewStringUTF(abi);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeRewritePath(
    JNIEnv* env,
    jclass,
    jstring path
) {
    const std::string input = to_string(env, path);
    std::string rewritten;
    if (!nestspace::rewrite_target_path(input.c_str(), &rewritten)) return nullptr;
    return env->NewStringUTF(rewritten.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeReadVirtualProc(
    JNIEnv* env,
    jclass,
    jstring path
) {
    const std::string input = to_string(env, path);
    const std::string content = nestspace::virtualized_proc_content(input.c_str());
    if (content.empty()) return nullptr;
    return env->NewStringUTF(content.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeReadProperty(
    JNIEnv* env,
    jclass,
    jstring name
) {
    const std::string key = to_string(env, name);
    char value[PROP_VALUE_MAX] = {};
    if (nestspace::hook_property_get(key.c_str(), value) <= 0) return nullptr;
    return env->NewStringUTF(value);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeReadPropertyCallback(
    JNIEnv* env,
    jclass,
    jstring name
) {
    const std::string key = to_string(env, name);
    const void* property = nestspace::hook_property_find(key.c_str());
    if (property == nullptr) return nullptr;
    PropertyCallbackCookie cookie;
    nestspace::hook_property_read_callback(
        property,
        property_callback_probe,
        &cookie
    );
    if (cookie.value.empty()) return nullptr;
    return env->NewStringUTF(cookie.value.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeIdentityRevision(
    JNIEnv*,
    jclass
) {
    const nestspace::Environment* environment = nestspace::current_environment();
    return environment ? environment->identity_revision : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeCapabilityMask(
    JNIEnv*,
    jclass
) {
    const nestspace::Environment* environment = nestspace::current_environment();
    return environment ? environment->capability_mask : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativePublishIdentity(
    JNIEnv*,
    jclass,
    jlong revision
) {
    const nestspace::Environment* current = nestspace::current_environment();
    if (current == nullptr || revision < current->identity_revision) {
        return JNI_FALSE;
    }
    if (revision == current->identity_revision) return JNI_TRUE;
    auto updated = std::make_unique<nestspace::Environment>(*current);
    updated->identity_revision = revision;
    nestspace::publish_environment(std::move(updated));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeRefreshHooks(
    JNIEnv* env,
    jclass,
    jboolean scan_elf_fallback
) {
    return hook_state_array(
        env,
        nestspace::refresh_hooks(scan_elf_fallback == JNI_TRUE)
    );
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeHookSnapshot(
    JNIEnv* env,
    jclass
) {
    return hook_state_array(env, nestspace::hook_state());
}

extern "C" JNIEXPORT void JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeInstallCrashTrace(
    JNIEnv* env,
    jobject,
    jstring path
) {
    const std::string content = to_string(env, path);
    install_native_crash_trace(content.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeSetPackerGuard(
    JNIEnv*,
    jclass,
    jboolean enabled
) {
    set_native_packer_guard(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeInstallKillGuard(
    JNIEnv*,
    jobject
) {
    return install_native_kill_guard() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeInstallSyscallGateway(
    JNIEnv*,
    jclass
) {
    return install_native_syscall_gateway() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeClear(
    JNIEnv*,
    jclass
) {
    nestspace::clear_environment();
    nestspace::restore_hooks();
}
