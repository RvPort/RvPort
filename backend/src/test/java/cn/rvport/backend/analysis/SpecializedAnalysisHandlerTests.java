package cn.rvport.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.worker.TaskExecutionContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpecializedAnalysisHandlerTests {

    @Test
    void floatingPointHandlerDelegatesToRuntime() {
        SpecializedAnalysisRuntimeService runtime = mock(SpecializedAnalysisRuntimeService.class);
        TaskExecutionContext context = mock(TaskExecutionContext.class);
        SpecializedAnalysisTaskInput input = input(SpecializedAnalyzerType.FLOATING_POINT);
        when(context.input(SpecializedAnalysisTaskInput.class)).thenReturn(input);
        FloatingPointAnalysisHandler handler = new FloatingPointAnalysisHandler(runtime);

        assertThat(handler.taskType()).isEqualTo(RvPortTaskTypes.FLOATING_POINT_ANALYSIS);
        assertThat(handler.handle(context).message()).contains("浮点精度分析完成");
        verify(runtime).runTask(input, context);
    }

    @Test
    void memoryOrderHandlerDelegatesToRuntime() {
        SpecializedAnalysisRuntimeService runtime = mock(SpecializedAnalysisRuntimeService.class);
        TaskExecutionContext context = mock(TaskExecutionContext.class);
        SpecializedAnalysisTaskInput input = input(SpecializedAnalyzerType.MEMORY_ORDER);
        when(context.input(SpecializedAnalysisTaskInput.class)).thenReturn(input);
        MemoryOrderAnalysisHandler handler = new MemoryOrderAnalysisHandler(runtime);

        assertThat(handler.taskType()).isEqualTo(RvPortTaskTypes.MEMORY_ORDER_ANALYSIS);
        assertThat(handler.handle(context).message()).contains("强弱内存序分析完成");
        verify(runtime).runTask(input, context);
    }

    private SpecializedAnalysisTaskInput input(SpecializedAnalyzerType type) {
        return new SpecializedAnalysisTaskInput(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), null, type);
    }
}
