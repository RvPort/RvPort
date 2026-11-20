## Purpose

定义现有评估报告阶段的普通与 AI 增强生成、四类有效分析快照、风险及来源摘要、报告组织和固定 HTML/JSON 制品，并区分当前报告、最新生成任务、复核草稿、确认清单及只读历史。

## Requirements

### Requirement: 迁移评估报告下载必须受控
后端 SHALL 通过报告下载接口读取对象存储中的报告制品，并 SHALL 防止内部存储位置泄露。

#### Scenario: 下载 HTML 报告
- **WHEN** 用户下载 `format=HTML` 的报告
- **THEN** 后端 SHALL 返回 HTML 文件内容
- **AND** 响应文件名 SHALL 使用安全文件名
- **AND** 后端 MUST NOT 在响应体或响应头中暴露服务器真实路径、bucket 或 object key

#### Scenario: 下载 JSON 报告
- **WHEN** 用户下载 `format=JSON` 的报告
- **THEN** 后端 SHALL 返回 JSON 文件内容
- **AND** 响应文件名 SHALL 使用安全文件名
- **AND** JSON 内容 SHALL 使用生成时保存的报告快照
- **AND** 后端 MUST NOT 暴露对象存储内部位置

#### Scenario: 拒绝不存在的报告制品
- **WHEN** 报告记录存在但请求格式对应的对象存储制品不存在
- **THEN** 后端 SHALL 返回统一业务错误
- **AND** 后端 MUST NOT 返回服务器路径、bucket、object key 或底层存储异常细节

### Requirement: 评估报告页面必须支持历史报告查看
迁移评估报告 API SHALL 支持前端在评估报告页面查看项目过往评估报告，并 SHALL 允许用户基于历史报告进行比较。

#### Scenario: 查询历史评估报告
- **WHEN** 已登录用户调用 `GET /api/projects/{projectId}/reports/evaluation`
- **THEN** 后端 MUST 校验当前用户对项目有访问权限
- **AND** 响应 SHALL 返回该项目的历史评估报告分页列表
- **AND** 列表 SHALL 支持按 `sourceRevisionId`、状态、风险等级和生成时间过滤
- **AND** 列表 SHALL 支持按 `createdAt`、`generatedAt`、`status` 和 `riskLevel` 排序
- **AND** 每条报告 SHALL 返回报告 ID、源码版本摘要、状态、风险等级、摘要、制品摘要和关键时间字段

#### Scenario: 查看报告详情中的比较入口
- **WHEN** 已登录用户调用 `GET /api/reports/{reportId}`
- **THEN** 响应 SHALL 包含报告摘要、源码版本摘要、制品摘要和可比较历史报告提示
- **AND** 若存在上一份可比较报告，响应 SHOULD 包含 latest comparison 入口摘要或 base report 摘要
- **AND** 响应 MUST NOT 返回完整报告正文、对象存储内部 key 或服务器真实路径

### Requirement: 评估报告必须作为报告比较主入口
迁移评估报告 SHALL 将历史差异查看建模为报告比较能力，而不是要求前端进入独立源码对比节点。

#### Scenario: 从评估报告发起历史比较
- **WHEN** 用户在评估报告页面点击与历史报告比较
- **THEN** 前端 SHALL 调用报告比较 API
- **AND** 后端 SHALL 基于报告输入快照创建或复用比较结果
- **AND** 前端 MUST NOT 为主流程调用源码 latest compare API 来完成报告比较

#### Scenario: 历史报告下载不受比较影响
- **WHEN** 用户查看或下载任意历史评估报告
- **THEN** 后端 SHALL 继续使用该报告生成时保存的 HTML 和 JSON artifact
- **AND** 报告比较结果 MUST NOT 修改历史报告 artifact 内容

### Requirement: 评估报告节点必须暴露历史报告比较准备信息
评估报告运行时 SHALL 在报告页聚合数据中提供历史报告比较准备信息，使前端能从评估报告页进入报告比较。

