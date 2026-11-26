    if (strcmp(name, "getresuid") == 0) {
        return reinterpret_cast<void*>(hook_getresuid);
    }
    if (strcmp(name, "getresgid") == 0) {
        return reinterpret_cast<void*>(hook_getresgid);
    }
    if (strcmp(name, "getgroups") == 0) {
        return reinterpret_cast<void*>(hook_getgroups);
    }
    if (strcmp(name, "open") == 0) return reinterpret_cast<void*>(hook_open);
    if (strcmp(name, "open64") == 0) return reinterpret_cast<void*>(hook_open64);
    if (strcmp(name, "__open_2") == 0) return reinterpret_cast<void*>(hook_open_2);
    if (strcmp(name, "openat") == 0) return reinterpret_cast<void*>(hook_openat);
    if (strcmp(name, "openat64") == 0) return reinterpret_cast<void*>(hook_openat64);
    if (strcmp(name, "__openat_2") == 0) return reinterpret_cast<void*>(hook_openat_2);
    if (strcmp(name, "access") == 0) return reinterpret_cast<void*>(hook_access);
    if (strcmp(name, "faccessat") == 0) return reinterpret_cast<void*>(hook_faccessat);
    if (strcmp(name, "faccessat2") == 0) {
        return reinterpret_cast<void*>(hook_faccessat2);
    }
    if (strcmp(name, "getcwd") == 0) {
        return reinterpret_cast<void*>(hook_getcwd);
    }
    if (strcmp(name, "getxattr") == 0) {
        return reinterpret_cast<void*>(hook_getxattr);
    }
    if (strcmp(name, "lgetxattr") == 0) {
        return reinterpret_cast<void*>(hook_lgetxattr);
    }
    if (strcmp(name, "fgetxattr") == 0) {
        return reinterpret_cast<void*>(hook_fgetxattr);
    }
    if (strcmp(name, "listxattr") == 0) {
        return reinterpret_cast<void*>(hook_listxattr);
    }
    if (strcmp(name, "llistxattr") == 0) {
        return reinterpret_cast<void*>(hook_llistxattr);
    }
    if (strcmp(name, "flistxattr") == 0) {
        return reinterpret_cast<void*>(hook_flistxattr);
    }
    if (strcmp(name, "stat") == 0) return reinterpret_cast<void*>(hook_stat);
    if (strcmp(name, "stat64") == 0) return reinterpret_cast<void*>(hook_stat64);
    if (strcmp(name, "lstat") == 0) return reinterpret_cast<void*>(hook_lstat);
    if (strcmp(name, "lstat64") == 0) return reinterpret_cast<void*>(hook_lstat64);
    if (strcmp(name, "statfs") == 0) return reinterpret_cast<void*>(hook_statfs);
    if (strcmp(name, "statvfs") == 0) return reinterpret_cast<void*>(hook_statvfs);
    if (strcmp(name, "fstat") == 0) return reinterpret_cast<void*>(hook_fstat);
    if (strcmp(name, "fstat64") == 0) {
        return reinterpret_cast<void*>(hook_fstat64);
    }
    if (strcmp(name, "fstatat") == 0) return reinterpret_cast<void*>(hook_fstatat);
    if (strcmp(name, "fstatat64") == 0) {
        return reinterpret_cast<void*>(hook_fstatat64);
    }
    if (strcmp(name, "statx") == 0) return reinterpret_cast<void*>(hook_statx);
    if (strcmp(name, "readlink") == 0) return reinterpret_cast<void*>(hook_readlink);
    if (strcmp(name, "readlinkat") == 0) {
        return reinterpret_cast<void*>(hook_readlinkat);
    }
    if (strcmp(name, "symlink") == 0) {
        return reinterpret_cast<void*>(hook_symlink);
    }
    if (strcmp(name, "symlinkat") == 0) {
        return reinterpret_cast<void*>(hook_symlinkat);
    }
    if (strstr(name, "N7android4base8ReadlinkERK") != nullptr) {
        return reinterpret_cast<void*>(hook_android_base_readlink);
    }
    if (strcmp(name, "__readlink_chk") == 0) {
        return reinterpret_cast<void*>(hook_readlink_chk);
    }
    if (strcmp(name, "__readlinkat_chk") == 0) {
        return reinterpret_cast<void*>(hook_readlinkat_chk);
    }
    if (strcmp(name, "opendir") == 0) return reinterpret_cast<void*>(hook_opendir);
    if (strcmp(name, "mkdir") == 0) return reinterpret_cast<void*>(hook_mkdir);
    if (strcmp(name, "mkdirat") == 0) return reinterpret_cast<void*>(hook_mkdirat);
    if (strcmp(name, "rmdir") == 0) return reinterpret_cast<void*>(hook_rmdir);
    if (strcmp(name, "chmod") == 0) return reinterpret_cast<void*>(hook_chmod);
    if (strcmp(name, "fchmodat") == 0) {
        return reinterpret_cast<void*>(hook_fchmodat);
    }
    if (strcmp(name, "fchmod") == 0) {
        return reinterpret_cast<void*>(hook_fchmod);
    }
    if (strcmp(name, "unlink") == 0) return reinterpret_cast<void*>(hook_unlink);
    if (strcmp(name, "unlinkat") == 0) {
        return reinterpret_cast<void*>(hook_unlinkat);
    }
    if (strcmp(name, "rename") == 0) return reinterpret_cast<void*>(hook_rename);
    if (strcmp(name, "renameat") == 0) {
        return reinterpret_cast<void*>(hook_renameat);
    }
    if (strcmp(name, "realpath") == 0) return reinterpret_cast<void*>(hook_realpath);
    if (strcmp(name, "canonicalize_file_name") == 0) {
        return reinterpret_cast<void*>(hook_canonicalize_file_name);
    }
    if (strcmp(name, "close") == 0) return reinterpret_cast<void*>(hook_close);
    if (strcmp(name, "dup") == 0) return reinterpret_cast<void*>(hook_dup);
    if (strcmp(name, "dup2") == 0) return reinterpret_cast<void*>(hook_dup2);
    if (strcmp(name, "dup3") == 0) return reinterpret_cast<void*>(hook_dup3);
    if (strcmp(name, "fcntl") == 0) return reinterpret_cast<void*>(hook_fcntl);
    if (strcmp(name, "syscall") == 0) return reinterpret_cast<void*>(hook_syscall);
    if (strcmp(name, "malloc") == 0 && object_diagnostics_enabled()) {
        return reinterpret_cast<void*>(hook_malloc);
    }
    if (strcmp(name, "calloc") == 0 && object_diagnostics_enabled()) {
        return reinterpret_cast<void*>(hook_calloc);
    }
    if (strcmp(name, "realloc") == 0 && object_diagnostics_enabled()) {
        return reinterpret_cast<void*>(hook_realloc);
    }
    if (strcmp(name, "free") == 0 && object_diagnostics_enabled()) {
        return reinterpret_cast<void*>(hook_free);
    }
    if (strcmp(name, "memcpy") == 0 && object_diagnostics_enabled()) {
        return reinterpret_cast<void*>(hook_memcpy);
    }
    if (strcmp(name, "memmove") == 0 && object_diagnostics_enabled()) {
        return reinterpret_cast<void*>(hook_memmove);
    }
    if (strcmp(name, "memset") == 0 && object_diagnostics_enabled()) {
        return reinterpret_cast<void*>(hook_memset);
    }
    if (strcmp(name, "strlen") == 0 && string_guard_enabled()) {
        return reinterpret_cast<void*>(hook_strlen);
    }
    if (strcmp(name, "fopen") == 0) return reinterpret_cast<void*>(hook_fopen);
    if (strcmp(name, "fopen64") == 0) return reinterpret_cast<void*>(hook_fopen64);
    if (strcmp(name, "fclose") == 0) return reinterpret_cast<void*>(hook_fclose);
    if (strcmp(name, "__system_property_get") == 0) {
        return reinterpret_cast<void*>(hook_property_get);
    }
    if (strcmp(name, "__system_property_find") == 0) {
        return reinterpret_cast<void*>(hook_property_find);
    }
    if (strcmp(name, "__system_property_read_callback") == 0) {
        return reinterpret_cast<void*>(hook_property_read_callback);
    }
    if (strcmp(name, "__system_property_read") == 0) {
        return reinterpret_cast<void*>(hook_property_read);
    }
    if (strcmp(name, "__system_property_serial") == 0) {
        return reinterpret_cast<void*>(hook_property_serial);
    }
    if (strcmp(name, "dlsym") == 0) {
        return reinterpret_cast<void*>(hook_dlsym);
    }
    if (strcmp(name, "dlvsym") == 0) {
        return reinterpret_cast<void*>(hook_dlvsym);
    }
    if (strcmp(name, "android_dlopen_ext") == 0 ||
        strcmp(name, "__loader_android_dlopen_ext") == 0) {
        return reinterpret_cast<void*>(hook_android_dlopen_ext);
    }
    if (strcmp(name, "dl_iterate_phdr") == 0) {
        return reinterpret_cast<void*>(hook_dl_iterate_phdr);
    }
    if (strcmp(name, "dladdr") == 0) {
        return reinterpret_cast<void*>(hook_dladdr);
    }
    if (strcmp(name, "tgkill") == 0) {
        return reinterpret_cast<void*>(hook_tgkill_entry);
    }
    if (strcmp(name, "kill") == 0) {
        return reinterpret_cast<void*>(hook_kill);
    }
    if (strcmp(name, "raise") == 0) {
        return reinterpret_cast<void*>(hook_raise);
    }
    if (strcmp(name, "abort") == 0) {
        return reinterpret_cast<void*>(hook_abort);
    }
    if (strcmp(name, "exit") == 0) {
        return reinterpret_cast<void*>(hook_exit);
    }
    if (strcmp(name, "_exit") == 0) {
        return reinterpret_cast<void*>(hook_exit_immediately);
    }
    if (strcmp(name, "mmap") == 0 && jit_trace_enabled()) {
        return reinterpret_cast<void*>(hook_mmap);
    }
    if (strcmp(name, "munmap") == 0 &&
        (jit_trace_enabled() || retain_small_code_mappings_enabled())) {
        return reinterpret_cast<void*>(hook_munmap);
    }
    if (strcmp(name, "mprotect") == 0 && jit_trace_enabled()) {
        return reinterpret_cast<void*>(hook_mprotect);
    }
    if (strcmp(name, "__clear_cache") == 0 && jit_trace_enabled()) {
        return reinterpret_cast<void*>(hook_clear_cache);
    }
    if (strcmp(name, "prctl") == 0) {
        return reinterpret_cast<void*>(hook_prctl);
    }
    if (strcmp(name, "ptrace") == 0) {
        return reinterpret_cast<void*>(hook_ptrace);
    }
    return nullptr;
}

void* loader_replacement_for(const char* name) {
    if (name == nullptr) return nullptr;
    if (strcmp(name, "dlopen") == 0) {
        return reinterpret_cast<void*>(hook_dlopen);
    }
    if (strcmp(name, "__loader_dlopen") == 0) {
        return reinterpret_cast<void*>(hook_loader_dlopen);
    }
    if (strcmp(name, "dlsym") == 0) {
        return reinterpret_cast<void*>(hook_dlsym);
    }
    if (strcmp(name, "dlvsym") == 0) {
        return reinterpret_cast<void*>(hook_dlvsym);
    }
    if (strcmp(name, "android_dlopen_ext") == 0 ||
        strcmp(name, "__loader_android_dlopen_ext") == 0) {
        return reinterpret_cast<void*>(hook_android_dlopen_ext);
    }
    return nullptr;
}

}  // namespace nestspace
