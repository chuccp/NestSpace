#include "nestspace/native_environment_internal.h"

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <condition_variable>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace nestspace {
namespace {

constexpr char kLogTag[] = "NativeHooks";

#ifndef DT_ANDROID_RELA
#define DT_ANDROID_RELA 0x60000011
#endif
#ifndef DT_ANDROID_RELASZ
#define DT_ANDROID_RELASZ 0x60000012
#endif

struct PatchedSlot {
    void** address;
    void* original;
};

bool decode_sleb128(
    const uint8_t** cursor,
    const uint8_t* end,
    int64_t* value
) {
    uint64_t result = 0;
    unsigned int shift = 0;
    uint8_t byte = 0;
    do {
        if (*cursor >= end || shift >= 64) return false;
        byte = *(*cursor)++;
        result |= static_cast<uint64_t>(byte & 0x7f) << shift;
        shift += 7;
    } while ((byte & 0x80) != 0);
    if (shift < 64 && (byte & 0x40) != 0) {
        result |= (~uint64_t{0}) << shift;
    }
    *value = static_cast<int64_t>(result);
    return true;
}

bool decode_android_rela(
    const void* packed,
    size_t packed_size,
    std::vector<ElfW(Rela)>* output
) {
    if (packed == nullptr ||
        packed_size < 4 ||
        memcmp(packed, "APS2", 4) != 0) {
        return false;
    }
    const auto* cursor = static_cast<const uint8_t*>(packed) + 4;
    const auto* end = static_cast<const uint8_t*>(packed) + packed_size;
    int64_t total_count = 0;
    int64_t current_offset = 0;
    uint64_t current_info = 0;
    int64_t current_addend = 0;
    if (!decode_sleb128(&cursor, end, &total_count) ||
        !decode_sleb128(&cursor, end, &current_offset) ||
        total_count < 0 ||
        total_count > 1'000'000) {
        return false;
    }
    output->reserve(static_cast<size_t>(total_count));
    for (int64_t processed = 0; processed < total_count;) {
        int64_t group_size = 0;
        int64_t group_flags = 0;
        if (!decode_sleb128(&cursor, end, &group_size) ||
            !decode_sleb128(&cursor, end, &group_flags) ||
            group_size < 0 ||
            group_size > total_count - processed) {
            return false;
        }
        constexpr int64_t kGroupedByInfo = 1;
        constexpr int64_t kGroupedByOffset = 2;
        constexpr int64_t kGroupedByAddend = 4;
        constexpr int64_t kHasAddend = 8;
        int64_t grouped_offset = 0;
        if ((group_flags & kGroupedByOffset) != 0 &&
            !decode_sleb128(&cursor, end, &grouped_offset)) {
            return false;
        }
        if ((group_flags & kGroupedByInfo) != 0) {
            int64_t info = 0;
            if (!decode_sleb128(&cursor, end, &info)) return false;
            current_info = static_cast<uint64_t>(info);
        }
        const int64_t addend_mode =
            group_flags & (kHasAddend | kGroupedByAddend);
        if (addend_mode == (kHasAddend | kGroupedByAddend)) {
            int64_t addend_delta = 0;
            if (!decode_sleb128(&cursor, end, &addend_delta)) return false;
            current_addend += addend_delta;
        } else if (addend_mode != kHasAddend) {
            current_addend = 0;
        }
        for (int64_t index = 0; index < group_size; ++index) {
            if ((group_flags & kGroupedByOffset) != 0) {
                current_offset += grouped_offset;
            } else {
                int64_t offset_delta = 0;
                if (!decode_sleb128(&cursor, end, &offset_delta)) return false;
                current_offset += offset_delta;
            }
            if ((group_flags & kGroupedByInfo) == 0) {
                int64_t info = 0;
                if (!decode_sleb128(&cursor, end, &info)) return false;
                current_info = static_cast<uint64_t>(info);
            }
            if (addend_mode == kHasAddend) {
                int64_t addend_delta = 0;
                if (!decode_sleb128(&cursor, end, &addend_delta)) return false;
                current_addend += addend_delta;
            }
            ElfW(Rela) relocation {};
            relocation.r_offset = static_cast<ElfW(Addr)>(current_offset);
            relocation.r_info = static_cast<ElfW(Xword)>(current_info);
            relocation.r_addend =
                static_cast<ElfW(Sxword)>(current_addend);
            output->push_back(relocation);
        }
        processed += group_size;
    }
    if (!output->empty()) {
        return true;
    }
    return total_count == 0;
}

struct ModuleKey {
    uintptr_t base;
    size_t name_hash;

    bool operator==(const ModuleKey& other) const {
        return base == other.base && name_hash == other.name_hash;
    }
};

struct ModuleKeyHash {
    size_t operator()(const ModuleKey& value) const {
        size_t result = value.base;
        result ^= value.name_hash + 0x9e3779b97f4a7c15ULL +
            (result << 6U) + (result >> 2U);
        return result;
    }
};

struct HookCounters {
    int errors = 0;
};

std::atomic<jint> g_last_hook_event{kHookEventNone};
std::atomic<jlong> g_last_syscall_number{-1};
std::mutex g_hook_lock;
std::vector<PatchedSlot> g_patched_slots;
std::unordered_set<void**> g_patched_addresses;
std::unordered_set<ModuleKey, ModuleKeyHash> g_scanned_modules;
std::unordered_set<ModuleKey, ModuleKeyHash> g_target_modules;
std::unordered_set<ModuleKey, ModuleKeyHash> g_hooked_modules;
HookCounters g_counters;
std::mutex g_refresh_queue_lock;
std::condition_variable g_refresh_queue_ready;
bool g_refresh_requested = false;
bool g_refresh_worker_started = false;

std::string read_file_bytes(const char* path);

ModuleKey module_key(const dl_phdr_info& info) {
    const std::string_view name = info.dlpi_name == nullptr
        ? std::string_view{}
        : std::string_view(info.dlpi_name);
    return ModuleKey{
        static_cast<uintptr_t>(info.dlpi_addr),
        std::hash<std::string_view>{}(name)
    };
}

bool starts_with_prefix(const char* name, const std::string& prefix) {
    if (name == nullptr || prefix.empty()) return false;
    const size_t length = prefix.size();
    return strncmp(name, prefix.c_str(), length) == 0 &&
        (name[length] == '\0' || name[length] == '/');
}

bool matches_prefix(
    const char* name,
    const std::string& prefix,
    const std::string& alias
) {
    return starts_with_prefix(name, prefix) ||
        (!alias.empty() && starts_with_prefix(name, alias));
}

bool target_object(const char* name) {
    if (name == nullptr || name[0] == '\0') return false;
    const std::string_view path(name);
    const size_t slash = path.find_last_of('/');
    const std::string_view basename = slash == std::string_view::npos
        ? path
        : path.substr(slash + 1);
    // Framework file/database resource bridges execute inside system native
    // libraries. Their PLT must be virtualized even though the libraries
    // themselves are not packaged with the target APK. In particular,
    // ResourcesManager loads ApplicationInfo.sourceDir through libandroidfw,
    // so an installed-path view is not complete until this path is readable by
    // the framework's own APK loader.
    if (basename == "libjavacore.so" ||
        basename == "libandroid_runtime.so" ||
        basename == "libandroidfw.so" ||
        basename == "libziparchive.so" ||
        basename == "libsqlite.so" ||
        basename == "libsqlcipher.so") {
        return true;
    }
    // TLS/crypto libraries use their own fork and entropy checks. Rewriting
    // libc/property entry points inside these modules can turn RAND_bytes into
    // a process abort, while they do not need virtual package identity.
    if (basename.find("ssl") != std::string_view::npos ||
        basename.find("crypto") != std::string_view::npos) {
        return false;
    }
    // Downloaded plugin modules are versioned independently from the target
    // APK and commonly ship their own linker/JNI initialization. Rewriting
    // their libc slots can invalidate internal function pointers.
    if (path.find("/files/plugins/") != std::string_view::npos) {
        return false;
    }
    const Environment* environment = current_environment();
    if (environment == nullptr) return false;
    return matches_prefix(
            name,
            environment->native_library_dir,
            environment->native_library_dir_alias
        ) ||
        matches_prefix(
            name,
            environment->data_root,
            environment->data_root_alias
        );
}

uint16_t native_machine() {
#if defined(__aarch64__)
    return EM_AARCH64;
#elif defined(__arm__)
    return EM_ARM;
#elif defined(__x86_64__)
    return EM_X86_64;
#elif defined(__i386__)
    return EM_386;
#else
    return EM_NONE;
#endif
}

bool is_hook_relocation(uint16_t machine, ElfW(Xword) type) {
    switch (machine) {
        case EM_AARCH64:
            // R_AARCH64_ABS64 / GLOB_DAT / JUMP_SLOT.
            return type == 257 || type == 1025 || type == 1026;
        case EM_ARM:
            // R_ARM_ABS32 / GLOB_DAT / JUMP_SLOT.
            return type == 2 || type == 21 || type == 22;
        case EM_X86_64:
            // R_X86_64_64 / GLOB_DAT / JUMP_SLOT.
            return type == 1 || type == 6 || type == 7;
        case EM_386:
            // R_386_32 / GLOB_DAT / JMP_SLOT.
            return type == 1 || type == 6 || type == 7;
        default:
            return false;
    }
}

unsigned int relocation_type(ElfW(Xword) information) {
#if defined(__LP64__)
    return ELF64_R_TYPE(information);
#else
    return ELF32_R_TYPE(information);
#endif
}

unsigned int relocation_symbol(ElfW(Xword) information) {
#if defined(__LP64__)
    return ELF64_R_SYM(information);
#else
    return ELF32_R_SYM(information);
#endif
}

uintptr_t runtime_address(
    const dl_phdr_info& info,
    ElfW(Addr) value
) {
    for (int index = 0; index < info.dlpi_phnum; ++index) {
        const auto& header = info.dlpi_phdr[index];
        if (header.p_type != PT_LOAD) continue;
        const uintptr_t start = info.dlpi_addr + header.p_vaddr;
        const uintptr_t end = start + header.p_memsz;
        if (value >= start && value < end) return static_cast<uintptr_t>(value);
    }
    return info.dlpi_addr + static_cast<uintptr_t>(value);
}

bool write_slot(void** address, void* value) {
    static const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) return false;
    const uintptr_t page = reinterpret_cast<uintptr_t>(address) &
        ~(static_cast<uintptr_t>(page_size) - 1U);
    if (mprotect(
            reinterpret_cast<void*>(page),
            static_cast<size_t>(page_size),
            PROT_READ | PROT_WRITE
        ) == 0) {
        __atomic_store_n(address, value, __ATOMIC_RELEASE);
        return true;
    }

    const int protect_error = errno;
    struct iovec local{&value, sizeof(value)};
    struct iovec remote{address, sizeof(value)};
    const long written = syscall(
        SYS_process_vm_writev,
        getpid(),
        &local,
        1,
        &remote,
        1,
        0
    );
    if (written == static_cast<long>(sizeof(value))) {
        static bool logged_fallback = false;
        if (!logged_fallback) {
            logged_fallback = true;
            __android_log_print(
                ANDROID_LOG_DEBUG,
                kLogTag,
                "patched through process_vm_writev slot=%p",
                reinterpret_cast<void*>(address)
            );
        }
        return true;
    }
    const int write_error = errno;
    if (g_counters.errors < 3) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "slot write failed page=%p slot=%p mprotect_errno=%d write_errno=%d",
            reinterpret_cast<void*>(page),
            reinterpret_cast<void*>(address),
            protect_error,
            write_error
        );
    }
    return false;
}

