package cn.rvport.backend.kb.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.codexprovider.CodexProviderResolvedConfig;
import cn.rvport.backend.codexprovider.CodexProviderType;
import cn.rvport.backend.codexprovider.CodexProviderWireApi;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import cn.rvport.backend.jooq.tables.records.ModelProfilesRecord;
import cn.rvport.backend.kb.FindingView;
import cn.rvport.backend.kb.KbScanRepository;
import cn.rvport.backend.kb.KbScanView;
import cn.rvport.backend.modelprofile.ModelProfileService;
import cn.rvport.backend.modelprofile.ModelProfileStage;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.TaskCreateCommand;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.task.TaskStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KbMigrationServiceTests {

    final UUID project = UUID.randomUUID();
    final UUID actor = UUID.randomUUID();
    final UUID scanRun = UUID.randomUUID();
    final UUID revision = UUID.randomUUID();
    final UUID profileId = UUID.randomUUID();
    final UUID runtimeId = UUID.randomUUID();

    final KbMigrationRepository repository = mock(KbMigrationRepository.class);
    final KbScanRepository kbScanRepository = mock(KbScanRepository.class);
    final SourceRepository sourceRepository = mock(SourceRepository.class);
    final ProjectAccessService projectAccessService = mock(ProjectAccessService.class);
    final TaskService taskService = mock(TaskService.class);
    final ModelProfileService models = mock(ModelProfileService.class);
    final AuditLogService auditLogService = mock(AuditLogService.class);
    final KbMigrationService service = new KbMigrationService(repository, kbScanRepository, sourceRepository,
            projectAccessService, taskService, models, auditLogService);

    @BeforeEach
    void setup() {
        when(sourceRepository.findActive(project)).thenReturn(Optional.of(revision(
                "/tmp/working-copy")));
        when(kbScanRepository.findLatestSucceededRunBySourceRevision(project, revision))
                .thenReturn(Optional.of(scanView(cn.rvport.backend.task.TaskStatus.SUCCEEDED)));
        when(kbScanRepository.listAllFindingViews(scanRun)).thenReturn(List.of(
                finding("finding:a", "intrinsic_call"),
                finding("finding:b", "macro_invocation")));
        var managed = new AgentRuntimesRecord();
        managed.setId(runtimeId);
        managed.setAgentType("OPENCODE");
        var profile = new ModelProfilesRecord();
        profile.setId(profileId);
        profile.setOwnerUserId(actor);
        var config = new AgentModelRuntimeConfig(managed, profile, new CodexProviderResolvedConfig(true, "opencode",
                CodexProviderType.OPENAI_COMPATIBLE, "http://mock.invalid", "fixture", CodexProviderWireApi.CHAT,
                "fake", 10, 1800));
        when(models.resolveAgentForStage(ModelProfileStage.KB_MIGRATION, actor)).thenReturn(config);
        when(taskService.create(any())).thenReturn(new TaskResponse(UUID.randomUUID(), project, "KB_MIGRATION",
                TaskStatus.PENDING, 0, null, null, null, null, null, null, null, Map.of()));
    }

    @Test
    void 创建成功固定执行身份并落运行() {
        var task = service.create(project, new KbMigrationRequest(null), actor, false, null);

        assertThat(task.type()).isEqualTo("KB_MIGRATION");
        var command = ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(taskService).create(command.capture());
        assertThat(command.getValue().inputSnapshot()).containsKeys("projectId", "scanRunId", "sourceRevisionId",
                "selection");
        var selection = (Map<?, ?>) command.getValue().inputSnapshot().get("selection");
        assertThat(selection.get("modelProfileId")).isEqualTo(profileId.toString());
        assertThat(selection.get("agentRuntimeId")).isEqualTo(runtimeId.toString());
        verify(repository).createRun(project, scanRun, revision, task.id(), profileId, runtimeId);
        verify(auditLogService).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 无成功扫描与零可投影条目拒绝() {
        when(kbScanRepository.findLatestSucceededRunBySourceRevision(project, revision)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(project, null, actor, false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("KB_MIGRATION_SCAN_NOT_SUCCEEDED");

        when(kbScanRepository.findLatestSucceededRunBySourceRevision(project, revision))
                .thenReturn(Optional.of(scanView(cn.rvport.backend.task.TaskStatus.SUCCEEDED)));
        when(kbScanRepository.listAllFindingViews(scanRun)).thenReturn(List.of(finding("finding:x", "test_asset")));
        assertThatThrownBy(() -> service.create(project, null, actor, false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("KB_MIGRATION_NO_ENTRIES");
        verify(taskService, never()).create(any());
    }

    @Test
    void 阶段未绑定与非法指定扫描拒绝() {
        when(models.resolveAgentForStage(ModelProfileStage.KB_MIGRATION, actor))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "MODEL_STAGE_NOT_BOUND", "未绑定"));
        assertThatThrownBy(() -> service.create(project, null, actor, false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("KB_MIGRATION_MODEL_NOT_CONFIGURED");

        var foreign = new KbScanView(scanRun, UUID.randomUUID(), UUID.randomUUID(), revision, null, null,
                cn.rvport.backend.task.TaskStatus.SUCCEEDED, null, null, null);
        when(kbScanRepository.findById(scanRun)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.create(project, new KbMigrationRequest(scanRun), actor, false, null))
                .isInstanceOf(ApiException.class);

        when(kbScanRepository.findById(scanRun)).thenReturn(Optional.of(scanView(cn.rvport.backend.task.TaskStatus.FAILED)));
        assertThatThrownBy(() -> service.create(project, new KbMigrationRequest(scanRun), actor, false, null))
                .extracting("code").isEqualTo("KB_MIGRATION_SCAN_NOT_SUCCEEDED");
    }

    @Test
    void 列表分页归一与详情鉴权() {
        when(repository.countRuns(project)).thenReturn(1L);
        when(repository.listRuns(project, 0, 100)).thenReturn(List.of(runView()));
        var page = service.list(project, -5, 500, actor, false);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).status()).isEqualTo("SUCCEEDED");

        var view = runView();
        when(repository.findRun(view.id())).thenReturn(Optional.of(view));
        var detail = service.detail(view.id(), actor, false);
        assertThat(detail.itemCount()).isEqualTo(3);
        assertThat(KbMigrationResponse.from(view, null).artifacts()).isEmpty();
        verify(projectAccessService, org.mockito.Mockito.times(2)).requireProjectAccess(project, actor, false);

        when(repository.findRun(UUID.randomUUID())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail(UUID.randomUUID(), actor, false))
                .isInstanceOf(ApiException.class);
    }

    private KbMigrationRunView runView() {
        return new KbMigrationRunView(UUID.randomUUID(), project, UUID.randomUUID(), scanRun, revision, "SUCCEEDED",
                Map.of(), 3, 2, 1, 0, profileId, runtimeId, null, null, null);
    }

    private SourceRevisionView revision(String workingCopy) {
        return new SourceRevisionView(revision, project, null, "v1", null, null, null, null, null, workingCopy,
                null, true, actor, null);
    }

    private KbScanView scanView(cn.rvport.backend.task.TaskStatus status) {
        return new KbScanView(scanRun, project, UUID.randomUUID(), revision, null, null, status,
                null, null, null);
    }

    private FindingView finding(String scannerId, String factKind) {
        return new FindingView(UUID.randomUUID(), project, scanRun, revision, "rule", "category", "high", "标题",
                "原因", "src/a.c", 1, 1, "sym", Map.of("scannerFindingId", scannerId, "factKind", factKind,
                        "actionable", true), "建议", "fp", "DETECTED", null);
    }
}
