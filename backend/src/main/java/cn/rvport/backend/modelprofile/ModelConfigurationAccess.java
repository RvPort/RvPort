package cn.rvport.backend.modelprofile;

import cn.rvport.backend.common.error.ApiException;
import cn.rvport.backend.project.ProjectAccessService;
import cn.rvport.backend.user.UserRepository;
import cn.rvport.backend.user.UserStatus;
import cn.rvport.backend.user.UserView;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 后台执行显式校验持久化配置主体，不读取请求线程或默认管理员。 */
@Service
public class ModelConfigurationAccess {
    private final UserRepository users;
    private final ProjectAccessService projects;

    public ModelConfigurationAccess(UserRepository users, ProjectAccessService projects) {
        this.users = users;
        this.projects = projects;
    }

    public UserView requireActive(UUID userId) {
        if (userId == null) {
            throw unavailable();
        }
        return users.findById(userId)
                .filter(user -> user.status() == UserStatus.ACTIVE && !user.mustChangePassword())
                .orElseThrow(this::unavailable);
    }

    public void requireProject(UUID userId, UUID projectId) {
        UserView user = requireActive(userId);
        projects.requireProjectAccess(projectId, userId, user.superAdmin());
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_REQUEST, "MODEL_CONFIGURATION_USER_UNAVAILABLE",
                "模型配置用户不可用，请重新触发 / Model configuration user unavailable; trigger again");
    }
}
