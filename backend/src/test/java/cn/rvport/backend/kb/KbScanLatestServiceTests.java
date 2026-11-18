package cn.rvport.backend.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.config.RvPortProperties;
import cn.rvport.backend.migration.NodeReportInclusionService;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.workflow.DomainLatestFreshness;
import cn.rvport.backend.workflow.DomainLatestResponse;
import cn.rvport.backend.workflow.ProjectContextService;
import cn.rvport.backend.workflow.ProjectContextSnapshot;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KbScanLatestServiceTests {
    @Mock private KbScanContextService scanContextService;

    @Mock
    private KbScanRepository kbScanRepository;
    @Mock
    private SourceRepository sourceRepository;
    @Mock
    private ProjectAccessService projectAccessService;
    @Mock
    private TaskService taskService;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private ProjectContextService projectContextService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private StoredArtifactService storedArtifactService;
    @Mock
    private NodeReportInclusionService nodeReportInclusionService;

    @Test
    void latestRetainsHistoryWhenActiveSourceIsMissingOrRuntimeChanged() throws Exception {
        UUID project = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        var historical = KbContextFixtures.scan(project, source, TaskStatus.SUCCEEDED, KbContextFixtures.CONTEXT);
        when(kbScanRepository.findLatestHistoricalRun(project)).thenReturn(Optional.of(historical));
        for (var selected : java.util.List.of(
                new KbScanContextService.Selection(project, null, KbContextFixtures.ENV, 7L, null),
                new KbScanContextService.Selection(project, source, KbContextFixtures.ENV, 8L, null),
                new KbScanContextService.Selection(project, source, KbContextFixtures.ENV, 7L, null))) {
            when(scanContextService.current(project)).thenReturn(selected);
            var result = service().latest(project, UUID.randomUUID(), false);
            assertThat(result.freshness()).isEqualTo(DomainLatestFreshness.STALE);
            assertThat(result.result().status()).isEqualTo("SUCCEEDED");
            assertThat(result.result().current()).isFalse();
            assertThat(result.result().staleReasons()).isNotEmpty();
            var json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result.result());
            assertThat(json).contains("executionContext", "message_en", "buildRunId")
                    .doesNotContain("/source", "password", "objectKey", "inputSnapshot");
        }
    }

    @Test
    void latestReturnsCurrentFailedRunWithoutFallingBack() {
        UUID projectId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        KbScanView failed = run(projectId, runId, taskId, sourceRevisionId, TaskStatus.FAILED);
        when(scanContextService.current(projectId)).thenReturn(KbContextFixtures.ready(projectId, sourceRevisionId));
        when(scanContextService.latest(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(failed));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task(projectId, taskId, TaskStatus.FAILED)));

        DomainLatestResponse<KbScanResponse> response = service().latest(projectId, UUID.randomUUID(), false);

        assertThat(response.freshness()).isEqualTo(DomainLatestFreshness.CURRENT);
        assertThat(response.execution().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(response.result().id()).isEqualTo(runId);
        assertThat(response.result().current()).isTrue();
    }

    @Test
    void latestReturnsEmptyWhenCurrentSourceMissing() {
        UUID projectId = UUID.randomUUID();
        when(scanContextService.current(projectId)).thenReturn(KbContextFixtures.ready(projectId, null));

        DomainLatestResponse<KbScanResponse> response = service().latest(projectId, UUID.randomUUID(), false);

        assertThat(response.freshness()).isEqualTo(DomainLatestFreshness.EMPTY);
        assertThat(response.execution()).isNull();
        assertThat(response.result()).isNull();
    }

    @Test
    void latestReturnsStaleRunWhenCurrentContextHasNoRunAndChecksAccess() {
        UUID projectId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();
        UUID currentSourceRevisionId = UUID.randomUUID();
        UUID staleSourceRevisionId = UUID.randomUUID();
        UUID staleRunId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        KbScanView stale = run(projectId, staleRunId, taskId, staleSourceRevisionId, TaskStatus.SUCCEEDED);
        when(scanContextService.current(projectId)).thenReturn(KbContextFixtures.ready(projectId, currentSourceRevisionId));
        when(scanContextService.latest(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        when(kbScanRepository.findLatestHistoricalRun(projectId)).thenReturn(Optional.of(stale));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task(projectId, taskId, TaskStatus.SUCCEEDED)));

        DomainLatestResponse<KbScanResponse> response = service().latest(projectId, actorUserId, false);

        assertThat(response.freshness()).isEqualTo(DomainLatestFreshness.STALE);
        assertThat(response.execution().status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(response.result().id()).isEqualTo(staleRunId);
        verify(projectAccessService).requireProjectAccess(projectId, actorUserId, false);
    }

    private KbScanService service() {
        var service = new KbScanService(
                kbScanRepository,
                sourceRepository,
                projectAccessService,
                taskService,
                taskRepository,
                projectContextService,
                auditLogService,
                new RvPortProperties(null, null, null),
                storedArtifactService,
                nodeReportInclusionService
        );
        org.springframework.test.util.ReflectionTestUtils.setField(service, "scanContextService", scanContextService);
        return service;
    }

    private KbScanView run(UUID projectId, UUID runId, UUID taskId, UUID sourceRevisionId, TaskStatus status) {
        return new KbScanView(
                runId,
                projectId,
                taskId,
                sourceRevisionId,
                "rules",
                Map.of(),
                status,
                Map.of("findingCount", 1),
                OffsetDateTime.now(),
                OffsetDateTime.now(), KbContextFixtures.CONTEXT
        );
    }

    private TaskView task(UUID projectId, UUID taskId, TaskStatus status) {
        return new TaskView(
                taskId,
                projectId,
                "KB_SCAN",
                status,
                0,
                "done",
                UUID.randomUUID(),
                OffsetDateTime.now(),
                null,
                null,
                OffsetDateTime.now(),
                60,
                status == TaskStatus.FAILED ? "KB_SCAN_FAILED" : null,
                status == TaskStatus.FAILED ? "failed" : null,
                0
        );
    }
}
