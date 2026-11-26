package cn.rvport.backend.report.review;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import cn.rvport.backend.common.error.GlobalExceptionHandler;
import cn.rvport.backend.security.RvPortUserPrincipal;
import cn.rvport.backend.user.UserStatus;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.support.*;

class ReportSourceSearchControllerTests {
    @Test void searchHasStableDefaultsNoStoreAndInvalidTypesNeverReachStorage() throws Exception {
        UUID report=UUID.randomUUID(),actor=UUID.randomUUID();
        var service=mock(ReportSourceWorkspaceService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ReportSourceWorkspaceController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(org.springframework.security.core.annotation.AuthenticationPrincipal.class);
                    }
                    public Object resolveArgument(org.springframework.core.MethodParameter parameter,ModelAndViewContainer container,
                            org.springframework.web.context.request.NativeWebRequest request,
                            org.springframework.web.bind.support.WebDataBinderFactory factory) {
                        return new RvPortUserPrincipal(actor,"test","test",null,false,false,UserStatus.ACTIVE);
                    }
                }).build();
        var result=Map.<String,Object>of("available",true,"matches",List.of(),"returnedCount",0,"truncated",false);
        when(service.search(report,"fixed",actor,false,"needle",false,100)).thenReturn(result);
        String url="/api/reports/"+report+"/source-files/fixed/search";
        mvc.perform(get(url).param("keyword","needle")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.available").value(true));
        verify(service).search(report,"fixed",actor,false,"needle",false,100);clearInvocations(service);
        for(String query:List.of("?limit=bad","?caseSensitive=bad"))
            mvc.perform(get(url+query)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPORT_REQUEST_INVALID"));
        verifyNoInteractions(service);
    }
}
