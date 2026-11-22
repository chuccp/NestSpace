#include <android/log.h>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <jni.h>
#include <atomic>
#include <mutex>
#include <string>
#include <sys/system_properties.h>
#include <sys/uio.h>
#include <thread>
#include <unwind.h>
#include <unistd.h>

#include "nestspace/native_environment_internal.h"

namespace {

using ThrowFunction = decltype(JNINativeInterface::Throw);
using ThrowNewFunction = decltype(JNINativeInterface::ThrowNew);
using RuntimeFieldResolverFunction = uintptr_t (*)(
    uintptr_t,
    uintptr_t,
    uintptr_t,
    uintptr_t,
    uintptr_t
);

extern "C" void runtime_field_resolver_entry();

ThrowFunction g_original_throw = nullptr;
ThrowNewFunction g_original_throw_new = nullptr;
RuntimeFieldResolverFunction g_original_runtime_field_resolver = nullptr;
std::atomic<bool> g_runtime_field_watch_started{false};
std::atomic<uintptr_t> g_runtime_field_resolver_address{0};
std::atomic<uintptr_t> g_runtime_field_operation_address{0};
thread_local int g_runtime_field_resolution_depth = 0;
thread_local bool g_static_field_retry_pending = false;

constexpr char kTag[] = "NestSpaceJniCompat";

uintptr_t find_anonymous_instruction_sequence(
    const uint32_t* sequence,
    size_t word_count,
    const char* label
) {
    if (sequence == nullptr || word_count == 0) return 0;
    FILE* maps = fopen("/proc/self/maps", "re");
    if (maps == nullptr) return 0;
    char line[2048];
    uintptr_t result = 0;
    uintptr_t first_word_match = 0;
    size_t mapping_count = 0;
    while (fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long begin_value = 0;
        unsigned long long end_value = 0;
        char permissions[8] = {};
        if (sscanf(
                line,
                "%llx-%llx %7s",
                &begin_value,
                &end_value,
                permissions
            ) != 3 ||
            permissions[0] != 'r' ||
            permissions[2] != 'x' ||
            end_value <= begin_value) {
            continue;
        }
        const bool anonymous_runtime_code =
            strstr(line, "[anon:.bss]") != nullptr ||
            (strchr(line, '/') == nullptr &&
                strchr(line, '[') == nullptr);
        if (!anonymous_runtime_code) continue;
        ++mapping_count;
        const uintptr_t begin = static_cast<uintptr_t>(begin_value);
        const uintptr_t end = static_cast<uintptr_t>(end_value);
        const size_t needle_size = word_count * sizeof(uint32_t);
        if (end - begin < needle_size) continue;
        for (uintptr_t address = begin;
             address + needle_size <= end;
             address += sizeof(uint32_t)) {
            uint32_t first_word = 0;
            memcpy(
                &first_word,
                reinterpret_cast<const void*>(address),
                sizeof(first_word)
            );
            if (first_word == sequence[0] && first_word_match == 0) {
                first_word_match = address;
            }
            if (memcmp(
                    reinterpret_cast<const void*>(address),
                    sequence,
                    needle_size
                ) == 0) {
                result = address;
                break;
            }
        }
        if (result != 0) break;
    }
    fclose(maps);
    if (result == 0) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kTag,
            "%s scan mappings=%zu firstWord=%p",
            label,
            mapping_count,
            reinterpret_cast<void*>(first_word_match)
        );
    }
    return result;
}

uintptr_t find_runtime_field_resolver() {
    const uintptr_t cached =
        g_runtime_field_resolver_address.load(std::memory_order_acquire);
    if (cached != 0) return cached;
    constexpr uint32_t kPrologue[] = {
        0xf81b0ffaU,
        0xa90163f9U,
        0xa9025bf7U,
        0xa90353f5U,
        0xa9047bf3U,
        0x9102c013U,
        0xaa0003f4U,
        0xaa1303e0U,
        0x2a0403f9U,
        0x2a0303f5U,
        0xaa0203f6U,
        0xaa0103f7U
    };
    const uintptr_t result = find_anonymous_instruction_sequence(
        kPrologue,
        sizeof(kPrologue) / sizeof(kPrologue[0]),
        "runtime resolver"
    );
    if (result != 0) {
        g_runtime_field_resolver_address.store(
            result,
            std::memory_order_release
        );
    }
    return result;
}

