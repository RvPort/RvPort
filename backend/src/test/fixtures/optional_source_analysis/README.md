# 源码独立分析本地验收夹具

仅用于新建的独立本地 Compose 项目。固定访问容器网络名 `backend:8080`，不得把该名称或网络接到已有部署。SSH 仅访问同一隔离网络中的 `optional-target`，没有宿主或远程 SSH 配置。模型仅访问 worker 回环地址，使用公开模拟密钥 `fixture-admin`，不调用真实模型。

## 运行约定

在受限客户端容器中挂载本目录；使用 Python 标准库，无额外依赖。状态文件 `/tmp/optional-acceptance-state.json` 与 Cookie 含本轮随机口令，仅留在客户端容器，不导出、不提交。第一步对独立新库的默认 admin 完成首次改密；后续重用本轮状态。每次基础验收创建新项目/源码，不清理其他数据。

按下列顺序执行（`<fixtures>` 为容器中的本目录）：

```sh
python3 <fixtures>/compose-acceptance.py
python3 <fixtures>/compose-review-checks.py
python3 <fixtures>/compose-build-checks.py
python3 <fixtures>/compose-enhanced-checks.py
python3 <fixtures>/compose-enhanced-checks.py partial
python3 <fixtures>/compose-context-checks.py
```

构建夹具要求本地 SSH 容器提供 clang-18、clang-21、Python 3 和 openssh-server，只有公钥认证；临时私钥位于客户端 `/tmp/optional-ssh-key`，目标允许目录为 `/srv/optional-source`、`/srv/optional-target`。两端同为本地测试架构，仅证明后端身份、批次、部署与顺序，不证明真实 RISC-V 运行能力。

模型使用上级 `report_review_loopback.py` 和 `agent_runtime_acceptance.py`，启动在 worker 的回环接口，将实际端口传入 setup；`scenario` 文件写 `confirmed`、`missing`、`unknown`、`duplicate`、`timeout` 等已有场景。

```sh
python3 <fixtures>/compose-ai-checks.py setup <回环端口>
python3 <fixtures>/compose-ai-checks.py run
python3 <fixtures>/compose-ai-checks.py missing
```

setup 显式创建本地个人 profile 并绑定 REPORT_AI_REVIEW，180 条真实 KB 候选分批运行真实 Codex CLI。模型输出由受控服务生成，因此本夹具验证协议、批次和状态，不代表模型语义准确率。

## 故障注入

容器停启/暂停由宿主显式执行，脚本不控制 Docker、不发现或修改其他环境。所有操作限定本轮项目名与确切容器名。

- worker 停止时 `compose-lifecycle-checks.py enqueue`，启动同版本 worker 后执行 `finish`，检查排队任务能正常领取完成。
- 仅在独立 override 中设置 KB 进程预算为 1 MiB，执行 `oom`，断言任务失败、诊断与预算保存、没有发布半截结果、后续浮点任务成功。缺少可证实诊断时不把进程失败伪报为 OOM。完成后恢复原预算。
- 独立 override 可缩短 heartbeat/offline/lost 为 2/5/12 秒，cleanup 周期为 1000 ms；不得修改默认部署配置。模型 scenario=timeout，执行 `lost-start` 后暂停确切 worker，运行 `lost-check` 等待既有 WORKER_LOST 终态，再恢复 worker、检查迟到执行不能替换旧报告。手动重新生成使用新任务，失联任务本身不宣称自动重试成功。
- scenario=timeout，执行 `cancel-start`、`cancel-check` 验证取消及旧报告保留。恢复正常 scenario 后重新生成，证明 worker 仍可服务。

`/tmp/optional-*-checks.json` 为脱敏断言结果。不要导出状态文件、Cookie、临时私钥、模型请求或完整运行目录。Java/Python 单元测试、schema 测试和覆盖率门禁必须另外执行；本夹具不替代它们。

V01–V19 的测试归属与实际运行证据见当前 change 的 verification.md。负向产物/跨项目/非法路径、部署关联和全部枚举异常由相应单元测试覆盖，真实 Compose 重点验证正常及代表性故障链路。
