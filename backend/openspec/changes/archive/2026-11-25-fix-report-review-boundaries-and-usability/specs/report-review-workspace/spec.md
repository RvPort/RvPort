## ADDED Requirements

### Requirement: 问题关键词搜索必须覆盖公开展示正文
后端 SHALL 按白名单提取标题、中英文原始证据说明、AI 依据、人工说明/修复原因、当前排除说明与公开相对路径，使用大小写无关字面搜索；MUST NOT 搜索隐藏 ID、哈希、凭据或内部元数据。

#### Scenario: 仅正文命中
- **WHEN** 关键词只出现在 originalSnapshot、aiReview 或 manualDetails 的任一展示正文字段
- **THEN** SHALL 返回相应条目，英文大小写不影响匹配；null 或未知结构安全忽略，不通过整个 JSON 字符串化扩大范围。

#### Scenario: 分页及统计一致
- **WHEN** 关键词结合状态/来源/文件过滤或跨页
- **THEN** SHALL 先对完整集合过滤再排序分页，filteredTotal/filteredSummary 与匹配集合一致，globalSummary 不受影响，空白关键词等同无筛选，超长关键词返回 400。

#### Scenario: 预览与复核区共享查询
- **WHEN** 生成前预览复用搜索逻辑
- **THEN** SHALL 搜索实际存在的扫描展示正文，不伪造 AI/人工字段，不因为 DTO 投影遗漏使同一扫描正文无法命中。

### Requirement: 固定源码全文搜索必须有界且保持版本权限
后端 SHALL 提供固定报告单文件搜索，一次请求校验绑定制品内容后返回有限的字面匹配位置；继续保留 context 每次最多 500 行的浏览契约。

#### Scenario: 未加载行和多次命中
- **WHEN** 关键词位于前端未加载行、同一行多个位置或第 500/501 行边界之外
- **THEN** SHALL 按原文件绝对行号、1-based UTF-16 列返回稳定排序的非重叠匹配，结束列不含终点，截取预览不改变绝对坐标。

#### Scenario: 限制和截断
- **WHEN** 指定合法 limit 或实际命中超过上限
- **THEN** SHALL 返回不超过 limit 的 matches、returnedCount 与准确 truncated，限制片段长度且不截断代理对，不虚构完整命中数；非法、空白、跨行或超长查询及超范围 limit 返回 400 REPORT_REQUEST_INVALID，不支持正则。

#### Scenario: 无匹配和源码不可用
- **WHEN** 校验成功但无匹配，或制品缺失、内容变化、编码/大小不满足条件
- **THEN** SHALL 前者 available=true 且空结果，后者 available=false 且双语原因，不返回未经完整校验的部分命中或伪装无匹配。

#### Scenario: 越权或当前源码已变化
- **WHEN** fileKey 不属于报告、用户无访问权限或项目切换 active 源码
- **THEN** SHALL 越权按既有隐藏规则拒绝；合法历史查询只使用报告固定源码，不能读取当前工作目录或客户端指定路径。
