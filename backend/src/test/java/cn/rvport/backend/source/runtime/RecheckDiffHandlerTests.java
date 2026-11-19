package cn.rvport.backend.source.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.source.RecheckTaskInput;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.worker.TaskExecutionContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RecheckDiffHandlerTests {

    @Test
    void exposesTaskTypeAndDelegatesInput() {
        RecheckDiffRuntimeService service = Mockito.mock(RecheckDiffRuntimeService.class);
        TaskExecutionContext context = Mockito.mock(TaskExecutionContext.class);
        RecheckTaskInput input = new RecheckTaskInput(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(context.input(RecheckTaskInput.class)).thenReturn(input);
        RecheckDiffHandler handler = new RecheckDiffHandler(service);

        assertThat(handler.taskType()).isEqualTo(RvPortTaskTypes.RECHECK_DIFF);
        assertThat(handler.handle(context).message()).isEqualTo("复检差异生成完成");
        verify(service).run(input, context);
    }

    @Test
    void propagatesRuntimeFailure() {
        RecheckDiffRuntimeService service = Mockito.mock(RecheckDiffRuntimeService.class);
        TaskExecutionContext context = Mockito.mock(TaskExecutionContext.class);
        RecheckTaskInput input = new RecheckTaskInput(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(context.input(RecheckTaskInput.class)).thenReturn(input);
        RuntimeException failure = new RuntimeException("failed");
        org.mockito.Mockito.doThrow(failure).when(service).run(input, context);

        assertThatThrownBy(() -> new RecheckDiffHandler(service).handle(context)).isSameAs(failure);
    }
}
