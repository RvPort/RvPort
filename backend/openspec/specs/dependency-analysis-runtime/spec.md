# dependency-analysis-runtime Specification

## Purpose
定义构建事实驱动的依赖分析能力，用于基于当前源码在 source/x86 环境中的成功构建事实提取构建、链接和运行依赖，并查询 target/RISC-V 环境能力生成可分页、可归档、可加入迁移输入的分析结果。
## Requirements
### Requirement: 依赖分析必须基于 x86 构建事实
依赖分析运行时 SHALL 基于当前源码版本在 source/x86 环境中的成功构建事实提取依赖，并 SHALL 使用 RISC-V 目标环境能力判断这些依赖是否可满足。

#### Scenario: 创建依赖分析任务
- **WHEN** 已登录用户调用 `POST /api/projects/{projectId}/dependency-analyses`
- **THEN** 后端 SHALL 校验当前用户有权访问项目
- **AND** 后端 SHALL 读取当前 active source revision 或请求指定的 `sourceRevisionId`
- **AND** 后端 SHALL 查找同一源码版本下成功的 source/x86 构建运行
- **AND** 后端 SHALL 创建 `DEPENDENCY_ANALYSIS` 任务和依赖分析运行记录
- **AND** 任务输入 MUST NOT 包含明文凭据、服务器真实路径、完整构建脚本或完整日志

#### Scenario: 没有成功 x86 构建
- **WHEN** 当前源码版本没有成功的 source/x86 构建运行
- **THEN** 后端 SHALL 返回明确业务错误
- **AND** 后端 MUST NOT 创建依赖分析运行、任务或队列消息

#### Scenario: 不依赖 RISC-V 构建失败证据
- **WHEN** 创建依赖分析任务
- **THEN** 后端 MUST NOT 要求存在 RISC-V 构建运行
- **AND** worker MUST NOT 读取 RISC-V 构建失败日志或测试失败日志作为必要输入

### Requirement: 依赖分析必须提取构建和运行依赖
依赖分析 worker SHALL 从 x86 构建事实中提取构建依赖、链接依赖、运行依赖和可选测试依赖，并 MUST NOT 将 compiler flag、宏或构建元数据文件本身作为依赖分析结果项。

#### Scenario: 使用多信号识别构建系统
- **WHEN** worker 分析用户提供的自定义构建脚本
- **THEN** worker SHALL 使用源码静态探测、`PS4` shell trace、可选 `strace execve`、构建日志和构建产物扫描组合识别构建系统
- **AND** 构建系统识别结果 SHALL 支持多个候选值、证据来源和置信度
- **AND** worker MUST NOT 依赖单一构建系统类型作为依赖提取前置条件

#### Scenario: strace 可用时采集 execve
- **WHEN** source/x86 环境存在 `strace`
- **THEN** 构建 wrapper MAY 使用 `strace -f -e execve` 采集真实子进程启动链
- **AND** worker SHALL 从 exec trace 中提取构建工具、编译器、linker、pkg-config、测试工具和关键参数摘要
- **AND** 采集结果 MUST 进行敏感参数脱敏

#### Scenario: strace 不可用时有限安装
- **WHEN** source/x86 环境不存在 `strace`
- **THEN** worker MAY 在构建前按目标环境包管理器执行一次受控、有限的 `strace` 安装尝试
- **AND** 安装尝试 MUST 有超时、日志记录和失败降级
- **AND** 安装失败 MUST NOT 阻断用户构建
- **AND** worker SHALL 记录 `straceUnavailable` 或等效 warning

#### Scenario: 无 strace 降级分析
- **WHEN** `strace` 不可用或被环境策略禁止
- **THEN** worker SHALL 继续使用 `PS4` shell trace、构建日志解析和产物扫描提取依赖事实
- **AND** 依赖分析 MUST NOT 因缺少 `strace` 直接失败

#### Scenario: 采集自然生成的编译数据库
- **WHEN** x86 构建完成后源码目录或构建目录中存在 `compile_commands.json`、`CMakeCache.txt` 或 `build.ninja`
- **THEN** worker SHALL 采集这些文件的结构化摘要
- **AND** worker SHALL 从中提取编译器、源文件、include path、编译参数、构建 generator 和构建工具线索
- **AND** 响应和结果 MUST NOT 暴露服务器真实路径或未脱敏的敏感参数
- **AND** `compile_commands.json`、`CMakeCache.txt`、`build.ninja` 文件本身 MUST NOT 作为依赖分析结果项保存或展示

#### Scenario: 采集编译器内置宏
- **WHEN** source/x86 构建环境存在 `cc`、`gcc`、`clang`、`c++`、`g++` 或 `clang++`
- **THEN** 构建 wrapper MAY 使用实际编译器执行 `-dM -E` 采集本次环境的 C/C++ 内置宏集合
- **AND** worker MAY 使用该集合辅助内部 evidence 分类
- **AND** `make`、`cmake`、`ninja`、`meson` 等构建系统 MUST NOT 被当作 C/C++ 编译器内置宏来源
- **AND** 编译器内置宏、项目自定义宏和构建系统 flag probe 宏 MUST NOT 作为依赖分析结果项保存或展示

#### Scenario: 使用 Bear 生成 compile_commands
- **WHEN** source/x86 环境存在 Bear
- **THEN** 构建 wrapper MAY 使用 Bear 包裹用户构建脚本生成 `compile_commands.json`
- **AND** Bear 生成结果 SHALL 标记来源为 `BEAR`
- **AND** worker SHALL 将 Bear 结果作为增强 evidence，而不是唯一依赖来源
- **AND** Bear 自身 MUST NOT 作为用户项目依赖结果项展示，除非它由用户构建命令显式依赖且不是后端为了采集临时安装或调用

#### Scenario: Bear 不可用时有限安装
- **WHEN** source/x86 环境不存在 Bear
- **THEN** worker MAY 在构建前按目标环境包管理器执行一次受控、有限的 Bear 安装尝试
- **AND** 安装尝试 MUST 有超时、日志记录和失败降级
- **AND** 安装失败 MUST NOT 阻断用户构建
- **AND** worker SHALL 记录 `bearUnavailable` 或等效 warning

#### Scenario: 不得为采集改变用户构建行为
- **WHEN** worker 尝试采集编译数据库
- **THEN** worker MUST NOT 为了生成 `compile_commands.json` 强行修改用户传入的构建参数、CMake generator、构建目录或构建脚本语义
- **AND** 如果用户构建自然没有产生编译数据库且 Bear 不可用，worker SHALL 使用其他观测信号继续分析

