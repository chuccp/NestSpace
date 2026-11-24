# 容器原生完整性检测逆向与验证总结

## 1. 文档目的

本文总结近期围绕 Android 容器运行时、目标应用完整性检测、原生库风险探针和测试
应用扩展所做的工作。文档只描述通用 Android 语义、原生检测路径和验证结果，不记录
目标应用的名称、包名或专用绕过逻辑。

本次工作的两个基本边界是：

- 生产容器只实现通用 Android 运行语义，不为单个目标应用增加专用判断或补丁。
- 目标应用相关的检测特征只允许出现在测试应用中，用于定位缺口和验证通用语义。

## 2. 目标与问题背景

目标应用在容器中启动后，会在登录阶段读取运行环境并执行完整性判断。过程中出现过以下
问题：

- 页面启动阶段发生 ANR，无法稳定进入登录页。
- 登录页能够显示，但后续界面点击可能无响应。
- Java 栈中存在宿主运行时帧。
- 原生层能够观察宿主的映射路径、进程身份或运行时模块。
- 测试应用虽然已有大量平台探针，但仍缺少部分原生检测面的直接验证。
- 目标应用最终仍会给出“非官方客户端”判断，并主动结束进程。

后续修复和验证重点因此分为三层：

1. 修正容器启动、页面生命周期和 Java 栈语义。
2. 补齐目标应用可观察到的通用 Android 原生接口行为。
3. 用测试应用近似还原完整性 SDK 的检测输入，确认容器没有明显暴露面。

## 3. 已完成的运行时修复

### 3.1 类加载器与包管理语义

- 目标 ClassLoader 恢复为标准的
  `PathClassLoader -> BootClassLoader` 结构。
- 目标侧 PackageManager 恢复为标准应用可见的
  `android.app.ApplicationPackageManager` 形态。
- 统一虚拟实例根目录、设备保护目录和反向路径映射。
- 修正包名、进程名、操作包名和安装来源等身份字段。
- 支持多进程下的应用身份、存储目录和 PackageManager 视图。

### 3.2 Activity 生命周期

- 修复父 Activity 启动子页面后立即结束所导致的黑屏。
- 修复部分页面启动阶段的阻塞和主线程 futex 等待。
- 将栈过滤路径移出容易与目标运行时同步窗口重叠的 Java 回调路径。

### 3.3 Java 栈虚拟化

- 在 `Throwable.nativeGetStackTrace` 和
  `VMStack.getThreadStackTrace` 的出口过滤宿主 Java 帧。
- 通过加载的 `libart.so` 动态解析 ART 相关符号。
- 直接读取 `StackTraceElement.declaringClass`，只移除宿主运行时帧。
- 容器内完整 Java 栈中的宿主帧由 6 个降为 0。
- 提供调试开关用于临时关闭栈过滤，便于问题定位。

## 4. 原生静态逆向结论

重点分析了 `libnpth.so`、`libnpth_vm_monitor.so`、
`libnpth_ref_monitor.so`、`libnpth_dl.so`、`libnpth_dumper.so`
和 `libmetasec_ml.so`。

### 4.1 虚拟环境模块判断

`libnpth.so` 的 `nIsVirtualEnv` 已还原为模块名枚举逻辑。它依次检查当前进程中是否
加载了以下模块：

- `libsandhook-native.so`
- `libsandhook.so`
- `libsandbox_32.so`
- `libsandbox_64.so`

任意一个模块存在时返回虚拟环境状态，否则返回正常状态。该判断并不只依赖路径文本，
因此单独过滤 `/proc/self/maps` 中的字符串不足以覆盖全部情况。

### 4.2 原生风险观测面

NPTH 和完整性 SDK 会组合读取以下运行信息：

- `/proc/self/maps` 和 `/proc/<pid>/maps`
- `/proc/self/smaps`、`smaps_rollup` 和内存聚合
- `/proc/self/fd`、FD 数量和 FD 上限
- `/proc/self/task`、线程名、线程数量和线程身份
- pthread key 数量、TLS key 和线程栈
- `/proc/self/pagemap`
- `/proc/meminfo`、`sysinfo` 和 RLIMIT
- 信号处理链和 native 栈
- `dl_iterate_phdr` 已加载模块集合
- hook 模块、hook 记录和匿名可执行映射
- `popen`、`fork`、`socket`、`ioctl`、`access`、`readlink`
  等原生接口

完整性 SDK 还包含以下风险相关入口或特征：

- `http_sign_callstack_check_all`
- `http_sign_callstack_check_error`
- `risk_sensor_cache`
- `disable_risk_sensor`
- Java `getStackTrace`
- 宿主类路径和运行时帧检查

这些输入说明，目标判断不是单一的包名或签名比较，而是 Java 身份、原生模块、线程、
文件描述符、内存视图和调用栈的组合结果。

### 4.3 风险回调链路

平台完整性管理层的非官方状态回调具有以下通用链路：

1. 平台层注册环境状态回调。
2. Metasec 包装层保存回调，并通过 Matrix JNI 进入原生库。
3. 原生层返回状态码和环境键。
4. 平台层仅在 Java 调用状态码为 0 时继续读取环境状态。
5. 环境状态非 0 时，平台层进入风险处置流程。

