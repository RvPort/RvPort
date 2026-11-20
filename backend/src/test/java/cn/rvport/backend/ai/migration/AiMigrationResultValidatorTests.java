package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiMigrationResultValidatorTests {

    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 正常结果分类计数() throws Exception {
        var root = mapper.readTree("""
                {"entries":[
                  {"id":"finding:a","status":"DONE","changeSummary":"替换为 vadd","files":["src/a.c"]},
                  {"id":"finding:b","status":"NO_WORK_NEEDED","reason":"已有 __riscv 分支"},
                  {"id":"finding:c","status":"PENDING","reason":"映射无把握"}
                ]}
                """);
        var result = AiMigrationResultValidator.validate(List.of("finding:a", "finding:b", "finding:c"), root);

        assertThat(result.expectedCount()).isEqualTo(3);
        assertThat(result.entries()).hasSize(3);
        assertThat(result.doneCount()).isEqualTo(1);
        assertThat(result.noWorkNeededCount()).isEqualTo(1);
        assertThat(result.pendingCount()).isEqualTo(1);
        assertThat(result.missingCount()).isZero();
        assertThat(result.invalid()).isFalse();
        assertThat(result.allMissing()).isFalse();
        assertThat(result.entries().get(0).files()).containsExactly("src/a.c");
        assertThat(result.entries().get(1).reason()).isEqualTo("已有 __riscv 分支");
    }

    @Test
    void 未知与重复条目计数不静默丢弃() throws Exception {
        var root = mapper.readTree("""
                {"entries":[
                  {"id":"finding:a","status":"DONE"},
                  {"id":"finding:a","status":"PENDING"},
                  {"id":"finding:unknown","status":"DONE"}
                ]}
                """);
        var result = AiMigrationResultValidator.validate(List.of("finding:a", "finding:b"), root);

        assertThat(result.entries()).hasSize(1);
        assertThat(result.unknownIds()).containsExactly("finding:unknown");
        assertThat(result.duplicateIds()).containsExactly("finding:a");
        assertThat(result.missingCount()).isEqualTo(1);
        assertThat(result.invalid()).isTrue();
    }

    @Test
    void 全部缺失与空集合() throws Exception {
        var root = mapper.readTree("{\"entries\":[]}");
        var result = AiMigrationResultValidator.validate(List.of("finding:a"), root);
        assertThat(result.allMissing()).isTrue();
        assertThat(result.missingCount()).isEqualTo(1);
    }

    @Test
    void 仅重复与空期望集合() throws Exception {
        var dupOnly = mapper.readTree("""
                {"entries":[
                  {"id":"finding:a","status":"DONE"},
                  {"id":"finding:a","status":"PENDING"}
                ]}
                """);
        var result = AiMigrationResultValidator.validate(List.of("finding:a"), dupOnly);
        assertThat(result.invalid()).isTrue();
        assertThat(result.duplicateIds()).containsExactly("finding:a");
        assertThat(result.entries()).hasSize(1);

        var empty = mapper.readTree("{\"entries\":[]}");
        var zero = AiMigrationResultValidator.validate(List.of(), empty);
        assertThat(zero.allMissing()).isFalse();
        assertThat(zero.expectedCount()).isZero();
    }

    @Test
    void 信封形态与同义字段容错() throws Exception {
        var root = mapper.readTree("""
                {"schemaVersion":"ai-migration.v1","scanRunId":"x","results":[
                  {"id":"finding:a","status":"DONE","summary":"改动说明","files":["src/a.c"]},
                  {"id":"finding:b","status":"NO_WORK_NEEDED","marking":"已有 __riscv 分支"}
                ]}
                """);
        var result = AiMigrationResultValidator.validate(List.of("finding:a", "finding:b"), root);
        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries().get(0).changeSummary()).isEqualTo("改动说明");
        assertThat(result.entries().get(1).reason()).isEqualTo("已有 __riscv 分支");
        assertThat(result.doneCount()).isEqualTo(1);
        assertThat(result.noWorkNeededCount()).isEqualTo(1);
    }

    @Test
    void 非常规形态容错() throws Exception {
        var root = mapper.readTree("""
                {"entries":[
                  {"id":"finding:a","status":"DONE","files":"not-array"},
                  {"id":"finding:zz","status":"DONE"},
                  {"id":"finding:zz","status":"PENDING"}
                ]}
                """);
        var result = AiMigrationResultValidator.validate(List.of("finding:a", "finding:b"), root);
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).files()).isEmpty();
        assertThat(result.unknownIds()).containsExactly("finding:zz");
    }

    @Test
    void 非法结构拒绝() {
        assertThatThrownBy(() -> AiMigrationResultValidator.validate(List.of("finding:a"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiMigrationResultValidator.validate(List.of("finding:a"),
                mapper.createObjectNode().put("entries", "nope")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiMigrationResultValidator.validate(List.of("finding:a"),
                mapper.readTree("{\"entries\":[{\"id\":\"finding:a\",\"status\":\"MAYBE\"}]}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiMigrationResultValidator.validate(List.of("finding:a"),
                mapper.readTree("{\"entries\":[{\"id\":\"\",\"status\":\"DONE\"}]}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
