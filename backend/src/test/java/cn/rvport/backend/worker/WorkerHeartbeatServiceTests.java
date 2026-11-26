package cn.rvport.backend.worker;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.config.RvPortAppMode;
import cn.rvport.backend.config.WorkerProperties;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkerHeartbeatServiceTests {

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
                registry
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
                registry
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
