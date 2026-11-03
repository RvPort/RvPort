import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { renderWithStrictProjectOverview as renderWithProjectOverview } from '@/test/strict-project-overview-utils'
import type { ProjectBuildValidation } from '@/types/project'

import AutomatedTest from '.'

const api = vi.hoisted(() => ({
  cancelProjectAutomatedTest: vi.fn(),
  createProjectAutomatedTest: vi.fn(),
  getProjectAutomatedTestLatest: vi.fn(),
  getProjectAutomatedTests: vi.fn(),
  getProjectBuildValidations: vi.fn(),
  getProjectStageReadiness: vi.fn(),
  getProjectTaskLogContent: vi.fn(),
  getProjectTaskLogIncrements: vi.fn(),
  getProjectTestComparisons: vi.fn()
}))
vi.mock('@/api/project/automated-test', () => api)
vi.mock('@/api/project/build-validation', () => ({
  getProjectBuildValidations: api.getProjectBuildValidations,
  getProjectTaskLogContent: api.getProjectTaskLogContent,
  getProjectTaskLogDownloadUrl: vi.fn(() => '/download'),
  getProjectTaskLogIncrements: api.getProjectTaskLogIncrements
}))
vi.mock('@/api/project/workflow', () => ({
  getProjectStageReadiness: api.getProjectStageReadiness
}))
vi.mock('@/components/BaseDrawer', () => ({
  default: ({
    children,
    extra,
    title,
    onClose
  }: {
    children: React.ReactNode
    extra?: React.ReactNode
    title: React.ReactNode
    onClose: () => void
  }) => (
    <div>
      {title}
      {extra}
      {children}
      <button onClick={onClose}>关闭测试 Drawer</button>
    </div>
  )
}))
vi.mock('@/components/MonacoEditor', () => ({
  default: ({ value }: { value: string }) => <pre>{value}</pre>
}))
vi.mock('@/components/CodeMirrorEditor', () => ({
  default: ({ ariaLabel, height, value }: { ariaLabel: string; height: number; value: string }) => (
    <textarea aria-label={ariaLabel} data-height={height} readOnly value={value} />
  )
}))

const page = <T,>(items: T[]) => ({
  hasMore: false,
  items,
  page: 1,
  pageSize: 100,
  total: items.length
})
const environment = {
  arch: 'x86_64',
  architecture: 'x86_64' as const,
  connectionStatus: 'SUCCESS' as const,
  enabled: true,
  configVersion: 1,
  id: 'env-source',
  name: 'x86',
  role: 'SOURCE' as const
}
const build: ProjectBuildValidation = {
  bitcode: {
    available: false,
    legacy: true,
    sourceIdentityDigest: null,
    compileContextDigest: null,
    bundleSha256: null,
    status: 'NOT_ATTEMPTED',
    reasonCode: 'LEGACY_RECORD',
    schemaVersion: 'llvm-bitcode-artifact-set.v1',
    artifactSetId: null,
    toolchainId: null,
    toolchainVersion: null,
    plannedCount: 0,
    succeededCount: 0,
    failedCount: 0,
    skippedCount: 0,
    warningCount: 0,
    totalBytes: 0,
    manifestArtifactId: null,
    bundleArtifactId: null
  },
  buildScope: 'SOURCE_ONLY',
  command: 'make',
  createdAt: '',
  currentEnvironmentConfigVersion: 1,
  currentRuntimeEnvironment: true,
  environment,
  environmentConfigVersion: 1,
  environmentId: 'env-source',
  environmentVariables: {},
  exitCode: 0,
  failureCategory: null,
  finishedAt: '',
  id: 'build-source',
  mergedLogId: null,
  projectId: 'project-1',
  sourceDeploymentId: 'deploy-source',
  sourceRevisionId: 'source-1',
  startedAt: '',
  status: 'SUCCEEDED',
  stderrLogId: null,
  stdoutLogId: null,
  taskId: 'task-build',
  timeoutSeconds: 60,
  workingDirectory: '/sources/x86'
}

