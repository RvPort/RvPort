package cn.rvport.backend.buildvalidation;

import static org.assertj.core.api.Assertions.assertThat;
import cn.rvport.backend.task.TaskStatus;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BuildValidationPairTests {
    @Test void rejectsForeignSourceEnvironmentVersionAndScope() {
        Fixture f = new Fixture();
        var run = f.view();
        assertThat(BuildValidationPair.matches(run, f.project, f.source, f.environment, 7)).isTrue();
        assertThat(BuildValidationPair.matches(null, f.project, f.source, f.environment, 7)).isFalse();
        assertThat(BuildValidationPair.matches(run, f.project, null, f.environment, 7)).isFalse();
        assertThat(BuildValidationPair.matches(run, f.project, f.source, null, 7)).isFalse();
        assertThat(BuildValidationPair.matches(run, UUID.randomUUID(), f.source, f.environment, 7)).isFalse();
        assertThat(BuildValidationPair.matches(run, f.project, UUID.randomUUID(), f.environment, 7)).isFalse();
        assertThat(BuildValidationPair.matches(run, f.project, f.source, UUID.randomUUID(), 7)).isFalse();
        assertThat(BuildValidationPair.matches(run, f.project, f.source, f.environment, 8)).isFalse();
        f.scope = BuildValidationScope.SOURCE_ONLY;
        assertThat(BuildValidationPair.matches(f.view(), f.project, f.source, f.environment, 7)).isFalse();
    }

    @Test void requiresTwoSuccessfulDistinctSidesOfTheSameKnownBatchAndScript() {
        Fixture left = new Fixture(), right = new Fixture();
        right.facts = left.facts;
        assertThat(BuildValidationPair.succeeded(left.view(), right.view())).isTrue();
        assertThat(BuildValidationPair.succeeded(null, right.view())).isFalse();
        assertThat(BuildValidationPair.succeeded(left.view(), null)).isFalse();
        right.id = left.id;
        assertThat(BuildValidationPair.succeeded(left.view(), right.view())).isFalse();
        right.id = UUID.randomUUID(); right.environment = left.environment;
        assertThat(BuildValidationPair.succeeded(left.view(), right.view())).isFalse();
        right.environment = UUID.randomUUID(); left.status = TaskStatus.FAILED;
        assertThat(BuildValidationPair.succeeded(left.view(), right.view())).isFalse();
        left.status = TaskStatus.SUCCEEDED; right.status = TaskStatus.RUNNING;
        assertThat(BuildValidationPair.succeeded(left.view(), right.view())).isFalse();
        right.status = TaskStatus.SUCCEEDED;
        for (Map<String, Object> facts : java.util.List.<Map<String, Object>>of(Map.of(), Map.of("buildBatchId", 1),
                Map.of("buildBatchId", "unknown"), Map.of("buildBatchId", UUID.randomUUID().toString()))) {
            left.facts = facts;
            assertThat(BuildValidationPair.succeeded(left.view(), right.view())).isFalse();
        }
        left.facts = right.facts; right.sha = "other";
        assertThat(BuildValidationPair.succeeded(left.view(), right.view())).isFalse();
        left.sha = null; right.sha = null;
        assertThat(BuildValidationPair.succeeded(left.view(), right.view())).isFalse();
    }

    private static class Fixture {
        UUID id = UUID.randomUUID(), project = UUID.randomUUID(), source = UUID.randomUUID(), environment = UUID.randomUUID();
        BuildValidationScope scope = BuildValidationScope.BOTH;
        TaskStatus status = TaskStatus.SUCCEEDED;
        String sha = "digest";
        Map<String, Object> facts = Map.of("buildBatchId", UUID.randomUUID().toString());
        BuildValidationRunView view() {
            return new BuildValidationRunView(id, project, UUID.randomUUID(), source, environment, scope,
                    null, 7L, null, sha, "make", "/source", 60, 0, status,
                    null, null, null, null, null, null, null, Map.of(), facts);
        }
    }
}