# kb-scanner-knowledge-data-production 变更（arm64 语料）

## ADDED Requirements

### Requirement: 手册语料 SHALL 以 collected-v1 类型登记并保持可再生成

人工从官方手册/文档收集的架构语料（无精确 git revision 可引用）SHALL 冻结语料原件于规则包 `collected/` 目录，以独立转换脚本产出确定性 JSON（排序、去重、规范化），并 SHALL 在知识清单中以 `collected-v1` 类型登记：来源描述含官方 URL、许可、采集日期、语料原件文件名与逐一对应的 SHA-256。

#### Scenario: 手册语料接入
- **WHEN** 维护者把 AArch64 builtin/头文件/平台宏/指令清单放入 `collected/arm64/` 并运行转换脚本
- **THEN** 产物 SHALL 通过 collected 契约校验（单源单族、事实三元组唯一、排序与规范化）
- **AND** 清单条目 SHALL 声明语料原件清单，装载器按 collected-v1 分支装载并把族的标记透传到知识条目

#### Scenario: 语料再生成一致性
- **WHEN** 转换脚本对相同语料原件再次运行
- **THEN** 产出 SHALL 与规则包内已登记产物字节一致
- **AND** 契约测试 SHALL 校验该一致性

#### Scenario: 语料清洗
- **WHEN** 语料包含注释行、截断脏值（如 `__arm_` 前缀）或手册表格字段名（非指令助记符）
- **THEN** 转换脚本 SHALL 按公开的清洗规则丢弃这些行
- **AND** 丢弃规则 SHALL 在脚本文档中说明，不得静默改变合法条目

### Requirement: 知识事实的架构族 SHALL 覆盖 x86 与 arm64

事实条目模型 SHALL 接受 `architectureFamily: "x86" | "arm64"`，语义范围 SHALL 按族命名（`<family>-only`/`<family>-toolchain`/`migration-sensitive`）。装载器 MUST 按来源声明的族装载（不得无条件归并为 x86），同一 fact_kind + 族 + 规范值在不同族下 SHALL 是不同知识条目。

#### Scenario: 两族并存
- **WHEN** 清单同时登记 x86 与 arm64 来源
- **THEN** 快照 SHALL 同时含两族条目且族标记正确
- **AND** 检测层按扫描声明的源架构过滤，族间不得串扰

## MODIFIED Requirements

### Requirement: Generated facts 必须从版本固定的权威输入确定性产生
git-commit 溯源合同继续适用于 `generated-v2`；本变更新增的 `collected-v1` 是并列的来源类型（手册语料溯源），不放宽 `generated-v2` 的 revision 合同。