uintptr_t find_runtime_field_operation() {
    const uintptr_t cached =
        g_runtime_field_operation_address.load(std::memory_order_acquire);
    if (cached != 0) return cached;
    constexpr uint32_t kPrologue[] = {
        0xd102c3ffU,
        0xa9056ffcU,
        0xa90667faU,
        0xa9075ff8U,
        0xa90857f6U,
        0xa9094ff4U,
        0xa90a7bfdU,
        0xd53bd048U,
        0xf90007e8U,
        0xf9401508U
    };
    const uintptr_t result = find_anonymous_instruction_sequence(
        kPrologue,
        sizeof(kPrologue) / sizeof(kPrologue[0]),
        "runtime field operation"
    );
    if (result != 0) {
        g_runtime_field_operation_address.store(
            result,
            std::memory_order_release
        );
    }
    return result;
}

void watch_runtime_field_resolver() {
    for (int attempt = 0; attempt < 2000; ++attempt) {
        const uintptr_t resolver = find_runtime_field_resolver();
        if (resolver != 0) {
            void* trampoline = nullptr;
            const bool hooked = nestspace::install_shared_inline_hook(
                reinterpret_cast<void*>(resolver),
                reinterpret_cast<void*>(runtime_field_resolver_entry),
                &trampoline
            );
            g_original_runtime_field_resolver =
                reinterpret_cast<RuntimeFieldResolverFunction>(
                    trampoline
                );
            __android_log_print(
                ANDROID_LOG_WARN,
                kTag,
                "runtime field resolver=%p hooked=%d",
                reinterpret_cast<void*>(resolver),
                hooked ? 1 : 0
            );
            return;
        }
        usleep(5000);
    }
    __android_log_print(
        ANDROID_LOG_WARN,
        kTag,
        "runtime field resolver was not discovered"
    );
}

