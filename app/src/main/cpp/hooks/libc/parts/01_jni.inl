
using RegisterNativesFunction = jint (*)(
    JNIEnv*,
    jclass,
    const JNINativeMethod*,
    jint
);
RegisterNativesFunction g_registerNatives = nullptr;
decltype(JNINativeInterface::GetMethodID) g_get_method_id = nullptr;
decltype(JNINativeInterface::GetFieldID) g_get_field_id = nullptr;
decltype(JNINativeInterface::ExceptionCheck) g_exception_check = nullptr;
decltype(JNINativeInterface::GetObjectClass) g_get_object_class = nullptr;
decltype(JNINativeInterface::GetObjectField) g_get_object_field = nullptr;
decltype(JNINativeInterface::GetStaticObjectField)
    g_get_static_object_field = nullptr;
decltype(JNINativeInterface::GetIntField) g_get_int_field = nullptr;
decltype(JNINativeInterface::GetStaticIntField)
    g_get_static_int_field = nullptr;
decltype(JNINativeInterface::FindClass) g_find_class = nullptr;
decltype(JNINativeInterface::FindClass) g_original_find_class = nullptr;
decltype(JNINativeInterface::FindClass) g_original_find_class_inline = nullptr;
decltype(JNINativeInterface::GetStaticMethodID) g_get_static_method_id = nullptr;
decltype(JNINativeInterface::GetStaticFieldID) g_get_static_field_id = nullptr;
decltype(JNINativeInterface::CallObjectMethodV) g_call_object_method_v = nullptr;
decltype(JNINativeInterface::CallStaticObjectMethodV)
    g_call_static_object_method_v = nullptr;
decltype(JNINativeInterface::CallIntMethodV) g_call_int_method_v = nullptr;
decltype(JNINativeInterface::CallBooleanMethodV)
    g_call_boolean_method_v = nullptr;
decltype(JNINativeInterface::CallStaticBooleanMethodV)
    g_call_static_boolean_method_v = nullptr;
decltype(JNINativeInterface::CallStaticVoidMethodV)
    g_call_static_void_method_v = nullptr;
decltype(JNINativeInterface::CallStaticVoidMethodA)
    g_call_static_void_method_a = nullptr;
std::unordered_map<jfieldID, std::string> g_probe_fields;
std::unordered_map<jmethodID, std::string> g_probe_methods;
std::mutex g_static_constant_fields_mutex;
std::unordered_map<jfieldID, jclass> g_static_constant_fields;
jclass g_complete_class_bridge = nullptr;
jmethodID g_complete_class_resolve_name = nullptr;
jmethodID g_complete_class_should_reconcile = nullptr;
thread_local int g_find_class_reconciliation_depth = 0;

extern "C" void nestspace_mark_static_instance_view();

jfieldID reconcile_get_field_id(
    JNIEnv* env,
    jclass clazz,
    const char* name,
    const char* signature
);

__attribute__((unused)) jint reconcile_get_int_field(
    JNIEnv* env,
    jobject object,
    jfieldID field
) {
    jclass declaring_class = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_static_constant_fields_mutex);
        auto found = g_static_constant_fields.find(field);
        if (found != g_static_constant_fields.end()) {
            declaring_class = found->second;
        }
    }
    if (declaring_class != nullptr) {
        char trace_value[PROP_VALUE_MAX] = {};
        __system_property_get(
            "debug.nestspace.class_resolution_trace",
            trace_value
        );
        if (strcmp(trace_value, "1") == 0) {
            __android_log_print(
                ANDROID_LOG_DEBUG,
                "NestSpaceNative",
                "routed constant int read field=%p class=%p",
                field,
                declaring_class
            );
        }
        return g_get_static_int_field(env, declaring_class, field);
    }
    return g_get_int_field(env, object, field);
}

__attribute__((unused)) jint reconcile_get_static_int_field(
    JNIEnv* env,
    jclass clazz,
    jfieldID field
) {
    jclass declaring_class = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_static_constant_fields_mutex);
        auto found = g_static_constant_fields.find(field);
        if (found != g_static_constant_fields.end()) {
            declaring_class = found->second;
        }
    }
    if (declaring_class != nullptr) {
        char trace_value[PROP_VALUE_MAX] = {};
        __system_property_get(
            "debug.nestspace.class_resolution_trace",
            trace_value
        );
        if (strcmp(trace_value, "1") == 0) {
            __android_log_print(
                ANDROID_LOG_DEBUG,
                "NestSpaceNative",
                "routed constant static int read field=%p class=%p",
                field,
                declaring_class
            );
        }
    }
    return g_get_static_int_field(
        env,
        declaring_class != nullptr ? declaring_class : clazz,
        field
    );
}

