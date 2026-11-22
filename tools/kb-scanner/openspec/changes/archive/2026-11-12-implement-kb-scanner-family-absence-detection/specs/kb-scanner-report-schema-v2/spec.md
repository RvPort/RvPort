# kb-scanner-report-schema-v2 变更（家族缺失候选投影词汇）

## MODIFIED Requirements

### Requirement: 架构家族扩展 SHALL 透传缺失候选且建议词汇覆盖 family_gap

`org.rvport.kb-scanner.architecture-family` 命名空间的投影 SHALL 透传家族级 `candidateMissingMember`（derivedTargetPath/derivationRule/anchorPath/status/scope）与目录级 `directoryAbsence`（仅 report 级，不进单 finding 级）；HTML 摘要 SHALL 渲染缺失候选行。双语建议词表 SHALL 新增 `family_gap` 文案（声明 authorized-file-list 范围限定语义，不代表全项目缺失），`missing_riscv_family_member` findingType 经其映射取建议。schema 结构零变化（extensions 是自由命名空间，findingType 是自由字符串）。

#### Scenario: 建议映射
- **WHEN** missing_riscv_family_member Finding 投影为 actionable_non_blocking
- **THEN** 建议 SHALL 取 family_gap 双语文案（zh 含"限于授权文件清单"、en 含 "limited to the authorized file list"）
