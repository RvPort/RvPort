package cn.rvport.backend.report;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import cn.rvport.backend.task.TaskStatus;
import org.junit.jupiter.api.Test;

class SourceVersionMetadataTests {
    ReportView report(Map<String,Object> summary) {
        return new ReportView(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                ReportType.EVALUATION,TaskStatus.SUCCEEDED,"报告",summary,null,null,null,null);
    }
    @Test void fixedMetadataWinsOverLiveRevisionAndUnavailableFieldsStayNullable() {
        var fixed=SourceVersionMetadata.of("上传版本","abc123","SNAPSHOT");
        var fallback=SourceVersionMetadata.of("后来修改的标签","def456","REVISION_RECORD");
        var view=report(Map.of("sourceMetadata",fixed));
        assertThat(SourceVersionMetadata.resolve(view,fallback)).isEqualTo(fixed);
        var response=ReportResponse.from(view,null,null,null,Map.of(),fallback);
        assertThat(response.sourceVersionLabel()).isEqualTo("上传版本");
        assertThat(response.sourceGitCommit()).isEqualTo("abc123");
        assertThat(response.sourceMetadataOrigin()).isEqualTo("SNAPSHOT");
        assertThat(response.reviewAvailable()).isTrue();
        assertThat(response.reviewUnavailableReasonCode()).isNull();
        assertThat(response.reviewUnavailableMessage()).isNull();assertThat(response.reviewUnavailableMessageEn()).isNull();
        var legacy=report(Map.of());
        assertThat(SourceVersionMetadata.resolve(legacy,fallback)).isEqualTo(fallback);
        var missing=ReportResponse.from(legacy,List.of(),null,null,null);
        assertThat(missing.sourceMetadataOrigin()).isEqualTo("UNAVAILABLE");
        assertThat(missing.sourceVersionLabel()).isNull();assertThat(missing.sourceGitCommit()).isNull();
        assertThat(missing.reviewAvailable()).isFalse();
        assertThat(missing.reviewUnavailableReasonCode()).isEqualTo("REPORT_REVIEW_UNAVAILABLE");
        assertThat(missing.reviewUnavailableMessage()).isNotBlank();assertThat(missing.reviewUnavailableMessageEn()).isNotBlank();
    }
}