__attribute__((unused)) jobject reconcile_get_object_field(
    JNIEnv* env,
    jobject object,
    jfieldID field
) {
    jclass declaring_class = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_static_constant_fields_mutex);
        auto found = g_static_constant_fields.find(field);
        if (found != g_static_constant_fields.end()) {
            declaring_class = found->second;
        }
    }
    if (declaring_class != nullptr) {
        return g_get_static_object_field(env, declaring_class, field);
    }
    return g_get_object_field(env, object, field);
}

bool initialize_complete_class_bridge(JNIEnv* env) {
    if (env == nullptr) return false;
    if (g_complete_class_bridge != nullptr &&
        g_complete_class_resolve_name != nullptr &&
        g_complete_class_should_reconcile != nullptr) {
        return true;
    }
    jclass bridge = env->FindClass(
        "com/coke/nestspace/container/loader/CompleteClassBridge"
    );
    if (bridge == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jmethodID resolve_name = env->GetStaticMethodID(
        bridge,
        "resolveName",
        "(Ljava/lang/String;)Ljava/lang/Class;"
    );
    jmethodID should_reconcile = env->GetStaticMethodID(
        bridge,
        "shouldReconcile",
        "(Ljava/lang/Class;)Z"
    );
    if (resolve_name == nullptr || should_reconcile == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(bridge);
        return false;
    }
    g_complete_class_bridge =
        static_cast<jclass>(env->NewGlobalRef(bridge));
    env->DeleteLocalRef(bridge);
    if (g_complete_class_bridge == nullptr) return false;
    g_complete_class_resolve_name = resolve_name;
    g_complete_class_should_reconcile = should_reconcile;
    return true;
}

const char* probe_method_name(jmethodID method) {
    const auto found = g_probe_methods.find(method);
    return found == g_probe_methods.end() ? "?" : found->second.c_str();
}

void virtualize_package_info_uid(
    JNIEnv* env,
    jobject result,
    const char* method_name
) {
    if (env == nullptr || result == nullptr || method_name == nullptr ||
        strstr(method_name, "getPackageInfo") == nullptr) {
        return;
    }
    jclass package_info_class = env->GetObjectClass(result);
    if (package_info_class == nullptr) return;
    jfieldID application_info_field = env->GetFieldID(
        package_info_class,
        "applicationInfo",
        "Landroid/content/pm/ApplicationInfo;"
    );
    jobject application_info = application_info_field == nullptr
        ? nullptr
        : env->GetObjectField(result, application_info_field);
    jclass application_info_class = application_info == nullptr
        ? nullptr
        : env->GetObjectClass(application_info);
    jfieldID uid_field = application_info_class == nullptr
        ? nullptr
        : env->GetFieldID(application_info_class, "uid", "I");
    if (uid_field != nullptr) {
        const jint previous = env->GetIntField(application_info, uid_field);
        const jint effective = static_cast<jint>(getuid());
        env->SetIntField(application_info, uid_field, effective);
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "virtualized PackageInfo uid %d -> %d",
            previous,
            effective
        );
    }
    jfieldID signatures_field = env->GetFieldID(
        package_info_class,
        "signatures",
        "[Landroid/content/pm/Signature;"
    );
    auto signatures = signatures_field == nullptr
        ? static_cast<jobjectArray>(nullptr)
        : static_cast<jobjectArray>(
            env->GetObjectField(result, signatures_field)
        );
    if (signatures != nullptr && env->GetArrayLength(signatures) > 0) {
        jobject signature = env->GetObjectArrayElement(signatures, 0);
        jclass signature_class = signature == nullptr
            ? nullptr
            : env->GetObjectClass(signature);
        jmethodID hash_code = signature_class == nullptr
            ? nullptr
            : env->GetMethodID(signature_class, "hashCode", "()I");
        const jint hash = hash_code == nullptr
            ? 0
            : env->CallIntMethod(signature, hash_code);
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "virtualized PackageInfo signatureCount=%d firstHash=%d",
            env->GetArrayLength(signatures),
            hash
        );
        if (signature_class != nullptr) env->DeleteLocalRef(signature_class);
        if (signature != nullptr) env->DeleteLocalRef(signature);
    }
    if (signatures != nullptr) env->DeleteLocalRef(signatures);
    if (application_info_class != nullptr) {
        env->DeleteLocalRef(application_info_class);
    }
    if (application_info != nullptr) env->DeleteLocalRef(application_info);
    env->DeleteLocalRef(package_info_class);
}

