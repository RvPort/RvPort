package cn.rvport.backend.modelprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.agentruntime.AgentRuntimeRepository;
import cn.rvport.backend.agentruntime.AgentRuntimeService;
import cn.rvport.backend.codexprovider.CodexProviderProbeService;
import cn.rvport.backend.codexprovider.CodexProviderResolvedConfig;
import cn.rvport.backend.codexprovider.CodexProviderTestResult;
import cn.rvport.backend.codexprovider.CodexProviderWireApi;
import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.config.CredentialCryptoProperties;
import cn.rvport.backend.jooq.tables.records.AgentRuntimesRecord;
import cn.rvport.backend.jooq.tables.records.ModelProfileAgentProbeRunsRecord;
import cn.rvport.backend.jooq.tables.records.ModelProfilesRecord;
import cn.rvport.backend.security.CredentialCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jooq.JSONB;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ModelProfileServiceTests {

    @Mock
    private ModelProfileRepository repository;

    @Mock
    private CodexProviderProbeService probeService;

    @Mock
    private AgentRuntimeRepository agentRuntimeRepository;

    @Mock
    private AgentRuntimeService agentRuntimeService;

    @Test
    void createsProfileWithEncryptedApiKeyAndMaskedResponse() {
        UUID actorId = UUID.randomUUID();
        ArgumentCaptor<String> encryptedCaptor = ArgumentCaptor.forClass(String.class);
        when(repository.nameExists("Test Compare", null)).thenReturn(false);
        when(repository.create(any(), eq("Test Compare"), eq(true), eq("codex"), eq("OPENAI_COMPATIBLE"),
                eq("https://llm.example.com/v1"), eq("model-a"), eq("CHAT"), encryptedCaptor.capture(),
                eq(20), eq(1800), eq(actorId))).thenAnswer(invocation -> record(
                invocation.getArgument(0),
                "Test Compare",
                true,
                "codex",
                "OPENAI_COMPATIBLE",
                "https://llm.example.com/v1",
                "model-a",
                "CHAT",
                invocation.getArgument(8),
                20,
                1800
        ));

        ModelProfileResponse response = service().create(new ModelProfileRequest(
                " Test Compare ",
                true,
                "codex",
                "OPENAI_COMPATIBLE",
                "https://llm.example.com/v1/",
                "model-a",
                "chat",
                "SecretKey123",
                true,
                20,
                1800,
                Set.of("name", "enabled", "executablePath", "modelProviderType", "baseUrl", "model", "wireApi", "apiKey", "timeoutSeconds", "maxRuntimeSeconds")
        ), actorId);

        assertThat(response.apiKeyConfigured()).isTrue();
        assertThat(response.apiKeyMasked()).isEqualTo("[CONFIGURED]");
        assertThat(encryptedCaptor.getValue()).contains("cipherText").doesNotContain("SecretKey123");
        assertThat(response.baseUrl()).isEqualTo("https://llm.example.com/v1");
        assertThat(response.wireApi()).isEqualTo("CHAT");
    }

    @Test
    void rejectsDuplicateAndInvalidInput() {
        when(repository.nameExists("Test Compare", null)).thenReturn(true);

        assertThatThrownBy(() -> service().create(new ModelProfileRequest(
                "Test Compare", true, "codex", "OPENAI_COMPATIBLE", "https://llm.example.com/v1",
                "model-a", "RESPONSES", null, false, 30, 1800, Set.of("name")
        ), UUID.randomUUID())).isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> service().create(new ModelProfileRequest(
                "Other", true, "codex", "DIRECT", "https://llm.example.com/v1",
                "model-a", "RESPONSES", null, false, 30, 1800, Set.of("name")
        ), UUID.randomUUID())).isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> service().create(new ModelProfileRequest(
                "Other", true, "bad\npath", "OPENAI_COMPATIBLE", "file:///tmp/model",
                "model-a", "RESPONSES", null, false, 30, 1800, Set.of("name")
        ), UUID.randomUUID())).isInstanceOf(ApiException.class);
    }

    @Test
    void updatePreservesApiKeyWhenOmittedAndClearsWhenBlank() {
        UUID profileId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ModelProfilesRecord current = record(profileId, "Existing", true, "codex", "OPENAI_COMPATIBLE",
                "https://llm.example.com/v1", "model-a", "RESPONSES", "cipher", 30, 1800);
        when(repository.findById(profileId)).thenReturn(java.util.Optional.of(current));
        when(repository.update(eq(current), eq(null), eq(null), eq(null), eq(null), eq("https://llm.example.com/v1"),
                eq("model-b"), eq(null), eq(null), eq(false), eq(null), eq(null), eq(actorId)))
                .thenReturn(record(profileId, "Existing", true, "codex", "OPENAI_COMPATIBLE",
                        "https://llm.example.com/v1", "model-b", "RESPONSES", "cipher", 30, 1800));

        ModelProfileResponse response = service().update(profileId, new ModelProfileRequest(
                null, null, null, null, null, "model-b", null, null, false, null, null, Set.of("model")
        ), actorId);

        assertThat(response.model()).isEqualTo("model-b");
        verify(repository).update(eq(current), eq(null), eq(null), eq(null), eq(null), eq("https://llm.example.com/v1"),
                eq("model-b"), eq(null), eq(null), eq(false), eq(null), eq(null), eq(actorId));

        when(repository.update(eq(current), eq(null), eq(null), eq(null), eq(null), eq("https://llm.example.com/v1"),
                eq("model-a"), eq(null), eq(null), eq(true), eq(null), eq(null), eq(actorId)))
                .thenReturn(record(profileId, "Existing", true, "codex", "OPENAI_COMPATIBLE",
                        "https://llm.example.com/v1", "model-a", "RESPONSES", null, 30, 1800));

        ModelProfileResponse cleared = service().update(profileId, new ModelProfileRequest(
                null, null, null, null, null, null, null, "", true, null, null, Set.of("apiKey")
        ), actorId);

        assertThat(cleared.apiKeyConfigured()).isFalse();
    }

    @Test
    void testsProfileWithTemporaryOverrideAndPersistsSummary() {
        UUID profileId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID runtimeId = UUID.randomUUID();
        ModelProfilesRecord current = record(profileId, "Existing", true, "codex", "OPENAI_COMPATIBLE",
                "https://saved.example.com/v1", "saved-model", "RESPONSES", null, 30, 1800);
        AgentRuntimesRecord runtime = runtime(runtimeId, "CODEX", "codex");
        when(repository.findById(profileId)).thenReturn(java.util.Optional.of(current));
        when(agentRuntimeRepository.findByType(cn.rvport.backend.codexprovider.AgentType.CODEX)).thenReturn(java.util.Optional.of(runtime));
        ArgumentCaptor<CodexProviderResolvedConfig> configCaptor = ArgumentCaptor.forClass(CodexProviderResolvedConfig.class);
        var dispatcher = org.mockito.Mockito.mock(CodexProbeDispatcher.class);
        when(dispatcher.execute(eq(null), eq(profileId), eq(runtimeId), configCaptor.capture(), eq(10)))
                .thenReturn(CodexProviderTestResult.success("ok", Instant.parse("2026-08-13T00:00:00Z")));
        var service = service();
        service.setCodexProbeDispatcher(dispatcher);

        CodexProviderTestResult result = service.test(profileId, new ModelProfileRequest(
                null, true, null, null, "https://override.example.com/v1", "override-model",
                "CHAT", "TemporaryKey123", true, 10, 60, Set.of("enabled", "baseUrl", "model", "wireApi", "apiKey", "timeoutSeconds", "maxRuntimeSeconds")
        ), actorId);

        assertThat(result.success()).isTrue();
        assertThat(configCaptor.getValue().apiKey()).isEqualTo("TemporaryKey123");
        assertThat(configCaptor.getValue().wireApi()).isEqualTo(CodexProviderWireApi.CHAT);
        assertThat(result.agentType()).isEqualTo("CODEX");
        assertThat(result.agentCompatible()).isTrue();
        verify(repository).updateTestResult(eq(profileId), eq("SUCCESS"), eq("ok"), any(),
                eq("CODEX"), eq(true), eq(true), any(JSONB.class), eq(actorId));
        verify(agentRuntimeRepository).updateProbeResult(eq(runtimeId), eq("SUCCESS"), eq("ok"), any(JSONB.class), any());
    }

    @Test
    void testsConnectivityWithoutAgentRuntimeAndPersistsSummary() {
        UUID profileId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        ModelProfilesRecord current = record(profileId, "Connectivity", true, "codex", "OPENAI_COMPATIBLE",
                "https://saved.example.com/v1", "saved-model", "RESPONSES", null, 30, 1800);
        when(repository.findById(profileId)).thenReturn(java.util.Optional.of(current));
        when(probeService.testConnectivity(any())).thenReturn(new ModelProfileConnectivityTestResult(
                true,
                "SUCCESS",
                "ok",
                Instant.parse("2026-08-28T00:00:00Z"),
                12,
                java.util.Map.of("wireApi", "CHAT")
        ));

        ModelProfileConnectivityTestResult result = service().testConnectivity(profileId, new ModelProfileRequest(
                null, true, null, null, "https://override.example.com/v1", "override-model",
                "CHAT", "TemporaryKey123", true, 10, 60,
                Set.of("enabled", "baseUrl", "model", "wireApi", "apiKey", "timeoutSeconds", "maxRuntimeSeconds")
        ), actorId);

        assertThat(result.success()).isTrue();
        verify(probeService).testConnectivity(any(CodexProviderResolvedConfig.class));
        verify(agentRuntimeRepository, never()).findByType(any());
        verify(agentRuntimeService, never()).requireEnabled(any());
        verify(repository).updateConnectivityResult(eq(profileId), eq("SUCCESS"), eq("ok"), any(JSONB.class), any(), eq(12L), eq(actorId));
    }

    @Test
    void startsAgentProbeRunAndPersistsPerRuntimeResult() {
        UUID profileId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID runtimeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ModelProfilesRecord current = record(profileId, "Agent Probe", true, "codex", "OPENAI_COMPATIBLE",
                "https://saved.example.com/v1", "saved-model", "RESPONSES", encrypted("SecretKey123"), 30, 1800);
        AgentRuntimesRecord runtime = runtime(runtimeId, "CODEX", "codex");
        ModelProfileAgentProbeRunsRecord run = probeRun(runId, profileId, "PENDING");
        when(repository.findById(profileId)).thenReturn(java.util.Optional.of(current));
        when(agentRuntimeService.requireEnabled(runtimeId)).thenReturn(runtime);
        when(repository.createProbeRun(eq(runId), eq(profileId), any(JSONB.class), any(JSONB.class), eq(actorId)))
                .thenReturn(run);
        when(probeService.test(any(CodexProviderResolvedConfig.class), eq(runtime)))
                .thenReturn(CodexProviderTestResult.success("ok", Instant.parse("2026-08-28T00:00:00Z")));

        ModelProfileService service = service(new DirectExecutorService(), runId);
        ModelProfileAgentProbeRunResponse response = service.startAgentTests(
                profileId,
                new ModelProfileAgentProbeRequest(List.of(runtimeId)),
                actorId
        );

        assertThat(response.id()).isEqualTo(runId);
        assertThat(response.status()).isEqualTo("PENDING");
        verify(repository).updateProbeRunStatus(eq(runId), eq(AgentProbeRunStatus.RUNNING), any(), any(), eq(null));
        verify(repository).createProbeResult(any(), eq(runId), eq(profileId), eq(runtimeId), eq("CODEX"),
                eq("SUCCESS"), eq(true), eq(true), any(JSONB.class), any(JSONB.class),
                eq(null), eq("ok"), any(), any(), any());
        verify(repository).updateProbeRunStatus(eq(runId), eq(AgentProbeRunStatus.SUCCESS), any(), eq(null), any());
        var testedAt = OffsetDateTime.parse("2026-08-28T00:00:00Z");
        verify(agentRuntimeRepository).updateProbeResult(eq(runtimeId), eq("SUCCESS"), eq("ok"), any(), eq(testedAt));
        verify(repository).updateTestResult(eq(profileId), eq("SUCCESS"), eq("ok"), eq(testedAt), eq("CODEX"),
                eq(true), eq(true), any(), eq(actorId));
        verify(repository, never()).findAssignment(any());
    }

    @Test
    void agentProbeRunKeepsOtherRuntimeResultWhenOneRuntimeFails() {
        UUID profileId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID firstRuntimeId = UUID.randomUUID();
        UUID secondRuntimeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ModelProfilesRecord current = record(profileId, "Partial Probe", true, "codex", "OPENAI_COMPATIBLE",
                "https://saved.example.com/v1", "saved-model", "RESPONSES", encrypted("SecretKey123"), 30, 1800);
        AgentRuntimesRecord firstRuntime = runtime(firstRuntimeId, "CODEX", "codex");
        AgentRuntimesRecord secondRuntime = runtime(secondRuntimeId, "OPENCODE", "opencode");
        when(repository.findById(profileId)).thenReturn(java.util.Optional.of(current));
        when(agentRuntimeService.requireEnabled(firstRuntimeId)).thenReturn(firstRuntime);
        when(agentRuntimeService.requireEnabled(secondRuntimeId)).thenReturn(secondRuntime);
        when(repository.createProbeRun(eq(runId), eq(profileId), any(JSONB.class), any(JSONB.class), eq(actorId)))
                .thenReturn(probeRun(runId, profileId, "PENDING"));
        when(probeService.test(any(CodexProviderResolvedConfig.class), eq(firstRuntime)))
                .thenReturn(CodexProviderTestResult.success("ok", Instant.parse("2026-08-28T00:00:00Z")));
        when(probeService.test(any(CodexProviderResolvedConfig.class), eq(secondRuntime)))
                .thenThrow(new IllegalStateException("runtime unavailable"));

        service(new DirectExecutorService(), runId).startAgentTests(
                profileId,
                new ModelProfileAgentProbeRequest(List.of(firstRuntimeId, secondRuntimeId)),
                actorId
        );

        verify(repository, times(2)).createProbeResult(any(), eq(runId), eq(profileId), any(), any(), any(),
                any(), any(), any(JSONB.class), any(JSONB.class), any(), any(), any(), any(), any());
        verify(repository).updateProbeRunStatus(eq(runId), eq(AgentProbeRunStatus.PARTIAL_SUCCESS),
                eq("模型 Agent 兼容性测试完成，成功 1 个，失败 1 个"), eq(null), any());
        verify(repository, never()).findAssignment(any());
    }

    @Test
    void rejectsAgentProbeRunWithEmptyOrDuplicateRuntimes() {
        UUID profileId = UUID.randomUUID();
        when(repository.findById(profileId)).thenReturn(java.util.Optional.of(record(profileId, "Agent Probe", true,
                "codex", "OPENAI_COMPATIBLE", "https://saved.example.com/v1", "saved-model", "RESPONSES", null, 30, 1800)));

        assertThatThrownBy(() -> service().startAgentTests(profileId, new ModelProfileAgentProbeRequest(List.of()), UUID.randomUUID()))
                .isInstanceOf(ApiException.class);

        UUID runtimeId = UUID.randomUUID();
        assertThatThrownBy(() -> service().startAgentTests(profileId, new ModelProfileAgentProbeRequest(List.of(runtimeId, runtimeId)), UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void stageAssignmentRequiresKnownStageAndEnabledProfile() {
        UUID profileId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID runtimeId = UUID.randomUUID();
        AgentRuntimesRecord runtime = runtime(runtimeId, "CODEX", "codex");
        when(repository.findEnabledById(profileId)).thenReturn(java.util.Optional.of(record(profileId, "Enabled", true,
                "codex", "OPENAI_COMPATIBLE", "https://llm.example.com/v1", "model-a", "RESPONSES", "cipher", 30, 1800)));
        when(agentRuntimeService.requireEnabled(runtimeId)).thenReturn(runtime);
        when(repository.listAssignments()).thenReturn(List.of(new ModelProfileRepository.AssignmentView(
                "TEST_LOG_COMPARE", profileId, record(profileId, "Enabled", true,
                "codex", "OPENAI_COMPATIBLE", "https://llm.example.com/v1", "model-a", "RESPONSES", "cipher", 30, 1800),
                runtimeId,
                runtime,
                OffsetDateTime.now())));

        List<ModelProfileAssignmentResponse> responses = service().saveAssignments(
                new ModelStageAssignmentRequest(List.of(new ModelStageAssignmentRequest.Entry("TEST_LOG_COMPARE", profileId, runtimeId))),
                actorId
        );

        assertThat(responses).hasSize(2);
        ModelProfileAssignmentResponse testCompare = responses.stream()
                .filter(item -> "TEST_LOG_COMPARE".equals(item.stage()))
                .findFirst()
                .orElseThrow();
        assertThat(testCompare.modelProfileId()).isEqualTo(profileId);
        assertThat(testCompare.agentRuntimeId()).isEqualTo(runtimeId);
        assertThat(responses).anySatisfy(item -> {
            assertThat(item.stage()).isEqualTo("KB_AI_EXTENSION");
            assertThat(item.modelProfileId()).isNull();
            assertThat(item.agentRuntimeId()).isNull();
        });
        verify(repository).upsertAssignment(ModelProfileStage.TEST_LOG_COMPARE, profileId, runtimeId, actorId);

        assertThatThrownBy(() -> service().saveAssignments(
                new ModelStageAssignmentRequest(List.of(new ModelStageAssignmentRequest.Entry("GENERAL_ASSISTANT", profileId, runtimeId))),
                actorId
        )).isInstanceOf(ApiException.class);
    }

    @Test
    void resolveForStageRejectsMissingAssignmentAndDisabledProfile() {
        when(repository.findAssignment(ModelProfileStage.TEST_LOG_COMPARE)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service().resolveForStage(ModelProfileStage.TEST_LOG_COMPARE))
                .isInstanceOf(ApiException.class)
                .hasMessage("测试结果比较阶段未绑定模型 profile");

        UUID profileId = UUID.randomUUID();
        UUID runtimeId = UUID.randomUUID();
        AgentRuntimesRecord runtime = runtime(runtimeId, "CODEX", "codex");
        when(repository.findAssignment(ModelProfileStage.TEST_LOG_COMPARE)).thenReturn(java.util.Optional.of(
                new ModelProfileRepository.AssignmentView("TEST_LOG_COMPARE", profileId,
                        record(profileId, "Disabled", false, "codex", "OPENAI_COMPATIBLE",
                                "https://llm.example.com/v1", "model-a", "RESPONSES", "cipher", 30, 1800),
                        runtimeId,
                        runtime,
                        OffsetDateTime.now())
        ));

        assertThatThrownBy(() -> service().resolveForStage(ModelProfileStage.TEST_LOG_COMPARE))
                .isInstanceOf(ApiException.class)
                .hasMessage("测试结果比较阶段绑定的模型 profile 不可用");
    }

    @Test
    void deleteRejectsAssignedProfile() {
        UUID profileId = UUID.randomUUID();
        when(repository.findById(profileId)).thenReturn(java.util.Optional.of(record(profileId, "Assigned", true,
                "codex", "OPENAI_COMPATIBLE", "https://llm.example.com/v1", "model-a", "RESPONSES", null, 30, 1800)));
        when(repository.isAssigned(profileId)).thenReturn(true);

        assertThatThrownBy(() -> service().delete(profileId)).isInstanceOf(ApiException.class);
        verify(repository, never()).delete(profileId);
    }

    @Test
    void createAppliesDefaultsAndRejectsEveryNormalizationBoundary() {
        UUID actorId = UUID.randomUUID();
        when(repository.create(any(), eq("Defaults"), eq(true), eq("codex"), eq("OPENAI_COMPATIBLE"),
                eq(null), eq(null), eq("RESPONSES"), eq(null), eq(30), eq(1800), eq(actorId)))
                .thenAnswer(invocation -> record(invocation.getArgument(0), "Defaults", true, "codex",
                        "OPENAI_COMPATIBLE", null, null, "RESPONSES", null, 30, 1800));
        ModelProfileResponse defaults = service().create(new ModelProfileRequest(
                "Defaults", null, null, null, " ", " ", null, null, false,
                null, null, Set.of()), actorId);
        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.executablePath()).isEqualTo("codex");

        List<ModelProfileRequest> invalid = List.of(
                request(" ", "codex", "OPENAI_COMPATIBLE", "https://ok.example/v1", "RESPONSES", 30, 1800),
                request("x".repeat(129), "codex", "OPENAI_COMPATIBLE", "https://ok.example/v1", "RESPONSES", 30, 1800),
                request("name", "bad\rpath", "OPENAI_COMPATIBLE", "https://ok.example/v1", "RESPONSES", 30, 1800),
                request("name", "codex", " ", "https://ok.example/v1", "RESPONSES", 30, 1800),
                request("name", "codex", "OPENAI_COMPATIBLE", "ftp://host/model", "RESPONSES", 30, 1800),
                request("name", "codex", "OPENAI_COMPATIBLE", "http:///missing-host", "RESPONSES", 30, 1800),
                request("name", "codex", "OPENAI_COMPATIBLE", "http://bad host", "RESPONSES", 30, 1800),
                request("name", "codex", "OPENAI_COMPATIBLE", "https://ok.example/v1", " ", 30, 1800),
                request("name", "codex", "OPENAI_COMPATIBLE", "https://ok.example/v1", "legacy", 30, 1800),
                request("name", "codex", "OPENAI_COMPATIBLE", "https://ok.example/v1", "RESPONSES", 0, 1800),
                request("name", "codex", "OPENAI_COMPATIBLE", "https://ok.example/v1", "RESPONSES", 601, 1800),
                request("name", "codex", "OPENAI_COMPATIBLE", "https://ok.example/v1", "RESPONSES", 30, 9),
                request("name", "codex", "OPENAI_COMPATIBLE", "https://ok.example/v1", "RESPONSES", 30, 86_401)
        );
        assertThatThrownBy(() -> service().create(null, actorId)).isInstanceOf(ApiException.class);
        invalid.forEach(input -> assertThatThrownBy(() -> service().create(input, actorId))
                .as(input.toString()).isInstanceOf(ApiException.class));
    }

    @Test
    void listValidatesFiltersAndMapsProbeState() {
        UUID id = UUID.randomUUID();
        ModelProfilesRecord profile = record(id, "Listed", true, "codex", "OPENAI_COMPATIBLE",
                "https://llm.example/v1", "model", "RESPONSES", null, 30, 1800);
        when(repository.list(any())).thenReturn(List.of(profile));
        when(repository.count(any())).thenReturn(1L);
        when(repository.listLatestProbeResults(id)).thenReturn(List.of());
        when(repository.findActiveProbeRun(id)).thenReturn(java.util.Optional.of(probeRun(UUID.randomUUID(), id, "RUNNING")));

        var page = service().list(new ModelProfileListRequest(null, null, true, " OPENAI_COMPATIBLE ",
                " SUCCESS ", "key", "updatedAt", "desc"));
        assertThat(page.items()).singleElement().satisfies(item -> assertThat(item.activeAgentProbeRun()).isNotNull());
        assertThat(page.page()).isEqualTo(1);

        assertThatThrownBy(() -> service().list(new ModelProfileListRequest(1, 20, null, "DIRECT", null,
                null, null, null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service().list(new ModelProfileListRequest(1, 20, null, null, "RUNNING",
                null, null, null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service().list(new ModelProfileListRequest(1, 20, null, null, "FAILED",
                null, "unknown", null))).isInstanceOf(ApiException.class);
        service().list(new ModelProfileListRequest(1, 20, null, " ", " ", null, " ", null));
    }

    @Test
    void updateCoversAllFieldsDuplicateAndNullRequest() {
        UUID id = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ModelProfilesRecord current = record(id, "Old", true, "codex", "OPENAI_COMPATIBLE",
                "https://old.example/v1", "old", "RESPONSES", null, 30, 1800);
        when(repository.findById(id)).thenReturn(java.util.Optional.of(current));
        when(repository.update(eq(current), eq("New"), eq(false), eq("opencode"), eq("OPENAI_COMPATIBLE"),
                eq("https://new.example/v1"), eq(null), eq("CHAT"), any(), eq(true), eq(1), eq(10), eq(actor)))
                .thenReturn(record(id, "New", false, "opencode", "OPENAI_COMPATIBLE",
                        "https://new.example/v1", null, "CHAT", "encrypted", 1, 10));
        ModelProfileRequest update = new ModelProfileRequest(" New ", false, " opencode ", "OPENAI_COMPATIBLE",
                "https://new.example/v1/", " ", "chat", "new-key", true, 1, 10,
                Set.of("name", "enabled", "executablePath", "modelProviderType", "baseUrl", "model", "wireApi",
                        "apiKey", "timeoutSeconds", "maxRuntimeSeconds"));
        assertThat(service().update(id, update, actor).name()).isEqualTo("New");

        when(repository.nameExists("New", id)).thenReturn(true);
        assertThatThrownBy(() -> service().update(id, update, actor)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service().update(id, null, actor)).isInstanceOf(ApiException.class);
    }

    @Test
    void supportsDeleteGetAndRuntimeOverrideWhileRejectingMissingResources() {
        UUID id = UUID.randomUUID();
        UUID runtimeId = UUID.randomUUID();
        ModelProfilesRecord profile = record(id, "Profile", true, "codex", "OPENAI_COMPATIBLE",
                "https://llm.example/v1", "model", "RESPONSES", encrypted("saved-key"), 30, 1800);
        AgentRuntimesRecord runtime = runtime(runtimeId, "OPENCODE", "opencode");
        when(repository.findById(id)).thenReturn(java.util.Optional.of(profile));
        when(repository.listLatestProbeResults(id)).thenReturn(List.of());
        when(repository.findActiveProbeRun(id)).thenReturn(java.util.Optional.empty());
        when(agentRuntimeService.requireEnabled(runtimeId)).thenReturn(runtime);
        when(probeService.test(any(), eq(runtime))).thenReturn(CodexProviderTestResult.success("ok", Instant.now()));

        assertThat(service().get(id).id()).isEqualTo(id);
        service().delete(id);
        verify(repository).delete(id);
        service().test(id, new ModelProfileRequest(null, null, null, null, null, null, null,
                null, false, runtimeId, null, null, Set.of()), UUID.randomUUID());
        verify(agentRuntimeRepository, never()).findByType(any());

        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service().get(missing)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsInvalidAgentRuntimeSelectionsAndAssignments() {
        UUID profileId = UUID.randomUUID();
        when(repository.findById(profileId)).thenReturn(java.util.Optional.of(record(profileId, "Profile", true,
                "codex", "OPENAI_COMPATIBLE", "https://llm.example/v1", "model", "RESPONSES", null, 30, 1800)));
        assertThatThrownBy(() -> service().startAgentTests(profileId, null, UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service().startAgentTests(profileId,
                new ModelProfileAgentProbeRequest(Arrays.asList((UUID) null)), UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            UUID runtimeId = UUID.randomUUID();
            tooMany.add(runtimeId);
            when(agentRuntimeService.requireEnabled(runtimeId)).thenReturn(runtime(runtimeId, "CODEX", "codex"));
        }
        assertThatThrownBy(() -> service().startAgentTests(profileId,
                new ModelProfileAgentProbeRequest(tooMany), UUID.randomUUID())).isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> service().saveAssignments(null, UUID.randomUUID())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service().saveAssignments(new ModelStageAssignmentRequest(null), UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
        UUID runtimeId = UUID.randomUUID();
        ModelStageAssignmentRequest.Entry entry = new ModelStageAssignmentRequest.Entry("KB_AI_EXTENSION", profileId, runtimeId);
        assertThatThrownBy(() -> service().saveAssignments(new ModelStageAssignmentRequest(List.of(entry, entry)), UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
        when(repository.findEnabledById(profileId)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service().saveAssignments(new ModelStageAssignmentRequest(List.of(entry)), UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void resolvesBothStagesAndRejectsMissingOrDisabledAgent() {
        UUID profileId = UUID.randomUUID();
        UUID runtimeId = UUID.randomUUID();
        ModelProfilesRecord profile = record(profileId, "Profile", true, "codex", "OPENAI_COMPATIBLE",
                "https://llm.example/v1", "model", "CHAT", encrypted("secret"), 30, 1800);
        AgentRuntimesRecord runtime = runtime(runtimeId, "OPENCODE", "opencode");
        var assignment = new ModelProfileRepository.AssignmentView("KB_AI_EXTENSION", profileId, profile,
                runtimeId, runtime, OffsetDateTime.now());
        when(repository.findAssignment(ModelProfileStage.KB_AI_EXTENSION)).thenReturn(java.util.Optional.of(assignment));
        assertThat(service().resolveForStage(ModelProfileStage.KB_AI_EXTENSION).apiKey()).isEqualTo("secret");
        assertThat(service().resolveAgentForStage(ModelProfileStage.KB_AI_EXTENSION).runtime()).isEqualTo(runtime);

        var noAgent = new ModelProfileRepository.AssignmentView("KB_AI_EXTENSION", profileId, profile,
                runtimeId, null, OffsetDateTime.now());
        when(repository.findAssignment(ModelProfileStage.KB_AI_EXTENSION)).thenReturn(java.util.Optional.of(noAgent));
        assertThatThrownBy(() -> service().resolveAgentForStage(ModelProfileStage.KB_AI_EXTENSION))
                .isInstanceOf(ApiException.class).hasMessageContaining("Agent runtime");
        runtime.setEnabled(false);
        when(repository.findAssignment(ModelProfileStage.KB_AI_EXTENSION)).thenReturn(java.util.Optional.of(assignment));
        assertThatThrownBy(() -> service().resolveAgentForStage(ModelProfileStage.KB_AI_EXTENSION))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void failedProbeRunWithBlankFailureIsSanitizedAndAggregated() {
        UUID profileId = UUID.randomUUID();
        UUID runtimeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        AgentRuntimesRecord runtime = runtime(runtimeId, "CODEX", "codex");
        when(repository.findById(profileId)).thenReturn(java.util.Optional.of(record(profileId, "Profile", true,
                "codex", "OPENAI_COMPATIBLE", "https://llm.example/v1", "model", "RESPONSES", null, 30, 1800)));
        when(agentRuntimeService.requireEnabled(runtimeId)).thenReturn(runtime);
        when(repository.createProbeRun(any(), eq(profileId), any(), any(), any())).thenReturn(probeRun(runId, profileId, "PENDING"));
        when(probeService.test(any(), eq(runtime))).thenThrow(new IllegalStateException(" "));

        service(new DirectExecutorService(), runId).startAgentTests(profileId,
                new ModelProfileAgentProbeRequest(List.of(runtimeId)), UUID.randomUUID());
        verify(repository).updateProbeRunStatus(eq(runId), eq(AgentProbeRunStatus.FAILED),
                eq("模型 Agent 兼容性测试完成，成功 0 个，失败 1 个"), eq(null), any());
        verify(repository).createProbeResult(any(), eq(runId), eq(profileId), eq(runtimeId), eq("CODEX"),
                eq("FAILED"), eq(false), eq(false), any(), any(), eq("AGENT_PROBE_FAILED"),
                eq("模型 Agent 兼容性测试失败"), any(), any(), any());
    }

    @Test
    void coversDisabledCreateNullOverrideFailedProbeAndNullAssignmentMembers() {
        UUID actor = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID runtimeId = UUID.randomUUID();
        ModelProfilesRecord profile = record(profileId, "Profile", true, "codex", "OPENAI_COMPATIBLE",
                "https://llm.example/v1", "model", "RESPONSES", null, 30, 1800);
        AgentRuntimesRecord runtime = runtime(runtimeId, "CODEX", "codex");
        when(repository.create(any(), eq("Disabled"), eq(false), eq("codex"), eq("OPENAI_COMPATIBLE"),
                eq(null), eq("model"), eq("RESPONSES"), eq(null), eq(30), eq(1800), eq(actor)))
                .thenAnswer(invocation -> record(invocation.getArgument(0), "Disabled", false, "codex",
                        "OPENAI_COMPATIBLE", null, "model", "RESPONSES", null, 30, 1800));
        assertThat(service().create(new ModelProfileRequest("Disabled", false, null, null, null, "model", null,
                null, false, null, null, Set.of()), actor).enabled()).isFalse();

        when(repository.findById(profileId)).thenReturn(java.util.Optional.of(profile));
        when(agentRuntimeRepository.findByType(cn.rvport.backend.codexprovider.AgentType.CODEX))
                .thenReturn(java.util.Optional.of(runtime));
        var failedCheck = cn.rvport.backend.codexprovider.CodexProviderProbeCheckResult.failure(
                cn.rvport.backend.codexprovider.CodexProviderProbeName.AGENT_TEXT, "BROKEN", "bad", 1);
        when(probeService.test(any(), eq(runtime))).thenReturn(CodexProviderTestResult.of(false, "bad", Instant.now(),
                cn.rvport.backend.codexprovider.AgentType.CODEX, runtimeId, false, false, List.of(failedCheck)));
        assertThat(service().test(profileId, null, actor).success()).isFalse();

        when(repository.listAssignments()).thenReturn(List.of(new ModelProfileRepository.AssignmentView(
                "TEST_LOG_COMPARE", profileId, null, runtimeId, null, OffsetDateTime.now())));
        assertThat(service().listAssignments()).anySatisfy(item -> {
            if (item.stage().equals("TEST_LOG_COMPARE")) {
                assertThat(item.modelProfile()).isNull();
                assertThat(item.agentRuntime()).isNull();
            }
        });

        assertThatThrownBy(() -> service().saveAssignments(new ModelStageAssignmentRequest(List.of()), actor))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service().saveAssignments(new ModelStageAssignmentRequest(List.of(
                new ModelStageAssignmentRequest.Entry(null, profileId, runtimeId))), actor))
                .isInstanceOf(ApiException.class);
    }

    private ModelProfileRequest request(String name, String executable, String provider, String baseUrl,
            String wireApi, Integer timeout, Integer maxRuntime) {
        return new ModelProfileRequest(name, true, executable, provider, baseUrl, "model", wireApi,
                null, false, timeout, maxRuntime, Set.of());
    }

    private ModelProfileService service() {
        return new ModelProfileService(repository, cipher(), new ObjectMapper(), probeService, agentRuntimeRepository, agentRuntimeService);
    }

    private ModelProfileService service(DirectExecutorService executor, UUID fixedRunId) {
        return new ModelProfileService(repository, cipher(), new ObjectMapper(), probeService, agentRuntimeRepository,
                agentRuntimeService, executor) {
            @Override
            UUID newRunId() {
                return fixedRunId;
            }
        };
    }

    private CredentialCipher cipher() {
        return new CredentialCipher(new CredentialCryptoProperties("MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=", "local-v1"));
    }

    private ModelProfilesRecord record(UUID id, String name, boolean enabled, String executablePath, String providerType,
            String baseUrl, String model, String wireApi, String encryptedApiKey, int timeoutSeconds, int maxRuntimeSeconds) {
        ModelProfilesRecord record = new ModelProfilesRecord();
        record.setId(id);
        record.setName(name);
        record.setEnabled(enabled);
        record.setExecutablePath(executablePath);
        record.setModelProviderType(providerType);
        record.setBaseUrl(baseUrl);
        record.setModel(model);
        record.setWireApi(wireApi);
        record.setApiKeyEncrypted(encryptedApiKey);
        record.setTimeoutSeconds(timeoutSeconds);
        record.setMaxRuntimeSeconds(maxRuntimeSeconds);
        record.setCreatedAt(OffsetDateTime.now());
        record.setUpdatedAt(OffsetDateTime.now());
        return record;
    }

    private AgentRuntimesRecord runtime(UUID id, String agentType, String executablePath) {
        AgentRuntimesRecord record = new AgentRuntimesRecord();
        record.setId(id);
        record.setAgentType(agentType);
        record.setName(agentType);
        record.setEnabled(true);
        record.setExecutablePath(executablePath);
        record.setDefaultArgs(JSONB.valueOf("[]"));
        record.setConfig(JSONB.valueOf("{}"));
        record.setLastProbeDetails(JSONB.valueOf("{}"));
        record.setCreatedAt(OffsetDateTime.now());
        record.setUpdatedAt(OffsetDateTime.now());
        return record;
    }

    private ModelProfileAgentProbeRunsRecord probeRun(UUID runId, UUID profileId, String status) {
        ModelProfileAgentProbeRunsRecord record = new ModelProfileAgentProbeRunsRecord();
        record.setId(runId);
        record.setModelProfileId(profileId);
        record.setStatus(status);
        record.setSelectedAgentRuntimeIds(JSONB.valueOf("[]"));
        record.setSelectedAgentRuntimes(JSONB.valueOf("[]"));
        record.setCreatedAt(OffsetDateTime.now());
        record.setUpdatedAt(OffsetDateTime.now());
        return record;
    }

    private String encrypted(String value) {
        try {
            return new ObjectMapper().writeValueAsString(cipher().encrypt(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
