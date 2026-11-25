package cn.rvport.backend.buildvalidation;

import cn.rvport.backend.task.TaskStatus;
import java.util.Objects;
import java.util.UUID;

/** 当前双端验证必须来自同一次请求；任何未知历史批次均不猜测为有效。 */
public final class BuildValidationPair {
    private BuildValidationPair() { }

    public static boolean matches(BuildValidationRunView run, UUID project, UUID source, UUID environment, long version) {
        return run != null && source != null && environment != null
                && project.equals(run.projectId()) && source.equals(run.sourceRevisionId())
                && environment.equals(run.environmentId()) && Objects.equals(version, run.environmentConfigVersion())
                && run.buildScope() == BuildValidationScope.BOTH;
    }

    public static boolean succeeded(BuildValidationRunView source, BuildValidationRunView target) {
        if (source == null || target == null || source.id().equals(target.id())
                || Objects.equals(source.environmentId(), target.environmentId())
                || source.status() != TaskStatus.SUCCEEDED || target.status() != TaskStatus.SUCCEEDED) return false;
        Object batch = source.buildFacts().get("buildBatchId");
        return batch instanceof String id && id.matches("[0-9a-fA-F-]{36}")
                && batch.equals(target.buildFacts().get("buildBatchId"))
                && Objects.equals(source.scriptSha256(), target.scriptSha256()) && source.scriptSha256() != null;
    }
}