void patch_slot(void** address, void* replacement) {
    if (address == nullptr || g_patched_addresses.count(address) != 0) return;
    const void* current = __atomic_load_n(address, __ATOMIC_ACQUIRE);
    g_patched_addresses.insert(address);
    if (current == replacement) return;
    if (!write_slot(address, replacement)) {
        g_patched_addresses.erase(address);
        ++g_counters.errors;
        if (g_counters.errors <= 3) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "patch slot failed slot=%p",
                reinterpret_cast<void*>(address)
            );
        }
        return;
    }
    g_patched_slots.push_back(
        PatchedSlot{address, const_cast<void*>(current)}
    );
    __atomic_store_n(address, replacement, __ATOMIC_RELEASE);
}

template <typename Relocation>
void patch_relocations(
    uintptr_t base,
    uint16_t machine,
    const Relocation* relocations,
    size_t size,
    const ElfW(Sym)* symbols,
    const char* strings,
    bool loader_only,
    const char* module_path
) {
    if (relocations == nullptr || symbols == nullptr || strings == nullptr) return;
    (void)module_path;
    const size_t count = size / sizeof(Relocation);
    for (size_t index = 0; index < count; ++index) {
        const auto& relocation = relocations[index];
        const auto type = relocation_type(relocation.r_info);
        if (!is_hook_relocation(machine, type)) continue;
        const auto symbol_index = relocation_symbol(relocation.r_info);
        const char* name = strings + symbols[symbol_index].st_name;
        void* replacement = loader_only
            ? loader_replacement_for(name)
            : replacement_for(name);
        if (replacement == nullptr) continue;
        auto** address = reinterpret_cast<void**>(
            base + relocation.r_offset
        );
        patch_slot(address, replacement);
    }
}

