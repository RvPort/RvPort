## MODIFIED Requirements

### Requirement: 评估报告生成必须固定有效分析候选
后端 SHALL 复用既有评估报告阶段和 REPORT_GENERATE，按 report-ai-review 的四类有效性和依赖筛选规则创建不可变 manifest；MUST NOT 先要求人工 CONFIRMED。

#### Scenario: 输入集合固定
- **WHEN** 创建报告事务成功
- **THEN** SHALL 固定 project/source revision、分析 run/result/context、所有候选证据与实际 AI 子集；worker 不重新选择运行或回查可变个人状态决定输入。

#### Scenario: 专项和依赖证据
- **WHEN** 输入包含专项 Finding 或依赖问题
- **THEN** SHALL 保存风险/原 verdict、双语证据、validation plan、多 occurrence 和依赖匹配证据；待核实候选保持不确定，不因用户选择升级。

#### Scenario: 新数据边界
- **WHEN** 初始化并生成报告
- **THEN** SHALL 只创建四类新来源快照，不兼容旧 SBOM_RESULT 或 MIGRATION_INPUT_REF 制品。

#### Scenario: 部分分析与覆盖快照
- **WHEN** 固定普通或 AI 报告输入
- **THEN** SHALL 同时固定来源运行、taskStatus、analysisStatus、resultAvailable、inputOrigin、实际模式、coverage、assumptions、missingEvidence 和 limitations；缺口与问题候选分开，不凭缺口自动制造源码缺陷。

### Requirement: 报告 readiness 必须表达来源模式与当前清单
后端 SHALL 统一报告 readiness、stage readiness、overview 和 latest 的有效性；报告来源资格与个人 AI 资格分开。

#### Scenario: 生成前读取
- **WHEN** 用户查询 readiness 或 input-preview
- **THEN** SHALL 返回 active 源码、四类运行状态/有效性/版本、来源候选数、继承排除数、实际 AI 数、模式可用性及稳定原因，预览分页且无写入。

#### Scenario: 没有合法发布分析
- **WHEN** 存在源码但没有有效合法发布产出
- **THEN** SHALL canGenerate=false；不能因 INCLUDED_FINDINGS 默认 SATISFIED 而允许生成。

#### Scenario: 一类成功零问题
- **WHEN** 只有一个当前有效且合法发布的运行（含 PARTIAL）且候选数为零
- **THEN** SHALL canGenerateStandard=true，AI 资格独立，不能返回无确认输入阻塞；来源事实不得伪装成未运行。

#### Scenario: 当前与最近任务
- **WHEN** 最新生成排队、失败或历史成功
- **THEN** SHALL 单独返回 latestGeneration 和 currentReport，不遮蔽仍有效当前报告；currentFixList 必须来自有效确认，不是问题总数。

### Requirement: 报告任务终态必须可靠且成功发布受控
后端 SHALL 复用任务租约和独立终态写入，不以部分 AI 结果或制品一半保存认定成功；发布与当前指针原子关联。

#### Scenario: 运行失败或取消
- **WHEN** 读取快照、AI、渲染或保存必要制品失败/取消
- **THEN** SHALL 对齐报告与任务失败/取消，脱敏诊断不随主事务回滚，旧有效报告保持；无自动普通降级。

#### Scenario: 有效空输入
- **WHEN** 至少一类有效合法发布分析（含 PARTIAL）但候选集合为空
- **THEN** SHALL 保存可识别的空 manifest 和双格式报告，不因集合为空失败；仍需明确确认空清单。

#### Scenario: 当前上下文已变化
- **WHEN** 旧 manifest 生成完成
- **THEN** SHALL 成功结果仅作历史，不能切换当前指针；新报告接替时须复核旧草稿 reviewRevision。

## ADDED Requirements

### Requirement: 报告必须独立表达证据完整性和最终处置
报告 SHALL 固定未执行、技术失败、已发布部分结果、范围内零发现及无法判定这些不同事实，HTML/JSON、历史与确认清单下载 SHALL 使用同一固定证据视图。

#### Scenario: 分析分母未知
- **WHEN** 只有内部源码枚举且不能证明真实构建全部 TU
- **THEN** SHALL 分别返回 knownSourceFileCount、plannedTuCount、analyzedTuCount、failedTuCount、unsupportedTuCount、omittedTuCount 及 denominatorKnown=false，不混用文件/TU 计数或声称完整构建覆盖。

#### Scenario: AI 或人工收敛
- **WHEN** 用户排除全部问题或确认空清单
- **THEN** SHALL 保存该版本证据限制和人工确认，不能将零待修复解释为完整验证或全项目安全；后续构建和测试仍按真实状态执行。

#### Scenario: 历史下载
- **WHEN** 新构建、源码或分析发布后下载旧报告或指定确认
- **THEN** SHALL 返回原版本覆盖和限制，不根据当前环境补写旧报告的验证依据。

### Requirement: 报告必须固定逐语言范围和失败来源诊断
报告 SHALL 保存各来源规范化语言、源/目标架构、选择范围、逐语言覆盖及实际输入摘要，区别未选择、不支持、排除、失败与已分析；AI SHALL 获取同一固定事实，不补写其他架构或语言的验证依据。

#### Scenario: 多语言工程部分覆盖
- **WHEN** KB有多语言结果而专项仅分析C/C++
- **THEN** SHALL 分别展示各来源范围，不能以KB文件数作为专项已分析TU分母，不能将未支持语言解释为零风险。

#### Scenario: 失败尝试和旧有效来源并存
- **WHEN** 最近尝试内存超限而同选择身份仍有旧有效结果
- **THEN** SHALL 固定实际选中旧运行ID，并单列最新尝试失败诊断，不把二者混成同一成功运行；仅失败无合法产出时拒绝生成。
