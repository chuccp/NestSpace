# 加固运行时复原总结

状态：暂停实测，保留工作区，等待下一次从进程级身份隔离继续。

本文是一份独立总结文档。下次可以由新的会话或新的代理直接从这里接手，
不需要依赖之前的对话上下文。背景研究和更长的技术过程见
[加固运行与原生 Android 复原研究](hardened-runtime-recovery.md)。

## 关键交接信息

```text
当前目标：
  在 NestSpace 容器中恢复与直接安装一致的加固应用运行语义

当前结果：
  真实 Application 挂载成功
  Application.onCreate 成功
  Provider 初始化成功
  首个 Activity 原生 onCreate 已进入，但未完成

当前阻塞：
  加固 runtime 初始化早期读到宿主内核 UID
  加固壳内置 Mini VM 据此建立了不同的初始化状态

不是根因：
  DEX 没有抓到
  Java ClassLoader 缺类
  目标 APK 的普通 Context/Resources 没有加载
  Application 没有挂载
  可选 native 库加载失败

属于故障路径的一部分：
  壳内置 Mini VM 的资源常量类字段/元数据未正确初始化
  它不是普通 Android Resources 文件缺失

下次第一件事：
  比较直装与容器中壳内置 Mini VM 的早期初始化状态
  设计并实现 native runtime 加载前的进程级凭据 / UID 隔离

不要再做：
  调整 DEX 合并顺序
  继续增加 JNI 字段桥
  固定 native 偏移跳转
  越界读取归零
  用首屏截图代替完整验收
```

关键代码提交：

```text
ad25f01  完善加固运行时兼容与阶段交接记录
6897842  整理加固运行时总结文档
```

当前现场：

```text
验收设备：emulator-5556
直接安装对照：保留
最新 snapshot：已构建并安装
debug.nestspace.*：全部为 0
NestSpace 与目标应用：已 force-stop
```

必须保留的约束：

- 不在通用核心中写目标应用包名、类名、哈希或固定壳特征。
- 不通过固定 native 偏移补丁推进启动。
- 不以越界归零、字段猜填或吞异常作为正式修复。
- 直装和容器必须使用同版本、同签名 APK。
- 下次先复现一次基线，再开始进程级身份隔离，不要连续尝试多组诊断。

## 1. 一句话结论

目标应用已经能够在容器中完成真实 Application 挂载、Application.onCreate
和 Provider 初始化，也能进入首个 Activity 的原生 onCreate；但加固运行时在
加载后的极早阶段读取到了宿主内核 UID，而不是容器对外展示的目标包 UID，
加固壳自带的 Mini VM / 隐藏解释器因此建立了不同的初始化状态。

继续增加 DEX、JNI 字段或 Activity 兼容层只能改变表面异常，不能恢复正确
Mini VM 状态。下一次应优先比较直装和容器的早期初始化状态，并解决 native
runtime 出现前和出现期间的进程级凭据 / UID 隔离。

## 2. 下次开始前必须知道的事情

1. 当前阶段修改已经作为 Git 检查点提交。下次不得从更早的干净提交重新开始，
   也不得擅自回滚本次研究提交。
2. 当前直接安装副本和容器导入 APK 必须保持同版本、同签名；不要卸载直装
   对照副本，否则 ClassLoader、路径和 UID 对照会失效。
3. 系统 UID 会在重装后变化。本次现场中的 UID 只能作为记录，不能硬编码。
4. 诊断属性必须保持为 `0`。类/字段桥、运行时字段 retry、seccomp gateway、
   越界恢复等实验能力默认都不能开启。
5. `Frida` 和 `strace` 会改变加固运行时行为。它们适合收集证据，不适合作为
   最终行为验收。
6. 自检应用的普通 Java、libc、直接 syscall 和 `/proc` 探针全部通过，仍然
   不能证明加固运行时初始化正确。
7. 可选 native 库加载失败不是本次根因。直接安装也会出现同类加载失败并继续。

## 3. 当前设备和 Git 检查点

### 3.1 Git

```text
分支：main
检查点：本次研究进度提交
```

下次先运行：

```bash
git log -1 --oneline
git status --short
```

确认当前提交和后续改动后再继续。现有研究提交不应擅自回滚。

### 3.2 模拟器

