package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

/** 回写服务的 DSL 编排与 JSON 序列化容错（真实 SQL 语义由 MigrationStatusWriterDbTests 覆盖）。 */
class MigrationStatusWriterTests {

    @Test void 累计各条目更新数并对空集合与失败态委托容错() {
        var dsl = mock(DSLContext.class);
        when(dsl.execute(anyString(), any(Object[].class))).thenReturn(1);
        var writer = new MigrationStatusWriter(dsl, new ObjectMapper());
        var run = UUID.randomUUID();
        int updated = writer.writeStatuses(UUID.randomUUID(), run, List.of(
                new MigrationStatusWriter.StatusEntry("a", "MIGRATED"),
                new MigrationStatusWriter.StatusEntry("b", "MIGRATION_NO_WORK"),
                new MigrationStatusWriter.StatusEntry("c", "MIGRATION_FAILED")));
        assertThat(updated).isEqualTo(3);
        verify(dsl, times(3)).execute(anyString(), any(Object[].class));
        assertThat(writer.writeStatuses(UUID.randomUUID(), run, List.of())).isZero();
        assertThat(writer.writeFailed(UUID.randomUUID(), run, List.of("a", "b"))).isEqualTo(2);
        assertThat(writer.resetToBaseline(UUID.randomUUID(), List.of("a"))).isEqualTo(1);
    }

    @Test void 序列化失败抛出稳定错误码且不半途提交() {
        var dsl = mock(DSLContext.class);
        var broken = mock(ObjectMapper.class);
        try {
            when(broken.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("boom") { });
        } catch (Exception ignored) {
            // mock 打桩声明受检异常，仅声明不会真的抛出
        }
        var writer = new MigrationStatusWriter(dsl, broken);
        assertThatThrownBy(() -> writer.writeStatuses(UUID.randomUUID(), UUID.randomUUID(),
                List.of(new MigrationStatusWriter.StatusEntry("a", "MIGRATED"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MIGRATION_STATUS_JSON_INVALID");
        verify(dsl, times(0)).execute(anyString(), any(Object[].class));
    }
}
