package cn.rvport.backend.kb.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.artifact.*;
import cn.rvport.backend.buildvalidation.*;
import cn.rvport.backend.kb.*;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.worker.TaskExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;

class KbCompileDatabaseTests {
    @TempDir Path root;
    final UUID project = UUID.randomUUID(), source = UUID.randomUUID(), buildId = UUID.randomUUID(), artifactId = UUID.randomUUID();
    final StoredArtifactService artifacts = mock(StoredArtifactService.class);
    final TaskExecutionContext task = mock(TaskExecutionContext.class);
    String remoteRoot;
    final KbScanView run = KbContextFixtures.scan(project, source, TaskStatus.RUNNING, KbContextFixtures.CONTEXT);

    KbScanRuntimeService service() {
        return new KbScanRuntimeService(null, null, null, null, new ObjectMapper(), artifacts, null, null, null);
    }
    Map<?, ?> resolve(Map<String, Object> facts) {
        var build = new BuildValidationRunView(buildId, project, UUID.randomUUID(), source, KbContextFixtures.ENV,
                BuildValidationScope.SOURCE_ONLY, null, 7L, null, "hash", "make", remoteRoot, 60, 0,
                TaskStatus.SUCCEEDED, null, null, null, null, null, null, null, Map.of(), facts);
        Object result = ReflectionTestUtils.invokeMethod(service(), "resolveCompileCommands", build, run, root, root, task);
        return ReflectionTestUtils.invokeMethod(result, "summary");
    }
    Map<String, Object> available(Object id) { return Map.of("compileDatabase", Map.of("available", true, "storedArtifactId", id)); }
    StoredArtifactView artifact(UUID owner, UUID projectId, StoredArtifactOwnerType type, String artifactType) {
        return new StoredArtifactView(artifactId, projectId, UUID.randomUUID(), type, owner, artifactType,
                "bucket", "private-key", "application/json", 1, "hash", Map.of(), null);
    }
    void download(String json) {
        when(artifacts.download(artifactId)).thenReturn(new StoredArtifactResource(
                artifact(buildId, project, StoredArtifactOwnerType.BUILD_VALIDATION, BuildValidationArtifacts.COMPILE_COMMANDS_JSON),
                new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8))));
    }

    @Test void absentDatabaseAndMissingArtifactStayDistinctAndRetainBinding() {
        for (var facts : List.<Map<String,Object>>of(Map.of(), Map.of("compileDatabase", Map.of("available", false)))) {
            var summary = resolve(facts);
            assertThat(summary.get("mode")).isEqualTo("NOT_AVAILABLE");
            assertThat(summary.get("buildRunId")).isEqualTo(buildId);
        }
        for (var facts : List.of(Map.<String,Object>of("compileDatabase", Map.of("available", true)), available("bad-id"))) {
            var summary = resolve(facts);
            assertThat(summary.get("mode")).isEqualTo("FALLBACK");
            assertThat(summary.get("reason")).isEqualTo("ARTIFACT_ID_MISSING");
        }
        verifyNoInteractions(artifacts);
    }

    @Test void rejectsEachOwnershipDimension() {
        for (var wrong : List.of(
                artifact(buildId, project, StoredArtifactOwnerType.KB_SCAN, BuildValidationArtifacts.COMPILE_COMMANDS_JSON),
                artifact(UUID.randomUUID(), project, StoredArtifactOwnerType.BUILD_VALIDATION, BuildValidationArtifacts.COMPILE_COMMANDS_JSON),
                artifact(buildId, UUID.randomUUID(), StoredArtifactOwnerType.BUILD_VALIDATION, BuildValidationArtifacts.COMPILE_COMMANDS_JSON),
                artifact(buildId, project, StoredArtifactOwnerType.BUILD_VALIDATION, "OTHER"))) {
            when(artifacts.download(artifactId)).thenReturn(new StoredArtifactResource(wrong, new ByteArrayResource("[]".getBytes(StandardCharsets.UTF_8))));
            var summary = resolve(available(artifactId.toString()));
            assertThat(summary.get("mode")).isEqualTo("FALLBACK");
            assertThat(summary.get("reason")).isEqualTo("ARTIFACT_OWNER_MISMATCH");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", "null", "{}", "broken", "[null]", "[{}]", "[{\"file\":2}]", "[{\"file\":\" \"}]",
            "[{\"file\":\"a.c\"}]", "[{\"file\":\"a.c\",\"command\":\" \"}]", "[{\"file\":\"a.c\",\"arguments\":[]}]"})
    void invalidContentFallsBackWithoutExposingStorageDetails(String json) {
        download(json);
        var summary = resolve(available(artifactId));
        assertThat(summary.get("mode")).isEqualTo("FALLBACK");
        assertThat(summary.toString()).doesNotContain("private-key", "bucket", root.toString());
    }

    @ParameterizedTest @ValueSource(strings = {"[]", "[{\"file\":\"a.c\",\"command\":\"cc a.c\"}]", "[{\"file\":\"a.c\",\"arguments\":[\"cc\",\"a.c\"]}]"})
    void validContentIsUsedWithOrWithoutRemoteRoot(String json) throws Exception {
        download(json);
        for (String remote : Arrays.asList(null, "/remote")) {
            remoteRoot = remote;
            var summary = resolve(available(artifactId));
            assertThat(summary.get("mode")).isEqualTo("USED");
            assertThat(summary.get("source")).isEqualTo("BOUND_SOURCE_BUILD");
            assertThat(Files.readString(root.resolve("compile_commands.input.json"))).contains("[");
        }
    }
}