#### Scenario: 提取构建依赖
- **WHEN** worker 分析 x86 构建命令和构建日志
- **THEN** worker SHALL 提取编译器、构建系统、pkg-config module、header、dev package、`-I`、`-L` 和构建工具线索
- **AND** 提取结果 SHALL 保留证据来源和置信度
- **AND** compiler flag、宏、构建数据库文件名和构建元数据文件名 MUST NOT 作为依赖结果项生成

#### Scenario: 提取链接和运行依赖
- **WHEN** worker 分析构建产物
- **THEN** worker SHALL 采集 ELF `NEEDED`、`SONAME`、解释器、动态库、owner package 和可执行文件依赖
- **AND** worker SHALL 将源环境 owner package、源版本、目标 provider package、目标版本、版本关系和风险映射作为可分页结果明细保存

#### Scenario: 排除本次构建生成产物
- **WHEN** worker 采集源码目录或构建目录中的 ELF、`.so` 或可执行文件
- **THEN** worker SHALL 通过构建前后 manifest 或等效机制区分仓库原有文件和本次构建新增产物
- **AND** 本次构建新增产物 SHALL 只作为排除证据记录，不作为依赖分析结果项
- **AND** 源码仓库原本携带的 ELF、`.so` 或可执行文件 SHALL 继续作为依赖候选分析

#### Scenario: 提取测试依赖
- **WHEN** 依赖分析输入包含可用测试事实
- **THEN** worker MAY 提取测试工具、测试报告路径和测试命令依赖
- **AND** 缺少测试事实 MUST NOT 导致依赖分析失败

### Requirement: 依赖分析必须排除本次构建生成共享库的内部依赖
依赖分析运行时 SHALL 使用构建前后 ELF manifest 区分本次构建生成的共享库，并 MUST NOT 将这些共享库自身或其他本次构建产物对这些共享库的内部链接关系保存为外部依赖分析结果。

#### Scenario: 本次构建生成共享库的 SONAME 不进入依赖结果
- **WHEN** 构建事实包含 `RVPORT_OBSERVATION buildProducedElf build/libfoo.so.1.2.3`
- **AND** 同一路径包含 `RVPORT_OBSERVATION elfSoname build/libfoo.so.1.2.3	libfoo.so.1`
- **THEN** 依赖分析 MUST NOT 生成 `libfoo.so.1` 或 `libfoo.so` 的外部依赖结果项
- **AND** 依赖分析 MAY 将该 SONAME 作为本次构建产物排除 evidence 使用

#### Scenario: 本次构建产物依赖本次构建共享库不进入依赖结果
- **WHEN** 构建事实包含本次构建生成共享库 `libfoo.so.1`
- **AND** 构建事实包含本次构建生成可执行文件 `build/tests/foo_test`
- **AND** `build/tests/foo_test` 的 `elfNeeded` 包含 `libfoo.so.1`
- **THEN** 依赖分析 MUST NOT 将 `libfoo.so.1` 保存为外部运行依赖
- **AND** 结果列表、详情、HTML artifact 和 JSON artifact MUST NOT 展示该库为缺失依赖

#### Scenario: 本次构建产物的外部系统库依赖仍需分析
- **WHEN** 本次构建生成可执行文件 `build/tests/foo_test`
- **AND** `build/tests/foo_test` 的 `elfNeeded` 包含 `libc.so.6`、`libm.so.6` 或 `libgcc_s.so.1`
- **AND** 这些库不是本次构建生成共享库的 SONAME 或归一化文件名
- **THEN** 依赖分析 SHALL 继续对这些外部系统库执行 source owner package 和 target provider package 匹配
- **AND** 依赖分析 SHALL 保留对应风险等级、版本关系和 evidence

#### Scenario: 仓库原本携带共享库仍需分析
- **WHEN** 构建事实包含 `RVPORT_OBSERVATION repositoryBundledElf vendor/libfoo.so`
- **AND** 该路径不属于 `buildProducedElf`
- **THEN** 依赖分析 SHALL 将该仓库原有共享库作为依赖候选分析
- **AND** 后端 MUST NOT 仅凭路径包含 `build`、`vendor` 或 `.so` 后缀就跳过分析

#### Scenario: 构建产物标记缺失时保持降级行为
- **WHEN** 构建事实没有 `buildProducedElf` 信息
- **AND** 存在 `elfNeeded` 或 `elfSoname` 观测
- **THEN** 依赖分析 SHALL 按现有外部依赖匹配逻辑处理
- **AND** 依赖分析 MUST NOT 因缺少构建产物 manifest 而失败

### Requirement: 依赖分析必须使用 compile database 辅助识别本次构建产物
依赖分析运行时 SHALL 在可用时使用同一源码版本、同一 source/x86 成功构建产生的 `compile_commands.json` 建立源码到 object 的构建关系，并 SHALL 将该关系作为识别本次构建生成 ELF/SO 的辅助证据。

#### Scenario: 从 compile database 建立 object 索引
- **WHEN** 依赖分析输入包含可用 `compile_commands.json` 构建上下文 artifact
- **THEN** worker SHALL 解析其中的 `file`、`directory`、`command` 或 `arguments`
- **AND** worker SHALL 从编译命令中提取 `-o` object 输出路径
- **AND** worker SHALL 建立项目源码相对路径到 object 相对路径的内部索引
- **AND** worker MUST NOT 将完整 `compile_commands.json`、完整编译命令或远端绝对路径写入依赖分析结果

#### Scenario: compile database 辅助过滤项目自产共享库
- **WHEN** `compile_commands.json` 证明项目源码被编译为 object 文件
- **AND** 构建日志、strace、build graph 或 ELF 事实证明这些 object 参与生成 `libfoo.so` 或 `libfoo.so.1`
- **THEN** 依赖分析 SHALL 将该共享库标记为本次构建产物
- **AND** 依赖分析 SHALL 过滤该共享库自身的 `elfSoname` 结果
- **AND** 依赖分析 SHALL 过滤其他本次构建产物对该共享库的内部 `elfNeeded` 结果
- **AND** evidence SHALL 记录 compile database 参与了构建产物归因

#### Scenario: 保留自产产物对外部库的依赖
- **WHEN** 本次构建生成的 ELF 或 SO 依赖 `libc.so.6`、`libgcc_s.so.1` 或其他外部系统库
- **THEN** 依赖分析 SHALL 继续生成对应外部库依赖结果
- **AND** 依赖分析 MUST NOT 因该引用发生在本次构建产物内就过滤外部依赖

#### Scenario: compile database 缺失或无效时降级
- **WHEN** `compile_commands.json` artifact 不存在、读取失败、格式非法或超过受控解析限制
- **THEN** 依赖分析 SHALL 继续使用 `buildProducedElf`、`repositoryBundledElf`、构建日志、strace 和 ELF metadata 执行现有分析
- **AND** 依赖分析 MUST NOT 因 compile database 不可用而失败
- **AND** 依赖分析 SHALL 记录安全 warning 或内部 evidence 摘要

