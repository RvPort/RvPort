# 实施与验证记录

本变更在 `dev` 分支实施。前端源码未修改，远程 dev/test 未连接、修改或测试。全部安装、实验和测试在本地 Docker，使用假凭据和独立模拟上游；没有真实模型验证。

## 已完成的验证

- CLI 冻结树：DSH 0.1.0-rc.8、HMR 1.0.17、Codex 0.165.0（wrapper + 必需 Linux x64 包）、OpenCode 1.18.18、Node 22.23.2、npm 10.9.8。两次空目录 npm ci 均成功，462 包，规范化清单与锁字节一致。
- HMR 本地对照确认两个独立问题：headless 未激活服务，以及 HMR 1.0.18 起移除 registerConfig。修复启用启动期 HMR entry 并锁定 1.0.17，没有改第三方源码。
- 真实 CLI + 模拟模型：Codex RESPONSES/CHAT、DSH、OpenCode 的文本/文件读写共四组，以及 Codex custom apply_patch 文件写入均通过。
- 固定真实 Codex 错误回归：401、403、429、500、慢流、断流、畸形 SSE、不支持内容及取消均按预期失败/中断并回收子进程。HTTP 单元测试另验证分类、并发密钥/上下文隔离、跨 token 拒绝和 listener 关闭。
- 最终源码 Maven clean verify：1,852 项，0 失败/错误，2 项现有数据库集成测试按环境开关跳过；这些不计作单元测试完成的替代。JaCoCo 全 BUNDLE/PACKAGE 和已有 CLASS 门禁通过。
- Python：68 项全量单元测试通过，整体与每脚本行/分支门禁分别校验通过。
- 完整 Compose：从项目根构建全部默认服务成功，以一次 `up -d --wait` 启动全部七个服务，六个有 healthcheck 的服务均 healthy；worker 无独立 healthcheck，通过正常管理 API 确认为 ONLINE，backend/worker 重启次数均为 0。
- 最终镜像正常 API：管理员登录与改密、创建/更新两种协议 profile、连通性、Codex RESPONSES/CHAT 与 DSH/OpenCode CHAT 的三项 probe、阶段绑定、两个 profile 并发隔离、1 秒超时及恢复、历史记录不变、普通用户权限拒绝均通过。超时每项总耗时小于 4 秒且 cleanup=COMPLETE；验收后无 Agent 进程、loopback listener、临时 workspace/supervisor 或 Codex 凭据文件残留。
- backend 与 worker 使用同一最终镜像 `sha256:1aedfb4a513a9b9ec7d5f7198973a051f4338510a9726d10aa49f506deff3a33`，实际版本和 462 包依赖清单核对一致；OpenCode 自动更新关闭。

| 范围 | 行 covered/total | 行覆盖率 | 分支 covered/total | 分支覆盖率 |
|---|---:|---:|---:|---:|
| Java 整体 | 24600/25377 | 96.94% | 9700/10583 | 91.66% |
| agentruntime | 233/233 | 100.00% | 34/34 | 100.00% |
| agentruntime.bridge | 486/495 | 98.18% | 346/375 | 92.27% |
| codexprovider | 496/515 | 96.31% | 215/236 | 91.10% |
| modelprofile | 881/910 | 96.81% | 362/397 | 91.18% |
| kb.ai | 563/578 | 97.40% | 179/198 | 90.40% |
| testcomparison.runtime | 416/423 | 98.35% | 180/194 | 92.78% |
| worker（回归范围，未修改） | 1011/1050 | 96.29% | 307/340 | 90.29% |
| Python 整体 | 765/786 | 97.33% | 300/317 | 94.64% |
| verify_lock.py | 64/65 | 98.46% | 41/42 | 97.62% |
| agent_process_supervisor.py | 65/66 | 98.48% | 21/22 | 95.45% |
| prepare_specialized_analysis.py | 293/310 | 94.52% | 111/122 | 90.98% |
| produce_bitcode.py | 343/345 | 99.42% | 127/131 | 96.95% |

