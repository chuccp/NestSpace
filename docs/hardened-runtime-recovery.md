# 加固运行与原生 Android 复原研究记录

- 状态：持续研究中
- 范围：应用级容器中的加固应用启动、DEX 恢复验证和 Android 运行语义复原
- 约束：文档不记录目标应用名称、包名、业务类名、哈希、私有文件路径或壳厂商特征

## 1. 结论

当前研究已经可以确认：

1. 加固后的运行期 DEX 可以完整恢复。
2. 从运行进程内存恢复出的 DEX 与直接安装进程中的对应 DEX 字节一致。
3. 加固后的 Application 可以完成挂载，并执行到业务初始化阶段。
4. 首个业务 Activity 可以被解析和创建。
5. 剩余问题已经不再是“脱壳不完整”，而是加固运行时与原生 Android 进程语义之间仍存在差异。

因此后续目标应当明确为：

> 不继续针对壳的私有数据结构做修补，而是把容器补齐成与直接安装应用一致的 Android 运行环境。

“DEX 已恢复”不等于“应用已按原生方式运行”。前者只能证明代码字节可用；后者还要求进程身份、ClassLoader、DEX 注册、native linker、系统服务、文件路径和生命周期形成同一套自洽事实。

## 2. 加固启动链的通用模型

不同加固产品实现不同，但常见启动链可以抽象为以下阶段。

### 2.1 Manifest 入口代理

安装包中的 Application 往往是一个壳入口。壳入口负责加载 native 初始化模块，再通过内部字段、静态槽位或工厂方法挂载真实 Application。

容器不能只识别固定的入口类名。应当根据以下通用特征判断：

- Manifest Application 与实际业务 Application 不同。
- 入口类的父类关系符合 Application 包装器。
- 入口类完成 attach 后会生成或暴露另一个 Application 实例。
- 业务 ClassLoader 在入口 attach 后才具备完整代码。

### 2.2 Native 初始化

壳通常会在早期加载 native 模块，并执行以下工作：

- 解密或还原运行期 DEX。
- 将运行期 DEX 注册到内存中的 DexFile。
- 扩展或替换业务 ClassLoader 的 DexPathList。
- 注册 JNI、动态 native 方法、异常处理和指令修复逻辑。
- 建立壳私有的类、方法、字段和字符串引用表。
- 检查进程 UID、包名、路径、属性、内存映射、调试状态和注入状态。

这一阶段通常发生在业务 Activity 解析之前，但部分表会在第一次解析业务类时才完成初始化。

### 2.3 运行期 DEX 注册

直装进程和容器进程最终都可能呈现相同的 Java 侧结构：

```text
Application ClassLoader
  ├─ installed base APK
  └─ runtime in-memory DEX container
```

外部观察到的结构相同，并不保证 ART 内部状态完全相同。仍需继续比较：

- DexFile 对象身份。
- DexCache 和 ClassLoader 关联。
- 运行期 DEX 内部子文件的注册顺序。
- native cookie 对应的内部 DexFile/OatFile。
- 首次类解析时壳私有引用表的建立过程。

### 2.4 首次业务类解析

壳可能在第一次解析业务 Activity 时执行最后一次环境绑定：

- 从运行期 DEX 中选择类定义。
- 将 DEX 方法或字段索引转换为内部槽位。
- 填充壳私有引用表。
- 读取进程、模块、包和系统属性作为分支条件。

此阶段最容易暴露“DEX 字节相同，但运行环境不同”的问题。

## 3. DEX 恢复验证

### 3.1 已验证内容

当前验证不应使用壳输出的文件列表作为唯一依据，而应从真实运行进程的匿名可执行/可读内存中扫描 DEX：

1. 校验 DEX magic、头部长度和文件大小。
2. 校验 DEX Adler-32。
3. 从进程内存复制完整 DEX。
4. 对恢复结果计算稳定摘要。
5. 与直接安装进程中的运行期 DEX 逐项比较。

当前结果：

- 恢复出的运行期 DEX 数量完整。
- DEX 字节与直接安装对照一致。
- 业务代码、资源和多 DEX 内容不再缺失。

### 3.2 能证明什么

DEX 对比一致可以证明：

- 解密流程可以跑通。
- 内存读取没有截断。
- 恢复后的代码不是残缺片段。
- 后续问题不是简单的代码缺失。

### 3.3 不能证明什么

DEX 对比一致不能证明：

- ART 对 DEX 的注册方式与直装一致。
- 壳的 native 引用表已经完整。
- ClassLoader 的所有内部状态与直装一致。
- native 库看到的进程身份与 Java 层一致。
- 所有 Activity 都能稳定连续加载。

### 3.4 纯脱壳的边界

这里的“纯脱壳”指：

> 从 APK 和真实运行进程中获得可独立保存、解析和校验的业务 DEX，不把恢复出的 DEX 再注入容器来掩盖运行环境问题。

纯脱壳的目标是得到完整代码，而不是得到“能从容器首屏进入”的修补产物。

纯脱壳输出可以是：

- 一组原始 DEX 文件。
- 各 DEX 的加载顺序和来源。
- DEX 与 ClassLoader 的关联信息。
- 类、方法、字段和代码项完整性报告。
- 可选的重新打包 APK，仅用于离线分析或测试。

纯脱壳不应包含：

- 壳私有字段修补。
- 固定 native 偏移修补。
- 针对业务类的特殊分支。
- 为绕过运行时崩溃而伪造的引用表。

### 3.5 运行时采集流程

当前可用的通用流程如下。

```text
启动目标进程
  → 等待加固运行时完成代码注册
  → 枚举可读内存映射
  → 扫描 DEX 头和大小字段
  → 校验 DEX 结构
  → 复制完整 DEX
  → 计算摘要并去重
  → 记录地址、顺序和覆盖类
  → 输出恢复结果
```

采集阶段应覆盖以下内存类型：

