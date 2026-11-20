package cn.rvport.backend.analysis;

import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.worker.TaskExecutionContext;
import cn.rvport.backend.worker.TaskHandler;
import cn.rvport.backend.worker.TaskHandlerResult;
import org.springframework.stereotype.Component;

@Component
public class FloatingPointAnalysisHandler implements TaskHandler {
    private final SpecializedAnalysisRuntimeService runtime;
    public FloatingPointAnalysisHandler(SpecializedAnalysisRuntimeService runtime) { this.runtime = runtime; }
    @Override public String taskType() { return RvPortTaskTypes.FLOATING_POINT_ANALYSIS; }
    @Override public TaskHandlerResult handle(TaskExecutionContext context) {
        runtime.runTask(context.input(SpecializedAnalysisTaskInput.class), context);
        return TaskHandlerResult.success("浮点精度分析完成");
    }
}
