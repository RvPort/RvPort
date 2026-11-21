package cn.rvport.backend.ai.migration.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.rvport.backend.audit.AuditLogService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SkillAdminServiceTests {

    final SkillRepository repository = org.mockito.Mockito.mock(SkillRepository.class);
    final AuditLogService audit = org.mockito.Mockito.mock(AuditLogService.class);
    final SkillAdminService service = new SkillAdminService(repository, audit);

    @Test
    void 查询委托仓储() {
        when(repository.status()).thenReturn(new SkillRepository.SkillStatus(
                List.of("riscv-migrate"), "aaa", "bbb", 2, " SKILL.md | 3 +-\n"));
        var status = service.status();
        assertThat(status.skills()).containsExactly("riscv-migrate");
        assertThat(status.commitsBehind()).isEqualTo(2);
    }

    @Test
    void 技能清单委托仓储() {
        when(repository.listSkills()).thenReturn(List.of("riscv-migrate", "riscv-verify"));
        assertThat(service.skills()).containsExactly("riscv-migrate", "riscv-verify");
    }

    @Test
    void 更新记审计并返回结果() {
        when(repository.update()).thenReturn(new SkillRepository.SkillUpdateResult(
                "before1", "after1", 3, 5, List.of(" a.md | 1 +")));
        var result = service.update(java.util.UUID.randomUUID(), null);
        assertThat(result.afterRevision()).isEqualTo("after1");
        assertThat(result.filesChanged()).isEqualTo(5);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(isNull(), any(), eq("ai.migration.skill.update"), eq("skill_repository"),
                isNull(), details.capture(), isNull());
        assertThat((Map<String, Object>) details.getValue())
                .containsEntry("before", (Object) "before1")
                .containsEntry("after", (Object) "after1")
                .containsEntry("commits", (Object) 3)
                .containsEntry("files", (Object) 5);
    }
}