jobject probe_call_object_method(
    JNIEnv* env,
    jobject object,
    jmethodID method,
    ...
) {
    va_list arguments;
    va_start(arguments, method);
    const jobject result = g_call_object_method_v(
        env,
        object,
        method,
        arguments
    );
    va_end(arguments);
    const char* method_name = probe_method_name(method);
    virtualize_package_info_uid(env, result, method_name);
    if (result != nullptr && strstr(method_name, "()Ljava/lang/String;") != nullptr) {
        const char* value = env->GetStringUTFChars(
            static_cast<jstring>(result),
            nullptr
        );
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe CallObjectMethod method=%s result=%p value=%s",
            method_name,
            result,
            value == nullptr ? "?" : value
        );
        if (value != nullptr) {
            env->ReleaseStringUTFChars(static_cast<jstring>(result), value);
        }
    } else {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe CallObjectMethod method=%s result=%p",
            method_name,
            result
        );
    }
    return result;
}

jobject probe_call_static_object_method(
    JNIEnv* env,
    jclass clazz,
    jmethodID method,
    ...
) {
    va_list arguments;
    va_start(arguments, method);
    const jobject result = g_call_static_object_method_v(
        env,
        clazz,
        method,
        arguments
    );
    va_end(arguments);
    const char* method_name = probe_method_name(method);
    if (result != nullptr && strstr(method_name, "()Ljava/lang/String;") != nullptr) {
        const char* value = env->GetStringUTFChars(
            static_cast<jstring>(result),
            nullptr
        );
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe CallStaticObjectMethod method=%s result=%p value=%s",
            method_name,
            result,
            value == nullptr ? "?" : value
        );
        if (value != nullptr) {
            env->ReleaseStringUTFChars(static_cast<jstring>(result), value);
        }
    } else {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe CallStaticObjectMethod method=%s result=%p",
            method_name,
            result
        );
    }
    return result;
}

jobject probe_call_object_method_v(
    JNIEnv* env,
    jobject object,
    jmethodID method,
    va_list arguments
) {
    const jobject result = g_call_object_method_v(
        env,
        object,
        method,
        arguments
    );
    const char* method_name = probe_method_name(method);
    virtualize_package_info_uid(env, result, method_name);
    if (result != nullptr && strstr(method_name, "()Ljava/lang/String;") != nullptr) {
        const char* value = env->GetStringUTFChars(
            static_cast<jstring>(result),
            nullptr
        );
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe CallObjectMethodV method=%s result=%p value=%s",
            method_name,
            result,
            value == nullptr ? "?" : value
        );
        if (value != nullptr) {
            env->ReleaseStringUTFChars(static_cast<jstring>(result), value);
        }
    } else {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe CallObjectMethodV method=%s result=%p",
            method_name,
            result
        );
    }
    return result;
}

jobject probe_call_static_object_method_v(
    JNIEnv* env,
    jclass clazz,
    jmethodID method,
    va_list arguments
) {
    const jobject result = g_call_static_object_method_v(
        env,
        clazz,
        method,
        arguments
    );
    const char* method_name = probe_method_name(method);
    if (result != nullptr && strstr(method_name, "()Ljava/lang/String;") != nullptr) {
        const char* value = env->GetStringUTFChars(
            static_cast<jstring>(result),
            nullptr
        );
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe CallStaticObjectMethodV method=%s result=%p value=%s",
            method_name,
            result,
            value == nullptr ? "?" : value
        );
        if (value != nullptr) {
            env->ReleaseStringUTFChars(static_cast<jstring>(result), value);
        }
    } else {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe CallStaticObjectMethodV method=%s result=%p",
            method_name,
            result
        );
    }
    return result;
}

jint probe_call_int_method(
    JNIEnv* env,
    jobject object,
    jmethodID method,
    ...
) {
    va_list arguments;
    va_start(arguments, method);
    const jint result = g_call_int_method_v(env, object, method, arguments);
    va_end(arguments);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallIntMethod method=%s result=%d",
        probe_method_name(method),
        result
    );
    return result;
}

