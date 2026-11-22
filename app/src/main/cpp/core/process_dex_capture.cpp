#include <android/log.h>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <jni.h>
#include <string>
#include <sys/stat.h>
#include <sys/uio.h>
#include <unistd.h>
#include <unordered_set>
#include <vector>

namespace {

constexpr char kTag[] = "ProcessDexCapture";
constexpr size_t kChunkSize = 1024 * 1024;
constexpr uint32_t kMaxDexSize = 256U * 1024U * 1024U;

uint32_t read_u32(const uint8_t* data) {
    return static_cast<uint32_t>(data[0]) |
        (static_cast<uint32_t>(data[1]) << 8U) |
        (static_cast<uint32_t>(data[2]) << 16U) |
        (static_cast<uint32_t>(data[3]) << 24U);
}

bool read_own_memory(
    uintptr_t address,
    void* output,
    size_t size
) {
    struct iovec local {
        output,
        size
    };
    struct iovec remote {
        reinterpret_cast<void*>(address),
        size
    };
    return process_vm_readv(
        getpid(),
        &local,
        1,
        &remote,
        1,
        0
    ) == static_cast<ssize_t>(size);
}

uint64_t stable_hash(const uint8_t* data, size_t size) {
    uint64_t value = 1469598103934665603ULL;
    for (size_t index = 0; index < size; ++index) {
        value ^= data[index];
        value *= 1099511628211ULL;
    }
    return value;
}

bool valid_dex_checksum(const uint8_t* data, size_t size) {
    if (size < 0x70) return false;
    uint32_t first = 1;
    uint32_t second = 0;
    for (size_t index = 12; index < size; ++index) {
        first = (first + data[index]) % 65521U;
        second = (second + first) % 65521U;
    }
    return read_u32(data + 8) == ((second << 16U) | first);
}

bool ends_with_ignore_case(const char* value, const char* suffix) {
    const char* deleted = strstr(value, " (deleted)");
    const size_t value_size = deleted == nullptr
        ? strlen(value)
        : static_cast<size_t>(deleted - value);
    const size_t suffix_size = strlen(suffix);
    if (value_size < suffix_size) return false;
    const char* tail = value + value_size - suffix_size;
    for (size_t index = 0; index < suffix_size; ++index) {
        if (tolower(static_cast<unsigned char>(tail[index])) !=
            tolower(static_cast<unsigned char>(suffix[index]))) {
            return false;
        }
    }
    return true;
}

bool path_is_within(const char* path, const char* root) {
    if (path == nullptr || root == nullptr || *root == '\0') return false;
    size_t root_size = strlen(root);
    while (root_size > 1 && root[root_size - 1] == '/') {
        --root_size;
    }
    if (strncmp(path, root, root_size) != 0) return false;
    return path[root_size] == '\0' || path[root_size] == '/';
}

bool mapping_candidate(
    const char* line,
    const char* permissions,
    const char* data_root
) {
    if (permissions == nullptr || permissions[0] != 'r') return false;
    const char* path = strchr(line, '/');
    if (path == nullptr) return true;
    if (strstr(path, "memfd") != nullptr ||
        path_is_within(path, data_root)) {
        return true;
    }
    return ends_with_ignore_case(path, ".dex") ||
        ends_with_ignore_case(path, ".apk") ||
        ends_with_ignore_case(path, ".jar") ||
        ends_with_ignore_case(path, ".zip");
}

bool executable_mapping_candidate(
    const char* line,
    const char* permissions
) {
    if (permissions == nullptr || permissions[0] != 'r' ||
        permissions[2] != 'x') {
        return false;
    }
    return strstr(line, "[anon:.bss]") != nullptr ||
        (strchr(line, '/') == nullptr && strchr(line, '[') == nullptr);
}

bool write_all(int fd, const uint8_t* data, size_t size) {
    size_t written = 0;
    while (written < size) {
        const ssize_t count = write(fd, data + written, size - written);
        if (count <= 0) return false;
        written += static_cast<size_t>(count);
    }
    return true;
}

std::string capture(
    const std::string& data_root,
    std::vector<std::string>* output_paths
) {
    if (output_paths == nullptr) return "invalid-output";
    const std::string directory = data_root + "/.nestspace/process-dex";
    mkdir(directory.c_str(), 0700);

    FILE* maps = fopen("/proc/self/maps", "re");
    if (maps == nullptr) return "maps-open-failed";
    std::unordered_set<uint64_t> seen;
    char line[2048];
    size_t mapping_count = 0;
    while (fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long start_value = 0;
        unsigned long long end_value = 0;
        char permissions[8] = {};
        if (sscanf(
                line,
                "%llx-%llx %7s",
                &start_value,
                &end_value,
                permissions
            ) != 3 ||
            !mapping_candidate(line, permissions, data_root.c_str())) {
            continue;
        }
        const uintptr_t start = static_cast<uintptr_t>(start_value);
        const uintptr_t end = static_cast<uintptr_t>(end_value);
        if (end <= start || end - start < 0x70 ||
            end - start > kMaxDexSize) {
            continue;
        }
        ++mapping_count;
        const size_t mapping_size = end - start;
        std::vector<uint8_t> chunk(kChunkSize + 0x70);
        size_t offset = 0;
        while (offset < mapping_size) {
            const size_t primary = std::min(
                kChunkSize,
                mapping_size - offset
            );
            const size_t request = std::min(
                mapping_size - offset,
                primary + 0x70
            );
            if (!read_own_memory(
                    start + offset,
                    chunk.data(),
                    request
                )) {
                break;
            }
            for (size_t index = 0; index + 0x70 <= request; ++index) {
                if (memcmp(chunk.data() + index, "dex\n", 4) != 0 ||
                    chunk[index + 7] != 0) {
                    continue;
                }
                const uint32_t file_size =
                    read_u32(chunk.data() + index + 32);
                const uint32_t header_size =
                    read_u32(chunk.data() + index + 36);
                const uintptr_t address = start + offset + index;
                if (file_size < 0x70 ||
                    file_size > kMaxDexSize ||
                    header_size < 0x70 ||
                    header_size > file_size ||
                    address + file_size > end) {
                    continue;
                }
                std::vector<uint8_t> dex(file_size);
                if (!read_own_memory(address, dex.data(), dex.size()) ||
                    !valid_dex_checksum(dex.data(), dex.size())) {
                    continue;
                }
                const uint64_t digest = stable_hash(
                    dex.data(),
                    dex.size()
                );
                if (!seen.insert(digest).second) continue;

                char name[32] = {};
                snprintf(name, sizeof(name), "%016llx.dex",
                    static_cast<unsigned long long>(digest));
                const std::string path = directory + "/" + name;
                const int fd = open(
                    path.c_str(),
                    O_CREAT | O_WRONLY | O_CLOEXEC,
                    0600
                );
                if (fd >= 0) {
                    const off_t existing_size = lseek(fd, 0, SEEK_END);
                    if (existing_size != static_cast<off_t>(dex.size())) {
                        ftruncate(fd, 0);
                        lseek(fd, 0, SEEK_SET);
                        if (!write_all(fd, dex.data(), dex.size())) {
                            close(fd);
                            continue;
                        }
                    }
                    fchmod(fd, 0400);
                    close(fd);
                    output_paths->push_back(path);
                }
            }
            if (primary >= mapping_size - offset) break;
            offset += primary;
        }
    }
    fclose(maps);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        kTag,
        "captured mappings=%zu dex=%zu",
        mapping_count,
        output_paths->size()
    );
    return {};
}

