package cn.rvport.backend.kb;

import java.util.List;
import java.util.UUID;

public record KbScanRequest(
        UUID sourceRevisionId,
        String ruleVersion,
        String language,
        String sourceArch,
        KbScanScope scanScope,
        List<String> excludePaths
) {
}