jint probe_call_int_method_v(
    JNIEnv* env,
    jobject object,
    jmethodID method,
    va_list arguments
) {
    const char* method_name = probe_method_name(method);
    const bool permission_check =
        strstr(method_name, "checkPermission") != nullptr;
    jstring permission_value = nullptr;
    const char* permission = nullptr;
    jint permission_pid = -1;
    jint permission_uid = -1;
    if (permission_check) {
        va_list copied;
        va_copy(copied, arguments);
        permission_value = va_arg(copied, jstring);
        permission_pid = va_arg(copied, jint);
        permission_uid = va_arg(copied, jint);
        permission = permission_value == nullptr
            ? nullptr
            : env->GetStringUTFChars(permission_value, nullptr);
        va_end(copied);
    }
    const jint result = g_call_int_method_v(env, object, method, arguments);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallIntMethodV method=%s result=%d permission=%s pid=%d uid=%d",
        method_name,
        result,
        permission == nullptr ? "?" : permission,
        permission_pid,
        permission_uid
    );
    if (permission != nullptr) {
        env->ReleaseStringUTFChars(
            permission_value,
            permission
        );
    }
    return result;
}

jboolean probe_call_boolean_method(
    JNIEnv* env,
    jobject object,
    jmethodID method,
    ...
) {
    va_list arguments;
    va_start(arguments, method);
    const jboolean result = g_call_boolean_method_v(
        env,
        object,
        method,
        arguments
    );
    va_end(arguments);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallBooleanMethod method=%s result=%d",
        probe_method_name(method),
        result
    );
    return result;
}

jboolean probe_call_static_boolean_method(
    JNIEnv* env,
    jclass clazz,
    jmethodID method,
    ...
) {
    va_list arguments;
    va_start(arguments, method);
    const jboolean result = g_call_static_boolean_method_v(
        env,
        clazz,
        method,
        arguments
    );
    va_end(arguments);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallStaticBooleanMethod method=%s result=%d",
        probe_method_name(method),
        result
    );
    return result;
}

jboolean probe_call_boolean_method_v(
    JNIEnv* env,
    jobject object,
    jmethodID method,
    va_list arguments
) {
    const jboolean result = g_call_boolean_method_v(
        env,
        object,
        method,
        arguments
    );
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallBooleanMethodV method=%s result=%d",
        probe_method_name(method),
        result
    );
    return result;
}

jboolean probe_call_static_boolean_method_v(
    JNIEnv* env,
    jclass clazz,
    jmethodID method,
    va_list arguments
) {
    const jboolean result = g_call_static_boolean_method_v(
        env,
        clazz,
        method,
        arguments
    );
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallStaticBooleanMethodV method=%s result=%d",
        probe_method_name(method),
        result
    );
    return result;
}

void probe_call_static_void_method_v(
    JNIEnv* env,
    jclass clazz,
    jmethodID method,
    va_list arguments
) {
    const char* method_name = probe_method_name(method);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallStaticVoidMethodV method=%s id=%p",
        method_name,
        method
    );
    if (strstr(method_name, "exit:(I)V") != nullptr ||
        strstr(method_name, "halt:(I)V") != nullptr) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "suppressed virtual machine termination method=%s",
            method_name
        );
        return;
    }
    g_call_static_void_method_v(env, clazz, method, arguments);
}

void probe_call_static_void_method(
    JNIEnv* env,
    jclass clazz,
    jmethodID method,
    ...
) {
    va_list arguments;
    va_start(arguments, method);
    const char* method_name = probe_method_name(method);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallStaticVoidMethod method=%s id=%p",
        method_name,
        method
    );
    if (strstr(method_name, "exit:(I)V") == nullptr &&
        strstr(method_name, "halt:(I)V") == nullptr) {
        g_call_static_void_method_v(env, clazz, method, arguments);
    }
    va_end(arguments);
}

void probe_call_static_void_method_a(
    JNIEnv* env,
    jclass clazz,
    jmethodID method,
    const jvalue* arguments
) {
    const char* method_name = probe_method_name(method);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe CallStaticVoidMethodA method=%s id=%p",
        method_name,
        method
    );
    if (strstr(method_name, "exit:(I)V") != nullptr ||
        strstr(method_name, "halt:(I)V") != nullptr) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "NestSpaceNative",
            "suppressed virtual machine termination method=%s",
            method_name
        );
        return;
    }
    g_call_static_void_method_a(env, clazz, method, arguments);
}

