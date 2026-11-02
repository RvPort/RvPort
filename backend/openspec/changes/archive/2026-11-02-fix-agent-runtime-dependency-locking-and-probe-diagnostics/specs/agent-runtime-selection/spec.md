## MODIFIED Requirements

### Requirement: Agent CLI 安装必须锁定完整依赖树
后端运行镜像 SHALL 使用已提交的精确版本清单和完整依赖锁文件安装受管 Agent CLI 及 OpenCodex/Bun，覆盖传递、peer、optional 与目标平台依赖；MUST NOT 在常规构建中浮动解析 latest 或仅固定主包。

#### Scenario: 构建固定 DSH 版本
- **WHEN** 构建本次后端镜像
- **THEN** DSH 主包 SHALL 为 `0.1.0-rc.8`，相关 DSH 组件和独立 Cordis/HMR 组件 SHALL 匹配经过验证的锁定组合
- **AND** Node、npm、平台、依赖版本及完整性信息 SHALL 可追溯
- **AND** backend 与 backend-worker SHALL 使用包含相同受管 CLI 的同一镜像

#### Scenario: 重复干净安装
- **WHEN** 在本地 Docker 以同一清单和锁文件进行两次空安装
- **THEN** SHALL 使用 `npm ci` 或已证实等价的冻结安装机制，得到相同规范化依赖树
- **AND** 第二次安装 MUST NOT 更新锁文件
- **AND** 缺包、额外包、版本或完整性不匹配 SHALL 使验收失败

#### Scenario: 锁定 Codex 最新稳定版本
- **WHEN** 开始实施 Codex 升级
- **THEN** SHALL 核实官方最新稳定版并记录精确版本与来源，2026-10-29 的提案基线为 `0.165.0`
- **AND** 如版本更新 SHALL 先更新设计，再锁定 wrapper 与匹配平台二进制及完整性校验
- **AND** MUST NOT 使用预发布版替代用户要求的最新稳定版或在镜像构建时重新解析 latest

#### Scenario: 依赖解析失败
- **WHEN** 安装出现 peer 冲突、堆溢出、网络失败或缺少平台包
- **THEN** SHALL 保留失败证据并保持安装验收未完成
- **AND** MUST NOT 通过忽略退出码、无依据强制安装或复用旧 node_modules 声称当前版本通过

#### Scenario: 本轮固定 OpenCodex 并保持既有版本基线
- **WHEN** 构建本轮 OpenCodex 替换方案
- **THEN** SHALL 使用 `@bitkyc08/opencodex@2.94.0`，固定匹配的 Bun 1.4.4、实际平台二进制及完整传递依赖和完整性信息
- **AND** SHALL 保持已验收的 Codex 0.165.0、DSH 0.1.0-rc.8/HMR 1.0.17 和 OpenCode 1.18.18 基线，不因新依赖扁平化而改变其依赖组合
- **AND** SHALL 记录许可证、安装脚本产生的依赖/二进制及全部下载来源；任何版本调整先更新设计并重新验证
- **AND** worker SHALL 使用冻结安装产物，运行期自动更新 MUST 被禁用

#### Scenario: worker 专属执行与共享镜像
- **WHEN** backend 与 backend-worker 使用同一含代理文件的运行镜像
- **THEN** 只有 worker 运行角色 SHALL 启动受管 OpenCodex，backend API 角色 MUST NOT 启动代理
- **AND** CHAT 的管理探针 SHALL 经内部调度到 worker，不因共用镜像而绕过该边界

## ADDED Requirements

### Requirement: OpenCodex 集成必须延续完整执行监管及质量门槛
项目 SHALL 将代理排队、启动、就绪、推理和关闭纳入现有预算与进程监管，并对本轮当前源码重新执行单元测试、覆盖率和本地完整 Docker 验收。

#### Scenario: 阶段和探针共用资源预算
- **WHEN** 多个业务阶段和 Codex CHAT probe 同时等待启动代理
- **THEN** SHALL 共同遵守受控并发限额，排队/就绪/执行共用绝对截止时间
- **AND** SHALL 显式设置有限上游等待，不依赖第三方对本地地址的无限超时默认值
- **AND** 超时或取消 SHALL 终止本执行拥有的代理和 CLI 进程树，在有限清理宽限结束后给出已清理或清理失败诊断

#### Scenario: 单元测试和覆盖率归属
- **WHEN** 验证本轮替换
- **THEN** SHALL 对 backend 全部自有 Java/资源脚本及完整受影响模块执行独立单元测试，整体及各受影响模块行/分支各至少 90%
- **AND** SHALL 纳入未加载源码、自有代理监管/准入/配置、worker 分派/结果归并、锁校验脚本及复用模块
- **AND** 第三方源码和自动生成文件可按明确理由排除，自有胶水逻辑 MUST NOT 排除或用仅变更行覆盖率替代

#### Scenario: 当前源码完整本地验收
- **WHEN** 本轮单元测试与覆盖率通过
- **THEN** SHALL 从根目录对当前候选源码顺序构建全部默认 Compose 服务，并一次 `docker compose up -d --wait` 启动隔离 stack，检查健康、日志和代表性业务/管理 API
- **AND** SHALL 验证无新增代理 Compose 服务/主机端口、仅 worker 启动代理、动态 profile、三项 probe、旧兼容入口、原生直连与 DSH/OpenCode 回归
- **AND** SHALL 记录完整命令、测试结果、源码/镜像/lock 标识、覆盖率 covered/total 和清理证据；未执行或失败不得沿用历史勾选
- **AND** 全部安装、测试和验收 SHALL 只在本地 Docker，远程 dev/test 只允许只读排查且不得执行测试
