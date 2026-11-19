## Purpose

明确旧知识库 AI 扩展能力的退役边界。独立建议任务、接口、存储和动态字段由报告 AI 复核替代；开发阶段不保留旧数据或在途任务兼容。

## Requirements

### Requirement: 旧 KB AI 扩展必须完整删除
后端 SHALL 删除旧创建/readiness/list/detail/artifact 下载路由、handler、专用存储和 Finding 上的动态 aiExtension 附加，不保留历史查询或旧任务解码兼容。

#### Scenario: 请求旧 API
- **WHEN** 通过认证和适用 CSRF 请求任一旧 KB AI 路由
- **THEN** SHALL 返回未注册路由 404，且无数据写入或模型调用。

#### Scenario: 全新启动
- **WHEN** 在重置的开发实例启动新 API/worker
- **THEN** SHALL 不注册旧任务处理器或旧制品读取路径；初始化清除旧任务队列，不实现旧任务恢复。
