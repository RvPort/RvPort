# project-pipeline-workbench Specification

## Purpose

定义受保护的项目工作台、固定流程拓扑、节点导航及展示辅助状态。当前上下文由 project-overview-current-context 定义；执行规则由 project-stage-runtime 定义；各阶段业务由独立能力规范定义。

## Requirements

### Requirement: 项目流水线工作台路由

前端 SHALL 提供受认证保护的项目流水线工作台路由，并将其作为项目工作台默认入口。

#### Scenario: 已登录用户打开项目流水线工作台

- **WHEN** 已登录用户打开 `/projects/:projectId`
- **THEN** 应用 SHALL 在受保护的主布局中渲染项目流水线工作台页面

#### Scenario: 未登录用户打开项目流水线工作台

- **WHEN** 不存在有效登录会话且用户打开 `/projects/:projectId`
- **THEN** 现有受保护路由守卫 SHALL 将用户导向 `/login`

### Requirement: 项目工作台加载与阶段导航

工作台 SHALL 通过 overview 获取项目、当前上下文、`workflow.stages` 和 `workflow.recommendedStageId`，并通过路径表示当前视图与阶段。

#### Scenario: 加载项目工作台

- **WHEN** 用户打开项目页
- **THEN** 页面 SHALL 显示局部加载状态，成功后展示项目摘要和流程，失败时提供重试
- **AND** 页面 SHALL 不额外读取旧的 `/workflow/status` 快照接口

#### Scenario: 打开阶段

- **WHEN** 用户点击有效阶段或访问 `/projects/:id/workflow/:stageId`
- **THEN** 页面 SHALL 按阶段 ID 懒加载阶段内容，并在 Workflow 视图以 Drawer 展示
- **AND** 单纯打开页面或阶段 SHALL 不自动启动扫描、报告、构建、测试或归档任务

### Requirement: 固定流程拓扑

画布 SHALL 在现有十二个业务节点基础上增加强弱内存序分析与浮点精度扫描两个节点。拓扑与坐标由前端维护，阶段 ID 与任务事实根据实际后端契约接入。

#### Scenario: 展示准备与分析关系

- **WHEN** 工作台渲染
- **THEN** 画布 SHALL 展示 `project -> env/assets -> x86-build`，并将 x86 构建保持为独立的一列
- **AND** 知识库扫描、依赖分析、强弱内存序分析和浮点精度扫描 SHALL 在后一列并行展示，四者均从 x86 构建取得展示级输入关系
- **AND** 两项新分析 SHALL 位于“扫描与分析”分组，分组边界、汇合线及反馈标签 SHALL 保持间距

#### Scenario: 展示验证与修复关系

- **WHEN** 工作台渲染评估后的流程
- **THEN** 画布 SHALL 保留 `report -> build -> test -> optimize -> archive` 及 `report -> manual`
- **AND** `manual -> assets` 与 `optimize -> assets` SHALL 保持带语义标签的回流边

### Requirement: 画布浏览保持状态边界

画布 SHALL 支持平移、缩放、悬浮关联关系和按当前推荐阶段聚焦；节点 SHALL 不可拖拽编排。

#### Scenario: 浏览流程

- **WHEN** 用户平移、缩放或悬浮节点
- **THEN** 页面 SHALL 只更新展示状态，不写入服务端项目状态
- **AND** 悬浮关联边 SHALL 通过样式联动，不为每次悬浮重建 edges 数组

### Requirement: Stage Drawer 必须按 stage ID 稳定挂载

Stage Drawer 的组件身份 SHALL 只由 stage ID 决定，不得因任务、readiness 或 Workflow 展示状态变化替换领域组件。

#### Scenario: Drawer 打开期间状态变化

- **WHEN** 当前阶段事实从等待变为运行或终态
- **THEN** Drawer SHALL 保留表单输入、滚动位置和当前子视图
- **AND** 页面 SHALL NOT 关闭后重新打开 Drawer

### Requirement: Workflow 前端辅助状态

项目流水线工作台 SHALL 根据前端固定的节点前置关系和 Overview 阶段事实状态，为尚未执行的节点推导展示用辅助状态。

#### Scenario: 节点前置阶段均已完成

