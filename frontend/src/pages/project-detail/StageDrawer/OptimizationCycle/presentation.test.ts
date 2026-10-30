import { expect, it } from 'vitest'

import { createOptimizationCycle, createStageRun } from '@/test/optimization-cycle-fixtures'

import {
  getOptimizationNextAction,
  getOptimizationStatusText,
  getRevisionLabel,
  getStageRunDescription
} from './presentation'

it.each([
  [{ versionLabel: 'release', gitCommit: 'commit', gitBranch: 'branch' }, 'release'],
  [{ versionLabel: null, gitCommit: 'commit', gitBranch: 'branch' }, 'commit'],
  [{ versionLabel: null, gitCommit: null, gitBranch: 'branch' }, 'branch'],
  [{ versionLabel: null, gitCommit: null, gitBranch: null }, '未标记版本'],
  [undefined, '未标记版本']
] as const)('uses the strongest available revision identity for %j', (revision, expected) => {
  expect(getRevisionLabel(revision)).toBe(expected)
})
it('preserves unknown statuses and reports counts even when the latest status has not arrived', () => {
  expect(getOptimizationStatusText('PLUGIN_STATUS')).toBe('PLUGIN_STATUS')
  expect(getOptimizationStatusText()).toBe('缺失数据')
  expect(getStageRunDescription({ ...createStageRun(), total: 2 })).toBe('2 次执行')
  expect(getStageRunDescription(createStageRun('FAILED'))).toBe('1 次执行，最近失败')
})
it.each([
  { recommendation: '需要重新验证', summary: '结果不同', expected: '需要重新验证' },
  { recommendation: null, summary: '结果不同', expected: '结果不同' },
  { recommendation: null, summary: null, expected: '自动化测试已完成，但双端结果尚未达成一致。' }
])(
  'does not allow archiving an inconclusive comparison, using the best available explanation: $expected',
  ({ recommendation, summary, expected }) => {
    const cycle = createOptimizationCycle()
    cycle.summary.build = createStageRun('SUCCEEDED')
    cycle.summary.test = createStageRun('SUCCEEDED')
    cycle.summary.testComparison = {
      id: 'comparison',
      taskId: 'comparison-task',
      testBatchId: 'batch',
      riskLevel: null,
      createdAt: '2026-10-28T00:00:00Z',
      finishedAt: null,
      status: 'SUCCEEDED',
      overallStatus: 'INCONCLUSIVE',
      summary,
      recommendation
    }
    expect(getOptimizationNextAction(cycle)).toMatchObject({
      stageId: 'test',
      description: expected
    })
  }
)