bool hook_object(const dl_phdr_info& info, bool loader_only) {
    const ElfW(Phdr)* dynamic_header = nullptr;
    for (int index = 0; index < info.dlpi_phnum; ++index) {
        if (info.dlpi_phdr[index].p_type == PT_DYNAMIC) {
            dynamic_header = &info.dlpi_phdr[index];
            break;
        }
    }
    if (dynamic_header == nullptr) {
        ++g_counters.errors;
        return false;
    }

    auto* dynamic = reinterpret_cast<const ElfW(Dyn)*>(
        info.dlpi_addr + dynamic_header->p_vaddr
    );
    const ElfW(Sym)* symbols = nullptr;
    const char* strings = nullptr;
    const void* jmprel = nullptr;
    size_t jmprel_size = 0;
    const void* android_rela = nullptr;
    size_t android_rela_size = 0;
    const ElfW(Rel)* rel = nullptr;
    size_t rel_size = 0;
    const ElfW(Rela)* rela = nullptr;
    size_t rela_size = 0;
    for (auto entry = dynamic; entry->d_tag != DT_NULL; ++entry) {
        switch (entry->d_tag) {
            case DT_SYMTAB:
                symbols = reinterpret_cast<const ElfW(Sym)*>(
                    runtime_address(info, entry->d_un.d_ptr)
                );
                break;
            case DT_STRTAB:
                strings = reinterpret_cast<const char*>(
                    runtime_address(info, entry->d_un.d_ptr)
                );
                break;
            case DT_JMPREL:
                jmprel = reinterpret_cast<const void*>(
                    runtime_address(info, entry->d_un.d_ptr)
                );
                break;
            case DT_PLTRELSZ:
                jmprel_size = entry->d_un.d_val;
                break;
            case DT_REL:
                rel = reinterpret_cast<const ElfW(Rel)*>(
                    runtime_address(info, entry->d_un.d_ptr)
                );
                break;
            case DT_RELSZ:
                rel_size = entry->d_un.d_val;
                break;
            case DT_RELA:
                rela = reinterpret_cast<const ElfW(Rela)*>(
                    runtime_address(info, entry->d_un.d_ptr)
                );
                break;
            case DT_RELASZ:
                rela_size = entry->d_un.d_val;
                break;
            case DT_ANDROID_RELA:
                android_rela = reinterpret_cast<const void*>(
                    runtime_address(info, entry->d_un.d_ptr)
                );
                break;
            case DT_ANDROID_RELASZ:
                android_rela_size = entry->d_un.d_val;
                break;
            default:
                break;
        }
    }

    const size_t before = g_patched_slots.size();
    if (jmprel != nullptr && jmprel_size > 0) {
#if defined(__arm__) || defined(__i386__)
        patch_relocations(
            info.dlpi_addr,
            native_machine(),
            static_cast<const ElfW(Rel)*>(jmprel),
            jmprel_size,
            symbols,
            strings,
            loader_only,
            info.dlpi_name
        );
#else
        patch_relocations(
            info.dlpi_addr,
            native_machine(),
            static_cast<const ElfW(Rela)*>(jmprel),
            jmprel_size,
            symbols,
            strings,
            loader_only,
            info.dlpi_name
        );
#endif
    }
    patch_relocations(
        info.dlpi_addr,
        native_machine(),
        rel,
        rel_size,
        symbols,
        strings,
        loader_only,
        info.dlpi_name
    );
    patch_relocations(
        info.dlpi_addr,
        native_machine(),
        rela,
        rela_size,
        symbols,
        strings,
        loader_only,
        info.dlpi_name
    );
    if (android_rela != nullptr && android_rela_size > 0) {
        std::vector<ElfW(Rela)> decoded;
        decode_android_rela(
                android_rela,
                android_rela_size,
                &decoded
            );
        if (!decoded.empty()) {
            patch_relocations(
                info.dlpi_addr,
                native_machine(),
                decoded.data(),
                decoded.size() * sizeof(ElfW(Rela)),
                symbols,
                strings,
                loader_only,
                info.dlpi_name
            );
        }
    }
    return g_patched_slots.size() > before;
}

