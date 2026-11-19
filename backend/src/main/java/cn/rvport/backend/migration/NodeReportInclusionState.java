package cn.rvport.backend.migration;

import java.util.UUID;

public record NodeReportInclusionState(
        boolean includedInLatestReport,
        UUID latestReportId
) {
    public static NodeReportInclusionState empty() {
        return new NodeReportInclusionState(false, null);
    }
}
