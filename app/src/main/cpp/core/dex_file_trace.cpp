#include <android/log.h>
#include <cstring>
#include <dlfcn.h>
#include <jni.h>
#include <link.h>
#include <mutex>
#include <unwind.h>
#include <vector>

#include "nestspace/native_environment_internal.h"

namespace {

using DexFileOpenFunction = void (*)(
    void*,
    bool,
    bool,
    bool,
    void*,
    void*,
    void*
);

DexFileOpenFunction g_original_open = nullptr;

_Unwind_Reason_Code collect_frame(
    struct _Unwind_Context* context,
    void* user_data
) {
    auto* frames = static_cast<std::vector<uintptr_t>*>(user_data);
    const uintptr_t frame =
        static_cast<uintptr_t>(_Unwind_GetIP(context));
    if (frame != 0) frames->push_back(frame);
    return frames->size() >= 24 ? _URC_END_OF_STACK : _URC_NO_REASON;
}

void trace_open(
    void* loader,
    bool verify,
    bool verify_checksum,
    bool image_check,
    void* error_code,
    void* error_message,
    void* output
) {
    std::vector<uintptr_t> frames;
    frames.reserve(24);
    _Unwind_Backtrace(collect_frame, &frames);
    __android_log_print(
        ANDROID_LOG_WARN,
        "DexFileTrace",
        "Open loader=%p verify=%d checksum=%d image=%d frames=%zu",
        loader,
        verify ? 1 : 0,
        verify_checksum ? 1 : 0,
        image_check ? 1 : 0,
        frames.size()
    );
    for (size_t index = 0; index < frames.size(); ++index) {
        Dl_info info{};
        const bool resolved =
            dladdr(reinterpret_cast<void*>(frames[index]), &info) != 0;
        const uintptr_t base =
            resolved && info.dli_fbase != nullptr
            ? reinterpret_cast<uintptr_t>(info.dli_fbase)
            : 0;
        __android_log_print(
            ANDROID_LOG_WARN,
            "DexFileTrace",
            "frame=%zu address=%p offset=%p module=%s",
            index,
            reinterpret_cast<void*>(frames[index]),
            reinterpret_cast<void*>(
                base == 0 ? 0 : frames[index] - base
            ),
            resolved && info.dli_fname != nullptr ? info.dli_fname : "?"
        );
    }
    g_original_open(
        loader,
        verify,
        verify_checksum,
        image_check,
        error_code,
        error_message,
        output
    );
}

struct ModuleSearch {
    uintptr_t base = 0;
};

int find_dexfile_module(
    struct dl_phdr_info* info,
    size_t,
    void* data
) {
    auto* search = static_cast<ModuleSearch*>(data);
    if (info == nullptr || info->dlpi_name == nullptr ||
        search == nullptr || search->base != 0) {
        return 0;
    }
    if (strstr(info->dlpi_name, "libdexfile.so") != nullptr) {
        search->base = static_cast<uintptr_t>(info->dlpi_addr);
    }
    return 0;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeInstallDexFileTrace(
    JNIEnv*,
    jobject
) {
    static std::once_flag once;
    std::call_once(once, [] {
        constexpr char kOpenSymbol[] =
            "_ZN3art13DexFileLoader4OpenEbbbPNS_22DexFileLoaderErrorCode"
            "EPNSt3__112basic_stringIcNS3_11char_traitsIcEENS3_9allocator"
            "IcEEEEEPNS3_6vectorINS3_10unique_ptrIKNS_7DexFileENS3_14"
            "default_deleteISE_EEEENS7_ISH_EEEE";
        void* handle = dlopen("libdexfile.so", RTLD_NOW | RTLD_NOLOAD);
        void* target = handle == nullptr
            ? nullptr
            : dlsym(handle, kOpenSymbol);
        if (handle != nullptr) dlclose(handle);
        if (target == nullptr) {
            ModuleSearch search;
            dl_iterate_phdr(find_dexfile_module, &search);
            if (search.base != 0) {
                target = reinterpret_cast<void*>(search.base + 0x1f710);
            }
        }
        void* trampoline = nullptr;
        const bool installed = nestspace::install_shared_inline_hook(
            target,
            reinterpret_cast<void*>(trace_open),
            &trampoline
        );
        g_original_open =
            reinterpret_cast<DexFileOpenFunction>(trampoline);
        __android_log_print(
            ANDROID_LOG_WARN,
            "DexFileTrace",
            "hook installed=%d target=%p",
            installed ? 1 : 0,
            target
        );
    });
}
