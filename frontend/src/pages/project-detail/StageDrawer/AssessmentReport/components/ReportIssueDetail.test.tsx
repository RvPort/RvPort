import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { renderWithQueryClient } from '@/test/test-utils'

import { projectQueryKeys } from '../../../queryKeys'
import ReportIssueDetail from './ReportIssueDetail'

const { getInput, getFinding, getCodeContext, getDependency } = vi.hoisted(() => ({
  getInput: vi.fn(),
  getFinding: vi.fn(),
  getCodeContext: vi.fn(),
  getDependency: vi.fn()
}))

vi.mock('@/api/project/migration-input', () => ({ getProjectMigrationInput: getInput }))
vi.mock('@/api/project/knowledge-base', () => ({
  getProjectKnowledgeBaseFinding: getFinding,
  getProjectKnowledgeBaseFindingCodeContext: getCodeContext
}))
vi.mock('@/api/project/dependency-analysis', () => ({
  getProjectDependencyAnalysisResult: getDependency
}))
vi.mock('@/components/CodeMirrorEditor', () => ({
  default: ({ ariaLabel, value }: { ariaLabel: string; value: string }) => (
    <pre aria-label={ariaLabel}>{value}</pre>
  )
}))
vi.mock('../../components/FindingDetail/index', () => ({
  FindingDetail: ({
    codeContextError,
    codeContextLoading,
    detail,
    loading,
    showMigrationInputStatus
  }: {
    codeContextError: boolean
    codeContextLoading: boolean
    detail?: { codeContext?: { lines: string[] }; id: string }
    loading: boolean
    showMigrationInputStatus: boolean
  }) => (
    <div data-testid="finding-detail">
      {JSON.stringify({
        codeContextError,
        codeContextLoading,
        detail,
        loading,
        showMigrationInputStatus
      })}
    </div>
  )
}))
vi.mock('../../components/DependencyResultDetail/index', () => ({
  DependencyResultDetailContent: ({ result }: { result: { id: string } }) => (
    <div>依赖详情 {result.id}</div>
  )
}))

function input(sourceType: string | null, sourceRefId: string | null = 'source-1') {
  return {
    reference: { sourceType, sourceRefId, issueTitle: '迁移风险' },
    sourceDetail: { evidence: 'src/main.cpp:12' }
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  getInput.mockResolvedValue(input('OTHER'))
  getFinding.mockResolvedValue({ id: 'finding-1', filePath: 'src/main.cpp', lineStart: 12 })
  getCodeContext.mockResolvedValue({ lines: ['source line'] })
  getDependency.mockResolvedValue({ id: 'dependency-1' })
})

