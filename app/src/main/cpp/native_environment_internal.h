#pragma once

#include <android/dlext.h>
#include <dirent.h>
#include <dlfcn.h>
#include <jni.h>
#include <link.h>
#include <linux/stat.h>
#include <signal.h>

#include <atomic>
#include <cstdio>
#include <memory>
#include <string>
#include <string_view>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/statvfs.h>
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
    std::string host_base_apk_path;
    std::string host_native_library_dir;
    std::string native_library_dir;
    std::string virtual_native_library_dir;
    std::vector<std::string> runtime_library_dirs;
    std::vector<std::string> virtual_runtime_library_dirs;
    std::vector<std::string> runtime_library_paths;
    std::vector<std::string> virtual_runtime_library_paths;
    std::string actual_base_apk_path;
    std::string virtual_base_apk_path;
    std::vector<std::string> actual_split_apk_paths;
    std::vector<std::string> virtual_split_apk_paths;
    std::vector<int> supplementary_groups;
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
int open_virtual_proc_for_pid(
    const char* path,
    int flags,
    pid_t process_id
);
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

std::string virtualized_proc_content(
    const char* path,
    pid_t self_pid = -1
);
int open_virtual_proc(const char* path, int flags);

using GetuidFunction = uid_t (*)();
using GetgidFunction = gid_t (*)();
using GetresuidFunction = int (*)(uid_t*, uid_t*, uid_t*);
using GetresgidFunction = int (*)(gid_t*, gid_t*, gid_t*);
using GetgroupsFunction = int (*)(int, gid_t*);
using OpenFunction = int (*)(const char*, int, ...);
using OpenatFunction = int (*)(int, const char*, int, ...);
using AccessFunction = int (*)(const char*, int);
using FaccessatFunction = int (*)(int, const char*, int, int);
using GetcwdFunction = char* (*)(char*, size_t);
using XattrFunction = ssize_t (*)(
    const char*,
    const char*,
    void*,
    size_t
);
using ListxattrFunction = ssize_t (*)(const char*, char*, size_t);
using FgetxattrFunction = ssize_t (*)(int, const char*, void*, size_t);
using FlistxattrFunction = ssize_t (*)(int, char*, size_t);
using StatFunction = int (*)(const char*, struct stat*);
using StatfsFunction = int (*)(const char*, struct statfs*);
using StatvfsFunction = int (*)(const char*, struct statvfs*);
using FstatFunction = int (*)(int, struct stat*);
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
using MkdirFunction = int (*)(const char*, mode_t);
using MkdiratFunction = int (*)(int, const char*, mode_t);
using RmdirFunction = int (*)(const char*);
using ChmodFunction = int (*)(const char*, mode_t);
using FchmodatFunction = int (*)(int, const char*, mode_t, int);
using FchmodFunction = int (*)(int, mode_t);
using UnlinkFunction = int (*)(const char*);
using UnlinkatFunction = int (*)(int, const char*, int);
using RenameFunction = int (*)(const char*, const char*);
using RenameatFunction = int (*)(int, const char*, int, const char*);
using RealpathFunction = char* (*)(const char*, char*);
using CanonicalizeFunction = char* (*)(const char*);
using CloseFunction = int (*)(int);
using DupFunction = int (*)(int);
using Dup2Function = int (*)(int, int);
using Dup3Function = int (*)(int, int, int);
using FcntlFunction = int (*)(int, int, ...);
using SyscallFunction = long (*)(long, ...);
using MallocFunction = void* (*)(size_t);
using CallocFunction = void* (*)(size_t, size_t);
using ReallocFunction = void* (*)(void*, size_t);
using FreeFunction = void (*)(void*);
using MemcpyFunction = void* (*)(void*, const void*, size_t);
using MemmoveFunction = void* (*)(void*, const void*, size_t);
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
using SigactionFunction = int (*)(
    int,
    const struct sigaction*,
    struct sigaction*
);
using SignalFunction = sighandler_t (*)(int, sighandler_t);
using MemsetFunction = void* (*)(void*, int, size_t);
using DlopenFunction = void* (*)(const char*, int);
using TgkillFunction = int (*)(pid_t, pid_t, int);
using KillFunction = int (*)(pid_t, int);
using RaiseFunction = int (*)(int);
using AbortFunction = void (*)();
using ExitFunction = void (*)(int);
using MmapFunction = void* (*)(
    void*,
    size_t,
    int,
    int,
    int,
    off_t
);
using MunmapFunction = int (*)(void*, size_t);
using MprotectFunction = int (*)(void*, size_t, int);
using ClearCacheFunction = void (*)(void*, void*);
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
    GetgidFunction getgid = nullptr;
    GetgidFunction getegid = nullptr;
    GetresuidFunction getresuid = nullptr;
    GetresgidFunction getresgid = nullptr;
    GetgroupsFunction getgroups = nullptr;
    OpenFunction open = nullptr;
    OpenFunction open64 = nullptr;
    OpenatFunction openat = nullptr;
    OpenatFunction openat64 = nullptr;
    AccessFunction access = nullptr;
    FaccessatFunction faccessat = nullptr;
    FaccessatFunction faccessat2 = nullptr;
    GetcwdFunction getcwd = nullptr;
    XattrFunction getxattr = nullptr;
    XattrFunction lgetxattr = nullptr;
    FgetxattrFunction fgetxattr = nullptr;
    ListxattrFunction listxattr = nullptr;
    ListxattrFunction llistxattr = nullptr;
    FlistxattrFunction flistxattr = nullptr;
    StatFunction stat = nullptr;
    StatFunction stat64 = nullptr;
    StatFunction lstat = nullptr;
    StatFunction lstat64 = nullptr;
    StatfsFunction statfs = nullptr;
    StatvfsFunction statvfs = nullptr;
    FstatFunction fstat = nullptr;
    FstatFunction fstat64 = nullptr;
    FstatatFunction fstatat = nullptr;
    FstatatFunction fstatat64 = nullptr;
    StatxFunction statx = nullptr;
    ReadlinkFunction readlink = nullptr;
    ReadlinkatFunction readlinkat = nullptr;
    OpendirFunction opendir = nullptr;
    MkdirFunction mkdir = nullptr;
    MkdiratFunction mkdirat = nullptr;
    RmdirFunction rmdir = nullptr;
    ChmodFunction chmod = nullptr;
    FchmodatFunction fchmodat = nullptr;
    FchmodFunction fchmod = nullptr;
    UnlinkFunction unlink = nullptr;
    UnlinkatFunction unlinkat = nullptr;
    RenameFunction rename = nullptr;
    RenameatFunction renameat = nullptr;
    RealpathFunction realpath = nullptr;
    CanonicalizeFunction canonicalize = nullptr;
    CloseFunction close = nullptr;
    DupFunction dup = nullptr;
    Dup2Function dup2 = nullptr;
    Dup3Function dup3 = nullptr;
    FcntlFunction fcntl = nullptr;
    SyscallFunction syscall = nullptr;
    MallocFunction mallocCall = nullptr;
    CallocFunction callocCall = nullptr;
    ReallocFunction reallocCall = nullptr;
    FreeFunction freeCall = nullptr;
    MemcpyFunction memcpyCall = nullptr;
    MemmoveFunction memmoveCall = nullptr;
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
    SigactionFunction sigactionCall = nullptr;
    SignalFunction signalCall = nullptr;
    MemsetFunction memsetCall = nullptr;
    DlopenFunction dlopen = nullptr;
    TgkillFunction tgkill = nullptr;
    KillFunction killProcess = nullptr;
    RaiseFunction raiseSignal = nullptr;
    AbortFunction abortProcess = nullptr;
    ExitFunction exitProcess = nullptr;
    ExitFunction exitImmediately = nullptr;
    MmapFunction mmapCall = nullptr;
    MunmapFunction munmapCall = nullptr;
    MprotectFunction mprotectCall = nullptr;
    ClearCacheFunction clearCacheCall = nullptr;
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
bool is_packer_relocation_action(const struct sigaction* action);
bool packer_relocation_guard_active();
bool is_syscall_gateway_action(const struct sigaction* action);
bool syscall_gateway_active();
void capture_previous_segv_action(const struct sigaction* action);
void install_property_hooks();
void install_jni_registration_hook(JNIEnv* env);
void* hook_dlsym(void* handle, const char* name);
void* hook_dlvsym(void* handle, const char* name, const char* version);
int hook_open(const char* path, int flags, ...);
int hook_open64(const char* path, int flags, ...);
int hook_openat(int dirfd, const char* path, int flags, ...);
int hook_openat64(int dirfd, const char* path, int flags, ...);
int hook_mkdir(const char* path, mode_t mode);
int hook_mkdirat(int dirfd, const char* path, mode_t mode);
int hook_rmdir(const char* path);
int hook_chmod(const char* path, mode_t mode);
int hook_fchmodat(int dirfd, const char* path, mode_t mode, int flags);
int hook_fchmod(int fd, mode_t mode);
void* hook_memset(void* destination, int value, size_t size);
int hook_unlink(const char* path);
int hook_unlinkat(int dirfd, const char* path, int flags);
int hook_rename(const char* old_path, const char* new_path);
int hook_renameat(
    int old_dirfd,
    const char* old_path,
    int new_dirfd,
    const char* new_path
);
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
