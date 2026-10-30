import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import type { ProjectDependencyAnalysis } from '@/types/project/dependency-analysis'

import {
  artifactFileExtension,
  dependencyExecutionTime,
  dependencyFailureDescription,
  dependencyFailureTitle,
  isCurrentAnalysisContext,
  riskTagLabel,
  summaryCount,
  summaryGroupEntries,
  targetMatchStatusLabel,
  targetMatchSummaryTone,
  taskStatusTag,
  tooltipTextCell
} from './presentation'

function analysis(overrides: Partial<ProjectDependencyAnalysis> = {}): ProjectDependencyAnalysis {
  return {
    id: 'analysis-1',
    projectId: 'project-1',
    taskId: 'task-1',
    sourceRevisionId: 'revision-1',
    sourceBuildRunId: 'run-1',
    targetEnvironmentId: 'environment-1',
    environmentConfigVersion: 1,
    currentEnvironmentConfigVersion: 1,
    currentRuntimeEnvironment: true,
    status: 'FAILED',
    summary: {},
    warnings: [],
    createdAt: '2026-10-27T08:00:00Z',
    startedAt: '2026-10-27T08:01:00Z',
    finishedAt: '2026-10-27T08:02:00Z',
    ...overrides
  }
}

describe('dependency analysis presentation', () => {
  it.each([
    ['CRITICAL', '严重'],
    ['LOW', '低'],
    ['COMMENT', '备注'],
    ['CUSTOM', 'CUSTOM']
  ])('labels risk %s as %s', (risk, label) => {
    expect(riskTagLabel(risk)).toBe(label)
  })

  it.each([
    ['TARGET_INSTALLED', '目标已安装'],
    ['SOURCE_BUILD_REQUIRED', '需从源码构建'],
    ['MISSING', '目标缺失'],
    ['CUSTOM', 'CUSTOM']
  ])('labels target match %s as %s', (status, label) => {
    expect(targetMatchStatusLabel(status)).toBe(label)
  })

  it('uses distinct tones for matched, available, repairable, missing and unknown targets', () => {
    const tones = [
      'TARGET_INSTALLED',
      'REPO_AVAILABLE',
      'SOURCE_BUILD_REQUIRED',
      'MISSING',
      'CUSTOM'
    ].map(targetMatchSummaryTone)
    expect(new Set(tones).size).toBe(5)
  })

  it('returns only numeric summary values and numeric group entries', () => {
    const summary = { count: 3, text: 'unknown', group: { packages: 2, invalid: 'x' } }
    expect(summaryCount(summary, 'count')).toBe(3)
    expect(summaryCount(summary, 'text')).toBe(0)
    expect(summaryGroupEntries(summary, 'group')).toEqual([['packages', 2]])
    expect(summaryGroupEntries(summary, 'text')).toEqual([])
    expect(summaryGroupEntries({ group: [] }, 'group')).toEqual([])
  })

  it('prefers actionable failure messages and falls back when evidence is empty', () => {
    expect(
      dependencyFailureDescription(
        analysis({
          summary: { error: ' ', failureMessage: 'Package unavailable', message: 'Later' }
        })
      )
    ).toBe('Package unavailable')
    expect(
      dependencyFailureDescription(analysis({ summary: { message: '  Retry later  ' } }))
    ).toBe('  Retry later  ')
    expect(dependencyFailureDescription(analysis())).toContain('未成功完成')
    expect(dependencyFailureTitle('CANCELLED')).toContain('取消')
    expect(dependencyFailureTitle('TIMEOUT')).toContain('超时')
    expect(dependencyFailureTitle('FAILED')).toContain('失败')
  })

  it.each([
    ['RUNNING', '开始时间：'],
    ['QUEUED', '创建时间：'],
    ['FAILED', '完成时间：']
  ] as const)('shows the relevant time for %s', (status, label) => {
    render(<>{dependencyExecutionTime(analysis({ status }))}</>)
    expect(screen.getByText(new RegExp(label))).toBeInTheDocument()
  })

  it('handles missing optional timestamps and context validity', () => {
    render(<>{dependencyExecutionTime(analysis({ status: 'RUNNING', startedAt: null }))}</>)
    expect(screen.getByText(/开始时间：/)).toBeInTheDocument()
    expect(isCurrentAnalysisContext(analysis())).toBe(true)
    expect(isCurrentAnalysisContext(analysis({ currentSourceRevision: false }))).toBe(false)
    expect(isCurrentAnalysisContext(analysis({ currentRuntimeEnvironment: false }))).toBe(false)
  })

  it('uses content type extensions without assuming every artifact is JSON', () => {
    expect(artifactFileExtension('text/html')).toBe('html')
    expect(artifactFileExtension('application/json')).toBe('json')
    expect(artifactFileExtension('text/plain')).toBe('txt')
  })

  it('renders a readable status tag and an empty-value tooltip', () => {
    render(
      <>
        {taskStatusTag('SUCCEEDED')}
        {tooltipTextCell(null)}
      </>
    )
    expect(screen.getByText('完成')).toBeInTheDocument()
    expect(screen.getByText('--')).toBeInTheDocument()
  })
})
