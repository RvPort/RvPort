# Tasks

- [x] 1. absence.py：文件级推导（derive_target_path 消费分桶模板）+ 两级输出框架 + FamilyBudget 增 absence_candidates/absence_directories
- [x] 2. 目录级推导 directoryAbsence（仅 extensions；generic 逃生口同文件级）
- [x] 3. 升格链：family_gap 实体/selects 关系/complete ProofPath（引用悬空防御降级）→ detectors/build_selection 分支 → classifiers/policy → assessors/migration → projection/policy（含 V1 映射）→ suggestions family_gap 双语文案
- [x] 4. analyzer.py 挂载（bind 之后）+ families.py 投影扩展（candidateMissingMember/directoryAbsence 透传 + HTML 摘要）
- [x] 5. 单测 14 用例（合成快照）：误报防线、模板推导、逃生口压制、arm64 对称、歧义跳过、升格 proof 复现、悬空降级、预算截断
- [x] 6. 组件测试 9 用例（真 CLI）：误报四件套永久回归（alarm/harmony/ppc64le）、foo_arm64.c→foo_riscv64.c、目录级候选、升格 Finding 全链（actionable/双语/证据链≥2）、未选中不升格、riscv 成员在场压制、V1 不变
- [x] 7. inventory 登记（architecture-family-test-inventory.json 增 absence.py 与新测试套件）
- [x] 8. spec delta + proposal 归档入 openspec change 目录
- [x] 9. 全量回归（本机 pytest）
- [ ] 10. 容器级验证：kb-scanner-test 容器内 90/90 覆盖率门禁
- [ ] 11. 归档
