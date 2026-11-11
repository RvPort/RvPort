package cn.rvport.backend.modelprofile;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.user.UserRepository;
import cn.rvport.backend.user.UserStatus;
import cn.rvport.backend.user.UserView;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelConfigurationAccessTests {
    private final UUID actor = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UserRepository users = mock(UserRepository.class);
    private final ProjectAccessService projects = mock(ProjectAccessService.class);
    private final ModelConfigurationAccess access = new ModelConfigurationAccess(users, projects);

    @Test void missingDisabledAndPasswordChangeUsersCannotExecute() {
        assertThatThrownBy(() -> access.requireActive(null)).isInstanceOf(ApiException.class);
        verifyNoInteractions(users, projects);
        when(users.findById(actor)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> access.requireActive(actor)).hasMessageContaining("Model configuration user unavailable");
        when(users.findById(actor)).thenReturn(Optional.of(user(false, false, UserStatus.DISABLED)));
        assertThatThrownBy(() -> access.requireActive(actor)).isInstanceOf(ApiException.class);
        when(users.findById(actor)).thenReturn(Optional.of(user(false, true, UserStatus.ACTIVE)));
        assertThatThrownBy(() -> access.requireProject(actor, project)).isInstanceOf(ApiException.class);
        verifyNoInteractions(projects);
    }

    @Test void projectAuthorizationUsesCurrentPersistentUserAuthority() {
        for (boolean admin : new boolean[]{false, true}) {
            UserView user = user(admin, false, UserStatus.ACTIVE);
            when(users.findById(actor)).thenReturn(Optional.of(user));
            assertThat(access.requireActive(actor)).isSameAs(user);
            access.requireProject(actor, project);
            verify(projects).requireProjectAccess(project, actor, admin);
        }
    }

    private UserView user(boolean admin, boolean passwordChange, UserStatus status) {
        return new UserView(actor, "fixture", "fixture", null, admin, passwordChange, status, null, null, null);
    }
}
