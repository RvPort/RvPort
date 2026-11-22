# kb-scanner-classification-and-assessment 变更（missing_riscv_family_member 归类）

## MODIFIED Requirements

### Requirement: 缺口类观察 SHALL 归 actionable_non_blocking 并计入 missing_target 维度

分类策略的缺口集合 SHALL 包含 `missing_riscv_family_member`（与 `missing_riscv_selection_path`/`riscv_test_coverage_gap` 同归 `actionable_non_blocking` / migration-or-test-gap）；迁移评估的 `target_reachability` 维度 SHALL 把 `missing_riscv_family_member` 计入 missing_target（"issue" 判定）；投影策略的 portability-gap 集合与 V1 `_source_kind` 映射（→ build）SHALL 同步覆盖该 kind。

#### Scenario: 升格条目归类
- **WHEN** BUILD.SELECTION 检测器产出 missing_riscv_family_member 观察（family_gap 升格链）
- **THEN** 分类 SHALL 返回 actionable_non_blocking，评估的 target_reachability SHALL 为 issue，投影 findingType SHALL 为 portability_gap 维度下的非阻断告警
