package cn.rvport.backend.report;

import cn.rvport.backend.artifact.StoredArtifactOwnerType;
import cn.rvport.backend.artifact.StoredArtifactRepository;
import cn.rvport.backend.artifact.StoredArtifactResource;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.artifact.StoredArtifactView;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.reportcomparison.ReportComparisonRepository;
import cn.rvport.backend.reportcomparison.ReportComparisonSummaryResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {

    private final ReportRepository reportRepository;
    private final ProjectAccessService projectAccessService;
    private final StoredArtifactRepository storedArtifactRepository;
    private final StoredArtifactService storedArtifactService;
    private final ReportComparisonRepository reportComparisonRepository;
    private final cn.rvport.backend.report.review.ReviewWorkspaceService reviews;

    public ReportService(ReportRepository reportRepository,
            ProjectAccessService projectAccessService,
            StoredArtifactRepository storedArtifactRepository,
            StoredArtifactService storedArtifactService,
            ReportComparisonRepository reportComparisonRepository,
            cn.rvport.backend.report.review.ReviewWorkspaceService reviews) {
        this.reportRepository = reportRepository;
        this.projectAccessService = projectAccessService;
        this.storedArtifactRepository = storedArtifactRepository;
        this.storedArtifactService = storedArtifactService;
        this.reportComparisonRepository = reportComparisonRepository;
        this.reviews = reviews;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportResponse> list(UUID projectId, ReportListRequest request, UUID actorUserId, boolean superAdmin) {
        projectAccessService.requireProjectAccess(projectId, actorUserId, superAdmin);
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        try {
            long total = reportRepository.count(projectId, request);
            var rows=reportRepository.list(projectId,request);
            var metadata=reportRepository.sourceMetadata(rows.stream().map(ReportView::sourceRevisionId).filter(java.util.Objects::nonNull).distinct().toList());
            List<ReportResponse> items = rows
                    .stream()
                    .map(row->response(row,metadata.get(row.sourceRevisionId())))
                    .toList();
            return PageResponse.of(items, page, pageSize, total);
        } catch (IllegalArgumentException ex) {
            throw badRequest("REPORT_QUERY_INVALID", "报告查询参数不正确");
        }
    }

    @Transactional(readOnly = true)
    public ReportResponse get(UUID reportId, UUID actorUserId, boolean superAdmin) {
        return response(requireAccessibleReport(reportId, actorUserId, superAdmin));
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportInputResponse> inputs(UUID reportId, ReportInputListRequest request, UUID actorUserId, boolean superAdmin) {
        ReportView report = requireAccessibleReport(reportId, actorUserId, superAdmin);
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        try {
            long total = reportRepository.countInputs(report.id(), request);
            List<ReportInputResponse> items = reportRepository.listInputs(report.id(), request)
                    .stream()
                    .map(ReportInputResponse::from)
                    .toList();
            return PageResponse.of(items, page, pageSize, total);
        } catch (IllegalArgumentException ex) {
            throw badRequest("REPORT_INPUT_QUERY_INVALID", "报告输入查询参数不正确");
        }
    }

    @Transactional(readOnly = true)
    public DownloadableReport download(UUID reportId, String format, UUID actorUserId, boolean superAdmin) {
        ReportView report = requireAccessibleReport(reportId, actorUserId, superAdmin);
        ReportDownloadFormat downloadFormat;
        try {
            downloadFormat = ReportDownloadFormat.parse(format);
        } catch (IllegalArgumentException ex) {
            throw badRequest("REPORT_FORMAT_INVALID", "报告下载格式不正确");
        }
        StoredArtifactView artifact = storedArtifactRepository
                .findByOwner(StoredArtifactOwnerType.MIGRATION_REPORT, report.id(), downloadFormat.artifactType())
                .orElseThrow(() -> notFound("REPORT_ARTIFACT_NOT_FOUND", "报告制品不存在"));
        StoredArtifactResource resource = storedArtifactService.download(artifact.id());
        return new DownloadableReport(
                resource.resource(),
                "migration-report-%s.%s".formatted(report.id(), downloadFormat.extension()),
                resource.artifact().contentType()
        );
    }

    private ReportView requireAccessibleReport(UUID reportId, UUID actorUserId, boolean superAdmin) {
        ReportView report = reportRepository.findById(reportId)
                .orElseThrow(() -> notFound("REPORT_NOT_FOUND", "报告不存在"));
        projectAccessService.requireProjectAccess(report.projectId(), actorUserId, superAdmin);
        return report;
    }

    private ReportResponse response(ReportView report) {
        var metadata=report.sourceRevisionId()==null?null:reportRepository.sourceMetadata(List.of(report.sourceRevisionId())).get(report.sourceRevisionId());
        return response(report,metadata);
    }
    private ReportResponse response(ReportView report,java.util.Map<String,Object> metadata) {
        List<ReportArtifactSummaryResponse> artifacts = storedArtifactRepository
                .listByOwner(StoredArtifactOwnerType.MIGRATION_REPORT, report.id())
                .stream()
                .filter(artifact -> "MIGRATION_REPORT_HTML".equals(artifact.artifactType())
                        || "MIGRATION_REPORT_JSON".equals(artifact.artifactType()))
                .map(ReportArtifactSummaryResponse::from)
                .toList();
        LatestComparableReportResponse latestComparable = reportComparisonRepository.latestComparableBase(report)
                .map(LatestComparableReportResponse::from)
                .orElse(null);
        ReportComparisonSummaryResponse latestComparison = reportComparisonRepository.latestForTargetReport(report.id())
                .map(ReportComparisonSummaryResponse::from)
                .orElse(null);
        return ReportResponse.from(report, artifacts, latestComparable, latestComparison, reviews.metadata(report.id()),metadata);
    }

    private ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public record DownloadableReport(Resource resource, String filename, String contentType) {
    }
}
