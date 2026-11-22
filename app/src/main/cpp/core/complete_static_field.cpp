#include <android/log.h>
#include <cstring>
#include <jni.h>
#include <memory>
#include <mutex>
#include <string>
#include <sys/mman.h>
#include <sys/system_properties.h>
#include <unordered_map>
#include <unistd.h>
#include <vector>

#include "nestspace/native_environment_internal.h"

extern "C" bool nestspace_is_runtime_field_code(uintptr_t address);

namespace {

constexpr char kTag[] = "CompleteStaticField";

using GetStaticFieldIdFunction =
    decltype(JNINativeInterface::GetStaticFieldID);
using GetFieldIdFunction =
    decltype(JNINativeInterface::GetFieldID);
using GetIntFieldFunction =
    decltype(JNINativeInterface::GetIntField);
using GetStaticIntFieldFunction =
    decltype(JNINativeInterface::GetStaticIntField);
using SetIntFieldFunction =
    decltype(JNINativeInterface::SetIntField);
using SetStaticIntFieldFunction =
    decltype(JNINativeInterface::SetStaticIntField);
using GetObjectFieldFunction =
    decltype(JNINativeInterface::GetObjectField);
using GetStaticObjectFieldFunction =
    decltype(JNINativeInterface::GetStaticObjectField);

GetStaticFieldIdFunction g_original_get_static_field_id = nullptr;
GetFieldIdFunction g_original_get_field_id = nullptr;
GetIntFieldFunction g_original_get_int_field = nullptr;
GetStaticIntFieldFunction g_original_get_static_int_field = nullptr;
SetIntFieldFunction g_original_set_int_field = nullptr;
SetStaticIntFieldFunction g_original_set_static_int_field = nullptr;
GetObjectFieldFunction g_original_get_object_field = nullptr;
GetStaticObjectFieldFunction g_original_get_static_object_field = nullptr;
jclass g_bridge_class = nullptr;
jmethodID g_resolve_method = nullptr;
jmethodID g_describe_method = nullptr;
std::mutex g_route_mutex;
std::unordered_map<jfieldID, jclass> g_static_field_routes;
std::unordered_map<jfieldID, jfieldID> g_projected_static_targets;
std::vector<std::unique_ptr<uint8_t[]>> g_instance_field_projections;
jfieldID g_framework_instance_int_field = nullptr;
thread_local bool g_static_instance_view_pending = false;
thread_local jfieldID g_bridged_instance_field = nullptr;
thread_local std::string g_bridged_instance_name;
thread_local std::string g_bridged_instance_signature;

uint32_t make_field_instance(jfieldID field) {
    if (field == nullptr) return 0;
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return 0;
    auto* access_flags = reinterpret_cast<uint32_t*>(
        reinterpret_cast<uintptr_t>(field) + sizeof(uint32_t)
    );
    const uintptr_t page =
        reinterpret_cast<uintptr_t>(access_flags) &
        ~(static_cast<uintptr_t>(page_size) - 1);
    const int previous_protection = PROT_READ | PROT_WRITE;
    if (mprotect(
            reinterpret_cast<void*>(page),
            static_cast<size_t>(page_size),
            previous_protection
        ) != 0) {
        return 0;
    }
    const uint32_t previous = *access_flags;
    *access_flags = previous & ~0x0008U;
    mprotect(
        reinterpret_cast<void*>(page),
        static_cast<size_t>(page_size),
        PROT_READ
    );
    return previous;
}

jfieldID resolve_complete_field(
    JNIEnv* env,
    jclass clazz,
    const char* name,
    const char* signature,
    bool is_static
) {
    auto get_original = [is_static](
        JNIEnv* target_env,
        jclass target_class,
        const char* target_name,
        const char* target_signature
    ) {
        return is_static
            ? g_original_get_static_field_id(
                target_env,
                target_class,
                target_name,
                target_signature
            )
            : g_original_get_field_id(
                target_env,
                target_class,
                target_name,
                target_signature
            );
    };
    jfieldID result = get_original(env, clazz, name, signature);
    if (result != nullptr || env == nullptr || clazz == nullptr ||
        name == nullptr || signature == nullptr ||
        g_bridge_class == nullptr || g_resolve_method == nullptr) {
        return result;
    }
    const uintptr_t caller = reinterpret_cast<uintptr_t>(
        __builtin_return_address(0)
    );
    if (!nestspace_is_runtime_field_code(caller)) {
        return result;
    }
    __android_log_print(
        ANDROID_LOG_DEBUG,
        kTag,
        "%s field missing name=%s signature=%s caller=%p",
        is_static ? "static" : "instance",
        name,
        signature,
        reinterpret_cast<void*>(caller)
    );
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (g_bridged_instance_field != nullptr &&
        name == g_bridged_instance_name &&
        signature == g_bridged_instance_signature) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kTag,
            "reused bridged static field=%s signature=%s",
            name,
            signature
        );
        return g_bridged_instance_field;
    }
    if (!is_static && g_original_get_static_field_id != nullptr) {
        jfieldID static_field = g_original_get_static_field_id(
            env,
            clazz,
            name,
            signature
        );
        if (static_field == nullptr && env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        if (static_field != nullptr) {
            {
                std::lock_guard<std::mutex> lock(g_route_mutex);
                auto found = g_static_field_routes.find(static_field);
                if (found != g_static_field_routes.end()) {
                    env->DeleteGlobalRef(found->second);
                    g_static_field_routes.erase(found);
                }
                jclass routed = static_cast<jclass>(
                    env->NewGlobalRef(clazz)
                );
                if (routed != nullptr) {
                    g_static_field_routes[static_field] = routed;
                }
            }
            g_bridged_instance_field = static_field;
            g_bridged_instance_name = name;
            g_bridged_instance_signature = signature;
            if (env->ExceptionCheck()) env->ExceptionClear();
            __android_log_print(
                ANDROID_LOG_DEBUG,
                kTag,
                "resolved same-class static view field=%s signature=%s "
                "as native static field",
                name,
                signature
            );
            return static_field;
        }
    }
    if (g_describe_method != nullptr) {
        auto description = static_cast<jstring>(
            env->CallStaticObjectMethod(
                g_bridge_class,
                g_describe_method,
                clazz,
                env->NewStringUTF(name)
            )
        );
        const char* text = description == nullptr
            ? nullptr
            : env->GetStringUTFChars(description, nullptr);
        if (text != nullptr) {
            __android_log_print(
                ANDROID_LOG_DEBUG,
                kTag,
                "%s",
                text
            );
            env->ReleaseStringUTFChars(description, text);
        }
        if (description != nullptr) env->DeleteLocalRef(description);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    auto complete_class = static_cast<jclass>(
        env->CallStaticObjectMethod(
            g_bridge_class,
            g_resolve_method,
            clazz,
            env->NewStringUTF(name),
            env->NewStringUTF(signature)
        )
    );
    if (complete_class != nullptr) {
        result = get_original(env, complete_class, name, signature);
        if (result != nullptr) {
            {
                std::lock_guard<std::mutex> lock(g_route_mutex);
                auto found = g_static_field_routes.find(result);
                if (found != g_static_field_routes.end()) {
                    env->DeleteGlobalRef(found->second);
                    g_static_field_routes.erase(found);
                }
            }
            env->DeleteLocalRef(complete_class);
            __android_log_print(
                ANDROID_LOG_DEBUG,
                kTag,
                "resolved %s field=%s signature=%s",
                is_static ? "static" : "instance",
                name,
                signature
            );
            return result;
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        result = is_static
            ? g_original_get_field_id(
                env,
                complete_class,
                name,
                signature
            )
            : g_original_get_static_field_id(
                env,
                complete_class,
                name,
                signature
            );
        if (result != nullptr) {
            if (is_static) {
                jclass routed = static_cast<jclass>(
                    env->NewGlobalRef(complete_class)
                );
                if (routed != nullptr) {
                    std::lock_guard<std::mutex> lock(g_route_mutex);
                    g_static_field_routes[result] = routed;
                }
            } else {
                const uint32_t previous_flags = make_field_instance(result);
                __android_log_print(
                    ANDROID_LOG_DEBUG,
                    kTag,
                    "instance field route adjusted access=%08x",
                    previous_flags
                );
            }
            env->DeleteLocalRef(complete_class);
            __android_log_print(
                ANDROID_LOG_DEBUG,
                kTag,
                "resolved opposite %s field=%s signature=%s",
                is_static ? "instance" : "static",
                name,
                signature
            );
            return result;
        }
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kTag,
            "complete class has no %s field name=%s signature=%s",
            is_static ? "static" : "instance",
            name,
            signature
        );
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(complete_class);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    return get_original(env, clazz, name, signature);
}

jfieldID complete_static_field_id(
    JNIEnv* env,
    jclass clazz,
    const char* name,
    const char* signature
) {
    return resolve_complete_field(env, clazz, name, signature, true);
}

extern "C" jfieldID complete_instance_field_id(
    JNIEnv* env,
    jclass clazz,
    const char* name,
    const char* signature
) {
    return resolve_complete_field(env, clazz, name, signature, false);
}

jint routed_int_field(JNIEnv* env, jobject object, jfieldID field) {
    jclass static_class = nullptr;
    jfieldID static_field = field;
    {
        std::lock_guard<std::mutex> lock(g_route_mutex);
        auto found = g_static_field_routes.find(field);
        if (found != g_static_field_routes.end()) {
            static_class = found->second;
            auto projected = g_projected_static_targets.find(field);
            if (projected != g_projected_static_targets.end()) {
                static_field = projected->second;
            }
        }
    }
    if (static_class != nullptr &&
        g_original_get_static_int_field != nullptr) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kTag,
            "routed instance int read through static field"
        );
        return g_original_get_static_int_field(
            env,
            static_class,
            static_field
        );
    }
    return g_original_get_int_field(env, object, field);
}

