#include <cstdint>
#include <jni.h>

extern "C" bool nestspace_is_runtime_field_code(uintptr_t address) {
    (void)address;
    return false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeInstallLateJniCompatibility(
    JNIEnv*,
    jobject
) {
}
