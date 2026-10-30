import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { renderWithStrictQueryClient as renderWithQueryClient } from '@/test/strict-mode-utils'
import type { ProjectOptimizationCycle } from '@/types/project'

const {
  getProjectOptimizationCycleLatest,
  getProjectOptimizationCycles,
  getProjectStageReadiness
} = vi.hoisted(() => ({
  getProjectOptimizationCycleLatest: vi.fn(),
  getProjectOptimizationCycles: vi.fn(),
  getProjectStageReadiness: vi.fn()
}))

vi.mock('@/api/project/optimization-cycle', () => ({
  getProjectOptimizationCycleLatest,
  getProjectOptimizationCycles
}))
vi.mock('@/api/project/workflow', () => ({ getProjectStageReadiness }))

vi.mock('@/components/BaseDrawer', () => ({
  default: ({
    children,
    extra,
    footer,
    title,
    onClose
  }: {
    children: React.ReactNode
    extra?: React.ReactNode
    footer?: React.ReactNode
    title: React.ReactNode
    onClose: () => void
  }) => (
    <div>
      {title}
      {extra}
      {children}
      {footer}
      <button onClick={onClose}>关闭优化闭环 Drawer</button>
    </div>
  )
}))

vi.mock('@/components/BaseTable', () => ({
  default: ({
    request
  }: {
    request: (query: { page: number; pageSize: number }) => Promise<unknown>
  }) => {
    void request({ page: 1, pageSize: 10 })
    return <div>优化轮次表格</div>
  }
}))

import OptimizationCycle from '.'

const cycle: ProjectOptimizationCycle = {
  completedAt: null,
  createdAt: '2026-08-18T08:00:00Z',
  cycleNumber: 2,
  id: 'cycle-current',
  projectId: 'project-1',
  sourceRevision: {
    active: true,
    createdAt: '2026-08-18T07:00:00Z',
    createdBy: 'user-1',
    gitBranch: 'main',
    gitCommit: 'def456',
    gitUrl: null,
    id: 'revision-1',
    projectId: 'project-1',
    revisionType: 'GIT' as const,
    sourceUri: null,
    versionLabel: 'def456',
    versionNote: null
  },
  sourceRevisionId: 'revision-1',
  startedAt: '2026-08-18T08:00:00Z',
  status: 'ACTIVE' as const,
  summary: {
    build: {
      byStatus: { FAILED: 1 },
      latestFinishedAt: null,
      latestRunId: 'build-1',
      latestStartedAt: null,
      latestStatus: 'FAILED',
      total: 1
    },
    migrationInputs: { byStatus: { CONFIRMED: 3 }, total: 3 },
    recheck: null,
    report: null,
    sourceDeploy: {
      byStatus: {},
      latestFinishedAt: null,
      latestRunId: null,
      latestStartedAt: null,
      latestStatus: null,
      total: 0
    },
    test: {
      byStatus: {},
      latestFinishedAt: null,
      latestRunId: null,
      latestStartedAt: null,
      latestStatus: null,
      total: 0
    },
    testComparison: null
  },
  updatedAt: '2026-08-18T08:00:00Z'
}

const stage = {
  id: 'optimize',
  order: 22,
  status: 'pending' as const,
  subtitle: '修复/验证',
  title: '优化闭环'
}

function renderDrawer(onStageChange = vi.fn()) {
  return {
    onStageChange,
    ...renderWithQueryClient(
      <OptimizationCycle
        projectId="project-1"
        stage={stage}
        visible
        onClose={vi.fn()}
        onStageChange={onStageChange}
      />
    )
  }
}

function mockCurrentCycle(
  result: typeof cycle | null = cycle,
  freshness: 'CURRENT' | 'STALE' | 'EMPTY' = result ? 'CURRENT' : 'EMPTY',
  sourceReady = true
) {
  const context = {
    runtimeEnvironmentVersion: 1,
    sourceRevisionId: sourceReady ? 'revision-1' : null
  }
  getProjectStageReadiness.mockResolvedValue({
    context,
    prerequisites: [
      {
        details: {},
        key: 'ACTIVE_SOURCE',
        reasonCode: sourceReady ? 'ACTIVE_SOURCE_READY' : 'ACTIVE_SOURCE_MISSING',
        referenceId: sourceReady ? 'revision-1' : null,
        status: sourceReady ? 'SATISFIED' : 'MISSING'
      }
    ],
    stageId: 'optimize'
  })
  getProjectOptimizationCycleLatest.mockResolvedValue({
    context,
    execution: null,
    freshness,
    result
  })
}

