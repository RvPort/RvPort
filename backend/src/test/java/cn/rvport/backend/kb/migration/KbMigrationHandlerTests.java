package cn.rvport.backend.kb.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.worker.TaskExecutionContext;
import cn.rvport.backend.worker.TaskHandlerResult;
import org.junit.jupiter.api.Test;

class KbMigrationHandlerTests {

    final KbMigrationRuntimeService runtimeService = mock(KbMigrationRuntimeService.class);
    final KbMigrationHandler handler = new KbMigrationHandler(runtimeService);
    final TaskExecutionContext context = mock(TaskExecutionContext.class);

    @Test
    void 任务类型与委托执行() {
        assertThat(handler.taskType()).isEqualTo("KB_MIGRATION");

        KbMigrationInput input = new KbMigrationInput(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), null, java.util.Map.of());
        when(context.input(KbMigrationInput.class)).thenReturn(input);
        TaskHandlerResult result = handler.handle(context);

        assertThat(result).isNotNull();
        verify(runtimeService).migrate(eq(input), any());
    }
}
