package cn.rvport.backend.ai.migration;

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

class AiMigrationServiceTests {

    final UUID project = UUID.randomUUID();
    final UUID actor = UUID.randomUUID();
    final UUID runId = UUID.randomUUID();
    final UUID taskId = UUID.randomUUID();
    final UUID scanRun = UUID.randomUUID();
    final UUID revision = UUID.randomUUID();
    final UUID profileId = UUID.randomUUID();
    final UUID runtimeId = UUID.randomUUID();

    final AiMigrationRepository repository = mock(AiMigrationRepository.class);
    final KbScanRepository kbScanRepository = mock(KbScanRepository.class);
    final SourceRepository sourceRepository = mock(SourceRepository.class);
    final ProjectAccessService projectAccessService = mock(ProjectAccessService.class);
    final TaskService taskService = mock(TaskService.class);
    final ModelProfileService models = mock(ModelProfileService.class);
    final AuditLogService auditLogService = mock(AuditLogService.class);
    final cn.rvport.backend.artifact.StoredArtifactRepository storedArtifactRepository =
            mock(cn.rvport.backend.artifact.StoredArtifactRepository.class);
    final cn.rvport.backend.artifact.StoredArtifactService storedArtifactService =
            mock(cn.rvport.backend.artifact.StoredArtifactService.class);
    final AiMigrationService service = new AiMigrationService(repository, kbScanRepository, sourceRepository,
            projectAccessService, taskService, models, auditLogService, storedArtifactRepository, storedArtifactService);

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
        when(models.resolveAgentForStage(ModelProfileStage.AI_MIGRATION, actor)).thenReturn(config);
        when(taskService.create(any())).thenReturn(new TaskResponse(UUID.randomUUID(), project, "AI_MIGRATION",
                TaskStatus.PENDING, 0, null, null, null, null, null, null, null, Map.of()));
    }

    @Test
    void 创建成功固定执行身份并落运行() {
        var task = service.create(project, new AiMigrationRequest(null), actor, false, null);

        assertThat(task.type()).isEqualTo("AI_MIGRATION");
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
                .extracting("code").isEqualTo("AI_MIGRATION_SCAN_NOT_SUCCEEDED");

        when(kbScanRepository.findLatestSucceededRunBySourceRevision(project, revision))
                .thenReturn(Optional.of(scanView(cn.rvport.backend.task.TaskStatus.SUCCEEDED)));
        when(kbScanRepository.listAllFindingViews(scanRun)).thenReturn(List.of(finding("finding:x", "test_asset")));
        assertThatThrownBy(() -> service.create(project, null, actor, false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("AI_MIGRATION_NO_ENTRIES");
        verify(taskService, never()).create(any());
    }

    @Test
    void 阶段未绑定与非法指定扫描拒绝() {
        when(models.resolveAgentForStage(ModelProfileStage.AI_MIGRATION, actor))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "MODEL_STAGE_NOT_BOUND", "未绑定"));
        assertThatThrownBy(() -> service.create(project, null, actor, false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("AI_MIGRATION_MODEL_NOT_CONFIGURED");

        var foreign = new KbScanView(scanRun, UUID.randomUUID(), UUID.randomUUID(), revision, null, null,
                cn.rvport.backend.task.TaskStatus.SUCCEEDED, null, null, null);
        when(kbScanRepository.findById(scanRun)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.create(project, new AiMigrationRequest(scanRun), actor, false, null))
                .isInstanceOf(ApiException.class);

        when(kbScanRepository.findById(scanRun)).thenReturn(Optional.of(scanView(cn.rvport.backend.task.TaskStatus.FAILED)));
        assertThatThrownBy(() -> service.create(project, new AiMigrationRequest(scanRun), actor, false, null))
                .extracting("code").isEqualTo("AI_MIGRATION_SCAN_NOT_SUCCEEDED");
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
        assertThat(AiMigrationResponse.from(view, null).artifacts()).isEmpty();
        verify(projectAccessService, org.mockito.Mockito.times(2)).requireProjectAccess(project, actor, false);

        when(repository.findRun(UUID.randomUUID())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail(UUID.randomUUID(), actor, false))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 详情与列表携带产物元数据() {
        when(repository.countRuns(project)).thenReturn(1L);
        when(repository.listRuns(project, 0, 20)).thenReturn(List.of(runView()));
        when(repository.findRun(any())).thenReturn(Optional.of(runView()));
        var diff = new cn.rvport.backend.artifact.StoredArtifactView(UUID.randomUUID(), project, taskId,
                cn.rvport.backend.artifact.StoredArtifactOwnerType.AI_MIGRATION, runId, "MIGRATION_DIFF",
                "bucket", "key", "text/x-diff", 11, "sha", Map.of(), null);
        when(storedArtifactRepository.listByOwner(
                cn.rvport.backend.artifact.StoredArtifactOwnerType.AI_MIGRATION, runId)).thenReturn(List.of(diff));

        var detail = service.detail(runId, actor, false);
        assertThat(detail.artifacts()).hasSize(1);
        assertThat(detail.artifacts().get(0).artifactType()).isEqualTo("MIGRATION_DIFF");

        var page = service.list(project, 0, 20, actor, false);
        assertThat(page.items().get(0).artifacts()).hasSize(1);
    }

    @Test
    void 下载补丁与结果按类型取产物并带文件名() {
        when(repository.findRun(runId)).thenReturn(Optional.of(runView()));
        var diffArtifact = new cn.rvport.backend.artifact.StoredArtifactView(UUID.randomUUID(), project, taskId,
                cn.rvport.backend.artifact.StoredArtifactOwnerType.AI_MIGRATION, runId, "MIGRATION_DIFF",
                "bucket", "diff-key", "text/x-diff", 5, "sha", Map.of(), null);
        var resultArtifact = new cn.rvport.backend.artifact.StoredArtifactView(UUID.randomUUID(), project, taskId,
                cn.rvport.backend.artifact.StoredArtifactOwnerType.AI_MIGRATION, runId, "MIGRATION_RESULT",
                "bucket", "result-key", "application/json", 7, "sha", Map.of(), null);
        when(storedArtifactRepository.findByOwner(
                cn.rvport.backend.artifact.StoredArtifactOwnerType.AI_MIGRATION, runId, "MIGRATION_DIFF"))
                .thenReturn(Optional.of(diffArtifact));
        when(storedArtifactRepository.findByOwner(
                cn.rvport.backend.artifact.StoredArtifactOwnerType.AI_MIGRATION, runId, "MIGRATION_RESULT"))
                .thenReturn(Optional.of(resultArtifact));
        var diffBytes = new org.springframework.core.io.ByteArrayResource("+patch".getBytes());
        var resultBytes = new org.springframework.core.io.ByteArrayResource("{}".getBytes());
        when(storedArtifactService.download(diffArtifact.id()))
                .thenReturn(new cn.rvport.backend.artifact.StoredArtifactResource(diffArtifact, diffBytes));
        when(storedArtifactService.download(resultArtifact.id()))
                .thenReturn(new cn.rvport.backend.artifact.StoredArtifactResource(resultArtifact, resultBytes));

        var diff = service.downloadArtifact(runId, AiMigrationService.ArtifactKind.DIFF, actor, false);
        assertThat(diff.filename()).isEqualTo("migration-diff.patch");
        assertThat(diff.contentType()).isEqualTo("text/x-diff");
        assertThat(diff.resource()).isSameAs(diffBytes);

        var result = service.downloadArtifact(runId, AiMigrationService.ArtifactKind.RESULT, actor, false);
        assertThat(result.filename()).isEqualTo("migration-result.json");
        assertThat(result.resource()).isSameAs(resultBytes);
        verify(projectAccessService, org.mockito.Mockito.times(2)).requireProjectAccess(project, actor, false);
    }

    @Test
    void 下载时运行不存在或产物缺失返回404() {
        when(repository.findRun(UUID.randomUUID())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.downloadArtifact(UUID.randomUUID(), AiMigrationService.ArtifactKind.DIFF,
                actor, false))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("AI_MIGRATION_RUN_NOT_FOUND");

        when(repository.findRun(runId)).thenReturn(Optional.of(runView()));
        when(storedArtifactRepository.findByOwner(any(), any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.downloadArtifact(runId, AiMigrationService.ArtifactKind.RESULT, actor, false))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("AI_MIGRATION_ARTIFACT_NOT_FOUND");
    }

    @Test
    void 产物内容接口返回文本并在超限时截断() throws Exception {
        when(repository.findRun(runId)).thenReturn(Optional.of(runView()));
        var artifact = new cn.rvport.backend.artifact.StoredArtifactView(UUID.randomUUID(), project, taskId,
                cn.rvport.backend.artifact.StoredArtifactOwnerType.AI_MIGRATION, runId, "MIGRATION_DIFF",
                "bucket", "diff-key", "text/x-diff", 5, "sha", Map.of(), null);
        when(storedArtifactRepository.findByOwner(any(), any(), any())).thenReturn(Optional.of(artifact));

        var small = new org.springframework.core.io.ByteArrayResource("+a\n-b\n".getBytes());
        when(storedArtifactService.download(artifact.id()))
                .thenReturn(new cn.rvport.backend.artifact.StoredArtifactResource(artifact, small));
        var content = service.artifactContent(runId, AiMigrationService.ArtifactKind.DIFF, actor, false);
        assertThat(content.content()).isEqualTo("+a\n-b\n");
        assertThat(content.truncated()).isFalse();
        assertThat(content.filename()).isEqualTo("migration-diff.patch");

        var big = "+line\n".repeat(AiMigrationService.MAX_ARTIFACT_CONTENT_CHARS / 6 + 10);
        when(storedArtifactService.download(artifact.id())).thenReturn(
                new cn.rvport.backend.artifact.StoredArtifactResource(artifact,
                        new org.springframework.core.io.ByteArrayResource(big.getBytes())));
        var truncated = service.artifactContent(runId, AiMigrationService.ArtifactKind.DIFF, actor, false);
        assertThat(truncated.truncated()).isTrue();
        assertThat(truncated.content()).hasSize(AiMigrationService.MAX_ARTIFACT_CONTENT_CHARS);
    }

    @Test
    void 产物内容读取失败映射为404() {
        when(repository.findRun(runId)).thenReturn(Optional.of(runView()));
        var artifact = new cn.rvport.backend.artifact.StoredArtifactView(UUID.randomUUID(), project, taskId,
                cn.rvport.backend.artifact.StoredArtifactOwnerType.AI_MIGRATION, runId, "MIGRATION_RESULT",
                "bucket", "result-key", "application/json", 5, "sha", Map.of(), null);
        when(storedArtifactRepository.findByOwner(any(), any(), any())).thenReturn(Optional.of(artifact));
        var broken = org.mockito.Mockito.mock(org.springframework.core.io.Resource.class);
        when(storedArtifactService.download(artifact.id()))
                .thenReturn(new cn.rvport.backend.artifact.StoredArtifactResource(artifact, broken));
        try {
            when(broken.getInputStream()).thenThrow(new java.io.IOException("gone"));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }

        assertThatThrownBy(() -> service.artifactContent(runId, AiMigrationService.ArtifactKind.RESULT, actor, false))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("AI_MIGRATION_ARTIFACT_NOT_FOUND");
    }

    @Test
    void 产物类型路径值解析() {
        assertThat(AiMigrationService.ArtifactKind.fromPathValue("diff"))
                .isEqualTo(AiMigrationService.ArtifactKind.DIFF);
        assertThat(AiMigrationService.ArtifactKind.fromPathValue(" patch "))
                .isEqualTo(AiMigrationService.ArtifactKind.DIFF);
        assertThat(AiMigrationService.ArtifactKind.fromPathValue("RESULT"))
                .isEqualTo(AiMigrationService.ArtifactKind.RESULT);
        assertThat(AiMigrationService.ArtifactKind.fromPathValue("nope")).isNull();
        assertThat(AiMigrationService.ArtifactKind.fromPathValue(null)).isNull();
    }

    private AiMigrationRunView runView() {
        return new AiMigrationRunView(runId, project, taskId, scanRun, revision, "SUCCEEDED",
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
