#pragma once

#include <android/dlext.h>
#include <dirent.h>
#include <dlfcn.h>
#include <jni.h>
#include <link.h>
#include <linux/stat.h>

#include <atomic>
#include <cstdio>
#include <memory>
#include <string>
#include <string_view>
#include <sys/stat.h>
#include <sys/types.h>
#include <unordered_map>
#include <vector>

namespace nestspace {

constexpr jlong kProbeStatusReadable = 1LL << 0;
constexpr jlong kProbeMapsReadable = 1LL << 1;
constexpr jlong kProbePropertyReadable = 1LL << 2;
constexpr jlong kProbeMountInfoReadable = 1LL << 3;
constexpr jlong kProbeSelinuxReadable = 1LL << 4;
constexpr jlong kProbeDirectOpenReadable = 1LL << 5;
constexpr jlong kProbeDirectStatReadable = 1LL << 6;

constexpr jint kHookEventNone = 0;
constexpr jint kHookEventUid = 1;
constexpr jint kHookEventFile = 2;
constexpr jint kHookEventProc = 3;
constexpr jint kHookEventProperty = 4;
constexpr jint kHookEventDirectSyscall = 5;
constexpr jint kHookEventLoader = 6;

struct VirtualPropertyInfo {
    std::string name;
    std::string value;
    uint32_t serial = 1;
};

struct PropertySnapshot {
    std::vector<std::unique_ptr<VirtualPropertyInfo>> owned;
    std::vector<const VirtualPropertyInfo*> by_name;
    std::unordered_map<const void*, const VirtualPropertyInfo*> by_pointer;