jclass resolve_complete_class_name(
    JNIEnv* env,
    jclass original,
    const char* name
) {
    if (env == nullptr || name == nullptr || name[0] == '\0' ||
        name[0] == '[' ||
        strncmp(name, "java/", 5) == 0 ||
        strncmp(name, "android/", 8) == 0 ||
        strncmp(name, "javax/", 6) == 0 ||
        strncmp(name, "dalvik/", 7) == 0 ||
        strncmp(name, "libcore/", 8) == 0 ||
        strncmp(name, "sun/", 4) == 0 ||
        strncmp(name, "com/android/", 12) == 0) {
        return nullptr;
    }
    if (!initialize_complete_class_bridge(env)) {
        return nullptr;
    }
    const jboolean candidate = env->CallStaticBooleanMethod(
        g_complete_class_bridge,
        g_complete_class_should_reconcile,
        original
    );
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (candidate == JNI_FALSE) return nullptr;
    jstring descriptor = env->NewStringUTF(name);
    if (descriptor == nullptr) return nullptr;
    auto resolved = static_cast<jclass>(
        env->CallStaticObjectMethod(
            g_complete_class_bridge,
            g_complete_class_resolve_name,
            descriptor
        )
    );
    env->DeleteLocalRef(descriptor);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return resolved;
}

jclass reconcile_found_class(
    JNIEnv* env,
    jclass original,
    const char* name
) {
    if (original == nullptr || env == nullptr || name == nullptr) {
        return original;
    }
    if (g_find_class_reconciliation_depth > 0) return original;
    ++g_find_class_reconciliation_depth;
    const jclass complete = resolve_complete_class_name(env, original, name);
    --g_find_class_reconciliation_depth;
    char trace_value[PROP_VALUE_MAX] = {};
    __system_property_get(
        "debug.nestspace.class_resolution_trace",
        trace_value
    );
    if (strcmp(trace_value, "1") == 0 &&
        strstr(name, "R$") != nullptr) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "class reconciliation name=%s runtime=%p application=%p",
            name,
            original,
            complete
        );
    }
    if (complete == nullptr ||
        env->IsSameObject(original, complete)) {
        if (complete != nullptr) env->DeleteLocalRef(complete);
        return original;
    }
    if (strcmp(trace_value, "1") == 0) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "reconciled FindClass name=%s runtime=%p application=%p",
            name,
            original,
            complete
        );
    }
    env->DeleteLocalRef(original);
    return complete;
}

jclass reconcile_find_class(JNIEnv* env, const char* name) {
    const auto find_class = g_original_find_class_inline != nullptr
        ? g_original_find_class_inline
        : g_original_find_class;
    if (find_class == nullptr) return nullptr;
    return reconcile_found_class(env, find_class(env, name), name);
}

jclass probe_find_class(JNIEnv* env, const char* name) {
    const jclass result = reconcile_found_class(
        env,
        g_find_class(env, name),
        name
    );
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe FindClass name=%s result=%p",
        name == nullptr ? "?" : name,
        result
    );
    return result;
}

jfieldID reconcile_get_field_id(
    JNIEnv* env,
    jclass clazz,
    const char* name,
    const char* signature
) {
    if (env == nullptr || clazz == nullptr || name == nullptr ||
        signature == nullptr || g_get_field_id == nullptr ||
        g_get_static_field_id == nullptr) {
        return nullptr;
    }
    jfieldID result = g_get_field_id(env, clazz, name, signature);
    if (result != nullptr) return result;
    if (env->ExceptionCheck()) env->ExceptionClear();
    const jboolean candidate = initialize_complete_class_bridge(env)
        ? env->CallStaticBooleanMethod(
            g_complete_class_bridge,
            g_complete_class_should_reconcile,
            clazz
        )
        : JNI_FALSE;
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (candidate == JNI_FALSE) {
        return g_get_field_id(env, clazz, name, signature);
    }
    result = g_get_static_field_id(env, clazz, name, signature);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (result != nullptr) {
        nestspace_mark_static_instance_view();
        jclass declaring_class = static_cast<jclass>(
            env->NewGlobalRef(clazz)
        );
        if (declaring_class != nullptr) {
            std::lock_guard<std::mutex> lock(
                g_static_constant_fields_mutex
            );
            g_static_constant_fields[result] = declaring_class;
        }
    }
    char trace_value[PROP_VALUE_MAX] = {};
    __system_property_get(
        "debug.nestspace.class_resolution_trace",
        trace_value
    );
    if (strcmp(trace_value, "1") == 0) {
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "reconciled field lookup name=%s signature=%s static=%p",
            name,
            signature,
            result
        );
    }
    return result;
}

