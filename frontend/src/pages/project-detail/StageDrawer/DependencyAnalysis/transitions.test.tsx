import { fireEvent, screen } from '@testing-library/react'
import type { ComponentProps, ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import { renderWithQueryClient } from '@/test/test-utils'
import type { ProjectDependencyAnalysis } from '@/types/project/dependency-analysis'

import DependencyAnalysis from '.'

const hook = vi.hoisted(() => ({ useDependencyAnalysisDrawer: vi.fn() }))
vi.mock('./useDependencyAnalysisDrawer', () => hook)
vi.mock('../components/StageSurface', () => ({
  default: ({
    children,
    title,
    footer,
    extra,
    onHistory,
    afterOpenChange
  }: {
    children: ReactNode
    title: ReactNode
    footer: ReactNode
    extra: ReactNode
    onHistory?: () => void
    afterOpenChange: (open: boolean) => void
  }) => (
    <div>
      {title}
      {footer}
      {extra}
      {children}
      {onHistory && <button onClick={onHistory}>历史入口</button>}
      <button onClick={() => afterOpenChange(false)}>关闭结束</button>
    </div>
  )
}))
vi.mock('./components/AnalysisResults', () => ({
  default: ({
    analysis,
    projectId,
    readOnly,
    onOpenResult,
    onRerun
  }: {
    analysis: ProjectDependencyAnalysis
    projectId: string
    readOnly?: boolean
    onOpenResult: (id: string) => void
    onRerun?: () => void
  }) => (
    <div>
      分析 {analysis.id} 项目 {projectId} 只读 {String(Boolean(readOnly))}
      <button onClick={() => onOpenResult('result-1')}>打开结果</button>
      {onRerun && <button onClick={onRerun}>重跑依赖分析</button>}
    </div>
  )
}))
vi.mock('./components/AnalysisHistory', () => ({
  default: ({
    onOpenAnalysis
  }: {
    onOpenAnalysis: (analysis: ProjectDependencyAnalysis) => void
  }) => <button onClick={() => onOpenAnalysis(analysis)}>选择历史分析</button>
}))
vi.mock('./components/ResultDetail', () => ({
  ResultDetail: ({ readOnly, onRefetch }: { readOnly: boolean; onRefetch: () => void }) => (
    <button onClick={onRefetch}>重读结果 只读 {String(readOnly)}</button>
  ),
  DependencyResultFooter: ({ projectId }: { projectId: string }) => <div>报告操作 {projectId}</div>
}))

const analysis: ProjectDependencyAnalysis = {
  id: 'analysis-1',
  projectId: 'project-1',
  taskId: 'task-1',
  sourceRevisionId: 'source-1',
  sourceBuildRunId: 'build-1',
  targetEnvironmentId: 'env-1',
  environmentConfigVersion: 1,
  currentEnvironmentConfigVersion: 1,
  currentRuntimeEnvironment: true,
  currentSourceRevision: true,
  status: 'SUCCEEDED',
  summary: {},
  warnings: [],
  createdAt: '2026-10-28T00:00:00Z',
  startedAt: null,
  finishedAt: null
}
type View =
  | { type: 'latest' | 'history' }
  | { type: 'history-analysis'; analysis: ProjectDependencyAnalysis }
  | {
      type: 'history-result' | 'latest-result'
      analysis: ProjectDependencyAnalysis
      resultId: string
    }
function fixture() {
  return {
    view: { type: 'latest' } as View,
    setView: vi.fn(),
    latestAnalysis: analysis,
    latestAnalysisQuery: { data: { result: analysis, execution: null, freshness: 'CURRENT' } },
    readinessQuery: {
      data: {
        prerequisites: ['X86_BUILD', 'TARGET_ENVIRONMENT'].map((key) => ({
          key,
          status: 'SATISFIED',
          details: {}
        }))
      }
    },
    createMutation: { isPending: false, mutate: vi.fn() },
    selectedResultQuery: { isLoading: false, isError: false, data: undefined, refetch: vi.fn() },
    queryClient: { invalidateQueries: vi.fn() },
    analysisKey: ['analysis-history', 'project-1'],
    refetch: vi.fn(),
    isError: false,
    isLoading: false,
    submitted: false
  }
}

describe('dependency analysis selection boundaries', () => {
  it('keeps result navigation attached to the selected analysis and removes report actions for stale or historical results', () => {
    let state = fixture()
    hook.useDependencyAnalysisDrawer.mockImplementation(() => state)
    const props = { projectId: 'project-1', visible: true, onClose: vi.fn() }
    const view = renderWithQueryClient(<DependencyAnalysis {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '打开结果' }))
    expect(state.setView).toHaveBeenCalledWith({
      type: 'latest-result',
      analysis,
      resultId: 'result-1'
    })
    fireEvent.click(screen.getByRole('button', { name: '重跑依赖分析' }))
    expect(state.createMutation.mutate).toHaveBeenCalledOnce()
    const cases: Array<[View, string, View]> = [
      [
        { type: 'latest-result', analysis, resultId: 'result-1' },
        '返回最新分析',
        { type: 'latest' }
      ],
      [
        { type: 'history-result', analysis, resultId: 'result-1' },
        '返回历史分析详情',
        { type: 'history-analysis', analysis }
      ],
      [{ type: 'history-analysis', analysis }, '返回历史记录', { type: 'history' }]
    ]
    for (const [route, back, expected] of cases) {
      state = { ...state, view: route }
      view.rerender(<DependencyAnalysis {...props} />)
      if (route.type === 'latest-result')
        expect(screen.getByText('报告操作 project-1')).toBeInTheDocument()
      else expect(screen.queryByText('报告操作 project-1')).not.toBeInTheDocument()
      if (route.type === 'history-result')
        fireEvent.click(screen.getByRole('button', { name: '重读结果 只读 true' }))
      fireEvent.click(screen.getByRole('button', { name: back }))
      expect(state.setView).toHaveBeenLastCalledWith(expected)
    }
    expect(state.selectedResultQuery.refetch).toHaveBeenCalledOnce()
    state = {
      ...state,
      view: {
        type: 'latest-result',
        analysis: { ...analysis, currentSourceRevision: false },
        resultId: 'result-1'
      }
    }
    view.rerender(<DependencyAnalysis {...props} />)
    expect(screen.queryByText('报告操作 project-1')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重读结果 只读 true' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '关闭结束' }))
    expect(state.setView).toHaveBeenLastCalledWith({ type: 'latest' })
  })

  it('refreshes the correct scope and forwards updated project and stage callbacks', () => {
    let state = fixture()
    hook.useDependencyAnalysisDrawer.mockImplementation(() => state)
    let props: ComponentProps<typeof DependencyAnalysis> = {
      projectId: 'project-1',
      visible: true,
      onClose: vi.fn(),
      onStageChange: vi.fn()
    }
    const view = renderWithQueryClient(<DependencyAnalysis {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '刷新依赖分析信息' }))
    expect(state.refetch).toHaveBeenCalledOnce()
    state = { ...state, view: { type: 'history' } }
    view.rerender(<DependencyAnalysis {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '刷新分析记录' }))
    expect(state.queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: state.analysisKey
    })
    fireEvent.click(screen.getByRole('button', { name: '选择历史分析' }))
    expect(state.setView).toHaveBeenLastCalledWith({ type: 'history-analysis', analysis })
    state = { ...state, view: { type: 'latest' } }
    for (const patch of [
      { projectId: 'project-2' },
      { visible: false },
      {
        stage: {
          id: 'rv-dependency-analysis',
          title: '新依赖分析',
          subtitle: '',
          order: 6,
          status: 'pending' as const
        }
      },
      { onStageChange: vi.fn() }
    ]) {
      props = { ...props, ...patch }
      view.rerender(<DependencyAnalysis {...props} />)
      expect(hook.useDependencyAnalysisDrawer).toHaveBeenLastCalledWith(
        props.projectId,
        props.visible
      )
    }
    expect(screen.getByText('分析 analysis-1 项目 project-2 只读 false')).toBeInTheDocument()
    expect(screen.getByText('新依赖分析')).toBeInTheDocument()
  })
})