#### Scenario: 不得仅凭 compile database 过滤 ELF/SO
- **WHEN** 某个 `.so` 或 ELF 文件没有 `buildProducedElf`、明确链接日志、strace linker 输出、build graph 或等效产物证据
- **AND** 仅能观察到同一目录附近存在 compile database object 输出
- **THEN** 依赖分析 MUST NOT 将该 `.so` 或 ELF 判定为本次构建产物
- **AND** 该文件 SHALL 按现有 repository bundled 或 unknown 规则继续分析

#### Scenario: 仓库原本携带的 prebuilt ELF/SO 不被误过滤
- **WHEN** 构建前 manifest 已经包含 `vendor/libbar.so` 或 `build/prebuilt/libbar.so`
- **AND** 构建后该文件没有强覆盖证据
- **THEN** 依赖分析 SHALL 保留该 ELF/SO 作为依赖候选
- **AND** 依赖分析 MUST NOT 因文件路径位于 `build/`、`out/` 或 compile database 工作目录下而过滤该文件

#### Scenario: 构建前已存在但被本次覆盖的产物
- **WHEN** 构建前 manifest 已经包含某个 ELF/SO
- **AND** 构建后 manifest、hash、mtime、strace 或链接日志能证明该路径被本次构建重新生成
- **THEN** 依赖分析 MAY 将该文件标记为本次构建产物
- **AND** evidence SHALL 记录覆盖判断来源
- **AND** 如果覆盖证据不足，依赖分析 SHALL 保持保守并继续按仓库原有 ELF/SO 分析

### Requirement: 依赖分析必须查询 RISC-V 目标能力
依赖分析 worker SHALL 查询 RISC-V 目标环境已安装能力和仓库能力，用于判断 x86 构建事实中提取的依赖是否可满足，并 SHALL 对 ELF/SO provider package 执行源目标版本匹配。

#### Scenario: 查询目标已安装能力
- **WHEN** worker 执行依赖分析
- **THEN** worker SHALL 查询目标环境已安装包、工具、库文件、pkg-config module 和 header provider
- **AND** 查询结果 MUST NOT 暴露明文凭据或远端敏感路径

#### Scenario: 查询目标仓库能力
- **WHEN** 目标环境支持包管理器仓库查询
- **THEN** worker SHALL 刷新或读取仓库 metadata
- **AND** worker SHALL 查询 package search、provides/whatprovides、pkg-config provider 和 header provider
- **AND** 查询失败 SHALL 记录 warning 和 evidence，不得直接解释为依赖缺失

#### Scenario: 匹配源目标 ELF/SO provider package 版本
- **WHEN** source/x86 构建事实包含 ELF、SO、`NEEDED` 或 `SONAME` 依赖
- **THEN** worker SHALL 优先使用源环境包数据库解析这些文件或 SONAME 的 source owner package 和 source owner version
- **AND** worker SHALL 使用目标环境已安装包、目标软件源包和 provides/whatprovides 查询对应 target provider package 和 target provider version
- **AND** 匹配结果 SHALL 保存源 provider、目标 provider、版本关系、风险等级和 evidence

#### Scenario: 目标能力证据不足
- **WHEN** 目标环境查询失败或证据不完整
- **THEN** 对应依赖结果 SHALL 标记为 `MANUAL_CONFIRM_REQUIRED` 或等效证据不足状态
- **AND** 结果 SHALL 包含可审计 warning 摘要

### Requirement: 链接库依赖必须使用 provider-aware 匹配
依赖分析 SHALL 对构建事实中发现的链接库使用 provider-aware 匹配，并 SHALL 在目标库文件证据缺失或缺少 provider version 时使用源侧 owner package 回退到目标 provider package。

#### Scenario: compile commands 链接库使用 provider 匹配
- **WHEN** 依赖分析从 `compile_commands.json` 中解析出 `-l<name>` 链接库
- **THEN** 后端 SHALL 将其规范化为 `lib<name>.so`
- **AND** 后端 SHALL 查询源环境中该库或相关路径的 owner package 证据
- **AND** 后端 SHALL 使用源侧 owner package、目标侧库文件、目标侧 repository provide 和目标侧 package 清单共同决定匹配结果

#### Scenario: strace 链接库使用 provider 匹配
- **WHEN** 依赖分析从 strace execve 观测中解析出 linker 参数 `-l<name>`
- **THEN** 后端 SHALL 将其规范化为 `lib<name>.so`
- **AND** 后端 SHALL 使用 provider-aware 匹配
- **AND** 后端 MUST NOT 仅因目标环境缺少 `INSTALLED_LIBRARY` 组件就判定该链接库缺失

#### Scenario: linker flag 链接库使用 provider 匹配
- **WHEN** 依赖分析从构建日志或 linker flag 中解析出 `-l<name>`
- **THEN** 后端 SHALL 使用与 compile commands 和 strace 相同的 provider-aware 匹配语义
- **AND** 结果字段 SHALL 包含可用的 source provider package/version、target provider package/version 和 provider version status

#### Scenario: 目标库存在但缺少 provider version 时继续 fallback
- **WHEN** 目标环境采集到了 `libc.so.6`、`libgcc_s.so.1` 或其他 `.so` 文件
- **AND** 该库组件没有可确认的 owner package/version
- **AND** 源环境已确认 source owner package
- **THEN** 后端 SHALL 继续使用 source owner package 在目标已安装包和目标软件源中查找同名 provider package
- **AND** 后端 MUST NOT 因目标库文件存在但版本为空就输出 `NO_TARGET_VERSION_FOUND`

### Requirement: 基础库别名必须参与目标 provider 匹配
依赖分析 SHALL 将基础 C/C++ 运行时库别名纳入目标 provider 匹配，避免常见链接名与包名不一致导致误判。

#### Scenario: libc 链接名映射 glibc
- **WHEN** 依赖分析处理 `libc.so` 或 `libc.so.6`
- **THEN** 目标 provider 匹配 SHALL 尝试 `glibc`
- **AND** 命中后 SHALL 返回目标 provider package/version

#### Scenario: libgcc_s 链接名映射 gcc 和 libgcc
- **WHEN** 依赖分析处理 `libgcc_s.so` 或 `libgcc_s.so.1`
- **THEN** 目标 provider 匹配 SHALL 尝试 `gcc` 和 `libgcc`
- **AND** 命中后 SHALL 返回目标 provider package/version

### Requirement: 依赖分析必须解析动态库实际源路径
依赖分析运行时 SHALL 对 ELF `NEEDED` 和 `SONAME` 中出现的动态库名解析 source 环境实际库文件路径，并 SHALL 使用该路径查询 source owner package/version。

