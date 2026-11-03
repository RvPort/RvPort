# 验证记录

## 范围与环境

2026-10-30 按用户追加的“验证收尾”执行。验证对象为当前 `frontend/` 整个工作区，包含尚未提交的其他前端修改；不能把结果归因于某个已提交 SHA。

- 镜像：`rvport-frontend-dev:local`，ID 为 `sha256:af7fd3932aadc9d07b8743843e30c10fce01e5209ebf655c8d124f9407acc4fe`。
- 工具链：Node.js `v22.23.2`、pnpm `12.15.0`；镜像中的 `package.json` 和锁文件与当前前端一致，仅复用依赖，源码从当前工作区重新复制。
- 独立 volume：`rvport-frontend-verify-20261030`；网络为 `none`，限制 2 CPU、6 GiB 内存，检查与测试按顺序执行，Vitest 单 worker。
- 不复制真实 `.env*`、运行数据或历史覆盖率报告；不连接部署服务、数据库、真实 SSH 或后端。
- pnpm 使用 `--config.verify-deps-before-run=false`，避免复制依赖到独立工作目录后触发自动重装；没有禁用任何质量检查。

## 实际结果

| 命令                                                                | 结果                                             |
| ------------------------------------------------------------------- | ------------------------------------------------ |
| `pnpm --config.verify-deps-before-run=false run format:check`       | 通过                                             |
| `pnpm --config.verify-deps-before-run=false run lint`               | 通过                                             |
| `pnpm --config.verify-deps-before-run=false run typecheck`          | 通过                                             |
| `pnpm --config.verify-deps-before-run=false run test`               | 214 个文件、1,335 个用例全部通过，耗时 369.20 秒 |
| `pnpm --config.verify-deps-before-run=false run build`              | 通过，耗时 7.71 秒                               |
| `openspec validate adapt-memory-order-result-presentation --strict` | 通过                                             |
| `openspec validate --specs --strict --no-interactive`               | 32 项主规范全部通过                              |

OpenSpec 命令在 Docker 中通过 `npx --offline --yes @fission-ai/openspec@1.13.0` 执行。三项 delta 分别向 `assessment-report`、`migration-risk-analysis-stages`、`project-pipeline-workbench` 同步 1、4、1 条要求，保留原有要求与场景。

## 本轮修正与回归范围

- 修复导入排序和格式问题，没有调整业务行为来迁就检查。
- 诊断页下载按钮补充明确的无障碍名称；按概览新的诊断摘要更新旧文案断言。
- 位置切换测试实际选择对应选项，继续验证不同 digest 及无源码位置不会复用旧片段；报告操作测试兼容中文按钮的可访问名称空格。
- 全量普通测试覆盖内存序 2.0/3.0、候选与正式风险、未知和截断诊断、主链/分支/断点、源码隔离、诊断二级页最新/历史往返及失败重试、报告待核实项与完整性提示。
- 浮点同规则/文件/编译单元的两个 Finding 独立展示，以及历史单 Finding 多 occurrences 的回归通过；知识库原有证据与上下文测试通过。
- 未删除或跳过失败测试，未降低覆盖率门槛。

## 日志、构建产物与复跑

原始日志导出到 `/tmp/rvport-frontend-verification-20261030/`，最终质量门禁日志为 `final-format:check.log`、`final-lint.log`、`final-typecheck.log`、`final-test.log`、`final-build.log`。早期失败及定向重跑日志同时保留，不以它们代替最终全量通过记录。

构建产物保留在独立 volume 的 `/work/dist/`，没有覆盖部署产物。该 volume 与本地临时日志仅作为本机排查证据，本文件记录可随源码保留的验证结果。

从 `frontend/` 使用相同依赖 volume 复跑当前源码的完整命令：

```sh
docker run --rm --network none --cpus=2 --memory=6g \
  -v "$PWD:/source:ro" \
  -v rvport-frontend-verify-20261030:/work \
  -w /work --entrypoint sh rvport-frontend-dev:local -c '
    tar -C /source --exclude=node_modules --exclude=.pnpm-store \
      --exclude=dist --exclude=coverage --exclude=.env\* \
      --exclude=.git --exclude=.migration-reports -cf - . | tar -C /work -xf -
    for check in format:check lint typecheck test build; do
      pnpm --config.verify-deps-before-run=false run "$check" || exit "$?"
    done
  '
```

## 警告与未验证范围

- jsdom 输出伪元素 `getComputedStyle` 不支持提示；普通单元测试无失败。
- 构建输出插件耗时与超过 500 kB 分块提示，主要大块为编辑器产物；未改变分块策略或隐藏警告。
- 本轮不统计覆盖率，不执行 E2E、浏览器视觉验收、真实重新扫描、后端版本部署确认或根 Compose 启动。通过的单元测试不等于真实环境已部署 `555edea` 或内存序分析器对应版本。
- 浮点独立 change `preserve-floating-point-finding-identity` 的回归已随本轮通过，但本次只归档内存序展示 change；不提交或推送代码。
