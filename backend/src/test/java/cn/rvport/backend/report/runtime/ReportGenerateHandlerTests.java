package cn.rvport.backend.report.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.report.ReportGenerateInput;
import cn.rvport.backend.report.review.ReportReviewRuntimeService;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.worker.TaskExecutionContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReportGenerateHandlerTests {

    @Test
    void exposesTaskTypeAndDelegatesInput() {
        ReportReviewRuntimeService service = Mockito.mock(ReportReviewRuntimeService.class);
        TaskExecutionContext context = Mockito.mock(TaskExecutionContext.class);
        ReportGenerateInput input = new ReportGenerateInput(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(context.input(ReportGenerateInput.class)).thenReturn(input);

        ReportGenerateHandler handler = new ReportGenerateHandler(service);

        assertThat(handler.taskType()).isEqualTo(RvPortTaskTypes.REPORT_GENERATE);
        assertThat(handler.handle(context).message()).isEqualTo("迁移评估报告生成完成");
        verify(service).generate(input, context);
    }

    @Test
    void propagatesRuntimeFailure() {
        ReportReviewRuntimeService service = Mockito.mock(ReportReviewRuntimeService.class);
        TaskExecutionContext context = Mockito.mock(TaskExecutionContext.class);
        ReportGenerateInput input = new ReportGenerateInput(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(context.input(ReportGenerateInput.class)).thenReturn(input);
        RuntimeException failure = new RuntimeException("failed");
        org.mockito.Mockito.doThrow(failure).when(service).generate(input, context);

        assertThatThrownBy(() -> new ReportGenerateHandler(service).handle(context)).isSameAs(failure);
    }
}
