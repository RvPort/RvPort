package cn.rvport.backend.modelprofile;

import static org.assertj.core.api.Assertions.*;

import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import cn.rvport.backend.jooq.tables.records.ModelProfilesRecord;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelExecutionSelectionTests {
    @Test void preservesOnlySelectedReferencesAndRejectsMissingOrForeignIdentity() {
        UUID actor = UUID.randomUUID(), profileId = UUID.randomUUID(), runtimeId = UUID.randomUUID();
        var profile = new ModelProfilesRecord();
        profile.setId(profileId);
        var runtime = new AgentRuntimesRecord();
        runtime.setId(runtimeId);
        var selection = ModelExecutionSelection.from(actor, new AgentModelRuntimeConfig(runtime, profile, null));
        assertThat(selection.configurationUserId()).isEqualTo(actor);
        assertThat(selection.modelProfileId()).isEqualTo(profileId);
        assertThat(selection.agentRuntimeId()).isEqualTo(runtimeId);
        profile.setBaseUrl("https://private.invalid");
        profile.setApiKeyEncrypted("never-log-secret");
        profile.setUpdatedAt(java.time.OffsetDateTime.parse("2026-11-11T00:00:00Z"));
        assertThat(selection.attemptAudit(new AgentModelRuntimeConfig(runtime, profile, null)))
                .contains(actor.toString(), profileId.toString(), runtimeId.toString(), "2026-11-11")
                .doesNotContain("private.invalid", "never-log-secret");
        var config = new AgentModelRuntimeConfig(runtime, profile, null);
        var result = cn.rvport.backend.codexprovider.CodexProviderTestResult.success("ok", java.time.Instant.now());
        var audited = selection.auditProbe(result, config);
        assertThat(audited.details().get("modelExecution").toString()).contains(actor.toString(), "2026-11-11")
                .doesNotContain("private.invalid", "never-log-secret");
        profile.setUpdatedAt(java.time.OffsetDateTime.parse("2026-11-12T00:00:00Z"));
        assertThat(selection.auditProbe(audited, config).details()).isEqualTo(audited.details());
        assertThat(audited.success()).isTrue();
        assertThat(audited.testedAt()).isEqualTo(result.testedAt());
        assertThatCode(() -> selection.requireActor(actor)).doesNotThrowAnyException();
        for (var invalid : new ModelExecutionSelection[]{
                new ModelExecutionSelection(null, profileId, runtimeId),
                new ModelExecutionSelection(UUID.randomUUID(), profileId, runtimeId),
                new ModelExecutionSelection(actor, null, runtimeId),
                new ModelExecutionSelection(actor, profileId, null)}) {
            assertThatThrownBy(() -> invalid.requireActor(actor)).isInstanceOf(ApiException.class)
                    .hasMessageContaining("Trusted model execution identity missing");
        }
        assertThatThrownBy(() -> selection.requireActor(null)).isInstanceOf(ApiException.class);
    }
}
