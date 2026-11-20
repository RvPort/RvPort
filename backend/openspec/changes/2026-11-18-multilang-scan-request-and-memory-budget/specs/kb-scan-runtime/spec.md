## ADDED Requirements

### Requirement: KB 扫描请求 SHALL 透传多语言与源架构

扫描请求 SHALL 接受逗号分隔的多语言字符串（与 KB Scanner `--language` 合同一致：`c,cpp,rust,go,java,python,asm` 的非空子集）与 `sourceArch`（`x86`/`arm64`；`x86_64`、`amd64`、`aarch64`、`arm` 等别名 SHALL 归一化为扫描器族名，未知值保守归一为 `x86`）。运行时命令 SHALL 追加 `--source-arch` 参数。缺省语言 `c,cpp` 与缺省源架构 `x86` 的既有行为与输出 MUST 保持不变；仅传语言的旧调用方式 MUST 继续可用（便捷构造器兼容）。

#### Scenario: 多语言混合扫描

- **WHEN** 请求携带 `language="c,rust"` 且 `sourceArch="arm64"`
- **THEN** 后端 SHALL 以 `--language c,rust --source-arch arm64` 调起扫描器
- **AND** 扫描器返回的各语言域 findings SHALL 正常导入

#### Scenario: 源架构别名归一化

- **WHEN** 请求携带 `sourceArch="aarch64"`
- **THEN** 后端 SHALL 归一化为 `arm64` 后透传

#### Scenario: 默认行为不变

- **WHEN** 请求不携带 language 与 sourceArch
- **THEN** 后端 SHALL 以既有默认 `c,cpp`/`x86` 执行，命令行与结果与改动前一致

### Requirement: 扫描子进程 SHALL 受内存预算约束

`RvPortProperties.KbScan` SHALL 定义 `memoryLimitMib`（缺省 2048；0 表示不设限）。`KbScanRuntimeService` SHALL 将 `KB_SCANNER_MEMORY_LIMIT_MIB` 注入扫描器子进程环境；扫描器据此设置自身 RLIMIT_AS，超限分配 SHALL 转换为 `pipeline.memory-budget-exceeded` FATAL 诊断（任务快速失败并保留诊断信息）而非无限换页。docker-compose worker SHALL 配置 `mem_limit` 与该环境变量，使失控扫描只影响 worker 容器本身。

#### Scenario: 默认限额生效

- **WHEN** 未配置 `memoryLimitMib` 且 worker 执行 KB_SCAN
- **THEN** 子进程环境 SHALL 含 `KB_SCANNER_MEMORY_LIMIT_MIB=2048`

#### Scenario: 显式关闭

- **WHEN** 配置 `memoryLimitMib=0`
- **THEN** 后端 SHALL 注入 `0`（扫描器解释为不设限），不阻断扫描

#### Scenario: 超限快速失败

- **WHEN** 扫描器分配超出限额
- **THEN** 任务 SHALL 以 FATAL 诊断失败并保留 `pipeline.memory-budget-exceeded` 错误码
- **AND** worker 容器与邻居服务 MUST NOT 因扫描进程 OOM 被连带终止
