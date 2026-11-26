package cn.rvport.backend.worker;

import cn.rvport.backend.task.TaskRepository;
import cn.rvport.backend.task.TaskRuntimeHandleRepository;
import cn.rvport.backend.task.TaskRuntimeHandleStatus;
import cn.rvport.backend.task.TaskRuntimeHandleView;
import cn.rvport.backend.task.TaskStatus;
import cn.rvport.backend.task.TaskView;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskAttemptService {
    private final TaskRepository tasks;
    private final TaskRuntimeHandleRepository handles;
    private final WorkerInstanceRepository workers;

    public TaskAttemptService(TaskRepository tasks, TaskRuntimeHandleRepository handles, WorkerInstanceRepository workers) {
        this.tasks = tasks;
        this.handles = handles;
        this.workers = workers;
    }

    @Transactional
    public Claim claim(UUID taskId, String workerId) {
        if (!workers.lockForClaim(workerId)) {
            throw new TaskRetryableException("TASK_WORKER_NOT_REGISTERED", null);
        }
        TaskView task = tasks.claim(taskId, "任务开始执行");
        if (task == null) {
            return null;
        }
        // The partial unique index permits only one active handle per task.
        TaskRuntimeHandleView handle = handles.createTaskHandle(task.id(), task.projectId(), workerId);
        if (handle == null || !workerId.equals(handle.workerInstanceId())) {
            throw new TaskAttemptLostException();
        }
        return new Claim(task, handle);
    }

    @Transactional
    public <T> T publish(UUID taskId, UUID handleId, Supplier<T> operation) {
        TaskView task = tasks.lockById(taskId);
        if (task == null || task.status() != TaskStatus.RUNNING) {
            throw new TaskAttemptLostException();
        }
        TaskRuntimeHandleView current = handles.findActiveByTaskId(taskId).orElse(null);
        if (current == null || !current.id().equals(handleId)
                || current.status() != TaskRuntimeHandleStatus.ACTIVE) {
            throw new TaskAttemptLostException();
        }
        return operation.get();
    }

    public record Claim(TaskView task, TaskRuntimeHandleView handle) { }
}