#### Scenario: 解析 libc 这类 NEEDED 动态库
- **WHEN** x86 构建事实中存在 `NEEDED` 动态库 `libc.so.6`
- **THEN** 构建事实采集 SHALL 尝试在 source 环境中解析 `libc.so.6` 的实际库文件路径
- **AND** 解析成功时 SHALL 记录实际库路径、resolver 来源、source owner package 和 source owner version
- **AND** 依赖分析结果 SHALL 使用该 owner package/version 作为 source provider 证据

#### Scenario: 动态库路径解析失败时降级
- **WHEN** `ldconfig`、安全路径扫描或等效 resolver 无法解析某个 `NEEDED` 动态库
- **THEN** 依赖分析 SHALL 保留当前 SONAME 匹配逻辑
- **AND** 结果 evidence SHALL 记录解析失败 warning
- **AND** 构建验证或依赖分析 MUST NOT 仅因单个动态库路径解析失败而失败

#### Scenario: 多候选动态库路径
- **WHEN** 同一 SONAME 在 source 环境中解析到多个候选路径
- **THEN** 后端 SHALL 优先选择实际引用 ELF 可确认的路径
- **AND** 无法确认唯一实际路径时 SHALL 保留候选摘要并降级为人工确认或低置信度 evidence
- **AND** 后端 MUST NOT 随机选择一个候选并标记为高置信度

### Requirement: 依赖分析必须支持同包生态 provider fallback
依赖分析运行时 SHALL 在默认 source 与 target 属于同一发行版包生态的前提下，当 target SO/provides 匹配失败但 source owner package 已确认时，使用 source owner package 对目标已安装包和目标软件源包进行 fallback 匹配。

#### Scenario: 目标 provides 缺失但同名包存在
- **WHEN** source 环境已确认动态库 owner package 为 `glibc`
- **AND** target 环境无法通过 SO/provides 定位该动态库 provider
- **AND** target 已安装包或目标软件源中存在同名 package `glibc`
- **THEN** 依赖分析 SHALL 使用 `glibc` 作为 target provider package
- **AND** evidence SHALL 标记 `matchedBy=source-owner-package-fallback`
- **AND** evidence SHALL 标记 `samePackageEcosystemAssumed=true`

#### Scenario: fallback 命中后继续比较大版本
- **WHEN** source owner package 和 target fallback package 都存在可解析版本
- **THEN** 依赖分析 SHALL 继续使用现有 major version 关系计算 `providerVersionStatus`
- **AND** 同大版本 SHALL 返回 `SAME_MAJOR` 和 `INFO`
- **AND** target 版本高于 source SHALL 返回 `TARGET_NEWER` 和 `LOW`
- **AND** target 版本低于 source SHALL 返回 `TARGET_OLDER` 和 `HIGH`

#### Scenario: fallback 命中但目标版本不可确认
- **WHEN** target fallback package 命中但目标 provider version 不可确认
- **THEN** 依赖分析 SHALL 返回证据不足状态或 `NO_TARGET_VERSION_FOUND`
- **AND** 后端 MUST NOT 因包名相同就伪造目标版本或低风险结论

#### Scenario: 不进行跨发行版 alias 推断
- **WHEN** source owner package 名称与 target 包生态中的等价包名不同
- **THEN** 本能力 MUST NOT 自动推断跨发行版 alias
- **AND** 后端 MUST NOT 将 `glibc` 自动等价为 `libc6`
- **AND** 该场景 SHALL 保持缺失、证据不足或人工确认状态，除非未来单独引入跨发行版 alias 能力

### Requirement: 依赖分析必须逐条持久化结果
依赖分析运行时 SHALL 将每个依赖判断结果逐条写入数据库，支持分页、过滤、排序、迁移输入导入和报告生成，并 SHALL 对 ELF/SO provider package 结果保存版本匹配证据。

#### Scenario: 保存依赖分析结果
- **WHEN** 依赖分析 worker 完成匹配
- **THEN** 后端 SHALL 写入依赖分析结果明细
- **AND** 每条结果 SHALL 关联 `projectId`、`sourceRevisionId`、`taskId` 和依赖分析运行 ID
- **AND** 每条结果 SHALL 保存依赖类型、名称、版本、目标匹配状态、风险等级、位置标签、建议、证据摘要和创建时间
- **AND** ELF/SO provider package 结果 SHALL 在 evidence 中保存 `sourceLibraryPath`、`sourceLibraryName`、`sourceSoname`、`sourceOwnerPackage`、`sourceOwnerVersion`、`sourceVersionEvidence`、`targetProviderPackage`、`targetProviderVersion`、`targetProviderSource`、`versionRelation`、`riskMappingRule` 和 `matchEvidence`
- **AND** 后端 MUST NOT 将完整结果数组整体塞入单个 JSON 字段

#### Scenario: 查询结果列表
- **WHEN** 用户调用依赖分析结果列表 API
- **THEN** 后端 SHALL 返回分页列表
- **AND** 列表 SHALL 支持按依赖类型、目标匹配状态、风险等级、关键字和创建时间过滤
- **AND** 列表 SHALL 支持稳定排序白名单
- **AND** 列表 MUST NOT 返回 compiler flag、宏或构建元数据文件本身的结果项

#### Scenario: 查询结果详情
- **WHEN** 用户查询单条依赖分析结果详情
- **THEN** 后端 SHALL 校验结果所属项目访问权限
- **AND** 响应 SHALL 返回结构化 evidence、source 构建事实摘要、目标候选摘要和建议
- **AND** 响应 MUST NOT 返回明文凭据、完整构建日志、完整脚本或服务器真实路径

### Requirement: 依赖分析必须生成受控制品
依赖分析运行时 SHALL 生成 JSON 和 HTML 形式的依赖分析报告或摘要制品，并 SHALL 使用对象存储制品能力保存。

#### Scenario: 生成依赖分析制品
- **WHEN** 依赖分析任务成功完成
- **THEN** 后端 SHALL 生成依赖分析 JSON 摘要制品
- **AND** 后端 SHALL 生成可展示 HTML 报告制品
- **AND** 制品元数据 SHALL 关联项目、源码版本、任务和依赖分析运行
- **AND** API 响应 MUST NOT 暴露 bucket、object key 或服务器真实路径

#### Scenario: 下载依赖分析制品
- **WHEN** 用户下载依赖分析 HTML 或 JSON 制品
- **THEN** 后端 SHALL 校验项目访问权限
- **AND** 后端 SHALL 通过对象存储读取制品内容
- **AND** 不支持的格式 SHALL 返回明确业务错误

### Requirement: 依赖分析结果必须可加入迁移输入
依赖分析结果 SHALL 能作为迁移输入 reference 的来源，并 SHALL 绑定到当前源码版本。

