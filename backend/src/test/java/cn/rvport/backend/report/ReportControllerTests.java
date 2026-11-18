package cn.rvport.backend.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.reportcomparison.ReportComparisonService;
import cn.rvport.backend.security.RvPortUserPrincipal;
import cn.rvport.backend.user.UserStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class ReportControllerTests {

    private final ReportService reportService = mock(ReportService.class);
    private final ReportComparisonService comparisonService = mock(ReportComparisonService.class);
    private final ReportOrganizationService organizationService = mock(ReportOrganizationService.class);
    private final ReportController controller = new ReportController(reportService, comparisonService, organizationService,mock(cn.rvport.backend.report.review.ReviewWorkspaceService.class));
    private final RvPortUserPrincipal principal = new RvPortUserPrincipal(UUID.randomUUID(), "user", "User",
            "user@example.com", false, false, UserStatus.ACTIVE);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void delegatesEveryReportAndComparisonEndpoint() {
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID comparisonId = UUID.randomUUID();
        UUID diffId = UUID.randomUUID();

        assertThat(controller.listEvaluation(principal, projectId, null, request).timestamp()).isNotNull();
        assertThat(controller.get(principal, reportId, request).timestamp()).isNotNull();
        assertThat(controller.inputs(principal, reportId, null, request).timestamp()).isNotNull();
        assertThat(controller.codeModules(principal, reportId, null, request).timestamp()).isNotNull();
        assertThat(controller.codeFiles(principal, reportId, "module", null, request).timestamp()).isNotNull();
        assertThat(controller.codeFindingTypes(principal, reportId, "module", "file", null, request).timestamp()).isNotNull();
        assertThat(controller.codeFindings(principal, reportId, "module", "file", "type", null, request).timestamp()).isNotNull();
        assertThat(controller.codeFileContext(principal, reportId, "file", null, request).timestamp()).isNotNull();
        assertThat(controller.createComparison(principal, reportId, null, request).timestamp()).isNotNull();
        assertThat(controller.getComparison(principal, comparisonId, request).timestamp()).isNotNull();
        assertThat(controller.comparisonDiffItems(principal, comparisonId, null, request).timestamp()).isNotNull();
        assertThat(controller.comparisonDiffItem(principal, comparisonId, diffId, request).timestamp()).isNotNull();

        verify(reportService).list(projectId, null, principal.id(), false);
        verify(reportService).get(reportId, principal.id(), false);
        verify(reportService).inputs(reportId, null, principal.id(), false);
        verify(organizationService).modules(reportId, null, principal.id(), false);
        verify(organizationService).files(reportId, "module", null, principal.id(), false);
        verify(organizationService).findingTypes(reportId, "module", "file", null, principal.id(), false);
        verify(organizationService).findings(reportId, "module", "file", "type", null, principal.id(), false);
        verify(organizationService).context(reportId, "file", null, principal.id(), false);
        verify(comparisonService).create(reportId, null, principal.id(), false, request);
        verify(comparisonService).get(comparisonId, principal.id(), false);
        verify(comparisonService).diffItems(comparisonId, null, principal.id(), false);
        verify(comparisonService).diffItem(comparisonId, diffId, principal.id(), false);
    }

    @Test
    void downloadBuildsAttachmentResponseWithServiceMetadata() {
        UUID reportId = UUID.randomUUID();
        ByteArrayResource resource = new ByteArrayResource("report".getBytes());
        when(reportService.download(reportId, "json", principal.id(), false))
                .thenReturn(new ReportService.DownloadableReport(resource, "report.json", "application/json"));

        var response = controller.download(principal, reportId, "json",null);

        assertThat(response.getBody()).isSameAs(resource);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json");
        assertThat(response.getHeaders().getContentDisposition().getFilename()).isEqualTo("report.json");
    }

    @Test
    void serviceErrorsPropagateWithoutBeingRewritten() {
        UUID reportId = UUID.randomUUID();
        ApiException failure = new ApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "报告不存在");
        when(reportService.get(reportId, principal.id(), false)).thenThrow(failure);

        assertThatThrownBy(() -> controller.get(principal, reportId, request)).isSameAs(failure);
    }
}