jmethodID probe_get_static_method_id(
    JNIEnv* env,
    jclass clazz,
    const char* name,
    const char* signature
) {
    const jmethodID result = g_get_static_method_id(env, clazz, name, signature);
    if (result != nullptr) {
        g_probe_methods[result] = std::string(name == nullptr ? "?" : name) +
            ":" + (signature == nullptr ? "?" : signature);
    }
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe GetStaticMethodID class=%p name=%s signature=%s result=%p",
        clazz,
        name == nullptr ? "?" : name,
        signature == nullptr ? "?" : signature,
        result
    );
    return result;
}

jfieldID probe_get_static_field_id(
    JNIEnv* env,
    jclass clazz,
    const char* name,
    const char* signature
) {
    const jfieldID result = g_get_static_field_id(env, clazz, name, signature);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe GetStaticFieldID class=%p name=%s signature=%s result=%p",
        clazz,
        name == nullptr ? "?" : name,
        signature == nullptr ? "?" : signature,
        result
    );
    return result;
}

jclass probe_get_object_class(JNIEnv* env, jobject object) {
    const jclass result = g_get_object_class(env, object);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe GetObjectClass object=%p result=%p",
        object,
        result
    );
    return result;
}

jmethodID probe_get_method_id(
    JNIEnv* env,
    jclass clazz,
    const char* name,
    const char* signature
) {
    const jmethodID result = g_get_method_id(env, clazz, name, signature);
    if (result != nullptr) {
        g_probe_methods[result] = std::string(name == nullptr ? "?" : name) +
            ":" + (signature == nullptr ? "?" : signature);
    }
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe GetMethodID class=%p name=%s signature=%s result=%p",
        clazz,
        name == nullptr ? "?" : name,
        signature == nullptr ? "?" : signature,
        result
    );
    return result;
}

jfieldID probe_get_field_id(
    JNIEnv* env,
    jclass clazz,
    const char* name,
    const char* signature
) {
    const jfieldID result = reconcile_get_field_id(
        env,
        clazz,
        name,
        signature
    );
    if (result != nullptr) {
        g_probe_fields[result] = std::string(name == nullptr ? "?" : name) +
            ":" + (signature == nullptr ? "?" : signature);
    }
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe GetFieldID class=%p name=%s signature=%s result=%p",
        clazz,
        name == nullptr ? "?" : name,
        signature == nullptr ? "?" : signature,
        result
    );
    return result;
}

jobject probe_get_object_field(
    JNIEnv* env,
    jobject object,
    jfieldID field
) {
    const jobject result = reconcile_get_object_field(env, object, field);
    const auto found = g_probe_fields.find(field);
    const char* field_name = found == g_probe_fields.end()
        ? "?"
        : found->second.c_str();
    if (result != nullptr &&
        found != g_probe_fields.end() &&
        found->second.find(":Ljava/lang/String;") != std::string::npos) {
        const char* value = env->GetStringUTFChars(
            static_cast<jstring>(result),
            nullptr
        );
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe GetObjectField field=%s object=%p result=%p value=%s",
            field_name,
            object,
            result,
            value == nullptr ? "?" : value
        );
        if (value != nullptr) {
            env->ReleaseStringUTFChars(static_cast<jstring>(result), value);
        }
    } else {
        const uintptr_t caller = reinterpret_cast<uintptr_t>(
            __builtin_return_address(0)
        );
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "jni-probe GetObjectField field=%s object=%p result=%p caller=%p",
            field_name,
            object,
            result,
            reinterpret_cast<void*>(caller)
        );
    }
    return result;
}

jboolean probe_exception_check(JNIEnv* env) {
    const jboolean result = g_exception_check(env);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        "NestSpaceNative",
        "jni-probe ExceptionCheck result=%d",
        result
    );
    return result;
}

void suppress_native_abort(JNIEnv*, jobject) {
    __android_log_print(
        ANDROID_LOG_WARN,
        "NestSpaceNative",
        "native abort callback invoked"
    );
}

