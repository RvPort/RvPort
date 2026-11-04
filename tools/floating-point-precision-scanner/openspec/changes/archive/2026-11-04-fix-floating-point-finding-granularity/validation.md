# 全规则 Finding 粒度验收记录

日期：2026-11-03。变更：`fix-floating-point-finding-granularity`。

## 范围与行为

修复范围为本工具的十条规则、候选构造、事实关系、证据闭包、惰性预算、局部异常、抑制、配置成员聚合、v2 输出、缓存版本与测试。未修改前端、Java 后端或其他工具源码。工作区原有根 README 和架构分析文档不属于本次交付。

独立 API/libm 调用、比较、转换、ABI 边界、归约候选、FMA 表达式、x87 求值链和 fenv 状态区间分别评估。一个候选的 unknown、failed、置信度或恢复证据不能覆盖其他候选。严格等价的危险配置允许聚合，但全部成员、assessment 和必要证据必须保留；成员 suppression 在聚合前执行，结果截断仍保留可验证的成员审计。

位置列表并非无条件删除：同一问题的必要关联证据，以及证明等价的配置影响组，仍可包含多个位置。独立调用/表达式/函数/循环不得以此理由合并。历史已持久化报告不回填；使用新粒度需要部署新工具后重新扫描。本次仅启动隔离验收服务，未替换原有部署。

逐规则语义、适用生命周期和不适用项理由见 [候选矩阵](../../../../tests/fixtures/validation/candidate-matrix-v1.json)，测试包含归属与引用集合断言，不只检查条数。预期由规范独立编写；未以 observed 自动生成 oracle。正常 PR 人工审查仍按项目流程执行。

## 基线、快照与实际执行环境

- 基线 HEAD：`f46eda032af59330862a782de340d029a78fd7ef`，此前归约修复提交 `555edea`。
- 基线在 Docker 中重新执行：380 个单元/契约测试通过，Python 行覆盖率 97.5244%、分支 94.0703%。实际复现 API 跨函数、混合比较、两个 fenv 区间、FMA/x87 跨函数和不同编译语义被合成一条结果的问题。
- 验证源码 Git tree：`72043b08d0180706d3624c64c7a1151b851fc1db`。临时 index 只纳入本工具；真实暂存区未被修改。归档关闭 autocrlf，按 Git 原始 blob 验证输入字节。
- 快照 archive SHA256：`ecaedb1e5c8d33e96f59a407e025f06e135e8d304d9b8691818b9302961a3098`。
- 最终工具镜像：`rvport-fp-all:20261103-git`，ID `sha256:e20771ff38f4d8e194227c325d1dcae3b1869bfa2c9ff779af7e723e4dea538b`。
- 259 个实际工具构建输入与 Git blob 一致；两份历史 benchmark/validation 报告遵照 Docker ignore 不作为构建输入。清除旧源码、构建和结果后，从当前快照安装 Python 包并重新编译 C++。
- Linux/amd64、Clang/LLVM 21.1.8，工具验证容器 4 CPU、4 GiB、禁网，C++ 编译并发 2。依赖版本输出与镜像检查保存在私有报告目录。
- 验证结束后仅补充本验收文档和任务状态；最终交付 tree 与验证 tree 的差异另存私有记录，确保无源码/测试变化。

私有报告根目录（下称 `R`）：`D:/projects/RvPort/.migration-reports/fix-floating-point-finding-granularity/2026-11-03`。该目录及凭据不进入公开源码。

## 工具验证命令与结果

在仓库根调用 Docker；工具镜像内工作目录为 `/workspace`：

```powershell
docker exec rvport-fp-all-git make lint typecheck coverage cpp-coverage integration validation benchmark
```

结果：Ruff 通过；mypy 检查 53 个源文件通过；Python 单元/契约 474 个通过；C++ 单元 22 个通过；真实 Clang/LLVM 集成 39 个通过。完整日志为 `R/final-gates.log`。每次 C++ 单测前清除 profraw，避免旧执行累计覆盖率。

Python 覆盖率以全部 `src/floating_point_precision_scanner` 为 source，`omit=[]`，包括未被加载的自有模块；仅排除无运行时行为的 `TYPE_CHECKING` 分支。C++ 统计全部自有可执行 `.cpp`（`ir_facts.cpp`、`ir_worker.cpp`），第三方依赖和仅声明头文件不作为自有可执行源码。单元/契约测试产生覆盖率，集成测试不替代单元覆盖率。