#### Scenario: 导入依赖分析结果
- **WHEN** 用户将依赖分析结果加入迁移输入
- **THEN** 后端 SHALL 使用 `sourceType=DEPENDENCY_RESULT`
- **AND** `sourceRefId` SHALL 指向依赖分析结果明细 ID
- **AND** 创建出的迁移输入 SHALL 绑定到 URL 中的 `sourceRevisionId`

#### Scenario: 生成迁移输入展示字段
- **WHEN** 后端为依赖分析结果创建迁移输入 reference
- **THEN** `detectedLevel` SHALL 使用 `riskLevel + "/" + targetMatchStatus`
- **AND** `locationLabel` SHALL 使用 `build:<tool>`、`package:<name@version>`、`lib:<soname-or-path>` 或 `test:<tool>` 等稳定格式
- **AND** 建议处理 SHALL 来自依赖分析结果的 suggestion

### Requirement: 依赖分析必须结构化展示 provider package 版本
依赖分析运行时 SHALL 为可解析的构建工具、ELF 和 SO 依赖保存并返回源端 provider package/version、目标端 provider package/version、detected version、provider version status 和版本证据置信度。

#### Scenario: 保存源目标 provider package 版本
- **WHEN** 依赖分析 worker 从构建事实中识别到构建工具、ELF 或 SO 依赖
- **THEN** worker SHALL 优先使用源环境包管理器 owner 查询获取 `sourceProviderPackage` 和 `sourceProviderVersion`
- **AND** worker SHALL 使用目标环境已安装包、目标软件源、provides 或 whatprovides 查询获取 `targetProviderPackage` 和 `targetProviderVersion`
- **AND** worker SHALL 将上述字段保存为结果明细的结构化字段或稳定 DTO 字段
- **AND** worker SHALL 在 evidence 中保留 owner 查询命令、匹配来源、版本解析方式和脱敏后的摘要

#### Scenario: ELF 和 SO 使用低风险版本探测
- **WHEN** 依赖项是 ELF 或 SO 文件
- **THEN** worker MUST NOT 默认对该文件执行 `--version`
- **AND** worker SHALL 优先使用 `rpm -qf`、`dpkg -S` 或等效包管理器 owner 查询获取包版本
- **AND** owner 查询失败时，worker MAY 使用 SONAME、文件名、symlink、`readelf`、`objdump` 或 `strings` 提取 `sourceDetectedVersion` 或 `targetDetectedVersion`
- **AND** `strings` 结果 MUST 标记为低置信度 evidence，不能作为高置信度 provider package version

#### Scenario: 工具命令补充 detected version
- **WHEN** 依赖项是构建工具或命令行工具
- **THEN** worker SHALL 优先使用包管理器 owner 查询获取 provider package version
- **AND** worker MAY 对白名单工具执行有限时、限长、脱敏的版本命令以获取 `sourceDetectedVersion` 或 `targetDetectedVersion`
- **AND** 版本命令失败 MUST NOT 导致依赖分析任务失败

#### Scenario: 计算 provider 版本状态
- **WHEN** 源端和目标端 provider 版本证据可用
- **THEN** worker SHALL 计算 `providerVersionStatus`
- **AND** 源有包、目标有包且大版本一致 SHALL 标记为 `SAME_MAJOR` 并映射为 `INFO`
- **AND** 源有包、目标有包且目标版本高于源版本 SHALL 标记为 `TARGET_NEWER` 并映射为 `LOW`
- **AND** 源有包、目标有包且目标版本低于源版本 SHALL 标记为 `TARGET_OLDER` 并映射为 `HIGH`
- **AND** 源无 owner package 但存在 detected version 且目标有 provider package version SHALL 标记为 `TARGET_PACKAGE_AVAILABLE_SOURCE_UNOWNED` 并映射为 `MEDIUM`
- **AND** 源无 owner package 且目标无可确认版本 SHALL 标记为 `NO_TARGET_VERSION_FOUND` 并映射为 `CRITICAL`
- **AND** 证据不足或不适用时 SHALL 标记为 `UNKNOWN` 并保留 warning

#### Scenario: API 和制品展示 provider 版本
- **WHEN** 用户查询依赖分析列表、结果详情、JSON artifact、HTML artifact 或真实验证 HTML
- **THEN** 响应或制品 SHALL 展示源 provider package/version、源 detected version、目标 provider package/version、目标 detected version、provider version status、目标匹配状态和风险等级
- **AND** 响应或制品 MUST NOT 要求前端从 nested evidence 中推断这些主要字段
- **AND** 响应或制品 MUST NOT 暴露服务器真实路径、SSH 凭据、私钥、token 或完整远端日志

#### Scenario: provider 信息缺失时明确展示
- **WHEN** 源端或目标端 provider package/version 无法确认
- **THEN** API 和制品 SHALL 返回明确的空值、`UNKNOWN` 状态或 warning 摘要
- **AND** 后端 MUST NOT 将缺失字段伪装为已确认版本
- **AND** 后端 MUST NOT 因单条依赖缺少 provider version 导致整个依赖分析失败

### Requirement: 依赖分析执行前必须刷新环境快照
依赖分析运行时 SHALL 在每次执行分析前刷新当前 source/x86 和 target/RISC-V 环境采集结果，并 SHALL 使用本次刷新产生的成功快照进行依赖匹配。

#### Scenario: 分析前刷新 source 和 target 环境
- **WHEN** `DEPENDENCY_ANALYSIS` worker 开始处理依赖分析任务
- **THEN** worker SHALL 对 source/x86 构建环境执行环境采集
- **AND** worker SHALL 对 target/RISC-V 目标环境执行环境采集
- **AND** worker SHALL 使用本次采集产生的 source snapshot 和 target snapshot 读取环境组件
- **AND** worker MUST NOT 默认使用任务开始前已存在的旧成功快照进行依赖匹配

#### Scenario: 目标环境删除软件后重新分析
- **WHEN** target/RISC-V 环境在旧快照后删除了某个已安装工具或软件包
- **AND** 用户重新创建依赖分析任务
- **THEN** worker SHALL 重新采集 target/RISC-V 环境
- **AND** 依赖结果 MUST NOT 根据旧快照返回该工具或软件包为 `TARGET_INSTALLED`
- **AND** 如果目标软件源仍提供该工具或软件包，依赖结果 SHALL 返回 `REPO_AVAILABLE` 或等效软件源可用状态

#### Scenario: source 环境采集失败
- **WHEN** source/x86 环境采集失败、超时或返回不可解析结果
- **THEN** 依赖分析任务 SHALL 失败
- **AND** 依赖分析运行 SHALL 标记为 `FAILED`
- **AND** 错误摘要 SHALL 标明 source 环境刷新失败
- **AND** 后端 MUST NOT 使用旧 source snapshot 继续分析