```text
emulator-5554：直装参考环境
emulator-5556：当前验收环境，已启动
最新 snapshot：已构建并安装到 5556
诊断属性：全部为 0
NestSpace：已 force-stop
目标应用：已 force-stop
```

当前现场 UID 记录：

```text
NestSpace：10210
当前直接安装副本：10216
```

这些值会变化。下次必须重新查询：

```bash
adb -s emulator-5556 shell pm list packages -U |
  grep -E "com.coke.nestspace|<target-package>"
```

### 3.3 构建产物

```text
app/build/outputs/apk/snapshot/app-snapshot.apk
```

构建：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:assembleSnapshot --no-daemon
```

已验证通过：

```text
:app:assembleSnapshot
:app:testDebugUnitTest
git diff --check
```

## 4. 当前启动链实际到达的位置

### 4.1 已通过

- APK 导入。
- 版本和签名一致性校验。
- 选择系统安装副本。
- 目标 SDK / VMRuntime 对齐。
- 目标 ClassLoader 创建。
- 目标 Resources 加载。
- native environment 安装。
- 外部 wrapper Context 创建。
- 真实 wrapper Application 挂载。
- wrapper 到真实 Application 的 Context 切换。
- Application.onCreate。
- Provider 创建。
- 首个 Activity 类解析。
- 进入首个 Activity 的原生 onCreate。

当前日志应包含类似：

```text
external Application mounted wrapper=<wrapper> target=<real-application>
application attached: <real-application>
startup phase=APPLICATION_CREATE outcome=SUCCESS
activity class=<launcher-activity>
```

### 4.2 尚未通过

- 首个 Activity.onCreate 完成。
- 首个 Activity 窗口正常显示。
- 第二 Activity 和后续页面切换。
- 冷启动稳定性。
- 退出后重新进入。
- 多实例并行。

## 5. 当前失败表现

同一个根因在不同调试配置下会表现为不同错误。

### 5.1 当前默认路径

保留现有运行期 DEX 注册时，可能报告资源常量类缺少字段。这里的“资源”不是
APK 中的 Android 资源文件缺失，而是业务 DEX 中由资源 ID 生成的常量类无法被
加固壳内置 Mini VM 按其私有类/字段元数据正确解析。

关键事实：

- Java ClassLoader 反射到的资源类字段是完整的。
- 壳内置 Mini VM 维护独立的类、方法、字段和寄存器状态；它与 Java ClassLoader
  看到的是两套元数据视图。
- JNI 错误来自 Mini VM 自己的类/字段解析状态，而不是普通 Java ClassLoader
  缺少定义，也不是目标 APK 的 `Resources` 对象没有加载。
- 补 JNI 字段 ID 后，会继续出现实例字段空指针或解释器越界。

### 5.2 APK-only 对照路径

使用更接近直装的 APK-only ClassLoader，并停止附加 DEX 注册时：

- Java 缺字段错误消失。
- native 隐藏解释器在 `move-result-object` 附近读取越界寄存器索引。
- 这不是普通 DEX 解析失败，而是加固运行时内部执行状态错误。

### 5.3 字段一致性桥路径

启用 JNI 类/字段一致性桥后：

- `FindClass` 和字段查找能够返回目标 ClassLoader 中的完整类。
- 缺字段异常可以越过。
- 后续仍然出现字段操作空指针或 native 解释器越界。

因此字段桥只能用于证明差异位置，不能作为正式修复。

### 5.4 “资源找不到”的边界

需要区分两个问题：

1. `Resources.NotFoundException`、资源表未加载或资源路径错误，属于普通 Android
   资源系统。当前容器已经能够为目标 Application 创建和使用目标 Resources，
   这类问题不是当前启动失败的主因。
2. “资源常量类缺少字段”属于代码执行问题。资源常量在 DEX 中是普通类；普通 ART
   ClassLoader 能找到完整定义，不代表壳内置 Mini VM 已经为该类建立同样的字段
   索引和运行时元数据。

当前命中的是第 2 类。只补普通 Java 字段、JNI `GetFieldID` 返回值或 Android
Resources 对象，改变的是 Mini VM 外部的表面结果，不能修复它内部的类元数据、
寄存器索引和虚拟 PC 状态。

## 6. 根因证据

### 6.1 直接安装和容器的运行期代码一致

已验证：

- 原生可执行代码镜像字节一致。
- 首个 Activity 的原生方法入口和方法元数据高度一致。
- JIT 入口代码一致。
- Java 层资源常量类字段集合一致。

所以问题不是单个运行期 DEX 文件没有抓到。

### 6.2 加固运行时初始化早期读取到不同 UID

对同版本直接安装和容器进程进行系统调用对比后得到：

```text
直接安装：
  加固 runtime 初始化后 getuid() = 系统安装副本 UID