Java 纳入全部编译的自有源码（含未加载类），仅排除自动生成 jOOQ；Python source 显式包含 analysis、bitcode、agentruntime 和 agent-cli。第三方 npm 包与测试夹具不属于生产源码分母。未降低门槛或新增业务排除。worker 代码未修改；取消/资源清理实现位于 codexprovider、bridge 和 supervisor，已纳入相应完整范围。

## 可重复执行的核心命令

以下终端命令从 `D:\projects\RvPort` 执行，安装/编译/测试均由 Docker 完成：

```powershell
docker build --target unit-test -t rvport-agent-runtime-unit:release -f backend/Dockerfile .
docker run --name rvport-agent-runtime-unit-release --network rvport-agent-unit-local --cpus 2 --memory 6g rvport-agent-runtime-unit:release
docker run --name rvport-agent-python-verify --network rvport-agent-unit-local --cpus 2 --memory 6g -e PYTHONPATH=/workspace/tools/memory-order-analyzer/python --entrypoint sh rvport-agent-runtime-unit:release -c 'python3 -m coverage run --branch --source=src/main/resources/analysis,src/main/resources/bitcode,src/main/resources/agentruntime,agent-cli -m pytest -q src/test/python && python3 -m coverage report --fail-under=90 && python3 -m coverage json -o /tmp/python-coverage.json'
```

Python 整体与每文件的行/分支分别校验，命令见根目录开发指南。全量 Java 报告导出自最终源码镜像 `sha256:21ceba353a83222c3e3208fc1a0ef8a46fcf00e30191196e59f0056afe75bf12`。

完整 Compose 验收使用专用项目 `rvport-agent-runtime-acceptance`、独立网络/卷、仅本地监听端口及假凭据。模拟上游为单独的测试容器，不加入生产 Compose 默认服务。实际命令如下（私有 env/override 不提交）：

```powershell
$report = Join-Path (Get-Location) '.migration-reports/fix-agent-runtime-dependency-locking-and-probe-diagnostics'
$proxy = (docker info --format '{{.HTTPProxy}}').Trim()
if ($proxy -notmatch '^https?://') { $proxy = 'http://' + $proxy }
docker compose --env-file "$report/compose.env" -f docker-compose.yml -f "$report/compose.override.yml" --parallel 1 build --builder rvport-agent-local-builder --build-arg "HTTP_PROXY=$proxy" --build-arg "HTTPS_PROXY=$proxy" --build-arg "http_proxy=$proxy" --build-arg "https_proxy=$proxy"
docker compose --env-file "$report/compose.env" -f docker-compose.yml -f "$report/compose.override.yml" up -d --wait --wait-timeout 300
docker exec rvport-agent-runtime-acceptance-backend-1 python3 /tmp/api-acceptance.py
```

构建所用 `$proxy` 为本机 Docker 配置的代理地址，不包含上游模型凭据。API 验收脚本从私有报告目录复制到容器；模拟服务来自 `backend/src/test/fixtures/agent_runtime_acceptance.py --serve`。完整命令、输入和结果保存在私有证据中，验收脚本只访问隔离服务。

## 报告与环境说明

私有原始证据位于根 `.migration-reports/fix-agent-runtime-dependency-locking-and-probe-diagnostics/`：`java-unit-release.log`、`jacoco-release/`、`surefire-release/`、`python-coverage.json`、`python-gate.log`、`cli-acceptance-current/`、两次 CLI manifest、源码 SHA-256 清单和版本来源记录。

本地 Docker 引擎曾中断，受影响日志不能算通过；Java 和真实 CLI 已成功重跑。前端 lint、类型检查、128 文件/697 项单元测试及生产构建已通过（frontend-final.log）。最终完整构建、统一启动、正常 API 验收均退出 0，证据分别为 compose-build-pip-inherited.log、compose-up.log、api-acceptance.log 和 agent-api-acceptance.json；其他构建日志保留其失败或中断状态。

