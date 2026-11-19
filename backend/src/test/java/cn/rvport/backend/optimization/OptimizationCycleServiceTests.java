package cn.rvport.backend.optimization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionType;
import cn.rvport.backend.source.SourceRevisionView;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OptimizationCycleServiceTests {

    private final OptimizationCycleRepository optimizationCycleRepository = org.mockito.Mockito.mock(OptimizationCycleRepository.class);
    private final SourceRepository sourceRepository = org.mockito.Mockito.mock(SourceRepository.class);
    private final ProjectAccessService projectAccessService = org.mockito.Mockito.mock(ProjectAccessService.class);
    private final cn.rvport.backend.workflow.ProjectContextService contexts = org.mockito.Mockito.mock(cn.rvport.backend.workflow.ProjectContextService.class);
    private final cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentService runtimes = org.mockito.Mockito.mock(cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentService.class);
    private final OptimizationValidationService validations = org.mockito.Mockito.mock(OptimizationValidationService.class);
    private final OptimizationCycleService service = new OptimizationCycleService(
            optimizationCycleRepository,
            sourceRepository,
            projectAccessService, contexts, runtimes, validations,org.mockito.Mockito.mock(cn.rvport.backend.report.review.CurrentFixListResolver.class)
    );

    @org.junit.jupiter.api.BeforeEach
    void contextDefaults() {
        when(contexts.snapshot(org.mockito.ArgumentMatchers.any())).thenReturn(new cn.rvport.backend.workflow.ProjectContextSnapshot(null, 1L));
        when(validations.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(OptimizationValidation.unavailable("OPTIMIZATION_BUILD_MISSING", "缺少构建", "Builds missing"));
    }

    @Test
    void createsOrReusesCycleForSourceRevision() {
        UUID projectId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        SourceRevisionView sourceRevision = sourceRevision(projectId, sourceRevisionId, "v1", true);
        when(contexts.snapshot(projectId)).thenReturn(new cn.rvport.backend.workflow.ProjectContextSnapshot(sourceRevisionId, 1L));
        OptimizationCycleView cycle = cycle(projectId, sourceRevisionId, 1, OptimizationCycleStatus.ACTIVE);
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision));
        when(sourceRepository.findPrevious(projectId, sourceRevisionId, sourceRevision.createdAt())).thenReturn(Optional.empty());
        when(optimizationCycleRepository.createOrReactivate(projectId, sourceRevisionId)).thenReturn(cycle);
        when(optimizationCycleRepository.summary(projectId, sourceRevisionId, null)).thenReturn(OptimizationCycleSummaryResponse.empty());

        OptimizationCycleResponse response = service.createOrReuseActiveCycle(projectId, sourceRevisionId);

        assertThat(response.cycleNumber()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(OptimizationCycleStatus.ACTIVE);
        assertThat(response.sourceRevision().id()).isEqualTo(sourceRevisionId);
        verify(optimizationCycleRepository).createOrReactivate(projectId, sourceRevisionId);
    }

    @Test
    void rejectsSourceRevisionOutsideProject() {
        UUID projectId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        when(sourceRepository.findById(sourceRevisionId))
                .thenReturn(Optional.of(sourceRevision(UUID.randomUUID(), sourceRevisionId, "v1", true)));

        assertThatThrownBy(() -> service.createOrReuseActiveCycle(projectId, sourceRevisionId))
                .isInstanceOf(ApiException.class)
                .hasMessage("源码版本不存在")
                .extracting("code")
                .isEqualTo("SOURCE_REVISION_NOT_FOUND");
    }

    @Test
    void overviewReturnsCurrentCycleAndTotal() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        SourceRevisionView sourceRevision = sourceRevision(projectId, sourceRevisionId, "v2", true);
        when(contexts.snapshot(projectId)).thenReturn(new cn.rvport.backend.workflow.ProjectContextSnapshot(sourceRevisionId, 1L));
        OptimizationCycleView cycle = cycle(projectId, sourceRevisionId, 2, OptimizationCycleStatus.ACTIVE);
        when(optimizationCycleRepository.count(projectId, new OptimizationCycleListRequest(1, 1, null, null, null, null, null, null)))
                .thenReturn(2L);
        when(optimizationCycleRepository.findBySourceRevision(projectId, sourceRevisionId)).thenReturn(Optional.of(cycle));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision));
        when(sourceRepository.findPrevious(projectId, sourceRevisionId, sourceRevision.createdAt()))
                .thenReturn(Optional.of(sourceRevision(projectId, UUID.randomUUID(), "v1", false)));
        when(optimizationCycleRepository.summary(org.mockito.ArgumentMatchers.eq(projectId), org.mockito.ArgumentMatchers.eq(sourceRevisionId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(OptimizationCycleSummaryResponse.empty());

        OptimizationOverviewResponse response = service.overview(projectId, actorId, false);

        assertThat(response.totalCycles()).isEqualTo(2);
        assertThat(response.currentCycle().cycleNumber()).isEqualTo(2);
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
    }

    @Test
    void listSupportsEmptyChildrenAndPaging() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        OptimizationCycleListRequest request = new OptimizationCycleListRequest(1, 20, null, null, null, null, "cycleNumber", "desc");
        SourceRevisionView sourceRevision = sourceRevision(projectId, sourceRevisionId, "v1", true);
        when(optimizationCycleRepository.count(projectId, request)).thenReturn(1L);
        when(optimizationCycleRepository.list(projectId, request))
                .thenReturn(List.of(cycle(projectId, sourceRevisionId, 1, OptimizationCycleStatus.ACTIVE)));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision));
        when(sourceRepository.findPrevious(projectId, sourceRevisionId, sourceRevision.createdAt())).thenReturn(Optional.empty());
        when(optimizationCycleRepository.summary(projectId, sourceRevisionId, null)).thenReturn(OptimizationCycleSummaryResponse.empty());

        PageResponse<OptimizationCycleResponse> response = service.list(projectId, request, actorId, false);

        assertThat(response.total()).isEqualTo(1);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().summary().build().total()).isZero();
    }

    @Test
    void detailDoesNotLeakCrossProjectCycle() {
        UUID projectId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(optimizationCycleRepository.findById(cycleId))
                .thenReturn(Optional.of(cycle(UUID.randomUUID(), UUID.randomUUID(), 1, OptimizationCycleStatus.ACTIVE)));

        assertThatThrownBy(() -> service.get(projectId, cycleId, actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("优化轮次不存在")
                .extracting("code")
                .isEqualTo("OPTIMIZATION_CYCLE_NOT_FOUND");
    }

    @Test
    void detailReturnsCycleOwnedByProject() {
        UUID projectId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SourceRevisionView source = sourceRevision(projectId, UUID.randomUUID(), "v3", true);
        OptimizationCycleView cycle = new OptimizationCycleView(cycleId, projectId, source.id(), 3,
                OptimizationCycleStatus.ACTIVE, null, OffsetDateTime.now(), null,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(optimizationCycleRepository.findById(cycleId)).thenReturn(Optional.of(cycle));
        when(sourceRepository.findById(source.id())).thenReturn(Optional.of(source));
        when(sourceRepository.findPrevious(projectId, source.id(), source.createdAt())).thenReturn(Optional.empty());
        when(optimizationCycleRepository.summary(projectId, source.id(), null))
                .thenReturn(OptimizationCycleSummaryResponse.empty());

        OptimizationCycleResponse response = service.get(projectId, cycleId, actorId, false);

        assertThat(response.id()).isEqualTo(cycleId);
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
    }

    @Test
    void latestDistinguishesCurrentHistoricalAndEmptyWithoutNullCurrentFilter() {
        UUID project = UUID.randomUUID(), revision = UUID.randomUUID(), actor = UUID.randomUUID();
        var source = sourceRevision(project, revision, "v1", true);
        when(contexts.snapshot(project)).thenReturn(new cn.rvport.backend.workflow.ProjectContextSnapshot(revision, 1L));
        var cycle = cycle(project, revision, 1, OptimizationCycleStatus.ACTIVE);
        when(sourceRepository.findById(revision)).thenReturn(Optional.of(source));
        when(optimizationCycleRepository.summary(project, revision, null)).thenReturn(OptimizationCycleSummaryResponse.empty());
        when(optimizationCycleRepository.findBySourceRevision(project, revision)).thenReturn(Optional.of(cycle));
        var current = service.latest(project, actor, false);
        assertThat(current.freshness().name()).isEqualTo("CURRENT");
        assertThat(current.result().validation().status().name()).isEqualTo("PENDING");
        assertThat(current.result().summary().build().latestStatus()).isNull();
        assertThat(current.execution()).isNull();
        when(contexts.snapshot(project)).thenReturn(new cn.rvport.backend.workflow.ProjectContextSnapshot(null, 1L));
        when(optimizationCycleRepository.findLatest(project, null)).thenReturn(Optional.of(cycle));
        var historical = service.latest(project, actor, false);
        assertThat(historical.freshness().name()).isEqualTo("STALE");
        assertThat(historical.result().validation().reasonCode()).isEqualTo("OPTIMIZATION_SOURCE_STALE");
        assertThat(historical.result().nextAction()).isNull();
        org.mockito.Mockito.verify(optimizationCycleRepository, org.mockito.Mockito.never()).findBySourceRevision(project, null);
        assertThat(service.overview(project, actor, false).currentCycle()).isNull();
        when(optimizationCycleRepository.findLatest(project, null)).thenReturn(Optional.empty());
        assertThat(service.latest(project, actor, false).freshness().name()).isEqualTo("EMPTY");
        when(contexts.snapshot(project)).thenReturn(new cn.rvport.backend.workflow.ProjectContextSnapshot(UUID.randomUUID(), 2L));
        when(optimizationCycleRepository.findLatest(project, null)).thenReturn(Optional.of(cycle));
        assertThat(service.latest(project, actor, false).freshness().name()).isEqualTo("STALE");
    }

    @Test
    void errorsPreservePermissionsAndDoNotWriteAnyCycleFacts() {
        UUID project = UUID.randomUUID(), actor = UUID.randomUUID(), id = UUID.randomUUID();
        assertThatThrownBy(() -> service.createOrReuseActiveCycle(project, id)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.get(project, id, actor, false)).isInstanceOf(ApiException.class);
        var cycle = cycle(project, id, 1, OptimizationCycleStatus.ACTIVE);
        when(optimizationCycleRepository.findById(cycle.id())).thenReturn(Optional.of(cycle));
        assertThatThrownBy(() -> service.get(project, cycle.id(), actor, false)).hasMessage("源码版本不存在");
        var query = new OptimizationCycleListRequest(1, 20, null, null, null, null, "unsafe", null);
        when(optimizationCycleRepository.list(project, query)).thenThrow(new IllegalArgumentException());
        assertThatThrownBy(() -> service.list(project, query, actor, false)).hasMessage("优化轮次查询参数不正确");
        org.mockito.Mockito.doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "无权限"))
                .when(projectAccessService).requireProjectAccess(project, actor, false);
        assertThatThrownBy(() -> service.latest(project, actor, false)).hasMessage("无权限");
    }

    @Test
    void allReadEntryPointsDeclareRepeatableRead() {
        for (var method : OptimizationCycleService.class.getDeclaredMethods()) {
            if (List.of("overview", "latest", "list", "get").contains(method.getName())) {
                var transaction = method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
                assertThat(transaction.readOnly()).isTrue();
                assertThat(transaction.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
            }
        }
    }

    private OptimizationCycleView cycle(UUID projectId, UUID sourceRevisionId, int cycleNumber, OptimizationCycleStatus status) {
        return new OptimizationCycleView(
                UUID.randomUUID(),
                projectId,
                sourceRevisionId,
                cycleNumber,
                status,
                Map.of(),
                OffsetDateTime.now(),
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    private SourceRevisionView sourceRevision(UUID projectId, UUID sourceRevisionId, String label, boolean active) {
        return new SourceRevisionView(
                sourceRevisionId,
                projectId,
                SourceRevisionType.GIT,
                label,
                "https://example.test/repo.git",
                "https://example.test/repo.git",
                "main",
                "abcdef",
                null,
                "/runtime/source",
                null,
                active,
                UUID.randomUUID(),
                OffsetDateTime.now()
        );
    }
}