容器：
  加固 runtime 初始化后 getuid() = 宿主进程 UID
```

容器已经虚拟化：

- Java `Process.myUid()`。
- PackageManager / Parcel 返回值。
- `/proc/self/status`。
- `/proc/self` stat owner。
- 常规 libc `getuid` / `geteuid`。

但加固运行时在 native runtime 极早阶段仍能读到宿主内核身份。

该时间点早于业务 Application。之后再去修 Context、ClassLoader、JNI、
DEX 或 Activity，已经无法撤销加固运行时建立的错误初始化状态。

### 6.3 加固壳内置 Mini VM 与 ART 是两套状态

加固壳的 native 模块内部包含自己的 Mini VM / 隐藏解释器。它负责解密或还原
运行期代码，并建立壳私有的类、方法、字段、字符串、常量和寄存器映射。
ART ClassLoader 是否能看到某个类，与 Mini VM 是否为该类建立元数据是两个独立
问题。

当前已确认：

- 直装与容器运行的 native 代码镜像字节一致。
- 首个 Activity 的原生方法入口、方法头和 JIT 代码高度一致。
- Java 层资源常量类的字段集合一致。
- 容器中的 Java ClassLoader 可以返回完整类，JNI 字段桥也能找到完整字段。
- 进入壳 Mini VM 后，仍会在隐藏表的非法寄存器索引处崩溃。

因此不能再把问题简化成“缺少 DEX”或“ART ClassLoader 不正确”。更准确的说法是：
相同代码字节进入容器后，壳 Mini VM 在首次业务方法执行前形成了不同的初始化
状态。资源常量类缺字段只是该状态的一种早期表现；后续的实例字段空指针和
`move-result-object` 越界来自同一套 Mini VM 元数据/执行状态分叉。

当前崩溃现场中的 `pc offset`、表地址和寄存器索引只用于定位问题，不能转成固定
native 偏移补丁。下一步应比较直装和容器在首个业务方法执行前的 Mini VM 初始化：

- 类的内部槽位是否一致。
- 方法和字段索引是否一致。
- 虚拟寄存器数与引用表是否一致。
- 进入首个方法处理器前的虚拟 PC 和执行模式是否一致。
- 早期身份分支是否改变了解密、注册或引用表构建顺序。

### 6.4 为什么 self-check 全绿仍然失败

现有自检能力可以确认：

```text
getuid=虚拟 UID
geteuid=虚拟 UID
syscall(SYS_getuid)=虚拟 UID
/proc/self/status=虚拟 UID
/proc/self/cmdline=目标进程名
```

这些是必要条件，不是充分条件。加固运行时可能使用当前探针没有覆盖的原始
调用、内核对象或组合判断。

## 7. 已尝试且不要重复的方案

### 7.1 DEX 方向

已经完成：

- 进程内存 DEX 捕获。
- 校验和结构验证。
- 内容寻址去重。
- 当前代次 manifest。
- 排除旧 `process-dex-backup-*` 和 `process-dex-stale-*`。
- Application 创建前后多次捕获。
- APK 优先、运行期 DEX 补位的顺序验证。

当前已从正常容器启动路径移除这套进程内存扫描；它只保留为离线诊断，
不能再作为启动兜底。

这些修复解决了 DEX 代次污染和资源类遮蔽问题，但不能修复 native 解释器状态。

### 7.2 Context / ClassLoader 方向

已经完成：

- wrapper shell Context 使用目标 ClassLoader。
- LoadedApk ApplicationInfo、包名、dataDir、APK 路径、Resources 对齐。
- wrapper 入口 Application 类名配置。
- wrapper 和真实 Application 的 base Context 重绑定。

这些修复让真实 Application 和 Provider 初始化成功。

### 7.3 Activity 方向

已经尝试：

- 延迟首个 Activity。
- 手动 `Activity.attach`。
- 框架 `ActivityThread.startActivityNow`。
- 不同 ClassLoader 形态。

最终错误没有改变。

### 7.4 JNI 方向

已经尝试：

- 统一 `FindClass`。
- 统一静态常量类。
- 静态字段按实例字段查询兼容。
- 运行时字段解析 retry。
- 字段异常恢复。

结果：

- 可以改变异常位置。
- 不能恢复 native 解释器寄存器索引。
- 某些 retry 会让壳更早崩溃。

### 7.5 Seccomp 方向

已经尝试：

- wrapper 初始化前安装 syscall gateway。
- Application 完成后安装 syscall gateway。

结果：

- 提前安装会让壳识别到额外 filter，拒绝挂载真实 Application。
- 延后安装来不及修正加固 runtime 的早期初始化状态。

### 7.6 越界恢复方向

已经尝试：

- 记录 OOB 现场。
- 恢复单次越界读取。
- 继续观察后续异常。

结果：

- 可以用于诊断。
- 会把错误推进到后续字段、对象或解释器错误。
- 不能作为正式实现。

## 8. 当前代码中建议保留的修改

### 8.1 wrapper Context 对齐

涉及：

- [`ContainerSession.kt`](../app/src/main/java/com/coke/nestspace/container/ContainerSession.kt)
- [`ExternalApplicationBootstrap.kt`](../app/src/main/java/com/coke/nestspace/container/application/ExternalApplicationBootstrap.kt)

建议保留：

- 临时 shell Context 同时具备目标的 ClassLoader、LoadedApk ApplicationInfo、
  包名、dataDir、APK 路径和 Resources。
- wrapper 入口字段指向缓存中的真实 Application。
- wrapper 和真实 Application 挂载后重新绑定容器 Context。

这是正常 Android 启动语义，不属于壳特判。

### 8.2 DEX 文件加载

涉及：

- [`RuntimeDexPathClassLoader.kt`](../app/src/main/java/com/coke/nestspace/container/loader/RuntimeDexPathClassLoader.kt)

建议保留：

- 显式 ClassLoader 代码路径。
- 已存在的生成 DEX 文件和正常失败异常。
- 不扫描 `/proc/self/maps` 或读取 `/proc/self/mem`。

### 8.3 启动阶段记录

涉及：

- [`AndroidStartupCoordinator.kt`](../app/src/main/java/com/coke/nestspace/container/runtime/AndroidStartupCoordinator.kt)
- [`ContainerLaunchProgress.kt`](../app/src/main/java/com/coke/nestspace/container/ContainerLaunchProgress.kt)

启动阶段只记录正常的 ClassLoader、Application、Provider 和 Activity
生命周期，不增加进程内存 DEX 扫描阶段。

### 8.4 运行期终止容纳

涉及：

- [`native_environment.cpp`](../app/src/main/cpp/core/native_environment.cpp)

它避免 wrapper 初始化失败时直接杀死整个宿主进程。

注意：

- 进程继续存活不等于初始化成功。
- 必须检查真实 Application 类型和 `APPLICATION_CREATE:SUCCESS`。
- 该能力可能在后续隔离进程架构中重新设计。

## 9. 只应作为诊断的修改

下面这些能力只能显式打开，不能成为默认行为：

- JNI `FindClass` inline hook。
- JNI 类/字段一致性 bridge。
- 运行时字段解析 retry。
- 普通字段异常恢复。
- runtime OOB zero。
- 提前或延后 syscall gateway。
- 强制 APK-only ClassLoader。
- 强制动态 ClassLoader。
- 强制框架 Activity 启动。

当前所有 `debug.nestspace.*` 属性已设为 `0`。

## 10. 下次优先工作

### 10.1 先做架构决策，不先写 hook

需要先回答：

1. 目标进程如何拥有与宿主分离的凭据视图？
2. 是否使用 user namespace、隔离进程槽或专用 native spawner？
3. 谁负责启动进程、加载目标 APK、创建 ClassLoader？
4. 窗口、Binder、Service、Provider 如何回到 NestSpace？
5. 多实例时 UID / credential 视图如何隔离？

已知约束：

- 普通 Android 应用不能自行 `setuid`。
- 当前应用 UID 下测试 user namespace 不可用。
- `isolatedProcess` 有独立 UID，但承载普通 Activity、应用数据和 Binder
  身份有额外限制。
- 专用 native spawner 最接近真实进程模型，但需要重新设计进程通信和窗口链。

### 10.2 第一个验收点不是首屏

在加固 runtime 初始化后立即比较：

```text
getuid
geteuid
getresuid
getgid / getegid
SELinux context
/proc/self owner
/proc/self/status
进程名
ClassLoader
Application 是否已挂载
```

必须与直接安装对照进程一致。

### 10.3 第二个验收点

Application.attach 前、native runtime `dlopen` 后、Application.onCreate
前后、首个 Activity 前分别采样同一组事实。

### 10.4 第三个验收点

只有进程身份视图稳定后，才重新测试：

- 首个 Activity.onCreate。
- 首个窗口显示。
- 第二 Activity。
- 冷启动和重启。

## 11. 下次恢复现场步骤

### 11.1 检查 Git

```bash
git status --short
git diff --stat
git rev-parse HEAD
```

### 11.2 检查设备

```bash
adb devices -l
adb -s emulator-5556 shell getprop sys.boot_completed
adb -s emulator-5556 shell pm list packages -U
```

### 11.3 确认诊断关闭

```bash
adb -s emulator-5556 shell getprop | grep debug.nestspace
```

全部应为 `0`。

### 11.4 确认直装对照

必须确认：

- 直装副本仍存在。
- 版本和导入 APK 相同。
- 签名和导入 APK 相同。
- 重新查询当前 UID，不使用本文中的历史 UID。

### 11.5 构建和安装

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:assembleSnapshot --no-daemon

adb -s emulator-5556 install -r \
  app/build/outputs/apk/snapshot/app-snapshot.apk
```

