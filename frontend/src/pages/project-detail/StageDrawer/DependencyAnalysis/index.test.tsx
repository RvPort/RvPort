import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { renderWithStrictProjectOverview as renderWithProjectOverview } from '@/test/strict-project-overview-utils'

import DependencyAnalysis from '.'

const {
  createProjectDependencyAutoFix,
  createProjectDependencyAnalysis,
  getProjectDependencyAutoFixCandidates,
  getProjectDependencyAnalyses,
  getProjectDependencyAnalysisArtifacts,
  getProjectDependencyAnalysisLatest,
  getProjectDependencyAnalysisResult,
  getProjectStageReadiness,
  getProjectMigrationInputs,
  importProjectMigrationInputs,
  updateProjectMigrationInput
} = vi.hoisted(() => ({
  createProjectDependencyAutoFix: vi.fn(),
  createProjectDependencyAnalysis: vi.fn(),
  getProjectDependencyAutoFixCandidates: vi.fn(),
  getProjectDependencyAnalyses: vi.fn(),
  getProjectDependencyAnalysisArtifacts: vi.fn(),
  getProjectDependencyAnalysisLatest: vi.fn(),
  getProjectDependencyAnalysisResult: vi.fn(),
  getProjectStageReadiness: vi.fn(),
  getProjectMigrationInputs: vi.fn(),
  importProjectMigrationInputs: vi.fn(),
  updateProjectMigrationInput: vi.fn()
}))

vi.mock('@/api/project/dependency-analysis', () => ({
  createProjectDependencyAutoFix,
  createProjectDependencyAnalysis,
  getProjectDependencyAutoFixCandidates,
  getProjectDependencyAnalyses,
  getProjectDependencyAnalysisArtifact: vi.fn(),
  getProjectDependencyAnalysisArtifacts,
  getProjectDependencyAnalysisLatest,
  getProjectDependencyAnalysisResult,
  getProjectDependencyAnalysisResults: vi.fn()
}))

vi.mock('@/api/project/workflow', () => ({ getProjectStageReadiness }))
vi.mock('@/api/project/migration-input', () => ({
  getProjectMigrationInputs,
  importProjectMigrationInputs,
  updateProjectMigrationInput
}))

vi.mock('@/components/BaseDrawer', () => ({
  default: ({
    children,
    extra,
    footer,
    title
  }: {
    children: React.ReactNode
    extra?: React.ReactNode
    footer?: React.ReactNode
    title: React.ReactNode
  }) => (
    <div>
      <h2>{title}</h2>
      {extra}
      {children}
      {footer}
    </div>
  )
}))

vi.mock('@/components/BaseTable', () => ({
  default: ({
    columns,
    queryKey
  }: {
    columns?: Array<{
      title?: React.ReactNode
      render?: (value: string, record: { id: string; [key: string]: unknown }) => React.ReactNode
    }>
    queryKey?: unknown[]
  }) => (
    <div data-testid="dependency-records">
      {queryKey?.[0] === 'dependency-analysis-results' ? (
        <>
          {columns?.[0]?.render?.('zlib', { id: 'result-1' })}
          {columns?.find((column) => column.title === '操作')?.render?.('', { id: 'result-1' })}
        </>
      ) : queryKey?.[0] === 'project-dependency-analyses' ? (
        columns
          ?.find((column) => column.title === '操作')
          ?.render?.('', {
            createdAt: '2026-09-01T07:00:00Z',
            currentRuntimeEnvironment: true,
            currentSourceRevision: true,
            finishedAt: '2026-09-01T07:01:00Z',
            id: 'analysis-history-1',
            projectId: 'project-1',
            sourceRevisionId: 'source-1',
            startedAt: '2026-09-01T07:00:10Z',
            status: 'SUCCEEDED',
            summary: { total: 1 },
            taskId: 'task-history-1'
          })
      ) : null}
    </div>
  )
}))

function currentLatest(result: Record<string, unknown>) {
  return {
    context: { runtimeEnvironmentVersion: 3, sourceRevisionId: 'source-1' },
    execution: {
      createdAt: '2026-09-01T08:00:00Z',
      errorCode: null,
      errorMessage: null,
      finishedAt: '2026-09-01T08:01:00Z',
      id: 'analysis-1',
      message: null,
      progress: 100,
      startedAt: '2026-09-01T08:00:10Z',
      status: result.status,
      taskId: 'task-1'
    },
    freshness: 'CURRENT',
    result
  }
}

