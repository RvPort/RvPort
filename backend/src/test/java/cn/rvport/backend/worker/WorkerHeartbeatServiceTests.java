package cn.rvport.backend.worker;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.config.RvPortAppMode;
import cn.rvport.backend.config.WorkerProperties;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkerHeartbeatServiceTests {
    @Test void failedInitialRegistrationNeverStartsConsumptionAndCapabilityFailureDoesNotStopRegisteredWorker() {
        var properties=properties(1,2);var id=new WorkerInstanceIdProvider(properties);
        var repository=org.mockito.Mockito.mock(WorkerInstanceRepository.class);
        var concurrency=org.mockito.Mockito.mock(WorkerConcurrencyController.class);
        var registry=org.mockito.Mockito.mock(TaskExecutionRegistry.class);
        var capabilities=org.mockito.Mockito.mock(ReportWorkerCapabilityService.class);
        var service=new WorkerHeartbeatService(properties,id,repository,concurrency,registry,capabilities);
        org.mockito.Mockito.doThrow(new IllegalStateException("database offline")).when(repository).upsertHeartbeat(
                org.mockito.Mockito.anyString(),org.mockito.Mockito.anyString(),org.mockito.Mockito.anyLong(),org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyInt(),org.mockito.Mockito.anyInt(),org.mockito.Mockito.anyInt());
        org.assertj.core.api.Assertions.assertThatThrownBy(service::heartbeat).hasMessage("database offline");
        org.mockito.Mockito.verifyNoInteractions(concurrency,capabilities);
        org.mockito.Mockito.reset(repository);when(concurrency.apply(1)).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("capability unavailable")).when(capabilities).heartbeat(id.workerInstanceId());
        service.heartbeat();service.heartbeat();
        var order=org.mockito.Mockito.inOrder(repository,concurrency);
        order.verify(repository).findConcurrencyLimit(id.workerInstanceId());
        order.verify(repository).upsertHeartbeat(org.mockito.Mockito.eq(id.workerInstanceId()),org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyLong(),org.mockito.Mockito.anyString(),org.mockito.Mockito.eq(1),org.mockito.Mockito.eq(0),org.mockito.Mockito.eq(0));
        order.verify(concurrency).apply(1);
        verify(capabilities,org.mockito.Mockito.times(2)).heartbeat(id.workerInstanceId());
    }

    @Test
    void heartbeatRegistersWorkerAndUsesConfiguredConcurrencyWhenNoDatabaseOverrideExists() {
        WorkerProperties properties = properties(2, 8);
        WorkerInstanceIdProvider idProvider = new WorkerInstanceIdProvider(properties);
        WorkerInstanceRepository repository = org.mockito.Mockito.mock(WorkerInstanceRepository.class);
        WorkerConcurrencyController concurrencyController = org.mockito.Mockito.mock(WorkerConcurrencyController.class);
        TaskExecutionRegistry registry = org.mockito.Mockito.mock(TaskExecutionRegistry.class);
        when(repository.findConcurrencyLimit(idProvider.workerInstanceId())).thenReturn(Optional.empty());
        when(concurrencyController.apply(2)).thenReturn(2);
        when(registry.activeCount()).thenReturn(1);
        WorkerHeartbeatService service = new WorkerHeartbeatService(
                properties,
                idProvider,
                repository,
                concurrencyController,
                registry, org.mockito.Mockito.mock(ReportWorkerCapabilityService.class)
        );

        service.heartbeat();

        var order = org.mockito.Mockito.inOrder(repository, concurrencyController);
        order.verify(repository).upsertHeartbeat(org.mockito.Mockito.eq(idProvider.workerInstanceId()),
                org.mockito.Mockito.anyString(),org.mockito.Mockito.anyLong(),org.mockito.Mockito.anyString(),
                org.mockito.Mockito.eq(2),org.mockito.Mockito.eq(0),org.mockito.Mockito.eq(1));
        order.verify(concurrencyController).apply(2);
        service.heartbeat();

        verify(repository, org.mockito.Mockito.times(2)).upsertHeartbeat(
                org.mockito.Mockito.eq(idProvider.workerInstanceId()),
                org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyLong(),
                org.mockito.Mockito.anyString(),
                org.mockito.Mockito.eq(2),
                org.mockito.Mockito.eq(2),
                org.mockito.Mockito.eq(1)
        );
    }

    @Test
    void heartbeatAppliesDatabaseConcurrencyOverride() {
        WorkerProperties properties = properties(1, 8);
        WorkerInstanceIdProvider idProvider = new WorkerInstanceIdProvider(properties);
        WorkerInstanceRepository repository = org.mockito.Mockito.mock(WorkerInstanceRepository.class);
        WorkerConcurrencyController concurrencyController = org.mockito.Mockito.mock(WorkerConcurrencyController.class);
        TaskExecutionRegistry registry = org.mockito.Mockito.mock(TaskExecutionRegistry.class);
        when(repository.findConcurrencyLimit(idProvider.workerInstanceId())).thenReturn(Optional.of(0));
        when(concurrencyController.apply(0)).thenReturn(0);
        WorkerHeartbeatService service = new WorkerHeartbeatService(
                properties,
                idProvider,
                repository,
                concurrencyController,
                registry, org.mockito.Mockito.mock(ReportWorkerCapabilityService.class)
        );

        service.heartbeat();

        verify(concurrencyController).apply(0);
        verify(repository, org.mockito.Mockito.times(2)).upsertHeartbeat(
                org.mockito.Mockito.eq(idProvider.workerInstanceId()),
                org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyLong(),
                org.mockito.Mockito.anyString(),
                org.mockito.Mockito.eq(1),
                org.mockito.Mockito.eq(0),
                org.mockito.Mockito.eq(0)
        );
    }

    private WorkerProperties properties(int concurrencyLimit, int maxConcurrencyLimit) {
        return new WorkerProperties(true, "test-worker", RvPortAppMode.COMBINED, concurrencyLimit, maxConcurrencyLimit, 10, 60);
    }
}
