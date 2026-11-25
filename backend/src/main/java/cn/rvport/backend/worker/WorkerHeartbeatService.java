package cn.rvport.backend.worker;

import cn.rvport.backend.config.WorkerProperties;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "rvport.worker", name = "enabled", havingValue = "true")
public class WorkerHeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(WorkerHeartbeatService.class);

    private final WorkerProperties properties;
    private final WorkerInstanceIdProvider workerInstanceIdProvider;
    private final WorkerInstanceRepository workerInstanceRepository;
    private final WorkerConcurrencyController workerConcurrencyController;
    private final TaskExecutionRegistry taskExecutionRegistry;
    private final ReportWorkerCapabilityService capabilities;
    private boolean registered;

    public WorkerHeartbeatService(
            WorkerProperties properties,
            WorkerInstanceIdProvider workerInstanceIdProvider,
            WorkerInstanceRepository workerInstanceRepository,
            WorkerConcurrencyController workerConcurrencyController,
            TaskExecutionRegistry taskExecutionRegistry,
            ReportWorkerCapabilityService capabilities
    ) {
        this.properties = properties;
        this.workerInstanceIdProvider = workerInstanceIdProvider;
        this.workerInstanceRepository = workerInstanceRepository;
        this.workerConcurrencyController = workerConcurrencyController;
        this.taskExecutionRegistry = taskExecutionRegistry;
        this.capabilities=capabilities;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${rvport.worker.heartbeat-interval-seconds:10}000")
    public void heartbeat() {
        int concurrencyLimit = workerInstanceRepository.findConcurrencyLimit(workerInstanceIdProvider.workerInstanceId())
                .orElse(properties.concurrencyLimit());
        // 队列消费者只能在首次身份写入成功之后启动，避免启动窗口耗尽消息重试。
        if (!registered) {
            workerInstanceRepository.upsertHeartbeat(workerInstanceIdProvider.workerInstanceId(), hostname(),
                    processId(), appVersion(), properties.concurrencyLimit(), 0, taskExecutionRegistry.activeCount());
            registered = true;
        }
        int activeConcurrency = workerConcurrencyController.apply(concurrencyLimit);
        workerInstanceRepository.upsertHeartbeat(
                workerInstanceIdProvider.workerInstanceId(),
                hostname(),
                processId(),
                appVersion(),
                properties.concurrencyLimit(),
                activeConcurrency,
                taskExecutionRegistry.activeCount()
        );
        try { capabilities.heartbeat(workerInstanceIdProvider.workerInstanceId()); }
        catch(RuntimeException ex) { log.warn("Worker capability observation deferred errorType={}",ex.getClass().getSimpleName()); }
    }

    private String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            log.debug("Worker hostname detection failed", ex);
            return "unknown-host";
        }
    }

    private long processId() {
        try {
            return ProcessHandle.current().pid();
        } catch (Exception ex) {
            String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
            int separator = runtimeName.indexOf('@');
            if (separator > 0) {
                try {
                    return Long.parseLong(runtimeName.substring(0, separator));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
            return -1;
        }
    }

    private String appVersion() {
        String version = WorkerHeartbeatService.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }
}
