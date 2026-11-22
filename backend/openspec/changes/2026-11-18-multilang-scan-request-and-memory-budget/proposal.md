## Why

kb-scanner 已支持多语言（c/rust/go/java/python/asm，opt-in）与 x86/arm64 双源架构，但后端扫描请求链路仅透传单语言字符串且无内存防线；同时生产环境发生过扫描进程内存耗尽导致 worker 容器被 OOM 连带、任务状态丢失的事故，且构建验证任务重试时因 `source_deploy_runs.task_id` 唯一约束直接失败，无法自动恢复。

## What Changes

- **多语言与源架构透传**：`KbScanRequest`/`KbScanInput` 增加多语言提交（逗号分隔字符串，与扫描器 `--language` 合同一致）与 `sourceArch`（`x86`/`arm64` 别名归一化）；运行时命令拼装追加 `--source-arch`。默认 `c,cpp`/`x86` 行为不变，单语言旧调用经便捷构造器保持兼容。
- **扫描进程内存闸门**：`RvPortProperties.KbScan` 新增 `memoryLimitMib`（默认 2048，0 关闭）；`KbScanRuntimeService` 将 `KB_SCANNER_MEMORY_LIMIT_MIB` 注入扫描器子进程环境，扫描器入口据此设置 RLIMIT_AS，超限转换为 `pipeline.memory-budget-exceeded` FATAL 诊断（分钟级失败替代小时级卡死）。compose worker 追加 `mem_limit` 与环境变量透传。
- **构建验证重试复用部署记录**：`SourceDeployExecutor.ensureDeployed` 在非 force 重试时先按 `task_id` 查回既有（含 FAILED）部署记录并复用重部署（同一 remote_path 幂等），不再因唯一键冲突使任务永久失败；配套 `findByTaskId` 查询。
- **测试容器可复现**：surefire 显式 `-Xmx512m`（无内存上限容器中 JVM 按宿主机 1/4 预留会导致 fork 失败）。

## Capabilities

### Modified Capabilities

- `kb-scan-runtime`：扫描请求多语言/源架构透传与内存闸门环境注入。
- `source-deploy-runtime`：任务重试的部署记录复用语义。

## Impact

- 代码：`KbScanRequest`/`KbScanInput`/`KbScanService`/`KbScanRuntimeService`/`RvPortProperties`/`SourceDeployExecutor`/`SourceDeployRepository`/`application.yml`/`docker-compose.yml`/`pom.xml` 及对应测试。
- 不改数据库 schema；不改 findings 导入链路。
- 全部改动已随 PR #22 验证（JDK25 容器 KbScan* 87/87）。