function mockReadiness({
  targetReasonCode = 'TARGET_ENVIRONMENT_READY',
  targetStatus = 'SATISFIED',
  x86ReasonCode = 'X86_BUILD_SUCCEEDED',
  x86Status = 'SATISFIED'
} = {}) {
  getProjectStageReadiness.mockResolvedValue({
    context: { runtimeEnvironmentVersion: 3, sourceRevisionId: 'source-1' },
    prerequisites: [
      {
        details: {},
        key: 'X86_BUILD',
        reasonCode: x86ReasonCode,
        referenceId: x86Status === 'SATISFIED' ? 'build-1' : null,
        status: x86Status
      },
      {
        details: {},
        key: 'TARGET_ENVIRONMENT',
        reasonCode: targetReasonCode,
        referenceId: targetStatus === 'SATISFIED' ? 'env-target' : null,
        status: targetStatus
      }
    ],
    stageId: 'rv-dependency-analysis'
  })
}

describe('DependencyAnalysis', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockReadiness()
    getProjectDependencyAnalyses.mockResolvedValue({ items: [], page: 1, pageSize: 1, total: 0 })
    getProjectDependencyAnalysisLatest.mockResolvedValue({
      context: { runtimeEnvironmentVersion: 3, sourceRevisionId: 'source-1' },
      execution: null,
      freshness: 'EMPTY',
      result: null
    })
    getProjectMigrationInputs.mockResolvedValue({ items: [], page: 1, pageSize: 200, total: 0 })
    getProjectDependencyAnalysisArtifacts.mockResolvedValue([])
    getProjectDependencyAutoFixCandidates.mockResolvedValue({
      hasMore: false,
      items: [],
      page: 1,
      pageSize: 200,
      total: 0
    })
    createProjectDependencyAutoFix.mockResolvedValue({
      autoFixRunId: 'fix-1',
      selectedCount: 1,
      taskId: 'task-fix-1'
    })
    createProjectDependencyAnalysis.mockResolvedValue({
      analysisRunId: 'analysis-1',
      sourceBuildRunId: 'build-1',
      sourceRevisionId: 'source-1',
      taskId: 'task-1'
    })
    getProjectDependencyAnalysisResult.mockResolvedValue({
      confidence: 'HIGH',
      dependencyName: 'zlib',
      dependencyType: 'LINK_LIBRARY',
      dependencyVersion: '1.2.13',
      evidence: {},
      id: 'result-1',
      migrationInputRefId: 'migration-input-1',
      migrationInputStatus: 'CANDIDATE',
      riskLevel: 'HIGH',
      targetMatchStatus: 'MISSING'
    })
    updateProjectMigrationInput.mockResolvedValue({
      id: 'migration-input-1',
      status: 'CONFIRMED'
    })
  })

  it('shows its own prerequisite guidance until a current successful x86 build is available', async () => {
    mockReadiness({
      targetReasonCode: 'TARGET_ENVIRONMENT_MISSING',
      targetStatus: 'MISSING',
      x86ReasonCode: 'ACTIVE_SOURCE_MISSING',
      x86Status: 'MISSING'
    })
    renderWithProjectOverview(
      <DependencyAnalysis
        onStageChange={vi.fn()}
        projectId="project-1"
        stage={{
          id: 'rv-dependency-analysis',
          order: 9,
          status: 'pending',
          subtitle: '',
          title: '依赖分析'
        }}
        visible
        onClose={vi.fn()}
      />
    )
    expect(await screen.findByText('暂不能执行依赖分析')).toBeInTheDocument()
    expect(screen.getByText('请完成下方未满足的前置条件。')).toBeInTheDocument()
    expect(screen.getByText('尚无成功构建')).toBeInTheDocument()
    expect(screen.getByText('未满足')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '前往 x86 构建' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '前往环境管理' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '执行依赖分析' })).not.toBeInTheDocument()
    expect(createProjectDependencyAnalysis).not.toHaveBeenCalled()
  })

  it('refreshes prerequisites only when requested by the user', async () => {
    renderWithProjectOverview(
      <DependencyAnalysis
        onStageChange={vi.fn()}
        projectId="project-1"
        stage={{
          id: 'rv-dependency-analysis',
          order: 9,
          status: 'pending',
          subtitle: '',
          title: '依赖分析'
        }}
        visible
        onClose={vi.fn()}
      />
    )

    await userEvent.click(screen.getByRole('button', { name: /刷新/ }))
    expect(getProjectStageReadiness).toHaveBeenCalledTimes(2)
    expect(getProjectDependencyAnalysisLatest).toHaveBeenCalledTimes(2)
  })

  it('shows the latest analysis result first and opens history on demand', async () => {
    const { queryClient } = renderWithProjectOverview(
      <DependencyAnalysis
        onStageChange={vi.fn()}
        projectId="project-1"
        stage={{
          id: 'rv-dependency-analysis',
          order: 9,
          status: 'pending',
          subtitle: '',
          title: '依赖分析'
        }}
        visible
        onClose={vi.fn()}
      />
    )
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')

    expect(await screen.findByText('已满足依赖分析条件')).toBeInTheDocument()
    expect(screen.getByText('x86 构建')).toBeInTheDocument()
    expect(screen.getByText('RISC-V 目标环境')).toBeInTheDocument()
    expect(screen.getByText('已完成')).toBeInTheDocument()
    expect(screen.getByText('已满足')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '查看历史记录' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '分析输入' })).not.toBeInTheDocument()
    expect(getProjectDependencyAnalyses).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: '执行依赖分析' }))
    expect(createProjectDependencyAnalysis).toHaveBeenCalledWith('project-1', {
      sourceRevisionId: 'source-1'
    })
    expect(invalidateQueries).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: '查看历史记录' }))
    expect(screen.getByText('分析记录')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '返回当前概览' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '刷新分析记录' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '查看最新结果' })).not.toBeInTheDocument()

    await queryClient.invalidateQueries({
      queryKey: ['project-dependency-analyses', 'project-1']
    })
    expect(getProjectDependencyAnalysisLatest).toHaveBeenCalledTimes(1)
    expect(getProjectStageReadiness).toHaveBeenCalledTimes(1)

    await userEvent.click(screen.getByRole('button', { name: '返回当前概览' }))
    expect(screen.getByRole('button', { name: '查看历史记录' })).toBeInTheDocument()
  })

  it('shows the latest failed execution instead of an empty result summary', async () => {
    getProjectDependencyAnalysisLatest.mockResolvedValue(
      currentLatest({
        createdAt: '2026-09-01T08:00:00Z',
        finishedAt: '2026-09-01T08:01:00Z',
        id: 'analysis-1',
        projectId: 'project-1',
        sourceRevisionId: 'source-1',
        startedAt: '2026-09-01T08:00:10Z',
        status: 'FAILED',
        summary: { error: '依赖解析器执行失败' },
        taskId: 'task-1'
      })
    )

    renderWithProjectOverview(
      <DependencyAnalysis onStageChange={vi.fn()} projectId="project-1" visible onClose={vi.fn()} />
    )

    expect(await screen.findByText('依赖分析执行失败')).toBeInTheDocument()
    expect(screen.getByText('依赖解析器执行失败')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新执行依赖分析' })).toHaveClass('ant-btn-primary')
    expect(screen.getByRole('button', { name: '查看历史记录' })).toHaveClass('ant-btn-link')
    expect(screen.queryByText('分析依赖')).not.toBeInTheDocument()
  })

  it('creates an automatic dependency fix from backend-recommended candidates', async () => {
    getProjectDependencyAnalysisLatest.mockResolvedValue(
      currentLatest({
        createdAt: '2026-09-01T08:00:00Z',
        finishedAt: '2026-09-01T08:01:00Z',
        id: 'analysis-1',
        projectId: 'project-1',
        sourceRevisionId: 'source-1',
        startedAt: '2026-09-01T08:00:10Z',
        status: 'SUCCEEDED',
        summary: { total: 1 },
        taskId: 'task-1'
      })
    )
    getProjectDependencyAutoFixCandidates.mockResolvedValue({
      hasMore: false,
      items: [
        {
          defaultSelected: true,
          dependencyName: 'zlib',
          dependencyType: 'LINK_LIBRARY',
          dependencyVersion: '1.2.13',
          installPackageName: 'zlib-devel',
          installVersionConstraint: '1.2',
          reason: '目标环境缺少对应开发包',
          resultItemId: 'result-1',
          riskLevel: 'HIGH',
          selectable: true,
          targetMatchStatus: 'MISSING'
        }
      ],
      page: 1,
      pageSize: 200,
      total: 1
    })

    renderWithProjectOverview(
      <DependencyAnalysis onStageChange={vi.fn()} projectId="project-1" visible onClose={vi.fn()} />
    )

    const autoFix = await screen.findByRole('button', { name: /自动修复依赖/ })
    const rerun = screen.getByRole('button', { name: '重新分析' })
    const history = screen.getByRole('button', { name: '查看历史记录' })
    expect(autoFix).toHaveClass('ant-btn-primary')
    expect(rerun).toHaveClass('ant-btn-default')
    expect(history).toHaveClass('ant-btn-link')
    expect(autoFix.compareDocumentPosition(rerun) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(history.compareDocumentPosition(rerun) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    await userEvent.click(autoFix)
    expect(await screen.findAllByText('zlib')).not.toHaveLength(0)
    await userEvent.click(await screen.findByRole('button', { name: '修复选中的依赖（1）' }))

    expect(createProjectDependencyAutoFix).toHaveBeenCalledWith('analysis-1', {
      rerunDependencyAnalysis: true,
      resultItemIds: ['result-1']
    })
  })

  it('updates report inclusion from the dependency detail footer', async () => {
    getProjectDependencyAnalysisLatest.mockResolvedValue(
      currentLatest({
        createdAt: '2026-09-01T08:00:00Z',
        currentRuntimeEnvironment: true,
        currentSourceRevision: true,
        finishedAt: '2026-09-01T08:01:00Z',
        id: 'analysis-1',
        projectId: 'project-1',
        sourceRevisionId: 'source-1',
        startedAt: '2026-09-01T08:00:10Z',
        status: 'SUCCEEDED',
        summary: { total: 1 },
        taskId: 'task-1'
      })
    )

    renderWithProjectOverview(
      <DependencyAnalysis onStageChange={vi.fn()} projectId="project-1" visible onClose={vi.fn()} />
    )

    await userEvent.click(await screen.findByRole('button', { name: 'zlib' }))
    expect(await screen.findByText('依赖结果详情')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '返回最新分析' })).toBeInTheDocument()
    expect(screen.getAllByText('未纳入报告')).toHaveLength(1)
    expect(screen.getByRole('button', { name: '纳入报告' })).toHaveClass('ant-btn-primary')

    await userEvent.click(screen.getByRole('button', { name: '纳入报告' }))
    expect(updateProjectMigrationInput).toHaveBeenCalledWith('migration-input-1', {
      status: 'CONFIRMED'
    })

    await userEvent.click(screen.getByRole('button', { name: '返回最新分析' }))
    expect(await screen.findByText('分析概览')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '返回最新分析' })).not.toBeInTheDocument()
  })

  it('does not present a stale dependency result as the current result', async () => {
    getProjectDependencyAnalysisLatest.mockResolvedValue({
      context: { runtimeEnvironmentVersion: 3, sourceRevisionId: 'source-1' },
      execution: null,
      freshness: 'STALE',
      result: null
    })

    renderWithProjectOverview(
      <DependencyAnalysis onStageChange={vi.fn()} projectId="project-1" visible onClose={vi.fn()} />
    )

    expect(await screen.findByText('已满足依赖分析条件')).toBeInTheDocument()
    expect(screen.getByText('当前版本暂无有效结果，旧结果可从历史记录查看。')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /自动修复依赖/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '执行依赖分析' })).toBeInTheDocument()
  })

  it('opens a history record as a read-only overview even when its context is current', async () => {
    renderWithProjectOverview(
      <DependencyAnalysis onStageChange={vi.fn()} projectId="project-1" visible onClose={vi.fn()} />
    )

    await userEvent.click(await screen.findByRole('button', { name: '查看历史记录' }))
    await userEvent.click(screen.getByRole('button', { name: '查看' }))

    expect(screen.getByText('历史分析详情')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '返回历史记录' })).toBeInTheDocument()
    expect(screen.getByText('只读记录')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /自动修复依赖/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新分析' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '纳入报告' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'zlib' }))
    expect(await screen.findByText('历史上下文，只读')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '返回历史分析详情' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '纳入报告' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /排\s*除/ })).not.toBeInTheDocument()
  })
})
