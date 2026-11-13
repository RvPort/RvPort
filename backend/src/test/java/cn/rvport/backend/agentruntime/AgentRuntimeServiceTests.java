package cn.rvport.backend.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;

class AgentRuntimeServiceTests {

    private final AgentRuntimeRepository repository = mock(AgentRuntimeRepository.class);
    private final AgentRuntimeService service = new AgentRuntimeService(repository);

    @Test
    void listsAndGetsMappedRuntimeDetails() {
        AgentRuntimesRecord record = runtime(true);
        when(repository.list()).thenReturn(List.of(record));
        when(repository.findById(record.getId())).thenReturn(Optional.of(record));

        assertThat(service.list()).singleElement().satisfies(response -> {
            assertThat(response.agentType()).isEqualTo("CODEX");
            assertThat(response.defaultArgs()).isEmpty();
            assertThat(response.executablePath()).isNull();
            assertThat(response.config()).isEmpty();
        });
        assertThat(service.get(record.getId()).id()).isEqualTo(record.getId());
    }

    @Test
    void requireAndRequireEnabledReturnRepositoryRecords() {
        AgentRuntimesRecord record = runtime(true);
        when(repository.findById(record.getId())).thenReturn(Optional.of(record));
        when(repository.findEnabledById(record.getId())).thenReturn(Optional.of(record));

        assertThat(service.require(record.getId())).isSameAs(record);
        assertThat(service.requireEnabled(record.getId())).isSameAs(record);
    }

    @Test
    void rejectsMissingAndDisabledRuntime() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        when(repository.findEnabledById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.require(id))
                .isInstanceOf(ApiException.class)
                .hasMessage("Agent runtime 不存在");
        assertThatThrownBy(() -> service.requireEnabled(id))
                .isInstanceOf(ApiException.class)
                .hasMessage("Agent runtime 不存在或未启用");
    }

    @Test
    void malformedOptionalJsonFallsBackToEmptyCollections() {
        AgentRuntimesRecord record = runtime(false);
        record.setDefaultArgs(JSONB.valueOf("not-json"));
        record.setConfig(JSONB.valueOf("["));
        record.setLastProbeDetails(null);
        when(repository.list()).thenReturn(List.of(record));

        AgentRuntimeResponse response = service.list().getFirst();
        assertThat(response.enabled()).isFalse();
        assertThat(response.defaultArgs()).isEmpty();
        assertThat(response.config()).isEmpty();
        assertThat(response.lastProbeDetails()).isEmpty();
    }

    @Test
    void nullDefaultArgumentsAndBlankMapsUseEmptyCollections() {
        AgentRuntimesRecord record = runtime(true);
        record.setDefaultArgs(null);
        record.setConfig(JSONB.valueOf(" "));
        record.setLastProbeDetails(JSONB.valueOf(" "));
        when(repository.list()).thenReturn(List.of(record));

        AgentRuntimeResponse response = service.list().getFirst();

        assertThat(response.defaultArgs()).isEmpty();
        assertThat(response.config()).isEmpty();
        assertThat(response.lastProbeDetails()).isEmpty();
    }

    @Test
    void sharedProbeDetailsAreNeverExposed() {
        AgentRuntimesRecord blank = runtime(true);
        blank.setDefaultArgs(JSONB.valueOf(" "));
        blank.setConfig(null);
        blank.setLastProbeDetails(JSONB.valueOf("bad-json"));
        AgentRuntimesRecord valid = runtime(true);
        valid.setLastProbeDetails(JSONB.valueOf("{\"workspace\":true}"));
        when(repository.list()).thenReturn(List.of(blank, valid));

        List<AgentRuntimeResponse> responses = service.list();
        assertThat(responses.get(0).defaultArgs()).isEmpty();
        assertThat(responses.get(0).config()).isEmpty();
        assertThat(responses.get(0).lastProbeDetails()).isEmpty();
        assertThat(responses.get(1).lastProbeDetails()).isEmpty();
    }

    @Test
    void jsonLiteralNullDoesNotLeakSharedConfiguration() {
        AgentRuntimesRecord record = runtime(true);
        JSONB nullData = JSONB.valueOf(null);
        record.setDefaultArgs(nullData);
        record.setConfig(nullData);
        record.setLastProbeDetails(nullData);
        when(repository.list()).thenReturn(List.of(record));

        AgentRuntimeResponse response = service.list().getFirst();
        assertThat(response.defaultArgs()).isEmpty();
        assertThat(response.config()).isEmpty();
        assertThat(response.lastProbeDetails()).isEmpty();
    }

    private static AgentRuntimesRecord runtime(boolean enabled) {
        AgentRuntimesRecord record = new AgentRuntimesRecord();
        record.setId(UUID.randomUUID());
        record.setAgentType("CODEX");
        record.setName("Codex");
        record.setEnabled(enabled);
        record.setExecutablePath("codex");
        record.setDefaultArgs(JSONB.valueOf("[\"exec\"]"));
        record.setConfig(JSONB.valueOf("{\"sandbox\":\"read-only\"}"));
        record.setLastProbeDetails(JSONB.valueOf("{}"));
        return record;
    }
}
