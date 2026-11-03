import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { createAutomatedTest, createTestComparison } from '@/test/automated-test-fixtures'
import type { ProjectAutomatedTest, ProjectTestComparison } from '@/types/project'

import ComparisonRecordDetail from './ComparisonRecordDetail'
import TestRecordDetail from './TestRecordDetail'

vi.mock('../../components/TaskLogPanel', () => ({
  TaskLogPanel: ({
    taskId,
    status,
    fallbackContent,
    onTaskCompleted
  }: {
    taskId: string
    status: string
    fallbackContent?: string | null
    onTaskCompleted: () => void
  }) => (
    <section aria-label="绑定的任务日志">
      <span>{taskId}</span>
      <span>{status}</span>
      <span>{fallbackContent}</span>
      <button onClick={onTaskCompleted}>任务结束</button>
    </section>
  )
}))

describe('test record detail bindings', () => {
  it('rebinds log identity, directory, exit code and completion callback when selecting another test', async () => {
    const firstCallback = vi.fn()
    const secondCallback = vi.fn()
    const view = render(
      <TestRecordDetail test={createAutomatedTest()} onTaskCompleted={firstCallback} />
    )
    expect(screen.getByText('/workspace/source')).toBeInTheDocument()
    const next = createAutomatedTest({
      id: 'test-next',
      taskId: 'task-next',
      status: 'SUCCEEDED',
      workingDirectory: '/workspace/next',
      exitCode: 0,
      finishedAt: '2026-10-28T00:01:00Z'
    })
    view.rerender(<TestRecordDetail test={next} onTaskCompleted={secondCallback} />)
    expect(screen.queryByText('test-source')).not.toBeInTheDocument()
    expect(screen.queryByText('task-source')).not.toBeInTheDocument()
    expect(screen.queryByText('/workspace/source')).not.toBeInTheDocument()
    expect(screen.getByText('/workspace/next')).toBeInTheDocument()
    expect(screen.getByText('0')).toBeInTheDocument()
    expect(screen.getByText('task-next')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '任务结束' }))
    expect(secondCallback).toHaveBeenCalledOnce()
    expect(firstCallback).not.toHaveBeenCalled()
  })

  it('keeps absent directory and exit code explicit for a legacy record', () => {
    render(
      <TestRecordDetail
        test={createAutomatedTest({
          workingDirectory: null,
          exitCode: null,
          startedAt: null,
          finishedAt: null
        })}
        onTaskCompleted={vi.fn()}
      />
    )
    expect(screen.getByRole('region', { name: '测试概要' })).toHaveTextContent('--')
    expect(screen.queryByText('0')).not.toBeInTheDocument()
  })

  it('replaces failed comparison metadata and log fallback when a different batch is selected', async () => {
    const oldCallback = vi.fn()
    const nextCallback = vi.fn()
    const view = render(
      <ComparisonRecordDetail
        comparison={createTestComparison({ status: 'FAILED', errorMessage: '旧任务失败详情' })}
        onTaskCompleted={oldCallback}
      />
    )
    expect(screen.getByText('旧任务失败详情')).toBeInTheDocument()
    const comparison = createTestComparison({
      id: 'comparison-next',
      taskId: 'task-next',
      testBatchId: 'batch-next',
      status: 'SUCCEEDED',
      overallStatus: 'EQUIVALENT',
      riskLevel: 'LOW',
      finishedAt: '2026-10-28T00:02:00Z'
    })
    view.rerender(<ComparisonRecordDetail comparison={comparison} onTaskCompleted={nextCallback} />)
    expect(screen.queryByText('旧任务失败详情')).not.toBeInTheDocument()
    expect(screen.queryByText('comparison-1')).not.toBeInTheDocument()
    expect(screen.queryByText('batch-1')).not.toBeInTheDocument()
    expect(screen.getByText('comparison-next')).toBeInTheDocument()
    expect(screen.getByText('batch-next')).toBeInTheDocument()
    expect(screen.getByText('结果等价')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '任务结束' }))
    expect(nextCallback).toHaveBeenCalledOnce()
    expect(oldCallback).not.toHaveBeenCalled()
  })

  it('updates independently arriving test status, timing and log fields without losing the selected directory', async () => {
    let snapshot = createAutomatedTest()
    const completed = vi.fn()
    const view = render(<TestRecordDetail test={snapshot} onTaskCompleted={completed} />)
    const updates: Array<[Partial<ProjectAutomatedTest>, string]> = [
      [{ exitCode: 7 }, '7'],
      [{ status: 'FAILED' }, '失败'],
      [{ startedAt: '2026-10-28T00:05:00Z' }, '2026-10-28 08:05'],
      [{ finishedAt: '2026-10-28T00:06:00Z' }, '2026-10-28 08:05 - 08:06'],
      [{ id: 'test-reconciled' }, 'test-reconciled'],
      [{ taskId: 'task-reconciled' }, 'task-reconciled'],
      [{ workingDirectory: '/workspace/reconciled' }, '/workspace/reconciled']
    ]
    for (const [patch, expected] of updates) {
      snapshot = { ...snapshot, ...patch }
      view.rerender(<TestRecordDetail test={snapshot} onTaskCompleted={completed} />)
      expect(screen.getByText(expected)).toBeInTheDocument()
      expect(screen.getByText(snapshot.workingDirectory!)).toBeInTheDocument()
      expect(screen.getByText(snapshot.taskId)).toBeInTheDocument()
    }
    const nextCompleted = vi.fn()
    view.rerender(<TestRecordDetail test={snapshot} onTaskCompleted={nextCompleted} />)
    await userEvent.click(screen.getByRole('button', { name: '任务结束' }))
    expect(nextCompleted).toHaveBeenCalledOnce()
    expect(completed).not.toHaveBeenCalled()
  })

  it('reconciles comparison outcome, risk, timing and task independently while preserving its batch', async () => {
    let snapshot = createTestComparison()
    const completed = vi.fn()
    const view = render(
      <ComparisonRecordDetail comparison={snapshot} onTaskCompleted={completed} />
    )
    const updates: Array<[Partial<ProjectTestComparison>, string]> = [
      [{ riskLevel: 'HIGH' }, '高'],
      [{ status: 'FAILED' }, '比较失败'],
      [{ errorMessage: '本次任务错误' }, '本次任务错误'],
      [{ taskId: 'comparison-task-new' }, 'comparison-task-new'],
      [{ status: 'SUCCEEDED' }, '已完成'],
      [{ overallStatus: 'EQUIVALENT' }, '结果等价'],
      [{ id: 'comparison-reconciled' }, 'comparison-reconciled'],
      [{ startedAt: '2026-10-28T00:05:00Z' }, '2026-10-28 08:05'],
      [{ finishedAt: '2026-10-28T00:06:00Z' }, '2026-10-28 08:05 - 08:06'],
      [{ testBatchId: 'batch-reconciled' }, 'batch-reconciled']
    ]
    for (const [patch, expected] of updates) {
      snapshot = { ...snapshot, ...patch }
      view.rerender(<ComparisonRecordDetail comparison={snapshot} onTaskCompleted={completed} />)
      expect(screen.getByText(expected)).toBeInTheDocument()
      expect(screen.getByText(snapshot.testBatchId)).toBeInTheDocument()
    }
    snapshot = { ...snapshot, errorMessage: null }
    const nextCompleted = vi.fn()
    view.rerender(<ComparisonRecordDetail comparison={snapshot} onTaskCompleted={nextCompleted} />)
    expect(screen.queryByText('本次任务错误')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '任务结束' }))
    expect(nextCompleted).toHaveBeenCalledOnce()
    expect(completed).not.toHaveBeenCalled()
  })
})