    const VirtualPropertyInfo* find(std::string_view name) const;
    const VirtualPropertyInfo* find(const void* pointer) const;
};

struct Environment {
    std::string instance_id;
    std::string package_name;
    std::string host_package_name;
    std::string data_root;
    std::string external_data_root;
    std::string process_name;
    std::string native_library_dir;
    std::string virtual_native_library_dir;
    std::string actual_base_apk_path;
    std::string virtual_base_apk_path;
    std::vector<std::string> actual_split_apk_paths;
    std::vector<std::string> virtual_split_apk_paths;
    std::string target_data_root;
    std::string target_data_alias;
    std::string target_device_root;
    std::string target_external_root;
    std::string target_external_alias;
    std::string actual_device_root;
    std::string native_library_dir_alias;
    std::string data_root_alias;
    std::shared_ptr<const PropertySnapshot> property_snapshot;
    jint host_uid = -1;
    jint observed_uid = -1;
    jint virtual_pid = -1;
    jlong identity_revision = 0;
    jlong capability_mask = 0;
};

extern std::atomic<int> g_virtual_proc_reads;
extern std::atomic<int> g_property_reads;
extern std::atomic<int> g_dlopen_calls;

const Environment* current_environment();
void publish_environment(std::unique_ptr<Environment> environment);
void clear_environment();

std::shared_ptr<const PropertySnapshot> build_property_snapshot(
    const std::unordered_map<std::string, std::string>& properties
);

bool can_open_for_read(const char* path);
bool can_syscall_open_for_read(const char* path);
bool can_syscall_stat(const char* path);
jint read_status_uid();

bool rewrite_target_path(const char* path, std::string* rewritten);
void virtualize_target_file_stat(const char* actual, struct stat* value);
void virtualize_target_statx(const char* actual, struct statx* value);
bool virtual_path_from_actual(
    const char* actual,
    char* buffer,
    size_t size,
    ssize_t* result
);
bool proc_fd_number(const char* path, int* fd);
const char* fd_virtual_path(int fd);
void copy_fd_path(int old_fd, int new_fd);
void erase_fd_path(int fd);
void clear_fd_paths();

std::string virtualized_proc_content(const char* path);
int open_virtual_proc(const char* path, int flags);

using GetuidFunction = uid_t (*)();
using OpenFunction = int (*)(const char*, int, ...);
using OpenatFunction = int (*)(int, const char*, int, ...);
using AccessFunction = int (*)(const char*, int);
using FaccessatFunction = int (*)(int, const char*, int, int);
using StatFunction = int (*)(const char*, struct stat*);
using FstatatFunction = int (*)(int, const char*, struct stat*, int);
using StatxFunction = int (*)(
    int,
    const char*,
    int,
    unsigned int,
    struct statx*
);
using ReadlinkFunction = ssize_t (*)(const char*, char*, size_t);
using ReadlinkatFunction = ssize_t (*)(int, const char*, char*, size_t);
using OpendirFunction = DIR* (*)(const char*);
using RealpathFunction = char* (*)(const char*, char*);
using CanonicalizeFunction = char* (*)(const char*);
using CloseFunction = int (*)(int);
using DupFunction = int (*)(int);
using Dup2Function = int (*)(int, int);
using Dup3Function = int (*)(int, int, int);
using FcntlFunction = int (*)(int, int, ...);
using SyscallFunction = long (*)(long, ...);
using FopenFunction = FILE* (*)(const char*, const char*);
using FdopenFunction = FILE* (*)(int, const char*);
using FcloseFunction = int (*)(FILE*);
using FilenoFunction = int (*)(FILE*);
using PropertyGetFunction = int (*)(const char*, char*);
using PropertyFindFunction = const void* (*)(const char*);
using PropertyCallback = void (*)(void*, const char*, const char*, uint32_t);
using PropertyReadCallbackFunction = void (*)(
    const void*,
    PropertyCallback,
    void*
);
using PropertyReadFunction = int (*)(const void*, char*, char*);
using PropertySerialFunction = uint32_t (*)(const void*);
using DlopenFunction = void* (*)(const char*, int);
using TgkillFunction = int (*)(pid_t, pid_t, int);
using ExitFunction = void (*)(int);
using StackCheckFailFunction = void (*)();
using PtraceFunction = long (*)(int, pid_t, void*, void*);
using PrctlFunction = int (*)(int, unsigned long, unsigned long, unsigned long, unsigned long);
using DlsymFunction = void* (*)(void*, const char*);
using DlvsymFunction = void* (*)(void*, const char*, const char*);
using DlIteratePhdrCallback = int (*)(struct dl_phdr_info*, size_t, void*);
using DlIteratePhdrFunction = int (*)(DlIteratePhdrCallback, void*);
using DladdrFunction = int (*)(const void*, Dl_info*);
using AndroidDlopenExtFunction = void* (*)(
    const char*,
    int,
    const android_dlextinfo*
);

struct RealFunctions {
    GetuidFunction getuid = nullptr;
    GetuidFunction geteuid = nullptr;
    OpenFunction open = nullptr;
    OpenFunction open64 = nullptr;
    OpenatFunction openat = nullptr;
    OpenatFunction openat64 = nullptr;
    AccessFunction access = nullptr;
    FaccessatFunction faccessat = nullptr;
    StatFunction stat = nullptr;
    StatFunction lstat = nullptr;
    FstatatFunction fstatat = nullptr;
    StatxFunction statx = nullptr;
    ReadlinkFunction readlink = nullptr;
    ReadlinkatFunction readlinkat = nullptr;
    OpendirFunction opendir = nullptr;
    RealpathFunction realpath = nullptr;
    CanonicalizeFunction canonicalize = nullptr;
    CloseFunction close = nullptr;
    DupFunction dup = nullptr;
    Dup2Function dup2 = nullptr;
    Dup3Function dup3 = nullptr;
    FcntlFunction fcntl = nullptr;
    SyscallFunction syscall = nullptr;
    FopenFunction fopen = nullptr;
    FopenFunction fopen64 = nullptr;
    FdopenFunction fdopen = nullptr;
    FcloseFunction fclose = nullptr;
    FilenoFunction fileno = nullptr;
    PropertyGetFunction propertyGet = nullptr;
    PropertyFindFunction propertyFind = nullptr;
    PropertyReadCallbackFunction propertyReadCallback = nullptr;
    PropertyReadFunction propertyRead = nullptr;
    PropertySerialFunction propertySerial = nullptr;
    DlopenFunction dlopen = nullptr;
    TgkillFunction tgkill = nullptr;
    ExitFunction exitProcess = nullptr;
    ExitFunction exitImmediately = nullptr;
    StackCheckFailFunction stackCheckFail = nullptr;
    PtraceFunction ptraceCall = nullptr;
    PrctlFunction prctlCall = nullptr;
    DlsymFunction dlsym = nullptr;
    DlvsymFunction dlvsym = nullptr;
    DlIteratePhdrFunction dlIteratePhdr = nullptr;
    DladdrFunction dladdr = nullptr;
    AndroidDlopenExtFunction androidDlopenExt = nullptr;
};

extern RealFunctions g_real;

void initialize_real_functions();
void install_loader_symbol_hooks();
void install_libc_path_hooks();
void install_process_signal_hooks();
void install_property_hooks();
void* hook_dlsym(void* handle, const char* name);
void* hook_dlvsym(void* handle, const char* name, const char* version);
int hook_open(const char* path, int flags, ...);
int hook_open64(const char* path, int flags, ...);
int hook_openat(int dirfd, const char* path, int flags, ...);
int hook_openat64(int dirfd, const char* path, int flags, ...);
extern "C" int hook_tgkill_entry(pid_t group, pid_t thread, int signal);
long hook_syscall(long number, ...);
void hook_exit(int status);
void hook_exit_immediately(int status);
void hook_stack_check_fail();
long hook_ptrace(int request, pid_t pid, void* address, void* data);
int hook_prctl(
    int option,
    unsigned long argument2,
    unsigned long argument3,
    unsigned long argument4,
    unsigned long argument5
);
void* replacement_for(const char* name);
void* loader_replacement_for(const char* name);

int hook_property_get(const char* name, char* value);
const void* hook_property_find(const char* name);
void hook_property_read_callback(
    const void* property,
    PropertyCallback callback,
    void* cookie
);
int hook_property_read(const void* property, char* name, char* value);
uint32_t hook_property_serial(const void* property);

struct HookState {
    int patch_count = 0;
    int hooked_modules = 0;
    int scanned_modules = 0;
    int target_modules = 0;
    int errors = 0;
    int virtual_proc_reads = 0;
    int property_reads = 0;
    int dlopen_calls = 0;
    jint last_hook_event = kHookEventNone;
    jlong last_syscall_number = -1;
};

void record_hook_event(jint event);
void record_syscall_event(jlong number);
HookState refresh_hooks(bool scan_elf_fallback);
void refresh_loaded_modules();
void request_loaded_modules_refresh();
HookState hook_state();
void restore_hooks();

}  // namespace nestspace
