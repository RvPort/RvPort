# Tasks

- [x] 1. 知识契约层：schema.py 放宽架构族/语义范围 Literal、新增 FACT_KIND_LITERALS、CollectedSourceDescriptorModel/CollectedFactsModel/CollectedManifestSourceModel
- [x] 2. 装载层：provider.py `_merge_entries` 族参数化、collected-v1 装载分支、`_collected_as_v2` lint 适配
- [x] 3. 语料入库：collected/arm64/ 四清单冻结、tools/collect_aarch64_entries.py 转换（清洗+映射+排序+确定性渲染）、generated/arm64-collected-entries.json
- [x] 4. manifest 登记：knowledge-manifest.json 新增 collected 源、scanner-manifest.json 哈希同步
- [x] 5. source-arch 贯通：ScanRequest/ScanContext 字段、CLI --source-arch、StagedContextFactory 透传
- [x] 6. 检测层：definitions.py ARM64_DEFINITIONS + DEFINITIONS_BY_FAMILY、KnowledgeIndex.by_kind_and_family、match_fact 族参数、local.py 按族选择/Disposition/编译旗标元数据、candidate_coverage 族词元
- [x] 7. 分析器/投影参数化：evidence_graph（分支角色/替代关系/族知识匹配/启发式词元）、build_selection/cmake_selection/test_selection 过滤、detectors/build_selection、evidence_kinds.intrinsic_symbols、chain/mapper 构造器、classifier risk/policy 扩 arm64 种类、suggestions _DEPENDENCY_TYPES
- [x] 8. 输出契约：Scope.domain 枚举、Summary.readiness arm64_only、V2SummaryReducer 族注入、KnowledgeSourceView collected kind、scan-result-v2.schema.json 同步
- [x] 9. 架构家族分析：paths.py 角色词表按源架构折叠、grouping.py 建族条件、analyzer.py 透传
- [x] 10. 后端：KbScanRequest/KbScanInput/RvPortProperties.KbScan sourceArch、KbScanService 归一化+快照、KbScanRuntimeService --source-arch、application.yml RVPORT_KB_SCAN_SOURCE_ARCH、5 处测试构造点同步
- [x] 11. 测试：schema/provider/matcher arm64 单测、test_arm64_scan.py 端到端（samples/arm64）、契约测试更新（detection/production/cutover 基线 + collected 再生成测试）、后端 KbScanServiceTests 归一化测试
- [x] 12. 本机验证：pytest 全量（仅预存 clang 哈希 3 处失败，与本变更无关）、validate-rules 通过、arm64/x86 样本双向扫描族隔离验证、后端 KbScan* 65+34 测试通过
- [ ] 13. Docker 级验证：kb-scanner-test 容器内全量测试与覆盖率门禁、后端测试容器、compose 全栈冒烟（arm64 项目建项→扫描→查看报告）
- [ ] 14. 归档：验证完成后按流程归档本 change