- **WHEN** 节点自身为 `PENDING` 且所有展示级前置节点均为 `DONE`
- **THEN** 前端 SHALL 将该节点的辅助状态推导为 `READY`

#### Scenario: 节点等待前置阶段

- **WHEN** 节点自身为 `PENDING`，且至少一个前置节点尚未 `DONE`
- **AND** 不存在已失败或已被阻塞的前置节点
- **THEN** 前端 SHALL 将该节点的辅助状态推导为 `WAITING`
- **AND** SHALL 保留尚未完成的直接前置节点，用于用户可见说明

#### Scenario: 节点因前置阶段被阻塞

- **WHEN** 节点自身为 `PENDING`，且任一前置节点为 `BLOCKED` 或其辅助状态为 `BLOCKED`
- **THEN** 前端 SHALL 将该节点的辅助状态推导为 `BLOCKED`
- **AND** SHALL 将阻塞语义继续传递到尚未执行的后续节点

#### Scenario: 节点已进入自身生命周期

- **WHEN** 节点状态为 `RUNNING`、`DONE` 或 `BLOCKED`
- **THEN** 前端 SHALL 优先展示后端节点事实状态
- **AND** 节点事实状态图标 SHALL NOT 被前端辅助状态替换

#### Scenario: 阻塞传播经过已有事实状态的节点

- **WHEN** 任一节点存在阻塞根节点
- **THEN** 前端 SHALL 将阻塞展示状态传播到尚未执行的后续前向节点
- **AND** 传播 SHALL 在 `RUNNING` 或 `DONE` 节点处停止
- **AND** 已运行或已完成节点 SHALL 保持其事实状态和原有视觉

#### Scenario: 反馈连线不参与前置判断

- **WHEN** Workflow 包含 `manual -> assets` 或 `optimize -> assets` 等 feedback edge
- **THEN** 前端 SHALL NOT 将 feedback edge 的源节点视为目标节点的必需前置

#### Scenario: 辅助状态不作为执行依据

- **WHEN** 用户打开阶段 Drawer 或尝试发起任务
- **THEN** 前端 SHALL NOT 使用 Workflow 辅助状态替代后端 readiness 或任务创建接口的权威校验

### Requirement: Workflow 依赖语义展示

项目流水线工作台 SHALL 通过连线、节点卡片和动态说明表达节点之间的完成、运行、等待和阻塞语义。

#### Scenario: 桌面画布展示依赖状态

- **WHEN** 用户在桌面视口查看 Workflow
- **THEN** 完成、运行、等待和阻塞路径 SHALL 使用可区分的连线样式
- **AND** 节点 SHALL 只使用一个图标表达后端返回的节点自身状态
- **AND** 节点第二行 SHALL 展示可开始、等待对象或阻塞来源等动态说明

#### Scenario: 尚未执行的前向链路展示阻塞

- **WHEN** 任一节点事实状态为 `BLOCKED`
- **THEN** 从该节点开始、直至事实状态边界的尚未执行节点和连线 SHALL 使用红色阻塞样式
- **AND** feedback edge SHALL 保持独立的回流样式

#### Scenario: 并行分析输入汇合

- **WHEN** 知识库扫描和依赖分析连接到评估报告
- **THEN** 画布 SHALL 在评估报告前展示单一输入汇合点
- **AND** 汇合点 SHALL 以 `n/2` 显示已完成的直接输入数量

#### Scenario: 所有支持宽度保持 Workflow 画布

- **WHEN** 用户在任一支持的桌面宽度查看 Workflow
- **THEN** 页面 SHALL 保持完整 Workflow 画布，不切换为移动端列表
- **AND** 节点 SHALL 直接展示辅助状态文案，不要求用户通过 hover 获取关键等待或阻塞信息

#### Scenario: Workflow 节点不展示固定业务副标题

- **WHEN** Workflow 节点渲染
- **THEN** 节点 SHALL 展示节点名称、事实状态标记和动态状态说明
- **AND** SHALL NOT 展示固定业务副标题

#### Scenario: Workflow 区分主路径和处理支路