#### Scenario: target 环境采集失败
- **WHEN** target/RISC-V 环境采集失败、超时或返回不可解析结果
- **THEN** 依赖分析任务 SHALL 失败
- **AND** 依赖分析运行 SHALL 标记为 `FAILED`
- **AND** 错误摘要 SHALL 标明 target 环境刷新失败
- **AND** 后端 MUST NOT 使用旧 target snapshot 继续分析

#### Scenario: 记录本次刷新快照
- **WHEN** 依赖分析任务成功完成
- **THEN** 依赖分析 JSON artifact SHALL 包含本次使用的 `sourceSnapshotId` 和 `targetSnapshotId`
- **AND** artifact SHALL 包含对应的 collected time 或等效采集时间
- **AND** 任务日志或运行 summary SHALL 能审计本次环境刷新已启用

### Requirement: 依赖分析必须基于 CMake 检测调用识别 compiler flag probe
依赖分析运行时 SHALL 使用 CMake 脚本中的 compiler flag 检测调用建立 define 变量和真实 compiler flag 的动态映射，并 SHALL 基于该映射识别 compiler flag probe。

#### Scenario: 解析 CMake C 编译器参数检测
- **WHEN** 源码树或构建目录中的 CMake 文件包含 `check_c_compiler_flag(<flag> <variable>)`
- **THEN** 构建事实采集 SHALL 输出 `<variable>` 到 `<flag>` 的映射
- **AND** 映射 SHALL 标记语言为 `C`
- **AND** 映射 SHALL 记录来源 CMake 文件的受控相对路径

#### Scenario: 解析 CMake CXX 编译器参数检测
- **WHEN** 源码树或构建目录中的 CMake 文件包含 `check_cxx_compiler_flag(<flag> <variable>)`
- **THEN** 构建事实采集 SHALL 输出 `<variable>` 到 `<flag>` 的映射
- **AND** 映射 SHALL 标记语言为 `CXX`
- **AND** 映射 SHALL 支持跨行函数调用

#### Scenario: 解析通用 CMake 编译器参数检测
- **WHEN** CMake 文件包含 `check_compiler_flag(<language> <flag> <variable>)`
- **THEN** 构建事实采集 SHALL 输出 `<variable>`、`<flag>` 和 `<language>` 的映射
- **AND** 无法静态解析的参数 SHALL 记录为低置信度或 unresolved evidence

#### Scenario: 多层级 CMake 脚本扫描
- **WHEN** 项目包含根目录、子目录或 `cmake/` 目录下的 `CMakeLists.txt` 或 `*.cmake`
- **THEN** 构建事实采集 SHALL 扫描这些 CMake 文件
- **AND** 扫描 SHALL 排除 `.git` 和明显无关的缓存目录
- **AND** 子目录 CMake 文件中的 compiler flag probe MUST NOT 被根目录扫描遗漏

#### Scenario: 使用动态映射解释 define
- **WHEN** `compile_commands.json` 或 `strace execve` 中出现 `-D<variable>`
- **AND** `<variable>` 命中 CMake compiler flag probe 映射
- **THEN** 依赖分析结果 SHALL 将该 define 标记为 `compiler_flag_probe`
- **AND** evidence SHALL 包含真实 `compilerFlag`、`probeSource=cmake_check_compiler_flag`、`probeFile` 和 `probeLanguage`
- **AND** 结果 SHALL 使用 `TARGET_INSTALLED` 和 `LOW` 风险表达该项通常属于当前编译器能力

#### Scenario: 不以 FLAG_SUPPORTED 前缀作为强判定
- **WHEN** `compile_commands.json` 或 `strace execve` 中出现以 `FLAG_SUPPORTED_` 开头的 define
- **AND** 该 define 未命中 CMake compiler flag probe 映射
- **THEN** 依赖分析 MUST NOT 仅凭前缀将其标记为 `compiler_flag_probe`
- **AND** 该 define SHALL 按编译器内置宏采集或项目/构建自定义宏规则继续分类

#### Scenario: CMake probe 映射冲突
- **WHEN** 同一 CMake 变量名映射到多个不同 compiler flag
- **THEN** 依赖分析 SHALL 将该变量对应结果降级为 `MANUAL_CONFIRM_REQUIRED`
- **AND** evidence SHALL 保存冲突的候选 flag 和来源文件

### Requirement: ELF/SO provider package 风险必须按版本关系映射
依赖分析 SHALL 对 ELF/SO provider package 匹配结果使用固定版本关系和风险等级映射，避免用模糊置信度替代实际替代风险。

#### Scenario: 源目标 provider 大版本一致
- **WHEN** 源环境存在 source owner package 和版本
- **AND** 目标环境或目标软件源存在对应 target provider package 和版本
- **AND** 源目标 package 大版本一致
- **THEN** 结果 SHALL 使用 `versionRelation=SAME_MAJOR`
- **AND** `riskLevel` SHALL 为 `INFO`

#### Scenario: 目标 provider 版本高于源 provider
- **WHEN** 源环境存在 source owner package 和版本
- **AND** 目标环境或目标软件源存在对应 target provider package 和版本
- **AND** 目标 package 版本高于源 package 版本
- **THEN** 结果 SHALL 使用 `versionRelation=TARGET_NEWER`
- **AND** `riskLevel` SHALL 为 `LOW`

#### Scenario: 目标 provider 版本低于源 provider
- **WHEN** 源环境存在 source owner package 和版本
- **AND** 目标环境或目标软件源存在对应 target provider package 和版本
- **AND** 目标 package 版本低于源 package 版本
- **THEN** 结果 SHALL 使用 `versionRelation=TARGET_OLDER`
- **AND** `riskLevel` SHALL 为 `HIGH`

#### Scenario: 源无 owner 但目标存在 provider
- **WHEN** 源环境无法解析 source owner package
- **AND** worker 能从源文件名、SONAME、readelf、symlink 或路径提取 source version evidence
- **AND** 目标环境或目标软件源存在对应 target provider package 和版本
- **THEN** 结果 SHALL 使用 `versionRelation=TARGET_PACKAGE_AVAILABLE_SOURCE_UNOWNED`
- **AND** `riskLevel` SHALL 为 `MEDIUM`

#### Scenario: 源无 owner 且目标无可确认版本
- **WHEN** 源环境无法解析 source owner package
- **AND** worker 无法确认目标环境或目标软件源存在对应版本
- **THEN** 结果 SHALL 使用 `versionRelation=NO_TARGET_VERSION_FOUND`
- **AND** `riskLevel` SHALL 为 `CRITICAL`

