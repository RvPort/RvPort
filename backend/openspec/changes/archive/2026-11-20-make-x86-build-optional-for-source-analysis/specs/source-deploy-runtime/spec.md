## ADDED Requirements

### Requirement: 构建内重试复用部署必须在副作用前校验身份
后端 SHALL 在按taskId、显式deploymentId或成功上下文复用部署的所有路径中，先校验任务授权、项目、源码、环境及配置版本、受控路径和请求关联；错误关联 MUST NOT 修改旧记录、打包、SSH或通过新建记录规避约束。

#### Scenario: 同任务失败重试
- **WHEN** 非force构建任务重试查到其FAILED部署且全部身份匹配
- **THEN** SHALL 复用同一部署行和服务端路径，受租约保护重部署；仍只有一个task关联部署行，成功后才执行构建。

#### Scenario: 错误关联
- **WHEN** task匹配但项目/源码/环境/环境配置版本或显式deploymentId不匹配，或路径不符合受控规则
- **THEN** SHALL 在markRunning与工具记录创建前受控失败，返回稳定双语安全原因，不能将新源码写入旧记录路径。

#### Scenario: 跨任务成功复用
- **WHEN** 同项目同源码同环境同配置版本已有成功部署且非force
- **THEN** SHALL 经身份校验复用，允许原部署属于另一个任务，不因原task不同错误拒绝合法缓存；force沿用既有语义且不豁免身份校验。

#### Scenario: 双端与并发重试
- **WHEN** BOTH两个环境各自任务执行或重复消息触发重试
- **THEN** SHALL 每个任务只绑定自身环境及同一固定源码版本，结合租约/事务/唯一约束保证单次有效执行；过期worker不得改写部署结果。
