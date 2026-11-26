package cn.rvport.backend.report.review;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.rvport.backend.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReportRequestExceptionHandlerTests {
    @Test void rejectsInvalidJsonBeforeGenerationWithoutEchoingPayload() throws Exception {
        var generation=mock(ReportReviewGenerationService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ReportReviewGenerationController(generation,mock(CurrentFixListResolver.class)))
                .setCustomArgumentResolvers(new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(org.springframework.security.core.annotation.AuthenticationPrincipal.class);
                    }
                    public Object resolveArgument(org.springframework.core.MethodParameter parameter,
                            org.springframework.web.method.support.ModelAndViewContainer container,
                            org.springframework.web.context.request.NativeWebRequest request,
                            org.springframework.web.bind.support.WebDataBinderFactory factory) {
                        return new cn.rvport.backend.security.RvPortUserPrincipal(java.util.UUID.randomUUID(),"test","test",null,false,false,
                                cn.rvport.backend.user.UserStatus.ACTIVE);
                    }
                })
                .setControllerAdvice(new ReportRequestExceptionHandler(),new GlobalExceptionHandler()).build();
        for(String body:new String[]{"{\"useAiEnhancement\":false}","{\"secretUnknown\":\"private\"}","{", "", "null",
                "{\"mode\":\"UNKNOWN\"}","{\"sourceRevisionId\":\"invalid\"}","{\"mode\":{}}","[]"}) {
            mvc.perform(post("/api/projects/11111111-1111-4111-8111-111111111111/reports/evaluation")
                    .header("Idempotency-Key","reusable-key").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPORT_REQUEST_INVALID"))
                    .andExpect(jsonPath("$.details.messageEn").isNotEmpty())
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        }
        verifyNoInteractions(generation);
        when(generation.create(any(),any(),eq("reusable-key"),any(),eq(false))).thenReturn(java.util.Map.of("id","original-task"));
        for(int retry=0;retry<2;retry++) mvc.perform(post("/api/projects/11111111-1111-4111-8111-111111111111/reports/evaluation")
                .header("Idempotency-Key","reusable-key").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"STANDARD\",\"selectionToken\":\"current\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("original-task"));
        verify(generation,times(2)).create(any(),argThat(request->request.mode()==ReportMode.STANDARD),eq("reusable-key"),any(),eq(false));
    }
}
