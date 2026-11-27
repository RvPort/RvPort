# 验收计划

本文件描述未来实施验收，不是完成记录。所有编译、单元测试、覆盖率和依赖操作在本地 Docker 内进行。只读仓库检查和编辑可在宿主机完成。

## 单元测试与覆盖率

以 backend 的 Dockerfile `unit-test` 目标为入口，以下命令从仓库根目录执行；名称使用本 change 专用前缀，实施前确认没有同名资源。CPU 和内存按 Docker 可用资源调整并记录，不并行启动多套完整构建。

```powershell
docker build --target unit-test -t rvport-mainline-unit:local -f backend/Dockerfile .
docker run --name rvport-mainline-unit --memory=6g --cpus=2 -e MAVEN_OPTS=-Xmx1536m rvport-mainline-unit:local
docker cp rvport-mainline-unit:/workspace/backend/target/surefire-reports .migration-reports/fix-workflow-mainline-recommendation/unit-reports
docker cp rvport-mainline-unit:/workspace/backend/target/site/jacoco .migration-reports/fix-workflow-mainline-recommendation/jacoco
```

容器入口实际为 Maven clean verify；实施时记录实际执行配置。导出前创建专用输出目录，并保存 Java exec/XML/HTML 及项目已有 Python 测试和覆盖率产物。现有受条件控制的数据库测试须在专用依赖容器中真实运行，不能用跳过代替通过。

统计 backend 全部自有可执行源码，含未加载源码；受影响模块至少包含 project 工作流聚合/推荐、report/review 当前事实解析，若新增复用模块则同步列入。Java 整体与每个受影响模块行/分支均不低于 90%；维持既有 Python 整体门禁，若 Python 受影响还需其模块指标。不得改动工具源码来满足后端覆盖率。列出第三方和生成代码的既有排除及理由，不增加业务逻辑排除。

除 Maven 成功外，核对 JaCoCo agent 实际加载、当前 run 的 exec/XML 非空且包含新增策略类和未加载类。验证缺失或空覆盖率报告时门禁实际失败，负向实验只操作独立容器副本，再恢复并执行正常门禁。没有报告、0 条计数、旧时间戳报告均不能作为通过证据。

## 行为断言

- 策略纯单元测试覆盖接口契约 M01–M18，随机或参数化置换阶段数组，并保留确定性复现种子。
- 聚合测试覆盖生成与复核分离、重新生成不被旧 DONE 遮蔽、旧身份任务不抢占、缺失工作区/确认引用、合法 PARTIAL 输入和 Context 失效。
- 权限测试覆盖普通账号访问自己/其他人项目、管理员按原规则访问、未知项目，验证新增字段无越权泄露。
- 序列化契约测试覆盖 nullable ID/计数、未知不等于零、旧字段保留，推荐读取不写任务或业务表。
- 测试确认空清单不导致构建/测试假成功；后续测试只认可对应构建验证，独立阶段调用不受推荐 ID 限制。

## 完整 Compose

使用仓库根目录的 `docker-compose.yml`，专用 project name `rvport-mainline-1126`，以临时且不入库的 env/override 分配空闲宿主机端口、独立网络、数据卷、运行目录和测试账号。先用 `docker compose config` 检查最终挂载、连接字符串和资源名称不引用已有部署。专用配置中设置 CPU/内存上限和构建并发，不能依赖仅 project name 就隔离显式命名资源。

实施记录应保存展开后的真实命令。构建与启动结构如下，额外 env/override 参数必须两次保持一致：

```powershell
docker compose -p rvport-mainline-1126 build
docker compose -p rvport-mainline-1126 up -d --wait
docker compose -p rvport-mainline-1126 ps
docker compose -p rvport-mainline-1126 logs --no-color --tail 200
```

上面是命令结构，未准备独立配置前不得直接执行。必须完整构建并一次启动所有默认服务，不以仅启动 backend/worker 代替；允许使用构建缓存，但镜像必须包含本次源码。

以本地合成源码和专用账号准备以下代表流程：无源码 → 上传源码 → 不构建 x86 的合法源码扫描 → 结果评估与专项分析同时就绪 → 专项运行时读取 overview → 生成普通报告 → 待复核/处置 → 手动确认非空 → 编辑后需重新确认 → 合法空清单。分别验证源码/Context 变化、报告重新生成、历史只读、无权限读取。后续验证所需环境仅使用独立本地测试资源或项目既有隔离夹具，禁止真实 SSH 主机；真实状态匹配在单元与数据库测试中完整覆盖，不能通过伪造业务成功替代完整验收。

记录所有默认服务健康、关键日志、请求/响应摘要、任务与报告对应关系及 M01–M18 实际覆盖方式。不得使用真实 API 密钥调用模型；本次改动不需要真实 AI 执行。保留标准报告和清单流程的回归断言。

## 交付证据

实施阶段新增 `verification.md`，列出源码版本/未提交差异、实际命令、容器/镜像、单元测试数量及跳过原因、整体和逐受影响模块行/分支覆盖率、统计范围、原始报告位置、Compose 健康与代表流程结果。原始运行输出保存在仓库根 `.migration-reports/fix-workflow-mainline-recommendation/`，不得提交凭据或运行数据。

纯提案阶段只执行 OpenSpec 严格校验、文档链接和范围检查；不声称已运行上述实现验收。任何任务或门禁未完成时保留未勾选，不提示归档。
