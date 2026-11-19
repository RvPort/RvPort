package cn.rvport.backend.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskMetadataResolverTests {

    private final TaskMetadataResolver resolver = new TaskMetadataResolver();

    @Test
    void resolvesBuildTaskMetadata() {
        UUID buildRunId = UUID.randomUUID();
        TaskCreateCommand build = resolver.resolve(new TaskCreateCommand(
                UUID.randomUUID(),
                RvPortTaskTypes.BUILD_VALIDATION,
                Map.of("buildRunId", buildRunId, "buildScope", "SOURCE_ONLY"),
                UUID.randomUUID(),
                300,
                "build"
        ));

        assertThat(build.stageId()).isEqualTo("x86-build");
        assertThat(build.resourceType()).isEqualTo("build_run");
        assertThat(build.resourceId()).isEqualTo(buildRunId);
        assertThat(build.resourceMode()).isEqualTo("SOURCE_ONLY");

    }

    @Test
    void preservesExplicitMetadataAndFallsBackForUnknownTaskType() {
        UUID resourceId = UUID.randomUUID();
        TaskCreateCommand explicit = resolver.resolve(new TaskCreateCommand(
                UUID.randomUUID(),
                "CUSTOM_TASK",
                Map.of(),
                UUID.randomUUID(),
                null,
                "custom",
                "manual-stage",
                "manual-resource",
                resourceId,
                "manual-mode",
                "SOURCE_X86"
        ));

        assertThat(explicit.stageId()).isEqualTo("manual-stage");
        assertThat(explicit.resourceType()).isEqualTo("manual-resource");
        assertThat(explicit.resourceId()).isEqualTo(resourceId);

        TaskCreateCommand unknown = resolver.resolve(new TaskCreateCommand(
                UUID.randomUUID(),
                "CUSTOM_TASK",
                Map.of(),
                UUID.randomUUID(),
                null,
                "custom"
        ));

        assertThat(unknown.stageId()).isEqualTo("task");
        assertThat(unknown.resourceType()).isEqualTo("CUSTOM_TASK");
        assertThat(unknown.resourceId()).isNull();
    }

    @Test
    void resolvesEveryKnownTaskTypeToStableStageResourceAndIdentifier() {
        UUID id = UUID.randomUUID();
        List<ExpectedMetadata> cases = List.of(
                expected(RvPortTaskTypes.SOURCE_GIT_SYNC, "source", "source_import", "sourceImportId"),
                expected(RvPortTaskTypes.SOURCE_DEPLOY, "source", "source_deploy_run", "sourceDeploymentId"),
                expected(RvPortTaskTypes.ENV_TEST_CONNECTION, "environment", "environment", "environmentId"),
                expected(RvPortTaskTypes.ENV_RUN_SCRIPT, "environment", "environment", "environmentId"),
                expected(RvPortTaskTypes.ENV_COLLECT, "environment", "environment_snapshot", "environmentSnapshotId"),
                expected(RvPortTaskTypes.BUILD_VALIDATION, "build-validation", "build_run", "buildRunId"),
                expected(RvPortTaskTypes.DEPENDENCY_ANALYSIS, "dependency-analysis", "dependency_analysis_run", "analysisRunId"),
                expected(RvPortTaskTypes.DEPENDENCY_AUTO_FIX, "dependency-analysis", "dependency_auto_fix_run", "autoFixRunId"),
                expected(RvPortTaskTypes.KB_SCAN, "kb-scan", "kb_scan_run", "scanRunId"),
                expected(RvPortTaskTypes.REPORT_GENERATE, "evaluation-report", "report", "reportId"),
                expected(RvPortTaskTypes.REPORT_COMPARE, "evaluation-report", "report_comparison_run", "comparisonRunId"),
                expected(RvPortTaskTypes.RECHECK_DIFF, "evaluation-report", "recheck_run", "recheckRunId"),
                expected(RvPortTaskTypes.AUTOMATED_TEST, "automated-test", "test_run", "testRunId"),
                expected(RvPortTaskTypes.TEST_LOG_COMPARE, "automated-test", "test_comparison_run", "comparisonRunId"),
                expected(RvPortTaskTypes.ARCHIVE_PROJECT, "archive", "project_archive", null)
        );

        for (ExpectedMetadata item : cases) {
            Map<String, ?> input = item.idKey == null ? Map.of() : Map.of(item.idKey, id.toString());
            TaskCreateCommand resolved = resolver.resolve(command(item.type, input));
            assertThat(resolved.stageId()).as(item.type).isEqualTo(item.stage);
            assertThat(resolved.resourceType()).as(item.type).isEqualTo(item.resourceType);
            assertThat(resolved.resourceId()).as(item.type).isEqualTo(item.idKey == null ? null : id);
        }
    }

    @Test
    void resolvesModesRolesAndFallbackIdentifiersIncludingInvalidValues() {
        UUID fallbackId = UUID.randomUUID();
        TaskCreateCommand environment = resolver.resolve(command(RvPortTaskTypes.ENV_COLLECT,
                Map.of("environmentSnapshotId", "invalid", "environmentId", fallbackId, "role", "TARGET_RISCV")));
        TaskCreateCommand script = resolver.resolve(command(RvPortTaskTypes.ENV_RUN_SCRIPT,
                Map.of("environmentId", fallbackId, "scriptType", "CUSTOM_BASH", "environmentRole", "SOURCE_X86")));
        TaskCreateCommand automatedTest = resolver.resolve(command(RvPortTaskTypes.AUTOMATED_TEST,
                Map.of("testBatchId", fallbackId.toString(), "testBatchRole", "TARGET")));
        TaskCreateCommand blankValues = resolver.resolve(command(RvPortTaskTypes.ENV_COLLECT,
                Map.of("environmentSnapshotId", " ", "environmentId", "bad-id", "role", " ")));

        assertThat(environment.resourceId()).isEqualTo(fallbackId);
        assertThat(environment.environmentRole()).isEqualTo("TARGET_RISCV");
        assertThat(script.resourceMode()).isEqualTo("CUSTOM_BASH");
        assertThat(script.environmentRole()).isEqualTo("SOURCE_X86");
        assertThat(automatedTest.resourceId()).isEqualTo(fallbackId);
        assertThat(automatedTest.resourceMode()).isEqualTo("TARGET");
        assertThat(blankValues.resourceId()).isNull();
        assertThat(blankValues.environmentRole()).isEqualTo(" ");
    }

    @Test
    void anyExplicitMetadataFieldPreventsInference() {
        UUID id = UUID.randomUUID();
        List<TaskCreateCommand> commands = List.of(
                new TaskCreateCommand(id, RvPortTaskTypes.KB_SCAN, Map.of(), id, null, "m", "stage", null, null, null, null),
                new TaskCreateCommand(id, RvPortTaskTypes.KB_SCAN, Map.of(), id, null, "m", null, "resource", null, null, null),
                new TaskCreateCommand(id, RvPortTaskTypes.KB_SCAN, Map.of(), id, null, "m", null, null, id, null, null),
                new TaskCreateCommand(id, RvPortTaskTypes.KB_SCAN, Map.of(), id, null, "m", null, null, null, "mode", null),
                new TaskCreateCommand(id, RvPortTaskTypes.KB_SCAN, Map.of(), id, null, "m", null, null, null, null, "role")
        );

        for (TaskCreateCommand command : commands) {
            assertThat(resolver.resolve(command)).isSameAs(command);
        }
    }

    private TaskCreateCommand command(String type, Map<String, ?> input) {
        return new TaskCreateCommand(UUID.randomUUID(), type, input, UUID.randomUUID(), 60, "message");
    }

    private ExpectedMetadata expected(String type, String stage, String resourceType, String idKey) {
        return new ExpectedMetadata(type, stage, resourceType, idKey);
    }

    private record ExpectedMetadata(String type, String stage, String resourceType, String idKey) {
    }
}