struct MappedModule {
    std::string path;
    uintptr_t base = UINTPTR_MAX;
    uintptr_t end = 0;
};

std::string read_file_bytes(const char* path) {
    const long fd = syscall(
        SYS_openat,
        AT_FDCWD,
        path,
        O_RDONLY | O_CLOEXEC,
        0
    );
    if (fd < 0) return {};
    struct stat status{};
    if (fstat(static_cast<int>(fd), &status) != 0 ||
        status.st_size > 256 * 1024 * 1024) {
        close(static_cast<int>(fd));
        return {};
    }
    std::string value(
        status.st_size > 0 ? static_cast<size_t>(status.st_size) : 0,
        '\0'
    );
    size_t offset = 0;
    while (offset < 256 * 1024 * 1024) {
        if (offset == value.size()) {
            value.resize(value.size() + 16 * 1024);
        }
        const ssize_t count = read(
            static_cast<int>(fd),
            value.data() + offset,
            value.size() - offset
        );
        if (count <= 0) break;
        offset += static_cast<size_t>(count);
    }
    close(static_cast<int>(fd));
    value.resize(offset);
    return value;
}

bool file_offset_for_vaddr(
    const ElfW(Phdr)* headers,
    size_t count,
    ElfW(Addr) address,
    size_t* result
) {
    for (size_t index = 0; index < count; ++index) {
        const auto& header = headers[index];
        if (header.p_type != PT_LOAD) continue;
        if (address < header.p_vaddr ||
            address >= header.p_vaddr + header.p_filesz) {
            continue;
        }
        *result = header.p_offset + (address - header.p_vaddr);
        return true;
    }
    return false;
}