#### Scenario: 存在可比较历史报告
- **WHEN** 当前源码版本存在最新评估报告
- **AND** 同项目存在早于该报告且状态成功的同类型历史报告
- **THEN** readiness 或 overview SHALL 返回可比较历史报告摘要
- **AND** 后端 SHALL 保持实际比较创建由报告比较 API 完成

#### Scenario: 不存在可比较历史报告
- **WHEN** 当前报告没有可比较历史报告
- **THEN** readiness 或 overview SHALL 返回空比较准备信息
- **AND** 后端 MUST NOT 将该情况视为报告生成 blocker

### Requirement: 迁移报告必须按模块文件类型和问题组织代码输入
迁移评估报告 SHALL 将代码类 报告快照 输入组织为“模块、文件、问题类型、具体 finding”四级层级，并 SHALL 保证每条输入只计入一个主模块和一个文件分组。

#### Scenario: 生成代码输入层级
- **WHEN** 报告生成任务读取当前报告源码版本下的代码类 报告快照 输入
- **THEN** 后端 SHALL 为每条输入保存模块、文件、问题类型和 finding 的组织投影
- **AND** 模块 finding 总数 SHALL 等于已组织代码类报告输入总数
- **AND** 同一 finding MUST NOT 因关联多个构建目标而重复计数

#### Scenario: 输入缺少有效文件定位
- **WHEN** 代码类 报告快照 输入缺少路径或有效行号
- **THEN** 后端 SHALL 将输入保留为可审计的未定位条目
- **AND** 后端 MUST NOT 因无法定位而丢弃该输入或使报告生成失败

### Requirement: 模块识别必须使用确定性降级链
后端 SHALL 按显式构建目标、构建产物或链接关系、最近构建描述文件、目录分组、项目根模块的顺序识别代码文件所属模块，并 SHALL 返回识别依据和置信度。

#### Scenario: 构建目标明确关联文件
- **WHEN** 报告绑定的构建上下文明确记录文件属于某个构建目标
- **THEN** 后端 SHALL 优先使用该构建目标作为模块
- **AND** 模块 SHALL 返回稳定的 `moduleKey`、`moduleName`、`basis` 和 `confidence`

#### Scenario: 构建上下文不足
- **WHEN** 文件无法通过构建目标或构建产物关系识别模块
- **THEN** 后端 SHALL 依次尝试最近构建描述文件和目录分组
- **AND** 所有策略均无法形成分组时 SHALL 回退到项目根模块

#### Scenario: 文件关联多个构建目标
- **WHEN** 同一文件被多个构建目标引用
- **THEN** 后端 SHALL 将文件归入确定性的共享模块或主模块
- **AND** 响应 SHALL 提供关联模块标识
- **AND** 该文件中的 finding MUST NOT 被复制计数

### Requirement: 报告代码层级接口必须支持逐级统计和分页查询
后端 SHALL 提供报告代码模块、模块文件、文件问题类型和具体 finding 的独立查询接口，并 SHALL 在每一级返回前端展示直接下级所需的统计。

#### Scenario: 查询模块列表
- **WHEN** 有项目访问权限的用户查询报告代码模块
- **THEN** 后端 SHALL 返回分页模块列表
- **AND** 每个模块 SHALL 包含文件数、finding 数、问题类型数、最高风险、风险分布、类型分布、识别依据和置信度

#### Scenario: 查询模块下的文件和类型
- **WHEN** 有项目访问权限的用户查询指定模块的文件或指定文件的问题类型
- **THEN** 文件响应 SHALL 包含 finding 数、问题类型数、最高风险、风险分布和类型分布
- **AND** 类型响应 SHALL 包含 finding 数、最高风险、首行、末行和符号数
- **AND** 列表 SHALL 支持白名单过滤、排序和分页

