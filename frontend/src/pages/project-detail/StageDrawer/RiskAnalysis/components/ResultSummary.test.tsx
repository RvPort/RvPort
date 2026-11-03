import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { run } from '../fixtures.test-support'
import ResultSummary from './ResultSummary'

vi.mock('@/api/project/specialized-analysis', () => ({
  getSpecializedArtifactDownloadUrl: (project: string, stage: string, id: string, format: string) =>
    `/artifact/${project}/${stage}/${id}/${format}`
}))

describe('analysis result summary', () => {
  it('updates independent result counters and metadata while retaining the current download identity', async () => {
    let current = { ...run }
    let summary = '首次分析说明'
    const toggle = vi.fn()
    const draw = () => (
      <ResultSummary
        run={current}
        summary={summary}
        projectId="project-1"
        stageId="memory-order-analysis"
        showLogs={false}
        onToggleLogs={toggle}
      />
    )
    const view = render(draw())
    const updates = [
      { findingCount: 7 },
      { candidateCount: 9 },
      { diagnosticCount: 3 },
      { toolVersion: '2.5' },
      { createdAt: '2026-10-28T00:00:00Z' },
      { finishedAt: '2026-10-28T00:02:00Z' },
      { status: 'PARTIAL' as const },
      { id: 'result-new' }
    ]
    for (const patch of updates) {
      current = { ...current, ...patch }
      view.rerender(draw())
      expect(screen.getByRole('link', { name: '下载JSON' })).toHaveAttribute(
        'href',
        `/artifact/project-1/memory-order-analysis/${current.id}/json`
      )
      expect(screen.getByText(summary)).toBeInTheDocument()
    }
    expect(screen.getByText('7')).toBeInTheDocument()
    expect(screen.getByText('9')).toBeInTheDocument()
    expect(within(screen.getByText('分析诊断').parentElement!).getByText('3')).toBeInTheDocument()
    expect(screen.queryByText('3 项分析诊断，请查看 JSON 结果或审计包。')).not.toBeInTheDocument()
    expect(screen.getByText('2.5')).toBeInTheDocument()
    expect(screen.getByText('2026-10-28 08:02')).toBeInTheDocument()
    summary = '补充扫描范围不完整'
    view.rerender(draw())
    expect(screen.queryByText('首次分析说明')).not.toBeInTheDocument()
    expect(screen.getByText(summary)).toHaveAttribute('data-warning', 'true')
    const nextToggle = vi.fn()
    view.rerender(
      <ResultSummary
        run={current}
        summary={summary}
        projectId="project-2"
        stageId="floating-point-analysis"
        showLogs
        onToggleLogs={nextToggle}
      />
    )
    expect(screen.getByRole('link', { name: '下载JSON' })).toHaveAttribute(
      'href',
      '/artifact/project-2/floating-point-analysis/result-new/json'
    )
    await userEvent.click(screen.getByRole('button', { name: '收起日志' }))
    expect(nextToggle).toHaveBeenCalledOnce()
    expect(toggle).not.toHaveBeenCalled()
  })

  it('offers only generated artifacts and no download links without a project context', () => {
    let current = { ...run }
    const props = {
      summary: '摘要',
      showLogs: false,
      onToggleLogs: vi.fn(),
      stageId: 'memory-order-analysis' as const,
      projectId: 'project-1'
    }
    const view = render(<ResultSummary {...props} run={current} />)
    for (const patch of [
      { resultJsonArtifactId: null },
      { resultHtmlArtifactId: null },
      { auditBundleArtifactId: null }
    ]) {
      current = { ...current, ...patch }
      view.rerender(<ResultSummary {...props} run={current} />)
    }
    expect(screen.getByText('已生成 0 份制品')).toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    view.rerender(
      <ResultSummary
        {...props}
        projectId={undefined}
        run={{ ...run, toolVersion: null, diagnosticCount: 0 }}
      />
    )
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(within(screen.getByText('工具版本').parentElement!).getByText('--')).toBeInTheDocument()
    expect(screen.queryByText(/项分析诊断，请查看/)).not.toBeInTheDocument()
    expect(
      within(screen.getByText('分析制品').parentElement!).getByText('已生成 3 份制品')
    ).toBeInTheDocument()
  })
})
