## ADDED Requirements

### Requirement: 报告历史必须提供对应固定源码的展示元数据
后端 SHALL 在列表和详情返回可选 sourceVersionLabel/sourceGitCommit 及 sourceMetadataOrigin，所有值对应报告绑定的 sourceRevisionId，不改变源码身份与 Context。

#### Scenario: 新报告固定展示元数据
- **WHEN** 创建基于上传标签或 Git 版本的报告
- **THEN** SHALL 保存该版本展示快照并在列表、详情一致返回，项目后来更换源码不改变此报告显示身份。

#### Scenario: 历史缺快照
- **WHEN** 历史报告无展示快照
- **THEN** SHALL 可批量关联同 sourceRevisionId 的持久元数据，标记 REVISION_RECORD；不存在时字段为 null、来源 UNAVAILABLE，保留 sourceRevisionId 回退，不逐条 N+1 查询、不猜测当前版本、不改写旧下载。

### Requirement: 证据原因必须提供一致且可追溯的双语展示
后端 SHALL 使用共享来源原因码解析器生成 code/message/messageEn/catalogVersion，独立于原始证据和 evidenceHash；新报告的展示快照由工作区和新导出共同消费。

#### Scenario: 已知和未知原因
- **WHEN** 原始证据包含常见原因码或未收录原因码
- **THEN** SHALL 已知码返回经来源语义核对的中英文说明；未知码保留原码并给出通用未知解释，不捏造问题结论，原始证据始终可追溯。

#### Scenario: 页面与导出一致
- **WHEN** 查询或下载新报告及其新确认版本
- **THEN** SHALL 使用同一固定 displayEvidence 与字典版本，保持人工决定、AI 结论、风险、证据完整性和继承身份不因翻译变化而改变。

#### Scenario: 历史不可变制品
- **WHEN** 读取尚无双语快照的历史报告
- **THEN** SHALL 可以附带标记 READ_TIME 的解释，但不冒充生成时事实、不修改原下载字节、不通过更新字典改写历史证据或补造复核数据。