- 匿名可读页。
- 匿名可执行页。
- `memfd`。
- 加固运行时自己的数据目录。
- 直接映射的 APK、ZIP 或内部容器文件。
- Java 堆和 native 堆中的连续 DEX 镜像。

不能只扫描磁盘文件。很多加固方案在启动后将 DEX 解密到匿名内存，磁盘上的第一阶段文件并不能代表最终业务代码。

### 3.6 DEX 识别与完整性校验

扫描时可以先用低成本条件过滤：

1. 文件头为 `dex\n` 加合法版本号。
2. DEX 头长度不小于标准头大小。
3. `file_size` 在合理范围内。
4. `header_size <= file_size`。
5. 当前扫描地址加 `file_size` 不越过所属映射。

命中后再执行完整校验：

- endian tag。
- map list 偏移。
- string/type/proto/field/method/class 各 section 的 offset 和 size。
- ID section 是否满足对齐要求。
- class data、code item、debug info 是否落在文件范围内。
- checksum 和 SHA-1 signature。
- 完整读取后的摘要。

部分加固方案会修改 checksum、signature 或部分 header 字段，但运行时仍能执行。因此建议提供两级判定：

| 级别 | 条件 | 用途 |
| --- | --- | --- |
| 结构完整 | magic、范围、section 和代码项均可解析 | 可以继续分析 |
| 原始完整 | 结构和原始 checksum/signature 均通过 | 可按原始 DEX 保存 |

修改过 checksum 的 DEX 必须标记来源和修补情况，不能与原始 DEX 混在一起。

### 3.7 多个运行期 DEX 的归类

恢复结果不能只按扫描地址排序，因为扫描顺序取决于内存布局。

推荐记录以下元数据：

```text
dex:
  source: anonymous-memory | memfd | archive-entry | filesystem
  address: runtime-address
  file_size: byte-count
  checksum_status: original | repaired | invalid
  digest: stable-hash
  class_count: number
  method_count: number
  loader_relation: primary | supplementary | unknown
  load_order_hint: number-or-null
```

排序优先级建议为：

1. ClassLoader 中的实际 DexElement 顺序。
2. DexFile 内部子 DEX 注册顺序。
3. DEX 文件头中的稳定信息。
4. 类定义覆盖和依赖关系。
5. 扫描地址仅作为最后的诊断信息。

不能把内存地址当作 DEX 名称或最终顺序。

### 3.8 内存 DEX 的采集算法

通用伪代码如下：

```text
for mapping in readable_mappings:
    offset = 0
    overlap = header_size

    while offset < mapping.size:
        block = read_mapping(mapping, offset, chunk_size + overlap)

        for candidate in find_magic(block, "dex\n"):
            file_size = read_u32(candidate + 32)
            if candidate + file_size > mapping.end:
                continue

            dex = read_memory(candidate, file_size)
            if not structurally_valid(dex):
                continue

            digest = hash(dex)
            if digest not in seen:
                save(dex)
                record_metadata()

        offset += chunk_size
```

关键点：

- 分块扫描需要保留重叠区，避免 DEX 头跨越块边界。
- 只对 `file_size` 范围内的数据做完整复制。
- 同一份 DEX 可能同时出现在 Java 堆、native 堆和匿名映射中，必须按摘要去重。
- 只读页、可执行页或通过 `process_vm_readv` 可读取的页都应尝试。
- 读取失败不能终止整个扫描，只记录当前映射不可读。

### 3.9 ClassLoader 关联

纯脱壳不仅需要 DEX 文件，还需要知道它们如何进入运行环境。

应采集：

- `Application.getClassLoader()` 与业务 ClassLoader 是否同一实例。
- ClassLoader 父链。
- `DexPathList.dexElements` 数量和顺序。
- 每个 element 的路径、DexFile 和对象身份。
- DexFile 的 native cookie。
- cookie 内部子 DEX 数量和顺序。
- 运行期 DEX 是否位于标准 APK element 之外。

用途：

- 判断业务代码来自 APK、运行期内存 DEX 还是自定义 ClassLoader。
- 为恢复后的多 DEX 重建提供顺序。
- 检查扫描到的 DEX 是否确实被业务 ClassLoader 使用。
- 识别未注册但由私有 VM 执行的特殊 DEX。

### 3.10 类覆盖与代码完整性

DEX 能解析不代表业务代码完整。应继续检查：

1. 入口 Application 是否存在。
2. Manifest 声明的 Activity、Service、Receiver 和 Provider 是否可解析。
3. 已加载业务类的数量。
4. 是否存在只有 class header、没有 code item 的异常类。
5. 多 DEX 之间的父类和接口依赖是否闭合。
6. native 方法声明是否能在对应 native 模块中找到注册或导出。
7. 资源类是否和资源表对应。

建议输出缺失类、缺失方法和未绑定 native 方法列表，作为脱壳完整性的一部分。

### 3.11 重新打包仅作为验证

可以把恢复出的 DEX 重新放入 APK，用于检查代码能否被标准 DEX 工具和 ART 解析。

重建时：

- 保留原始资源、split APK、assets 和 native 库。
- 根据原多 DEX 顺序生成连续 DEX 名称。
- 保留原始 AndroidManifest 的组件声明。
- 修正 ZIP 对齐和签名只用于测试。
- 不在重建包中加入容器运行期 Hook。

重新打包成功只能证明 DEX 可解析，不证明：

- 壳的 native 初始化被正确移除。
- 业务 Application 可以独立运行。
- 加固运行期不依赖额外资源或私有状态。
- 所有动态加载代码都已恢复。

因此重新打包不是容器运行前的必经步骤，也不能代替原生 Android 环境复原。

### 3.12 纯脱壳盲区

以下情况即使 DEX 扫描通过，也可能没有覆盖完整业务代码：

