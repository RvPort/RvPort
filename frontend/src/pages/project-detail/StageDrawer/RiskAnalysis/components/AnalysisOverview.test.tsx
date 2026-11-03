import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import type { SpecializedAnalysis } from '@/types/project/specialized-analysis'

import { latest, readiness, run } from '../fixtures.test-support'
import type { useRiskAnalysis } from '../useRiskAnalysis'
import AnalysisOverview from './AnalysisOverview'

vi.mock('../../components/StageOverview', () => ({
  default: ({ children }: { children: ReactNode }) => <div>{children}</div>
}))
vi.mock('../../components/TaskLogPanel', () => ({
  TaskLogPanel: ({ taskId, onTaskCompleted }: { taskId: string; onTaskCompleted: () => void }) => (
    <div>
      日志 {taskId}
      <button onClick={onTaskCompleted}>日志任务完成</button>
    </div>
  )
}))
vi.mock('./FindingList', () => ({
  default: ({
    run,
    search,
    severity,
    onFiltersChange,
    onSelect
  }: {
    run: SpecializedAnalysis
    search: string
    severity?: string
    onFiltersChange: (search: string, severity: string) => void
    onSelect: (id: string) => void
  }) => (
    <div>
      结果列表内容<span>{run.id}</span>
      <span>
        {search}:{severity}
      </span>
      <button onClick={() => onFiltersChange('publish', 'HIGH')}>筛选高风险</button>
      <button onClick={() => onSelect(`finding-${run.id}`)}>选择结果</button>
    </div>
  )
}))

type State = ReturnType<typeof useRiskAnalysis>

function state(
  currentRun: SpecializedAnalysis | null,
  overrides: Record<string, unknown> = {}
): State {
  return {
    run: currentRun,
    readiness: { data: readiness, isLoading: false, isError: false },
    latest: { data: latest(currentRun), isLoading: false, isError: false },
    selectedRun: { isLoading: false, isError: false },
    runId: null,
    buildPrerequisiteRejected: false,
    creationError: null,
    canStart: true,
    create: { isPending: false, mutate: vi.fn() },
    cancel: { isPending: false, mutate: vi.fn() },
    refresh: vi.fn(),
    showLogs: false,
    setShowLogs: vi.fn(),
    openHistory: vi.fn(),
    search: '',
    severity: undefined,
    setSearch: vi.fn(),
    setSeverity: vi.fn(),
    setFindingId: vi.fn(),
    submitted: false,
    ...overrides
  } as unknown as State
}