### 11.6 复现一次基线

只复现一次当前失败，确认日志仍与本文一致，再开始新方案。

建议过滤：

```text
ContainerSpecFactory
ContainerSession
ExternalAppBootstrap
application attached
APPLICATION_CREATE
activity class
native crash
```

不要连续运行大量诊断组合，避免把不同初始化代次混在一起。

## 12. 不能用来判断成功的现象

- 进程没有立刻退出。
- wrapper 日志显示 initialized in place。
- 首屏截图出现但进程随后退出。
- self-check 的普通 getuid / syscall / `/proc` 全部通过。
- 单个 JNI 字段异常消失。
- 单次越界恢复没有立即崩溃。
- Frida 注入后没有崩溃。

这些现象都可能是错误分支中的短暂结果。

## 13. 最终验收门槛

必须同时满足：

1. 加固 runtime 初始化早期看到的身份与直装一致。
2. 真实 Application 挂载成功。
3. Application.onCreate 成功。
4. 首个 Activity.onCreate 完成。
5. 窗口和其他 Activity 可正常切换。
6. 连续冷启动无 native crash。
7. 退出后重新进入状态正常。
8. 不同实例的凭据和 ClassLoader 不串用。
9. 不依赖应用包名、类名、哈希或固定 native 偏移。
10. 不依赖 OOB 归零、字段猜填或吞异常。