- **WHEN** 页面展示评估之后的阶段
- **THEN** 构建验证、自动化测试、优化闭环和归档 SHALL 保持水平主路径
- **AND** 人工修复 SHALL 位于评估报告下方并从报告节点底部引出
- **AND** 主路径 SHALL 使用连续的阶段间距，优化闭环保留回到源码管理的回流关系
- **AND** 返回源码管理的 feedback edge SHALL 显示回流语义文案

#### Scenario: Workflow 使用主题分组框

- **WHEN** 页面在桌面视口渲染 Workflow 分组
- **THEN** 画布 SHALL 使用“基础准备”、“扫描与分析”、“评估与处理”和“验证与交付”分组框
- **AND** 分组框 SHALL 使用可区分的低饱和主题色、实体边界和标题标识

### Requirement: 窄间距连线保持连续正交路径

前端 SHALL 在节点间水平空间不足时缩小连线端点偏移，避免竖线中部出现多余折返。

#### Scenario: 依赖分析连接评估输入汇合点

- **WHEN** 两端可用水平距离小于默认端点偏移之和
- **THEN** 连线沿同一竖直通道连接两端圆角，不在中部横移

### Requirement: 回流标签避开其他回流边

前端 SHALL 为回流线分配分组下方独立通道，并在无竖线交叉区间的横线段上方展示标签。避让计算 SHALL 排除当前边自身，兼容缩放产生的端点小数偏差。

#### Scenario: 人工修复与优化闭环同时回流

- **WHEN** 两条回流线同时展示
- **THEN** 横向通道保持间距，优化回流标签不与人工修复竖线相交

#### Scenario: 缩放后端点出现小数偏差

- **WHEN** 实测回流端点相对静态节点中心存在小数偏差
- **THEN** 修复和优化回流标签均保持在横线段上方，远离自身竖线

### Requirement: Workflow 与 Step 双视图

工作台 SHALL 提供完整 Workflow 画布与顺序化 Step 操作视图，两者使用同一项目与阶段事实，不把缩小或展开的 Workflow 画布作为第三种页面。

#### Scenario: Workflow 查看全局

- **WHEN** 用户访问 `/projects/:id/workflow`
- **THEN** 页面 SHALL 展示完整、可平移缩放的十二阶段画布、拓扑连线、节点状态与项目上下文
- **AND** 点击节点 SHALL 在当前画布上打开该阶段 Drawer，不切换为左右画布布局
- **AND** 打开或关闭 Drawer SHALL 保持用户当前的画布平移与缩放，只更新节点选中态；首次加载或明确从 Step 切回 Workflow 时仍可定位阶段

#### Scenario: Workflow 节点视觉语义

- **WHEN** 用户查看 Workflow 或点击节点打开 Drawer
- **THEN** 节点 SHALL 独立表达业务状态、业务当前阶段和点击选中，且三种含义可同时存在
- **AND** 业务状态 SHALL 保留状态色竖条、图标与底色，当前阶段 SHALL 使用节点下方的小号灰字「当前阶段」且不加边框、底色或胶囊，节点标题 SHALL 保持统一字重，点击选中 SHALL 使用底部蓝色短线
- **AND** 查看其他阶段或关闭 Drawer SHALL 不改变业务当前阶段标记
- **AND** 当前阶段 SHALL 常驻现有 hover 放大及关联连线流动效果且保留业务状态色；减少动态效果时 SHALL 不放大、不流动

#### Scenario: Step 阶段工作区

- **WHEN** 用户切换到 Step 或访问 `/projects/:id/steps/:stageId`
- **THEN** 页面 SHALL 在左侧沿用分组式紧凑导航，按顺序列出十二阶段的名称、辅助说明、事实状态与当前选中态，在右侧展示所选阶段内容
- **AND** 左侧导航 SHALL 不复刻 Workflow 的拓扑与分支连线，右侧宽表格 SHALL 在内容内部滚动
- **AND** 等待或阻塞状态 SHALL 不禁止查看阶段

#### Scenario: Step 内切换阶段

- **WHEN** 用户在 Step 导航或阶段内容中选择另一个有效阶段
- **THEN** 页面 SHALL 留在 Step 并以内嵌 Panel 展示新阶段，复用与 Workflow Drawer 相同的阶段业务内容
- **AND** 浏览器后退 SHALL 能恢复先前的 Step 阶段

#### Scenario: Step 根视图操作

