# Native runtime layout

The native runtime is organized by responsibility:

- `core/`: environment snapshot, path mapping, process view, and JNI bridge.
- `hooks/libc/`: libc, JNI, property, syscall, and dynamic-loader bridges.
- `hooks/elf/`: target-module PLT/GOT relocation hooks.
- `crash/`: crash tracing, signal handling, and the syscall gateway.

Large modules use ordered files under a local `parts/` directory. These `.inl`
files are implementation fragments of a single translation unit, not standalone
sources: they share anonymous-namespace state and must only be included by their
module entry point. Keep the include order in the entry `.cpp` file.