## 14. 加固壳已经暴露出的手段

下面的清单来自 APK 结构分析、native 模块的导入函数与字符串、运行期系统调用
对照和启动实验。分为“已确认参与当前启动路径”和“binary 中存在该能力，但尚未
证明本次启动实际触发”。二者不能混为一谈。

### 14.1 入口代理与分阶段启动

- Manifest 中先启动加固壳的 Application 代理，而不是直接启动业务
  Application。
- 壳先建立 native runtime、解密代码、准备 ClassLoader 和 Context，再通过内部
  字段、静态槽位或工厂方法挂载真实 Application。
- 真实 Application 挂载后仍会继续初始化 Provider 和启动 Activity；加固工作
  不只发生在 `attachBaseContext`。
- 壳会选择与 CPU ABI 对应的 native payload，并通过绝对路径动态加载运行期模块。
- 包装代码保留 32/64 位和 x86 兼容分支，不能只按当前 arm64 路径理解其加载逻辑。
- 除主 native 模块外，还存在辅助分发模块的二次加载：先走 linker 的库名搜索，
  失败后再通过 PackageManager 的 nativeLibraryDir 和包内固定路径回退。
- 运行期会创建目录、写出或复制解包后的模块，并调整文件权限；这些文件的实际
  owner、路径和权限会参与后续 native 加载。