int recover_field_value(
    JNIEnv* env,
    jthrowable throwable,
    bool return_value
) {
    if (env == nullptr || throwable == nullptr) return -1;
    jclass throwable_class = env->GetObjectClass(throwable);
    jmethodID get_message = throwable_class == nullptr
        ? nullptr
        : env->GetMethodID(
            throwable_class,
            "getMessage",
            "()Ljava/lang/String;"
        );
    auto message_object = get_message == nullptr
        ? static_cast<jstring>(nullptr)
        : static_cast<jstring>(
            env->CallObjectMethod(throwable, get_message)
        );
    const char* message = message_object == nullptr
        ? nullptr
        : env->GetStringUTFChars(message_object, nullptr);
    if (message == nullptr) {
        if (throwable_class != nullptr) env->DeleteLocalRef(throwable_class);
        return -1;
    }

    const char* field_marker = strstr(message, " field \"");
    const char* class_marker = strstr(message, " in class \"L");
    int recovered = -1;
    if (field_marker != nullptr && class_marker != nullptr) {
        const char* field_begin = field_marker + strlen(" field \"");
        const char* field_end = strchr(field_begin, '"');
        const char* class_begin = class_marker + strlen(" in class \"L");
        const char* class_end = strchr(class_begin, ';');
        if (field_end != nullptr && class_end != nullptr &&
            field_end > field_begin && class_end > class_begin) {
            const std::string field_name(
                field_begin,
                static_cast<size_t>(field_end - field_begin)
            );
            const std::string class_descriptor(
                class_begin,
                static_cast<size_t>(class_end - class_begin)
            );
            jclass target_class = env->FindClass(class_descriptor.c_str());
            jfieldID target_field = target_class == nullptr
                ? nullptr
                : env->GetStaticFieldID(
                    target_class,
                    field_name.c_str(),
                    "I"
                );
            if (target_field == nullptr && env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            if (target_class != nullptr && target_field != nullptr) {
                if (return_value) {
                    recovered = env->GetStaticIntField(
                        target_class,
                        target_field
                    );
                } else {
                    jclass class_class = env->FindClass("java/lang/Class");
                    jmethodID get_fields = class_class == nullptr
                        ? nullptr
                        : env->GetMethodID(
                            class_class,
                            "getDeclaredFields",
                            "()[Ljava/lang/reflect/Field;"
                        );
                    auto fields = get_fields == nullptr
                        ? static_cast<jobjectArray>(nullptr)
                        : static_cast<jobjectArray>(
                            env->CallObjectMethod(target_class, get_fields)
                        );
                    jclass field_class = env->FindClass(
                        "java/lang/reflect/Field"
                    );
                    jmethodID get_name = field_class == nullptr
                        ? nullptr
                        : env->GetMethodID(
                            field_class,
                            "getName",
                            "()Ljava/lang/String;"
                        );
                    jmethodID get_modifiers = field_class == nullptr
                        ? nullptr
                        : env->GetMethodID(
                            field_class,
                            "getModifiers",
                            "()I"
                        );
                    jclass modifier_class = env->FindClass(
                        "java/lang/reflect/Modifier"
                    );
                    jmethodID is_static = modifier_class == nullptr
                        ? nullptr
                        : env->GetStaticMethodID(
                            modifier_class,
                            "isStatic",
                            "(I)Z"
                        );
                    int static_index = 0;
                    if (fields != nullptr && get_name != nullptr &&
                        get_modifiers != nullptr &&
                        is_static != nullptr) {
                        const jsize count = env->GetArrayLength(fields);
                        for (jsize index = 0; index < count; ++index) {
                            jobject field = env->GetObjectArrayElement(
                                fields,
                                index
                            );
                            if (field == nullptr) continue;
                            const jint modifiers = env->CallIntMethod(
                                field,
                                get_modifiers
                            );
                            if (!env->CallStaticBooleanMethod(
                                    modifier_class,
                                    is_static,
                                    modifiers
                                )) {
                                env->DeleteLocalRef(field);
                                continue;
                            }
                            auto java_name = static_cast<jstring>(
                                env->CallObjectMethod(field, get_name)
                            );
                            const char* name = java_name == nullptr
                                ? nullptr
                                : env->GetStringUTFChars(
                                    java_name,
                                    nullptr
                                );
                            if (name != nullptr && field_name == name) {
                                recovered = static_index;
                            }
                            if (name != nullptr) {
                                env->ReleaseStringUTFChars(
                                    java_name,
                                    name
                                );
                            }
                            if (java_name != nullptr) {
                                env->DeleteLocalRef(java_name);
                            }
                            env->DeleteLocalRef(field);
                            ++static_index;
                            if (recovered >= 0) break;
                        }
                    }
                    if (modifier_class != nullptr) {
                        env->DeleteLocalRef(modifier_class);
                    }
                    if (field_class != nullptr) {
                        env->DeleteLocalRef(field_class);
                    }
                    if (fields != nullptr) env->DeleteLocalRef(fields);
                    if (class_class != nullptr) {
                        env->DeleteLocalRef(class_class);
                    }
                }
            }
            if (target_class != nullptr) {
                env->DeleteLocalRef(target_class);
            }
        }
    }
    env->ReleaseStringUTFChars(message_object, message);
    env->DeleteLocalRef(message_object);
    if (throwable_class != nullptr) env->DeleteLocalRef(throwable_class);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return recovered;
}

}  // namespace

namespace {

struct ThrowNewDecision {
    uintptr_t retry_address;
    uintptr_t flags;
    uintptr_t registers[12];
};

struct UnwindState {
    uintptr_t frames[24];
    size_t count;
};

_Unwind_Reason_Code collect_unwind_frame(
    struct _Unwind_Context* context,
    void* opaque
) {
    auto* state = static_cast<UnwindState*>(opaque);
    if (state == nullptr || state->count >= 24) {
        return _URC_END_OF_STACK;
    }
    const uintptr_t pc = _Unwind_GetIP(context);
    if (pc != 0) state->frames[state->count++] = pc;
    return _URC_NO_REASON;
}

void log_native_stack(const char* reason) {
    UnwindState state{};
    _Unwind_Backtrace(collect_unwind_frame, &state);
    for (size_t index = 0; index < state.count; ++index) {
        Dl_info info{};
        const bool resolved = dladdr(
            reinterpret_cast<void*>(state.frames[index]),
            &info
        ) != 0;
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kTag,
            "%s frame[%zu]=%p module=%s offset=%p",
            reason,
            index,
            reinterpret_cast<void*>(state.frames[index]),
            resolved && info.dli_fname != nullptr ? info.dli_fname : "?",
            resolved && info.dli_fbase != nullptr
                ? reinterpret_cast<void*>(
                    state.frames[index] -
                    reinterpret_cast<uintptr_t>(info.dli_fbase)
                )
                : nullptr
        );
    }
}

