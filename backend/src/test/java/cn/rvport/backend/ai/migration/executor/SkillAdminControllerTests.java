package cn.rvport.backend.ai.migration.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import cn.rvport.backend.security.RvPortUserPrincipal;

class SkillAdminControllerTests {

    final SkillAdminService service = org.mockito.Mockito.mock(SkillAdminService.class);
    final SkillAdminController controller = new SkillAdminController(service);
    final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void 查询端点委托服务() {
        when(service.status()).thenReturn(new SkillRepository.SkillStatus(
                List.of("riscv-migrate"), "a", "b", 1, "stat"));
        var response = controller.status(request);
        assertThat(response.data().skills()).containsExactly("riscv-migrate");
        assertThat(response.data().commitsBehind()).isEqualTo(1);
    }

    @Test
    void 更新端点带操作者身份() {
        var principal = new RvPortUserPrincipal(UUID.randomUUID(), "admin", "Admin",
                "a@b.c", true, false, cn.rvport.backend.user.UserStatus.ACTIVE);
        when(service.update(org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.any(HttpServletRequest.class)))
                .thenReturn(new SkillRepository.SkillUpdateResult("a", "b", 1, 2, List.of("x")));
        var response = controller.update(principal, request);
        assertThat(response.data().afterRevision()).isEqualTo("b");
        verify(service).update(principal.id(), request);
    }
}