整体和 Makefile 列出的全部受影响模块行/分支均达到 90%。下表同时列出未修改模块，未修改的 `output.py` 历史分支覆盖率为 75%，保留在整体分母中，未因难测排除或更改其统计；本次不修改该模块。

原始覆盖率：`R/coverage/coverage.json`、`coverage.xml`、`htmlcov/`、`cpp-coverage.json`。语料：`R/validation/current-observed.json`、`validation-result.json`；性能：`R/benchmark/benchmark-result.json`。保留原有 182 案例，新增 24 个，206/206 精确匹配，生命周期 100%，TP 134、FP 0、FN 0；各规则 strict/supported precision/recall 均 100%。保留独立 oracle 标记的 unknown 案例，不通过删除难例或统一弃判过关。

版本：工具 0.6.0、facts 1.6、mapper 1.3、evidence 1.3、registry 1.4、全部规则 1.2、LLVM extractor 1.2；request 1.4、result v2、worker protocol 1.1 与 IR schema 1.1 保持兼容。候选/关系语义变化进入缓存指纹，旧缓存拒用，冷暖和搬迁等价验证通过。

## 当前快照覆盖率

| 范围 | 行覆盖率 | 分支覆盖率 |
|---|---:|---:|
| Python 整体 | 97.76% | 94.72% |
| `__init__.py` | 100.00% | 100.00% |
| `__main__.py` | 100.00% | 100.00% |
| `aggregation.py` | 100.00% | 100.00% |
| `application.py` | 93.77% | 90.62% |
| `artifacts.py` | 97.20% | 95.65% |
| `ast_relations.py` | 100.00% | 100.00% |
| `benchmark.py` | 100.00% | 100.00% |
| `benchmark_runner.py` | 100.00% | 96.43% |
| `budget.py` | 100.00% | 95.45% |
| `build_contexts.py` | 97.37% | 91.67% |
| `cache.py` | 98.23% | 100.00% |
| `candidates.py` | 100.00% | 100.00% |
| `canonical.py` | 100.00% | 100.00% |
| `canonical_types.py` | 100.00% | 100.00% |
| `cli.py` | 98.17% | 90.00% |
| `compilation.py` | 93.63% | 91.18% |
| `corpus_runner.py` | 98.37% | 95.00% |
| `coverage_gate.py` | 98.00% | 90.00% |
| `diagnostics.py` | 100.00% | 100.00% |
| `evidence.py` | 100.00% | 98.28% |
| `facts.py` | 95.88% | 91.40% |
| `frontend.py` | 98.65% | 94.44% |
| `ir_worker.py` | 100.00% | 97.22% |
| `output.py` | 100.00% | 75.00% |
| `profiles.py` | 98.89% | 91.67% |
| `protocol.py` | 98.46% | 92.86% |
| `reduction_candidates.py` | 100.00% | 100.00% |
| `result_v2.py` | 98.16% | 94.25% |
| `rules.py` | 97.36% | 93.95% |
| `source_locations.py` | 100.00% | 100.00% |
| `step_descriptions.py` | 100.00% | 100.00% |
| `suppression.py` | 100.00% | 100.00% |
| `validation.py` | 99.37% | 98.08% |
| C++ 整体 | 98.95% | 92.25% |
| ir_facts.cpp | 99.17% | 92.08% |
| ir_worker.cpp | 96.83% | 94.44% |

## 逐规则真实语料结果

