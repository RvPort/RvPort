package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.worker.TaskExecutionContext;
import cn.rvport.backend.worker.TaskHandlerResult;
import org.junit.jupiter.api.Test;

class AiMigrationHandlerTests {

    final AiMigrationRuntimeService runtimeService = mock(AiMigrationRuntimeService.class);
    final AiMigrationHandler handler = new AiMigrationHandler(runtimeService);
    final TaskExecutionContext context = mock(TaskExecutionContext.class);

    @Test
    void 任务类型与委托执行() {
        assertThat(handler.taskType()).isEqualTo("AI_MIGRATION");

        AiMigrationInput input = new AiMigrationInput(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), null, java.util.Map.of());
        when(context.input(AiMigrationInput.class)).thenReturn(input);
        TaskHandlerResult result = handler.handle(context);

        assertThat(result).isNotNull();
        verify(runtimeService).migrate(eq(input), any());
    }
}
