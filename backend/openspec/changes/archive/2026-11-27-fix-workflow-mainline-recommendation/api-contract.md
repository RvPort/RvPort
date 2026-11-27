# overview 主线推荐对接契约

状态：后端已实现，实际验收见 verification.md。入口保持 `GET /api/projects/{projectId}/overview`，沿用项目访问权限和现有错误格式。无新增操作接口。

## 字段

| 字段 | 类型 | 语义 |
|---|---|---|
| workflow.recommendedStageId | 现有 stage ID 或 null | 当前应聚焦的页面；null 表示无可推荐动作，不表示错误 |
| workflow.stages | 原阶段数组 | 继续保持展示顺序及真实 status、reasonCode、optional、canStart |
| workflow.currentFixList | 原对象 | 保持 available、reportId、confirmationId、draftVersion、itemCount、reasonCode 语义 |
| workflow.reportReview | 新增对象 | 当前报告导航摘要；每次 overview 均返回此对象 |
| reportReview.state | 枚举 | 见下表，仅为机器状态，不替代阶段 status |
| reportReview.reportId | UUID 或 null | 摘要所描述的报告；GENERATING 时为当前生成中的报告 |
| reportReview.taskId | UUID 或 null | GENERATING/GENERATION_FAILED 对应任务，其余无关联任务时为 null |
| reportReview.confirmationState | 枚举或 null | UNCONFIRMED、NEEDS_CONFIRMATION、CONFIRMED_WITH_ITEMS、CONFIRMED_EMPTY；无当前有效草稿时为 null |
| reportReview.pendingReviewCount | 非负整数或 null | 当前有效草稿的待复核数；未知、失效或生成中为 null |
| reportReview.toFixCount | 非负整数或 null | 当前有效草稿待修复数，区别于不可变确认清单 itemCount |

GENERATING 描述新任务，`currentFixList` 可能仍引用有效旧报告，二者 reportId 可以不同；客户端不得拼接为同一报告。历史报告详情通过既有报告接口读取。没有待复核条目不表示用户已确认，0 与 null 不可互换。

| state | 适用事实 | 典型推荐 |
|---|---|---|
| NOT_GENERATED | 无当前报告、无有效当前生成终态可供展示 | 无输入时 kb，有输入时 report |
| GENERATING | 与当前输入身份匹配的任务 PENDING/QUEUED/RUNNING | report |
| GENERATION_FAILED | 当前生成失败且无可继续处理的有效报告 | 有输入时 report，否则按准备步骤 |
| UNAVAILABLE | 应有报告但复核/草稿/确认引用缺失 | report |
| STALE | 报告 Context 失效或不再具备当前发布资格 | 有输入时 report，否则按准备步骤 |
| PENDING_REVIEW | 有效草稿 pendingReviewCount > 0 | report |
| UNCONFIRMED | 有效草稿无待复核，但从未确认 | report |
| NEEDS_CONFIRMATION | 有效草稿无待复核，原确认失效 | report |
| CONFIRMED_WITH_ITEMS | 当前确认有效，itemCount > 0 | manual |
| CONFIRMED_EMPTY | 当前确认有效，itemCount = 0 | 后续验证分流 |

摘要优先级：匹配的活动生成任务 → 当前报告不可用/失效判断 → 有效草稿与确认 → 无当前报告时的匹配生成失败/尚未生成。确认记录缺失属于 UNAVAILABLE；Context 不匹配属于 STALE。旧报告存在但从未具有当前资格时，不能仅因创建时间最新而选择它。存在待复核时 state 优先为 PENDING_REVIEW，同时保留 confirmationState=UNCONFIRMED 或 NEEDS_CONFIRMATION。

`currentFixList` 不可用时沿用现有原因码；确认引用缺失归入 REPORT_REVIEW_UNAVAILABLE，不泄露内部异常。无效清单不得通过 itemCount=0 推断为空清单。有效空清单必须同时具备 available=true、有效 confirmationId 和 reasonCode=CONFIRMED_EMPTY。

