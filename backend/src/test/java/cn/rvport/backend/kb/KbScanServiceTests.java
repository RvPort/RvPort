package cn.rvport.backend.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactResource;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.config.RvPortProperties;
import cn.rvport.backend.migration.NodeReportInclusionService;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionType;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.task.TaskCreateCommand;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskService;
import cn.rvport.backend.task.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class KbScanServiceTests {

    @Mock
    private KbScanRepository kbScanRepository;

    @Mock
    private SourceRepository sourceRepository;

    @Mock
    private ProjectAccessService projectAccessService;

    @Mock
    private TaskService taskService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private StoredArtifactService storedArtifactService;

    @Mock
    private NodeReportInclusionService nodeReportInclusionService;


    @Mock private KbScanContextService scanContextService;

    @org.junit.jupiter.api.BeforeEach
    void contextDefaults() {
        org.mockito.Mockito.lenient().when(scanContextService.resolve(any(), any()))
                .thenAnswer(call -> KbContextFixtures.ready(call.getArgument(0), call.getArgument(1)));
        org.mockito.Mockito.lenient().when(scanContextService.current(any())).thenAnswer(call -> {
            UUID project = call.getArgument(0);
            UUID source = sourceRepository.findActive(project).map(SourceRevisionView::id).orElse(null);
            return KbContextFixtures.ready(project, source);
        });
    }

    @TempDir
    private Path tempDir;

    @Test
    void rejectsScanWithoutCurrentSuccessfulBuildBeforeAnyCreationSideEffects() throws Exception {
        UUID project = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        when(sourceRepository.findActive(project)).thenReturn(Optional.of(sourceRevision(project, source, tempDir)));
        org.mockito.Mockito.doReturn(new KbScanContextService.Selection(project, source, KbContextFixtures.ENV, 7L, null))
                .when(scanContextService).resolve(project, source);
        assertThatThrownBy(() -> service().create(project, null, UUID.randomUUID(), false, new MockHttpServletRequest()))
                .isInstanceOf(cn.rvport.backend.workflow.PrerequisiteNotSatisfiedException.class)
                .extracting("code").isEqualTo("PREREQUISITE_NOT_SATISFIED");
        org.mockito.Mockito.verifyNoInteractions(taskService, kbScanRepository, auditLogService);
        var order = org.mockito.Mockito.inOrder(projectAccessService, scanContextService, sourceRepository);
        order.verify(projectAccessService).requireProjectAccess(eq(project), any(), eq(false));
        order.verify(scanContextService).lock(project);
        order.verify(sourceRepository).findActive(project);
        order.verify(scanContextService).resolve(project, source);
    }

    @Test
    void currentListRequiresWholeContextAndHonorsExplicitSourceFilter() {
        UUID project = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        var selected = KbContextFixtures.ready(project, source);
        org.mockito.Mockito.doReturn(selected).when(scanContextService).current(project);
        var request = new KbScanListRequest(null, null, null, null, null, null, null);
        var bound = request.withContext(source, selected.executionContext());
        when(kbScanRepository.countRuns(project, bound)).thenReturn(1L);
        when(kbScanRepository.listRuns(project, bound))
                .thenReturn(List.of(KbContextFixtures.scan(project, source, TaskStatus.SUCCEEDED, selected.executionContext())));
        var result = service().list(project, request, UUID.randomUUID(), false);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.current()).isTrue();
            assertThat(item.staleReasons()).isEmpty();
            assertThat(item.message()).isNotBlank();
            assertThat(item.message_en()).isNotBlank();
        });
        var mismatched = new KbScanListRequest(null, null, null, UUID.randomUUID(), null, null, null);
        assertThat(service().list(project, mismatched, UUID.randomUUID(), false).items()).isEmpty();
        var explicit = new KbScanListRequest(null, null, null, source, null, null, null);
        when(kbScanRepository.listRuns(project, explicit.withContext(source, selected.executionContext()))).thenReturn(List.of());
        assertThat(service().list(project, explicit, UUID.randomUUID(), false).items()).isEmpty();
        org.mockito.Mockito.doReturn(new KbScanContextService.Selection(project, source, KbContextFixtures.ENV, 8L, null))
                .when(scanContextService).current(project);
        assertThat(service().list(project, request, UUID.randomUUID(), false).items()).isEmpty();
    }

    @Test
    void rejectsAccessBeforeReadingScanContext() {
        UUID project = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "denied"))
                .when(projectAccessService).requireProjectAccess(project, actor, false);
        assertThatThrownBy(() -> service().latest(project, actor, false)).isInstanceOf(ApiException.class);
        org.mockito.Mockito.verifyNoInteractions(scanContextService, kbScanRepository);
    }

    @Test
    void createsKbScanWithActiveSourceRevision() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Path source = Files.createDirectories(tempDir.resolve("source"));
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, source)));
        when(taskService.create(any())).thenReturn(task(taskId, projectId));
        when(kbScanRepository.createRun(any(), eq(taskId))).thenReturn(UUID.randomUUID());

        TaskResponse response = service().create(projectId, new KbScanRequest(null, null, null, null, null), actorId, false, new MockHttpServletRequest());

        assertThat(response.id()).isEqualTo(taskId);
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
        ArgumentCaptor<TaskCreateCommand> commandCaptor = ArgumentCaptor.forClass(TaskCreateCommand.class);
        verify(taskService).create(commandCaptor.capture());
        assertThat(commandCaptor.getValue().type()).isEqualTo(RvPortTaskTypes.KB_SCAN);
        assertThat(commandCaptor.getValue().inputSnapshot().get("sourceRevisionId")).isEqualTo(sourceRevisionId);
        assertThat(commandCaptor.getValue().inputSnapshot().get("executionContext")).isEqualTo(KbContextFixtures.CONTEXT);
        assertThat(commandCaptor.getValue().inputSnapshot().toString()).doesNotContain(source.toString());
        assertThat(commandCaptor.getValue().inputSnapshot().get("excludePaths")).isEqualTo(List.of(".git", "build"));
    }

    @Test
    void createsKbScanFromNullRequestUsingAllBuiltInDefaults() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Path source = Files.createDirectories(tempDir.resolve("null-request-source"));
        when(sourceRepository.findActive(projectId))
                .thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, source)));
        when(taskService.create(any())).thenReturn(task(taskId, projectId));
        when(kbScanRepository.createRun(any(), eq(taskId))).thenReturn(UUID.randomUUID());

        TaskResponse response = service(new RvPortProperties(null, null, new RvPortProperties.Worker(false)))
                .create(projectId, null, actorId, false, new MockHttpServletRequest());

        assertThat(response.id()).isEqualTo(taskId);
        ArgumentCaptor<KbScanInput> input = ArgumentCaptor.forClass(KbScanInput.class);
        verify(kbScanRepository).createRun(input.capture(), eq(taskId));
        assertThat(input.getValue().language()).isEqualTo("c,cpp");
        assertThat(input.getValue().targetArch()).isEqualTo("riscv64");
        assertThat(input.getValue().ruleVersion()).isNull();
        assertThat(input.getValue().scanScope()).containsEntry("mode", "ALL");
        assertThat(input.getValue().excludePaths()).containsExactly(".git", "build");
    }

    @Test
    void createsKbScanWithSpecifiedSourceRevisionAndScope() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Path source = Files.createDirectories(tempDir.resolve("source"));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, source)));
        when(taskService.create(any())).thenReturn(task(taskId, projectId));
        when(kbScanRepository.createRun(any(), eq(taskId))).thenReturn(UUID.randomUUID());

        service().create(projectId, new KbScanRequest(
                sourceRevisionId,
                "riscv-porting@2026-07",
                "c",
                new KbScanScope("PATHS", List.of("src")),
                List.of(".git", "third_party")
        ), actorId, false, new MockHttpServletRequest());

        ArgumentCaptor<KbScanInput> inputCaptor = ArgumentCaptor.forClass(KbScanInput.class);
        verify(kbScanRepository).createRun(inputCaptor.capture(), eq(taskId));
        assertThat(inputCaptor.getValue().sourceRevisionId()).isEqualTo(sourceRevisionId);
        assertThat(inputCaptor.getValue().ruleVersion()).isEqualTo("riscv-porting@2026-07");
        assertThat(inputCaptor.getValue().scanScope()).containsEntry("mode", "PATHS");
    }

    @Test
    void rejectsMissingActiveSourceRevision() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().create(projectId, new KbScanRequest(null, null, null, null, null), actorId, false, new MockHttpServletRequest()))
                .isInstanceOf(ApiException.class)
                .hasMessage("项目没有可用的 active source revision")
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsCrossProjectSourceRevisionAndMissingWorkingCopy() {
        UUID projectId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(UUID.randomUUID(), sourceRevisionId, tempDir.resolve("missing"))));

        assertThatThrownBy(() -> service().create(projectId, new KbScanRequest(sourceRevisionId, null, null, null, null), actorId, false, new MockHttpServletRequest()))
                .isInstanceOf(ApiException.class)
                .hasMessage("源码版本不存在")
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);

        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, tempDir.resolve("missing"))));
        assertThatThrownBy(() -> service().create(projectId, new KbScanRequest(sourceRevisionId, null, null, null, null), actorId, false, new MockHttpServletRequest()))
                .isInstanceOf(ApiException.class)
                .hasMessage("源码工作副本不存在")
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listsKbScansWithPaginationFilteringAndSortingRequest() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        KbScanListRequest request = new KbScanListRequest(
                2,
                5,
                TaskStatus.SUCCEEDED,
                sourceRevisionId,
                false, true,
                "riscv-porting@2026-07",
                "finishedAt",
                "desc"
        );
        when(kbScanRepository.countRuns(projectId, request)).thenReturn(6L);
        when(kbScanRepository.listRuns(projectId, request)).thenReturn(List.of(run(scanId, projectId, sourceRevisionId, TaskStatus.SUCCEEDED)));

        var page = service().list(projectId, request, actorId, false);

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(5);
        assertThat(page.total()).isEqualTo(6);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.items()).extracting(KbScanResponse::id).containsExactly(scanId);
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
        verify(kbScanRepository).countRuns(projectId, request);
        verify(kbScanRepository).listRuns(projectId, request);
    }

    @Test
    void wrapsInvalidKbScanListSortAsBadRequest() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        KbScanListRequest request = new KbScanListRequest(1, 20, null, null, null, "unsafeField", "asc");
        when(sourceRepository.findActive(projectId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, tempDir)));
        when(kbScanRepository.countRuns(eq(projectId), any(KbScanListRequest.class))).thenThrow(new IllegalArgumentException("bad sort"));

        assertThatThrownBy(() -> service().list(projectId, request, actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("KB 扫描查询参数不正确")
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
    }

    @Test
    void getsKbScanDetailAfterProjectAccessCheck() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        Map<String, Object> summary = Map.of(
                "findingCount", 1,
                "compileCommands", Map.of(
                        "mode", "USED",
                        "source", "LATEST_SOURCE_BUILD",
                        "commandCount", 2
                )
        );
        when(kbScanRepository.findById(scanId)).thenReturn(Optional.of(run(scanId, projectId, sourceRevisionId, TaskStatus.RUNNING, summary)));

        KbScanResponse response = service().get(scanId, actorId, false);

        assertThat(response.id()).isEqualTo(scanId);
        assertThat(response.projectId()).isEqualTo(projectId);
        assertThat(response.status()).isEqualTo(TaskStatus.RUNNING.name());
        @SuppressWarnings("unchecked")
        Map<String, Object> compileCommands = (Map<String, Object>) response.summary().get("compileCommands");
        assertThat(compileCommands)
                .containsEntry("mode", "USED")
                .containsEntry("source", "LATEST_SOURCE_BUILD")
                .containsEntry("commandCount", 2);
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
    }

    @Test
    void listsFindingsWithPaginationFilteringAndSortingRequest() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID findingId = UUID.randomUUID();
        FindingListRequest request = new FindingListRequest(
                3,
                10,
                "C_CPP.X86.HEADER",
                "issue",
                "critical",
                "header",
                "src/",
                "immintrin.h",
                "DETECTED",
                "lineStart",
                "asc"
        );
        FindingView finding = finding(findingId, projectId, scanId, sourceRevisionId, "src/main.c", 9, 9);
        when(kbScanRepository.findById(scanId)).thenReturn(Optional.of(run(scanId, projectId, sourceRevisionId, TaskStatus.SUCCEEDED)));
        when(kbScanRepository.countFindings(scanId, request)).thenReturn(25L);
        when(kbScanRepository.listFindingViews(scanId, request)).thenReturn(List.of(finding));

        var page = service().findings(scanId, request, actorId, false);

        assertThat(page.page()).isEqualTo(3);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.total()).isEqualTo(25);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.items()).extracting(FindingResponse::id).containsExactly(findingId);
        FindingResponse item = page.items().getFirst();
        assertThat(item.fact()).containsEntry("kind", "code");
        assertThat(item.conclusion()).containsEntry("severity", "high");
        assertThat(item.occurrences()).hasSize(1);
        assertThat(item.evidenceChain()).containsEntry("status", "complete");
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
        verify(kbScanRepository).countFindings(scanId, request);
        verify(kbScanRepository).listFindingViews(scanId, request);
    }

    @Test
    void wrapsInvalidFindingListSortAsBadRequest() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        FindingListRequest request = new FindingListRequest(1, 20, null, null, null, null, null, null, null, "unsafeField", "asc");
        when(kbScanRepository.findById(scanId)).thenReturn(Optional.of(run(scanId, projectId, sourceRevisionId, TaskStatus.SUCCEEDED)));
        when(kbScanRepository.countFindings(scanId, request)).thenThrow(new IllegalArgumentException("bad sort"));

        assertThatThrownBy(() -> service().findings(scanId, request, actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("Finding 查询参数不正确")
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
    }

    @Test
    void getsFindingDetailAfterProjectAccessCheck() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID findingId = UUID.randomUUID();
        when(kbScanRepository.findFinding(findingId)).thenReturn(Optional.of(finding(findingId, projectId, scanId, sourceRevisionId, "src/main.c", 1, 1)));

        FindingResponse response = service().finding(findingId, actorId, false);

        assertThat(response.id()).isEqualTo(findingId);
        assertThat(response.scanRunId()).isEqualTo(scanId);
        assertThat(response.fact()).containsEntry("symbol", "immintrin.h");
        assertThat(response.conclusion()).containsEntry("findingType", "issue");
        assertThat(response.occurrences()).hasSize(1);
        assertThat(response.evidenceChain()).containsEntry("status", "complete");
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
    }

    @Test
    void listsArtifactsWithPaginationFilteringAndSortingRequest() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        KbScanArtifactListRequest request = new KbScanArtifactListRequest(
                1,
                2,
                KbScanArtifactType.SCAN_REPORT_HTML,
                "1",
                "abc123",
                "sizeBytes",
                "desc"
        );
        KbScanArtifactSummaryResponse artifact = new KbScanArtifactSummaryResponse(
                artifactId,
                KbScanArtifactType.SCAN_REPORT_HTML.name(),
                "1",
                "abc123",
                1024,
                OffsetDateTime.now(),
                Map.of("format", "html")
        );
        when(kbScanRepository.findById(scanId)).thenReturn(Optional.of(run(scanId, projectId, sourceRevisionId, TaskStatus.SUCCEEDED)));
        when(kbScanRepository.countArtifacts(scanId, request)).thenReturn(3L);
        when(kbScanRepository.listArtifacts(scanId, request)).thenReturn(List.of(artifact));

        var page = service().artifacts(scanId, request, actorId, false);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(2);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).extracting(KbScanArtifactSummaryResponse::id).containsExactly(artifactId);
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
        verify(kbScanRepository).countArtifacts(scanId, request);
        verify(kbScanRepository).listArtifacts(scanId, request);
    }

    @Test
    void wrapsInvalidArtifactListSortAsBadRequest() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        KbScanArtifactListRequest request = new KbScanArtifactListRequest(1, 20, null, null, null, "unsafeField", "asc");
        when(kbScanRepository.findById(scanId)).thenReturn(Optional.of(run(scanId, projectId, sourceRevisionId, TaskStatus.SUCCEEDED)));
        when(kbScanRepository.countArtifacts(scanId, request)).thenThrow(new IllegalArgumentException("bad sort"));

        assertThatThrownBy(() -> service().artifacts(scanId, request, actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("KB 扫描 artifact 查询参数不正确")
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
    }

    @Test
    void downloadsKbArtifactByFormatAndKeepsPathOutOfResponseMetadata() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        Path outputRoot = Files.createDirectories(tempDir.resolve("runtime/kb-scans"));
        Path report = Files.writeString(outputRoot.resolve("scan-report.html"), "<html>ok</html>");
        when(kbScanRepository.findById(scanId)).thenReturn(Optional.of(run(scanId, projectId, sourceRevisionId, TaskStatus.SUCCEEDED)));
        when(kbScanRepository.findArtifactByType(scanId, KbScanArtifactType.SCAN_REPORT_HTML))
                .thenReturn(Optional.of(artifact(projectId, scanId, KbScanArtifactType.SCAN_REPORT_HTML, report)));

        KbScanService.DownloadableKbScanArtifact download = service().downloadArtifactByFormat(scanId, "html", actorId, false);

        assertThat(download.filename()).isEqualTo("scan-report-" + scanId + ".html");
        assertThat(download.contentType()).isEqualTo("text/html");
        assertThat(download.resource().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("<html>ok</html>");
        verify(projectAccessService).requireProjectAccess(projectId, actorId, false);
    }

    @Test
    void downloadsJsonAndLogArtifactsByFormat() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        Path outputRoot = Files.createDirectories(tempDir.resolve("runtime/kb-scans"));
        Path json = Files.writeString(outputRoot.resolve("scan-result.json"), "{\"ok\":true}");
        Path log = Files.writeString(outputRoot.resolve("scan.log"), "diagnostic");
        when(kbScanRepository.findById(scanId)).thenReturn(Optional.of(run(scanId, projectId, sourceRevisionId, TaskStatus.SUCCEEDED)));
        when(kbScanRepository.findArtifactByType(scanId, KbScanArtifactType.SCAN_RESULT_JSON))
                .thenReturn(Optional.of(artifact(projectId, scanId, KbScanArtifactType.SCAN_RESULT_JSON, json)));
        when(kbScanRepository.findArtifactByType(scanId, KbScanArtifactType.SCAN_LOG))
                .thenReturn(Optional.of(artifact(projectId, scanId, KbScanArtifactType.SCAN_LOG, log)));

        assertThat(service().downloadArtifactByFormat(scanId, "json", actorId, false).contentType()).isEqualTo("application/json");
        assertThat(service().downloadArtifactByFormat(scanId, "log", actorId, false).contentType()).isEqualTo("text/plain");
    }

    @Test
    void downloadsNewKbArtifactFromObjectStorage() {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID storedArtifactId = UUID.randomUUID();
        KbScanService service = service();
        when(kbScanRepository.findById(scanId)).thenReturn(Optional.of(run(scanId, projectId, UUID.randomUUID(), TaskStatus.SUCCEEDED)));
        when(kbScanRepository.findArtifactByType(scanId, KbScanArtifactType.SCAN_RESULT_JSON))
                .thenReturn(Optional.of(new KbScanArtifactDownload(
                        UUID.randomUUID(),
                        projectId,
                        scanId,
                        KbScanArtifactType.SCAN_RESULT_JSON.name(),
                        "0".repeat(64),
                        11,
                        tempDir.resolve("missing.json").toString(),
                        storedArtifactId
                )));
        when(storedArtifactService.download(storedArtifactId)).thenReturn(new StoredArtifactResource(
                storedArtifact(storedArtifactId, projectId, scanId, "SCAN_RESULT_JSON", "application/json"),
                new ByteArrayResource("{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        ));

        KbScanService.DownloadableKbScanArtifact result = service.downloadArtifactByFormat(scanId, "json", actorId, false);

        assertThat(result.contentType()).isEqualTo("application/json");
        assertThat(result.resource()).isInstanceOf(ByteArrayResource.class);
    }

    @Test
    void rejectsInvalidMissingUnauthorizedAndMissingFileArtifactDownloads() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();

        assertThatThrownBy(() -> service().downloadArtifactByFormat(scanId, "xml", actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("KB 扫描 artifact 下载格式不正确")
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        when(kbScanRepository.findById(scanId)).thenReturn(Optional.of(run(scanId, projectId, sourceRevisionId, TaskStatus.SUCCEEDED)));
        when(kbScanRepository.findArtifactByType(scanId, KbScanArtifactType.SCAN_REPORT_HTML)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().downloadArtifactByFormat(scanId, "html", actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("KB 扫描 artifact 不存在")
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);

        Path outputRoot = Files.createDirectories(tempDir.resolve("runtime/kb-scans"));
        when(kbScanRepository.findArtifactByType(scanId, KbScanArtifactType.SCAN_RESULT_JSON))
                .thenReturn(Optional.of(artifact(projectId, scanId, KbScanArtifactType.SCAN_RESULT_JSON, outputRoot.resolve("missing.json"))));
        assertThatThrownBy(() -> service().downloadArtifactByFormat(scanId, "json", actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("KB 扫描 artifact 文件不存在")
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);

        UUID forbiddenScanId = UUID.randomUUID();
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ACCESS_DENIED", "无项目访问权限"))
                .when(projectAccessService).requireProjectAccess(projectId, actorId, false);
        when(kbScanRepository.findById(forbiddenScanId)).thenReturn(Optional.of(run(forbiddenScanId, projectId, sourceRevisionId, TaskStatus.SUCCEEDED)));
        assertThatThrownBy(() -> service().downloadArtifactByFormat(forbiddenScanId, "html", actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("无项目访问权限");
    }

    @Test
    void rejectsArtifactDownloadWhenContentPathEscapesRootOrSymlinkEscapes() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        Path outputRoot = Files.createDirectories(tempDir.resolve("runtime/kb-scans"));
        Path outside = Files.writeString(tempDir.resolve("outside.json"), "{}");
        when(kbScanRepository.findById(scanId)).thenReturn(Optional.of(run(scanId, projectId, sourceRevisionId, TaskStatus.SUCCEEDED)));
        when(kbScanRepository.findArtifactByType(scanId, KbScanArtifactType.SCAN_RESULT_JSON))
                .thenReturn(Optional.of(artifact(projectId, scanId, KbScanArtifactType.SCAN_RESULT_JSON, outside)));

        assertThatThrownBy(() -> service().downloadArtifactByFormat(scanId, "json", actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("KB 扫描 artifact 路径非法")
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        UUID symlinkScanId = UUID.randomUUID();
        Path link = outputRoot.resolve("link.json");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException ex) {
            Assumptions.abort("当前文件系统不支持符号链接测试: " + ex.getMessage());
        }
        when(kbScanRepository.findById(symlinkScanId)).thenReturn(Optional.of(run(symlinkScanId, projectId, sourceRevisionId, TaskStatus.SUCCEEDED)));
        when(kbScanRepository.findArtifactByType(symlinkScanId, KbScanArtifactType.SCAN_RESULT_JSON))
                .thenReturn(Optional.of(artifact(projectId, symlinkScanId, KbScanArtifactType.SCAN_RESULT_JSON, link)));
        assertThatThrownBy(() -> service().downloadArtifactByFormat(symlinkScanId, "json", actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("KB 扫描 artifact 路径非法");
    }

    @Test
    void codeContextReturnsFixedWindowAndRejectsPathEscape() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID findingId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        Path source = Files.createDirectories(tempDir.resolve("source"));
        Path file = source.resolve("src/main.c");
        Files.createDirectories(file.getParent());
        Files.write(file, java.util.stream.IntStream.rangeClosed(1, 50).mapToObj(i -> "line-" + i).toList());
        when(kbScanRepository.findFinding(findingId)).thenReturn(Optional.of(finding(findingId, projectId, scanId, sourceRevisionId, "src/main.c", 25, 26)));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, source)));

        FindingCodeContextResponse response = service().codeContext(findingId, actorId, false);

        assertThat(response.contextBefore()).isEqualTo(20);
        assertThat(response.contextAfter()).isEqualTo(20);
        assertThat(response.startLine()).isEqualTo(5);
        assertThat(response.endLine()).isEqualTo(46);
        assertThat(response.lines()).hasSize(42);
        assertThat(response.lines()).filteredOn(FindingCodeContextResponse.CodeLine::highlighted)
                .extracting(FindingCodeContextResponse.CodeLine::lineNumber)
                .containsExactly(25, 26);
        assertThat(response.filePath()).isEqualTo("src/main.c");

        UUID escapedFindingId = UUID.randomUUID();
        when(kbScanRepository.findFinding(escapedFindingId)).thenReturn(Optional.of(finding(escapedFindingId, projectId, scanId, sourceRevisionId, "../secret.txt", 1, 1)));
        assertThatThrownBy(() -> service().codeContext(escapedFindingId, actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("Finding 源码路径非法");
    }

    @Test
    void codeContextRejectsMissingFileAndSymlinkEscape() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID sourceRevisionId = UUID.randomUUID();
        Path source = Files.createDirectories(tempDir.resolve("source"));
        UUID missingFindingId = UUID.randomUUID();
        when(kbScanRepository.findFinding(missingFindingId)).thenReturn(Optional.of(finding(missingFindingId, projectId, scanId, sourceRevisionId, "missing.c", 1, 1)));
        when(sourceRepository.findById(sourceRevisionId)).thenReturn(Optional.of(sourceRevision(projectId, sourceRevisionId, source)));

        assertThatThrownBy(() -> service().codeContext(missingFindingId, actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("Finding 源码文件不存在")
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);

        Path outside = Files.writeString(tempDir.resolve("secret.c"), "secret");
        Path link = source.resolve("link.c");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException ex) {
            Assumptions.abort("当前文件系统不支持符号链接测试: " + ex.getMessage());
        }
        UUID linkFindingId = UUID.randomUUID();
        when(kbScanRepository.findFinding(linkFindingId)).thenReturn(Optional.of(finding(linkFindingId, projectId, scanId, sourceRevisionId, "link.c", 1, 1)));
        assertThatThrownBy(() -> service().codeContext(linkFindingId, actorId, false))
                .isInstanceOf(ApiException.class)
                .hasMessage("Finding 源码路径非法")
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private KbScanService service() {
        return service(new RvPortProperties(null, new RvPortProperties.KbScan(null, null, null,
                tempDir.resolve("runtime/kb-scans").toString(), 1800, null, "c,cpp", "riscv64", 20), new RvPortProperties.Worker(false)));
    }

    private KbScanService service(RvPortProperties properties) {
        KbScanService service = new KbScanService(
                kbScanRepository,
                sourceRepository,
                projectAccessService,
                taskService,
                auditLogService,
                properties,
                storedArtifactService,
                nodeReportInclusionService
        );
        org.springframework.test.util.ReflectionTestUtils.setField(service, "scanContextService", scanContextService);
        return service;
    }

    private SourceRevisionView sourceRevision(UUID projectId, UUID sourceRevisionId, Path workingCopy) {
        return new SourceRevisionView(
                sourceRevisionId,
                projectId,
                SourceRevisionType.GIT,
                "main",
                "git@example/repo.git",
                "git@example/repo.git",
                "main",
                "abcdef",
                null,
                workingCopy.toString(),
                null,
                true,
                UUID.randomUUID(),
                OffsetDateTime.now()
        );
    }

    private TaskResponse task(UUID taskId, UUID projectId) {
        return new TaskResponse(taskId, projectId, RvPortTaskTypes.KB_SCAN, TaskStatus.PENDING, 0, "KB 扫描任务已创建",
                OffsetDateTime.now(), null, null, null, null, null, Map.of());
    }

    private KbScanView run(UUID scanId, UUID projectId, UUID sourceRevisionId, TaskStatus status) {
        return run(scanId, projectId, sourceRevisionId, status, Map.of("findingCount", 1));
    }

    private KbScanView run(UUID scanId, UUID projectId, UUID sourceRevisionId, TaskStatus status, Map<String, Object> summary) {
        return new KbScanView(
                scanId,
                projectId,
                UUID.randomUUID(),
                sourceRevisionId,
                "riscv-porting@2026-07",
                Map.of("mode", "ALL"),
                status,
                summary,
                OffsetDateTime.now().minusMinutes(1),
                OffsetDateTime.now()
        );
    }

    private FindingView finding(UUID findingId, UUID projectId, UUID scanId, UUID sourceRevisionId, String filePath, int lineStart, int lineEnd) {
        return new FindingView(
                findingId,
                projectId,
                scanId,
                sourceRevisionId,
                "C_CPP.X86.HEADER",
                "issue",
                "high",
                "使用 x86 header",
                "reason",
                filePath,
                lineStart,
                lineEnd,
                "immintrin.h",
                Map.of(
                        "schemaVersion", "2.0",
                        "finding", Map.of(
                                "id", "C_CPP.X86.HEADER",
                                "fact", Map.of(
                                        "kind", "code",
                                        "symbol", "immintrin.h",
                                        "primaryLocation", Map.of(
                                                "file", filePath,
                                                "span", Map.of(
                                                        "start", Map.of("line", lineStart),
                                                        "end", Map.of("line", lineEnd)
                                                )
                                        )
                                ),
                                "conclusion", Map.of(
                                        "severity", "high",
                                        "title", "使用 x86 header",
                                        "findingType", "issue"
                                ),
                                "occurrences", List.of(Map.of("id", "occurrence-1", "kind", "source-include")),
                                "evidenceChain", Map.of("status", "complete")
                        )
                ),
                "suggestion",
                "fingerprint",
                "DETECTED",
                OffsetDateTime.now()
        );
    }

    private KbScanArtifactDownload artifact(UUID projectId, UUID scanId, KbScanArtifactType type, Path contentPath) {
        return new KbScanArtifactDownload(
                UUID.randomUUID(),
                projectId,
                scanId,
                type.name(),
                "abc123",
                10,
                contentPath.toString(),
                null
        );
    }

    private StoredArtifactView storedArtifact(UUID artifactId, UUID projectId, UUID ownerId, String artifactType, String contentType) {
        return new StoredArtifactView(
                artifactId,
                projectId,
                UUID.randomUUID(),
                StoredArtifactOwnerType.KB_SCAN,
                ownerId,
                artifactType,
                "rvport-artifacts",
                "projects/test/object.json",
                contentType,
                11,
                "0".repeat(64),
                Map.of(),
                OffsetDateTime.now()
        );
    }
}
