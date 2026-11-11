## Purpose

定义平台模型配置迁入 admin 个人名下的归属、历史审计、在途任务处理、回滚边界及本地 Docker 验收要求。

## Requirements

### Requirement: 旧平台配置必须迁入指定 admin 且保留审计
迁移 SHALL 将升级前所有平台 profile、加密密钥和阶段绑定归属到 username 精确为 `admin` 的超级管理员账号，历史测试结果随 profile 归属；MUST NOT 覆写 created_by、updated_by、测试操作者、原结果与时间。

#### Scenario: 历史创建者为空或其他人
- **WHEN** 旧 profile 的 created_by 为空或非 admin
- **THEN** 新 owner SHALL 仍为 admin，原审计与密钥密文字节 SHALL 保持不变
- **AND** 其他普通用户 SHALL 不自动获得任何平台配置或密钥副本

#### Scenario: admin 不存在或不是超级管理员
- **WHEN** 迁移不能找到唯一、角色符合要求的精确 admin 账号
- **THEN** 迁移 SHALL 原子失败并给出可诊断原因，不创建账号、不改密码、不选择其他管理员

#### Scenario: admin 被禁用
- **WHEN** admin 存在且是超级管理员但账号被禁用
- **THEN** 归属迁移 SHALL 不改变禁用状态，执行入口 SHALL 继续拒绝该账号调用模型

#### Scenario: 迁移后重复启动
- **WHEN** 迁移已经成功且普通用户已建立个人配置后应用再次启动
- **THEN** 后端 MUST NOT 再次归属这些配置到 admin，不能改写密钥或审计历史

### Requirement: 升级必须有界处理旧在途任务
发布方案 SHALL 停止旧版本接收和领取 AI 任务，等待运行中任务排空，对无法确定可信配置主体的旧排队任务记录受控终态；MUST NOT 在迁移后让旧任务默认使用 admin 凭据。

#### Scenario: 旧消息在新 Worker 到达
- **WHEN** 旧消息引用没有可信配置主体和执行引用的遗留任务
- **THEN** 新 worker SHALL 拒绝上游调用并记录有界失败及重新触发指引，保留原任务审计

#### Scenario: 需要回滚个人化版本
- **WHEN** 个人数据已产生后进行本地回滚演练
- **THEN** 方案 SHALL 备份新数据并恢复匹配的升级前数据库和镜像，明确恢复点之后写入的留存与损失
- **AND** MUST NOT 直接用全局旧应用读取多用户配置或宣称自动无损回滚

### Requirement: 本变更必须完成后端本地 Docker 质量验收
所有依赖安装、生成、构建、单元测试、覆盖率与运行验收 SHALL 在本地 Docker 中完成；远程 dev/test MUST NOT 被修改或用于测试。本 change SHALL 仅修改 backend，前端覆盖率例外保持且不新增前端实施任务。

#### Scenario: 单元测试与覆盖率
- **WHEN** 验收本次实现
- **THEN** 后端全部自有可执行代码及每个完整受影响模块的行、分支覆盖率 SHALL 分别至少 90%，保留更严格门槛
- **AND** SHALL 纳入未加载源码及自有资源脚本，明确第三方、生成代码排除项，不缩小分母或仅统计变更行
- **AND** SHALL 保留可重复命令、测试结果、covered/total、统计范围及原始报告路径；不支持的指标明确标为不可用

#### Scenario: 完整 Compose 验收
- **WHEN** 后端单元测试和覆盖率通过
- **THEN** SHALL 从仓库根目录 `.` 依次且限制资源构建当前源码全部默认 Compose 服务，并一次 `docker compose up -d --wait` 启动独立本地 stack
- **AND** SHALL 使用独立环境配置、网络、卷和端口，以本地模拟上游及假凭据验证健康、日志、权限、触发者、派生与恢复 API 流程，不连接真实数据库或 SSH
- **AND** 默认前端服务仅随完整 Compose 构建启动，不代表个人模型页面已经适配

#### Scenario: 未达质量门槛
- **WHEN** 必需测试失败、覆盖率不足或 Compose 验收未完成
- **THEN** 相关任务 SHALL 保持未勾选，不提示可归档；大规模无关历史缺口 SHALL 报告差距并请求扩大范围，不自行改造或降低标准