const testRun = (role: 'SOURCE' | 'TARGET') => ({
  buildRunId: role === 'SOURCE' ? 'build-source' : 'build-target',
  combinedLogId: null,
  command: 'ctest',
  comparisonId: null,
  comparisonStatus: null,
  comparisonUnavailableCode: null,
  comparisonUnavailableMessage: null,
  currentEnvironmentConfigVersion: 1,
  currentRuntimeEnvironment: true,
  environmentConfigVersion: 1,
  environmentId: role === 'SOURCE' ? 'env-source' : 'env-target',
  environmentName: role === 'SOURCE' ? 'x86' : 'RISC-V',
  exitCode: 0,
  failedCount: 0,
  finishedAt: '',
  id: `test-${role}`,
  outputSummary: null,
  passedCount: 12,
  projectId: 'project-1',
  skippedCount: 0,
  sourceRevisionId: 'source-1',
  startedAt: '',
  status: 'SUCCEEDED' as const,
  stderrLogId: null,
  stdoutLogId: null,
  taskId: `task-${role}`,
  testBatchId: 'batch-1',
  testBatchRole: role,
  timeoutSeconds: 60,
  workingDirectory: role === 'SOURCE' ? '/sources/x86' : '/sources/riscv'
})

function mockRequests(
  builds: ProjectBuildValidation[] = [
    build,
    {
      ...build,
      id: 'build-target',
      environmentId: 'env-target',
      environment: { id: 'env-target', name: 'RISC-V', arch: 'riscv64' },
      workingDirectory: '/sources/riscv'
    }
  ]
) {
  const ready = builds.length === 2
  api.getProjectStageReadiness.mockResolvedValue({
    context: { runtimeEnvironmentVersion: 1, sourceRevisionId: 'source-1' },
    prerequisites: [
      {
        details: ready
          ? { sourceBuildRunId: 'build-source', targetBuildRunId: 'build-target' }
          : {},
        key: 'BUILD_VALIDATION',
        reasonCode: ready ? 'BUILD_VALIDATION_READY' : 'BUILD_VALIDATION_MISSING',
        referenceId: ready ? 'build-source' : null,
        status: ready ? 'SATISFIED' : 'MISSING'
      }
    ],
    stageId: 'test'
  })
  api.getProjectAutomatedTestLatest.mockResolvedValue(
    ready
      ? {
          context: { runtimeEnvironmentVersion: 1, sourceRevisionId: 'source-1' },
          execution: {
            createdAt: '2026-09-07T00:00:00Z',
            errorCode: null,
            errorMessage: null,
            finishedAt: '2026-09-07T00:01:00Z',
            id: 'test-SOURCE',
            message: null,
            progress: 100,
            startedAt: '2026-09-07T00:00:10Z',
            status: 'SUCCEEDED',
            taskId: 'task-SOURCE'
          },
          freshness: 'CURRENT',
          result: testRun('SOURCE')
        }
      : {
          context: { runtimeEnvironmentVersion: 1, sourceRevisionId: 'source-1' },
          execution: null,
          freshness: 'EMPTY',
          result: null
        }
  )
  api.getProjectBuildValidations.mockResolvedValue(page(builds))
  api.getProjectAutomatedTests.mockResolvedValue(page([]))
  api.getProjectTestComparisons.mockResolvedValue(page([]))
  api.getProjectTaskLogContent.mockResolvedValue({
    logs: {
      merged: {
        content: '',
        hasMore: false,
        lines: 100,
        logId: null,
        offset: 0,
        returnedLines: 0,
        totalLines: 0,
        type: 'merged'
      }
    }
  })
  api.createProjectAutomatedTest.mockResolvedValue({ id: 'task-test' })
}