#### Scenario: 查询具体 finding 的默认顺序
- **WHEN** 用户未提交 finding 排序参数
- **THEN** 后端 SHALL 按 `lineStart ASC NULLS LAST`、`lineEnd ASC NULLS LAST` 和稳定 ID 升序返回

#### Scenario: 无权读取其他项目报告
- **WHEN** 用户查询无权访问项目中的报告层级数据
- **THEN** 后端 SHALL 拒绝请求
- **AND** 响应 MUST NOT 泄露模块、文件、finding 或源码上下文信息

### Requirement: 报告必须提供文件级联合源码上下文
后端 SHALL 为报告内代码文件提供联合源码上下文，默认覆盖首个纳入报告 finding 之前 20 行至最后一个纳入报告 finding 之后 20 行，并 SHALL 高亮该文件内所有纳入报告的 finding。

#### Scenario: 同一文件存在多个 finding
- **WHEN** 用户查询报告中某文件的联合上下文
- **THEN** 后端 SHALL 返回该文件所有已纳入报告 finding 的元数据
- **AND** 上下文 SHALL 从最早 finding 的起始行向前最多 20 行开始
- **AND** 上下文 SHALL 在最晚 finding 的结束行之后最多 20 行结束
- **AND** 每个代码行 SHALL 返回真实行号、文本和关联 finding 高亮

#### Scenario: 文件边界不足二十行
- **WHEN** finding 距离文件头或文件尾不足 20 行
- **THEN** 后端 SHALL 将上下文裁剪到真实文件边界
- **AND** 后端 MUST NOT 生成不存在的行号或占位代码

#### Scenario: 只包含生成报告时的 finding
- **WHEN** 同一路径在当前输入池中还有 报告快照之外或其他源码版本的 finding
- **THEN** 文件上下文 SHALL 只高亮该报告输入快照中的 固定 finding
- **AND** 后端 MUST NOT 动态混入当前输入池或 active source revision 的其他问题

### Requirement: 文件上下文省略必须结构化表达
当联合上下文跨度超过服务端限制时，后端 SHALL 以 `CODE` 和 `OMITTED` block 返回内容，并 MUST NOT 将省略提示伪装成源码文字。

#### Scenario: 远距离 finding 之间产生省略区间
- **WHEN** 同一文件的 finding 窗口之间存在超过阈值的未命中行
- **THEN** 响应 SHALL 为真实源码返回 `kind=CODE` block
- **AND** 响应 SHALL 为省略范围返回 `kind=OMITTED` block
- **AND** `OMITTED` block SHALL 包含起止行、行数、稳定原因码和 `loadable` 状态

#### Scenario: 展开可加载省略区间
- **WHEN** 用户使用文件上下文 API 请求 `OMITTED` block 对应的合法行范围
- **THEN** 后端 SHALL 返回该范围内的真实代码行
- **AND** 返回内容 SHALL 继续使用报告绑定的源码版本

### Requirement: 文件上下文必须保持历史版本完整性和路径安全
报告文件上下文 SHALL 绑定报告的不可变 `sourceRevisionId`、规范化相对路径、内容哈希和行号范围，且 SHALL 通过对象存储制品或等价不可变快照提供内容。

#### Scenario: 源码更新后查看旧报告
- **WHEN** 项目 active source revision 已更新且用户查看旧报告文件上下文
- **THEN** 后端 SHALL 返回旧报告生成时绑定的源码内容和哈希
- **AND** 后端 MUST NOT 回退读取当前 active source revision

#### Scenario: 路径逃逸或文件不可读取
- **WHEN** 报告输入路径逃逸源码根、文件缺失、文件为二进制、文件过大或行号失效
- **THEN** 后端 SHALL 返回 `contextAvailable=false` 和稳定原因码
- **AND** 后端 MUST NOT 返回宿主机绝对路径、对象存储内部位置或底层异常细节
- **AND** 该情况 MUST NOT 使整份报告生成失败