因此，Java 层看到的是原生风险引擎的综合结果，不能仅从某一个公开字段直接推出
具体失败项。

## 5. 测试应用补充内容

测试应用新增了不改变目标行为的原生观测探针，并显示为独立结果行。主要包括：

### 5.1 虚拟环境模块名

通过 `dl_iterate_phdr` 枚举实际加载模块，按文件名检查 NPTH 使用的四个沙箱模块名。
该探针补上了此前只扫描映射路径、无法确认模块枚举结果的问题。

### 5.2 pthread key 生命周期

- 创建并记录当前可用的 pthread key 数量。
- 对每个 key 执行 `setspecific/getspecific` 往返校验。
- 复位并删除全部测试 key。
- 验证删除后仍可重新创建 key。

### 5.3 线程栈与 guard 边界

使用 `pthread_getattr_np` 和 `pthread_attr_getstack` 获取当前线程栈的：

- 栈基址
- 栈大小
- guard 大小
- 页对齐状态
- API 返回状态

### 5.4 native smaps 聚合

直接打开 `/proc/self/smaps` 和 `/proc/self/smaps_rollup`，检查：

- 映射数量
- Rss
- Pss
- `smaps_rollup` 是否可读
- Pss 与 Rss 的基本数量关系

### 5.5 sysinfo 与 meminfo 一致性

比较原生 `sysinfo` 与 `/proc/meminfo` 的总内存和可用内存量，避免只验证接口可调用、
却不验证返回视图是否一致。

### 5.6 native pagemap

直接读取 `/proc/self/pagemap`。Android 应用可能获得有效读取，也可能因 SELinux 返回
权限拒绝；两种标准结果都视为可接受，异常为其他错误。

### 5.7 maps 与动态模块集合

统计 `/proc/self/maps` 中的 VMA 数量、文件模块路径和
`dl_iterate_phdr` 模块数，检查两个模块视图不会出现明显不一致。

### 5.8 hook/框架映射

扫描映射中的常见 hook 运行时和匿名栈标记，包括：

- sandbox/sandhook
- shadowhook
- LSPosed
- Substrate
- Frida
- bytehook 匿名映射

## 6. 已有平台探针

除本次新增内容外，测试应用已经覆盖：

- UID、GID、调用 UID 和 Binder 身份
- PackageManager、ApplicationInfo、PackageInfo 和签名关系
- APK 路径、归档内容和构建号
- `/proc/self/status`、`cmdline`、`task`、`mountinfo` 和 `smaps`
- 文件系统、外部存储和设备保护存储
- Build、系统属性和内核视图
- Root、调试属性、注入环境变量
- 多进程、ContentProvider、Service 和广播
- Java 栈中的宿主帧
- FD 数量、上限和匿名映射可执行权限
- `popen`、`socket`、信号 handler 和辅助可执行文件

## 7. 验证结果

构建验证：

- `:app:assembleDebug` 通过
- `:testapp:assembleDebug` 通过
- 四个 ABI 的 native 编译通过
- 单元测试通过
- `git diff --check` 通过

运行验证：

- 直装测试应用：`overall=PASS`
- 容器内测试应用：`overall=PASS`
- 新增 8 项原生探针全部 PASS
- 容器测试启动与执行过程未出现 ANR

本次补充验证的关键结果包括：

- 沙箱模块枚举结果为 0
- hook 映射扫描结果为 0
- pthread key 创建、读写、删除和复用均正常
- 线程栈属性可读取且页对齐正常
- native smaps 与 rollup 视图正常
- `sysinfo` 与 `/proc/meminfo` 总量一致
- pagemap 返回标准设备行为
- maps 与动态模块集合数量关系正常

## 8. 仍未闭环的问题

本次扩展确认了容器在新增原生观测面上没有明显暴露，但目标应用仍会在后续阶段给出
非官方状态，并主动结束进程。

已经确认的内容：

- 宿主 Java 栈已过滤为 0。
- 沙箱模块名未命中。
- maps、smaps、pthread key、线程栈和内存视图测试均通过。
- 风险处置动作能够正常执行，不再表现为关闭动作卡住。

仍待确认的内容：

- 原生风险引擎最终使用的状态码来自哪个具体检查组合。
- 原生调用参数中的环境键、缓存状态和调用栈上下文是否存在额外关联判断。
- 某些检测是否依赖服务器返回的风险结果，而不是本地单项条件。

现有证据不足以证明剩余失败只由某一个明确字段导致。继续处理时，应优先从原生风险
调用的输入参数、状态码和中间缓存入手，而不是继续添加应用专用规则。

## 9. 后续建议

1. 对原生风险调用做参数级跟踪，记录环境键、操作码、输入对象和返回状态。
2. 区分本地即时判断与服务器校验结果，避免把网络侧结果误判为本地泄漏。
3. 继续以通用 Android 语义为修复对象，例如 Binder 身份、模块枚举、线程和文件视图。
4. 所有目标应用相关检测特征只保留在测试应用中。
5. 每次修改后同时验证直装和容器运行，检查新增探针是否 PASS 以及是否引入 ANR。

## 10. 当前提交

本次测试扩展提交：

```text
954d95e test: expand native environment probes
```