int capture_executable_mappings(const std::string& data_root) {
    const std::string directory = data_root + "/.nestspace/runtime-code";
    mkdir(directory.c_str(), 0700);
    FILE* maps = fopen("/proc/self/maps", "re");
    if (maps == nullptr) return 0;
    char line[2048];
    int captured = 0;
    while (fgets(line, sizeof(line), maps) != nullptr) {
        unsigned long long start_value = 0;
        unsigned long long end_value = 0;
        char permissions[8] = {};
        if (sscanf(
                line,
                "%llx-%llx %7s",
                &start_value,
                &end_value,
                permissions
            ) != 3 ||
            !executable_mapping_candidate(line, permissions) ||
            end_value <= start_value ||
            end_value - start_value > 64U * 1024U * 1024U) {
            continue;
        }
        const size_t size = static_cast<size_t>(end_value - start_value);
        std::vector<uint8_t> data(size);
        if (!read_own_memory(
                static_cast<uintptr_t>(start_value),
                data.data(),
                data.size()
            )) {
            continue;
        }
        char name[64] = {};
        snprintf(name, sizeof(name), "%llx-%llx.bin",
            start_value, end_value);
        const std::string path = directory + "/" + name;
        const int fd = open(
            path.c_str(),
            O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC,
            0600
        );
        if (fd < 0) continue;
        const bool written = write_all(fd, data.data(), data.size());
        fchmod(fd, 0400);
        close(fd);
        if (written) ++captured;
    }
    fclose(maps);
    __android_log_print(
        ANDROID_LOG_DEBUG,
        kTag,
        "captured executable mappings=%d",
        captured
    );
    return captured;
}

}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeCaptureProcessDex(
    JNIEnv* env,
    jobject,
    jstring data_root
) {
    if (env == nullptr || data_root == nullptr) return nullptr;
    const char* root = env->GetStringUTFChars(data_root, nullptr);
    if (root == nullptr) return nullptr;
    std::vector<std::string> paths;
    const std::string error = capture(root, &paths);
    env->ReleaseStringUTFChars(data_root, root);
    if (!error.empty()) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kTag,
            "capture failed: %s",
            error.c_str()
        );
    }
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(paths.size()),
        string_class,
        nullptr
    );
    env->DeleteLocalRef(string_class);
    if (result == nullptr) return nullptr;
    for (size_t index = 0; index < paths.size(); ++index) {
        jstring path = env->NewStringUTF(paths[index].c_str());
        if (path == nullptr) continue;
        env->SetObjectArrayElement(
            result,
            static_cast<jsize>(index),
            path
        );
        env->DeleteLocalRef(path);
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_coke_nestspace_container_runtime_NativeEnvironmentBridge_nativeDumpRuntimeCode(
    JNIEnv* env,
    jobject,
    jstring data_root
) {
    if (env == nullptr || data_root == nullptr) return 0;
    const char* root = env->GetStringUTFChars(data_root, nullptr);
    if (root == nullptr) return 0;
    const int count = capture_executable_mappings(root);
    env->ReleaseStringUTFChars(data_root, root);
    return count;
}
