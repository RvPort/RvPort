package cn.rvport.backend.analysis;

import static org.assertj.core.api.Assertions.*;
import cn.rvport.backend.analysis.AnalysisInputPlan.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalysisInputPlanTests {
    final UUID project = UUID.randomUUID(), source = UUID.randomUUID(), build = UUID.randomUUID();
    final String hash = "a".repeat(64);
    Identity identity(String language, String architecture) {
        return Identity.of(language, architecture, "riscv64", Map.of(), List.of(), "rules@1");
    }
    AnalysisInputPlan plan(Identity identity, InputOrigin origin, BuildBinding binding, List<Artifact> artifacts) {
        return new AnalysisInputPlan(1, project, source, hash, "KB", "0.1.0", "profile@1", identity,
                origin, binding, artifacts, List.of(), List.of(), null);
    }
    BuildBinding binding(UUID projectId, UUID revision, String architecture) {
        return new BuildBinding(build, projectId, revision, UUID.randomUUID(), 1, architecture, "clang@21.1.8", "x86_64-linux-gnu", "lp64");
    }
    Artifact artifact(UUID owner) { return new Artifact(UUID.randomUUID(), owner, "COMPILE_DATABASE", hash); }

    @Test void normalizesEquivalentLanguagesArchitectureAndPathsWithoutLosingRawRequests() throws Exception {
        var left = identity(" CPP, c,c, rust ", "AMD64");
        var right = identity("rust,c,cpp", "x86_64");
        assertThat(left.effectiveLanguages()).containsExactly("c", "cpp", "rust");
        assertThat(left.requestedLanguage()).isEqualTo(" CPP, c,c, rust ");
        assertThat(left.architectureAssumed()).isFalse();
        var a = plan(left, InputOrigin.SOURCE_DISCOVERY, null, List.of());
        var b = plan(right, InputOrigin.SOURCE_DISCOVERY, null, List.of());
        assertThat(a.effectiveInputDigest()).isEqualTo(b.effectiveInputDigest());
        assertThat(a.originalInputDigest()).isEqualTo(a.effectiveInputDigest());
        assertThat(new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(a), AnalysisInputPlan.class)).isEqualTo(a);
        assertThat(identity(null, null).effectiveLanguages()).containsExactly("c", "cpp");
        assertThat(identity("c", "aarch64").effectiveSourceArch()).isEqualTo("arm64");
        var scoped = Identity.of("c", "x86", "riscv64", Map.of("mode", " paths ", "paths", List.of("src\\c", "src/c")),
                List.of("vendor", "generated", "vendor"), "rules@1");
        assertThat(scoped.scanScope()).containsEntry("paths", List.of("src/c"));
        assertThat(scoped.excludePaths()).containsExactly("generated", "vendor");
    }

    @Test void inputChangesAffectDigestAndCollectionsAreImmutable() {
        var base = plan(identity("c", "x86"), InputOrigin.SOURCE_DISCOVERY, null, List.of());
        for (var changed : List.of(identity("c,cpp", "x86"), identity("c", "arm64"), identity("c", "unknown"),
                Identity.of("c", "x86", "riscv64", Map.of(), List.of("vendor"), "rules@1"),
                Identity.of("c", "x86", "riscv64", Map.of(), List.of(), "rules@2"))) {
            assertThat(base.effectiveInputDigest()).isNotEqualTo(plan(changed, InputOrigin.SOURCE_DISCOVERY, null, List.of()).effectiveInputDigest());
        }
        assertThatThrownBy(() -> base.identity().effectiveLanguages().add("rust")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> base.identity().scanScope().put("mode", "PATHS")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> base.summary().put("secret", "value")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(base.summary()).containsEntry("inputOrigin", InputOrigin.SOURCE_DISCOVERY).containsEntry("build", null);
        assertThat((Map<String,Object>)base.summary().get("identity"))
                .containsEntry("requestedLanguage","c").containsEntry("requestedSourceArch","x86");
    }

    @Test void trustedArtifactsRequireMatchingIdentityAndFallbackPreservesRequestedDigest() {
        var binding = binding(project, source, "x86");
        List<Artifact> artifacts = new ArrayList<>(List.of(artifact(build), artifact(build)));
        var enhanced = plan(identity("c", "x86"), InputOrigin.BUILD_ARTIFACTS, binding, artifacts);
        java.util.Collections.reverse(artifacts);
        assertThat(enhanced.effectiveInputDigest()).isEqualTo(plan(identity("c", "amd64"), InputOrigin.BUILD_ARTIFACTS, binding, artifacts).effectiveInputDigest());
        artifacts.clear();
        assertThat(enhanced.artifacts()).hasSize(2);
        var fallback = enhanced.sourceFallback("COMPILE_DATABASE_UNAVAILABLE");
        assertThat(fallback.inputOrigin()).isEqualTo(InputOrigin.SOURCE_DISCOVERY);
        assertThat(fallback.build()).isNull();
        assertThat(fallback.artifacts()).isEmpty();
        assertThat(fallback.originalInputDigest()).isEqualTo(enhanced.effectiveInputDigest());
        assertThat(fallback.effectiveInputDigest()).isNotEqualTo(enhanced.effectiveInputDigest());
        assertThat(fallback.sourceFallback("COMPILE_DATABASE_UNAVAILABLE")).isEqualTo(fallback);
        assertThat(fallback.sourceFallback("BITCODE_UNAVAILABLE").originalInputDigest()).isEqualTo(enhanced.effectiveInputDigest());
        for (var wrong : List.of(binding(UUID.randomUUID(), source, "x86"), binding(project, UUID.randomUUID(), "x86"), binding(project, source, "arm64")))
            assertThatThrownBy(() -> plan(identity("c", "x86"), InputOrigin.BUILD_ARTIFACTS, wrong, List.of(artifact(build))))
                    .hasMessage("ANALYSIS_ARTIFACT_IDENTITY_MISMATCH");
        assertThatThrownBy(() -> plan(identity("c", "unknown"), InputOrigin.BUILD_ARTIFACTS, binding, List.of(artifact(build))))
                .hasMessage("ANALYSIS_ARTIFACT_IDENTITY_MISMATCH");
        assertThatThrownBy(() -> plan(identity("c", "x86"), InputOrigin.BUILD_ARTIFACTS, binding, List.of(artifact(UUID.randomUUID()))))
                .hasMessage("ANALYSIS_ARTIFACT_IDENTITY_MISMATCH");
    }

    @Test void rejectsInvalidOriginsVersionsHashesAndRequiredFields() {
        var id = identity("c", "x86");
        for (InputOrigin origin : InputOrigin.values()) {
            assertThatThrownBy(() -> plan(id, origin, binding(project, source, "x86"), List.of())).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> plan(id, InputOrigin.BUILD_ARTIFACTS, null, List.of(artifact(build)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan(id, InputOrigin.SOURCE_DISCOVERY, null, List.of(artifact(build)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan(id, null, null, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan(null, InputOrigin.SOURCE_DISCOVERY, null, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisInputPlan(0, project, source, hash, "KB", "1", "1", id,
                InputOrigin.SOURCE_DISCOVERY, null, List.of(), List.of(), List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        for (String invalid : java.util.Arrays.asList(null, "", "ABC", "G".repeat(64))) {
            assertThatThrownBy(() -> new Artifact(build, source, "COMPILE_DATABASE", invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Artifact(null, build, "A", hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Artifact(build, null, "A", hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Artifact(build, source, " ", hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisInputPlan(1, null, source, hash, "KB", "1", "1", id,
                InputOrigin.SOURCE_DISCOVERY, null, List.of(), List.of(), List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisInputPlan(1, project, null, hash, "KB", "1", "1", id,
                InputOrigin.SOURCE_DISCOVERY, null, List.of(), List.of(), List.of(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsUnknownLanguagesPathsScopesAndOversizedAuditValues() {
        for (String language : List.of("c,", "", "swift", "c,java,,cpp", " "))
            assertThatThrownBy(() -> identity(language, "x86")).isInstanceOf(IllegalArgumentException.class);
        for (String path : List.of("", "../src", "src/../other", "/etc", "C:/root", "src//file", "src/./file", "src\n/file"))
            assertThatThrownBy(() -> Identity.of("c", "x86", "riscv64", Map.of(), List.of(path), "r1")).isInstanceOf(IllegalArgumentException.class);
        for (Map<String, Object> scope : List.<Map<String,Object>>of(Map.of("secret", "value"), Map.of("mode", 5),
                Map.of("mode", "UNKNOWN"), Map.of("paths", "src"), Map.of("paths", List.of(5)), Map.of("mode", "PATHS")))
            assertThatThrownBy(() -> Identity.of("c", "x86", "riscv64", scope, List.of(), "r1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> identity("c,".repeat(101) + "c", "x86")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> identity("c", "x".repeat(101))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BuildBinding(build, project, source, UUID.randomUUID(), -1, "x86", "clang", "target", "abi"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsIncompleteIdentityAndBuildBindings() {
        UUID environment = UUID.randomUUID();
        for (int omitted = 0; omitted < 4; omitted++) {
            int field = omitted;
            assertThatThrownBy(() -> new BuildBinding(field == 0 ? null : build, field == 1 ? null : project,
                    field == 2 ? null : source, field == 3 ? null : environment, 0, "x86", "clang", "target", "abi"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Identity("c", null, "x86", "x86", false, "riscv64", Map.of(), List.of(), "r1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Identity("c", List.of(), "x86", "x86", false, "riscv64", Map.of(), List.of(), "r1"))
                .isInstanceOf(IllegalArgumentException.class);
        for (String architecture : java.util.Arrays.asList(null, "unknown"))
            assertThatThrownBy(() -> new Identity("c", List.of("c"), "x86", architecture, false, "riscv64", Map.of(), List.of(), "r1"))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identity.of("c", "x86", "riscv64", null, List.of(), "r1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identity.of("c", "x86", "riscv64", Map.of(), null, "r1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void preservesImmutableArchiveAndGitSourceIdentity() {
        for (String digest : List.of(hash, "git:" + "a".repeat(40), "git:" + "b".repeat(64))) {
            var plan = new AnalysisInputPlan(1, project, source, digest, "KB", "1", "1", identity("c", "x86"),
                    InputOrigin.SOURCE_DISCOVERY, null, List.of(), List.of(), List.of(), null);
            assertThat(plan.sourceDigest()).isEqualTo(digest);
            assertThat(plan.summary()).containsEntry("sourceDigest", digest);
        }
        for (String digest : java.util.Arrays.asList(null, "main", "git:main", "git:abcd", "G".repeat(64)))
            assertThatThrownBy(() -> new AnalysisInputPlan(1, project, source, digest, "KB", "1", "1", identity("c", "x86"),
                    InputOrigin.SOURCE_DISCOVERY, null, List.of(), List.of(), List.of(), null))
                    .hasMessage("ANALYSIS_SOURCE_DIGEST_INVALID");
    }
}
