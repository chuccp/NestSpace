#include "nestspace/native_environment_internal.h"

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <limits.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <unordered_set>
#include <utility>
#include <vector>

namespace {

constexpr const char* kTag = "NestSpaceDeps";
constexpr int kMaxDependencyDepth = 32;

template <typename Ehdr, typename Phdr, typename Dyn, int kElfClass>
bool virtual_to_offset(
    const std::vector<uint8_t>& image,
    const std::vector<Phdr>& headers,
    uint64_t address,
    uint64_t* offset
) {
    for (const Phdr& header : headers) {
        if (header.p_type != PT_LOAD || address < header.p_vaddr) continue;
        const uint64_t relative = address - header.p_vaddr;
        if (relative >= header.p_memsz) continue;
        const uint64_t result = header.p_offset + relative;
        if (result >= image.size()) return false;
        *offset = result;
        return true;
    }
    return false;
}

template <typename Ehdr, typename Phdr, typename Dyn, int kElfClass>
std::vector<std::string> read_dynamic_dependencies(
    const std::vector<uint8_t>& image
) {
    if (image.size() < sizeof(Ehdr)) return {};
    Ehdr header{};
    std::memcpy(&header, image.data(), sizeof(header));
    if (std::memcmp(header.e_ident, ELFMAG, SELFMAG) != 0 ||
        header.e_ident[EI_CLASS] != kElfClass ||
        header.e_phentsize < sizeof(Phdr) ||
        header.e_phnum == 0 ||
        header.e_phoff > image.size() ||
        static_cast<uint64_t>(header.e_phentsize) * header.e_phnum >
            image.size() - header.e_phoff) {
        return {};
    }

    std::vector<Phdr> headers;
    headers.reserve(header.e_phnum);
    for (uint16_t index = 0; index < header.e_phnum; ++index) {
        Phdr program{};
        const uint64_t offset =
            header.e_phoff + static_cast<uint64_t>(index) * header.e_phentsize;
        std::memcpy(&program, image.data() + offset, sizeof(program));
        headers.push_back(program);
    }

    const Phdr* dynamic_header = nullptr;
    for (const Phdr& candidate : headers) {
        if (candidate.p_type == PT_DYNAMIC) {
            dynamic_header = &candidate;
            break;
        }
    }
    if (dynamic_header == nullptr ||
        dynamic_header->p_offset >= image.size() ||
        dynamic_header->p_filesz > image.size() - dynamic_header->p_offset ||
        dynamic_header->p_filesz < sizeof(Dyn)) {
        return {};
    }

    uint64_t string_table_address = 0;
    std::vector<uint64_t> name_offsets;
    const uint64_t count = dynamic_header->p_filesz / sizeof(Dyn);
    for (uint64_t index = 0; index < count; ++index) {
        Dyn entry{};
        const uint64_t offset = dynamic_header->p_offset + index * sizeof(Dyn);
        std::memcpy(&entry, image.data() + offset, sizeof(entry));
        if (entry.d_tag == DT_NULL) break;
        if (entry.d_tag == DT_STRTAB) {
            string_table_address = entry.d_un.d_ptr;
        } else if (entry.d_tag == DT_NEEDED) {
            name_offsets.push_back(entry.d_un.d_val);
        }
    }
    if (string_table_address == 0 || name_offsets.empty()) return {};

    uint64_t string_table_offset = 0;
    if (!virtual_to_offset<Ehdr, Phdr, Dyn, kElfClass>(
            image,
            headers,
            string_table_address,
            &string_table_offset)) {
        return {};
    }

    std::vector<std::string> result;
    result.reserve(name_offsets.size());
    for (uint64_t name_offset : name_offsets) {
        const uint64_t offset = string_table_offset + name_offset;
        if (offset >= image.size()) continue;
        const char* begin = reinterpret_cast<const char*>(image.data() + offset);
        const size_t remaining = image.size() - offset;
        const size_t length = strnlen(begin, remaining);
        if (length == 0 || length == remaining) continue;
        result.emplace_back(begin, length);
    }
    return result;
}

std::vector<std::string> read_dependencies(const std::string& path) {
    std::ifstream stream(path, std::ios::binary);
    if (!stream) return {};
    std::vector<uint8_t> image{
        std::istreambuf_iterator<char>(stream),
        std::istreambuf_iterator<char>()
    };
    if (image.size() < EI_NIDENT) return {};
    if (image[EI_CLASS] == ELFCLASS64) {
        return read_dynamic_dependencies<Elf64_Ehdr, Elf64_Phdr, Elf64_Dyn,
            ELFCLASS64>(image);
    }
    if (image[EI_CLASS] == ELFCLASS32) {
        return read_dynamic_dependencies<Elf32_Ehdr, Elf32_Phdr, Elf32_Dyn,
            ELFCLASS32>(image);
    }
    return {};
}

std::string directory_of(const std::string& path) {
    const size_t separator = path.find_last_of('/');
    return separator == std::string::npos ? "." : path.substr(0, separator);
}

std::string file_name_of(const std::string& path) {
    const size_t separator = path.find_last_of('/');
    return separator == std::string::npos ? path : path.substr(separator + 1);
}

bool is_regular_file(const std::string& path) {
    std::ifstream stream(path, std::ios::binary);
    return stream.good();
}

int preload_dependencies(
    const std::string& root,
    std::unordered_set<std::string>* visited,
    nestspace::DlopenFunction open_library,
    int depth
) {
    if (depth > kMaxDependencyDepth) return 0;
    char resolved[PATH_MAX] = {};
    const std::string canonical = realpath(root.c_str(), resolved) != nullptr
        ? resolved
        : root;
    if (!visited->insert(canonical).second) return 0;

    const std::string directory = directory_of(root);
    int loaded = 0;
    for (const std::string& dependency : read_dependencies(root)) {
        if (dependency.find('/') != std::string::npos) continue;
        const std::string candidate = directory + "/" + dependency;
        if (!is_regular_file(candidate)) continue;
        loaded += preload_dependencies(
            candidate,
            visited,
            open_library,
            depth + 1
        );
        if (open_library(candidate.c_str(), RTLD_NOW | RTLD_GLOBAL) != nullptr) {
            ++loaded;
        } else {
            __android_log_print(
                ANDROID_LOG_WARN,
                kTag,
                "preload %s failed: %s",
                dependency.c_str(),
                dlerror()
            );
        }
    }
    return loaded;
}

}  // namespace

namespace nestspace {

int preload_dependency_closure(
    const char* root,
    DlopenFunction open_library
) {
    if (root == nullptr || open_library == nullptr) return 0;
    const std::string root_path(root);
    if (root_path.empty() ||
        root_path.find('/') == std::string::npos) {
        return 0;
    }
    std::unordered_set<std::string> visited;
    const int loaded = preload_dependencies(
        root_path,
        &visited,
        open_library,
        0
    );
    __android_log_print(
        ANDROID_LOG_DEBUG,
        kTag,
        "preloaded %d dependencies for %s",
        loaded,
        file_name_of(root_path).c_str()
    );
    return loaded;
}

}  // namespace nestspace