extern "C" bool nestspace_consume_static_instance_view();
extern "C" bool nestspace_is_runtime_field_code(uintptr_t address);

extern "C" void handle_throw_new(
    JNIEnv* env,
    jclass clazz,
    const char* message,
    uintptr_t caller,
    uintptr_t flags,
    ThrowNewDecision* decision
) {
    decision->retry_address = 0;
    decision->flags = flags;
    if (g_original_throw_new == nullptr) return;
    if (message != nullptr &&
        (strstr(message, "DoFieldOperate") != nullptr ||
            strstr(message, "AGET_OBJECT") != nullptr)) {
        log_native_stack("field exception stack");
        Dl_info info{};
        const bool resolved = dladdr(
            reinterpret_cast<void*>(caller),
            &info
        ) != 0;
        __android_log_print(
            ANDROID_LOG_WARN,
            kTag,
            "exception message=%s caller=%p offset=%p flags=%p "
            "retry=%p module=%s x19=%p x20=%p x21=%p x22=%p "
            "x23=%p x24=%p x25=%p x26=%p x27=%p x28=%p",
            message,
            reinterpret_cast<void*>(caller),
            reinterpret_cast<void*>(
                resolved && info.dli_fbase != nullptr
                    ? caller - reinterpret_cast<uintptr_t>(info.dli_fbase)
                    : 0
            ),
            reinterpret_cast<void*>(flags),
            reinterpret_cast<void*>(decision->retry_address),
            resolved && info.dli_fname != nullptr
                ? info.dli_fname
                : "?",
            reinterpret_cast<void*>(decision->registers[0]),
            reinterpret_cast<void*>(decision->registers[1]),
            reinterpret_cast<void*>(decision->registers[2]),
            reinterpret_cast<void*>(decision->registers[3]),
            reinterpret_cast<void*>(decision->registers[4]),
            reinterpret_cast<void*>(decision->registers[5]),
            reinterpret_cast<void*>(decision->registers[6]),
            reinterpret_cast<void*>(decision->registers[7]),
            reinterpret_cast<void*>(decision->registers[8]),
            reinterpret_cast<void*>(decision->registers[9])
        );
    }
    if (decision->retry_address == 0) {
        char suppress[PROP_VALUE_MAX] = {};
        __system_property_get(
            "debug.nestspace.suppress_failed_field_exception",
            suppress
        );
        if (strcmp(suppress, "1") == 0 &&
            message != nullptr &&
            strstr(message, "DoFieldOperate") != nullptr) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kTag,
                "suppressed failed field exception message=%s",
                message
            );
            return;
        }
        g_original_throw_new(env, clazz, message);
    }
}

}  // namespace

extern "C" bool nestspace_is_runtime_field_code(uintptr_t address) {
    (void)address;
    return g_runtime_field_resolution_depth > 0;
}