## 联调矩阵

| 编号 | 输入事实 | 期望推荐 / 摘要 |
|---|---|---|
| M01 | 基础配置有效，无源码，环境未配 | assets / NOT_GENERATED |
| M02 | 源码有效，无报告输入，KB 和 x86 均 PENDING | kb / NOT_GENERATED |
| M03 | 仅有效浮点或内存序结果，无 KB、x86 | report / NOT_GENERATED |
| M04 | 报告生成可执行，浮点/内存序 RUNNING | report / NOT_GENERATED |
| M05 | report DONE，2 条待复核，从未确认 | report / PENDING_REVIEW，confirmationState=UNCONFIRMED |
| M06 | report DONE，22 条待修复、0 条待复核，未确认 | report / UNCONFIRMED |
| M07 | 确认后编辑，0 条待复核 | report / NEEDS_CONFIRMATION |
| M08 | 确认后编辑，2 条待复核 | report / PENDING_REVIEW，confirmationState=NEEDS_CONFIRMATION |
| M09 | 有效非空确认清单，支线 RUNNING | manual / CONFIRMED_WITH_ITEMS |
| M10 | 有效空清单，验证环境就绪，未构建验证 | build / CONFIRMED_EMPTY |
| M11 | 有效空清单，验证环境未就绪 | env / CONFIRMED_EMPTY |
| M12 | 有效空清单，匹配构建验证成功，未测试 | test / CONFIRMED_EMPTY |
| M13 | 历史确认非空，当前 Context 改变，报告仍可生成 | report / STALE |
| M14 | 报告存在但复核数据缺失 | report / UNAVAILABLE，计数 null |
| M15 | 有效旧报告存在，匹配的新生成任务运行 | report / GENERATING，不伪造旧清单失效 |
| M16 | 所有当前步骤和归档真实有效完成 | null / CONFIRMED_EMPTY |
| M17 | 旧 Context 生成任务运行，当前有效非空清单 | manual / CONFIRMED_WITH_ITEMS |
| M18 | 新生成失败，仍有有效未确认报告 | report / UNCONFIRMED，失败任务由任务历史展示 |

每个场景还应对 stages 数组置换，确认推荐保持一致。M10–M12 的 build 指后续构建验证，不是 x86-build；不伪造 manual、build 或 test 的 DONE。优化/归档是否可开始继续由原 readiness 决定。

## 响应片段示例

以下为联调预期片段，不是已运行证据，也不表示省略字段被删除。示例 UUID 仅用于夹具。

```json
{
  "recommendedStageId": "report",
  "reportReview": {
    "state": "UNCONFIRMED",
    "reportId": "00000000-0000-4000-8000-000000000001",
    "taskId": null,
    "confirmationState": "UNCONFIRMED",
    "pendingReviewCount": 0,
    "toFixCount": 22
  },
  "currentFixList": {
    "available": false,
    "reportId": "00000000-0000-4000-8000-000000000001",
    "confirmationId": null,
    "draftVersion": 1,
    "itemCount": 0,
    "reasonCode": "UNCONFIRMED"
  }
}
```

## 前端旧覆盖规则对接说明

前端当前 `getCurrentWorkflowStageId` 在服务端推荐 kb、KB 与 x86 同状态且 x86 未完成时改为 x86-build。这会使 M02 的页面结果与 API 不一致；该条件需要在前端后续改动中移除。前端应原样消费 recommendedStageId，并分别显示各节点 status/canStart；不得把任意 RUNNING 节点再覆盖成当前推荐，不应因 report DONE 而忽略服务端推荐。

本 change 只提交后端契约和夹具，不修改前端。后端交付只能证明 API 行为，不能声称旧前端 KB 导航覆盖已修复。已有前端忽略新增 reportReview 时仍能读取原字段；建议后续使用摘要表达待复核、未确认和需重新确认。新增面向用户的说明需同时具备中文和英文。
