package cn.rvport.backend.modelprofile;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.rvport.backend.agentruntime.*;
import cn.rvport.backend.codexprovider.*;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.jooq.tables.records.*;
import cn.rvport.backend.kb.ai.KbAiExtensionAgentRuntime;
import cn.rvport.backend.security.CredentialCipher;
import cn.rvport.backend.testcomparison.runtime.TestComparisonAgentRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PersonalModelBoundaryTests {
    private final ModelProfileRepository profiles = mock(ModelProfileRepository.class);
    private final CredentialCipher cipher = mock(CredentialCipher.class);
    private final CodexProviderProbeService probes = mock(CodexProviderProbeService.class);
    private final AgentRuntimeRepository runtimes = mock(AgentRuntimeRepository.class);
    private final AgentRuntimeService runtimeService = mock(AgentRuntimeService.class);
    private final ModelConfigurationAccess access = mock(ModelConfigurationAccess.class);
    private final ModelProfileService service = service();

    private ModelProfileService service() {
        var result = new ModelProfileService(profiles, cipher, new ObjectMapper(), probes, runtimes, runtimeService);
        result.setConfigurationAccess(access);
        return result;
    }

    @Test void foreignAndUnknownIdsAreIndistinguishableForEveryPersonalEntryPointIncludingAdmin() {
        UUID foreign = UUID.randomUUID(), run = UUID.randomUUID();
        for (UUID actor : List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())) {
            List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> actions = List.of(
                    () -> service.get(foreign, actor), () -> service.update(foreign, null, actor),
                    () -> service.delete(foreign, actor), () -> service.test(foreign, null, actor),
                    () -> service.testConnectivity(foreign, null, actor),
                    () -> service.startAgentTests(foreign, null, actor),
                    () -> service.listAgentProbeResults(foreign, actor),
                    () -> service.getAgentTestRun(run, actor));
            for (var action : actions) {
                assertThatThrownBy(action).isInstanceOf(ApiException.class)
                        .satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));
            }
            verify(profiles, times(7)).findById(foreign, actor);
            verify(profiles).findProbeRun(run, actor);
        }
        verifyNoInteractions(cipher, probes, runtimes, runtimeService);
        verify(profiles, never()).delete(any(), any());
        verify(profiles, never()).listLatestProbeResults(any(), org.mockito.ArgumentMatchers.any());
        verify(profiles, never()).listProbeResultsByRun(any(), org.mockito.ArgumentMatchers.any());
    }

    @Test void rejectedAccountNeverQueriesPrivateDataOrProbes() {
        UUID actor = UUID.randomUUID();
        doThrow(new ApiException(HttpStatus.BAD_REQUEST, "MODEL_CONFIGURATION_USER_UNAVAILABLE", "disabled"))
                .when(access).requireActive(actor);
        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable action : List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> service.list(null, actor), () -> service.create(null, actor),
                () -> service.get(UUID.randomUUID(), actor), () -> service.listAssignments(actor),
                () -> service.saveAssignments(null, actor), () -> service.getAgentTestRun(UUID.randomUUID(), actor),
                () -> service.resolveAgentForStage(ModelProfileStage.KB_AI_EXTENSION, actor))) {
            assertThatThrownBy(action).hasMessage("disabled");
        }
        verifyNoInteractions(profiles, probes, cipher, runtimeService, runtimes);
    }

    @Test void disabledProfileAndExecutableOverrideNeverStartProbe() {
        UUID actor = UUID.randomUUID(), id = UUID.randomUUID();
        var profile = new ModelProfilesRecord(); profile.setId(id); profile.setEnabled(false);
        when(profiles.findById(id, actor)).thenReturn(Optional.of(profile));
        assertThatThrownBy(() -> service.startAgentTests(id, new ModelProfileAgentProbeRequest(List.of(UUID.randomUUID())), actor))
                .isInstanceOf(ApiException.class).hasMessageContaining("disabled");
        var override = new ModelProfileRequest("x", true, "/bin/arbitrary", "OPENAI_COMPATIBLE", "https://x.invalid",
                "x", "CHAT", null, false, 60, 1800, Set.of("executablePath"));
        assertThatThrownBy(() -> service.create(override, actor)).hasMessageContaining("cannot be overridden");
        assertThatThrownBy(() -> service.update(id, override, actor)).hasMessageContaining("cannot be overridden");
        assertThatThrownBy(() -> service.testConnectivity(id, override, actor)).hasMessageContaining("cannot be overridden");
        verifyNoInteractions(probes, runtimeService, runtimes, cipher);
    }

    @Test void disabledDefaultRuntimeCannotBeUsedForPersonalProbe() {
        UUID actor = UUID.randomUUID(), id = UUID.randomUUID();
        var profile = new ModelProfilesRecord(); profile.setId(id); profile.setEnabled(true);
        when(profiles.findById(id, actor)).thenReturn(Optional.of(profile));
        var disabled = new AgentRuntimesRecord(); disabled.setEnabled(false);
        when(runtimes.findByType(AgentType.CODEX)).thenReturn(Optional.of(disabled));
        assertThatThrownBy(() -> service.test(id, null, actor)).isInstanceOf(ApiException.class)
                .hasMessage("默认 Codex Agent runtime 不存在");
        verifyNoInteractions(probes, cipher);
    }

    @Test void claimUsesFixedReferencesAndCurrentVersionWithoutReadingStageBindings() {
        UUID actor = UUID.randomUUID(), project = UUID.randomUUID(), profileId = UUID.randomUUID(), runtimeId = UUID.randomUUID();
        var profile = new ModelProfilesRecord();
        profile.setId(profileId); profile.setOwnerUserId(actor); profile.setEnabled(true);
        profile.setModelProviderType("OPENAI_COMPATIBLE"); profile.setWireApi("CHAT");
        profile.setBaseUrl("https://alice.invalid/v1"); profile.setModel("first");
        profile.setTimeoutSeconds(60); profile.setMaxRuntimeSeconds(1800);
        var runtime = new AgentRuntimesRecord(); runtime.setId(runtimeId); runtime.setExecutablePath("managed-codex");
        when(profiles.findEnabledById(profileId, actor)).thenReturn(Optional.of(profile));
        when(runtimeService.requireEnabled(runtimeId)).thenReturn(runtime);
        var first = service.resolveExecution(actor, project, profileId, runtimeId);
        profile.setModel("second");
        var retry = service.resolveExecution(actor, project, profileId, runtimeId);
        assertThat(first.model().model()).isEqualTo("first");
        assertThat(retry.model().model()).isEqualTo("second");
        assertThat(retry.model().executablePath()).isEqualTo("managed-codex");
        verify(access, times(2)).requireProject(actor, project);
        verify(profiles, never()).findAssignment(any(), any());
        when(profiles.findEnabledById(profileId, actor)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveExecution(actor, project, profileId, runtimeId)).isInstanceOf(ApiException.class);
        verify(runtimeService, times(2)).requireEnabled(runtimeId);
        verifyNoInteractions(probes);
    }

    @Test void bothBusinessRuntimesRejectDamagedIdentityBeforeReadingOrExecutingConfiguration() {
        var modelService = mock(ModelProfileService.class);
        var registry = mock(AgentRuntimeAdapterRegistry.class);
        var kb = new KbAiExtensionAgentRuntime(modelService, registry, new ObjectMapper());
        var comparison = new TestComparisonAgentRuntime(modelService, registry, new ObjectMapper());
        UUID actor = UUID.randomUUID(), project = UUID.randomUUID();
        for (ModelExecutionSelection selection : Arrays.asList(null,
                new ModelExecutionSelection(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))) {
            assertThatThrownBy(() -> kb.resolveExecution(selection, actor, project)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> comparison.resolveExecution(selection, actor, project)).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(modelService, registry);
        var selection = new ModelExecutionSelection(actor, UUID.randomUUID(), UUID.randomUUID());
        var managed = new AgentRuntimesRecord(); managed.setExecutablePath("codex");
        var config = new AgentModelRuntimeConfig(managed, new ModelProfilesRecord(),
                new CodexProviderResolvedConfig(true,"codex",CodexProviderType.OPENAI_COMPATIBLE,
                        "https://alice.invalid/v1","model",CodexProviderWireApi.CHAT,"fixture-key",60,1800));
        when(modelService.resolveExecution(actor, project, selection.modelProfileId(), selection.agentRuntimeId())).thenReturn(config);
        assertThat(kb.resolveExecution(selection, actor, project)).isSameAs(config);
        assertThat(comparison.resolveExecution(selection, actor, project)).isSameAs(config);
        verify(modelService, never()).resolveAgentForStage(any(), any());
        verifyNoInteractions(registry);
    }
}