- **WHEN** 用户查看 Step 阶段根视图
- **THEN** 页面 SHALL 不显示阶段级手动刷新，且不为该操作增加工具栏或内容留白
- **AND** 内容中的列表刷新、环境检查等业务操作及内部详情页操作 SHALL 保留，Workflow Drawer 的刷新 SHALL 不变

#### Scenario: 视图切换保留阶段

- **WHEN** 用户从已打开阶段 Drawer 的 Workflow 切换到 Step
- **THEN** 页面 SHALL 在 Step 打开同一阶段；没有已打开阶段时 SHALL 使用有效的推荐阶段，缺失时回到项目基础配置
- **AND** 从 Step 返回 Workflow SHALL 保留画布并定位刚查看的阶段，不自动打开另一个 Drawer
- **AND** 连续返回同一阶段 SHALL 再次定位；取消未保存更改确认后 SHALL 不改变仍处于隐藏状态的画布定位

#### Scenario: 完整详情与主展示视口滚动

- **WHEN** 用户在 Workflow Drawer 或 Step 中查看日志、源码或分析详情
- **THEN** 页面 SHALL 完整保留必要的摘要、判断、建议及上下文信息，正文超过可见高度时允许正文滚动，页头与返回入口保持固定
- **AND** 主代码、日志及分析证据视口 SHALL 使用统一的独立高度 `clamp(720px, 80vh, 1200px)`，不被上方元信息挤压；视口内容溢出时在内部滚动
- **AND** 紧凑桌面上下排列时 SHALL 保留代码区的独立高度，不与问题列表平分剩余屏幕高度
- **AND** Step 阶段根视图与内部详情之间切换时 SHALL 将正文滚动到顶部，完整展示概要

#### Scenario: 阶段内部列表与详情往返

- **WHEN** 用户在同一阶段的列表设置筛选、页码、每页条数或排序后进入详情，再通过详情返回入口回到该列表
- **THEN** Workflow Drawer 与 Step SHALL 恢复该列表的查询控件状态，并使用这些条件重新请求数据
- **AND** 列表搜索框 SHALL 在阶段局部范围内按数据集保留尚未生效的输入草稿，失焦 SHALL NOT 提交查询；进入详情取消防抖计时，返回列表恢复草稿并继续正常防抖，Enter 和清空仍立即提交
- **AND** 清空全部筛选 SHALL 同时清除已应用查询条件和待生效搜索草稿、取消防抖并回到第一页，即使已应用搜索词原本为空；清空后的新输入 SHALL 仍可在详情往返恢复，不得重新应用清空前的旧草稿
- **AND** 未生效搜索草稿的失焦 SHALL NOT 因触发查询而替换或卸载详情入口；指针点击和键盘激活 SHALL 均能进入详情，无论查询缓存是否命中或后续请求快慢、成功或失败
- **AND** 最新查询结果的总数减少导致已保存页码越界时，列表 SHALL 校正到最后一个有效页码并重新请求，保留筛选、每页条数与排序；总数为零时 SHALL 回到第一页
- **AND** 不同记录数据集 SHALL 独立保存状态，返回同一数据集时恢复对应状态；不常驻隐藏列表或复用过期的行选择
- **AND** 报告模块、文件、问题和依赖自定义列表 SHALL 同样校正越界页码；从依赖详情返回 SHALL 恢复依赖清单实际展开状态，而不仅保存局部状态值
- **AND** 关闭阶段时 SHALL 清空局部表格状态，跨阶段、跨项目及切换 Workflow/Step 不保证保留；正文滚动仍遵守既定详情滚动规则

#### Scenario: URL 与历史

- **WHEN** 用户刷新、复制阶段地址或使用浏览器前进后退
- **THEN** 工作台 SHALL 从 `/workflow`、`/workflow/:stageId`、`/steps/:stageId` 路径恢复对应视图与阶段
- **AND** 不要求旧 `panel` 查询参数兼容

#### Scenario: 切换与未保存更改