| 规则 | 案例/精确匹配 | TP | FP | FN | strict P/R | supported P/R | abstention |
|---|---:|---:|---:|---:|---|---|---:|
| `architecture-specific-fp-api` | 25/25 | 28 | 0 | 0 | 100%/100% | 100%/100% | 0 |
| `floating-environment` | 20/20 | 12 | 0 | 0 | 100%/100% | 100%/100% | 0 |
| `fma-contraction` | 24/24 | 12 | 0 | 0 | 100%/100% | 100%/100% | 0 |
| `fragile-fp-comparison` | 21/21 | 15 | 0 | 0 | 100%/100% | 100%/100% | 0 |
| `long-double-abi` | 18/18 | 9 | 0 | 0 | 100%/100% | 100%/100% | 0 |
| `math-library-variance` | 18/18 | 9 | 0 | 0 | 100%/100% | 100%/100% | 0 |
| `mixed-precision-conversion` | 18/18 | 9 | 0 | 0 | 100%/100% | 100%/100% | 0 |
| `reduction-reassociation` | 24/24 | 20 | 0 | 0 | 100%/100% | 100%/100% | 0 |
| `unsafe-fp-optimization` | 20/20 | 11 | 0 | 0 | 100%/100% | 100%/100% | 0 |
| `x87-excess-precision` | 18/18 | 9 | 0 | 0 | 100%/100% | 100%/100% | 0 |

abstention 为应支持案例中的弃判；独立 oracle 标记 unsupported 的 unknown 案例仍全部执行和精确比较，不从案例数删除。每条规则至少保留 3 个 unknown 案例。

## 固定资源性能

| 档位 | 冷 wall 秒 | 冷 CPU 秒 | 冷 RSS MiB | 热 wall 秒 | 结果字节 | 门禁 |
|---|---:|---:|---:|---:|---:|---|
| S | 0.9611 | 0.9587 | 83.94 | 0.0554 | 6170 | 通过 |
| M | 4.5014 | 4.4505 | 83.95 | 0.1921 | 12422 | 通过 |
| L | 23.3818 | 23.2543 | 83.95 | 1.0411 | 43624 | 通过 |

环境为 Linux/amd64、Clang/LLVM 21.1.8、4 CPU、4 GiB、禁网；每档三次冷/热运行及一次工作区搬迁。原 S/M/L workload digest、绝对门槛、热/冷 60% 门槛和相对接受基线 20% 门槛均保持不变。


## 完整 Compose 与安装包验收

从仓库根使用私有隔离 override，按 minio、backend、frontend 顺序构建全部可构建默认服务。其余默认服务使用 Compose 指定的 PostgreSQL 18.4、RabbitMQ 4.3.2-management、Redis 8.8.0。最终镜像：

| 服务 | 镜像 | ID |
|---|---|---|
| backend / backend-worker | `rvport-fp-all-backend:20261103` | `sha256:c2ea2cb18d99d1e94f705a5c7262e71aeda1bc238997aa8d2691afa176dfae36` |
| frontend | `rvport-fp-all-frontend:20261103` | `sha256:657c125888b39cbda2d647665ad928ed20d46611ef047ca26570bd598a88b8f6` |
| minio | `rvport-fp-all-minio:20261103` | `sha256:9d65bb698828342896d4714c3e03abda94d602a405b2f6e1aae60c89bb1916ba` |

默认后端 Dockerfile 的远端 LLVM 下载因速度门限失败，部分 apt 下载也受网络影响。最终使用私有 `R/Dockerfile.backend-validation` 适配依赖缓存，未修改公开 Dockerfile，不能据此声称无适配网络构建通过。缓存仅提供第三方依赖：校验固定 LLVM bundle SHA256 `b456e58a78fc48d9f0cf3d767762840baf0346806f71d399d1dfffff58c5b489`；核对 Clang/LLVM 21.1.8；复用 Maven 依赖、锁定 Python 依赖、与当前 package-lock 完全一致的 Node 依赖。清除旧自有源码、JAR、分析工具与安装包，从当前快照重新编译 Java、FP/MOA C++ worker，重新安装 FP Python 包，并复制当前 agent、KB 等自有代码。当前锁文件验证及补丁校验均执行。适配文件、依赖镜像 ID 和完整构建日志留在私有报告；不以旧应用产物代替本次构建。

实际终端命令（工作目录 `D:\projects\RvPort`；`compose.env` 是私有测试凭据，不复制到公开文档）：

```powershell
$r = 'D:/projects/RvPort/.migration-reports/fix-floating-point-finding-granularity/2026-11-03'
foreach ($service in @('minio', 'backend', 'frontend')) {
    docker compose --env-file "$r/compose.env" -p rvport-fp-all-granularity -f docker-compose.yml -f "$r/compose.override.yml" build $service
    if ($LASTEXITCODE -ne 0) { throw "Build failed: $service" }
}
docker network create --label rvport.validation=fix-floating-point-finding-granularity rvport-fp-all-granularity-infra
docker compose --env-file "$r/compose.env" -p rvport-fp-all-granularity -f docker-compose.yml -f "$r/compose.override.yml" up -d --wait --wait-timeout 300
```

