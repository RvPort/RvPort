import type { AiMigrationViewedRun } from './types'

const STATUS_PRESENTATION: Record<string, { color: string; label: string }> = {
  PENDING: { color: 'default', label: '排队中' },
  RUNNING: { color: 'processing', label: '迁移中' },
  SUCCEEDED: { color: 'success', label: '已完成' },
  FAILED: { color: 'error', label: '失败' },
  CANCELLED: { color: 'warning', label: '已取消' },
  TIMEOUT: { color: 'error', label: '超时' }
}

export function presentAiMigrationStatus(status: string) {
  return STATUS_PRESENTATION[status] ?? { color: 'default', label: status }
}

/** 运行进行中或排队时还没有条目结论，用一句话说明当前状态。 */
export function describeAiMigrationRun(run: AiMigrationViewedRun) {
  const status = presentAiMigrationStatus(run.status).label
  if (run.status === 'RUNNING' || run.status === 'PENDING') {
    return `${status}，迁移在隔离容器中执行，完成后可查看补丁`
  }
  if (run.status !== 'SUCCEEDED') {
    return `${status}，本次运行没有可用的迁移结果`
  }
  const parts = [`${run.doneCount} 条已迁移`]
  if (run.falsePositiveCount > 0) parts.push(`${run.falsePositiveCount} 条判定无需迁移`)
  if (run.pendingCount > 0) parts.push(`${run.pendingCount} 条待人工确认`)
  return parts.join('，')
}

export function hasAiMigrationArtifact(run: AiMigrationViewedRun, artifactType: string) {
  return run.artifacts.some((artifact) => artifact.artifactType === artifactType)
}
