package cn.rvport.backend.modelprofile;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.rvport.backend.agentruntime.AgentRuntimeService;
import cn.rvport.backend.codexprovider.*;
import cn.rvport.backend.jooq.tables.records.*;
import cn.rvport.backend.security.CredentialCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class CodexProbeDispatcherTests {
    final UUID id=UUID.randomUUID(), profileId=UUID.randomUUID(), runtimeId=UUID.randomUUID();
    final CodexProbeJobRepository jobs=mock(CodexProbeJobRepository.class);
    final ModelProfileRepository profiles=mock(ModelProfileRepository.class);
    final AgentRuntimeService runtimes=mock(AgentRuntimeService.class);
    final CredentialCipher cipher=mock(CredentialCipher.class);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final CodexProviderProbeService probes=mock(CodexProviderProbeService.class);
    final RabbitTemplate rabbit=mock(RabbitTemplate.class);
    final CodexProbeDispatcher dispatcher=new CodexProbeDispatcher(jobs,profiles,runtimes,cipher,json,probes,rabbit);
    final AtomicReference<CodexProbeJobRepository.Job> owned=new AtomicReference<>();

    @AfterEach void clearInterrupt() { Thread.interrupted(); }

    CodexProbeJobRepository.Job job(String status, String payload, String result, OffsetDateTime deadline) {
        return new CodexProbeJobRepository.Job(id,null,profileId,runtimeId,payload,status,null,deadline,result,null);
    }
    CodexProviderResolvedConfig config() {
        return new CodexProviderResolvedConfig(true,"codex",CodexProviderType.OPENAI_COMPATIBLE,
                "http://model/v1","dynamic",CodexProviderWireApi.CHAT,"private-key",1,60);
    }
    void create(CodexProbeJobRepository.Job job) {
        when(jobs.create(any(),any(),eq(profileId),eq(runtimeId),any(),any())).thenReturn(job);
        when(jobs.find(id)).thenReturn(Optional.of(job));
    }

    @Test void boundedWaitReturnsStoredResultAndOnlyPublishesId() throws Exception {
        var result=CodexProbeDispatcher.failure(runtimeId,"SAMPLE");
        create(job("DONE",null,json.writeValueAsString(result),OffsetDateTime.now().plusSeconds(1)));
        assertThat(dispatcher.execute(null,profileId,runtimeId,null,1)).isEqualTo(result);
        verify(rabbit).convertAndSend("",CodexProbeDispatcher.QUEUE,id.toString());
        verify(jobs).cancel(id);
        verifyNoInteractions(probes);
    }

    @Test void overrideIsEncryptedWithoutChangingProfile() throws Exception {
        var encrypted=new CredentialCipher.EncryptedSecret("a","b","c","d");
        when(cipher.encrypt(any())).thenReturn(encrypted);
        create(job("CANCELLED",null,null,OffsetDateTime.now().plusSeconds(1)));
        assertThat(dispatcher.execute(null,profileId,runtimeId,config(),1).success()).isFalse();
        verify(jobs).create(any(),isNull(),eq(profileId),eq(runtimeId),
                eq(json.writeValueAsString(encrypted)),any());
        verify(cipher).encrypt(argThat(bytes -> new String(bytes,java.nio.charset.StandardCharsets.UTF_8).contains("private-key")));
        verifyNoInteractions(profiles);
    }

    @Test void deadlineMissingRecordAndCorruptResultConverge() {
        create(job("QUEUED",null,null,OffsetDateTime.now().minusSeconds(1)));
        assertThat(dispatcher.execute(null,profileId,runtimeId,null,1).details()).containsEntry("errorCode","AGENT_PROBE_WORKER_TIMEOUT");
        create(job("DONE",null,"bad-json",OffsetDateTime.now().plusSeconds(1)));
        assertThat(dispatcher.execute(null,profileId,runtimeId,null,1).details()).containsEntry("errorCode","AGENT_PROBE_DISPATCH_FAILED");
        when(jobs.create(any(),any(),any(),any(),any(),any())).thenThrow(new IllegalStateException("private-key"));
        assertThat(dispatcher.execute(null,profileId,runtimeId,null,1).message()).doesNotContain("private-key");
    }

    @Test void interruptionCancelsWaitingJobAndPreservesInterruptFlag() {
        create(job("RUNNING",null,null,OffsetDateTime.now().plusSeconds(2)));
        Thread.currentThread().interrupt();
        assertThat(dispatcher.execute(null,profileId,runtimeId,null,1).details()).containsEntry("errorCode","AGENT_PROBE_INTERRUPTED");
        assertThat(Thread.interrupted()).isTrue();
        verify(jobs).cancel(id);
    }

    @Test void failedPublishUsesBoundedPersistentRecovery() {
        doThrow(new AmqpException("unavailable")).when(rabbit).convertAndSend("",CodexProbeDispatcher.QUEUE,id.toString());
        when(jobs.publications()).thenReturn(List.of(id));
        dispatcher.maintain();
        verify(jobs).expire();
        verify(rabbit).convertAndSend("",CodexProbeDispatcher.QUEUE,id.toString());
    }

    void prepareWorker(String payload) throws Exception {
        when(jobs.claim(eq(id),any())).thenAnswer(call -> {
            var value=new CodexProbeJobRepository.Job(id,null,profileId,runtimeId,payload,"RUNNING",
                    call.getArgument(1),OffsetDateTime.now().plusSeconds(3),null,null);
            owned.set(value);
            return Optional.of(value);
        });
        when(jobs.find(id)).thenAnswer(call -> Optional.of(owned.get()));
        var profile=new ModelProfilesRecord();
        profile.setModelProviderType("OPENAI_COMPATIBLE"); profile.setWireApi("CHAT"); profile.setModel("dynamic");
        profile.setBaseUrl("http://model/v1"); profile.setTimeoutSeconds(1); profile.setMaxRuntimeSeconds(60);
        when(profiles.findEnabledById(profileId)).thenReturn(Optional.of(profile));
        var runtime=new AgentRuntimesRecord(); runtime.setId(runtimeId); runtime.setAgentType("CODEX"); runtime.setExecutablePath("codex");
        when(runtimes.requireEnabled(runtimeId)).thenReturn(runtime);
        when(probes.test(any(),eq(runtime))).thenReturn(CodexProbeDispatcher.failure(runtimeId,"EXPECTED"));
    }

    @Test void workerFreezesCurrentProfileAndFinishesWithOwner() throws Exception {
        prepareWorker(null);
        dispatcher.process(id);
        verify(probes).test(argThat(config -> config.model().equals("dynamic") && config.apiKey().isEmpty()),any());
        verify(jobs).finish(eq(id),eq(owned.get().owner()),contains("EXPECTED"));
        verify(jobs).expire();
    }

    @Test void workerDecryptsOverridesAndSavedKey() throws Exception {
        var encrypted=new CredentialCipher.EncryptedSecret("a","b","c","d");
        prepareWorker(json.writeValueAsString(encrypted));
        when(cipher.decrypt(encrypted)).thenReturn(json.writeValueAsBytes(config()));
        dispatcher.process(id);
        verify(probes).test(eq(config()),any());
        reset(probes);
        prepareWorker(null);
        profiles.findEnabledById(profileId).orElseThrow().setApiKeyEncrypted(json.writeValueAsString(encrypted));
        when(cipher.decrypt(encrypted)).thenReturn("updated-key".getBytes());
        dispatcher.process(id);
        verify(probes).test(argThat(config -> config.apiKey().equals("updated-key")),any());
    }

    @Test void duplicateDisabledAndWrongRuntimeDoNotExecuteModel() throws Exception {
        when(jobs.claim(eq(id),any())).thenReturn(Optional.empty());
        dispatcher.process(id);
        verifyNoInteractions(probes);
        prepareWorker(null);
        when(profiles.findEnabledById(profileId)).thenReturn(Optional.empty());
        dispatcher.process(id);
        verify(jobs).finish(eq(id),any(),contains("AGENT_PROBE_WORKER_FAILED"));
        prepareWorker(null);
        runtimes.requireEnabled(runtimeId).setAgentType("OPENCODE");
        dispatcher.process(id);
        verifyNoInteractions(probes);
    }

    @Test void revokedOwnershipInterruptsActiveProbe() throws Exception {
        prepareWorker(null);
        doReturn(Optional.of(job("CANCELLED",null,null,OffsetDateTime.now().plusSeconds(2)))).when(jobs).find(id);
        when(probes.test(any(),any())).thenAnswer(call -> { Thread.sleep(5000); return null; });
        dispatcher.process(id);
        verify(jobs,never()).finish(any(),any(),any());
    }
}