- 仅在特定页面、登录后或特定配置下才加载的按需 DEX。
- 由 native VM 直接解释，但未注册到 Java DexFile 的代码。
- 只在磁盘或网络下载后短暂解密的内存 DEX。
- native 生成代码、JIT 代码或自定义字节码。
- 被拆成多个不连续映射、无法一次复制的 DEX。
- 依赖页面保护切换才能读取的内存。
- 通过直接 syscall 或其他进程注入加载的代码。

因此脱壳验收应覆盖多个业务入口和长时间运行，而不是只看首屏。

### 3.13 与容器运行的关系

纯脱壳模块可以在容器内使用，但职责必须保持分离：

```text
纯脱壳模块
  → 发现运行期 DEX
  → 保存并验证代码
  → 提供分析结果

容器运行模块
  → 建立原生 Android 进程语义
  → 让加固运行期自行注册并执行代码
```

调试模式下可以临时把恢复结果接入分析器，但不能把“预注入恢复 DEX”作为正常启动方案。否则会掩盖 ClassLoader、DexFile 和 Framework 初始化顺序的真实问题。

当前运行路径不再扫描 `/proc/self/maps` 或读取 `/proc/self/mem` 来发现 DEX。
DEX 的发现、恢复和保存只作为离线诊断能力，不属于正常容器启动链。

## 4. 当前已定位的环境差异

### 4.1 ClassLoader 代码路径

直装和容器都应保持以下形状：

- 父加载器为平台启动加载器。
- 应用 APK 位于业务 ClassLoader 的代码路径。
- 运行期 DEX 通过独立 DexFile 元素挂入同一加载器。
- 共享库使用独立父加载器，不能混入应用 DEX 列表。

当前已修正：

- 不再把共享库 jar 当作普通应用 DEX。
- 外部 Application 的代码路径保持“APK + 运行期 DEX”结构。
- ClassLoader context 的 shared-library 数量不再出现已知不一致。

### 4.2 Native 库搜索路径

直装应用的 ClassLoader 通常包含：

- 已安装 APK 的 native library 目录。
- APK 内部对应的 ABI 库路径。

容器不应把壳自己在数据目录中解出的全部 native 文件自动加入应用级 ClassLoader 搜索路径。那会改变壳看到的库拓扑，也可能影响其内部模块枚举。

当前原则：

- 应用级 ClassLoader 只暴露与直装一致的 native 搜索路径。
- 壳通过绝对路径加载的私有模块仍由平台 linker 正常加载。
- 数据目录中的运行期库保持应用私有权限，不伪装成系统库。

### 4.3 VMRuntime 与 targetSdk

直装进程由 `ActivityThread` 完成绑定，ART/VMRuntime 会获得：

- 目标 targetSdk。
- 目标 package name。
- 目标 data directory。

容器在创建业务 ClassLoader 和 Application 前应补齐同类信息，而不是只修 Java `Build` 或 PackageManager 返回值。

当前已补齐：

- `VMRuntime.setTargetSdkVersion`。
- `VMRuntime.setProcessPackageName`。
- `VMRuntime.setProcessDataDirectory`。

### 4.4 OpPackageName

普通安装完成后，Application 的基础 Context 会以自身包名作为 op package。

容器早期为了兼容真实宿主 UID 的系统服务调用，可能暂时使用宿主 op package。该值必须在业务初始化完成后恢复为目标包名，同时确保系统服务代理仍将真实 Binder 调用重写为宿主可接受的归属信息。

当前原则：

- Application onCreate 期间优先保证真实系统调用可完成。
- Application onCreate 完成后更新为目标 op package。
- 需要真实宿主归因的系统服务通过统一代理处理。

### 4.5 dladdr 和模块路径稳定性

`dladdr()` 返回的模块路径在原生 Android 中具有稳定生命周期。容器将实际路径映射为虚拟路径时，不能返回可被下一次调用覆盖的临时缓冲区。

当前已改为稳定路径缓存：

- 同一实际模块始终返回同一虚拟路径字符串。
- 多次查询不同模块不会互相覆盖。
- 壳可以安全保存并比较多个模块路径。

### 4.6 文件权限与 stat

APK 安装目录中的 native 库与数据目录中动态解出的库具有不同所有权语义：

- 安装目录通常由系统管理。
- 数据目录中的运行期库属于应用私有文件。

不能因为文件以 `.so` 结尾就统一伪装成系统 native 库。当前实现只对真实安装库目录应用系统库属性；实例数据目录中的运行期模块继续按目标应用私有文件处理。

### 4.7 调试兼容层的开关语义

调试兼容层必须默认关闭，且布尔属性 `"0"` 必须表示关闭。

当前已修正：

- `native_field_fallback=0` 不再安装 `Throw/ThrowNew` hook。
- `0`、属性缺失、无效值均不得触发高风险 hook。

这类错误会污染 ART 异常路径和 trampoline，不能用于运行时验收。

## 5. 当前主要阻塞点

### 5.1 壳私有运行期表

当前观察到的共同故障形态是：

1. 首个业务 Activity 可以成功解析和创建。
2. 下一个 Activity 触发新的业务类解析。
3. 加固运行期进入中间代码解释器，并为当前虚拟方法建立执行上下文。
4. 上下文构造函数按“虚拟寄存器数 × 4”分配 32 位寄存器表，按“虚拟寄存器数 × 8”分配引用表。
5. 后续执行中，解码器返回的虚拟寄存器号远大于该方法声明的寄存器数。
6. 解释器以错误寄存器号索引寄存器表，越过实际可读范围。
7. 进程产生 `SIGSEGV / SEGV_ACCERR`。

已确认的细节：

- 故障位置的解码结果具有稳定特征，不是单纯随机越界。
- 直装冷启动也会调用通用解码器，但不会进入容器当前命中的失败执行器分支。
- 容器路径已经生成完整的一组类型、JNI 和对象描述，说明代码并没有缺失。
- 因此根因更接近“同一方法在运行期出现控制流或虚拟 PC 分叉”，而不是缺少业务类、DEX 或寄存器表容量。