extern "C" bool nestspace_redirect_current_field_to_static() {
#if defined(__aarch64__)
    const uintptr_t resolver = find_runtime_field_resolver();
    const uintptr_t field_operation = find_runtime_field_operation();
    if (resolver == 0 || field_operation == 0) return false;
    uintptr_t stack_pointer = 0;
    __asm__ __volatile__("mov %0, sp" : "=r"(stack_pointer));
    if (stack_pointer == 0) return false;
    const uintptr_t return_address = field_operation + 0xb8;
    constexpr size_t kChunkSize = 0x8000;
    constexpr size_t kScanSize = 0x40000;
    uint8_t chunk[kChunkSize];
    uintptr_t nearby_returns[8] = {};
    size_t nearby_count = 0;
    for (size_t chunk_offset = 0;
         chunk_offset < kScanSize;
         chunk_offset += kChunkSize) {
        struct iovec local {
            chunk,
            kChunkSize
        };
        struct iovec remote {
            reinterpret_cast<void*>(stack_pointer + chunk_offset),
            kChunkSize
        };
        const long count = process_vm_readv(
            getpid(),
            &local,
            1,
            &remote,
            1,
            0
        );
        if (count < static_cast<long>(sizeof(uintptr_t))) break;
        for (size_t offset = 0;
             offset + sizeof(uintptr_t) <= static_cast<size_t>(count);
             offset += sizeof(uintptr_t)) {
            uintptr_t word = 0;
            memcpy(&word, chunk + offset, sizeof(word));
            if (word >= field_operation &&
                word < field_operation + 0x2000 &&
                nearby_count < 8) {
                bool duplicate = false;
                for (size_t index = 0; index < nearby_count; ++index) {
                    if (nearby_returns[index] == word) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    nearby_returns[nearby_count++] = word;
                }
            }
            if (word != return_address) continue;
            const uintptr_t return_slot =
                stack_pointer + chunk_offset + offset;
            // The runtime resolver preserves x23 at sp+0x20. Its saved
            // return address lives at sp+0x40, so from the discovered
            // return-address slot the operation flags are 0x20 bytes
            // earlier. Patching 0x28 hits an unrelated argument slot.
            const uintptr_t flags_address = return_slot - 0x20;
            uintptr_t flags = 0;
            struct iovec flags_local {
                &flags,
                sizeof(flags)
            };
            struct iovec flags_remote {
                reinterpret_cast<void*>(flags_address),
                sizeof(flags)
            };
            if (process_vm_readv(
                    getpid(),
                    &flags_local,
                    1,
                    &flags_remote,
                    1,
                    0
                ) != static_cast<long>(sizeof(flags))) {
                continue;
            }
            __android_log_print(
                ANDROID_LOG_DEBUG,
                kTag,
                "field stack candidate slot=%p flagsSlot=%p flags=%p",
                reinterpret_cast<void*>(return_slot),
                reinterpret_cast<void*>(flags_address),
                reinterpret_cast<void*>(flags)
            );
            uintptr_t updated_flags = 1U;
            struct iovec write_local {
                &updated_flags,
                sizeof(updated_flags)
            };
            struct iovec write_remote {
                reinterpret_cast<void*>(flags_address),
                sizeof(updated_flags)
            };
            if (process_vm_writev(
                    getpid(),
                    &write_local,
                    1,
                    &write_remote,
                    1,
                    0
                ) != static_cast<long>(sizeof(updated_flags))) {
                continue;
            }
            __android_log_print(
                ANDROID_LOG_WARN,
                kTag,
                "redirected field operation to static resolver=%p "
                "fieldOperation=%p returnSlot=%p flags=%p",
                reinterpret_cast<void*>(resolver),
                reinterpret_cast<void*>(field_operation),
                reinterpret_cast<void*>(return_slot),
                reinterpret_cast<void*>(flags)
            );
            char pause_value[PROP_VALUE_MAX] = {};
            __system_property_get(
                "debug.nestspace.field_redirect_pause_ms",
                pause_value
            );
            const long pause_ms = strtol(pause_value, nullptr, 10);
            if (pause_ms > 0) {
                __android_log_print(
                    ANDROID_LOG_WARN,
                    kTag,
                    "pausing after field redirect for %ldms",
                    pause_ms
                );
                usleep(static_cast<useconds_t>(pause_ms) * 1000U);
            }
            return true;
        }
    }
    __android_log_print(
        ANDROID_LOG_DEBUG,
        kTag,
        "field stack redirect miss resolver=%p fieldOperation=%p "
        "sp=%p return=%p",
        reinterpret_cast<void*>(resolver),
        reinterpret_cast<void*>(field_operation),
        reinterpret_cast<void*>(stack_pointer),
        reinterpret_cast<void*>(return_address)
    );
    for (size_t index = 0; index < nearby_count; ++index) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            kTag,
            "field stack nearby=%p fieldOperationOffset=%p",
            reinterpret_cast<void*>(nearby_returns[index]),
            reinterpret_cast<void*>(
                nearby_returns[index] - field_operation
            )
        );
    }
#endif
    return false;
}

extern "C" uintptr_t invoke_runtime_field_resolver(
    uintptr_t first,
    uintptr_t second,
    uintptr_t third,
    uintptr_t fourth,
    uintptr_t fifth
) {
    if (g_original_runtime_field_resolver == nullptr) return 0;
    ++g_runtime_field_resolution_depth;
    uintptr_t result = g_original_runtime_field_resolver(
        first,
        second,
        third,
        fourth,
        fifth
    );
    --g_runtime_field_resolution_depth;
    if (nestspace_consume_static_instance_view()) {
        g_static_field_retry_pending = true;
        ++g_runtime_field_resolution_depth;
        result = g_original_runtime_field_resolver(
            first,
            second,
            third,
            fourth,
            fifth | 1U
        );
        --g_runtime_field_resolution_depth;
    }
    return result;
}