jint hook_register_natives(
    JNIEnv* env,
    jclass clazz,
    const JNINativeMethod* methods,
    jint count
) {
    std::vector<JNINativeMethod> registered_methods;
    bool changed = false;
    if (env != nullptr && clazz != nullptr && methods != nullptr) {
        jclass class_class = env->FindClass("java/lang/Class");
        jmethodID get_name = class_class == nullptr
            ? nullptr
            : env->GetMethodID(
                class_class,
                "getName",
                "()Ljava/lang/String;"
            );
        auto class_name = get_name == nullptr
            ? static_cast<jstring>(nullptr)
            : static_cast<jstring>(env->CallObjectMethod(clazz, get_name));
        const char* class_chars = class_name == nullptr
            ? nullptr
            : env->GetStringUTFChars(class_name, nullptr);
        for (jint index = 0; index < count; ++index) {
            Dl_info function_info = {};
            const bool function_resolved = methods[index].fnPtr != nullptr &&
                dladdr(methods[index].fnPtr, &function_info) != 0;
            __android_log_print(
                ANDROID_LOG_DEBUG,
                "NestSpaceNative",
                "RegisteredNative class=%s name=%s signature=%s address=%p "
                "module=%s symbol=%s offset=%p",
                class_chars == nullptr ? "?" : class_chars,
                methods[index].name == nullptr ? "?" : methods[index].name,
                methods[index].signature == nullptr
                    ? "?"
                    : methods[index].signature,
                methods[index].fnPtr,
                function_resolved && function_info.dli_fname != nullptr
                    ? function_info.dli_fname
                    : "?",
                function_resolved && function_info.dli_sname != nullptr
                    ? function_info.dli_sname
                    : "?",
                function_resolved && function_info.dli_fbase != nullptr
                    ? reinterpret_cast<void*>(
                        reinterpret_cast<uintptr_t>(methods[index].fnPtr) -
                        reinterpret_cast<uintptr_t>(function_info.dli_fbase)
                    )
                    : nullptr
            );
            if (methods[index].name != nullptr &&
                methods[index].signature != nullptr &&
                strcmp(methods[index].name, "nativeAbort") == 0 &&
                strcmp(methods[index].signature, "()V") == 0) {
                if (!changed) {
                    registered_methods.assign(methods, methods + count);
                    changed = true;
                }
                registered_methods[index].fnPtr =
                    reinterpret_cast<void*>(&suppress_native_abort);
                __android_log_print(
                    ANDROID_LOG_WARN,
                    "NestSpaceNative",
                    "suppressed native abort callback class=%s",
                    class_chars == nullptr ? "?" : class_chars
                );
            }
        }
        if (class_chars != nullptr) {
            env->ReleaseStringUTFChars(class_name, class_chars);
        }
        if (class_name != nullptr) env->DeleteLocalRef(class_name);
        if (class_class != nullptr) env->DeleteLocalRef(class_class);
    }
    return g_registerNatives != nullptr
        ? g_registerNatives(
            env,
            clazz,
            changed ? registered_methods.data() : methods,
            count
        )
        : JNI_ERR;
}

