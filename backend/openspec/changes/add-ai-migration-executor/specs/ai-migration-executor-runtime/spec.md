# ai-migration-executor-runtime 新能力

## ADDED Requirements

### Requirement: 执行器镜像必须自带构建期自检

执行器镜像（pi + qemu-user-static + RISC-V 交叉工具链 + provider extension + 入口脚本）SHALL 在构建期自检 `pi --version`、`qemu-riscv64 --version` 与交叉编译器版本，任一失败 SHALL 中断构建；构建入口与出口 SHALL 模板化以支持受限网络。

#### Scenario: 构建自检失败
- **WHEN** 镜像构建期内任一自检命令不可用
- **THEN** 构建 SHALL 失败，不得产出镜像

#### Scenario: 受限网络构建
- **WHEN** 提供内网 apt/node/npm 构建参数
- **THEN** 构建 SHALL 使用给定源完成，不依赖公网默认源

### Requirement: 迁移执行必须在一次性容器中运行且可取消

后端 SHALL 以 `docker run --rm` 启动命名容器执行迁移（workspace 挂载、模型凭据经显式环境变量注入、代理环境置空）；任务取消或超时 SHALL 终止容器；启动前 SHALL 清理同名残留容器。

#### Scenario: 正常执行
- **WHEN** 迁移任务在容器模式执行
- **THEN** 容器 SHALL 以任务唯一命名启动，退出后自动清理，退出码映射任务结果

#### Scenario: 取消与超时
- **WHEN** 任务被取消或超出执行预算
- **THEN** 对应容器 SHALL 被终止，任务按取消/超时语义落终态

#### Scenario: 同名残留
- **WHEN** 启动时存在同名容器（重试场景）
- **THEN** 后端 SHALL 先移除该容器再启动新实例

### Requirement: 技能必须内置于执行器镜像

> 修订记录（2026-11-24）：本要求原为「技能必须以 git 管理的卷装载并支持受控更新」。平台不再对技能做
> 独立的 git 版本管理，改为随镜像发布，故整条替换。

迁移技能（`riscv-migrate`）SHALL 随源码入库，并在执行器镜像构建时复制到镜像内的技能目录；容器启动时 MUST NOT 挂载任何技能卷，后端 MUST NOT 访问技能远端仓库。技能的更新 SHALL 经「修改仓库内技能文件 → 重建执行器镜像」完成，技能版本即镜像版本。

#### Scenario: 离线启动
- **WHEN** 部署环境无法访问技能远端仓库
- **THEN** 迁移执行 SHALL 不受影响，镜像内技能直接可用

#### Scenario: 技能可写
- **WHEN** 技能脚本需要在自身目录下写入运行期文件
- **THEN** 该写入 SHALL 成功（镜像内技能目录不设只读）

#### Scenario: 技能更新
- **WHEN** 需要调整技能内容
- **THEN** 变更 SHALL 提交到本仓库并重建执行器镜像；重建后的容器 SHALL 使用新技能

#### Scenario: 无技能管理接口
- **WHEN** 任何用户访问原技能查询或更新接口
- **THEN** 后端 SHALL 返回未找到，不得提供运行期更新能力

### Requirement: 执行通道必须可配置回退

迁移执行通道 SHALL 支持容器模式与子进程模式配置切换；两模式 SHALL 产出同一套产物合同；切换 MUST NOT 要求数据迁移。

#### Scenario: 回退到子进程
- **WHEN** 配置切换为子进程模式
- **THEN** 迁移任务 SHALL 按既有子进程路径执行，产物与校验行为不变

#### Scenario: 容器执行失败回退验证
- **WHEN** 容器模式执行失败
- **THEN** 运维 SHALL 能切换子进程模式恢复迁移能力，无需代码回滚