### Requirement: HTML 和 JSON 报告必须共享组织口径
后端 SHALL 使用同一版本化报告视图模型生成 HTML 与 JSON 报告，并 SHALL 包含执行摘要、代码模块/文件层级、全局依赖迁移列表和原始证据附录。

#### Scenario: 生成组织化报告制品
- **WHEN** 报告生成任务成功完成
- **THEN** HTML 和 JSON 的模块数、文件数、finding 数、风险分布和依赖条目数 SHALL 一致
- **AND** HTML SHALL 默认以聚合摘要展示并避免一次渲染全部源码行
- **AND** JSON SHALL 通过 schemaVersion 标识新的组织结构

#### Scenario: 新流程历史下载
- **WHEN** 查询新流程产生的历史报告
- **THEN** SHALL 返回该报告固定组织投影和制品，不用当前算法覆盖；变更前无组织投影数据不提供兼容读取。

### Requirement: 报告 JSON 与 HTML 必须共享专项分析口径
JSON 和 HTML 报告 SHALL 使用同一输入快照和聚合数据，且 SHALL 保留专项分析来源类型以供前端分组和报告比较。

#### Scenario: 生成双格式报告
- **WHEN** 报告包含任一专项分析 Finding
- **THEN** JSON 与 HTML 的数量、风险、模块、文件和 Finding ID SHALL 一致
- **AND** 两种格式 MUST NOT 暴露工具工作目录或对象存储内部信息

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

### Requirement: 报告摘要草稿与历史必须独立表达
后端 SHALL 分别返回固定 reportSummary、AI 完整性、草稿统计及确认状态；历史读取不动态混入当前输入池。

#### Scenario: 修改草稿
- **WHEN** 用户加入/排除/恢复或补充问题
- **THEN** SHALL 仅更新草稿与审计及确认有效性，不重写原风险、AI 结论、报告 summary 和制品。

#### Scenario: Context 更新
- **WHEN** 源码或所选有效分析 Context 改变
- **THEN** SHALL reportUpToDate=false，旧报告只读且不能作为新清单依据；变化原因与新增/移除/变化摘要来自来源快照比较，不来自人工状态变动。

#### Scenario: 历史比较准备
- **WHEN** 存在可比较旧报告或不存在
- **THEN** SHALL 提供准确比较提示，缺少比较基线不阻塞报告生成；所有历史事实保持原版本。

### Requirement: 报告双格式输出与归档必须使用固定版本
后端 SHALL 生成同一视图模型的 HTML/JSON 制品，保存安全元数据并通过受控下载提供，原始报告与确认清单制品分开。

#### Scenario: 生成摘要
- **WHEN** 报告成功且来源含正式风险和候选
- **THEN** SHALL 来源记录数、决策项数、正式风险数、待核实数分别统计，HTML/JSON 一致；未知/未分析不表示安全。

#### Scenario: 下载确认清单
- **WHEN** 请求指定 confirmationId 的 HTML 或 JSON
- **THEN** SHALL 返回该不可变确认版本的固定制品，不读取当前草稿；无此确认或无权限拒绝。

#### Scenario: 归档候选
- **WHEN** 报告制品成功保存
- **THEN** SHALL 登记报告 ID、来源/风险安全聚合和制品引用，不含完整 prompt、源码、秘密或恶意路径明细；登记失败不反向回滚已成功报告。

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

### Requirement: 全局依赖展示必须保留逐来源身份
后端 SHALL 按 provider/依赖坐标分组展示依赖证据，但不丢失逐来源问题与处置，不强制归入源码文件。

#### Scenario: 同包多证据
- **WHEN** 同 provider 下有多个 SO/ELF 或来源记录
- **THEN** SHALL 保留全部来源 ID 和 evidence，组级数量与逐项数量分开，不能合并导致待复核项消失。

#### Scenario: 无文件定位
- **WHEN** 依赖没有源码位置
- **THEN** SHALL 在全局列表和统计展示依赖证据，不伪造文件树路径；只针对中以上/未知未解决问题生成决策项。

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