诊断时应使用内核硬件断点或 `perf_event_open` 寄存器采样，避免 `ptrace` 改变 `TracerPid`。外部 ptrace 会在加固运行期完成 `JNI_OnLoad` 前触发其反调试分支，从而观察不到真实故障。

进一步对比直装与容器后，已经排除了几个常见误导方向：

- 同一运行期方法的原始字节码缓冲区在直装和容器中逐字节一致。
- 上下文构造函数在两边收到的虚拟寄存器数量一致。
- 容器中的寄存器表按“寄存器数 × 4”分配，第二张引用表按“寄存器数 × 8”分配。
- 捕获到的 `x19` 上下文在崩溃前没有被 `free`，因此不是简单的 use-after-free。
- 崩溃时解码出的源、目标虚拟寄存器号远大于该方法声明的寄存器数。
- 采样时 `x22` 指向的方法头合法：寄存器数、参数数、指令区和代码长度都与调用上下文一致。
- 应用可见的 UID、GID、附加组、信号掩码、Seccomp 和 `/proc/self/status` 已能与直装形态对齐。

因此当前最可信的结论是：解释器在上下文初始化之后出现了执行状态分叉，导致指令指针错位或进入了错误处理器。继续修改表容量、字段偏移或越界页面不会恢复正确语义。下一步应比较首次方法执行期间的分支结果、虚拟 PC 推进和处理器选择。

### 5.1.1 首次指令处理器分叉

对同一段隐藏运行期代码做了逐次硬件断点采样，结果为：

```text
直装：
  code item 起点
    -> 通用操作数解码
    -> 下一次解码位于 +6 字节
    -> 随后跳到 +0x16 字节

容器：
  code item 起点
    -> 通用操作数解码
    -> 同一 PC 再次解码
    -> 只前进 +4 字节
    -> 下一条指令已经越界访问虚拟寄存器表
```

两次运行中，第一次解码收到的输入完全一致：

- 当前方法声明 2 个虚拟寄存器。
- 两个进程都从同一个 DEX 文件偏移创建了相同的方法上下文。
- 上下文状态字节均为 `0x55`。
- 当前 32 位代码字均为 `0x3d7e07e4`。
- 解码辅助函数都读取相同的高 16 位操作数。

因此可以排除“DEX 字节不同”“上下文状态字节不同”和“通用解码函数算法不同”这三种解释。

真正的差异发生在进入解码函数之前：

- 直装第一次调用由另一套运行路径发起，随后没有进入容器的当前解释器处理器。
- 容器第一次调用由解释器的双操作数处理器发起；该处理器在同一 PC 连续解码两次，然后按 4 字节推进。
- 容器该处理器拿到的打包操作数为 `0xdcaf`，目标寄存器号为 `0xdc`，已经远大于方法声明的 2 个寄存器。
- 下一次进入崩溃处理器时，打包操作数变成 `0x10c0c`，目标寄存器号为 `0x0c`，仍然非法。
- 对容器解释器处理器下执行断点时，直装冷启动没有任何命中；这进一步证明直装没有走当前解释器分支。

这说明首个操作数解码结果不是首要问题；首要问题是同一方法在直装和容器中被送入了不同的执行模式或首条指令处理器。后续必须比较：

1. 加固运行期如何决定解释执行、JIT 预热或已编译代码复用。
2. 方法注册时保存的执行模式、处理器表和选择条件。
3. 首次业务 Activity 加载前，方法是否已经完成验证或预热。
4. ART 内部方法与加固私有方法描述符之间的关联是否完整。

不能用“把虚拟寄存器表扩大到 256 项”或“忽略越界写入”来掩盖问题。这样做只会把错误 PC 和错误操作数继续传播到后续字段、方法解析，最终形成更难定位的错误。

### 5.1.2 延后 syscall 身份网关对照

为避免包装器在早期解包阶段检测 seccomp，曾在目标 Application 完成 `onCreate` 后、首个 Activity 打开前安装直接 syscall 身份网关。该网关会虚拟化 `getuid/geteuid/getgid/getegid`，并对 `/proc/self/*`、APK 路径和 native 路径做统一重写。

结果：

- 网关成功安装，日志为 `external syscall gateway installed=true`。
- 目标启动时间延长约 3 秒。
- 最终仍进入同一失败处理器，并得到完全相同的 `x25=0x10c0c`、`x23=0x0c` 和越界寄存器表访问。

因此，单纯让 native 直接 syscall 返回目标 UID 并不足以改变执行模式选择，至少不是唯一条件。该实验没有保留为默认路径。

### 5.1.3 AOT 预热对照

直装进程额外映射了目标应用数据目录下的 `cache/oat_primary/arm64/base.art`，容器没有同名目标映射。为验证这是否是执行模式选择条件，执行了：

```text
cmd package compile -m speed -f <target-package>
```

系统报告目标 ABI 的编译状态为 `speed`，产物位于 APK 安装目录下的 `oat/arm64/base.odex`。随后绕过 MCP 的 20 秒启动等待限制，直接启动容器宿主 Activity，避免长启动被测试工具终止。

结果仍在首个业务 Activity 创建后于同一处理器崩溃，越界寄存器和故障地址特征不变。因此：

- 复用 APK 的系统 AOT odex/vdex 本身不足以保证进入直装的执行路径。
- 目标数据目录下 `base.art` 的存在与否不是唯一选择条件。
- 需要继续定位加固运行期的方法级执行模式选择，而不是只做包级 dex2oat 预热。

### 5.1.4 同包已安装副本对照

针对设备上存在同版本、同签名真实安装副本的情况做了两组对照：

- 存在真实安装副本时，容器会选择该副本的 APK 和 native 库路径。
- 移除真实安装副本后，容器改用导入 APK 和实例 native 缓存路径。

