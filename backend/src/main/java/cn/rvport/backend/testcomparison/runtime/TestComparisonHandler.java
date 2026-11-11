package cn.rvport.backend.testcomparison.runtime;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.automatedtest.AutomatedTestRepository;
import cn.rvport.backend.automatedtest.AutomatedTestRunView;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskErrorCode;
import cn.rvport.backend.task.TaskExecutionException;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.testcomparison.TestComparisonRepository;
import cn.rvport.backend.testcomparison.TestComparisonStatus;
import cn.rvport.backend.testcomparison.TestComparisonTaskInput;
import cn.rvport.backend.worker.TaskExecutionContext;
import cn.rvport.backend.worker.TaskAttemptLostException;
import cn.rvport.backend.worker.TaskHandler;
import cn.rvport.backend.worker.TaskHandlerResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TestComparisonHandler implements TaskHandler {

    private final TestComparisonRepository repository;
    private final AutomatedTestRepository automatedTestRepository;
    private final TestComparisonInputAggregator inputAggregator;
    private final TestComparisonAgentRuntime agentRuntime;
    private final StoredArtifactService storedArtifactService;
    private final ArchiveCandidateRegistrar archiveCandidateRegistrar;

    public TestComparisonHandler(
            TestComparisonRepository repository,
            AutomatedTestRepository automatedTestRepository,
            TestComparisonInputAggregator inputAggregator,
            TestComparisonAgentRuntime agentRuntime,
            StoredArtifactService storedArtifactService,
            ArchiveCandidateRegistrar archiveCandidateRegistrar
    ) {
        this.repository = repository;
        this.automatedTestRepository = automatedTestRepository;
        this.inputAggregator = inputAggregator;
        this.agentRuntime = agentRuntime;
        this.storedArtifactService = storedArtifactService;
        this.archiveCandidateRegistrar = archiveCandidateRegistrar;
    }

    @Override
    public String taskType() {
        return RvPortTaskTypes.TEST_LOG_COMPARE;
    }

    @Override
    public TaskHandlerResult handle(TaskExecutionContext context) {
        TestComparisonTaskInput input = context.input(TestComparisonTaskInput.class);
        context.publish(() -> { repository.markRunning(input.comparisonRunId()); return null; });
        context.progress(10, "开始聚合测试结果");
        try {
            if (!java.util.Objects.equals(input.projectId(), context.task().projectId())) { throw cn.rvport.backend.modelprofile.ModelExecutionSelection.missing(); }
            AutomatedTestRunView source = automatedTestRepository.findById(input.sourceTestRunId())
                    .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "源测试记录不存在", null));
            AutomatedTestRunView target = automatedTestRepository.findById(input.targetTestRunId())
                    .orElseThrow(() -> new TaskExecutionException(TaskErrorCode.RESOURCE_NOT_FOUND, "目标测试记录不存在", null));
            TestComparisonInputBundle bundle = inputAggregator.aggregate(source, target);
            var config = agentRuntime.resolveExecution(input.modelExecution(), context.task().createdBy(), context.task().projectId());
            context.log(cn.rvport.backend.task.TaskLogType.TOOL, input.modelExecution().attemptAudit(config));
            context.progress(35, "开始调用测试结果比较 agent");
            TestComparisonAgentResult result = agentRuntime.run(bundle, config);
            context.progress(85, "保存测试结果比较输出");
            var artifact = storedArtifactService.storeJson(
                    input.projectId(),
                    context.task().id(),
                    StoredArtifactOwnerType.TEST_COMPARISON,
                    input.comparisonRunId(),
                    "AGENT_OUTPUT",
                    result.agentOutput(),
                    Map.of("comparisonRunId", input.comparisonRunId(), "sourceTestRunId", input.sourceTestRunId(), "targetTestRunId", input.targetTestRunId()),
                    context
            );
            context.publish(() -> {
            repository.replaceFindings(input.comparisonRunId(), result.findings());
            automatedTestRepository.updateStats(
                    input.sourceTestRunId(),
                    integer(result.sourceStats().get("passedTests")),
                    integer(result.sourceStats().get("failedTests")),
                    integer(result.sourceStats().get("skippedTests"))
            );
            automatedTestRepository.updateStats(
                    input.targetTestRunId(),
                    integer(result.targetStats().get("passedTests")),
                    integer(result.targetStats().get("failedTests")),
                    integer(result.targetStats().get("skippedTests"))
            );
            repository.saveResult(
                    input.comparisonRunId(),
                    result.overallStatus(),
                    result.riskLevel(),
                    result.confidence(),
                    result.summary(),
                    result.recommendation(),
                    result.sourceStats(),
                    result.targetStats(),
                    bundle.truncated(),
                    bundle.summary(),
                    artifact.id()
            );
            Map<String, Object> archiveSummary = new LinkedHashMap<>();
            archiveSummary.put("overallStatus", result.overallStatus() == null ? "" : result.overallStatus().name());
            archiveSummary.put("riskLevel", result.riskLevel() == null ? "" : result.riskLevel().name());
            archiveSummary.put("confidence", result.confidence());
            archiveSummary.put("summary", result.summary());
            archiveSummary.put("recommendation", result.recommendation());
            archiveSummary.put("sourceStats", result.sourceStats());
            archiveSummary.put("targetStats", result.targetStats());
            archiveSummary.put("inputTruncated", bundle.truncated());
            repository.findById(input.comparisonRunId()).ifPresent(run -> archiveCandidateRegistrar.registerTestComparison(
                    run,
                    source.sourceRevisionId(),
                    archiveSummary,
                    artifact
            ));
            return null;
            });
            context.progress(95, "测试结果比较完成");
            return TaskHandlerResult.success("测试结果比较完成");
        } catch (TaskAttemptLostException ex) {
            throw ex;
        } catch (TestComparisonAgentOutputException ex) {
            storedArtifactService.storeJson(
                    input.projectId(),
                    context.task().id(),
                    StoredArtifactOwnerType.TEST_COMPARISON,
                    input.comparisonRunId(),
                    "AGENT_OUTPUT",
                    ex.agentOutput(),
                    Map.of(
                            "comparisonRunId", input.comparisonRunId(),
                            "sourceTestRunId", input.sourceTestRunId(),
                            "targetTestRunId", input.targetTestRunId(),
                            "failed", true,
                            "errorCode", ex.code()
                    ), context
            );
            fail(context, input, ex.code(), ex.getMessage());
            throw new TaskExecutionException(TaskErrorCode.REMOTE_COMMAND_FAILED, ex.getMessage(), ex);
        } catch (ApiException ex) {
            fail(context, input, ex.code(), ex.getMessage());
            throw new TaskExecutionException(TaskErrorCode.REMOTE_COMMAND_FAILED, ex.getMessage(), ex);
        } catch (TaskExecutionException ex) {
            fail(context, input, ex.errorCode(), ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            fail(context, input, "TEST_COMPARE_FAILED", "测试结果比较失败");
            throw ex;
        }
    }

    private void fail(TaskExecutionContext context, TestComparisonTaskInput input, String code, String message) {
        context.publish(() -> {
            repository.markFailed(input.comparisonRunId(), TestComparisonStatus.FAILED, code, message);
            return null;
        });
    }

    private Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