- **WHEN** 用户在环境表单、项目描述、源码导入、扫描配置或构建/测试配置有未保存更改时切换视图、阶段或离开工作台
- **THEN** 页面 SHALL 在导航前提供继续编辑或放弃更改的选择；继续编辑时 SHALL 留在原阶段并保留输入
- **AND** 已保存的服务端数据 SHALL 继续由查询缓存复用，但滚动、筛选、局部弹窗等临时 UI 状态不保证跨视图保留
- **AND** 项目描述、源码导入与扫描配置 SHALL 在提交期间暂停编辑与局部返回/取消；失败后 SHALL 保留原输入供重试
- **AND** 允许提交期间继续编辑的执行配置，成功提交 SHALL 只清除当次提交内容的脏状态，后续输入 SHALL 仍受离开保护
- **AND** 用户重新打开扫描配置时 SHALL 开始新的编辑会话，不能以此前提交过的相同配置判定新输入已保存

#### Scenario: 项目任务更新

- **WHEN** 用户切换视图或 overview 与任务状态刷新
- **THEN** 项目级任务事件订阅 SHALL 不因展示载体切换而重建，阶段事实状态 SHALL 同步更新

#### Scenario: 桌面端宽度

- **WHEN** 视口位于 1024–1199px、1200–1599px、1600–1919px 或 1920px 以上
- **THEN** 页面 SHALL 分别使用紧凑、标准、宽屏或最大 1920px 的桌面布局，Step 右侧内容 SHALL 占用剩余宽度
- **AND** 低于 1024px SHALL 保留 1024px 桌面最小宽度，不切换移动端布局

### Requirement: Git 源码任务失败后可恢复

Git 源码导入 SHALL 跟随已有任务状态显示进行中或失败；初次导入失败、取消或超时后 SHALL 恢复编辑入口，任务进行中 SHALL 防止重复提交。

#### Scenario: 首次 Git 同步失败

- **WHEN** Git 同步已接受但后台任务失败，且项目尚无当前源码
- **THEN** 页面 SHALL 显示失败并恢复表单，保留当前页面的仓库和 Ref 配置，清除已提交的 Token
- **AND** 重新进入阶段 SHALL 使用服务端已有任务状态，不将旧提交标记永远视为运行中

### Requirement: 迁移问题动作归属来源与会话

迁移问题导入后的更新 SHALL 仅作用于匹配的来源记录，状态与 pending SHALL 归属完整编辑和认证会话。

#### Scenario: 导入未返回匹配记录

- **WHEN** 导入响应没有匹配 sourceRefId 的条目
- **THEN** 前端 SHALL 读取既有记录定位；未找到时 SHALL 报错，SHALL NOT 更新第一条无关记录

#### Scenario: 提交中切换记录或身份

- **WHEN** 提交尚未结束时切换记录或认证身份
- **THEN** 新记录 SHALL NOT 继承旧 loading 或本地确认状态

### Requirement: 源码片段并行加载独立反馈

报告源码省略片段 SHALL 各自维护加载状态，同一片段的重复加载 SHALL 被合并或阻止。

#### Scenario: 同时展开两个片段

- **WHEN** 两个源码片段正在读取，其中一个先完成
- **THEN** 另一个片段 SHALL 保持 loading，直到其自身请求完成或失败

### Requirement: 阶段业务状态按项目和认证身份隔离

阶段实例 SHALL 按项目、认证 generation 与阶段建立状态边界。相同项目和阶段的 Workflow/Step 切换 SHALL 保留业务实例，跨项目或身份切换 SHALL 丢弃旧表单、选择及局部详情。

#### Scenario: 同一阶段切换项目

- **WHEN** 项目 A 的阶段存在草稿或选择，用户切换到项目 B 的相同阶段
- **THEN** 项目 B SHALL NOT 继承 A 的输入、编辑模式或选中记录
- **AND** 相同项目的视图及阶段状态更新 SHALL NOT 重置既有草稿

### Requirement: 自动化测试批次区分提交结果并保留恢复能力

批次创建 SHALL 在发送任一请求前检查两侧构建输入；两侧全部确认创建时才 SHALL 标记草稿已提交。部分成功及全部失败 SHALL 保留草稿与各侧结果，提供失败侧恢复入口。

#### Scenario: 部分创建成功或全部失败

- **WHEN** x86 与 RISC-V 的创建请求仅部分成功或均失败
- **THEN** 前端 SHALL NOT 触发全部成功回调或清除草稿的未保存状态
- **AND** 已确认提交的任务 SHALL 保留；重试 SHALL 仅发送失败侧，并沿用本批次编号、构建输入和提交时配置
- **AND** 用户明确开始新批次 SHALL NOT 取消已确认提交的任务

