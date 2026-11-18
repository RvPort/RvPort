package cn.rvport.backend.report.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.rvport.backend.report.ReportGenerateRequest;
import cn.rvport.backend.security.RvPortUserPrincipal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.*;

class ReportReviewRouteTests {
    @Test void applicationHasOneReportGenerationEntryAndNoRetiredNodeConfirmationOrKbAiRoutes() throws Exception {
        var scan=new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var paths=new ArrayList<String>();
        for(var bean:scan.findCandidateComponents("cn.rvport.backend")) {
            var type=Class.forName(bean.getBeanClassName());
            var root=AnnotatedElementUtils.findMergedAnnotation(type,RequestMapping.class);
            var prefixes=root==null||root.path().length==0?new String[]{""}:root.path();
            for(var method:type.getDeclaredMethods()) {
                var mapping=AnnotatedElementUtils.findMergedAnnotation(method,RequestMapping.class);
                if(mapping==null) continue;
                for(var verb:mapping.method()) for(var prefix:prefixes)
                    for(var path:mapping.path().length==0?new String[]{""}:mapping.path())
                        paths.add(verb+" "+prefix+path);
            }
        }
        assertThat(paths).noneMatch(p->p.contains("ai-extension") || p.contains("migration-input"));
        assertThat(Collections.frequency(paths,"POST /api/projects/{projectId}/reports/evaluation")).isOne();
        assertThat(paths).contains("PATCH /api/me/model-stage-assignments","GET /api/me/model-stage-assignments",
                "POST /api/reports/{reportId}/review-items","PATCH /api/reports/{reportId}/review-items","GET /api/reports/{reportId}/source-files");
    }
}
