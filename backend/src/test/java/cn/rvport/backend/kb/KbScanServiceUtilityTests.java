package cn.rvport.backend.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.audit.AuditLogService;
import cn.rvport.backend.config.RvPortProperties;
import cn.rvport.backend.migration.NodeReportInclusionService;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.task.TaskService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class KbScanServiceUtilityTests {

    @Test
    void normalizesScopeExcludesDefaultsAndConfiguredLimits() {
        KbScanService defaults = service(new RvPortProperties(null, null, new RvPortProperties.Worker(false)));
        assertThat(this.<Map<String, Object>>invoke(defaults, "scanScope", (KbScanScope) null))
                .containsEntry("mode", "ALL").containsEntry("paths", List.of());
        assertThat(this.<Map<String, Object>>invoke(defaults, "scanScope",
                new KbScanScope(" ", java.util.Arrays.asList(null, "", "src"))))
                .containsEntry("mode", "ALL").containsEntry("paths", List.of("src"));
        assertThat(this.<Map<String, Object>>invoke(defaults, "scanScope", new KbScanScope("PATHS", null)))
                .containsEntry("mode", "PATHS").containsEntry("paths", List.of());
        assertThat(this.<List<String>>invoke(defaults, "excludePaths", (List<String>) null))
                .containsExactly(".git", "build");
        assertThat(this.<List<String>>invoke(defaults, "excludePaths", List.of()))
                .containsExactly(".git", "build");
        assertThat(this.<List<String>>invoke(defaults, "excludePaths", java.util.Arrays.asList(" src ", null, " ")))
                .containsExactly("src");
        assertThat((int) invoke(defaults, "timeoutSeconds")).isEqualTo(1800);
        assertThat((int) invoke(defaults, "contextLines")).isEqualTo(20);
        assertThat((String) invoke(defaults, "defaultLanguage")).isEqualTo("c,cpp");

        RvPortProperties configured = new RvPortProperties(null,
                new RvPortProperties.KbScan(null, null, null, " ", 45, null, " rust ", null, "riscv64", -3, null),
                new RvPortProperties.Worker(false));
        KbScanService custom = service(configured);
        assertThat((int) invoke(custom, "timeoutSeconds")).isEqualTo(45);
        assertThat((int) invoke(custom, "contextLines")).isZero();
        assertThat((String) invoke(custom, "defaultLanguage")).isEqualTo("rust");
        assertThat((Path) invoke(custom, "outputRoot")).isAbsolute();
    }

    @Test
    void resolvesAbsoluteCurrentAndParentRelativePaths() {
        KbScanService service = service(new RvPortProperties(null, null, new RvPortProperties.Worker(false)));
        Path absolute = Path.of("target").toAbsolutePath();
        assertThat((Path) invoke(service, "resolvePath", absolute.toString())).isEqualTo(absolute.normalize());
        assertThat((Path) invoke(service, "resolvePath", ".")).isEqualTo(Path.of(".").toAbsolutePath().normalize());
        // 父级回退语义依赖 cwd 布局（原断言假设 cwd=backend/ 且父目录是仓库根）——
        // 在任意工作目录下改为验证确定性语义：两侧都不存在时回退 cwd 绝对路径。
        assertThat((Path) invoke(service, "resolvePath", "definitely/missing/nested/file.txt"))
                .isEqualTo(Path.of("definitely/missing/nested/file.txt").toAbsolutePath().normalize());
        assertThat((Path) invoke(service, "resolvePath", "definitely-missing-path"))
                .isEqualTo(Path.of("definitely-missing-path").toAbsolutePath().normalize());
    }

    @Test
    void mapsEveryArtifactFilenameAndContentType() {
        KbScanService service = service(new RvPortProperties(null, null, new RvPortProperties.Worker(false)));
        UUID scanId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        assertThat(filename(service, artifactId, scanId, "SCAN_REPORT_HTML")).isEqualTo("scan-report-" + scanId + ".html");
        assertThat(filename(service, artifactId, scanId, "SCAN_RESULT_JSON")).isEqualTo("scan-result-" + scanId + ".json");
        assertThat(filename(service, artifactId, scanId, "SCAN_LOG")).isEqualTo("scan-log-" + scanId + ".log");
        assertThat(filename(service, artifactId, scanId, "OTHER")).isEqualTo("kb-scan-artifact-" + artifactId);
        assertThat((String) invoke(service, "contentType", "SCAN_REPORT_HTML")).isEqualTo("text/html");
        assertThat((String) invoke(service, "contentType", "SCAN_RESULT_JSON")).isEqualTo("application/json");
        assertThat((String) invoke(service, "contentType", "SCAN_LOG")).isEqualTo("text/plain");
        assertThat((String) invoke(service, "contentType", "OTHER")).isEqualTo("application/octet-stream");
    }

    private String filename(KbScanService service, UUID artifactId, UUID scanId, String type) {
        return invoke(service, "artifactFilename",
                new KbScanArtifactDownload(artifactId, UUID.randomUUID(), scanId, type, "sha", 1, "path", null));
    }

    private KbScanService service(RvPortProperties properties) {
        return new KbScanService(
                mock(KbScanRepository.class), mock(SourceRepository.class), mock(ProjectAccessService.class),
                mock(TaskService.class), mock(AuditLogService.class), properties,
                mock(StoredArtifactService.class), mock(NodeReportInclusionService.class));
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(KbScanService service, String method, Object... args) {
        return (T) ReflectionTestUtils.invokeMethod(service, method, args);
    }
}