两组都在首个业务 Activity 打开后进入同一失败解释器，并产生相同的越界寄存器特征。因此：

- 真实安装路径泄漏不是本次首指令分叉的直接根因。
- 不能通过依赖真实安装副本来解决兼容性问题。
- 虚拟空间仍应使用自身导入的 APK、实例数据目录和虚拟身份；真实副本只能作为元数据或 AOT 复用候选，不能成为运行正确性的前提。

当前仍需重点验证的进程级差异是真实内核身份，而不是应用侧伪装值。运行期本地查询、PackageManager 和 `Binder.getCallingUid` 可以返回虚拟身份，但内核仍以宿主 UID 和 SELinux 分类执行 Binder 事务。加固运行期如果通过 native 或直接 Binder 路径交叉比较这份真实身份，仍可能选择与直装不同的校验分支。

后续又完成了三组对照：

- 手工 `Activity.attach` 与框架 `ActivityThread.startActivityNow` 两条启动路径都会进入同一失败执行器，说明问题不在单一 Activity 创建方式。
- 故障前额外的 ActivityTaskManager 查询均正常收到 reply，没有权限拒绝、死亡客户端或异常返回。
- 将当前应用自报 UID 临时统一为宿主 UID 后，进程可见身份更自洽，崩溃延迟约一秒，但仍未越过同一执行器故障。该实验已撤回，不保留为目标 UID 的最终实现。

因此下一阶段不能只继续补 Java/PackageManager 查询；需要把内核调用者身份与目标应用可见身份一起设计，或证明执行器分叉完全不依赖内核身份。

这说明：

- DEX 字节不是首要问题。
- 单纯修改页面权限、把缺项补零或循环映射会破坏语义。
- 加固解释器在运行期进入了错误处理器或错位虚拟 PC。

不能采用以下临时方案作为最终实现：

- 对固定 native 偏移打补丁。
- 把越界读取强制归零。
- 把表指针直接绕回映射开头。
- 通过固定字段名、类名或索引补充业务代码。
- 针对特定壳或特定应用维护特征分支。
- 只把内部表指针改成所在映射的起点；这种处理没有证明该节点原本应当承担表基址语义。

这些方案可能让流程前进一步，但会改变正常的类、字段以及 DEX 解析结果，最终表现为缺字段、错误方法或后续 Activity 继续崩溃。

### 5.1.5 同机直装与首指令状态复核

在同一台设备上保留同版本直装副本，并分别冷启动直装进程和容器进程后，进一步复核了运行期代码与首个失败方法的执行状态：

- 两侧运行期可执行映像逐字节一致，SHA-256 相同。
- 两侧操作码处理表在归一化映射基址后完全一致。
- 两侧进入同一方法代码块时，代码项头部、固定操作数区和首个 32 位代码字一致。
- 直装的解码调用来自另一组处理器，解码位置最后会推进到代码项起点后 `0x10`。
- 容器从代码项后 `0x04` 的位置进入双寄存器移动处理器，随后得到非法源/目标寄存器号并越过寄存器表。

因此可以进一步排除：

- 运行期映像被不同版本覆盖。
- 操作码处理表被通用 hook 改坏。
- 固定操作数区或代码项元数据不同。

当前差异仍表现为：同一代码项在直装中选择的处理流程和虚拟 PC 序列不同。后续应优先定位进入首个处理器之前的方法上下文创建、执行模式字段和调用方状态，而不是继续检查处理表内容。

### 5.1.6 历史解包 DEX 混代

诊断中发现运行期 DEX 缓存会跨多次解包累积。后续启动可能同时注册多个时期的同名类，较小的资源类副本缺少后续新增字段，造成：

- JNI 字段查询失败。
- Java 直接字段访问失败。
- 同一个类通过反射可见字段，但原生解释器仍按旧定义查询。

已加入“当前代清单”：成功捕获后只暴露本次生成的一组 DEX，旧文件保留但不再参与类路径。同时按 DEX 体量优先排序，避免资源型小副本遮蔽完整定义。

这能消除历史缓存引起的字段缺失，但无法单独修复首个启动 Activity 的解释器分叉，也不能替代正确的 ART 类注册语义。

### 5.1.7 启动 Activity 与首页 Activity 的差异

对启动 Activity 和后续首页 Activity 分别创建时，观察到：

- 启动 Activity 在首个隐藏方法解释阶段进入错误处理器。
- 首页 Activity 可以先完成 `onCreate`，但随后因 AndroidX 资源字段重复定义和 Fragment 生命周期状态缺失而在 `onStart`、`onPostCreate` 失败。

因此当前至少存在两个独立问题：

1. 加固运行期的首个方法执行模式与直装不同。
2. 多代或分段 DEX 中的资源类和框架类视图仍可能不完整。

不能用“绕过启动页直接打开首页”作为验收结论；这条路仍会因生命周期状态不完整而关闭宿主。

### 5.2 同进程 Android 单例边界

普通 Android 进程中的以下对象近似进程级单例：

- `ActivityThread`。
- `Instrumentation`。
- `LoadedApk`。
- 主 Looper。
- 进程级 native linker namespace。
- 全局 property、JNI 和 signal 状态。

当前容器使用“一个槽位进程只承载一个实例”的约束来接近普通安装环境。该约束必须保留。

### 5.3 真实内核身份

免 Root 单 APK 无法把宿主内核 UID 改成目标应用 UID，也无法让 `system_server` 中的真实 Binder caller 变成另一个应用。

因此需要区分：

- 目标代码观察到的身份。
- 系统服务根据真实调用者看到并允许的身份。

两者必须通过统一 proxy 协调，不能只修改其中一层。

### 5.4 直接 syscall

libc GOT/PLT hook 无法覆盖所有内联 `svc` 或直接 syscall。对直接 syscall，应继续通过统一 native gateway 或明确的 capability 边界处理。