describe('specialized analysis overview', () => {
  it('applies partial run refreshes independently and uses the latest filter and log callbacks', async () => {
    let currentRun = run
    let current = state(currentRun, { showLogs: true })
    const view = render(
      <AnalysisOverview state={current} stageId="memory-order-analysis" projectId="project-1" />
    )
    const updates: Array<
      [Partial<SpecializedAnalysis>, string | { label: string; value: string }]
    > = [
      [{ findingCount: 8 }, { label: '正式风险', value: '8' }],
      [{ candidateCount: 4 }, { label: '源码候选', value: '4' }],
      [{ diagnosticCount: 3 }, { label: '分析诊断', value: '3' }],
      [{ taskId: 'task-new' }, '日志 task-new'],
      [{ id: 'run-new' }, 'run-new']
    ]
    for (const [patch, expected] of updates) {
      currentRun = { ...currentRun, ...patch }
      current = { ...current, run: currentRun }
      view.rerender(
        <AnalysisOverview state={current} stageId="memory-order-analysis" projectId="project-1" />
      )
      if (typeof expected === 'string') expect(screen.getByText(expected)).toBeInTheDocument()
      else
        expect(
          within(screen.getByText(expected.label).parentElement!).getByText(expected.value)
        ).toBeInTheDocument()
    }
    const nextSearch = vi.fn(),
      nextSeverity = vi.fn(),
      nextSelect = vi.fn(),
      nextRefresh = vi.fn()
    for (const patch of [
      { search: 'cache' },
      { severity: 'HIGH' },
      { setSearch: nextSearch },
      { setSeverity: nextSeverity },
      { setFindingId: nextSelect },
      { refresh: nextRefresh }
    ]) {
      current = { ...current, ...patch }
      view.rerender(
        <AnalysisOverview state={current} stageId="memory-order-analysis" projectId="project-1" />
      )
      expect(screen.getByText('run-new')).toBeInTheDocument()
    }
    expect(screen.getByText('cache:HIGH')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '筛选高风险' }))
    await userEvent.click(screen.getByRole('button', { name: '选择结果' }))
    await userEvent.click(screen.getByRole('button', { name: '日志任务完成' }))
    expect(nextSearch).toHaveBeenCalledWith('publish')
    expect(nextSeverity).toHaveBeenCalledWith('HIGH')
    expect(nextSelect).toHaveBeenCalledWith('finding-run-new')
    expect(nextRefresh).toHaveBeenCalledOnce()
    view.rerender(<AnalysisOverview state={current} stageId="memory-order-analysis" />)
    expect(screen.queryByText('结果列表内容')).not.toBeInTheDocument()
    expect(screen.getByText('日志 task-new')).toBeInTheDocument()
  })

  it('uses execution messages and progress for a live run and permits retry only after readiness recovers', async () => {
    const running = { ...run, status: 'RUNNING' as const }
    const snapshot = latest(running)
    snapshot.execution = {
      id: 'exec',
      taskId: run.taskId,
      status: 'RUNNING',
      errorCode: null,
      errorMessage: null,
      message: '正在解析源码',
      progress: 42,
      createdAt: run.createdAt,
      startedAt: run.startedAt,
      finishedAt: null
    }
    const current = state(running, { latest: { data: snapshot, isLoading: false, isError: false } })
    const view = render(
      <AnalysisOverview state={current} stageId="floating-point-analysis" projectId="project-1" />
    )
    expect(screen.getByText('正在解析源码')).toBeInTheDocument()
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '42')
    await userEvent.click(screen.getByRole('button', { name: '查看日志' }))
    expect(current.setShowLogs).toHaveBeenCalledWith(true)
    await userEvent.click(screen.getByRole('button', { name: '查看历史记录' }))
    expect(current.openHistory).toHaveBeenCalledOnce()
    snapshot.execution = { ...snapshot.execution, status: 'FAILED', errorMessage: '后端诊断消息' }
    const failed = state(
      { ...run, status: 'FAILED', summary: {}, diagnosticCount: 0 },
      { canStart: false, latest: { data: { ...snapshot }, isLoading: false, isError: false } }
    )
    view.rerender(
      <AnalysisOverview state={failed} stageId="floating-point-analysis" projectId="project-1" />
    )
    expect(screen.getByText('后端诊断消息')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新分析' })).not.toBeInTheDocument()
    view.rerender(
      <AnalysisOverview
        state={{ ...failed, canStart: true }}
        stageId="floating-point-analysis"
        projectId="project-1"
      />
    )
    await userEvent.click(screen.getByRole('button', { name: '重新分析' }))
    expect(failed.create.mutate).toHaveBeenCalledOnce()
  })
  it('moves from ready to running to failed without showing stale results', async () => {
    const actor = userEvent.setup()
    const ready = state(null)
    const view = render(
      <AnalysisOverview state={ready} stageId="memory-order-analysis" projectId="project-1" />
    )
    expect(screen.getByText('已满足分析条件')).toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: '启动分析' }))
    expect(ready.create.mutate).toHaveBeenCalledOnce()
    view.rerender(
      <AnalysisOverview
        state={state({ ...run, status: 'RUNNING', currentSourceRevision: true })}
        stageId="memory-order-analysis"
        projectId="project-1"
      />
    )
    expect(screen.getByText('强弱内存序分析正在执行')).toBeInTheDocument()
    expect(screen.queryByText('结果列表内容')).not.toBeInTheDocument()
    view.rerender(
      <AnalysisOverview
        state={state({
          ...run,
          status: 'FAILED',
          currentSourceRevision: false,
          staleReasons: ['源码版本已更新'],
          summary: { error: '分析器退出' },
          diagnosticCount: 2
        })}
        stageId="memory-order-analysis"
        projectId="project-1"
      />
    )
    expect(screen.getByText('该分析基于旧源码')).toBeInTheDocument()
    expect(screen.getByText('分析器退出')).toBeInTheDocument()
    expect(screen.getByText('2 项分析诊断，请查看日志。')).toBeInTheDocument()
    expect(screen.queryByText('结果列表内容')).not.toBeInTheDocument()
  })

  it('keeps partial findings visible with their caution and task log', () => {
    render(
      <AnalysisOverview
        state={state({ ...run, status: 'PARTIAL', findingCount: 2 }, { showLogs: true })}
        stageId="floating-point-analysis"
        projectId="project-1"
      />
    )
    expect(screen.getByText(/部分范围未完成/)).toBeInTheDocument()
    expect(screen.getByText('结果列表内容')).toBeInTheDocument()
    expect(screen.getByText('日志 task')).toBeInTheDocument()
  })

  it('refreshes results, log identity and event handlers when a newer analysis replaces the old run', async () => {
    const actor = userEvent.setup()
    const old = state(run, { showLogs: true })
    const view = render(
      <AnalysisOverview state={old} stageId="memory-order-analysis" projectId="project-1" />
    )
    const next = state(
      {
        ...run,
        id: 'run-next',
        projectId: 'project-next',
        analyzerType: 'FLOATING_POINT',
        verdict: 'no_supported_risk',
        taskId: 'task-next',
        findingCount: 0,
        candidateCount: 0,
        diagnosticCount: 3,
        status: 'COMPLETE',
        startedAt: '2026-10-28T00:00:00Z',
        finishedAt: '2026-10-28T00:01:00Z'
      },
      { showLogs: true, search: 'new-filter', severity: 'LOW' }
    )
    view.rerender(
      <AnalysisOverview state={next} stageId="floating-point-analysis" projectId="project-next" />
    )
    expect(screen.getByText(/本次分析范围内未发现支持的风险/)).toBeInTheDocument()
    expect(screen.queryByText(/1 项分析结果/)).not.toBeInTheDocument()
    expect(screen.queryByText('run')).not.toBeInTheDocument()
    expect(screen.queryByText('日志 task')).not.toBeInTheDocument()
    expect(screen.getByText('日志 task-next')).toBeInTheDocument()
    expect(screen.getByText('new-filter:LOW')).toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: '日志任务完成' }))
    await actor.click(screen.getByRole('button', { name: '筛选高风险' }))
    await actor.click(screen.getByRole('button', { name: '选择结果' }))
    await actor.click(screen.getByRole('button', { name: '重新分析' }))
    expect(next.refresh).toHaveBeenCalledOnce()
    expect(next.setSearch).toHaveBeenCalledWith('publish')
    expect(next.setSeverity).toHaveBeenCalledWith('HIGH')
    expect(next.setFindingId).toHaveBeenCalledWith('finding-run-next')
    expect(next.create.mutate).toHaveBeenCalledOnce()
    expect(old.refresh).not.toHaveBeenCalled()
    expect(old.create.mutate).not.toHaveBeenCalled()
  })

  it('confirms live cancellation but keeps a selected historical run read-only', async () => {
    const actor = userEvent.setup()
    const active = state({ ...run, status: 'QUEUED' })
    const view = render(
      <AnalysisOverview state={active} stageId="memory-order-analysis" projectId="project-1" />
    )
    expect(screen.getByText('强弱内存序分析等待执行')).toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: '取消分析' }))
    await actor.click(await screen.findByRole('button', { name: '继续分析' }))
    expect(active.cancel.mutate).not.toHaveBeenCalled()
    await actor.click(screen.getByRole('button', { name: '取消分析' }))
    await actor.click(await screen.findByRole('button', { name: '确认取消' }))
    expect(active.cancel.mutate).toHaveBeenCalledOnce()
    const history = state(
      { ...run, status: 'RUNNING', taskId: 'historical-task' },
      { runId: 'historical', showLogs: true }
    )
    view.rerender(
      <AnalysisOverview state={history} stageId="floating-point-analysis" projectId="project-1" />
    )
    expect(screen.queryByRole('button', { name: '取消分析' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '查看历史记录' })).not.toBeInTheDocument()
    expect(screen.getByText('日志 historical-task')).toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: '收起日志' }))
    expect(history.setShowLogs).toHaveBeenCalledWith(false)
  })

  it.each(['FAILED', 'CANCELLED', 'TIMEOUT'] as const)(
    'does not offer retries or leak the latest error into a %s historical run',
    (status) => {
      const current = latest(run)
      current.execution = {
        id: 'latest',
        taskId: 'latest-task',
        status: 'FAILED',
        errorMessage: '不相关的最新错误',
        errorCode: null,
        message: null,
        progress: null,
        createdAt: run.createdAt,
        startedAt: null,
        finishedAt: null
      }
      render(
        <AnalysisOverview
          state={state(
            { ...run, status, summary: { error: { internal: true } } },
            { runId: 'historical', latest: { data: current, isLoading: false, isError: false } }
          )}
          stageId="memory-order-analysis"
          projectId="project-1"
        />
      )
      expect(screen.getByText('本次执行未完成，请查看任务日志后重试。')).toBeInTheDocument()
      expect(screen.queryByText('不相关的最新错误')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: '重新分析' })).not.toBeInTheDocument()
      expect(screen.queryByText('结果列表内容')).not.toBeInTheDocument()
    }
  )

  it('retries a failed historical read and renders its stale result without enabling a new analysis', async () => {
    const actor = userEvent.setup()
    const view = render(
      <AnalysisOverview
        state={state(null, {
          runId: 'historical',
          selectedRun: { isLoading: true, isError: false }
        })}
        stageId="memory-order-analysis"
        projectId="project-1"
      />
    )
    expect(screen.getByText('正在加载分析记录')).toBeInTheDocument()
    const failed = state(null, {
      runId: 'historical',
      selectedRun: { isLoading: false, isError: true }
    })
    view.rerender(
      <AnalysisOverview state={failed} stageId="memory-order-analysis" projectId="project-1" />
    )
    await actor.click(screen.getByRole('button', { name: '重新加载' }))
    expect(failed.refresh).toHaveBeenCalledOnce()
    view.rerender(
      <AnalysisOverview
        state={state(
          { ...run, currentSourceRevision: false, staleReasons: [] },
          { runId: 'historical' }
        )}
        stageId="memory-order-analysis"
        projectId="project-1"
      />
    )
    expect(screen.getByText('该分析基于旧源码')).toBeInTheDocument()
    expect(screen.getByText('请针对当前源码重新分析。')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新分析' })).not.toBeInTheDocument()
    expect(screen.getByText('结果列表内容')).toBeInTheDocument()
  })
})
