package cn.rvport.backend.report;

import java.util.UUID;

public record ReportGenerateInput(
        UUID projectId,
        UUID sourceRevisionId,
        UUID reportId,
        UUID configurationUserId,
        cn.rvport.backend.modelprofile.ModelExecutionSelection modelExecution
) {
    public ReportGenerateInput(UUID projectId,UUID sourceRevisionId,UUID reportId) { this(projectId,sourceRevisionId,reportId,null,null); }
}
