package cn.rvport.backend.ai.migration;

import java.util.UUID;

/** AI 迁移发起请求；scanRunId 可空，缺省取当前源码版本的最新成功扫描。 */
public record AiMigrationRequest(UUID scanRunId) {
}
