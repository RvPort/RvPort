package cn.rvport.backend.kb.migration;

import java.util.UUID;

/** KB 迁移发起请求；scanRunId 可空，缺省取当前源码版本的最新成功扫描。 */
public record KbMigrationRequest(UUID scanRunId) {
}
