package cn.rvport.backend.modelprofile;

import cn.rvport.backend.agentruntime.AgentModelRuntimeConfig;
import cn.rvport.backend.common.error.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** 持久化在任务输入中的非敏感引用；重试不重新选择阶段绑定。 */
public record ModelExecutionSelection(UUID configurationUserId, UUID modelProfileId, UUID agentRuntimeId) {
    public static ModelExecutionSelection from(UUID actor, AgentModelRuntimeConfig config) {
        return new ModelExecutionSelection(actor, config.modelProfile().getId(), config.runtime().getId());
    }

    public void requireActor(UUID taskActor) {
        if (configurationUserId == null || !configurationUserId.equals(taskActor)
                || modelProfileId == null || agentRuntimeId == null) {
            throw missing();
        }
    }

    public static ApiException missing() {
        return new ApiException(HttpStatus.BAD_REQUEST, "MODEL_EXECUTION_IDENTITY_MISSING",
                "缺少可信模型执行身份，请重新触发 / Trusted model execution identity missing; trigger again");
    }

    /** 领取时的安全版本；不包含地址、密钥或 prompt。 */
    public String attemptAudit(AgentModelRuntimeConfig config) {
        return "MODEL_EXECUTION configurationUserId=" + configurationUserId
                + " modelProfileId=" + modelProfileId + " agentRuntimeId=" + agentRuntimeId
                + " profileUpdatedAt=" + config.modelProfile().getUpdatedAt()
                + " runtimeUpdatedAt=" + config.runtime().getUpdatedAt();
    }

    public cn.rvport.backend.codexprovider.CodexProviderTestResult auditProbe(
            cn.rvport.backend.codexprovider.CodexProviderTestResult result, AgentModelRuntimeConfig config) {
        var details = new java.util.LinkedHashMap<String, Object>(result.details());
        // CHAT worker 的领取版本优先，API 不能用入队前的版本覆盖它。
        details.putIfAbsent("modelExecution", java.util.Map.of(
                "configurationUserId", String.valueOf(configurationUserId),
                "modelProfileId", String.valueOf(modelProfileId),
                "agentRuntimeId", String.valueOf(agentRuntimeId),
                "profileUpdatedAt", java.util.Objects.toString(config.modelProfile().getUpdatedAt(), ""),
                "runtimeUpdatedAt", java.util.Objects.toString(config.runtime().getUpdatedAt(), "")));
        return new cn.rvport.backend.codexprovider.CodexProviderTestResult(result.success(), result.status(), result.message(),
                result.testedAt(), result.agentType(), result.agentRuntimeId(), result.basicAvailable(), result.agentCompatible(),
                result.checks(), java.util.Map.copyOf(details));
    }
}
