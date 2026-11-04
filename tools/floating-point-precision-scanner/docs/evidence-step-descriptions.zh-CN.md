# 浮点证据步骤双语说明

[English](evidence-step-descriptions.md) | 简体中文

工具 0.5.1 保持公开结果协议 `schemaVersion: 2.0`，为每个 `evidenceChain.steps[]` 增加 `title/titleEn/reason/reasonEn`。标题上限 512 字符，说明上限 4096 字符，禁止空白字符串。严格使用旧 Schema 的消费者需要同步更新，历史报告不自动补写。

## 描述来源

| 引用 | 说明依据 |
|---|---|
| sourceFact | 事实种类、函数、类型、相对位置和实际运算/调用属性 |
| irFact | opcode、函数、结果/操作数类型、fast-math flags、调试位置 |
| binding | 匹配方法、置信度和对应关系 |
| profile | 目标 triple、ABI、long double 布局及已声明浮点能力 |
| buildContext | context_id、目标 ABI 和声明的构建能力，不复制命令 |
| compileOption | 本次规范化后的有效浮点选项 |
| assessment | 规则、评估状态及 reasonCode；特殊规则给出对应限制 |
| conclusion | 当前 finding 的现有中英文标题与结论 |

AST 的累加不等于 LLVM 已执行重排；profile 不等于实机测试。缺引用输出 `step_description_unavailable`；存在歧义的源码输出 `source_semantics_ambiguous`；未完成评估输出 `assessment_not_complete`。步骤为 unknown，链为 partial，保留可审计 break。

## 稳定性与安全

每次扫描只建立一次类型化索引，生成文案不调用外部服务、不重复读源码。索引不跨扫描缓存；已有事实缓存继续按版本与内容键管理，新结果始终重新投影。Finding/step/edge 身份、引用关系和风险等级不因文案变化而重算；整个结果摘要正常包含证据链变化。

只摘要允许的事实属性，安全相对路径可以展示，绝对路径、URL、凭据赋值脱敏。字段按长度截断；现有整体输出预算继续只截断完整 finding。不会把未记录的 IR 属性认定为启用。

后端原样保存新字段并通过现有详情返回，不新增接口、数据库列或前端代码；历史记录依旧可读取，但没有这些新字段，重扫后才生成。归档登记复用现有浮点分析报告逻辑。

## 验证

Linux 容器内执行 `make coverage`，统计包含所有自有 Python 源码（包括未导入模块），整体和受影响模块行/分支均需达到 90%。`step_descriptions` 已纳入门禁。真实 Clang 验证执行 `pytest -q tests/integration`；当前 IR worker 工具版本同步为 0.6.0，需同时重建以避免版本锁校验失败。嵌套数据说明使用 canonical JSON，冷暖缓存及工作区搬迁不改变说明内容。