### Requirement: ELF/SO provider package 结果必须去重聚合
依赖分析 SHALL 避免把同一个软件包因多个库文件命中重复展示成大量相同风险项。

#### Scenario: 源 provider package 多个 SO 命中同一目标 provider
- **WHEN** 多个源 ELF/SO 具有相同 source owner package、source owner major version、target provider package、target provider major version 和 version relation
- **THEN** 列表结果 SHOULD 聚合为同一条 provider package 结果
- **AND** 详情 evidence SHALL 保留所有命中的源路径、SONAME 和 target provides 证据

#### Scenario: 源无 owner 的库文件聚合
- **WHEN** 多个源无 owner 的 ELF/SO 具有相同 library stem、source version evidence、target provider package、target provider major version 和 version relation
- **THEN** 列表结果 SHOULD 聚合为同一条结果
- **AND** 详情 evidence SHALL 保留所有命中的源路径和目标 provider 证据

### Requirement: 依赖分析节点必须支持本节点确认进入报告
依赖分析运行时 SHALL 支持在依赖分析节点内将依赖分析结果加入、撤回或确认为报告输入。

#### Scenario: 依赖结果默认确认
- **WHEN** 依赖分析任务成功写入结果项
- **AND** 结果 riskLevel 为 `CRITICAL`、`HIGH`、`MEDIUM`、`INFO` 或 `COMMENT`
- **THEN** 后端 SHALL 创建或维护对应 `DEPENDENCY_RESULT` migration input reference
- **AND** reference 状态 SHALL 默认为 `CONFIRMED`

#### Scenario: 依赖结果低风险默认候选
- **WHEN** 依赖分析任务成功写入结果项
- **AND** 结果 riskLevel 为 `LOW`
- **THEN** 后端 SHALL 创建或维护对应 `DEPENDENCY_RESULT` migration input reference
- **AND** reference 状态 SHALL 默认为 `CANDIDATE`

#### Scenario: 依赖节点人工撤回
- **WHEN** 用户在依赖分析节点撤回某个结果项
- **THEN** 后端 SHALL 将对应 migration input reference 状态更新为 `EXCLUDED`
- **AND** 报告生成 MUST NOT 使用该依赖结果

#### Scenario: 依赖节点人工加入低风险
- **WHEN** 用户在依赖分析节点人工加入 `LOW` 结果项
- **THEN** 后端 SHALL 将对应 migration input reference 状态更新为 `CONFIRMED`
- **AND** 报告生成 SHALL 可以使用该依赖结果

#### Scenario: SBOM 不再作为依赖前序
- **WHEN** 依赖分析结果进入迁移输入流程
- **THEN** 后端 SHALL 使用 `sourceType=DEPENDENCY_RESULT`
- **AND** 后端 MUST NOT 同时创建 `SBOM_RESULT` reference

### Requirement: 依赖分析列表必须默认收敛到当前上下文
依赖分析运行列表 API SHALL 默认返回当前 active source revision 和当前运行环境配置版本下的依赖分析记录，并 SHALL 支持显式查看历史记录。

#### Scenario: 默认查询当前依赖分析记录
- **WHEN** 用户调用 `GET /api/projects/{projectId}/dependency-analyses` 且未指定 `sourceRevisionId`、`environmentConfigVersion` 或 `includeStale`
- **THEN** 后端 SHALL 使用项目 active source revision 作为默认源码版本
- **AND** 后端 SHALL 使用当前项目运行环境配置版本作为默认环境版本
- **AND** 响应 MUST NOT 混入旧源码版本或旧运行环境配置版本的依赖分析运行

#### Scenario: 显式查看历史依赖分析记录
- **WHEN** 用户调用依赖分析列表 API 并显式提交历史 `sourceRevisionId`、历史 `environmentConfigVersion` 或 `includeStale=true`
- **THEN** 后端 SHALL 按请求参数返回匹配历史记录
- **AND** 响应 SHALL 标明每条记录是否属于当前源码版本和当前运行环境配置版本

### Requirement: 依赖分析结果必须返回报告纳入状态
依赖分析结果列表和详情 SHALL 返回该结果与当前报告输入池、最新评估报告之间的关系。

#### Scenario: 依赖结果已确认进入下一次报告
- **WHEN** 依赖分析结果存在同一源码版本下的 `sourceType=DEPENDENCY_RESULT` migration input reference
- **AND** reference 状态为 `CONFIRMED`
- **THEN** 依赖结果响应 SHALL 返回 `confirmedForNextReport=true`
- **AND** 响应 SHALL 返回 `migrationInputRefId` 和 `migrationInputStatus`

#### Scenario: 依赖结果已纳入最新报告
- **WHEN** 当前源码版本存在最新成功评估报告
- **AND** 该报告的 `report_inputs` 包含对应 migration input reference
- **THEN** 依赖结果响应 SHALL 返回 `includedInLatestReport=true`
- **AND** 响应 SHALL 返回 `latestReportId`

#### Scenario: 依赖结果未纳入报告
- **WHEN** 依赖分析结果没有对应 migration input reference 或 reference 未进入最新报告快照
- **THEN** 依赖结果响应 SHALL 返回明确的 `confirmedForNextReport=false` 或 `includedInLatestReport=false`
- **AND** 后端 MUST NOT 要求前端自行联表推断该状态

### Requirement: 依赖分析结果必须支持自动修复候选派生
依赖分析运行时 SHALL 为依赖自动修复提供稳定的结果字段和候选派生规则，使后端可以基于分析结果判断哪些依赖可以尝试在目标环境安装。

#### Scenario: 派生中风险自动修复候选
- **WHEN** 依赖分析结果 `riskLevel=MEDIUM`
- **AND** 结果存在 `targetProviderPackage`
- **THEN** 该结果 SHALL 可作为自动修复候选项
- **AND** 候选项 SHALL 使用目标侧 provider package/version 作为优先安装依据

#### Scenario: 派生高风险自动修复候选
- **WHEN** 依赖分析结果 `riskLevel=HIGH`
- **AND** 结果存在 `sourceProviderPackage`
- **THEN** 该结果 SHALL 可作为自动修复候选项展示
- **AND** 候选项 SHALL 默认不选中
- **AND** 候选项 SHALL 使用源侧 package name 和 source major version 作为目标源匹配依据

#### Scenario: 保持依赖分析结果只读
- **WHEN** 用户创建依赖自动修复任务
- **THEN** 后端 MUST NOT 修改原依赖分析结果项的 `riskLevel`、`targetMatchStatus`、provider 字段或 evidence
- **AND** 自动修复的安装计划和执行结果 SHALL 写入自动修复运行表和条目表