extern "C" bool nestspace_consume_static_retry() {
    if (!g_static_field_retry_pending) return false;
    g_static_field_retry_pending = false;
    return true;
}

extern "C" __attribute__((naked))
void runtime_field_resolver_entry() {
    __asm__ __volatile__(
        "bti c\n"
        "sub sp, sp, #0x30\n"
        "str x30, [sp, #0x20]\n"
        "bl invoke_runtime_field_resolver\n"
        "str x0, [sp, #0x18]\n"
        "bl nestspace_consume_static_retry\n"
        "cbz w0, 1f\n"
        "orr x23, x23, #1\n"
        "1:\n"
        "ldr x0, [sp, #0x18]\n"
        "ldr x30, [sp, #0x20]\n"
        "add sp, sp, #0x30\n"
        "ret\n"
    );
}

extern "C" __attribute__((naked)) void trace_throw_new_entry() {
    __asm__ __volatile__(
        "bti c\n"
        "sub sp, sp, #0xe0\n"
        "stp x19, x20, [sp, #0x50]\n"
        "stp x21, x22, [sp, #0x60]\n"
        "stp x23, x24, [sp, #0x70]\n"
        "stp x25, x26, [sp, #0x80]\n"
        "stp x27, x28, [sp, #0x90]\n"
        "str x29, [sp, #0xd0]\n"
        "str x30, [sp, #0xd8]\n"
        "mov x3, x30\n"
        "mov x4, x23\n"
        "add x5, sp, #0x40\n"
        "bl handle_throw_new\n"
        "ldr x10, [sp, #0x40]\n"
        "ldr x11, [sp, #0x48]\n"
        "ldp x19, x20, [sp, #0x50]\n"
        "ldp x21, x22, [sp, #0x60]\n"
        "ldr x24, [sp, #0x78]\n"
        "ldp x25, x26, [sp, #0x80]\n"
        "ldp x27, x28, [sp, #0x90]\n"
        "cbz x10, 1f\n"
        "mov x23, x11\n"
        "mov x30, x10\n"
        "b 2f\n"
        "1:\n"
        "ldp x23, x24, [sp, #0x70]\n"
        "ldr x30, [sp, #0xd8]\n"
        "2:\n"
        "ldr x29, [sp, #0xd0]\n"
        "add sp, sp, #0xe0\n"
        "ret\n"
    );
}

extern "C" int nestspace_recover_or_throw(
    JNIEnv* env,
    jthrowable throwable,
    void* caller_stack,
    uintptr_t caller_address
) {
    (void)caller_stack;
    char enabled[PROP_VALUE_MAX] = {};
    __system_property_get(
        "debug.nestspace.native_field_fallback",
        enabled
    );
    const bool trace_only = strcmp(enabled, "trace") == 0;
    const bool recover_value = strcmp(enabled, "value") == 0;
    if ((!trace_only && !recover_value && strcmp(enabled, "1") != 0) ||
        env == nullptr || throwable == nullptr) {
        g_original_throw(env, throwable);
        return -2;
    }
    const int recovered = recover_field_value(
        env,
        throwable,
        recover_value
    );
    Dl_info caller_info{};
    const bool resolved = dladdr(
        reinterpret_cast<void*>(caller_address),
        &caller_info
    ) != 0;
    __android_log_print(
        ANDROID_LOG_WARN,
        kTag,
        "field recovery caller=%p offset=%p recovered=%d trace=%d",
        reinterpret_cast<void*>(caller_address),
        reinterpret_cast<void*>(
            resolved && caller_info.dli_fbase != nullptr
                ? caller_address -
                    reinterpret_cast<uintptr_t>(caller_info.dli_fbase)
                : 0
        ),
        recovered,
        trace_only ? 1 : 0
    );
    if (recovered >= 0 && !trace_only) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        static_cast<uint8_t*>(caller_stack)[0x34] = 1;
        return recovered;
    }
    g_original_throw(env, throwable);
    return -2;
}