describe('AutomatedTest stage drawer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getProjectTaskLogIncrements.mockResolvedValue({
      taskId: 'task-1',
      fromEventId: null,
      latestEventId: 0,
      hasMore: false,
      logs: {
        merged: { content: '', returnedEvents: 0 },
        stdout: { content: '', returnedEvents: 0 },
        stderr: { content: '', returnedEvents: 0 }
      }
    })
  })

  it('keeps cross-version records readable when current conditions fail, and aggregates both API pages', async () => {
    mockRequests([])
    api.getProjectStageReadiness.mockRejectedValue(new Error('current unavailable'))
    api.getProjectAutomatedTests.mockImplementation(async (_id, query) => ({
      ...page([
        {
          ...testRun(query.page === 1 ? 'SOURCE' : 'TARGET'),
          sourceRevisionId: 'old-source',
          testBatchId: 'old-batch'
        }
      ]),
      page: query.page,
      pageSize: 1,
      total: 2,
      hasMore: query.page === 1
    }))
    renderWithProjectOverview(<AutomatedTest projectId="project-1" visible onClose={vi.fn()} />)
    expect(await screen.findByText('自动化测试数据加载失败')).toBeVisible()
    expect(await screen.findByText('old-source')).toBeVisible()
    expect(api.getProjectAutomatedTests).toHaveBeenCalledWith('project-1', {
      page: 2,
      pageSize: 1,
      sortBy: 'createdAt',
      sortDirection: 'desc'
    })
    await userEvent.click(screen.getByRole('button', { name: '查看' }))
    expect(screen.getByText('批次测试结果')).toBeVisible()
    expect(screen.getAllByRole('button', { name: '日志' })).toHaveLength(2)
    expect(screen.queryByRole('button', { name: '运行自动化测试' })).not.toBeInTheDocument()
  })

  it('retains legacy records without a batch identity', async () => {
    mockRequests([])
    api.getProjectAutomatedTests.mockResolvedValue(
      page([{ ...testRun('SOURCE'), id: 'legacy-test', testBatchId: null, testBatchRole: null }])
    )
    renderWithProjectOverview(<AutomatedTest projectId="project-1" visible onClose={vi.fn()} />)
    expect(await screen.findByText('legacy-test')).toBeVisible()
    await userEvent.click(screen.getByRole('button', { name: '查看' }))
    expect(await screen.findByRole('button', { name: '返回测试记录' })).toBeVisible()
    expect(screen.getByRole('region', { name: '测试概要' })).toBeVisible()
  })

  it('reports configuration edits and clears dirty state when reverted', async () => {
    mockRequests()
    const report = vi.fn()
    renderWithProjectOverview(
      <AutomatedTest projectId="project-1" visible onClose={vi.fn()} onDirtyChange={report} />
    )
    const input = await screen.findByRole('textbox', { name: '环境变量（每行 KEY=VALUE，可选）' })
    await userEvent.type(input, 'DRAFT=1')
    await waitFor(() => expect(report).toHaveBeenLastCalledWith(true))
    await userEvent.clear(input)
    await waitFor(() => expect(report).toHaveBeenLastCalledWith(false))
  })

  it('creates paired requests without environmentId', async () => {
    mockRequests()
    renderWithProjectOverview(
      <AutomatedTest
        projectId="project-1"
        stage={{ id: 'test', order: 1, title: '自动化测试', subtitle: '', status: 'pending' }}
        visible
        onClose={vi.fn()}
      />
    )
    const button = await screen.findByRole('button', { name: '运行自动化测试' })
    expect(api.getProjectStageReadiness).toHaveBeenCalledWith('project-1', 'test')
    expect(api.getProjectAutomatedTestLatest).toHaveBeenCalledWith('project-1')
    expect(
      screen.getByRole('textbox', { name: '环境变量（每行 KEY=VALUE，可选）' })
    ).toHaveAttribute('rows', '8')
    expect(screen.getByRole('textbox', { name: '测试命令' })).toHaveAttribute('data-height', '256')
    await userEvent.click(button)
    await waitFor(() => expect(api.createProjectAutomatedTest).toHaveBeenCalledTimes(2))
    const first = api.createProjectAutomatedTest.mock.calls[0][1]
    const second = api.createProjectAutomatedTest.mock.calls[1][1]
    expect(first.environmentId).toBeUndefined()
    expect(second.environmentId).toBeUndefined()
    expect(first.testBatchId).toBe(second.testBatchId)
    expect(new Set([first.testBatchRole, second.testBatchRole])).toEqual(
      new Set(['SOURCE', 'TARGET'])
    )
  })

  it('initializes the command and current batch from domain latest', async () => {
    mockRequests()
    api.getProjectAutomatedTestLatest.mockResolvedValue({
      context: { runtimeEnvironmentVersion: 1, sourceRevisionId: 'source-1' },
      execution: {
        createdAt: '2026-09-07T00:00:00Z',
        errorCode: null,
        errorMessage: null,
        finishedAt: null,
        id: 'test-SOURCE',
        message: null,
        progress: 20,
        startedAt: '2026-09-07T00:00:10Z',
        status: 'RUNNING',
        taskId: 'task-SOURCE'
      },
      freshness: 'CURRENT',
      result: { ...testRun('SOURCE'), command: 'npm run test:current', status: 'RUNNING' }
    })
    api.getProjectAutomatedTests.mockResolvedValue(
      page([
        { ...testRun('SOURCE'), id: 'old-source', passedCount: 99, testBatchId: 'batch-old' },
        testRun('TARGET')
      ])
    )
    renderWithProjectOverview(
      <AutomatedTest
        projectId="project-1"
        stage={{ id: 'test', order: 1, title: '自动化测试', subtitle: '', status: 'pending' }}
        visible
        onClose={vi.fn()}
      />
    )

    expect(await screen.findByRole('textbox', { name: '测试命令' })).toHaveValue(
      'npm run test:current'
    )
    expect(screen.getByRole('heading', { name: '测试记录' })).toBeInTheDocument()
    expect(screen.getByText('batch-old')).toBeInTheDocument()
    expect(screen.getByText('batch-1')).toBeInTheDocument()
    expect(screen.queryByText('99')).not.toBeInTheDocument()
  })

  it('keeps the peer task and retries only the failed side using the original batch', async () => {
    mockRequests()
    api.createProjectAutomatedTest
      .mockResolvedValueOnce({ id: 'task-source' })
      .mockRejectedValueOnce(new Error('target unavailable'))
      .mockResolvedValueOnce({ id: 'task-target' })
    renderWithProjectOverview(<AutomatedTest projectId="project-1" visible onClose={vi.fn()} />)
    await userEvent.click(await screen.findByRole('button', { name: '运行自动化测试' }))
    const retry = await screen.findByRole('button', { name: '重试失败侧' })
    expect(screen.getByText(/已确认提交 1 个任务/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /运行自动化测试/ })).toBeDisabled()
    await userEvent.click(retry)
    await waitFor(() => expect(api.createProjectAutomatedTest).toHaveBeenCalledTimes(3))
    expect(api.createProjectAutomatedTest.mock.calls[2][1]).toEqual(
      api.createProjectAutomatedTest.mock.calls[1][1]
    )
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: '重试失败侧' })).not.toBeInTheDocument()
    )
  })

  it('leaves create and cancel refresh ownership to SSE', async () => {
    const user = userEvent.setup()
    mockRequests()
    api.getProjectAutomatedTestLatest.mockResolvedValue({
      context: { runtimeEnvironmentVersion: 1, sourceRevisionId: 'source-1' },
      execution: {
        createdAt: '2026-09-07T00:00:00Z',
        errorCode: null,
        errorMessage: null,
        finishedAt: null,
        id: 'test-SOURCE',
        message: null,
        progress: 20,
        startedAt: '2026-09-07T00:00:10Z',
        status: 'RUNNING',
        taskId: 'task-SOURCE'
      },
      freshness: 'CURRENT',
      result: { ...testRun('SOURCE'), status: 'RUNNING' }
    })
    api.cancelProjectAutomatedTest.mockResolvedValue({ id: 'task-SOURCE' })
    const { queryClient } = renderWithProjectOverview(
      <AutomatedTest
        projectId="project-1"
        stage={{ id: 'test', order: 1, title: '自动化测试', subtitle: '', status: 'pending' }}
        visible
        onClose={vi.fn()}
      />
    )
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: '运行自动化测试' }))
    await waitFor(() => expect(api.createProjectAutomatedTest).toHaveBeenCalledTimes(2))
    expect(invalidateQueries).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '取消 x86' }))
    await user.click(await screen.findByRole('button', { name: 'OK' }))
    await waitFor(() => expect(api.cancelProjectAutomatedTest).toHaveBeenCalled())
    expect(api.cancelProjectAutomatedTest.mock.calls[0]?.[0]).toBe('test-SOURCE')
    expect(invalidateQueries).not.toHaveBeenCalled()
  })

  it('disables running when a matching build is missing', async () => {
    mockRequests([build])
    const onStageChange = vi.fn()
    renderWithProjectOverview(
      <AutomatedTest
        projectId="project-1"
        stage={{ id: 'test', order: 1, title: '自动化测试', subtitle: '', status: 'pending' }}
        visible
        onClose={vi.fn()}
        onStageChange={onStageChange}
      />
    )
    expect(await screen.findByText('尚无有效双环境构建')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '运行自动化测试' })).not.toBeInTheDocument()
    expect(screen.getByText('双环境构建验证')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '前往构建验证' }))
    expect(onStageChange).toHaveBeenCalledWith('build')
  })

  it('shows the automatic comparison and opens paged test logs', async () => {
    mockRequests()
    api.getProjectAutomatedTests.mockResolvedValue(page([testRun('SOURCE'), testRun('TARGET')]))
    api.getProjectTestComparisons.mockResolvedValue(
      page([
        {
          id: 'comparison-1',
          projectId: 'project-1',
          taskId: 'task-comparison',
          testBatchId: 'batch-1',
          sourceTestRunId: 'test-SOURCE',
          targetTestRunId: 'test-TARGET',
          status: 'SUCCEEDED',
          overallStatus: 'EQUIVALENT',
          riskLevel: 'LOW',
          confidence: 1,
          summary: '测试结果一致',
          recommendation: null,
          unavailableCode: null,
          unavailableMessage: null,
          errorCode: null,
          errorMessage: null,
          createdAt: '',
          startedAt: '',
          finishedAt: '',
          updatedAt: ''
        }
      ])
    )
    api.getProjectTaskLogContent.mockResolvedValue({
      logs: {
        merged: {
          content: 'native test output',
          hasMore: false,
          lines: 100,
          logId: 'log-1',
          offset: 0,
          returnedLines: 1,
          totalLines: 1,
          type: 'merged'
        }
      }
    })
    renderWithProjectOverview(
      <AutomatedTest
        projectId="project-1"
        stage={{ id: 'test', order: 1, title: '自动化测试', subtitle: '', status: 'pending' }}
        visible
        onClose={vi.fn()}
      />
    )
    await userEvent.click(await screen.findByRole('button', { name: '查看' }))
    expect(await screen.findByText('双端测试结果等价')).toBeInTheDocument()
    expect(screen.getByText('结果等价')).toBeInTheDocument()
    expect(await screen.findByText('测试结果一致')).toBeInTheDocument()
    expect(screen.queryByText('测试记录')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '查看对比日志' }))
    expect(screen.getByText('测试结果对比日志')).toBeInTheDocument()
    expect(await screen.findByText('native test output')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '返回测试记录概览' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '返回测试记录概览' }))
    await userEvent.click(screen.getByRole('button', { name: '返回测试记录' }))
    expect(screen.getByText('测试记录')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '测试记录' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '查看' }))
    expect(screen.getByText('测试记录概览')).toBeInTheDocument()
    expect(screen.getByText('只读记录')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '运行自动化测试' })).not.toBeInTheDocument()
    await userEvent.click(screen.getAllByRole('button', { name: '日志' })[0])
    expect(await screen.findByText('native test output')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '返回测试记录概览' })).toBeInTheDocument()
  })

  it('stops current queries in history and while closing a log', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    mockRequests()
    api.getProjectAutomatedTests.mockResolvedValue(page([testRun('SOURCE'), testRun('TARGET')]))
    const { queryClient } = renderWithProjectOverview(
      <AutomatedTest
        projectId="project-1"
        stage={{ id: 'test', order: 1, title: '自动化测试', subtitle: '', status: 'pending' }}
        visible
        onClose={onClose}
      />
    )

    await user.click(await screen.findByRole('button', { name: '查看' }))
    const latestCalls = api.getProjectAutomatedTestLatest.mock.calls.length
    const readinessCalls = api.getProjectStageReadiness.mock.calls.length
    await user.click(screen.getByRole('button', { name: '刷新自动化测试信息' }))
    await waitFor(() => expect(api.getProjectAutomatedTests.mock.calls.length).toBeGreaterThan(1))
    expect(api.getProjectAutomatedTestLatest).toHaveBeenCalledTimes(latestCalls)
    expect(api.getProjectStageReadiness).toHaveBeenCalledTimes(readinessCalls)
    await queryClient.invalidateQueries({
      queryKey: ['project-automated-tests', 'project-1', 'latest']
    })
    await queryClient.invalidateQueries({
      queryKey: ['project-stage-readiness', 'project-1', 'test']
    })
    expect(api.getProjectAutomatedTestLatest).toHaveBeenCalledTimes(latestCalls)
    expect(api.getProjectStageReadiness).toHaveBeenCalledTimes(readinessCalls)

    await user.click(screen.getAllByRole('button', { name: '日志' })[0])
    const logLatestCalls = api.getProjectAutomatedTestLatest.mock.calls.length
    const logReadinessCalls = api.getProjectStageReadiness.mock.calls.length
    await user.click(screen.getByRole('button', { name: '关闭测试 Drawer' }))
    expect(onClose).toHaveBeenCalledOnce()
    expect(api.getProjectAutomatedTestLatest).toHaveBeenCalledTimes(logLatestCalls)
    expect(api.getProjectStageReadiness).toHaveBeenCalledTimes(logReadinessCalls)
  })

  it('shows a recoverable comparison failure without exposing raw agent output', async () => {
    mockRequests()
    api.getProjectAutomatedTests.mockResolvedValue(page([testRun('SOURCE'), testRun('TARGET')]))
    api.getProjectTestComparisons.mockResolvedValue(
      page([
        {
          id: 'comparison-failed',
          projectId: 'project-1',
          taskId: 'task-comparison',
          testBatchId: 'batch-1',
          sourceTestRunId: 'test-SOURCE',
          targetTestRunId: 'test-TARGET',
          status: 'FAILED',
          overallStatus: null,
          riskLevel: null,
          confidence: null,
          summary: 'raw summary from agent',
          recommendation: null,
          unavailableCode: null,
          unavailableMessage: null,
          errorCode: 'AGENT_FAILED',
          errorMessage: 'raw agent output should stay hidden',
          createdAt: '',
          startedAt: '',
          finishedAt: '',
          updatedAt: ''
        }
      ])
    )

    renderWithProjectOverview(
      <AutomatedTest
        projectId="project-1"
        stage={{ id: 'test', order: 1, title: '自动化测试', subtitle: '', status: 'pending' }}
        visible
        onClose={vi.fn()}
      />
    )

    await userEvent.click(await screen.findByRole('button', { name: '查看' }))
    expect(await screen.findByText('测试结果对比失败')).toBeInTheDocument()
    expect(
      screen.getByText('对比任务执行失败，请刷新信息或重新运行自动化测试。')
    ).toBeInTheDocument()
    expect(screen.queryByText('raw summary from agent')).not.toBeInTheDocument()
    expect(screen.queryByText('raw agent output should stay hidden')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '查看对比日志' }))
    expect(await screen.findByText('raw agent output should stay hidden')).toBeInTheDocument()
  })
  it('keeps the failed-side retry after visiting history and preserves the confirmed side', async () => {
    mockRequests()
    api.createProjectAutomatedTest.mockImplementation(async (_project, input) => {
      if (input.testBatchRole === 'TARGET') throw new Error('offline')
      return { id: 'source-task' }
    })
    const user = userEvent.setup()
    renderWithProjectOverview(<AutomatedTest projectId="project-1" visible onClose={vi.fn()} />)
    await user.click(await screen.findByRole('button', { name: '运行自动化测试' }))
    await screen.findByText('本批次尚未完整创建')
    await user.click(screen.getByRole('button', { name: '查看' }))
    await user.click(screen.getByRole('button', { name: '返回测试记录' }))
    await user.click(screen.getByRole('button', { name: '重试失败侧' }))
    await waitFor(() => expect(api.createProjectAutomatedTest).toHaveBeenCalledTimes(3))
    expect(screen.getByText('本批次尚未完整创建')).toBeInTheDocument()
    expect(api.createProjectAutomatedTest.mock.calls[2][1].testBatchRole).toBe('TARGET')
    expect(api.createProjectAutomatedTest.mock.calls[2][1].testBatchId).toBe(
      api.createProjectAutomatedTest.mock.calls[0][1].testBatchId
    )
  })
})