不能为了让一个分支继续执行而注入额外 seccomp 层，却不虚拟化 `Seccomp`、filter 数量、tracer 状态和 signal 行为。

### 5.5 Settings Provider 的静态缓存

Framework 会为 `Settings.Global`、`Settings.Secure` 和 `Settings.System` 保存进程级 `NameValueCache`。在容器进程中，这些缓存可能先通过宿主身份取得 Provider，随后目标 Provider 初始化再使用该缓存时，就会出现：

```text
Calling uid: host-uid doesn't match source uid: target-uid
```

这会让目标在早期 Provider 初始化阶段收到 `SecurityException`，并可能改变后续运行分支。

当前处理方式：

1. 在发布目标 ContentResolver 后、目标 Provider attach 前，清理三个 Settings 命名空间的静态缓存。
2. 通过目标虚拟 ContentResolver 预热 SQLite 和显示相关设置项。
3. Provider overlay 对 Settings 的读取使用宿主侧真实调用身份查询，再以标准 `Bundle("value")` 返回。
4. `Settings.Secure.ANDROID_ID` 继续返回按应用实例派生的虚拟值。

该处理移除了启动阶段的 UID 校验异常，但不能消除免 Root 同进程容器中“目标包身份”和“真实 Binder UID”之间的根本差异。

### 5.6 外部 Application 包装器的路径缓存

加固入口经常在 `attachBaseContext` 中读取：

- `Context.getFilesDir().getParentFile()`
- `Context.getDir(...)`
- `Context.getPackageCodePath()`

并把结果缓存到静态字段。Framework 的 `ContextImpl.getDataDir()` 并不只读取
`LoadedApk.mDataDir`，还会优先使用 `LoadedApk.mDataDirFile`。如果只替换字符串字段，
而保留原来的 `File` 对象，包装器就会把宿主数据目录和 native 库路径写入自己的静态缓存。

当前修复包括：

1. 同时设置 `mDataDir`、`mDataDirFile`、`mCredentialProtectedDataDirFile` 和
   `mDeviceProtectedDataDirFile`。
2. 外部包装器挂载完成后，把其静态 `Context` 字段从临时 framework Context 重新绑定到
   容器目标 Context。
3. 通过 JNI 探针确认真实返回值已经是目标包路径，而不是宿主路径。

修复前：

```text
/data/data/<host>/.jiagu/libjiagu_64.so
/data/data/<host>/.jiagu
```

修复后：

```text
/data/data/<target>/.jiagu/libjiagu_64.so
/data/data/<target>/.jiagu
```

该差异会直接参与加固运行期的路径校验和内部键生成。修复后崩溃仍然存在，说明它不是唯一根因，
但属于必须保持一致的原生安装事实。

## 6. 复原原生 Android 的设计原则

### 6.1 直装进程是对照基准

所有关键状态都应与直接安装进程比较：

- Application 实例和真实 Application 的关系。
- Application 基础 Context。
- ClassLoader 身份和父加载器。
- DexPathList 元素。
- DexFile native cookie。
- native library 搜索路径。
- ApplicationInfo。
- VMRuntime 状态。
- 模块路径和文件属性。

### 6.2 一份事实，多个观察面

同一环境事实必须在 Java、native、文件、属性和系统服务中保持一致：

```text
虚拟进程事实
  ├─ Java Framework
  ├─ ART / ClassLoader
  ├─ libc / direct syscall
  ├─ /proc
  ├─ property
  ├─ file / stat / readlink / dladdr
  └─ Binder / system service proxy
```

只修改一个观察面会产生新的跨层矛盾。

### 6.3 优先复现启动顺序

Application、Provider、Activity 和 Framework 状态应按普通 Android 的顺序建立。包名、UID、dataDir、ClassLoader 和 native 环境必须在业务代码第一次读取前就绪。

### 6.4 不把“能运行”建立在伪造壳内部结构上

壳的私有表应由同一套 Android/DEX 注册状态自然生成。容器负责提供正常环境，不负责推测壳需要的槽位内容。

### 6.5 诊断能力默认低开销

高成本能力只在显式调试属性下开启：

- ClassLoader 形状打印。
- Activity 类加载前暂停。
- native 大块分配记录。
- 模块路径查询记录。
- DEX/native 内存采样。
- 信号和寄存器跟踪。

普通启动不得依赖这些诊断开关改变行为。

## 7. 建议的后续方案

### 7.1 对比直装与容器的 ART 内部状态

下一阶段应优先获取：

- ClassLoader 对象身份。
- DexPathList 和 DexElement 身份。
- DexFile cookie 对应的内部对象。
- 运行期 DEX 子文件注册顺序。
- 首次 Activity 解析前后的 ClassLinker/DexCache 状态。

目标不是读取壳的业务数据，而是确认 ART 是否按普通安装应用的方式注册了同一批 DEX。

### 7.2 以 `ActivityThread.handleBindApplication` 的状态模型为基准

建议把容器初始化整理为与 Framework bind 阶段对应的步骤：

1. 写入目标 VMRuntime targetSdk。
2. 写入目标 package name 和 dataDir。
3. 创建与 LoadedApk 一致的 ClassLoader。
4. 绑定目标 ApplicationInfo 和 Resources。
5. attach 业务 Application。
6. 执行 Application.onCreate。
7. 再进入首个 Activity 创建。

每一步都应能在诊断中单独确认。

### 7.3 把壳生成的运行期 DEX 视为正常多 DEX

恢复出的运行期 DEX 应通过标准 DexFile/ClassLoader 机制注册。若必须由壳自行注册，则容器应继续提供：

- 正常的 APK 路径。
- 正常的 native library 路径。
- 正常的 target package/UID 观察值。
- 正常的模块路径和文件权限。
- 正常的 Framework 状态。

避免再次通过预注入、替换字段或手工拼装运行期表来绕过问题。

