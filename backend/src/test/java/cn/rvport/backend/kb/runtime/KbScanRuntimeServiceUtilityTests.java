package cn.rvport.backend.kb.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.rvport.backend.archive.ArchiveCandidateRegistrar;
import cn.rvport.backend.artifact.StoredArtifactService;
import cn.rvport.backend.buildvalidation.BuildValidationRepository;
import cn.rvport.backend.config.RvPortProperties;
import cn.rvport.backend.kb.KbScanRepository;
import cn.rvport.backend.kb.KbFindingInput;
import cn.rvport.backend.kb.KbScanInput;
import cn.rvport.backend.kb.KbScanView;
import cn.rvport.backend.kb.KbScannerResult;
import cn.rvport.backend.project.runtime.ProjectRuntimeEnvironmentRepository;
import cn.rvport.backend.source.SourceRepository;
import cn.rvport.backend.source.SourceRevisionType;
import cn.rvport.backend.source.SourceRevisionView;
import cn.rvport.backend.task.ToolRunRepository;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import cn.rvport.backend.worker.TaskExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class KbScanRuntimeServiceUtilityTests {

    @TempDir
    private Path tempDir;

    @Test
    void recognizesOnlyStructuredPathFields() {
        KbScanRuntimeService service = service(null);

        for (String field : List.of("file", "directory", "cwd", "source_path", "source-paths", "projectRoot")) {
            assertThat((boolean) invoke(service, "isPathField", field)).isTrue();
        }
        assertThat((boolean) invoke(service, "isPathField", (String) null)).isFalse();
        assertThat((boolean) invoke(service, "isPathField", "matchedText")).isFalse();
        assertThat((boolean) invoke(service, "isPathField", "pathology")).isFalse();
    }

    @Test
    void normalizesRelativeAbsoluteExternalAndInvalidFindingPaths() throws IOException {
        KbScanRuntimeService service = service(null);
        Path source = Files.createDirectories(tempDir.resolve("source"));
        Path nested = Files.createDirectories(source.resolve("src")).resolve("main.c");
        Files.writeString(nested, "int main(){}", StandardCharsets.UTF_8);

        assertThat((String) invoke(service, "relativePath", source, (String) null)).isNull();
        assertThat((String) invoke(service, "relativePath", source, " ")).isNull();
        assertThat((String) invoke(service, "relativePath", source, "src/main.c")).isEqualTo("src/main.c");
        assertThat((String) invoke(service, "relativePath", source, nested.toString())).isEqualTo("src/main.c");
        assertThat((String) invoke(service, "relativePath", source, tempDir.resolve("outside.c").toString()))
                .isEqualTo("outside.c");
        assertThat((String) invoke(service, "relativePath", source, "bad\0path")).isNull();
    }

    @Test
    void choosesPrimaryLocationAndNormalizesLineBounds() {
        KbScanRuntimeService service = service(null);
        KbScannerResult.Location primary = location("primary.c", 3, 4);
        KbScannerResult.Location fallback = location("fallback.c", 8, 9);
        KbScannerResult.Finding direct = finding(new KbScannerResult.Fact(
                "code", "x", null, primary, null), List.of());
        KbScannerResult.Finding occurrence = finding(null, java.util.Arrays.asList(
                null,
                new KbScannerResult.Occurrence("1", "code", null, null, null, null, null, null),
                new KbScannerResult.Occurrence("2", "code", fallback, null, null, null, null, null)));

        assertThat((KbScannerResult.Location) invoke(service, "primaryLocation", (KbScannerResult.Finding) null)).isNull();
        assertThat((KbScannerResult.Location) invoke(service, "primaryLocation", direct)).isEqualTo(primary);
        assertThat((KbScannerResult.Location) invoke(service, "primaryLocation", occurrence)).isEqualTo(fallback);
        assertThat((KbScannerResult.Location) invoke(service, "primaryLocation", finding(null, null))).isNull();
        assertThat((Integer) invoke(service, "line", (KbScannerResult.Position) null)).isNull();
        assertThat((Integer) invoke(service, "line", new KbScannerResult.Position(null, 1))).isNull();
        assertThat((Integer) invoke(service, "line", new KbScannerResult.Position(0, 1))).isEqualTo(1);
        assertThat((Integer) invoke(service, "line", new KbScannerResult.Position(7, 1))).isEqualTo(7);
    }

    @Test
    void sanitizesNestedMapsListsAndTextWithoutDamagingSourceCode() throws IOException {
        KbScanRuntimeService service = service(null);
        Path source = Files.createDirectories(tempDir.resolve("source"));
        Path output = Files.createDirectories(tempDir.resolve("output"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("file", source.resolve("main.c").toString());
        input.put("paths", List.of(source.resolve("a.c").toString(), "../outside"));
        input.put("matchedText", "#include \"../compat/header.h\" at " + output.resolve("scan.log"));
        input.put("count", 3);

        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = invoke(service, "sanitizeValue", input, null, source, output);

        assertThat(sanitized.get("file")).isEqualTo("<source-root>");
        assertThat(sanitized.get("paths").toString()).contains("<source-root>", "<server-path-redacted>");
        assertThat(sanitized.get("matchedText").toString())
                .contains("#include \"../compat/header.h\"")
                .doesNotContain(output.toString());
        assertThat(sanitized.get("count")).isEqualTo(3);
    }

    @Test
    void buildsNullSafeScannerSummaryForPresentAndAbsentSections() {
        KbScanRuntimeService service = service(null);
        KbScannerResult empty = new KbScannerResult("2.0", null, null, null, null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> emptySummary = invoke(service, "safeSummary", empty, 0, null);
        assertThat(emptySummary).containsEntry("findingCount", 0).containsEntry("diagnosticCount", 0);
        assertThat(emptySummary.get("tool")).isEqualTo(Map.of());
        assertThat(emptySummary.get("scan")).isEqualTo(Map.of());
        assertThat(emptySummary.get("summary")).isEqualTo(Map.of());
        assertThat(emptySummary.get("diagnostics")).isEqualTo(List.of());

        KbScannerResult.Scan scan = new KbScannerResult.Scan(
                "scan", Map.of("name", "kb"), Map.of("mode", "ALL"), List.of(), List.of());
        KbScannerResult populated = new KbScannerResult(
                "2.0", scan, Map.of(), Map.of("files", 1), List.of(), List.of(Map.of("level", "info")));
        Map<String, Object> compile = Map.of("mode", "USED");
        @SuppressWarnings("unchecked")
        Map<String, Object> populatedSummary = invoke(service, "safeSummary", populated, 2, compile);
        assertThat(populatedSummary).containsEntry("findingCount", 2)
                .containsEntry("diagnosticCount", 1)
                .containsEntry("compileCommands", compile);
        assertThat(populatedSummary.get("tool")).isEqualTo(Map.of("name", "kb"));
    }

    @Test
    void handlesUuidCollectionsCommandsStreamsAndTextBoundaries() {
        KbScanRuntimeService service = service(null);
        UUID id = UUID.randomUUID();
        assertThat((UUID) invoke(service, "uuidValue", id)).isEqualTo(id);
        assertThat((UUID) invoke(service, "uuidValue", id.toString())).isEqualTo(id);
        assertThat((UUID) invoke(service, "uuidValue", " ")).isNull();
        assertThat((UUID) invoke(service, "uuidValue", "not-a-uuid")).isNull();
        assertThat((UUID) invoke(service, "uuidValue", 1)).isNull();

        assertThat(this.<List<String>>invoke(service, "splitCommand", (String) null)).containsExactly("python");
        assertThat(this.<List<String>>invoke(service, "splitCommand", " ")).containsExactly("python");
        assertThat(this.<List<String>>invoke(service, "splitCommand", "python  -m scanner"))
                .containsExactly("python", "-m", "scanner");
        assertThat((String) invoke(service, "append", null, "right")).isEqualTo("right");
        assertThat((String) invoke(service, "append", " ", "right")).isEqualTo("right");
        assertThat((String) invoke(service, "append", "left", "right"))
                .isEqualTo("left" + System.lineSeparator() + "right");
        assertThat((String) invoke(service, "truncate", null, 3)).isEmpty();
        assertThat((String) invoke(service, "truncate", "abc", 3)).isEqualTo("abc");
        assertThat((String) invoke(service, "truncate", "abcdef", 3)).startsWith("abc\n");
        assertThat((String) invoke(service, "read", new ByteArrayInputStream("中文".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("中文");
        assertThat((String) invoke(service, "read", new FailingInputStream())).isEmpty();
    }

    @Test
    void convertsMapsAndOptionalValuesWithoutExternalState() {
        KbScanRuntimeService service = service(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> mapped = invoke(service, "mapValue", Map.of(1, "one"));
        assertThat(mapped).containsEntry("1", "one");
        assertThat((Map<?, ?>) invoke(service, "mapValue", "not-map")).isEmpty();
        assertThat(this.<List<String>>invoke(service, "safeList", (List<String>) null)).isEmpty();
        assertThat(this.<List<String>>invoke(service, "safeList", List.of("x"))).containsExactly("x");
        Map<String, Object> target = new LinkedHashMap<>();
        invoke(service, "putIfNotNull", target, "missing", null);
        invoke(service, "putIfNotNull", target, "present", 1);
        assertThat(target).containsExactly(Map.entry("present", 1));
    }

    @Test
    void rewritesCompileDatabasePathsRecursivelyAndLeavesUnrelatedValuesAlone() throws IOException {
        KbScanRuntimeService service = service(null);
        String remote = "/remote/source";
        String local = tempDir.resolve("local-source").toAbsolutePath().normalize().toString();
        List<Object> arguments = new ArrayList<>(List.of(
                "/remote/source/include", "-Wall", 7));
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("directory", remote);
        command.put("file", remote + "/src/main.c");
        command.put("command", "gcc -c main.c");
        command.put("arguments", arguments);
        command.put("nested", Map.of("file", remote + "/ignored.c"));
        List<Object> payload = new ArrayList<>(List.of(command, "scalar", Map.of("directory", 42)));

        assertThat((boolean) invoke(service, "rewriteCompileCommandPaths", payload, remote, local)).isTrue();
        assertThat(command.get("directory")).isEqualTo(local);
        assertThat(command.get("file").toString()).endsWith("src" + java.io.File.separator + "main.c");
        assertThat(command.get("command")).isEqualTo("gcc -c main.c");
        assertThat(arguments.get(0).toString()).endsWith("include");
        assertThat(arguments.get(1)).isEqualTo("-Wall");
        assertThat(arguments.get(2)).isEqualTo(7);
        assertThat((boolean) invoke(service, "rewriteCompileCommandPaths", "scalar", remote, local)).isFalse();

        Path passthrough = tempDir.resolve("compile-no-root.json");
        assertThat((boolean) invoke(service, "writeLocalCompileCommands", passthrough,
                "[]".getBytes(StandardCharsets.UTF_8), " ", tempDir)).isFalse();
        assertThat(Files.readString(passthrough)).isEqualTo("[]");
        assertThat((String) invoke(service, "normalizeRemoteRoot", "\\remote\\source///"))
                .isEqualTo("/remote/source");
    }

    @Test
    void mapsSparseAndReversedFindingsToStableDefaults() throws IOException {
        KbScanRuntimeService service = service(null);
        UUID projectId = UUID.randomUUID();
        UUID scanId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        KbScanView run = new KbScanView(
                scanId, projectId, UUID.randomUUID(), revisionId, null, Map.of(),
                TaskStatus.RUNNING, Map.of(), OffsetDateTime.now(), null);
        Path source = Files.createDirectories(tempDir.resolve("finding-source"));
        KbScannerResult empty = new KbScannerResult("2.0", null, null, null, null, null);
        assertThat(this.<List<KbFindingInput>>invoke(service, "mapFindings", run, source, empty)).isEmpty();

        KbScannerResult.Finding sparse = new KbScannerResult.Finding(
                null, null, null, null, null, null);
        KbScannerResult.Location reversed = new KbScannerResult.Location(
                "main.c", new KbScannerResult.Span(
                new KbScannerResult.Position(10, 1), new KbScannerResult.Position(2, 1)), Map.of());
        KbScannerResult.Fact fact = new KbScannerResult.Fact("code", " ", null, reversed, null);
        KbScannerResult.Conclusion conclusion = new KbScannerResult.Conclusion(
                " ", " ", null, " ", null, null, null, null, null, null);
        KbScannerResult.Finding detailed = new KbScannerResult.Finding(
                "rule", fact, conclusion, List.of(), null, null);
        KbScannerResult result = new KbScannerResult(
                "2.0", null, null, null, java.util.Arrays.asList(null, sparse, detailed), null);

        List<KbFindingInput> mapped = invoke(service, "mapFindings", run, source, result);

        assertThat(mapped).hasSize(2);
        assertThat(mapped.get(0).ruleId()).isEqualTo("UNKNOWN");
        assertThat(mapped.get(0).category()).isEqualTo("issue");
        assertThat(mapped.get(0).severity()).isEqualTo("info");
        assertThat(mapped.get(0).title()).isEqualTo("KB Scanner Finding");
        assertThat(mapped.get(1).filePath()).isEqualTo("main.c");
        assertThat(mapped.get(1).lineStart()).isEqualTo(10);
        assertThat(mapped.get(1).lineEnd()).isEqualTo(10);
        assertThat(mapped.get(1).symbol()).isNull();
    }

    @Test
    void appliesConfiguredAndDefaultScannerProperties() throws IOException {
        KbScanRuntimeService defaults = service(null);
        assertThat((String) invoke(defaults, "pythonCommand")).isEqualTo("python");
        assertThat((String) invoke(defaults, "defaultLanguage")).isEqualTo("c,cpp");
        assertThat((String) invoke(defaults, "defaultSourceArch")).isEqualTo("x86");
        assertThat((String) invoke(defaults, "defaultTargetArch")).isEqualTo("riscv64");
        assertThat((int) invoke(defaults, "timeoutSeconds")).isEqualTo(1800);
        assertThat((long) invoke(defaults, "maxOutputBytes")).isEqualTo(10_485_760L);

        Path scanner = Files.createDirectories(tempDir.resolve("scanner"));
        Path output = Files.createDirectories(tempDir.resolve("scanner-output"));
        RvPortProperties.KbScan configured = new RvPortProperties.KbScan(
                "python3 -I", scanner.toString(), tempDir.resolve("rules").toString(),
                output.toString(), 45, 2048L, "c", "arm64", "arm64", 12);
        KbScanRuntimeService service = service(configured);
        assertThat((String) invoke(service, "pythonCommand")).isEqualTo("python3 -I");
        assertThat((String) invoke(service, "defaultLanguage")).isEqualTo("c");
        assertThat((String) invoke(service, "defaultSourceArch")).isEqualTo("arm64");
        assertThat((String) invoke(service, "defaultTargetArch")).isEqualTo("arm64");
        assertThat((int) invoke(service, "timeoutSeconds")).isEqualTo(45);
        assertThat((long) invoke(service, "maxOutputBytes")).isEqualTo(2048L);
        assertThat((Path) invoke(service, "scannerRoot")).isEqualTo(scanner);
        assertThat((Path) invoke(service, "outputRoot")).isEqualTo(output);
        assertThat((Path) invoke(service, "rulesetPath")).isEqualTo(tempDir.resolve("rules").normalize());

        RvPortProperties.KbScan blanks = new RvPortProperties.KbScan(
                " ", scanner.toString(), " ", output.toString(), null, null, " ", " ", " ", null);
        KbScanRuntimeService blankService = service(blanks);
        assertThat((String) invoke(blankService, "pythonCommand")).isEqualTo("python");
        assertThat((String) invoke(blankService, "defaultLanguage")).isEqualTo("c,cpp");
        assertThat((String) invoke(blankService, "defaultSourceArch")).isEqualTo("x86");
        assertThat((String) invoke(blankService, "defaultTargetArch")).isEqualTo("riscv64");
        assertThat((int) invoke(blankService, "timeoutSeconds")).isEqualTo(1800);
        assertThat((long) invoke(blankService, "maxOutputBytes")).isEqualTo(10_485_760L);
    }

    @Test
    void normalizesSourceArchAliasesToScannerFamilies() {
        // record 紧凑构造器只补默认值，不做别名归一（归一发生在 KbScanService.create）。
        assertThat(new RvPortProperties.KbScan(null, null, null, null, null, null, null, "arm64", null, null).sourceArch()).isEqualTo("arm64");
        assertThat(new RvPortProperties.KbScan(null, null, null, null, null, null, null, null, null, null).sourceArch()).isEqualTo("x86");
    }

    @Test
    void validatesSourceRootAndSanitizesEveryJsonNodeShape() throws IOException {
        KbScanRuntimeService service = service(null);
        UUID projectId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        SourceRevisionView missing = new SourceRevisionView(
                revisionId, projectId, SourceRevisionType.GIT, "main", null, null, null, null,
                null, null, null, true, UUID.randomUUID(), OffsetDateTime.now());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> invoke(service, "sourceRoot", missing))
                .isInstanceOf(cn.rvport.backend.task.TaskExecutionException.class)
                .hasMessage("源码工作副本不存在");
        SourceRevisionView blank = new SourceRevisionView(
                revisionId, projectId, SourceRevisionType.GIT, "main", null, null, null, null,
                null, " ", null, true, UUID.randomUUID(), OffsetDateTime.now());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> invoke(service, "sourceRoot", blank))
                .isInstanceOf(cn.rvport.backend.task.TaskExecutionException.class);
        Path source = Files.createDirectories(tempDir.resolve("json-source"));
        SourceRevisionView present = new SourceRevisionView(
                revisionId, projectId, SourceRevisionType.GIT, "main", null, null, null, null,
                null, source.toString(), null, true, UUID.randomUUID(), OffsetDateTime.now());
        assertThat((Path) invoke(service, "sourceRoot", present)).isEqualTo(source);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode tree = mapper.readTree("{\"file\":\"" + escapeJson(source.resolve("a.c").toString())
                + "\",\"items\":[\"plain\",null,3]}");
        JsonNode sanitized = invoke(service, "sanitizeJsonNode", tree, null, source, tempDir.resolve("out"));
        assertThat(sanitized.get("file").textValue()).isEqualTo("<source-root>");
        assertThat(sanitized.get("items").get(0).textValue()).isEqualTo("plain");
        assertThat(sanitized.get("items").get(1).isNull()).isTrue();
        assertThat(sanitized.get("items").get(2).intValue()).isEqualTo(3);
        assertThat((JsonNode) invoke(service, "sanitizeJsonNode", null, null, source, tempDir)).isNull();
    }

    @Test
    void validatesNullAndMissingProjectInputsBeforeScanning() {
        KbScanRuntimeService service = service(null);
        UUID projectId = UUID.randomUUID();
        TaskExecutionContext context = mock(TaskExecutionContext.class);
        org.mockito.Mockito.when(context.task()).thenReturn(new TaskView(
                UUID.randomUUID(), projectId, "KB_SCAN", TaskStatus.RUNNING, 0, "running",
                UUID.randomUUID(), OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(),
                null, null, null, null, 0));

        for (KbScanInput input : java.util.Arrays.asList(
                null,
                new KbScanInput(null, null, UUID.randomUUID(), null, null, null, null, null, null),
                new KbScanInput(UUID.randomUUID(), null, UUID.randomUUID(), null, null, null, null, null, null))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> invoke(service, "validate", input, context))
                    .isInstanceOf(cn.rvport.backend.task.TaskExecutionException.class);
        }
        assertThat((String) invoke(service, "normalizeRemoteRoot", "/")).isEqualTo("/");
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private KbScanRuntimeService service(RvPortProperties.KbScan kbScan) {
        return new KbScanRuntimeService(
                mock(KbScanRepository.class), mock(SourceRepository.class), mock(ToolRunRepository.class),
                new RvPortProperties(null, kbScan, new RvPortProperties.Worker(false)),
                new ObjectMapper(), mock(StoredArtifactService.class), mock(ArchiveCandidateRegistrar.class),
                mock(BuildValidationRepository.class),
                mock(ProjectRuntimeEnvironmentRepository.class));
    }

    private KbScannerResult.Location location(String file, int start, int end) {
        return new KbScannerResult.Location(file,
                new KbScannerResult.Span(
                        new KbScannerResult.Position(start, 1),
                        new KbScannerResult.Position(end, 1)), Map.of());
    }

    private KbScannerResult.Finding finding(
            KbScannerResult.Fact fact,
            List<KbScannerResult.Occurrence> occurrences
    ) {
        return new KbScannerResult.Finding("id", fact, null, occurrences, null, null);
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(KbScanRuntimeService service, String method, Object... args) {
        return (T) ReflectionTestUtils.invokeMethod(service, method, args);
    }

    private static final class FailingInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("read failed");
        }
    }
}