bool hook_elf_file(const std::string& path, uintptr_t base) {
    const std::string bytes = read_file_bytes(path.c_str());
    if (bytes.size() < sizeof(ElfW(Ehdr))) return false;
    const auto* header = reinterpret_cast<const ElfW(Ehdr)*>(bytes.data());
    if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0 ||
        header->e_phoff == 0 ||
        header->e_phnum == 0 ||
        header->e_phoff + sizeof(ElfW(Phdr)) * header->e_phnum > bytes.size()) {
        return false;
    }
    const auto* headers = reinterpret_cast<const ElfW(Phdr)*>(
        bytes.data() + header->e_phoff
    );
    const ElfW(Phdr)* dynamic_header = nullptr;
    for (size_t index = 0; index < header->e_phnum; ++index) {
        if (headers[index].p_type == PT_DYNAMIC) {
            dynamic_header = &headers[index];
            break;
        }
    }
    if (dynamic_header == nullptr ||
        dynamic_header->p_offset + dynamic_header->p_filesz > bytes.size()) {
        return false;
    }

    const auto* dynamic = reinterpret_cast<const ElfW(Dyn)*>(
        bytes.data() + dynamic_header->p_offset
    );
    const ElfW(Sym)* symbols = nullptr;
    const char* strings = nullptr;
    const ElfW(Rel)* rel = nullptr;
    size_t rel_size = 0;
    const ElfW(Rela)* rela = nullptr;
    size_t rela_size = 0;
    const void* jmprel = nullptr;
    size_t jmprel_size = 0;
    ElfW(Sword) plt_kind = 0;
    for (auto entry = dynamic; entry->d_tag != DT_NULL; ++entry) {
        size_t offset = 0;
        switch (entry->d_tag) {
            case DT_SYMTAB:
                if (file_offset_for_vaddr(
                        headers,
                        header->e_phnum,
                        entry->d_un.d_ptr,
                        &offset
                    )) {
                    symbols = reinterpret_cast<const ElfW(Sym)*>(
                        bytes.data() + offset
                    );
                }
                break;
            case DT_STRTAB:
                if (file_offset_for_vaddr(
                        headers,
                        header->e_phnum,
                        entry->d_un.d_ptr,
                        &offset
                    )) {
                    strings = bytes.data() + offset;
                }
                break;
            case DT_PLTREL:
                plt_kind = entry->d_un.d_val;
                break;
            case DT_JMPREL:
                if (file_offset_for_vaddr(
                        headers,
                        header->e_phnum,
                        entry->d_un.d_ptr,
                        &offset
                    )) {
                    jmprel = bytes.data() + offset;
                }
                break;
            case DT_PLTRELSZ:
                jmprel_size = entry->d_un.d_val;
                break;
            case DT_REL:
                if (file_offset_for_vaddr(
                        headers,
                        header->e_phnum,
                        entry->d_un.d_ptr,
                        &offset
                    )) {
                    rel = reinterpret_cast<const ElfW(Rel)*>(
                        bytes.data() + offset
                    );
                }
                break;
            case DT_RELSZ:
                rel_size = entry->d_un.d_val;
                break;
            case DT_RELA:
                if (file_offset_for_vaddr(
                        headers,
                        header->e_phnum,
                        entry->d_un.d_ptr,
                        &offset
                    )) {
                    rela = reinterpret_cast<const ElfW(Rela)*>(
                        bytes.data() + offset
                    );
                }
                break;
            case DT_RELASZ:
                rela_size = entry->d_un.d_val;
                break;
            default:
                break;
        }
    }
    if (symbols == nullptr || strings == nullptr) return false;
    const size_t before = g_patched_slots.size();
    if (jmprel != nullptr && jmprel_size > 0) {
        if (plt_kind == DT_RELA) {
            patch_relocations(
                base,
                header->e_machine,
                static_cast<const ElfW(Rela)*>(jmprel),
                jmprel_size,
                symbols,
                strings,
                false,
                path.c_str()
            );
        } else if (plt_kind == DT_REL) {
            patch_relocations(
                base,
                header->e_machine,
                static_cast<const ElfW(Rel)*>(jmprel),
                jmprel_size,
                symbols,
                strings,
                false,
                path.c_str()
            );
        }
    }
    patch_relocations(
        base,
        header->e_machine,
        rel,
        rel_size,
        symbols,
        strings,
        false,
        path.c_str()
    );
    patch_relocations(
        base,
        header->e_machine,
        rela,
        rela_size,
        symbols,
        strings,
        false,
        path.c_str()
    );
    return g_patched_slots.size() > before;
}