- 原生入口使用 `JNI_OnLoad` 和动态 JNI 方法注册，业务代码可见的接口名称和方法
  数量会在运行期建立。
- wrapper Context、Application、Provider 和 Activity 可能缓存 dataDir、APK
  路径、native 库路径或 ClassLoader。只在某一处替换字符串不足以覆盖这些缓存。

### 14.2 安装包与代码保护

- 业务 DEX 不以完整的明文文件直接暴露；启动后由 native 层解密、解压并恢复到
  进程内存。
- native 模块导入 zlib 的 `inflate` / `deflate` 系列函数，说明运行期数据包含
  压缩或压缩封装层。
- native 符号表包含多类动态解密器，以及 ELF Header、Program Header 和本地模块
  相关的解密路径。运行中的可执行代码不能按磁盘上的静态文件直接理解。
- 使用 `mmap`、`mprotect` 创建和切换可执行页面，代码可以在启动后生成、解密或
  改写。
- native 模块包含多个可执行 LOAD 段，运行期还会映射额外的可执行映像；磁盘上的
  单个静态 `.so` 不能代表进程内全部可执行代码。
- 恢复出的运行期 DEX 会注册到 ART 的 `DexFile` / ClassLoader 体系，并可能由
  多个 DEX 共同组成业务代码。
- 同一业务类可能出现多个代次的资源型或补充型 DEX；壳的私有引用表与 Java
  ClassLoader 的可见类集合不是同一个状态。
- native 模块被裁剪并隐藏常规符号，只保留动态链接必需的入口和少量桥函数。
- 包装层的配置和字符串可通过编号访问到 native 解密桥，磁盘 DEX 中的字符串不能
  代表运行期最终字符串表。

### 14.3 壳内置 Mini VM 和隐藏执行器

- 壳的 native 模块内部自带 Mini VM / 隐藏解释器，不完全是直接调用 ART 执行
  全部业务方法。
- Mini VM 会为业务方法建立自己的类、方法、字段、字符串、常量、虚拟寄存器和
  引用表。
- 隐藏执行器包含操作数解码器、指令处理器分发表、方法上下文和虚拟 PC；同一
  DEX code item 可能根据运行期状态进入不同处理器。
- JNI 中包含大量不透明编号的 native 桥方法，以及针对 `ZipFile`、`AssetManager`、
  `Class` / `ClassLoader`、`Resources`、`AssetFileDescriptor`、`MediaPlayer` 和
  `SoundPool` 的原生入口。加固不仅托管代码，也参与 APK 条目和资源流的读取。
- JNI `FindClass`、`GetFieldID` 等接口只负责 Java 与 native 的边界查询，不能
  代替 Mini VM 内部的类链接和寄存器状态。
- 资源 ID 常量类在 Java 中是普通 DEX 类。Java ClassLoader 能找到完整字段，
  不代表 Mini VM 已建立同名字段、索引和对象引用。
- 当前容器路径在 Mini VM 中形成了与直装不同的初始化状态，随后出现非法的隐藏
  寄存器/表索引，而不是普通 Android 资源文件缺失。

### 14.4 反调试、反注入与环境观察

已确认会改变当前启动行为的手段：

- 反 `ptrace`：native 模块直接导入 `ptrace`，外部调试器在
  `JNI_OnLoad` 前后接入会改变初始化路径。运行时还会通过线程或子进程组合
  `fork`、`PTRACE_ATTACH` / `PTRACE_SEIZE` 等调试状态观察路径。用普通 `ptrace`
  做现象验收不可靠。
- 进程状态观察：读取自身和关联进程的 `/proc/<pid>/maps`、`cmdline`、`comm`
  等文件，用于观察模块、进程名和父子进程关系。
- 模块路径观察：使用 `dladdr`、`dl_iterate_phdr`、`dlopen`、`dlsym` 和相关
  路径记录对比运行时模块，因此路径字符串必须稳定且与直装一致。
- Seccomp 观察：在壳初始化和 Application 挂载前额外安装 syscall gateway 会被
  识别，表现为拒绝挂载真实 Application。
