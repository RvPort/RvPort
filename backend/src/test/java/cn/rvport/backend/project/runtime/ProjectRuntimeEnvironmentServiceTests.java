package cn.rvport.backend.project.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.environment.EnvironmentRepository;
import cn.rvport.backend.environment.EnvironmentRole;
import cn.rvport.backend.environment.EnvironmentStatus;
import cn.rvport.backend.environment.EnvironmentView;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.task.TaskEventService;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectRuntimeEnvironmentServiceTests {

    @Mock
    private ProjectRuntimeEnvironmentRepository repository;
    @Mock
    private EnvironmentRepository environmentRepository;
    @Mock
    private ProjectAccessService projectAccessService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private TaskEventService taskEventService;

    @Test
    void updatesSourceAndTargetAndMarksReady() {
        ProjectRuntimeEnvironmentService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        EnvironmentView source = environment(projectId, EnvironmentRole.SOURCE_X86, true);
        EnvironmentView target = environment(projectId, EnvironmentRole.TARGET_RISCV, true);
        when(repository.find(projectId)).thenReturn(Optional.of(ProjectRuntimeEnvironmentView.empty(projectId)));
        when(environmentRepository.findById(source.id())).thenReturn(Optional.of(source));
        when(environmentRepository.findById(target.id())).thenReturn(Optional.of(target));
        when(repository.upsert(projectId, source.id(), target.id(), actorId, true))
                .thenReturn(new ProjectRuntimeEnvironmentView(projectId, source.id(), target.id(), 2L, actorId, null, OffsetDateTime.now()));

        ProjectRuntimeEnvironmentResponse response = service.update(
                projectId,
                new ProjectRuntimeEnvironmentUpdateRequest(source.id(), target.id()),
                actorId,
                false,
                null
        );

        assertThat(response.ready()).isTrue();
        assertThat(response.version()).isEqualTo(2L);
        assertThat(response.sourceEnvironment().id()).isEqualTo(source.id());
        assertThat(response.targetEnvironment().id()).isEqualTo(target.id());
        verify(taskEventService).appendProjectContextChanged(
                eq(projectId),
                eq("context"),
                any(),
                eq(java.util.List.of(
                        "runtimeEnvironment.sourceEnvironmentId",
                        "runtimeEnvironment.targetEnvironmentId",
                        "runtimeEnvironment.version"
                )),
                eq("项目运行环境已更新")
        );
    }

    @Test
    void supportsPartialUpdateAndDoesNotIncrementWhenUnchanged() {
        ProjectRuntimeEnvironmentService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        EnvironmentView source = environment(projectId, EnvironmentRole.SOURCE_X86, true);
        ProjectRuntimeEnvironmentView current = new ProjectRuntimeEnvironmentView(projectId, source.id(), null, 3L, actorId, null, null);
        when(repository.find(projectId)).thenReturn(Optional.of(current));
        when(environmentRepository.findById(source.id())).thenReturn(Optional.of(source));
        when(repository.upsert(projectId, source.id(), null, actorId, false)).thenReturn(current);

        ProjectRuntimeEnvironmentResponse response = service.update(
                projectId,
                new ProjectRuntimeEnvironmentUpdateRequest(source.id(), null),
                actorId,
                false,
                null
        );

        assertThat(response.ready()).isFalse();
        assertThat(response.missing()).containsExactly("targetEnvironment");
        verify(repository).upsert(projectId, source.id(), null, actorId, false);
        verify(taskEventService, never()).appendProjectContextChanged(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsInvalidInputDisabledEnvironmentWrongRoleAndSameEnvironment() {
        ProjectRuntimeEnvironmentService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        EnvironmentView disabled = environment(projectId, EnvironmentRole.SOURCE_X86, false);
        EnvironmentView wrongRole = environment(projectId, EnvironmentRole.TARGET_RISCV, true);
        when(repository.find(projectId)).thenReturn(Optional.of(ProjectRuntimeEnvironmentView.empty(projectId)));
        when(environmentRepository.findById(disabled.id())).thenReturn(Optional.of(disabled));
        when(environmentRepository.findById(wrongRole.id())).thenReturn(Optional.of(wrongRole));

        assertThatThrownBy(() -> service.update(projectId, new ProjectRuntimeEnvironmentUpdateRequest(null, null), actorId, false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("PROJECT_RUNTIME_ENVIRONMENT_UPDATE_EMPTY");
        assertThatThrownBy(() -> service.update(projectId, new ProjectRuntimeEnvironmentUpdateRequest(disabled.id(), null), actorId, false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("ENVIRONMENT_DISABLED");
        assertThatThrownBy(() -> service.update(projectId, new ProjectRuntimeEnvironmentUpdateRequest(wrongRole.id(), null), actorId, false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("PROJECT_RUNTIME_ENVIRONMENT_ROLE_INVALID");
        assertThatThrownBy(() -> service.update(projectId, new ProjectRuntimeEnvironmentUpdateRequest(wrongRole.id(), wrongRole.id()), actorId, false, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("PROJECT_RUNTIME_ENVIRONMENT_ROLE_INVALID");
        verify(repository, never()).upsert(any(), any(), any(), any(), eq(true));
    }

    @Test
    void requireCompleteRejectsMissingConfiguration() {
        ProjectRuntimeEnvironmentService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(repository.find(projectId)).thenReturn(Optional.of(ProjectRuntimeEnvironmentView.empty(projectId)));

        assertThatThrownBy(() -> service.requireComplete(projectId, actorId, false))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("PROJECT_RUNTIME_ENVIRONMENTS_REQUIRED");
    }

    @Test
    void rejectsDisablingSelectedEnvironment() {
        ProjectRuntimeEnvironmentService service = service();
        UUID environmentId = UUID.randomUUID();
        when(repository.isSelectedEnvironment(environmentId)).thenReturn(true);

        assertThatThrownBy(() -> service.assertEnvironmentNotSelected(environmentId))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("PROJECT_RUNTIME_ENVIRONMENT_IN_USE");
    }

    @Test
    void bumpsRuntimeEnvironmentVersionWhenSelectedEnvironmentConfigChanges() {
        ProjectRuntimeEnvironmentService service = service();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ProjectRuntimeEnvironmentView updated = new ProjectRuntimeEnvironmentView(
                projectId,
                environmentId,
                UUID.randomUUID(),
                4L,
                actorId,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
        when(repository.bumpVersionIfAnySelected(projectId, java.util.List.of(environmentId), actorId))
                .thenReturn(Optional.of(updated));

        Optional<ProjectRuntimeEnvironmentView> response = service.bumpVersionIfSelected(projectId, environmentId, actorId);

        assertThat(response).contains(updated);
        verify(taskEventService).appendProjectContextChanged(
                eq(projectId),
                eq("context"),
                any(),
                eq(java.util.List.of("runtimeEnvironment.version", "environment.config")),
                eq("项目运行环境配置已更新")
        );
    }

    @Test
    void getsSummarizesAndReadsVersionForEmptyConfiguration() {
        ProjectRuntimeEnvironmentService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(repository.find(projectId)).thenReturn(Optional.empty());
        when(repository.currentVersion(projectId)).thenReturn(7L);

        assertThat(service.get(projectId, actorId, false).ready()).isFalse();
        assertThat(service.get(projectId, actorId, false).missing())
                .containsExactly("sourceEnvironment", "targetEnvironment");
        assertThat(service.summary(projectId).ready()).isFalse();
        assertThat(service.currentVersion(projectId)).isEqualTo(7L);
    }

    @Test
    void updatesOnlyTargetAndReportsOnlyChangedTargetField() {
        ProjectRuntimeEnvironmentService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        EnvironmentView source = environment(projectId, EnvironmentRole.SOURCE_X86, true);
        EnvironmentView oldTarget = environment(projectId, EnvironmentRole.TARGET_RISCV, true);
        EnvironmentView newTarget = environment(projectId, EnvironmentRole.TARGET_RISCV, true);
        ProjectRuntimeEnvironmentView current = new ProjectRuntimeEnvironmentView(
                projectId, source.id(), oldTarget.id(), 2L, actorId, null, null);
        ProjectRuntimeEnvironmentView updated = new ProjectRuntimeEnvironmentView(
                projectId, source.id(), newTarget.id(), 3L, actorId, null, OffsetDateTime.now());
        when(repository.find(projectId)).thenReturn(Optional.of(current));
        when(environmentRepository.findById(source.id())).thenReturn(Optional.of(source));
        when(environmentRepository.findById(newTarget.id())).thenReturn(Optional.of(newTarget));
        when(repository.upsert(projectId, source.id(), newTarget.id(), actorId, true)).thenReturn(updated);

        ProjectRuntimeEnvironmentResponse response = service.update(projectId,
                new ProjectRuntimeEnvironmentUpdateRequest(null, newTarget.id()), actorId, false, null);

        assertThat(response.ready()).isTrue();
        verify(taskEventService).appendProjectContextChanged(eq(projectId), eq("context"), any(),
                eq(java.util.List.of("runtimeEnvironment.targetEnvironmentId", "runtimeEnvironment.version")), any());
    }

    @Test
    void rejectsSameEnvironmentAfterBothRolesValidate() {
        ProjectRuntimeEnvironmentService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        EnvironmentView source = environment(projectId, EnvironmentRole.SOURCE_X86, true);
        EnvironmentView target = environment(projectId, EnvironmentRole.TARGET_RISCV, true);
        source = copyWithId(source, id);
        target = copyWithId(target, id);
        when(repository.find(projectId)).thenReturn(Optional.of(ProjectRuntimeEnvironmentView.empty(projectId)));
        when(environmentRepository.findById(id)).thenReturn(Optional.of(source), Optional.of(target));

        assertThatThrownBy(() -> service.update(projectId,
                new ProjectRuntimeEnvironmentUpdateRequest(id, id), actorId, false, null))
                .isInstanceOf(ApiException.class).extracting("code")
                .isEqualTo("PROJECT_RUNTIME_ENVIRONMENT_DUPLICATED");
    }

    @Test
    void requireCompleteValidatesBothConfiguredEnvironmentsAndReturnsConfiguration() {
        ProjectRuntimeEnvironmentService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        EnvironmentView source = environment(projectId, EnvironmentRole.SOURCE_X86, true);
        EnvironmentView target = environment(projectId, EnvironmentRole.TARGET_RISCV, true);
        ProjectRuntimeEnvironmentView configured = new ProjectRuntimeEnvironmentView(
                projectId, source.id(), target.id(), 5L, actorId, null, OffsetDateTime.now());
        when(repository.find(projectId)).thenReturn(Optional.of(configured));
        when(environmentRepository.findById(source.id())).thenReturn(Optional.of(source));
        when(environmentRepository.findById(target.id())).thenReturn(Optional.of(target));

        assertThat(service.requireComplete(projectId, actorId, false)).isSameAs(configured);

        ProjectRuntimeEnvironmentView onlySource = new ProjectRuntimeEnvironmentView(
                projectId, source.id(), null, 6L, actorId, null, null);
        when(repository.find(projectId)).thenReturn(Optional.of(onlySource));
        assertThat(service.requireSource(projectId, actorId, false)).isSameAs(onlySource);
        assertThatThrownBy(() -> service.requireComplete(projectId, actorId, false))
                .isInstanceOf(ApiException.class).extracting("code")
                .isEqualTo("PROJECT_RUNTIME_ENVIRONMENTS_REQUIRED");
        when(repository.find(projectId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireSource(projectId, actorId, false))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("PROJECT_SOURCE_ENVIRONMENT_REQUIRED");
    }

    @Test
    void allowsUnselectedEnvironmentAndDoesNotPublishWhenVersionWasNotBumped() {
        ProjectRuntimeEnvironmentService service = service();
        UUID projectId = UUID.randomUUID();
        UUID environmentId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(repository.isSelectedEnvironment(environmentId)).thenReturn(false);
        when(repository.bumpVersionIfAnySelected(projectId, java.util.List.of(environmentId), actorId))
                .thenReturn(Optional.empty());

        service.assertEnvironmentNotSelected(environmentId);
        assertThat(service.bumpVersionIfSelected(projectId, environmentId, actorId)).isEmpty();
        verify(taskEventService, never()).appendProjectContextChanged(any(), any(), any(), any(), any());
    }

    @Test
    void summaryReportsEachEnvironmentThatHasNotPassedItsConnectionTest() {
        ProjectRuntimeEnvironmentService service = service();
        UUID projectId = UUID.randomUUID();
        EnvironmentView source = environment(projectId, EnvironmentRole.SOURCE_X86, true);
        EnvironmentView target = environment(projectId, EnvironmentRole.TARGET_RISCV, true);
        source = withStatus(source, EnvironmentStatus.UNKNOWN);
        target = withStatus(target, EnvironmentStatus.UNAVAILABLE);
        when(repository.find(projectId)).thenReturn(Optional.of(new ProjectRuntimeEnvironmentView(
                projectId, source.id(), target.id(), 2L, UUID.randomUUID(), null, null)));
        when(environmentRepository.findById(source.id())).thenReturn(Optional.of(source));
        when(environmentRepository.findById(target.id())).thenReturn(Optional.of(target));

        ProjectRuntimeEnvironmentResponse response = service.summary(projectId);

        assertThat(response.ready()).isFalse();
        assertThat(response.missing()).containsExactly("sourceEnvironmentTest", "targetEnvironmentTest");
    }

    @Test
    void rejectsMissingAndForeignConfiguredEnvironments() {
        ProjectRuntimeEnvironmentService service = service();
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        when(repository.find(projectId)).thenReturn(Optional.of(ProjectRuntimeEnvironmentView.empty(projectId)));
        when(environmentRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(projectId,
                new ProjectRuntimeEnvironmentUpdateRequest(missingId, null), actorId, false, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ENVIRONMENT_NOT_FOUND");

        EnvironmentView foreign = environment(UUID.randomUUID(), EnvironmentRole.SOURCE_X86, true);
        when(environmentRepository.findById(foreign.id())).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.update(projectId,
                new ProjectRuntimeEnvironmentUpdateRequest(foreign.id(), null), actorId, false, null))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ENVIRONMENT_NOT_FOUND");
    }

    private ProjectRuntimeEnvironmentService service() {
        return new ProjectRuntimeEnvironmentService(repository, environmentRepository, projectAccessService, auditLogService, taskEventService);
    }

    private EnvironmentView environment(UUID projectId, EnvironmentRole role, boolean enabled) {
        return new EnvironmentView(
                UUID.randomUUID(),
                projectId,
                role.name(),
                role,
                role == EnvironmentRole.SOURCE_X86 ? "x86_64" : "riscv64",
                "127.0.0.1",
                22,
                "root",
                UUID.randomUUID(),
                null,
                "/workspace",
                enabled ? EnvironmentStatus.AVAILABLE : EnvironmentStatus.DISABLED,
                enabled,
                null,
                null,
                UUID.randomUUID(),
                OffsetDateTime.now(),
                null
        );
    }

    private EnvironmentView copyWithId(EnvironmentView view, UUID id) {
        return new EnvironmentView(id, view.projectId(), view.name(), view.role(), view.arch(), view.host(),
                view.sshPort(), view.username(), view.credentialId(), view.jumpHostEnvironmentId(),
                view.workingDirectory(), view.status(), view.enabled(), view.configVersion(),
                view.lastConnectedAt(), view.lastCollectedAt(), view.createdBy(), view.createdAt(), view.updatedAt());
    }

    private EnvironmentView withStatus(EnvironmentView view, EnvironmentStatus status) {
        return new EnvironmentView(view.id(), view.projectId(), view.name(), view.role(), view.arch(), view.host(),
                view.sshPort(), view.username(), view.credentialId(), view.jumpHostEnvironmentId(),
                view.workingDirectory(), status, view.enabled(), view.configVersion(),
                view.lastConnectedAt(), view.lastCollectedAt(), view.createdBy(), view.createdAt(), view.updatedAt());
    }
}