jint routed_static_int_field(
    JNIEnv* env,
    jclass clazz,
    jfieldID field
) {
    jclass static_class = nullptr;
    jfieldID static_field = field;
    {
        std::lock_guard<std::mutex> lock(g_route_mutex);
        auto found = g_static_field_routes.find(field);
        if (found != g_static_field_routes.end()) {
            static_class = found->second;
            auto projected = g_projected_static_targets.find(field);
            if (projected != g_projected_static_targets.end()) {
                static_field = projected->second;
            }
        }
    }
    if (static_class != nullptr &&
        g_original_get_static_int_field != nullptr) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kTag,
            "routed static int read class=%p field=%p target=%p",
            static_class,
            field,
            static_field
        );
        return g_original_get_static_int_field(
            env,
            static_class,
            static_field
        );
    }
    return g_original_get_static_int_field(env, clazz, static_field);
}

void routed_set_int_field(
    JNIEnv* env,
    jobject object,
    jfieldID field,
    jint value
) {
    jclass static_class = nullptr;
    jfieldID static_field = field;
    {
        std::lock_guard<std::mutex> lock(g_route_mutex);
        auto found = g_static_field_routes.find(field);
        if (found != g_static_field_routes.end()) {
            static_class = found->second;
            auto projected = g_projected_static_targets.find(field);
            if (projected != g_projected_static_targets.end()) {
                static_field = projected->second;
            }
        }
    }
    if (static_class != nullptr &&
        g_original_set_static_int_field != nullptr) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kTag,
            "routed instance int write through static field"
        );
        g_original_set_static_int_field(
            env,
            static_class,
            static_field,
            value
        );
        return;
    }
    g_original_set_int_field(env, object, field, value);
}