- 信号与异常处理：安装 signal handler、备用信号栈和 native crash handler，
  对 SIGSEGV、SIGBUS、SIGILL、SIGTRAP 等异常有独立处理路径；失败时也可能主动
  `kill`、`raise` 或终止进程。

binary 中存在、但当前尚未隔离出具体触发路径的能力：

- 使用 `inotify` 监视文件变化。
- 扫描 `/dev`、`/proc`、进程 fd 和内存映射，检查额外的注入模块或异常文件。
- 检查常见 Root / superuser 路径和调试相关文件。
- 读取或设置环境变量，并检查 `PATH` 中的常见可执行目录。
- 通过 fork、线程、pipe、select 或 socket 建立辅助监视、通信或崩溃上报流程。

这些能力说明壳不只检查一个 UID，而是在组合进程、模块、文件、信号和环境事实。

### 14.5 进程身份、包身份与路径一致性

已确认参与当前问题的检查：

- native 初始化极早期直接调用 `getuid`；直装看到目标安装 UID，容器看到宿主
  UID。Java、Parcel、常见 libc hook 和 `/proc/self/status` 已虚拟化也不能覆盖
  这个时间点。
- 读取 `/proc/<pid>/cmdline`、`/proc/<pid>/comm` 和进程名，检查运行所属的包和
  进程角色。
- 检查 APK 路径、dataDir、native 库路径、模块加载路径和文件所有者。
- 通过 `dladdr` 和模块枚举检查加载器、运行时库和动态模块是否处于预期位置。

壳代码中存在、但尚需单独确认当前分支是否执行的检查：

- 使用 `opPackageName`、`LoadedApk`、ApplicationInfo 和 PackageManager 视图
  建立包身份；这些值必须在业务初始化前保持一致。
- 通过反射读取 `ActivityThread.currentActivityThread()` 和
  `mBoundApplication.appInfo`，从框架当前绑定状态反查包名、Application 类名、
  `sourceDir`、版本号和数据目录。
- 直接调用 PackageManager 的 `checkPermission`，并通过 AppOpsManager 的
  `permissionToOp` / `noteOp` 检查权限和操作是否允许。

当前 native 模块还直接引用了 PackageManager、PackageInfo、ApplicationInfo 和
签名数组。因此包名、安装路径和签名是同一组身份事实，不能用随机值分别伪造。

### 14.6 设备环境与网络能力

native 模块或 JNI 字符串中可以看到以下环境读取能力。它们是否全部参与当前
加固判定尚未逐项证明：

- 通过 `__system_property_get`、system property 和 `build.prop` 读取系统版本、
  ABI、设备属性和构建信息。
- 读取 `/proc/version`、`/proc/meminfo`、`/sys/class/net/*` 等内核和网络信息。
- 读取 `Build.FINGERPRINT`、`DISPLAY`、`RELEASE`、`MANUFACTURER`、`BRAND`、
  `MODEL`、`HARDWARE`、`CPU_ABI`、`CPU_ABI2`、`SUPPORTED_ABIS`，以及
  `ro.product.real_model`、`ro.board.platform`、`ro.mtk.hardware` 等属性和
  `ro.build.fingerprint`。
- 读取 `Settings.Secure.ANDROID_ID`、系统语言和国家、外部存储挂载状态、总容量与
  可用容量、内存容量和 CPU ABI。
- 读取 `ro.revision`、`user/test-keys` 等构建标记，以及 Wi-Fi 接口和网络状态。
- 查询 ConnectivityManager、网络连接状态、SIM 状态、运营商名称、电话权限和
  外部存储状态。
- native 模块导入 socket、connect、send、recv，并包含 HTTP GET / POST 请求路径。
- 网络路径包含主机名解析和连接状态检查，不只是直接访问固定 IP。
- 本地存在 crash 和日志目录，配合信号处理收集 native 异常。
- 网络请求可能用于设备判定、策略获取或崩溃上报；当前没有证据证明每一次请求
  都会阻止启动，因此不能把“断网失败”直接归因为单一网络校验。

设备属性、模拟器路径、Root 路径和网络状态应视为可能参与评分或分支的环境事实，
不能只伪造单个 `Build` 字段。

### 14.7 完整性与异常数据

