package cn.rvport.backend.report.runtime;

import cn.rvport.backend.report.ReportGenerateInput;
import cn.rvport.backend.report.review.ReportReviewRuntimeService;
import cn.rvport.backend.task.RvPortTaskTypes;
import cn.rvport.backend.worker.TaskExecutionContext;
import cn.rvport.backend.worker.TaskHandler;
import cn.rvport.backend.worker.TaskHandlerResult;
import org.springframework.stereotype.Component;

@Component
public class ReportGenerateHandler implements TaskHandler {

    private final ReportReviewRuntimeService runtimeService;

    public ReportGenerateHandler(ReportReviewRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Override
    public String taskType() {
        return RvPortTaskTypes.REPORT_GENERATE;
    }

    @Override
    public TaskHandlerResult handle(TaskExecutionContext context) {
        runtimeService.generate(context.input(ReportGenerateInput.class), context);
        return TaskHandlerResult.success("迁移评估报告生成完成");
    }
}
