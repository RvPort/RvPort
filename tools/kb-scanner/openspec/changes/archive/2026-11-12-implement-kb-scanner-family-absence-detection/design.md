# Design

## 上下文

architecture_family 分析此前只被动记 candidateAbsence（"范围内未见候选"），不推导缺失、不产 Finding——结构缺失（`src/arch/x86_64/` 有而 riscv64 无）对产品和 AI 迁移是盲区。riscv-scan 的 arch_missing.py 有该能力但为无词边界子串匹配（实测误报：alarm.c→al_riscv64.c、foo_arm64.c→foo_64_riscv64.c），且"匹配即报"与五态合同冲突。

## 决策

### D1: 推导消费分桶模板，绝不做二次字面替换

`derive_target_path` 直接读 `family["template"]`（grouping 已按词边界剥离 token 的产物）：`filename:src/foo.c` → `src/foo_riscv64.c`、`directory:arch/{architecture}/impl.c` → `arch/riscv64`。这是误报防线的根——riscv-scan 的两类误报都源于它自己重新做子串替换（ARCH_DETECT_RE 无边界、ARCH_STRIP_TOKENS 拆错 arm64）。

### D2: 两级输出（词法候选 → 构建选中证明升格）

纯词法缺失只进 extensions 候选（candidateMissingMember/directoryAbsence，status=candidate、scope=authorized-file-list）；仅当成员绑定证明 selectionStatus=selected 且无 riscv/generic 成员时升格 family_gap 实体 + selects 关系 + complete ProofPath。理由：文件名相似性本身证据太弱（五态合同要求 unknown 不当结论），构建选中把"该家族确实被编译"钉死后才够 Finding 强度——比 riscv-scan 的盲报强一代。

### D3: 升格链完全镜像 selection_gap 先例（issue 字符串驱动）

metadata.issue=missing_riscv_family_member 的关系 → BUILD.SELECTION 检测器分支 → policy 归 actionable_non_blocking → assessor missing_target → suggestions family_gap 双语文案。零新增检测器/分类器类型，V1 映射一处补齐。

### D4: 升格引用悬空防御

gap 关系的 evidence_ids 只取 facts.evidence 实存 ID；成员绑定 relationIds 在 base.relations 中缺失时降级为仅候选并记 family-absence-escalation-skipped 问题码——绝不发悬空引用（BuildSelectionDetector 的 invalid-relation-reference 校验会让整段 FAILED）。

### D5: 防线集合

歧义家族不推导；riscv/generic 成员存在即压制（可移植逃生口）；arm64 源对称（角色判定全经 paths._tables(source_arch) 折叠，目标恒 riscv64）；absence 预算截断记码不静默。

## 风险与缓解

- riscv-scan 黑盒误报零复发：alarm/harmony/ppc64le/exclude 四件套进组件测试永久回归。
- 快照基线：family extensions 新字段改变 fixtures 摘要——bilingual expected-pairs 同步登记 family_gap 文案对。