#### Scenario: 修复后重新分析生成新结果
- **WHEN** 依赖自动修复完成后触发新的依赖分析
- **THEN** 新依赖分析 SHALL 创建独立的依赖分析运行和结果项
- **AND** 原依赖分析运行和结果项 SHALL 作为历史记录保留

### Requirement: 依赖分析必须提供 latest
依赖分析领域 SHALL 提供当前 Context 下的 latest 接口，返回最新依赖分析执行状态和结果摘要。

#### Scenario: 查询依赖分析 latest
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/dependency-analyses/latest`
- **THEN** 后端 SHALL 返回统一 latest 外壳
- **AND** `execution` SHALL 指向当前源码版本和当前运行环境版本下最新依赖分析任务
- **AND** `result` SHALL 包含结果总数、风险统计、目标匹配统计、报告 artifact 和可修复候选摘要

#### Scenario: 依赖分析 latest 识别旧环境
- **WHEN** 只有旧运行环境版本的依赖分析记录存在
- **THEN** 后端 MUST NOT 将该记录标记为 `CURRENT`
- **AND** latest SHALL 返回 `freshness=STALE` 或 `freshness=EMPTY`

### Requirement: 依赖分析 readiness 必须校验 x86 构建和目标环境
项目级 readiness 在 `stageId=rv-dependency-analysis` 时 SHALL 返回依赖分析所需前置条件。

#### Scenario: x86 构建已成功且目标环境存在
- **WHEN** 当前 Context 按活动优先、否则最近创建选中的 x86 source-only 构建已成功
- **AND** 项目已配置目标 RISC-V 运行环境
- **THEN** `X86_BUILD` 和 `TARGET_ENVIRONMENT` prerequisites SHALL 返回 `SATISFIED`

#### Scenario: x86 构建失败
- **WHEN** 当前 Context 下最近一次 x86 source-only 构建失败
- **THEN** `X86_BUILD` prerequisite SHALL 返回 `FAILED`
- **AND** `reasonCode` SHALL 为 `X86_BUILD_FAILED`

#### Scenario: x86 构建正在运行
- **WHEN** 当前 Context 下存在运行中的 x86 source-only 构建任务
- **THEN** `X86_BUILD` prerequisite SHALL 返回 `RUNNING`

#### Scenario: 目标环境不可用
- **WHEN** 项目配置了目标环境但环境被禁用或最近连接检查不可用
- **THEN** `TARGET_ENVIRONMENT` prerequisite SHALL 返回 `UNAVAILABLE`

#### Scenario: 源码分析放开不影响依赖门槛
- **WHEN** 当前只有源码分析计划或前置构建未成功
- **THEN** SHALL 拒绝依赖分析创建，无任务副作用，不消费内部解析投影、旧版本或更早成功构建冒充当前构建事实。

#### Scenario: 创建执行查询一致
- **WHEN** readiness 后构建或环境改变
- **THEN** SHALL 创建重新校验、入队固定引用、worker 复核真实依赖，并以同一 Context 规则供 latest 和报告选择，不回退历史满足条件。

### Requirement: C 和 C++ 编译器依赖必须按能力与 provider package 匹配
依赖分析 SHALL 将构建实际执行的工具名称、编译器能力和源 provider package 分别保存和匹配，并 MUST NOT 使用仅提供 C 编译能力的工具或软件包满足 C++ 编译器依赖。

#### Scenario: 目标仓库提供源侧 C++ provider package
- **WHEN** 构建证据观测到 `c++`，源环境确认其由 `gcc-c++` 提供，目标环境未安装 `c++` 或 `g++`，但目标仓库提供 `gcc-c++`
- **THEN** 结果 SHALL 保留 `dependencyName=c++`
- **AND** `sourceProviderPackage` 和 `targetProviderPackage` SHALL 为 `gcc-c++`
- **AND** `targetMatchStatus` SHALL 为 `REPO_AVAILABLE`
- **AND** `riskLevel` SHALL 为 `MEDIUM`

#### Scenario: 目标仅安装 GCC C 编译器
- **WHEN** 源构建需要 `c++` 或 `g++`，而目标环境仅安装 `gcc` 且目标仓库没有对应 C++ provider package
- **THEN** 依赖分析 MUST NOT 使用 `gcc` 将该依赖判定为 `TARGET_INSTALLED`
- **AND** 结果 SHALL 为 `MISSING/HIGH` 或基于快照可用性的等价未满足状态

#### Scenario: 目标已安装同家族 C++ 编译器
- **WHEN** 源侧 `c++` 的 provider 属于 GCC C++ 家族，且目标已安装 `c++`、`g++` 或相同 provider package
- **THEN** 结果 SHALL 为 `TARGET_INSTALLED`
- **AND** 匹配证据 SHALL 返回实际命中的工具或 provider package

#### Scenario: Clang 与 GCC 家族不得交叉误配
- **WHEN** 源侧 C++ 工具明确由 GCC 或 Clang 家族 provider 提供
- **THEN** 等价工具匹配 SHALL 限制在相同编译器家族
- **AND** 后端 MUST NOT 仅因另一家族的 C 或 C++ 命令存在而报告已满足

#### Scenario: 源 provider 信息缺失
- **WHEN** 构建观测到 C++ 编译工具但源环境无法确认 provider package
- **THEN** 后端 SHALL 优先匹配目标严格同名工具或可证明等价的 C++ 驱动
- **AND** 后端 MUST NOT 回退到裸 `gcc` 并报告已满足
- **AND** 匹配证据 SHALL 表达 provider 信息缺失和实际采用的回退依据

### Requirement: 所有构建工具证据必须共享统一目标匹配规则
compile database、execve summary、execve tool 和构建日志文本提取出的工具依赖 SHALL 使用同一套 provider-aware 目标匹配规则，并 SHALL 在去重后保留最高质量的真实使用证据。

#### Scenario: compile database 和 execve 同时观测到 c++
- **WHEN** 同一构建的 `compile_commands.json` 和 `strace execve` 都包含 `c++`
- **THEN** 结果 SHALL 只保留一个 `BUILD_TOOL c++` 主条目
- **AND** 条目 SHALL 使用高置信度真实执行证据及一致的 provider 匹配状态

#### Scenario: 宏采样包含未用于构建的 g++
- **WHEN** 环境宏采样产生 `compilerBuiltinMacros g++`，但构建命令、compile database 和 execve 均未使用 `g++`
- **THEN** 后端 MUST NOT 仅根据宏采样创建 `g++` 构建依赖

#### Scenario: 既有工具别名继续工作
- **WHEN** 构建使用 `cc`、binutils 工具、`ninja`、`rpmbuild` 或 `configure`
- **THEN** 统一匹配规则 SHALL 保持既有经过验证的 provider 或软件包别名行为
- **AND** 不同证据来源 MUST NOT 对同一工具返回互相矛盾的满足状态