void routed_set_static_int_field(
    JNIEnv* env,
    jclass clazz,
    jfieldID field,
    jint value
) {
    jclass static_class = nullptr;
    jfieldID static_field = field;
    {
        std::lock_guard<std::mutex> lock(g_route_mutex);
        auto found = g_static_field_routes.find(field);
        if (found != g_static_field_routes.end()) {
            static_class = found->second;
            auto projected = g_projected_static_targets.find(field);
            if (projected != g_projected_static_targets.end()) {
                static_field = projected->second;
            }
        }
    }
    g_original_set_static_int_field(
        env,
        static_class != nullptr ? static_class : clazz,
        static_field,
        value
    );
}

jobject routed_get_object_field(
    JNIEnv* env,
    jobject object,
    jfieldID field
) {
    jclass static_class = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_route_mutex);
        auto found = g_static_field_routes.find(field);
        if (found != g_static_field_routes.end()) {
            static_class = found->second;
        }
    }
    if (static_class != nullptr &&
        g_original_get_static_object_field != nullptr) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kTag,
            "routed instance object read through static field"
        );
        return g_original_get_static_object_field(
            env,
            static_class,
            field
        );
    }
    return g_original_get_object_field(env, object, field);
}

jobject routed_get_static_object_field(
    JNIEnv* env,
    jclass clazz,
    jfieldID field
) {
    jclass static_class = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_route_mutex);
        auto found = g_static_field_routes.find(field);
        if (found != g_static_field_routes.end()) {
            static_class = found->second;
        }
    }
    return g_original_get_static_object_field(
        env,
        static_class != nullptr ? static_class : clazz,
        field
    );
}

}  // namespace