extern "C" __attribute__((naked)) void recover_field_throw_entry() {
    __asm__ __volatile__(
        "mov x9, sp\n"
        "sub sp, sp, #0x80\n"
        "stp x0, x1, [sp, #0x00]\n"
        "stp x19, x20, [sp, #0x10]\n"
        "stp x21, x22, [sp, #0x20]\n"
        "stp x23, x24, [sp, #0x30]\n"
        "stp x25, x26, [sp, #0x40]\n"
        "stp x27, x28, [sp, #0x50]\n"
        "stp x29, x30, [sp, #0x60]\n"
        "str x9, [sp, #0x70]\n"
        "ldr x0, [sp, #0x00]\n"
        "ldr x1, [sp, #0x08]\n"
        "mov x2, x9\n"
        "ldr x3, [sp, #0x68]\n"
        "bl nestspace_recover_or_throw\n"
        "cmn w0, #2\n"
        "b.eq 2f\n"
        "ldp x19, x20, [sp, #0x10]\n"
        "ldp x21, x22, [sp, #0x20]\n"
        "ldp x23, x24, [sp, #0x30]\n"
        "ldp x25, x26, [sp, #0x40]\n"
        "ldp x27, x28, [sp, #0x50]\n"
        "ldp x29, x30, [sp, #0x60]\n"
        "ldr x9, [sp, #0x70]\n"
        "mov sp, x9\n"
        "mov w20, w0\n"
        "ret\n"
        "2:\n"
        "ldp x0, x1, [sp, #0x00]\n"
        "ldp x19, x20, [sp, #0x10]\n"
        "ldp x21, x22, [sp, #0x20]\n"
        "ldp x23, x24, [sp, #0x30]\n"
        "ldp x25, x26, [sp, #0x40]\n"
        "ldp x27, x28, [sp, #0x50]\n"
        "ldp x29, x30, [sp, #0x60]\n"
        "ldr x9, [sp, #0x70]\n"
        "mov sp, x9\n"
        "ret\n"
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeInstallLateJniCompatibility(
    JNIEnv* env,
    jobject
) {
    if (env == nullptr || env->functions == nullptr) return;
    static std::once_flag once;
    std::call_once(once, [env] {
        char field_fallback[PROP_VALUE_MAX] = {};
        __system_property_get(
            "debug.nestspace.native_field_fallback",
            field_fallback
        );
        const bool fallback_enabled =
            strcmp(field_fallback, "1") == 0 ||
            strcmp(field_fallback, "trace") == 0 ||
            strcmp(field_fallback, "value") == 0;
        bool hooked = false;
        bool throw_new_hooked = false;
        if (fallback_enabled) {
            void* trampoline = nullptr;
            hooked = nestspace::install_shared_inline_hook(
                reinterpret_cast<void*>(env->functions->Throw),
                reinterpret_cast<void*>(recover_field_throw_entry),
                &trampoline
            );
            g_original_throw = reinterpret_cast<ThrowFunction>(trampoline);
            void* throw_new_trampoline = nullptr;
            throw_new_hooked =
                nestspace::install_shared_inline_hook(
                    reinterpret_cast<void*>(env->functions->ThrowNew),
                    reinterpret_cast<void*>(trace_throw_new_entry),
                    &throw_new_trampoline
                );
            g_original_throw_new =
                reinterpret_cast<ThrowNewFunction>(throw_new_trampoline);
        }
        char runtime_hook[PROP_VALUE_MAX] = {};
        __system_property_get(
            "debug.nestspace.runtime_field_hook",
            runtime_hook
        );
        if (strcmp(runtime_hook, "1") == 0) {
            const uintptr_t runtime_field_resolver =
                find_runtime_field_resolver();
            void* runtime_field_trampoline = nullptr;
            const bool runtime_field_hooked =
                runtime_field_resolver != 0 &&
                nestspace::install_shared_inline_hook(
                    reinterpret_cast<void*>(runtime_field_resolver),
                    reinterpret_cast<void*>(runtime_field_resolver_entry),
                    &runtime_field_trampoline
                );
            g_original_runtime_field_resolver =
                reinterpret_cast<RuntimeFieldResolverFunction>(
                    runtime_field_trampoline
                );
            if (runtime_field_resolver == 0 &&
                !g_runtime_field_watch_started.exchange(true)) {
                std::thread(watch_runtime_field_resolver).detach();
            }
            __android_log_print(
                ANDROID_LOG_WARN,
                kTag,
                "runtime field resolver=%p hooked=%d",
                reinterpret_cast<void*>(runtime_field_resolver),
                runtime_field_hooked ? 1 : 0
            );
        }
        __android_log_print(
            ANDROID_LOG_WARN,
            kTag,
            "field throw hook=%d throwNew=%d",
            hooked ? 1 : 0,
            throw_new_hooked ? 1 : 0
        );
    });
}
