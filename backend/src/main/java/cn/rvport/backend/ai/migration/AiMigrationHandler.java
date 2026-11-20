package cn.rvport.backend.ai.migration;

import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.worker.TaskExecutionContext;
import cn.rvport.backend.worker.TaskHandler;
import cn.rvport.backend.worker.TaskHandlerResult;
import org.springframework.stereotype.Component;

@Component
public class AiMigrationHandler implements TaskHandler {

    private final AiMigrationRuntimeService runtimeService;

    public AiMigrationHandler(AiMigrationRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Override
    public String taskType() {
        return RvPortTaskTypes.AI_MIGRATION;
    }

    @Override
    public TaskHandlerResult handle(TaskExecutionContext context) {
        runtimeService.migrate(context.input(AiMigrationInput.class), context);
        return TaskHandlerResult.success("AI 迁移完成");
    }
}
