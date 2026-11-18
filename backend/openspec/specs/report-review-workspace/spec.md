## Purpose

定义不可变报告事实、持久复核草稿和不可变确认清单的职责，覆盖统一单次及批量处置、框选源码人工新增、完整集合统计、空清单和下游确认版本消费。

## Requirements

### Requirement: 报告事实草稿和确认快照必须分离
后端 SHALL 固定原始报告及 AI 结果，独立持久化草稿、追加操作审计与不可变确认；新扫描项按本次模式与 AI 结论初始化，最终清单由用户提交。

#### Scenario: AI 判断存在问题
- **WHEN** AI 返回 CONFIRMED_ISSUE
- **THEN** SHALL 在无可继承人工决定时初始化为 TO_FIX，草稿待修复计数相应增加。即使待复核数为零，也须等用户提交才创建最终清单。

#### Scenario: 复核结论不确定或使用普通模式
- **WHEN** 无可继承人工决定，AI 返回 SUSPECTED_FALSE_POSITIVE 或 INSUFFICIENT_EVIDENCE，或选择普通模式
- **THEN** SHALL 初始化为 PENDING_REVIEW，保留扫描事实，不自动排除。

#### Scenario: 刷新恢复
- **WHEN** 用户修改草稿后刷新或重新进入
- **THEN** SHALL 读取服务端同一报告最新草稿及全局计数；原始报告下载不被改写。

#### Scenario: 人工新增
- **WHEN** 用户显式框选并提交待修复项
- **THEN** SHALL 标记 MANUAL、TO_FIX，记录 actor 和原始文本，不写成扫描器 Finding。

### Requirement: 复核必须使用统一原子单次批量处置
后端 SHALL 用同一 PATCH items 数组处理 INCLUDE、EXCLUDE、RESTORE，携带 expectedVersion 与幂等标识，完整校验后一次提交。

#### Scenario: 单次与批量
- **WHEN** items 为 1 条或多条
- **THEN** SHALL 使用相同校验与响应，重复 ID/非法项或跨报告项使整批失败，无部分写入；一次有效批次仅增加一个草稿版本。

#### Scenario: 恢复排除
- **WHEN** 执行 RESTORE
- **THEN** SHALL 恢复最近有效排除前状态，无法确定则 PENDING_REVIEW；保留审计，不复活旧确认资格。

#### Scenario: 并发冲突
- **WHEN** 基于旧版本修改或确认
- **THEN** SHALL 返回 409 与安全当前版本，且不写任何状态或确认快照。

### Requirement: 框选新增必须绑定已知源码和服务端片段
后端 SHALL 支持报告固定版本已知文件（含零发现文件）的框选新增，校验 fileKey、内容 hash、范围、描述和修复原因，服务端提取片段。

#### Scenario: 合法多行选区
- **WHEN** 提交 1-based 行与 UTF-16 列、终点不含的非空选区
- **THEN** SHALL 保存相对路径、range、内容/片段 hash、片段、说明和 actor；新增为人工待修复。

#### Scenario: 伪造或失效选区
- **WHEN** hash 不一致、越界、空选区、非法 UTF-16 边界、文件不属报告或源码不可用
- **THEN** SHALL 拒绝，无人工项写入，不接受客户端代码替代源码。

#### Scenario: 人工项移除
- **WHEN** 用户排除人工新增项
- **THEN** SHALL 保留条目和操作审计，并允许统一接口恢复，不物理删除证据。

### Requirement: 文件筛选与完整清单必须独立
后端 SHALL 仅把绑定版本 manifest 中已知源码放入文件树，完整问题列表包含依赖和未定位项；服务端统计完整集合。

#### Scenario: 未知路径与无位置依赖
- **WHEN** 问题无法映射已知源码
- **THEN** SHALL 出现在全局列表并返回定位原因/依赖证据，不伪造树节点。

#### Scenario: 零发现文件
- **WHEN** 浏览已知文件且无扫描问题
- **THEN** SHALL 可读取合法区间并新增人工项。

#### Scenario: 分页过滤
- **WHEN** 使用来源、文件、状态或搜索条件
- **THEN** SHALL 返回稳定分页和 filteredTotal/全局摘要各自语义，确认不得只检查当前页或过滤结果。

### Requirement: 清单确认必须不可变且支持零项
后端 SHALL 原子验证当前报告/Context、草稿版本和全局待复核数，固定确认快照；同报告同草稿版本确认幂等。

#### Scenario: 仍有待复核
- **WHEN** 任何页存在 PENDING_REVIEW
- **THEN** SHALL 拒绝确认并返回数量，不创建确认记录。

#### Scenario: 空清单确认
- **WHEN** 没有待复核且 TO_FIX 为零
- **THEN** SHALL 保存 CONFIRMED_EMPTY，明确无需修复，与 UNCONFIRMED 区分。

#### Scenario: 确认后修改
- **WHEN** 已确认草稿实际发生变化
- **THEN** SHALL currentConfirmationId 清空并返回 NEEDS_CONFIRMATION，原快照/时间/操作者保留，必须重新确认。

### Requirement: 下游必须消费明确有效的确认版本
后端 SHALL 通过统一 resolver 供工作流和修复相关执行使用确认 ID，不以原报告问题数、旧 references 或浏览器数据代替。

#### Scenario: 当前未确认或已过期
- **WHEN** 查询或创建依赖清单的后续流程
- **THEN** SHALL 返回原因并阻止以无效清单创建执行，不能回退旧确认；通用源码上传及独立构建测试不被无条件禁用。

#### Scenario: 任务使用清单
- **WHEN** 后续任务创建后草稿或源码改变
- **THEN** SHALL 任务记录固定确认引用，启动前重新校验有效性；历史结果仍可追溯但不冒充当前结果。

#### Scenario: 空清单与验证链
- **WHEN** 当前确认为空
- **THEN** SHALL 表达无需清单修复，但不能把构建/测试/比较标为已完成。