describe('Optimization cycle drawer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getProjectOptimizationCycles.mockResolvedValue({
      hasMore: false,
      items: [],
      page: 1,
      pageSize: 10,
      total: 0
    })
  })

  it('guides users to source management when no optimization cycle exists', async () => {
    mockCurrentCycle(null, 'EMPTY', false)
    const { onStageChange } = renderDrawer()

    expect(await screen.findByText('暂不能查看优化闭环')).toBeInTheDocument()
    expect(screen.getByText('当前源码')).toBeInTheDocument()
    expect(screen.getByText('未导入或未激活')).toBeInTheDocument()
    await userEvent.setup().click(await screen.findByRole('button', { name: '前往源码管理' }))
    expect(onStageChange).toHaveBeenCalledWith('assets')
    expect(getProjectStageReadiness).toHaveBeenCalledWith('project-1', 'optimize')
    expect(getProjectOptimizationCycleLatest).toHaveBeenCalledWith('project-1')
  })

  it('directs a cycle with an unsuccessful build to build validation', async () => {
    mockCurrentCycle()
    const { onStageChange } = renderDrawer()

    expect(await screen.findByText(/\u7b2c 2 \u8f6e\u4f18\u5316/)).toBeInTheDocument()
    expect(screen.queryByText('优化轮次表格')).not.toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: '前往构建验证' }))
    expect(onStageChange).toHaveBeenCalledWith('build')
    await userEvent.setup().click(screen.getByRole('button', { name: '查看历史记录' }))
    expect(await screen.findByText('优化轮次表格')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '返回当前结果' })).toBeInTheDocument()
  })

  it('directs a successfully built cycle to automated testing before archiving', async () => {
    mockCurrentCycle({
      ...cycle,
      summary: {
        ...cycle.summary,
        build: { ...cycle.summary.build, latestStatus: 'SUCCEEDED' }
      }
    })
    const { onStageChange } = renderDrawer()

    expect(await screen.findByText('问题复检')).toBeInTheDocument()
    expect(screen.getByText('缺失问题复检数据')).toBeInTheDocument()
    expect(screen.queryByText('源码复检')).not.toBeInTheDocument()
    await userEvent.setup().click(await screen.findByRole('button', { name: '前往自动化测试' }))
    expect(onStageChange).toHaveBeenCalledWith('test')
  })

  it('only enables archiving after build and automated testing succeed', async () => {
    mockCurrentCycle({
      ...cycle,
      summary: {
        ...cycle.summary,
        build: { ...cycle.summary.build, latestStatus: 'SUCCEEDED' },
        test: { ...cycle.summary.test, latestStatus: 'SUCCEEDED', total: 1 },
        testComparison: {
          createdAt: '2026-08-18T09:00:00Z',
          finishedAt: '2026-08-18T09:01:00Z',
          id: 'comparison-1',
          overallStatus: 'EQUIVALENT',
          recommendation: null,
          riskLevel: 'NONE',
          status: 'SUCCEEDED',
          summary: '双端测试结果一致',
          taskId: 'comparison-task-1',
          testBatchId: 'batch-1'
        }
      }
    })
    const { onStageChange } = renderDrawer()

    expect(await screen.findByText('验证路径')).toBeInTheDocument()
    expect(screen.getByText('本轮问题范围')).toBeInTheDocument()
    expect(screen.queryByText('验证结果')).not.toBeInTheDocument()
    expect(screen.getByText('结果一致')).toBeInTheDocument()
    await userEvent.setup().click(await screen.findByRole('button', { name: '进入归档' }))
    expect(onStageChange).toHaveBeenCalledWith('archive')
    expect(screen.queryByRole('button', { name: '详情' })).not.toBeInTheDocument()
  })

  it('waits for the comparison before allowing the cycle to archive', async () => {
    mockCurrentCycle({
      ...cycle,
      summary: {
        ...cycle.summary,
        build: { ...cycle.summary.build, latestStatus: 'SUCCEEDED' },
        test: { ...cycle.summary.test, latestStatus: 'SUCCEEDED', total: 1 }
      }
    })
    const { onStageChange } = renderDrawer()

    expect(await screen.findAllByText('缺失测试结果对比数据')).not.toHaveLength(0)
    await userEvent.setup().click(screen.getByRole('button', { name: '查看自动化测试' }))
    expect(onStageChange).toHaveBeenCalledWith('test')
    expect(screen.queryByRole('button', { name: '进入归档' })).not.toBeInTheDocument()
  })

  it('does not present a stale cycle as the current cycle', async () => {
    mockCurrentCycle(cycle, 'STALE')
    renderDrawer()

    expect(await screen.findByText(/历史记录中保留了旧源码的轮次/)).toBeInTheDocument()
    expect(screen.queryByText(/第 2 轮优化/)).not.toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: '查看历史记录' }))
    expect(screen.getByText('优化轮次表格')).toBeInTheDocument()
  })

  it('stops current queries in history and while closing', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    mockCurrentCycle()
    const { queryClient } = renderWithQueryClient(
      <OptimizationCycle
        projectId="project-1"
        stage={stage}
        visible
        onClose={onClose}
        onStageChange={vi.fn()}
      />
    )

    await user.click(await screen.findByRole('button', { name: '查看历史记录' }))
    const latestCalls = getProjectOptimizationCycleLatest.mock.calls.length
    const readinessCalls = getProjectStageReadiness.mock.calls.length
    await user.click(screen.getByRole('button', { name: '刷新优化轮次记录' }))
    await queryClient.invalidateQueries({
      queryKey: ['project-optimization-cycles', 'project-1', 'latest']
    })
    await queryClient.invalidateQueries({
      queryKey: ['project-stage-readiness', 'project-1', 'optimize']
    })
    expect(getProjectOptimizationCycleLatest).toHaveBeenCalledTimes(latestCalls)
    expect(getProjectStageReadiness).toHaveBeenCalledTimes(readinessCalls)

    await user.click(screen.getByRole('button', { name: '返回当前结果' }))
    await waitFor(() =>
      expect(getProjectOptimizationCycleLatest.mock.calls.length).toBeGreaterThan(latestCalls)
    )
    const closingLatestCalls = getProjectOptimizationCycleLatest.mock.calls.length
    const closingReadinessCalls = getProjectStageReadiness.mock.calls.length
    await user.click(screen.getByRole('button', { name: '关闭优化闭环 Drawer' }))
    await queryClient.invalidateQueries({
      queryKey: ['project-optimization-cycles', 'project-1', 'latest']
    })
    await queryClient.invalidateQueries({
      queryKey: ['project-stage-readiness', 'project-1', 'optimize']
    })
    expect(onClose).toHaveBeenCalledOnce()
    expect(getProjectOptimizationCycleLatest).toHaveBeenCalledTimes(closingLatestCalls)
    expect(getProjectStageReadiness).toHaveBeenCalledTimes(closingReadinessCalls)
  })
})
