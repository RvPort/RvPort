import { describe, expect, it } from 'vitest'

import {
  describeAiMigrationRun,
  hasAiMigrationArtifact,
  presentAiMigrationStatus
} from './presentation'
import type { AiMigrationViewedRun } from './types'

function run(overrides: Partial<AiMigrationViewedRun> = {}): AiMigrationViewedRun {
  return {
    artifacts: [
      { artifactType: 'MIGRATION_DIFF', id: 'diff-1' },
      { artifactType: 'MIGRATION_RESULT', id: 'result-1' }
    ],
    doneCount: 16,
    falsePositiveCount: 0,
    id: 'run-1',
    pendingCount: 1,
    scanRunId: 'scan-run-1234',
    status: 'SUCCEEDED',
    ...overrides
  }
}

describe('ai migration presentation', () => {
  it('maps known statuses to a label and falls back to the raw status', () => {
    expect(presentAiMigrationStatus('RUNNING')).toEqual({ color: 'processing', label: '迁移中' })
    expect(presentAiMigrationStatus('TIMEOUT')).toEqual({ color: 'error', label: '超时' })
    expect(presentAiMigrationStatus('SOMETHING_NEW')).toEqual({
      color: 'default',
      label: 'SOMETHING_NEW'
    })
  })

  it('describes a finished run with only the non-zero item groups', () => {
    expect(describeAiMigrationRun(run())).toBe('16 条已迁移，1 条待人工确认')

    const withFalsePositives = run({ falsePositiveCount: 2, pendingCount: 0 })
    expect(describeAiMigrationRun(withFalsePositives)).toBe('16 条已迁移，2 条判定无需迁移')

    const allMigrated = run({ pendingCount: 0 })
    expect(describeAiMigrationRun(allMigrated)).toBe('16 条已迁移')
  })

  it('does not present item counts while a run is still in progress', () => {
    expect(describeAiMigrationRun(run({ doneCount: 0, status: 'RUNNING' }))).toContain(
      '迁移在隔离容器中执行'
    )
    expect(describeAiMigrationRun(run({ status: 'PENDING' }))).not.toContain('条已迁移')
  })

  it('tells a failed run apart from a finished one without item counts', () => {
    expect(describeAiMigrationRun(run({ status: 'FAILED' }))).toBe(
      '失败，本次运行没有可用的迁移结果'
    )
    expect(describeAiMigrationRun(run({ status: 'CANCELLED' }))).toBe(
      '已取消，本次运行没有可用的迁移结果'
    )
  })

  it('detects artifacts by type and ignores missing ones', () => {
    expect(hasAiMigrationArtifact(run(), 'MIGRATION_DIFF')).toBe(true)
    expect(hasAiMigrationArtifact(run(), 'MIGRATION_RESULT')).toBe(true)
    expect(hasAiMigrationArtifact(run({ artifacts: [] }), 'MIGRATION_DIFF')).toBe(false)
    expect(hasAiMigrationArtifact(run(), 'MIGRATION_UNKNOWN')).toBe(false)
  })
})
