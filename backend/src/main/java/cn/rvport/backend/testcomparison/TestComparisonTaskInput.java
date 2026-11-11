package cn.rvport.backend.testcomparison;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TestComparisonTaskInput(
        UUID projectId,
        UUID comparisonRunId,
        UUID sourceTestRunId,
        UUID targetTestRunId,
        cn.rvport.backend.modelprofile.ModelExecutionSelection modelExecution
) {
}