第一次 up 在创建容器前因 external 网络不存在而退出，保存于 `compose-up-network-preflight.log`。补建本项目专属网络后，最终一轮以一次 up 启动全部默认服务并返回 0，没有逐服务补启动。资源上限、5 个独立 volume、网络及本机回环端口见 `compose-isolation.json` 和私有 override；不连接既有数据库或 SSH。

验收结果：7 个默认服务 running，backend/frontend/postgres/rabbitmq/redis/minio 六个健康检查均 healthy，worker 无容器 healthcheck，但管理接口显示 ONLINE 和持续 heartbeat。通过前端代理实际执行 CSRF、登录、修改初始密码、项目创建/读取、项目概览、浮点分析列表和 worker 查询。日志中首次空库探测出现 `databasechangeloglock` 尚不存在，随后 107 个 Liquibase changeset 全部完成；另有依赖弃用、Java native access 和单节点存储提示，无持续启动或业务失败。原始记录为 `compose-up.log`、`compose-ps.json`、`compose-service-logs.txt`、`compose-smoke.json`、`worker-registry.json`。

前端代码未变；仍从快照构建 builder，执行 `pnpm lint && pnpm typecheck && pnpm test`：214 个测试文件、1335 个测试全部通过；生产构建通过。按根 AGENTS 本次不验收前端覆盖率。DOM 模拟环境的 getComputedStyle/CodeMirror 测量提示保留在日志，未导致断言失败。结果在 `frontend-checks.log`、`compose-build-frontend-final6.log`；后端当前源码构建日志为 `compose-build-backend-final6.log`，minio 为 `compose-build-minio.log`。

实际 Compose backend 中的安装路径 `/opt/specialized-analysis-venv/lib/python3.12/site-packages/floating_point_precision_scanner` 已逐字节验证 55 个 Python/schema/profile 文件，与 Git 快照一致，工具 0.6.0、worker extractor 1.2。另从同一生产 backend 镜像启动 4 CPU/4 GiB、禁网容器 `rvport-fp-all-installed`，只复制输入语料和校验脚本，不挂载/覆盖 scanner 源码，执行：

```powershell
docker exec -e FP_SCANNER_CLANG=/usr/bin/clang-21 -e FP_SCANNER_IR_WORKER=/opt/rvport/analysis-tools/bin/fp-ir-worker rvport-fp-all-installed /opt/specialized-analysis-venv/bin/python -m floating_point_precision_scanner.corpus_runner --inputs /tmp/installed-fixtures/executable-inputs-v1.json --output /tmp/installed-validation/current-observed.json
docker exec rvport-fp-all-installed /opt/specialized-analysis-venv/bin/python -m floating_point_precision_scanner.validation --corpus /tmp/installed-fixtures/executable-corpus-v1.json --observed /tmp/installed-validation/current-observed.json --output /tmp/installed-validation/validation-result.json
docker exec rvport-fp-all-installed /opt/specialized-analysis-venv/bin/python /tmp/audit_installed.py
```

安装包全部 206 个案例通过，每个 Finding payload 与开发验证逐项完全一致，输入/源码摘要一致且 result schema 校验通过。向量计算代表项目 `granularity-api-vectorcalc-project` 得到 **10 条 API Finding，每条 1 个 occurrence、affectedCount=1**；不同函数的独立调用不再被收进一条位置下拉列表。十规则粒度、混合状态、合法关联/配置聚合、预算与 suppression 场景均由同一完整语料和单元矩阵覆盖。记录为 `installed-audit.json`、`installed-byte-check.json`、`compose-installed-byte-check.json`、`installed-worker-version.json`、`installed-validation/`。

## 交付检查与阶段

OpenSpec 严格校验、文档本地链接、Git diff 空白与变更范围检查通过。全部实现和验收任务完成；本次不执行主规范同步、归档、提交或推送。下一阶段在本工具独立 OpenSpec 根进行 sync，再 archive。历史归档勾选未作为本次验收证据。
