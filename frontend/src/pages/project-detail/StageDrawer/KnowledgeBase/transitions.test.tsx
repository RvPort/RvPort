import { fireEvent, screen } from '@testing-library/react'
import { Form } from 'antd'
import type { ComponentProps, ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import { renderWithQueryClient } from '@/test/test-utils'
import type { ProjectKnowledgeBaseScan } from '@/types/project/knowledge-base'

import Kb from '.'
import type { KnowledgeBaseView } from './types'

const hook = vi.hoisted(() => ({ useKnowledgeBaseDrawer: vi.fn() }))
vi.mock('./useKnowledgeBaseDrawer', () => hook)
vi.mock('../components/StageSurface', () => ({
  default: ({
    children,
    extra,
    title,
    footer,
    onHistory,
    afterOpenChange
  }: {
    children: ReactNode
    extra: ReactNode
    title: ReactNode
    footer: ReactNode
    onHistory?: () => void
    afterOpenChange: (open: boolean) => void
  }) => (
    <div>
      {title}
      {extra}
      {children}
      {footer}
      {onHistory && <button onClick={onHistory}>历史入口</button>}
      <button onClick={() => afterOpenChange(false)}>结束关闭</button>
    </div>
  )
}))
vi.mock('./components/ScanResults', () => ({
  ScanResults: ({
    scan,
    projectId,
    readOnly,
    onOpenFinding,
    onShowScanInfo,
    onRescan,
    aiSuggestion
  }: {
    scan: ProjectKnowledgeBaseScan
    projectId: string
    readOnly?: boolean
    onOpenFinding: (id: string) => void
    onShowScanInfo: () => void
    onRescan?: () => void
    aiSuggestion?: { onGenerate: () => void; onRetry: () => void; loading: boolean; error: boolean }
  }) => (
    <div>
      扫描 {scan.id} 项目 {projectId}
      {readOnly && <span>只读扫描</span>}
      <button onClick={() => onOpenFinding('finding-1')}>打开命中</button>
      <button onClick={onShowScanInfo}>扫描产物</button>
      {onRescan && <button onClick={onRescan}>重新扫描入口</button>}
      {aiSuggestion && (
        <>
          <span>
            AI {String(aiSuggestion.loading)} {String(aiSuggestion.error)}
          </span>
          <button onClick={aiSuggestion.onGenerate}>生成建议</button>
          <button onClick={aiSuggestion.onRetry}>重试建议</button>
        </>
      )}
    </div>
  )
}))
vi.mock('./components/ScanHistory', () => ({
  ScanHistory: ({ onOpenScan }: { onOpenScan: (id: string) => void }) => (
    <button onClick={() => onOpenScan('old-scan')}>打开旧扫描</button>
  )
}))
vi.mock('./components/ScanConfig', () => ({
  ScanConfig: ({
    sourceReady,
    x86BuildReady,
    onReady,
    onManageSource,
    onManageX86Build
  }: {
    sourceReady: boolean
    x86BuildReady: boolean
    onReady: () => void
    onManageSource: () => void
    onManageX86Build: () => void
  }) => (
    <div>
      配置 {String(sourceReady)} {String(x86BuildReady)}
      <button onClick={onReady}>配置已加载</button>
      <button onClick={onManageSource}>管理源码</button>
      <button onClick={onManageX86Build}>管理构建</button>
    </div>
  )
}))
vi.mock('./components/ScanInfo', () => ({
  ScanInfo: ({
    error,
    loading,
    onRetry
  }: {
    error: boolean
    loading: boolean
    onRetry: () => void
  }) => (
    <div>
      产物状态 {String(loading)} {String(error)}
      <button onClick={onRetry}>重试产物</button>
    </div>
  )
}))
vi.mock('../components/FindingDetail/index', () => ({
  FindingDetail: ({
    loading,
    codeContextLoading,
    codeContextError,
    showMigrationInputStatus
  }: {
    loading: boolean
    codeContextLoading: boolean
    codeContextError: boolean
    showMigrationInputStatus: boolean
  }) => (
    <div>
      详情 {String(loading)} {String(codeContextLoading)} {String(codeContextError)} 只读{' '}
      {String(showMigrationInputStatus)}
    </div>
  )
}))

const scan: ProjectKnowledgeBaseScan = {
  id: 'scan-1',
  taskId: 'task-1',
  sourceRevisionId: 'source-1',
  kbVersion: '1',
  scope: { mode: 'ALL', paths: [] },
  status: 'SUCCEEDED',
  summary: {},
  startedAt: null,
  finishedAt: null
}
function fixture() {
  return {
    view: { type: 'results' } as KnowledgeBaseView,
    scan: scan as ProjectKnowledgeBaseScan | null,
    latestScan: { result: scan, freshness: 'CURRENT', execution: null },
    readinessQuery: { data: { prerequisites: [] } },
    sourceReady: true,
    x86BuildReady: true,
    isReadinessLoading: false,
    isStageError: false,
    isStageLoading: false,
    isCreating: false,
    submitted: false,
    isRecordLoading: false,
    isRecordError: false,
    isFindingLoading: false,
    isFindingError: false,
    isCodeContextLoading: false,
    isCodeContextError: false,
    isScanArtifactsLoading: false,
    isScanArtifactsError: false,
    isHistoryFetching: false,
    isAiSuggestionLoading: false,
    isAiSuggestionError: false,
    isCreatingAiSuggestion: false,
    scanArtifacts: [],
    findingDetail: undefined,
    setView: vi.fn(),
    showConfig: vi.fn(),
    showHistory: vi.fn(),
    showRecord: vi.fn(),
    showResults: vi.fn(),
    showScanInfo: vi.fn(),
    clearScanCache: vi.fn(),
    refetchRecord: vi.fn(),
    refetchFinding: vi.fn(),
    refetchScanInfo: vi.fn(),
    refetchStage: vi.fn(),
    refreshHistory: vi.fn(),
    refetchAiSuggestion: vi.fn(),
    handleCreateScan: vi.fn(),
    handleCreateAiSuggestion: vi.fn()
  }
}

describe('knowledge scan view routing', () => {
  it('routes current and historical records to their own parents and keeps historical findings read-only', async () => {
    let state = fixture()
    hook.useKnowledgeBaseDrawer.mockImplementation(function useDrawer() {
      const [form] = Form.useForm()
      return { ...state, form }
    })
    const view = renderWithQueryClient(<Kb visible projectId="project-1" onClose={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '打开命中' }))
    expect(state.setView).toHaveBeenCalledWith({
      type: 'detail',
      findingId: 'finding-1',
      scanId: 'scan-1',
      returnTo: 'results'
    })
    fireEvent.click(screen.getByRole('button', { name: '扫描产物' }))
    expect(state.showScanInfo).toHaveBeenCalledWith('scan-1')
    fireEvent.click(screen.getByRole('button', { name: '生成建议' }))
    fireEvent.click(screen.getByRole('button', { name: '重试建议' }))
    expect(state.handleCreateAiSuggestion).toHaveBeenCalledOnce()
    expect(state.refetchAiSuggestion).toHaveBeenCalledOnce()
    const cases: Array<[KnowledgeBaseView, string, 'showResults' | 'showRecord' | 'showHistory']> =
      [
        [{ type: 'record', scanId: 'scan-1' }, '返回扫描记录', 'showHistory'],
        [
          { type: 'detail', scanId: 'scan-1', findingId: 'finding-1', returnTo: 'record' },
          '返回扫描记录概览',
          'showRecord'
        ],
        [
          { type: 'detail', scanId: 'scan-1', findingId: 'finding-1', returnTo: 'results' },
          '返回知识库扫描',
          'showResults'
        ],
        [
          { type: 'scan-info', scanId: 'scan-1', returnTo: 'history' },
          '返回扫描记录',
          'showHistory'
        ],
        [
          { type: 'scan-info', scanId: 'scan-1', returnTo: 'record' },
          '返回扫描记录概览',
          'showRecord'
        ],
        [
          { type: 'scan-info', scanId: 'scan-1', returnTo: 'results' },
          '返回知识库扫描',
          'showResults'
        ]
      ]
    for (const [route, back, action] of cases) {
      state = { ...state, view: route }
      view.rerender(<Kb visible projectId="project-1" onClose={vi.fn()} />)
      await screen.findByRole('button', { name: back })
      if (route.type === 'record') {
        expect(screen.getByText('只读扫描')).toBeInTheDocument()
        fireEvent.click(screen.getByRole('button', { name: '打开命中' }))
        expect(state.setView).toHaveBeenLastCalledWith(
          expect.objectContaining({ returnTo: 'record' })
        )
      }
      if (route.type === 'detail')
        expect(
          await screen.findByText(
            `详情 false false false 只读 ${String(route.returnTo !== 'results')}`
          )
        ).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: back }))
      expect(state[action]).toHaveBeenCalled()
      if (action === 'showRecord') expect(state.showRecord).toHaveBeenLastCalledWith('scan-1')
    }
    state = { ...state, view: { type: 'history' } }
    view.rerender(<Kb visible projectId="project-1" onClose={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: '打开旧扫描' }))
    expect(state.showRecord).toHaveBeenLastCalledWith('old-scan')
    fireEvent.click(screen.getByRole('button', { name: '刷新扫描记录' }))
    expect(state.refreshHistory).toHaveBeenCalledOnce()
  })

  it('binds ready configuration actions, propagates prerequisite navigation and clears transient state after close', async () => {
    let state = fixture()
    hook.useKnowledgeBaseDrawer.mockImplementation(function useDrawer() {
      const [form] = Form.useForm()
      return { ...state, form }
    })
    let props: ComponentProps<typeof Kb> = {
      visible: true,
      projectId: 'project-1',
      onClose: vi.fn(),
      onStageChange: vi.fn(),
      onDirtyChange: vi.fn()
    }
    const view = renderWithQueryClient(<Kb {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '重新扫描入口' }))
    expect(state.showConfig).toHaveBeenCalledOnce()
    state = {
      ...state,
      view: { type: 'config' },
      sourceReady: false,
      x86BuildReady: false,
      isReadinessLoading: true
    }
    view.rerender(<Kb {...props} />)
    expect(await screen.findByText('配置 true true')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '配置已加载' }))
    expect(screen.getByRole('button', { name: '开始扫描' })).toBeDisabled()
    state = { ...state, sourceReady: true, x86BuildReady: true, isReadinessLoading: false }
    view.rerender(<Kb {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '开始扫描' }))
    expect(state.handleCreateScan).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '管理源码' }))
    fireEvent.click(screen.getByRole('button', { name: '管理构建' }))
    expect(props.onStageChange).toHaveBeenCalledWith('assets')
    expect(props.onStageChange).toHaveBeenCalledWith('x86-build')
    fireEvent.click(screen.getByRole('button', { name: '结束关闭' }))
    expect(state.clearScanCache).toHaveBeenCalledOnce()
    expect(state.setView).toHaveBeenLastCalledWith({ type: 'results' })
    state = { ...state, view: { type: 'results' }, isAiSuggestionLoading: true }
    for (const patch of [
      { projectId: 'project-2' },
      { onDirtyChange: vi.fn() },
      { visible: false },
      {
        stage: {
          id: 'kb',
          order: 4,
          title: '新扫描工作区',
          subtitle: '',
          status: 'pending' as const
        }
      }
    ]) {
      props = { ...props, ...patch }
      view.rerender(<Kb {...props} />)
      expect(hook.useKnowledgeBaseDrawer).toHaveBeenLastCalledWith(
        props.projectId,
        props.visible,
        props.onDirtyChange
      )
    }
    expect(screen.getByText('扫描 scan-1 项目 project-2')).toBeInTheDocument()
    expect(screen.getByText('AI true false')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '刷新知识库扫描' }))
    expect(state.refetchStage).toHaveBeenCalledOnce()
  })
})