describe('report issue source detail', () => {
  it('refreshes generic snapshot fields independently and binds a newly selected input to its own finding', async () => {
    let snapshot = input('OTHER')
    getInput.mockResolvedValue(snapshot)
    const view = renderWithQueryClient(<ReportIssueDetail migrationInputId="input-1" />)
    await screen.findByText('迁移风险')
    snapshot = { ...snapshot, reference: { ...snapshot.reference, issueTitle: '更新风险标题' } }
    act(() => view.queryClient.setQueryData(projectQueryKeys.migrationInput('input-1'), snapshot))
    expect(await screen.findByText('更新风险标题')).toBeInTheDocument()
    snapshot = { ...snapshot, sourceDetail: { evidence: 'src/new.cpp:24' } }
    act(() => view.queryClient.setQueryData(projectQueryKeys.migrationInput('input-1'), snapshot))
    await waitFor(() =>
      expect(screen.getByLabelText('报告问题来源数据')).toHaveTextContent('src/new.cpp:24')
    )
    getInput.mockResolvedValue(input('KB_FINDING', 'finding-next'))
    let resolveFinding!: (finding: {
      id: string
      filePath: string | null
      lineStart: number | null
    }) => void
    getFinding.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveFinding = resolve
        })
    )
    view.rerender(<ReportIssueDetail migrationInputId="input-2" />)
    await waitFor(() => expect(getFinding).toHaveBeenCalledWith('finding-next'))
    expect(screen.getByTestId('finding-detail')).toHaveTextContent('"loading":true')
    expect(screen.queryByText('更新风险标题')).not.toBeInTheDocument()
    await act(async () => resolveFinding({ id: 'finding-next', filePath: null, lineStart: null }))
    await waitFor(() =>
      expect(screen.getByTestId('finding-detail')).toHaveTextContent('finding-next')
    )
    expect(getCodeContext).not.toHaveBeenCalled()
    act(() =>
      view.queryClient.setQueryData(projectQueryKeys.knowledgeBaseFinding('finding-next'), {
        id: 'finding-next',
        filePath: 'src/new.cpp',
        lineStart: null
      })
    )
    await waitFor(() =>
      expect(screen.getByTestId('finding-detail')).toHaveTextContent('src/new.cpp')
    )
    expect(getCodeContext).not.toHaveBeenCalled()
    act(() =>
      view.queryClient.setQueryData(projectQueryKeys.knowledgeBaseFinding('finding-next'), {
        id: 'finding-next',
        filePath: 'src/new.cpp',
        lineStart: 24
      })
    )
    await waitFor(() => expect(getCodeContext).toHaveBeenCalledWith('finding-next'))
    expect(getDependency).not.toHaveBeenCalled()
  })
  it('shows the loading state and retries a failed input request', async () => {
    const user = userEvent.setup()
    getInput.mockImplementationOnce(() => new Promise(() => {}))
    const loading = renderWithQueryClient(<ReportIssueDetail migrationInputId="input-1" />)
    expect(screen.getByText('正在加载问题详情')).toBeInTheDocument()
    loading.unmount()

    getInput.mockRejectedValueOnce(new Error('unavailable'))
    renderWithQueryClient(<ReportIssueDetail migrationInputId="input-2" />)
    expect(await screen.findByText('报告问题详情加载失败')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重新加载' }))
    expect(await screen.findByLabelText('报告问题来源数据')).toHaveTextContent('src/main.cpp:12')
    expect(getInput).toHaveBeenCalledWith('input-2')
  })

  it('shows generic source evidence when the report input has no specialized detail', async () => {
    getInput.mockResolvedValue(input(null, null))
    renderWithQueryClient(<ReportIssueDetail migrationInputId="input-1" />)
    expect(await screen.findByText('迁移风险')).toBeInTheDocument()
    expect(screen.getByText('--')).toBeInTheDocument()
    expect(screen.getByLabelText('报告问题来源数据')).toHaveTextContent('src/main.cpp:12')
    expect(getFinding).not.toHaveBeenCalled()
    expect(getDependency).not.toHaveBeenCalled()
  })

  it('loads a knowledge-base finding and its source context as read-only evidence', async () => {
    getInput.mockResolvedValue(input('KB_FINDING', 'finding-1'))
    renderWithQueryClient(<ReportIssueDetail migrationInputId="input-1" />)
    await waitFor(() => expect(getCodeContext).toHaveBeenCalledWith('finding-1'))
    await waitFor(() =>
      expect(screen.getByTestId('finding-detail')).toHaveTextContent('source line')
    )
    expect(screen.getByTestId('finding-detail')).toHaveTextContent(
      '"showMigrationInputStatus":false'
    )
    expect(getDependency).not.toHaveBeenCalled()
  })

  it('reports a finding failure and allows retry without querying unrelated sources', async () => {
    const user = userEvent.setup()
    getInput.mockResolvedValue(input('KB_FINDING', 'finding-1'))
    getFinding.mockRejectedValueOnce(new Error('finding unavailable'))
    renderWithQueryClient(<ReportIssueDetail migrationInputId="input-1" />)
    expect(await screen.findByText('知识库问题详情加载失败')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重新加载' }))
    expect(await screen.findByTestId('finding-detail')).toHaveTextContent('finding-1')
    expect(getDependency).not.toHaveBeenCalled()
  })

  it('keeps the finding visible when its source context fails', async () => {
    getInput.mockResolvedValue(input('KB_FINDING', 'finding-1'))
    getCodeContext.mockRejectedValue(new Error('context unavailable'))
    renderWithQueryClient(<ReportIssueDetail migrationInputId="input-1" />)
    await waitFor(() =>
      expect(screen.getByTestId('finding-detail')).toHaveTextContent('"codeContextError":true')
    )
    expect(screen.getByTestId('finding-detail')).toHaveTextContent('finding-1')
  })

  it('does not fetch a finding when its reference is missing', async () => {
    getInput.mockResolvedValue(input('KB_FINDING', null))
    renderWithQueryClient(<ReportIssueDetail migrationInputId="input-1" />)
    expect(await screen.findByTestId('finding-detail')).toHaveTextContent('"loading":false')
    expect(getFinding).not.toHaveBeenCalled()
    expect(getCodeContext).not.toHaveBeenCalled()
  })

  it('loads a dependency result and retries a failed dependency request', async () => {
    const user = userEvent.setup()
    getInput.mockResolvedValue(input('DEPENDENCY_RESULT', 'dependency-1'))
    getDependency.mockRejectedValueOnce(new Error('dependency unavailable'))
    renderWithQueryClient(<ReportIssueDetail migrationInputId="input-1" />)
    expect(await screen.findByText('依赖问题详情加载失败')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重新加载' }))
    expect(await screen.findByText('依赖详情 dependency-1')).toBeInTheDocument()
    expect(getFinding).not.toHaveBeenCalled()
  })
})
