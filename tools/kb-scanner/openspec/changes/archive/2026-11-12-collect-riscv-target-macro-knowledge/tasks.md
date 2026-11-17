# Tasks

- [x] 1. 知识契约层：schema.py `ARCHITECTURE_FAMILIES` 增 riscv、`SEMANTIC_SCOPES` 增 riscv-only；`ArchitectureFamilyLiteral`/`SemanticScopeLiteral` 别名统一三处 Literal（GeneratedFactV2Model/CuratedEntryModel）；`source_arch` 取值集与 `DEFINITIONS_BY_FAMILY` 不动
- [x] 2. 语料冻结：collected/riscv/riscv.macro（203 项，源 rvport-skills/riscv-scan）；collected/reserve/ 两份 Rust 表（哈希钉死、不进 manifest）
- [x] 3. collector：tools/collect_riscv_entries.py（模板 collect_aarch64_entries.py，确定性渲染 generated/riscv-collected-entries.json）
- [x] 4. manifest 登记：knowledge-manifest.json 增 collected-riscv-entries 源（version 2026.11-riscv.1）、scanner-manifest.json 哈希链同步；validate-rules 通过
- [x] 5. 消费点：evidence_graph._branch_role 增目标族预检 _target_family_role（先于 _ROLE_PATTERNS 词形正则，防 "__riscv_xlen" 被裸 "riscv" 子串截胡）；branch_knowledge 过滤器放行 riscv platformMacros
- [x] 6. 测试：schema 单测（riscv+riscv-only 合法/未知族仍拒）、生产契约（计数 203/SHA256/再生成一致/储备钉哈希）、装载契约（单源单族/DEFINITIONS_BY_FAMILY 无 riscv 键）、端到端 test_riscv_macro_scan.py（riscv 分支识别+不误报+negated unknown+源族行为不变）
- [x] 7. 基线同步：open-source-regression-baseline.json 顶层与 4 fixture v2.0 摘要（新源进入 scan.knowledgeSources）
- [x] 8. 文档：docs/knowledge.md + knowledge.zh-CN.md 增 riscv 目标族与储备数据段落（双语）
- [x] 9. 容器级验证：kb-scanner-test 容器内全量测试与覆盖率门禁
- [x] 10. 归档：验证完成后按流程归档本 change