服务证据为 compose-health.json、compose-services.log、worker-online.json、frontend-backend-health.log、compose-image-ids.txt、backend-worker-image-comparison.txt、backend/backend-worker-versions.log、backend/backend-worker-manifest.json 和 manifest-comparison.log。日志中没有持续业务错误；空数据库首次 Liquibase 探测尚不存在的 databasechangeloglock 后正常创建并完成迁移，邮件未启用警告来自本地权限用例，另有现有 Java native-access/单节点 MinIO 提示。

验收后已移除本次 Compose 服务与专用数据卷、模拟服务、测试容器、独立 BuildKit 及其状态卷、CLI 工作卷和两个专用网络。保留报告与验收镜像，未清理其他项目资源；清理命令退出 0，证据为 cleanup.log 与 containers-before-cleanup.txt / containers-after-cleanup.txt。当前仍为 dev，前端源码无差异，未提交或推送。OpenSpec 严格校验与修改文档的相对文件链接检查通过；本阶段实施和验收完成，规范同步与归档等待下一步指令。

构建环境补充：隔离 BuildKit 使用 2 CPU、4 GiB（含 swap 总额 6 GiB），worker max-parallelism=1。因 Docker Hub 大层下载持续停滞，将同一官方基础镜像导出为 OCI layout，并核对导出的 config digest 与本地官方镜像 ID 完全一致；仅私有 Compose override 使用 additional_contexts 指向这些内容，项目 Dockerfile 仍使用原官方基础镜像。npm/Maven/LLVM 只复用下载缓存，应用始终使用当前源码重新构建。

Windows System 事件 2004 多次报告虚拟内存不足，vmmemWSL 约 21–24 GB，与 Docker 整体重启时间接近。已保留 host-memory-events.json；没有修改全局 WSL 设置或停止其他项目容器。中断的构建不计为通过。

随后发现旧隔离 BuildKit 中的 Java 基础层存在 0 字节的 java、dpkg status 文件，原始官方镜像与 OCI 归档内容正常。已移除并重建本次隔离构建器；重新导入后，java、javac、lib/modules、dpkg status 的 SHA-256 与官方镜像逐一一致，Java 命令正常。仅保留经 15,757 项已安装文件 MD5 核验通过的独立 Ubuntu 工具链缓存及下载缓存。证据为 java-base-integrity-rebuilt.log、ubuntu-cache-integrity.log；该次缓存恢复重建日志为 compose-build-clean-cache.log。

后续构建遇到下载问题：pip 下载 rpds-py 触发默认读取超时，已为专项工具依赖安装补齐与既有运行时安装一致的 `--timeout 120 --retries 10`；KB scanner 的隔离构建子进程不继承命令行超时，因此同时设置该次安装进程的 `PIP_DEFAULT_TIMEOUT=120 PIP_RETRIES=10`，重建后安装成功。不改变 Agent 执行预算。MinIO 的 Go 下载卡在 HTTP/2 响应体读取，诊断栈保留于 compose-build-download-retry.log。使用独立、限时的本地 Go 容器临时设置 `GODEBUG=http2client=0`，按原始 go.mod/go.sum 从官方源预取模块缓存；断点续传 LZ4 后执行 `go mod verify`，全部模块校验通过，未修改 MinIO、前端源码或生产服务配置。成功证据为 go-all-download-verified.log、lz4-resume-download.log；此前失败和超时日志保留，不计作通过。

Node 22.23.2 Linux x64 压缩包独立核对官方 SHASUMS256.txt，SHA-256 为 `d60acfe00a2932254bb0ad20e01b0d74397a0875595de719654b214f4b03f307`。证据为 node-official-SHASUMS256.txt、node-archive-sha256.txt。最终单元测试之后仅补充 Dockerfile 下载参数，Java/Python 生产源码和测试源码哈希保持一致。
