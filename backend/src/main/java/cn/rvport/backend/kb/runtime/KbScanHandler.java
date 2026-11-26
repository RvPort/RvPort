package cn.rvport.backend.kb.runtime;

import cn.rvport.backend.kb.KbScanInput;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.worker.TaskExecutionContext;
import cn.rvport.backend.worker.TaskHandler;
import cn.rvport.backend.worker.TaskHandlerResult;
import org.springframework.stereotype.Component;

@Component
public class KbScanHandler implements TaskHandler {

    private final KbScanRuntimeService runtimeService;

    public KbScanHandler(KbScanRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Override
    public String taskType() {
        return RvPortTaskTypes.KB_SCAN;
    }

    @Override
    public TaskHandlerResult handle(TaskExecutionContext context) {
        runtimeService.scanTask(context.input(KbScanInput.class), context);
        return TaskHandlerResult.success("KB 扫描完成");
    }
}