- native 代码会遍历 ZIP 条目、`AndroidManifest.xml`、`META-INF/*` 和
  `META-INF/MANIFEST.MF`，并包含 `Digest:`、`MessageDigest`、签名证书和证书
  编码字节相关路径。
- APK 中存在独立的加固标记文件和 native 元数据，运行期还会使用 hash、lock 和
  日志文件记录状态。
- PackageManager 查询会读取版本号、版本名、sourceDir 和签名；签名会被解析为
  X.509 证书并读取编码后的字节。
- native crash handler 会生成 `CRASH_REPORT`，记录构建指纹、revision、ABI、
  pid/tid、进程名、信号、寄存器、PC/SP、代码区和内存映射。
- crash 处理还包含时间窗口和频率字段，存在“短时间内崩溃次数过多就不再继续”
  的限流路径。

当前已经确认这些代码和数据路径存在于壳中，但尚未逐项确认每个摘要实际比较的文件
范围。因此可以确定 APK/签名/运行期文件具备完整性检查能力，不能仅凭字符串断言
某一项校验一定在本次启动中执行。

### 14.8 对容器的直接影响

这些手段共同要求以下事实在 Java、native、文件系统和系统服务中一致：

```text
启动入口
  Application wrapper -> 真实 Application -> Provider -> Activity

代码加载
  APK -> native runtime -> 解密/解压 -> 运行期 DEX -> ART ClassLoader

隐藏执行
  Mini VM 类表/方法表/字段表 -> 方法上下文 -> 虚拟 PC -> 指令处理器

进程身份
  包名 -> UID/GID -> cmdline/comm -> APK/dataDir/native 路径 -> SELinux/Binder

反分析
  ptrace/TracerPid -> maps/模块 -> seccomp/signal -> 文件变化 -> 网络状态
```

只修其中一层会出现“错误位置改变但启动仍未完成”。已经观察到的资源常量类缺
字段、实例字段空指针、虚拟寄存器越界和不同指令处理器分叉，都是这条链上不同
阶段的表面结果。

### 14.9 证据边界

已确认参与当前启动路径：

- Application 代理和真实 Application 二次挂载。
- 运行期 DEX 解密、解压、内存注册和多 DEX 组合。
- 壳内置 Mini VM / 隐藏解释器及其私有元数据。
- 早期 `getuid`、进程名、模块路径和文件路径检查。
- APK 条目、资源流和 native 模块路径由壳参与读取。
- `ptrace` 会改变行为，以及额外 seccomp 层会阻止真实 Application 挂载。
- 资源常量类在 Mini VM 中的元数据与 Java ClassLoader 不一致。

只在 binary 中确认存在能力、尚未证明当前路径触发：

- Root 路径扫描。
- `inotify` 文件监视。
- SIM、网络和外部存储检查。
- `ANDROID_ID`、Build 指纹和设备属性参与启动判定的具体评分方式。
- `checkPermission` 和 AppOps 查询是否在当前启动阶段执行。
- Manifest/SHA/签名摘要与本地 hash 文件的具体比较范围。
- HTTP 策略获取或崩溃上报。
- fork 子进程的具体职责。

后续分析应继续区分“看到 API/字符串”和“该分支在本环境真实执行”，避免为了一个
尚未触发的检查扩大 hook 范围。

### 14.10 不属于壳本身的业务保护

当前 APK 还包含独立的业务级反跟踪 native 库和签名校验 JNI。它们不在壳
native 模块的符号和代码范围内，本文不把它们计入“加固壳手段”。

它们会在业务层启动后叠加第二组调试状态、进程状态和签名密钥检查。因此即使壳的
Mini VM 初始化恢复成功，后续仍需单独验证业务层的反跟踪和签名校验。

## 15. 当前结论

```text
APK / DEX 恢复：完成
运行期代码一致性：已验证
Context / ClassLoader：完成
普通 Android Resources：已加载
资源常量类：Java 定义完整，壳内置 Mini VM 元数据仍不一致
真实 Application 挂载：完成
Application.onCreate：完成
首个 Activity 类加载：完成
首个 Activity 原生 onCreate：进入但未完成
根因：native runtime 早期身份视图与直装不一致，壳内置 Mini VM 据此形成不同状态
架构缺口：缺少进程级凭据 / UID 隔离
下次入口：比较直装与容器的 Mini VM 早期元数据，并恢复一致的进程身份视图
```
