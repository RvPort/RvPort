package cn.rvport.backend.kb.migration;

import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.worker.TaskExecutionContext;
import cn.rvport.backend.worker.TaskHandler;
import cn.rvport.backend.worker.TaskHandlerResult;
import org.springframework.stereotype.Component;

@Component
public class KbMigrationHandler implements TaskHandler {

    private final KbMigrationRuntimeService runtimeService;

    public KbMigrationHandler(KbMigrationRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Override
    public String taskType() {
        return RvPortTaskTypes.KB_MIGRATION;
    }

    @Override
    public TaskHandlerResult handle(TaskExecutionContext context) {
        runtimeService.migrate(context.input(KbMigrationInput.class), context);
        return TaskHandlerResult.success("KB 迁移完成");
    }
}