### 7.4 为多 Activity 连续加载建立回归矩阵

至少验证：

- 冷启动 20 次。
- 首 Activity 创建。
- 第二 Activity 创建。
- 页面来回切换。
- 退出后重新进入。
- 同一实例二次冷启动。
- 不同实例并行运行。
- native 线程启动和退出。

验收不能只以“首屏图片出现”为准。

### 7.5 保持 wrapper Context 与真实 Application 的入口一致

外部运行时 wrapper 可能在 `attachBaseContext` 中通过
`context.getClassLoader().loadClass(entryApplication)` 挂载真实
Application。容器创建的临时 `ContextImpl` 必须同时具备目标的
ClassLoader、LoadedApk ApplicationInfo、包名、dataDir、APK 路径和
Resources；只替换 Resources 会让 wrapper 挂载失败。

wrapper 挂载完成后，wrapper 和真实 Application 的 base Context 都应
切回容器的虚拟 Context，避免业务 Application.onCreate 再次通过系统
StorageManager 读取宿主 UID 的外部目录。

### 7.6 对比原始系统调用，而不只对比 libc/Parcel/PackageManager

对同一 APK 的直接安装与容器进程进行系统调用对比后，可以观察到：

```text
直接安装：
  libjiagu 初始化后读取 getuid() -> 目标包 UID

容器：
  libjiagu 初始化后读取 getuid() -> 宿主 UID
```

即使 libc `getuid`、直接 `syscall(SYS_getuid)`、`/proc/self/status`
和 `/proc/self` stat 已做了虚拟化，壳仍可能在 native 初始化极早阶段
使用未被现有 hook 覆盖的原始调用。该值会影响后续隐藏解释器的初始化
状态，表现为后续 `move-result-object` 使用错误的解释器寄存器索引。

诊断上可比较：

- 进入 Application.attach 前的 UID。
- native runtime 首次 `dlopen` 后的 UID。
- 首个 Activity 前的 UID。
- ClassLoader 和 Application 挂载状态。

不能在 wrapper 初始化前直接增加可被壳识别的 seccomp filter；这会让
wrapper 拒绝挂载真实 Application。Application 完成后再安装 gateway
又无法修正早先形成的 native 初始化状态。

## 8. 当前验收标准

通过标准应同时满足：

1. 运行期 DEX 与直装进程字节一致。
2. Application ClassLoader 结构和关键内部状态与直装一致。
3. Application 成功创建。
4. 首个业务 Activity 以及后续 Activity 稳定创建。
5. 连续冷启动无 native crash。
6. 退出和重新进入后状态可恢复。
7. 不依赖目标包名、业务类名、哈希或壳特征补丁。
8. 不通过越界归零、页面乱映射或字段猜填补来推进流程。
9. libjiagu 初始化前后和首个 Activity 执行期间看到的进程身份一致。

## 9. 明确禁止的方案

- 为目标应用添加固定包名、类名或哈希分支。
- 在通用核心中写入壳厂商特征。
- 针对固定 native 偏移打补丁来跳过检查。
- 把越界读取统一填零。
- 用静态字段对象冒充实例字段。
- 手工重写壳私有引用表。
- 在未模拟 `Seccomp`/signal 语义时注入额外 filter。
- 为了让首屏出现而吞掉业务异常。

这些方案可以用于一次性诊断，但不能作为通用运行时能力提交。

## 10. 当前结论摘要

当前研究阶段可以记录为：

```text
DEX 恢复：完成
运行期代码完整性：已验证
Application 挂载：完成
首 Activity 创建：进入原生 onCreate，但尚未完成
多 Activity 连续加载：未稳定
根因方向：native 初始化早期仍可观察到宿主内核身份
架构缺口：缺少进程级凭据/UID 隔离，现有 hook 无法覆盖所有原始调用
最终路线：恢复原生 Android 行为，而不是继续修补壳私有结构
```

下一步工作的核心不是再次“脱壳”，而是把容器中的 ClassLoader、ART 内部 Dex 注册和 native 进程事实收敛到与直接安装应用一致。

## 11. 阶段暂停点与下次接续记录

本节是阶段性总结。当前没有完成最终验收，不再继续尝试临时绕过。
下次应从“进程级身份隔离”开始，而不是重新从脱壳或 DEX 捕获开始。

### 11.1 已经确认完成的部分

- 同一 APK 的导入、签名/版本校验和安装副本选择正常。
- 容器显示的 package UID 已与系统安装副本一致，不再使用旧实例 UID。
- 密封运行期代码的抓取、校验、代次记录和重复文件去重已完成。
- Application 类加载器、wrapper Context 和真实 Application Context 已对齐。
- wrapper 使用标准 ClassLoader 时可以挂载真实 Application。
- `Application.onCreate` 可以成功完成，业务 Provider 可以创建。
- 首个 Activity 类可以被解析，并会进入原生 `onCreate`。
- 默认启动不再依赖 JNI 类/字段、seccomp 或越界恢复实验开关。

### 11.2 当前失败表现

当前构建默认配置下，首个 Activity 会进入原生 `onCreate`，随后失败。
不同诊断配置会把同一个根因暴露成不同表面错误：

1. 保留当前运行期 DEX 注册：
   - 报告资源常量类缺少字段。
   - Java 侧对该类反射是完整的，差集不在普通 ClassLoader 层。
2. 使用更接近直装的 APK-only ClassLoader，并停止额外 DEX 注册：
   - 不再报告 Java 缺字段。
   - native 隐藏解释器在 `move-result-object` 路径读取越界寄存器索引。
3. 打开 JNI 类/字段一致性桥：
   - 缺字段错误可以越过。
   - 后续仍会出现实例字段空指针或解释器越界，说明错误发生在壳内部执行状态，
     不是 JNI 字段 API 本身。
