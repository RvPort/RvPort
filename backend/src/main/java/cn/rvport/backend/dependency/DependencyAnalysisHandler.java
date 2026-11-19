package cn.rvport.backend.dependency;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.artifact.StoredArtifactCreateCommand;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactResource;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.buildvalidation.BuildValidationArtifacts;
import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.buildvalidation.BuildValidationRunView;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.environment.runtime.EnvironmentCollectionResult;
import cn.rvport.backend.environment.runtime.EnvironmentTaskInput;
import cn.rvport.backend.environment.runtime.SshRuntimeService;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskErrorCode;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskLogRepository;
import cn.rvport.backend.task.TaskLogResponse;
import cn.rvport.backend.task.TaskLogService;
import cn.rvport.backend.task.TaskLogType;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.ToolRunRepository;
import cn.rvport.backend.worker.CancellableResource;
import cn.rvport.backend.worker.TaskCancelledException;
import cn.rvport.backend.worker.TaskAttemptLostException;
import cn.rvport.backend.worker.TaskExecutionContext;
import cn.rvport.backend.worker.TaskHandler;
import cn.rvport.backend.worker.TaskHandlerResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DependencyAnalysisHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(DependencyAnalysisHandler.class);
    private static final String TOOL_NAME = "rvport-dependency-analysis";
    private static final String TOOL_VERSION = "1";
    private static final int ENVIRONMENT_REFRESH_TIMEOUT_SECONDS = 1800;

    private final DependencyAnalysisRepository repository;
    private final BuildValidationRepository buildValidationRepository;
    private final TaskLogRepository taskLogRepository;
    private final TaskLogService taskLogService;
    private final ToolRunRepository toolRunRepository;
    private final SshRuntimeService sshRuntimeService;
    private final DependencyFactExtractor extractor;
    private final StoredArtifactService storedArtifactService;
    private final ArchiveCandidateRegistrar archiveCandidateRegistrar;
    private final ObjectMapper objectMapper;

    public DependencyAnalysisHandler(
            DependencyAnalysisRepository repository,
            BuildValidationRepository buildValidationRepository,
            TaskLogRepository taskLogRepository,
            TaskLogService taskLogService,
            ToolRunRepository toolRunRepository,
            SshRuntimeService sshRuntimeService,
            DependencyFactExtractor extractor,
            StoredArtifactService storedArtifactService,
            ArchiveCandidateRegistrar archiveCandidateRegistrar,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.buildValidationRepository = buildValidationRepository;
        this.taskLogRepository = taskLogRepository;
        this.taskLogService = taskLogService;
        this.toolRunRepository = toolRunRepository;
        this.sshRuntimeService = sshRuntimeService;
        this.extractor = extractor;
        this.storedArtifactService = storedArtifactService;
        this.archiveCandidateRegistrar = archiveCandidateRegistrar;
        this.objectMapper = objectMapper;
    }

    @Override
    public String taskType() {
        return RvPortTaskTypes.DEPENDENCY_ANALYSIS;
    }

    @Override
    public TaskHandlerResult handle(TaskExecutionContext context) {
        DependencyAnalysisTaskInput input = context.input(DependencyAnalysisTaskInput.class);
        DependencyAnalysisView run = repository.findById(input.analysisRunId())
                .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "依赖分析运行不存在", null));
        UUID toolRunId = context.publish(() -> toolRunRepository.create(
                context.task().projectId(),
                context.task().id(),
                TOOL_NAME,
                TOOL_VERSION,
                "依赖分析",
                TaskStatus.RUNNING,
                OffsetDateTime.now()
        ));
        try {
            context.publish(() -> { repository.markRunning(run.id()); return null; });
            context.progress(10, "开始依赖分析");
            context.throwIfCancellationRequested();
            BuildValidationRunView buildRun = buildValidationRepository.findById(input.sourceBuildRunId())
                    .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "source 构建运行不存在", null));
            String mergedLog = mergedBuildLog(buildRun);
            SnapshotReference sourceSnapshot = refreshEnvironment(
                    context,
                    run.projectId(),
                    buildRun.environmentId(),
                    "source",
                    "source/x86 环境刷新",
                    15,
                    30
            );
            SnapshotReference targetSnapshot = refreshEnvironment(
                    context,
                    run.projectId(),
                    run.targetEnvironmentId(),
                    "target",
                    "target/RISC-V 环境刷新",
                    30,
                    50
            );
            context.progress(55, "提取 x86 构建事实");
            List<DependencyAnalysisRepository.TargetComponentView> sourceComponents = repository.listTargetComponents(sourceSnapshot.snapshotId());
            List<DependencyAnalysisRepository.TargetComponentView> targetComponents = repository.listTargetComponents(targetSnapshot.snapshotId());
            List<Object> warnings = new ArrayList<>();
            String compileDatabaseJson = compileDatabaseJson(run, buildRun, warnings);
            List<DependencyAnalysisResultItem> items = extractor.extract(run, buildRun, mergedLog, sourceComponents, targetComponents, compileDatabaseJson);
            context.publish(() -> {
                repository.replaceResultItems(run.id(), items);
                return null;
            });
            context.progress(70, "生成依赖分析制品");
            Map<String, Object> refreshSummary = environmentRefreshSummary(sourceSnapshot, targetSnapshot);
            Map<String, Object> summary = artifactSummary(items, refreshSummary);


            Map<String, Object> json = artifactJson(run, buildRun, sourceSnapshot, targetSnapshot, items, warnings, summary);
            StoredArtifactView jsonArtifact = storedArtifactService.storeJson(
                    run.projectId(),
                    run.taskId(),
                    StoredArtifactOwnerType.DEPENDENCY_ANALYSIS,
                    run.id(),
                    "SUMMARY_JSON",
                    json,
                    summary,
                    context
            );
            StoredArtifactView htmlArtifact = storedArtifactService.store(new StoredArtifactCreateCommand(
                    run.projectId(),
                    run.taskId(),
                    StoredArtifactOwnerType.DEPENDENCY_ANALYSIS,
                    run.id(),
                    "SUMMARY_HTML",
                    "text/html;charset=UTF-8",
                    html(json).getBytes(StandardCharsets.UTF_8),
                    summary,
                    "html"
            ), context);
            context.publish(() -> {
                repository.markFinished(run.id(), TaskStatus.SUCCEEDED, summary, warnings);
                DependencyAnalysisView finished = repository.findById(run.id()).orElse(run);
                archiveCandidateRegistrar.registerDependencyAnalysis(finished, summary, List.of(jsonArtifact, htmlArtifact));
                toolRunRepository.finish(toolRunId, TaskStatus.SUCCEEDED, 0, summary);
                return null;
            });
            context.progress(90, "依赖分析完成");
            return TaskHandlerResult.success("依赖分析成功");
        } catch (TaskAttemptLostException ex) {
            throw ex;
        } catch (TaskCancelledException ex) {
            finishFailure(context, run.id(), toolRunId, TaskStatus.CANCELLED, Map.of("error", "任务已取消"));
            throw ex;
        } catch (TaskExecutionException ex) {
            finishFailure(context, run.id(), toolRunId, TaskStatus.FAILED, Map.of("error", ex.safeMessage(), "errorCode", ex.errorCode()));
            throw ex;
        } catch (Exception ex) {
            log.warn("Dependency analysis handler failed runId={} taskId={}", run.id(), run.taskId(), ex);
            finishFailure(context, run.id(), toolRunId, TaskStatus.FAILED, Map.of("error", "依赖分析失败"));
            throw new TaskExecutionException(TaskErrorCode.HANDLER_FAILED, "依赖分析失败", ex);
        }
    }

    private void finishFailure(TaskExecutionContext context, UUID runId, UUID toolRunId,
            TaskStatus status, Map<String, Object> summary) {
        context.publish(() -> {
            repository.markFinished(runId, status, summary, List.of());
            toolRunRepository.finish(toolRunId, status, null, summary);
            return null;
        });
    }

    private String mergedBuildLog(BuildValidationRunView buildRun) {
        String excerpt = taskLogRepository.findLatest(buildRun.taskId(), TaskLogType.COMBINED)
                .map(log -> log.excerpt() == null ? "" : log.excerpt())
                .orElse("");
        String tail;
        try {
            tail = taskLogService.readTailText(buildRun.taskId(), buildRun.mergedLogId(), 20_000);
        } catch (ApiException ex) {
            log.warn("Dependency analysis falls back to task log excerpt taskId={} logId={} code={}",
                    buildRun.taskId(), buildRun.mergedLogId(), ex.code());
            tail = "";
        }
        if (tail.isBlank()) {
            return excerpt;
        }
        if (excerpt.isBlank() || tail.contains(excerpt)) {
            return tail;
        }
        return excerpt + "\n" + tail;
    }

    private String compileDatabaseJson(DependencyAnalysisView run, BuildValidationRunView buildRun, List<Object> warnings) {
        Map<?, ?> compileDatabase = buildRun.buildFacts().get("compileDatabase") instanceof Map<?, ?> map ? map : Map.of();
        if (!Boolean.TRUE.equals(compileDatabase.get("available"))) {
            return null;
        }
        Object storedArtifactId = compileDatabase.get("storedArtifactId");
        if (storedArtifactId == null) {
            warnings.add(Map.of("source", "compileDatabase", "reason", "ARTIFACT_ID_MISSING"));
            return null;
        }
        try {
            UUID artifactId = UUID.fromString(String.valueOf(storedArtifactId));
            StoredArtifactResource resource = storedArtifactService.download(artifactId);
            StoredArtifactView artifact = resource.artifact();
            if (!run.projectId().equals(artifact.projectId())
                    || artifact.ownerType() != StoredArtifactOwnerType.BUILD_VALIDATION
                    || !buildRun.id().equals(artifact.ownerId())
                    || !BuildValidationArtifacts.COMPILE_COMMANDS_JSON.equals(artifact.artifactType())) {
                warnings.add(Map.of("source", "compileDatabase", "reason", "ARTIFACT_SCOPE_MISMATCH"));
                return null;
            }
            byte[] bytes = resource.resource().getContentAsByteArray();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            warnings.add(Map.of(
                    "source", "compileDatabase",
                    "reason", "ARTIFACT_READ_FAILED",
                    "exception", ex.getClass().getSimpleName()
            ));
            return null;
        }
    }

    private Map<String, Object> artifactJson(
            DependencyAnalysisView run,
            BuildValidationRunView buildRun,
            SnapshotReference sourceSnapshot,
            SnapshotReference targetSnapshot,
            List<DependencyAnalysisResultItem> items,
            List<Object> warnings,
            Map<String, Object> summary
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", "dependency-analysis.v1");
        value.put("analysisRunId", run.id());
        value.put("projectId", run.projectId());
        value.put("sourceRevisionId", run.sourceRevisionId());
        value.put("sourceBuildRunId", buildRun.id());
        value.put("targetEnvironmentId", run.targetEnvironmentId());
        value.put("environmentRefresh", true);
        value.put("sourceSnapshotId", sourceSnapshot.snapshotId());
        value.put("targetSnapshotId", targetSnapshot.snapshotId());
        value.put("sourceSnapshotCollectedAt", sourceSnapshot.collectedAt());
        value.put("targetSnapshotCollectedAt", targetSnapshot.collectedAt());
        value.put("environmentRefreshSummary", Map.of(
                "enabled", true,
                "source", sourceSnapshot.toSummary(),
                "target", targetSnapshot.toSummary()
        ));
        value.put("summary", summary);
        value.put("warnings", warnings);
        value.put("items", items);
        return value;
    }

    private Map<String, Object> artifactSummary(List<DependencyAnalysisResultItem> items, Map<String, Object> environmentRefresh) {
        Map<String, Object> byRisk = new LinkedHashMap<>();
        Map<String, Object> byStatus = new LinkedHashMap<>();
        for (DependencyAnalysisResultItem item : items) {
            byRisk.merge(item.riskLevel().name(), 1, (a, b) -> (Integer) a + (Integer) b);
            byStatus.merge(item.targetMatchStatus().name(), 1, (a, b) -> (Integer) a + (Integer) b);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", items.size());
        summary.put("byRisk", byRisk);
        summary.put("byTargetMatchStatus", byStatus);
        summary.put("environmentRefresh", environmentRefresh);
        return summary;
    }

    private SnapshotReference refreshEnvironment(
            TaskExecutionContext context,
            UUID projectId,
            UUID environmentId,
            String side,
            String message,
            int progressStart,
            int progressEnd
    ) {
        context.progress(progressStart, message);
        context.throwIfCancellationRequested();
        OffsetDateTime startedAt = OffsetDateTime.now();
        try {
            EnvironmentCollectionResult result = sshRuntimeService.collectEnvironment(new EnvironmentTaskInput(
                    projectId,
                    environmentId,
                    null,
                    ENVIRONMENT_REFRESH_TIMEOUT_SECONDS,
                    "ENV_COLLECT",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            ), stageContext(context, progressStart, progressEnd));
            OffsetDateTime collectedAt = repository.findSnapshotCollectedAt(result.snapshotId()).orElse(OffsetDateTime.now());
            return new SnapshotReference(side, environmentId, result.snapshotId(), collectedAt, startedAt, OffsetDateTime.now());
        } catch (TaskExecutionException ex) {
            throw new TaskExecutionException(ex.errorCode(), message + "失败：" + ex.safeMessage(), ex);
        }
    }

    private Map<String, Object> environmentRefreshSummary(SnapshotReference sourceSnapshot, SnapshotReference targetSnapshot) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("enabled", true);
        summary.put("sourceSnapshotId", sourceSnapshot.snapshotId());
        summary.put("targetSnapshotId", targetSnapshot.snapshotId());
        summary.put("sourceSnapshotCollectedAt", sourceSnapshot.collectedAt());
        summary.put("targetSnapshotCollectedAt", targetSnapshot.collectedAt());
        summary.put("sourceDurationMs", Duration.between(sourceSnapshot.startedAt(), sourceSnapshot.finishedAt()).toMillis());
        summary.put("targetDurationMs", Duration.between(targetSnapshot.startedAt(), targetSnapshot.finishedAt()).toMillis());
        return summary;
    }

    private TaskExecutionContext stageContext(TaskExecutionContext delegate, int start, int end) {
        return new TaskExecutionContext() {
            @Override
            public <T> T publish(java.util.function.Supplier<T> operation) {
                return delegate.publish(operation);
            }
            @Override
            public cn.rvport.backend.task.TaskView task() {
                return delegate.task();
            }

            @Override
            public <T> T input(Class<T> inputType) {
                return delegate.input(inputType);
            }

            @Override
            public void progress(int progress, String message) {
                int bounded = Math.max(0, Math.min(100, progress));
                int mapped = start + Math.round((end - start) * (bounded / 100.0f));
                delegate.progress(mapped, message);
            }

            @Override
            public Optional<TaskLogResponse> log(TaskLogType logType, String content) {
                return delegate.log(logType, content);
            }

            @Override
            public boolean isCancellationRequested() {
                return delegate.isCancellationRequested();
            }

            @Override
            public void throwIfCancellationRequested() {
                delegate.throwIfCancellationRequested();
            }

            @Override
            public void registerLocalProcess(Process process, Duration gracePeriod) {
                delegate.registerLocalProcess(process, gracePeriod);
            }

            @Override
            public void registerCancellableResource(CancellableResource resource) {
                delegate.registerCancellableResource(resource);
            }

            @Override
            public void registerRemoteProcess(UUID environmentId, Long remotePid, Long remotePgid, String commandMarker) {
                delegate.registerRemoteProcess(environmentId, remotePid, remotePgid, commandMarker);
            }
        };
    }

    private String html(Map<String, Object> json) {
        try {
            String rows = htmlRows(json.get("items"));
            Map<?, ?> summary = mapValue(json.get("summary"));
            String riskChips = chips(mapValue(summary.get("byRisk")));
            String statusChips = chips(mapValue(summary.get("byTargetMatchStatus")));
            String refreshBlock = refreshBlock(json);
            String escapedJson = escapeHtml(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json));
            return """
                    <!doctype html>
                    <html lang="zh-CN">
                    <head><meta charset="utf-8"><title>依赖分析报告</title>
                    <style>
                    body{font-family:Arial,sans-serif;margin:24px;color:#1f2937;background:#f8fafc}
                    h1{margin:0 0 16px}.chips{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0 20px}.chip{border:1px solid #cbd5e1;border-radius:4px;padding:5px 8px;background:#fff}.chip b{margin-right:6px}
                    .meta{background:#fff;border:1px solid #d7dee8;padding:12px;margin:0 0 16px;font-size:13px}.meta div{margin:4px 0}
                    table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #d7dee8}th,td{padding:8px 10px;border-bottom:1px solid #e5e7eb;text-align:left;vertical-align:top;font-size:13px}
                    th{background:#eef2f7}.mono{font-family:ui-monospace,SFMono-Regular,Consolas,monospace}.risk-CRITICAL,.risk-HIGH{color:#b42318}.risk-MEDIUM{color:#a15c07}.risk-LOW,.risk-INFO{color:#087443}
                    details{margin-top:18px}pre{white-space:pre-wrap;background:#fff;padding:16px;border:1px solid #d7dee8;max-height:520px;overflow:auto}
                    </style></head>
                    <body>
                    <h1>依赖分析报告</h1>
                    __REFRESH_BLOCK__
                    <div class="chips">__RISK_CHIPS____STATUS_CHIPS__</div>
                    <table><thead><tr><th>类型</th><th>名称</th><th>依赖版本</th><th>源包</th><th>源检测版本</th><th>目标包</th><th>目标检测版本</th><th>版本关系</th><th>风险/状态</th><th>位置</th><th>建议</th></tr></thead><tbody>__ROWS__</tbody></table>
                    <details><summary>查看完整 JSON</summary><pre>__JSON__</pre></details>
                    </body></html>
                    """
                    .replace("__REFRESH_BLOCK__", refreshBlock)
                    .replace("__RISK_CHIPS__", riskChips)
                    .replace("__STATUS_CHIPS__", statusChips)
                    .replace("__ROWS__", rows)
                    .replace("__JSON__", escapedJson);
        } catch (Exception ex) {
            return "<!doctype html><html><body><h1>依赖分析报告</h1><p>报告生成失败</p></body></html>";
        }
    }

    private String htmlRows(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return "<tr><td colspan=\"11\">无依赖分析条目</td></tr>";
        }
        StringBuilder rows = new StringBuilder();
        for (Object raw : list) {
            if (!(raw instanceof DependencyAnalysisResultItem item)) {
                continue;
            }
            rows.append("<tr>")
                    .append(td(item.dependencyType().name(), "mono"))
                    .append(td(item.dependencyName(), ""))
                    .append(td(item.dependencyVersion(), ""))
                    .append(td(packageLabel(item.sourceProviderPackage(), item.sourceProviderVersion()), "mono"))
                    .append(td(item.sourceDetectedVersion(), "mono"))
                    .append(td(packageLabel(item.targetProviderPackage(), item.targetProviderVersion()), "mono"))
                    .append(td(item.targetDetectedVersion(), "mono"))
                    .append(td(item.providerVersionStatus(), "mono"))
                    .append(td(item.riskLevel().name() + " / " + item.targetMatchStatus().name(), "risk-" + item.riskLevel().name()))
                    .append(td(item.locationLabel(), "mono"))
                    .append(td(item.recommendation(), ""))
                    .append("</tr>");
        }
        return rows.isEmpty() ? "<tr><td colspan=\"11\">无依赖分析条目</td></tr>" : rows.toString();
    }

    private String chips(Map<?, ?> counts) {
        if (counts == null || counts.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder();
        counts.forEach((key, value) -> html.append("<span class=\"chip\"><b>")
                .append(escapeHtml(String.valueOf(key)))
                .append("</b>")
                .append(escapeHtml(String.valueOf(value)))
                .append("</span>"));
        return html.toString();
    }

    private Map<?, ?> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private String refreshBlock(Map<String, Object> json) {
        return """
                <div class="meta">
                <div><b>环境刷新</b>：已启用，本报告使用任务执行时重新采集的快照。</div>
                <div>Source Snapshot：<span class="mono">__SOURCE_ID__</span>，采集时间：<span class="mono">__SOURCE_TIME__</span></div>
                <div>Target Snapshot：<span class="mono">__TARGET_ID__</span>，采集时间：<span class="mono">__TARGET_TIME__</span></div>
                </div>
                """
                .replace("__SOURCE_ID__", escapeHtml(String.valueOf(json.get("sourceSnapshotId"))))
                .replace("__SOURCE_TIME__", escapeHtml(String.valueOf(json.get("sourceSnapshotCollectedAt"))))
                .replace("__TARGET_ID__", escapeHtml(String.valueOf(json.get("targetSnapshotId"))))
                .replace("__TARGET_TIME__", escapeHtml(String.valueOf(json.get("targetSnapshotCollectedAt"))));
    }

    private String packageLabel(String name, String version) {
        name = name == null ? "" : name;
        version = version == null ? "" : version;
        if (name.isBlank()) {
            return "";
        }
        return version.isBlank() ? name : name + "@" + version;
    }

    private String td(String value, String cssClass) {
        String cls = cssClass == null || cssClass.isBlank() ? "" : " class=\"" + escapeHtml(cssClass) + "\"";
        return "<td" + cls + ">" + escapeHtml(value == null ? "" : value) + "</td>";
    }

    private String escapeHtml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record SnapshotReference(
            String side,
            UUID environmentId,
            UUID snapshotId,
            OffsetDateTime collectedAt,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt
    ) {
        Map<String, Object> toSummary() {
            return Map.of(
                    "side", side,
                    "environmentId", environmentId,
                    "snapshotId", snapshotId,
                    "collectedAt", collectedAt,
                    "durationMs", Duration.between(startedAt, finishedAt).toMillis()
            );
        }
    }
}
