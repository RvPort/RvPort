## MODIFIED Requirements

### Requirement: 自动化测试 readiness 必须依赖构建验证
项目级 readiness 在 `stageId=test` 时 SHALL 返回自动化测试所需的构建验证前置条件。

#### Scenario: 构建验证已成功
- **WHEN** 当前 Context 下存在成功的双环境构建验证
- **THEN** `BUILD_VALIDATION` prerequisite SHALL 返回 `SATISFIED`
- **AND** `referenceId` SHALL 指向构建验证记录或批次

#### Scenario: 构建验证缺失
- **WHEN** 当前 Context 下没有双环境构建验证记录
- **THEN** `BUILD_VALIDATION` prerequisite SHALL 返回 `MISSING`
- **AND** `reasonCode` SHALL 为 `BUILD_VALIDATION_MISSING`

#### Scenario: 构建验证失败
- **WHEN** 当前 Context 下最近一次双环境构建验证失败
- **THEN** `BUILD_VALIDATION` prerequisite SHALL 返回 `FAILED`
- **AND** `reasonCode` SHALL 为 `BUILD_VALIDATION_FAILED`

#### Scenario: 单侧成功不足
- **WHEN** BOTH 仅一端成功，另一端缺失、运行中或失败
- **THEN** BUILD_VALIDATION SHALL 不为 SATISFIED，返回双方状态和原因，不选成功一侧代替完整验证。

#### Scenario: 旧测试不能充当构建前置
- **WHEN** 存在历史测试成功但当前源码/环境的对应构建验证不可用
- **THEN** SHALL 仍按当前双端构建判断，禁止使用 test run ID 或测试状态填充 BUILD_VALIDATION。

#### Scenario: 分析阶段没有前置构建
- **WHEN** 没有 SOURCE_ONLY，但当前 BOTH 已成功且对应测试配置有效
- **THEN** SHALL 允许自动化测试，保持构建验证在测试之前，验证项目、源码、环境和所关联构建的成功状态。