4. 打开运行时字段解析 retry：
   - 会改变壳自身初始化流程，并产生更早的 native 崩溃，不能作为正式方案。

诊断中看到的解释器错误现场：

```text
pc offset:  0x81294
destination: v12
opcode bits: 0x10c0c
source index computed by decoder: out of range
```

这些偏移只用于诊断，不能作为固定 native patch 条件。

### 11.3 根因证据

同一设备上对直接安装和容器进程做系统调用对比：

```text
直接安装：
  native runtime 初始化后 getuid() = 系统安装副本 UID

容器：
  native runtime 初始化后 getuid() = 宿主进程 UID
```

容器在 Java、Parcel、PackageManager、`/proc/self/status`、
`/proc/self` stat 和通常的 libc `getuid` 上已经返回虚拟 UID，但壳在
native runtime 极早初始化阶段仍能读到宿主内核身份。

该时间点早于业务 Application。壳会据此建立不同的隐藏解释器初始化状态；
之后再修正 Context、ClassLoader、JNI 字段或 DEX，只能改变表面错误，
不能恢复正确解释器。

已验证的辅助事实：

- 直接安装与容器的原生可执行代码镜像字节一致。
- 首个 Activity 的原生方法入口和 JIT 代码高度一致。
- Java 层资源常量类的字段集合一致。
- 因此问题不是单个 DEX 缺失或某个 Java 类定义错误。

### 11.4 已尝试但不应重复的方案

- 延迟首个 Activity：不改变壳的内部初始化分支。
- 清空 native environment：无法正常加载运行期库。
- 更改首个 Activity 的延迟、框架启动方式或手动 attach：错误相同。
- Application 完成后才安装 seccomp gateway：时间太晚。
- wrapper 初始化前安装 seccomp gateway：壳会识别到环境被额外 filter
  包裹，拒绝挂载真实 Application。
- 用 JNI `FindClass`/`GetFieldID` 直接替换结果：可以改变异常位置，不能修复
  native 解释器寄存器状态。
- 越界读取归零：只能用于观察后续错误，不能作为实现。
- 固定 native 偏移跳转或修补：违反通用运行时原则，且未证明能稳定启动。

### 11.5 当前代码中与本次结论直接相关的修复

- [`ContainerSession.kt`](../app/src/main/java/com/coke/nestspace/container/ContainerSession.kt)
  - 临时 shell Context 补齐目标 ClassLoader、LoadedApk 信息和 Resources。
  - Application 挂载后重新绑定 wrapper 和真实 Application 的 base Context。
  - 运行期终止入口会被容器记录，避免业务 wrapper 直接杀死整个宿主进程。
- [`ExternalApplicationBootstrap.kt`](../app/src/main/java/com/coke/nestspace/container/application/ExternalApplicationBootstrap.kt)
  - 根据 wrapper 的公开入口字段配置真实 Application 类名。
  - 完成 Context 重新绑定。
- [`RuntimeDexPathClassLoader.kt`](../app/src/main/java/com/coke/nestspace/container/loader/RuntimeDexPathClassLoader.kt)
  - 只处理显式代码路径和已存在的 DEX 文件，不扫描进程内存。
- [`01_jni.inl`](../app/src/main/cpp/hooks/libc/parts/01_jni.inl)
  - 类/字段一致性 bridge 为显式诊断能力，默认关闭。
  - `FindClass` inline hook 只有在显式属性为 `1` 时安装。
- [`native_environment.cpp`](../app/src/main/cpp/core/native_environment.cpp)
  - 记录并容纳 `Runtime.nativeExit`，避免业务初始化直接终止容器宿主。
- [`late_jni_trace.cpp`](../app/src/main/cpp/core/late_jni_trace.cpp)
  - 运行时字段解析 retry 与普通字段异常恢复已经拆分。

### 11.6 下次优先方案

优先实现真正的进程级身份隔离，而不是继续扩大 hook 覆盖率。

候选顺序：

1. 在 native runtime 加载前，让目标进程在独立 credential/UID 视图中运行。
2. 确保该视图同时满足：
   - 不直接使用目标应用真实系统 UID；
   - `getuid`、`geteuid`、`getresuid` 在 libc、syscall、`/proc` 中一致；
   - SELinux 和 Binder caller 视图不暴露宿主身份；
   - ClassLoader 仍使用目标的正常 APK、dataDir 和 ApplicationInfo。
3. 如果应用 UID 无法使用 user namespace，应评估独立的隔离进程槽或专用
   native spawner，而不是在业务进程内继续补原始系统调用。
4. 首个验证点不是首屏，而是 native runtime 初始化后立即比较：
   - UID；
   - 进程名；
   - SELinux context；
   - `/proc/self` owner；
   - ClassLoader；
   - Application 挂载状态。
5. 只有上述状态一致后，再测试首个 Activity 和后续页面。

不建议下一次继续尝试：

- 再调整 DEX merge 顺序；
- 再添加某个 JNI 字段桥；
- 再增加固定的 native 偏移恢复；
- 用首屏截图代替运行时身份验证。

### 11.7 设备与工作区状态

截至本次暂停：

```text
模拟器：emulator-5556
最新 snapshot：已构建并安装
诊断属性：已全部清零
NestSpace 与目标应用：已 force-stop
直接安装参考应用：保留在设备上
工作区：当前阶段修改已提交为 Git 检查点
最新验证：assembleSnapshot、testDebugUnitTest、git diff --check 通过
```

下次恢复测试前先检查：

```bash
adb devices -l
adb -s emulator-5556 shell getprop | grep debug.nestspace
git status --short
```

构建命令：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:assembleSnapshot --no-daemon
```

不要把已提交的研究检查点当作已完成基线回滚。下一次应直接基于该提交继续，
先阅读本节和 7.5、7.6，再从进程级身份隔离开始。

更完整的独立总结见
[加固运行时复原总结](hardened-runtime-summary.md)。