extern "C" bool nestspace_consume_static_instance_view() {
    if (!g_static_instance_view_pending) return false;
    g_static_instance_view_pending = false;
    return true;
}

extern "C" void nestspace_mark_static_instance_view() {
    g_static_instance_view_pending = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeInstallCompleteStaticFieldResolver(
    JNIEnv* env,
    jobject
) {
    if (env == nullptr || env->functions == nullptr) return;
    static std::once_flag once;
    std::call_once(once, [env] {
        jclass bridge = env->FindClass(
            "com/coke/nestspace/container/loader/CompleteClassBridge"
        );
        if (bridge == nullptr) {
            env->ExceptionClear();
            return;
        }
        jmethodID resolve = env->GetStaticMethodID(
            bridge,
            "resolve",
            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)"
            "Ljava/lang/Class;"
        );
        if (resolve == nullptr) {
            env->ExceptionClear();
            env->DeleteLocalRef(bridge);
            return;
        }
        jmethodID describe = env->GetStaticMethodID(
            bridge,
            "describe",
            "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/String;"
        );
        if (describe == nullptr) {
            env->ExceptionClear();
            env->DeleteLocalRef(bridge);
            return;
        }
        g_bridge_class = static_cast<jclass>(env->NewGlobalRef(bridge));
        g_resolve_method = resolve;
        g_describe_method = describe;
        env->DeleteLocalRef(bridge);

        void* trampoline = nullptr;
        const bool installed = nestspace::install_shared_inline_hook(
            reinterpret_cast<void*>(env->functions->GetStaticFieldID),
            reinterpret_cast<void*>(complete_static_field_id),
            &trampoline
        );
        g_original_get_static_field_id =
            reinterpret_cast<GetStaticFieldIdFunction>(trampoline);
        void* instance_trampoline = nullptr;
        const bool instance_installed = nestspace::install_shared_inline_hook(
            reinterpret_cast<void*>(env->functions->GetFieldID),
            reinterpret_cast<void*>(complete_instance_field_id),
            &instance_trampoline
        );
        g_original_get_field_id =
            reinterpret_cast<GetFieldIdFunction>(instance_trampoline);
        jclass activity_class = env->FindClass("android/app/Activity");
        if (activity_class != nullptr &&
            g_original_get_field_id != nullptr) {
            g_framework_instance_int_field = g_original_get_field_id(
                env,
                activity_class,
                "mResultCode",
                "I"
            );
            env->DeleteLocalRef(activity_class);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kTag,
            "framework int projection field=%p",
            g_framework_instance_int_field
        );
        void* static_int_trampoline = nullptr;
        const bool static_int_installed =
            nestspace::install_shared_inline_hook(
                reinterpret_cast<void*>(env->functions->GetStaticIntField),
                reinterpret_cast<void*>(routed_static_int_field),
                &static_int_trampoline
            );
        g_original_get_static_int_field =
            reinterpret_cast<GetStaticIntFieldFunction>(
                static_int_trampoline
            );
        void* set_int_trampoline = nullptr;
        const bool set_int_installed =
            nestspace::install_shared_inline_hook(
                reinterpret_cast<void*>(env->functions->SetIntField),
                reinterpret_cast<void*>(routed_set_int_field),
                &set_int_trampoline
            );
        g_original_set_int_field =
            reinterpret_cast<SetIntFieldFunction>(set_int_trampoline);
        void* set_static_int_trampoline = nullptr;
        const bool set_static_int_installed =
            nestspace::install_shared_inline_hook(
                reinterpret_cast<void*>(env->functions->SetStaticIntField),
                reinterpret_cast<void*>(routed_set_static_int_field),
                &set_static_int_trampoline
            );
        g_original_set_static_int_field =
            reinterpret_cast<SetStaticIntFieldFunction>(
                set_static_int_trampoline
            );
        void* object_trampoline = nullptr;
        const bool object_installed =
            nestspace::install_shared_inline_hook(
                reinterpret_cast<void*>(env->functions->GetObjectField),
                reinterpret_cast<void*>(routed_get_object_field),
                &object_trampoline
            );
        g_original_get_object_field =
            reinterpret_cast<GetObjectFieldFunction>(object_trampoline);
        void* static_object_trampoline = nullptr;
        const bool static_object_installed =
            nestspace::install_shared_inline_hook(
                reinterpret_cast<void*>(
                    env->functions->GetStaticObjectField
                ),
                reinterpret_cast<void*>(routed_get_static_object_field),
                &static_object_trampoline
            );
        g_original_get_static_object_field =
            reinterpret_cast<GetStaticObjectFieldFunction>(
                static_object_trampoline
            );
        void* int_trampoline = nullptr;
        const bool int_installed = nestspace::install_shared_inline_hook(
            reinterpret_cast<void*>(env->functions->GetIntField),
            reinterpret_cast<void*>(routed_int_field),
            &int_trampoline
        );
        g_original_get_int_field =
            reinterpret_cast<GetIntFieldFunction>(int_trampoline);
        char probe[PROP_VALUE_MAX] = {};
        __system_property_get(
            "debug.nestspace.probe_field_hook",
            probe
        );
        if (strcmp(probe, "1") == 0) {
            jclass integer_class = env->FindClass("java/lang/Integer");
            if (integer_class != nullptr) {
                env->GetStaticFieldID(
                    integer_class,
                    "__nestspace_probe_missing__",
                    "I"
                );
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->DeleteLocalRef(integer_class);
            }
        }
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kTag,
            "resolver installed instance=%d static=%d int=%d "
            "staticInt=%d setInt=%d staticSetInt=%d object=%d "
            "staticObject=%d",
            instance_installed ? 1 : 0,
            installed ? 1 : 0,
            int_installed ? 1 : 0,
            static_int_installed ? 1 : 0,
            set_int_installed ? 1 : 0,
            set_static_int_installed ? 1 : 0,
            object_installed ? 1 : 0,
            static_object_installed ? 1 : 0
        );
    });
}