#### Scenario: 旧批次在身份或编辑作用域变化后完成

- **WHEN** 账号、项目或所属编辑视图已变化，旧创建请求随后完成
- **THEN** 旧结果 SHALL NOT 标记新草稿已提交、显示通知或建立新视图的重试入口

### Requirement: 阶段预加载与导航隔离

阶段选择 SHALL 按既有路径立即发起导航，资源未就绪时显示阶段加载状态；预加载完成或失败 SHALL NOT 发起迟到导航。未保存保护遵循本能力既有规则。

#### Scenario: 连续选择不同阶段

- **WHEN** 用户选择环境阶段，其资源尚未就绪时又选择报告或其他阶段
- **THEN** 后一次选择 SHALL 保持有效，环境资源加载结束 SHALL NOT 将页面带回环境

#### Scenario: 预加载期间切换视图、项目或离开页面

- **WHEN** 预加载尚未完成，用户切换 Workflow/Step、另一个项目或离开项目页
- **THEN** 旧预加载 SHALL NOT 覆盖新位置，前进后退及未保存确认 SHALL 保持有效

#### Scenario: 阶段资源加载失败

- **WHEN** 预加载或阶段资源读取失败
- **THEN** 预加载 SHALL NOT 产生未处理 Promise 拒绝或改变导航，阶段失败 SHALL 使用既有路由错误恢复入口

### Requirement: 阶段详情与前置条件保持更新

前端 SHALL 按选中记录读取构建详情，并在日志完成时刷新该记录；跨阶段操作 SHALL 刷新其已知依赖条件。

#### Scenario: 构建列表快照落后

- **WHEN** 打开列表中仍显示运行中的构建记录，而详情接口已有终态
- **THEN** 概要 SHALL 使用详情的状态、退出码与执行时间
- **AND** 日志完成通知 SHALL 刷新当前构建详情

#### Scenario: x86 构建更新

- **WHEN** 前端接收 x86 构建生命周期事件并刷新领域数据
- **THEN** 前端 SHALL 同时失效知识库扫描的前置条件查询

#### Scenario: 从详情调整报告输入

- **WHEN** 用户在任一列表或详情中纳入或排除迁移问题
- **THEN** 公共操作 SHALL 统一刷新报告前置条件和优化轮次，不依赖调用方补齐这些 key

#### Scenario: 专项分析无 SSE 时继续观察

- **WHEN** 用户打开仍在运行的专项分析历史详情，或最新接口已有活动任务但尚未生成分析结果
- **THEN** 前端 SHALL 继续轮询所属记录或最新状态，不依赖 SSE 推送才能到达终态
- **AND** 记录进入终态后 SHALL 停止该记录的活动轮询

### Requirement: 新分析节点的导航与报告输入边界

工作台 SHALL 将两项新分析纳入 Workflow、Step 和有效阶段深链接，并保持既有工作台行为。

#### Scenario: 两种视图切换分析阶段

- **WHEN** 用户在新分析阶段切换 Workflow 与 Step
- **THEN** 页面 SHALL 保留所选阶段，复用同一业务组件及项目上下文
- **AND** SHALL 沿用共享的未保存更改保护、阶段关闭与历史导航规则

#### Scenario: 新分析结果纳入报告输入

- **WHEN** 后端明确将新分析结果纳入报告输入
- **THEN** 工作台 SHALL 保持知识库扫描和依赖分析的 `n/2` 必需输入汇合计数与连线，两项专项分析通过确认后的报告输入纳入结果
- **AND** SHALL NOT 将画布中的可选分析自行升级为报告执行的必需门禁

### Requirement: 分析完整性不由执行终态推断

Workflow、Step 和阶段内容 SHALL 保留服务端执行事实，并独立提示分析完整性。

#### Scenario: 已完成节点包含部分分析

- **WHEN** 任务为 SUCCEEDED 或节点为 DONE，但 analysisStatus=PARTIAL 或 analysisComplete=false
- **THEN** 前端 SHALL 提示“部分分析完成”，不把零正式风险解释为迁移安全
