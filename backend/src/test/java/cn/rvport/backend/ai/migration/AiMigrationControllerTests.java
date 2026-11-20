package cn.rvport.backend.ai.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.common.web.ApiResponse;
import cn.rvport.backend.common.web.PageResponse;
import cn.rvport.backend.task.TaskResponse;
import cn.rvport.backend.task.TaskStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiMigrationControllerTests {

    final AiMigrationService service = mock(AiMigrationService.class);
    final AiMigrationController controller = new AiMigrationController(service);
    final UUID project = UUID.randomUUID();
    final UUID runId = UUID.randomUUID();
    final UUID actor = UUID.randomUUID();
    final cn.rvport.backend.security.RvPortUserPrincipal principal = new cn.rvport.backend.security.RvPortUserPrincipal(
            actor, "user", "User", "user@example.com", false, false,
            cn.rvport.backend.user.UserStatus.ACTIVE);
    final org.springframework.mock.web.MockHttpServletRequest request =
            new org.springframework.mock.web.MockHttpServletRequest();

    @BeforeEach
    void setup() {
        when(service.create(any(), any(), any(), anyBoolean(), any())).thenReturn(new TaskResponse(UUID.randomUUID(),
                project, "AI_MIGRATION", TaskStatus.PENDING, 0, null, null, null, null, null, null, null, Map.of()));
        when(service.list(any(), anyInt(), anyInt(), any(), anyBoolean()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));
        when(service.detail(any(), any(), anyBoolean())).thenReturn(AiMigrationResponse.from(
                new AiMigrationRunView(runId, project, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        "SUCCEEDED", Map.of(), 1, 1, 0, 0, UUID.randomUUID(), UUID.randomUUID(), null, null, null),
                List.of()));
    }

    @Test
    void 创建端点委托服务返回任务() {
        ApiResponse<TaskResponse> response = controller.create(principal, project, new AiMigrationRequest(null), request);
        assertThat(response.data().type()).isEqualTo("AI_MIGRATION");
        verify(service).create(org.mockito.ArgumentMatchers.eq(project), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(actor), org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 列表与详情端点委托服务() {
        ApiResponse<PageResponse<AiMigrationResponse>> list = controller.list(principal, project, 0, 20, request);
        assertThat(list.data().items()).isEmpty();

        ApiResponse<AiMigrationResponse> detail = controller.detail(principal, runId, request);
        assertThat(detail.data().status()).isEqualTo("SUCCEEDED");
        assertThat(detail.data().itemCount()).isEqualTo(1);
        verify(service).detail(runId, actor, false);
    }
}
