package cn.rvport.backend.analysis;

import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.worker.TaskExecutionContext;
import cn.rvport.backend.worker.TaskHandler;
import cn.rvport.backend.worker.TaskHandlerResult;
import org.springframework.stereotype.Component;

@Component
public class MemoryOrderAnalysisHandler implements TaskHandler {
    private final SpecializedAnalysisRuntimeService runtime;
    public MemoryOrderAnalysisHandler(SpecializedAnalysisRuntimeService runtime) { this.runtime = runtime; }
    @Override public String taskType() { return RvPortTaskTypes.MEMORY_ORDER_ANALYSIS; }
    @Override public TaskHandlerResult handle(TaskExecutionContext context) {
        runtime.runTask(context.input(SpecializedAnalysisTaskInput.class), context);
        return TaskHandlerResult.success("强弱内存序分析完成");
    }
}