void install_jni_registration_hook(JNIEnv* env) {
    if (env == nullptr || env->functions == nullptr) return;
    initialize_complete_class_bridge(env);
    char inline_enabled[PROP_VALUE_MAX] = {};
    __system_property_get(
        "debug.nestspace.class_resolution_hook",
        inline_enabled
    );
    const bool use_inline_hook = strcmp(inline_enabled, "1") == 0;
    static std::once_flag find_class_hook_once;
    if (use_inline_hook) std::call_once(find_class_hook_once, [env] {
        g_original_find_class = env->functions->FindClass;
        void* trampoline = nullptr;
        const bool hooked = nestspace::install_shared_inline_hook(
            reinterpret_cast<void*>(env->functions->FindClass),
            reinterpret_cast<void*>(reconcile_find_class),
            &trampoline
        );
        if (hooked) {
            g_original_find_class_inline =
                reinterpret_cast<decltype(JNINativeInterface::FindClass)>(
                    trampoline
                );
        }
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "FindClass reconciliation hook installed=%d original=%p",
            hooked ? 1 : 0,
            reinterpret_cast<void*>(g_original_find_class)
        );
    });
    char enabled[PROP_VALUE_MAX] = {};
    __system_property_get(
        "debug.nestspace.jni_registration_hook",
        enabled
    );
    const bool diagnostics = strcmp(enabled, "1") == 0;
    char bridge_enabled[PROP_VALUE_MAX] = {};
    __system_property_get(
        "debug.nestspace.class_resolution_bridge",
        bridge_enabled
    );
    const bool reconcile_classes = strcmp(bridge_enabled, "1") == 0;
    if (!diagnostics && !reconcile_classes) return;
    static std::once_flag once;
    std::call_once(once, [env, diagnostics, reconcile_classes] {
        // Keep the process-wide JNI implementation untouched. Native
        // packers commonly compare the global table and scan the
        // RegisterNatives entry for inline patches. A per-thread table copy
        // is sufficient for diagnostics and leaves executable code byte-for-
        // byte identical.
        static JNINativeInterface probe_functions;
        const JNINativeInterface* original_functions = env->functions;
        probe_functions = *original_functions;
        g_registerNatives = original_functions->RegisterNatives;
        g_get_method_id = original_functions->GetMethodID;
        g_get_field_id = original_functions->GetFieldID;
        g_exception_check = original_functions->ExceptionCheck;
        g_get_object_class = original_functions->GetObjectClass;
        g_get_object_field = original_functions->GetObjectField;
        g_get_static_object_field =
            original_functions->GetStaticObjectField;
        g_get_int_field = original_functions->GetIntField;
        g_get_static_int_field =
            original_functions->GetStaticIntField;
        g_find_class = original_functions->FindClass;
        if (g_original_find_class == nullptr) {
            g_original_find_class = original_functions->FindClass;
        }
        g_get_static_method_id = original_functions->GetStaticMethodID;
        g_get_static_field_id = original_functions->GetStaticFieldID;
        g_call_object_method_v = original_functions->CallObjectMethodV;
        g_call_static_object_method_v =
            original_functions->CallStaticObjectMethodV;
        g_call_int_method_v = original_functions->CallIntMethodV;
        g_call_boolean_method_v = original_functions->CallBooleanMethodV;
        g_call_static_boolean_method_v =
            original_functions->CallStaticBooleanMethodV;
        g_call_static_void_method_v =
            original_functions->CallStaticVoidMethodV;
        g_call_static_void_method_a =
            original_functions->CallStaticVoidMethodA;
        if (reconcile_classes) {
            probe_functions.FindClass = diagnostics
                ? probe_find_class
                : reconcile_find_class;
            probe_functions.GetFieldID = diagnostics
                ? probe_get_field_id
                : reconcile_get_field_id;
            probe_functions.GetIntField = reconcile_get_int_field;
            probe_functions.GetStaticIntField =
                reconcile_get_static_int_field;
        }
        if (diagnostics) {
            probe_functions.GetMethodID = probe_get_method_id;
            probe_functions.GetStaticMethodID = probe_get_static_method_id;
            probe_functions.GetStaticFieldID = probe_get_static_field_id;
            probe_functions.GetObjectClass = probe_get_object_class;
            probe_functions.CallObjectMethod = probe_call_object_method;
            probe_functions.CallStaticObjectMethod =
                probe_call_static_object_method;
            probe_functions.CallObjectMethodV = probe_call_object_method_v;
            probe_functions.CallStaticObjectMethodV =
                probe_call_static_object_method_v;
            probe_functions.CallIntMethod = probe_call_int_method;
            probe_functions.CallIntMethodV = probe_call_int_method_v;
            probe_functions.CallBooleanMethod = probe_call_boolean_method;
            probe_functions.CallStaticBooleanMethod =
                probe_call_static_boolean_method;
            probe_functions.CallBooleanMethodV =
                probe_call_boolean_method_v;
            probe_functions.CallStaticBooleanMethodV =
                probe_call_static_boolean_method_v;
            probe_functions.CallStaticVoidMethod =
                probe_call_static_void_method;
            probe_functions.CallStaticVoidMethodV =
                probe_call_static_void_method_v;
            probe_functions.CallStaticVoidMethodA =
                probe_call_static_void_method_a;
            probe_functions.RegisterNatives = hook_register_natives;
        }
        env->functions = &probe_functions;
        __android_log_print(
            ANDROID_LOG_DEBUG,
            "NestSpaceNative",
            "JNI RegisterNatives table probe installed original=%p",
            reinterpret_cast<void*>(g_registerNatives)
        );
    });
}
