import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { renderWithStrictProjectOverview as renderWithProjectOverview } from '@/test/strict-project-overview-utils'

import Build from '.'

const {
  cancelProjectBuildValidation,
  createProjectBuildValidations,
  getProjectBuildValidation,
  getProjectBuildValidationLatest,
  getProjectBuildValidations,
  getProjectOverview,
  getProjectStageReadiness,
  getProjectTaskLogContent,
  getProjectTaskLogIncrements,
  tableFilters
} = vi.hoisted(() => ({
  cancelProjectBuildValidation: vi.fn(),
  createProjectBuildValidations: vi.fn(),
  getProjectBuildValidation: vi.fn(),
  getProjectBuildValidationLatest: vi.fn(),
  getProjectBuildValidations: vi.fn(),
  getProjectOverview: vi.fn(),
  getProjectStageReadiness: vi.fn(),
  getProjectTaskLogContent: vi.fn(),
  getProjectTaskLogIncrements: vi.fn(),
  tableFilters: {} as { status?: string }
}))

vi.mock('@/api/project/build-validation', () => ({
  cancelProjectBuildValidation,
  createProjectBuildValidations,
  getProjectBuildValidation,
  getProjectBuildValidationLatest,
  getProjectBuildValidations,
  getProjectTaskLogContent,
  getProjectTaskLogDownloadUrl: vi.fn(() => '/download'),
  getProjectTaskLogIncrements
}))
vi.mock('@/api/project/workflow', () => ({ getProjectStageReadiness }))
vi.mock('@/api/project/projects', () => ({ getProjectOverview }))

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
    footer: React.ReactNode
    title: React.ReactNode
    onClose: () => void
  }) => (
    <div>
      {title}
      {extra}
      {children}
      {footer}
      <button onClick={onClose}>关闭测试 Drawer</button>
    </div>
  )
}))

vi.mock('@/components/BaseTable', () => ({
  default: ({
    columns,
    request,
    toolbar
  }: {
    columns?: Array<{
      dataIndex?: string
      title?: string
      render?: (value: unknown, record: Record<string, unknown>) => React.ReactNode
    }>
    request: (query: { page: number; pageSize: number; status?: string }) => unknown
    toolbar?: {
      filters?: {
        fields: Array<{
          ariaLabel?: string
          options?: Array<{ label: string; value: string }>
          placeholder?: string
        }>
      }
    }
  }) => {
    const record = {
      createdAt: '2026-09-07T00:00:00Z',
      environment: { arch: 'riscv64', name: 'RISC-V 目标环境' },
      environmentId: 'env-target',
      id: 'build-latest',
      status: 'RUNNING',
      taskId: 'task-latest',
      workingDirectory: '/workspace/target'
    }
    const statusField = toolbar?.filters?.fields[0]
    return (
      <div data-testid="build-table">
        {statusField ? (
          <select
            aria-label={statusField.ariaLabel}
            defaultValue=""
            onChange={(event) => {
              tableFilters.status = event.target.value || undefined
            }}
          >
            <option value="">{statusField.placeholder}</option>
            {statusField.options?.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        ) : null}
        <button
          type="button"
          onClick={() => void request({ page: 1, pageSize: 20, status: tableFilters.status })}
        >
          加载构建记录
        </button>
        <table>
          <thead>
            <tr>
              {columns?.map((column) => (
                <th key={column.title}>{column.title}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            <tr>
              {columns?.map((column) => {
                const value = column.dataIndex
                  ? record[column.dataIndex as keyof typeof record]
                  : undefined
                return (
                  <td key={column.title}>
                    {column.render?.(value, record) ?? String(value ?? '')}
                  </td>
                )
              })}
            </tr>
          </tbody>
        </table>
      </div>
    )
  }
}))

vi.mock('@/components/MonacoEditor', () => ({
  default: ({ value }: { value: string }) => <div data-testid="build-log-editor">{value}</div>
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
  pageSize: 20,
  total: items.length
})
const stage = { id: 'build', order: 9, title: '构建验证', subtitle: '', status: 'pending' as const }
const runtimeEnvironment = (
  id: string,
  role: 'SOURCE' | 'TARGET',
  architecture: 'x86_64' | 'riscv64'
) => ({
  architecture,
  connectionStatus: 'SUCCESS' as const,
  enabled: true,
  configVersion: 1,
  id,
  name: id,
  role
})

function mockBaseRequests({ ready = false } = {}) {
  getProjectOverview.mockResolvedValue({
    currentContext: {
      activeSourceRevision: {
        active: true,
        id: 'source-1',
        type: 'GIT',
        version: 'main@abc123',
        versionNote: '',
        updatedAt: '2026-09-03T00:00:00Z',
        repositoryUrl: 'https://github.com/ISCAS/RvPort.git'
      },
      runtimeEnvironments: {
        missing: ready ? [] : ['sourceEnvironment', 'targetEnvironment'],
        projectId: 'project-1',
        ready,
        sourceEnvironment: ready ? runtimeEnvironment('env-source', 'SOURCE', 'x86_64') : null,
        targetEnvironment: ready ? runtimeEnvironment('env-target', 'TARGET', 'riscv64') : null,
        updatedBy: null,
        updatedAt: null,
        version: 1
      }
    }
  })
  getProjectStageReadiness.mockResolvedValue({
    context: { runtimeEnvironmentVersion: 1, sourceRevisionId: ready ? 'source-1' : null },
    prerequisites: [
      {
        details: {},
        key: 'ACTIVE_SOURCE',
        reasonCode: ready ? 'ACTIVE_SOURCE_READY' : 'ACTIVE_SOURCE_MISSING',
        referenceId: ready ? 'source-1' : null,
        status: ready ? 'SATISFIED' : 'MISSING'
      },
      {
        details: {},
        key: 'SOURCE_ENVIRONMENT',
        reasonCode: ready ? 'SOURCE_ENVIRONMENT_READY' : 'SOURCE_ENVIRONMENT_MISSING',
        referenceId: ready ? 'env-source' : null,
        status: ready ? 'SATISFIED' : 'MISSING'
      },
      {
        details: {},
        key: 'TARGET_ENVIRONMENT',
        reasonCode: ready ? 'TARGET_ENVIRONMENT_READY' : 'TARGET_ENVIRONMENT_MISSING',
        referenceId: ready ? 'env-target' : null,
        status: ready ? 'SATISFIED' : 'MISSING'
      }
    ],
    stageId: 'build'
  })
  getProjectBuildValidationLatest.mockResolvedValue({
    context: {
      runtimeEnvironmentVersion: 1,
      sourceRevisionId: ready ? 'source-1' : null
    },
    execution: null,
    freshness: 'EMPTY',
    result: null
  })
  getProjectBuildValidation.mockResolvedValue({
    id: 'build-latest',
    status: 'RUNNING',
    taskId: 'task-latest',
    environment: { name: 'RISC-V 目标环境' },
    workingDirectory: '/workspace/target'
  })
  getProjectBuildValidations.mockResolvedValue(page([]))
  getProjectTaskLogContent.mockResolvedValue({ logs: {} })
}

function renderBuild(onStageChange = vi.fn()) {
  return renderWithProjectOverview(
    <Build
      projectId="project-1"
      stage={stage}
      visible
      onClose={vi.fn()}
      onStageChange={onStageChange}
    />
  )
}

function mockLatestBuild(status: 'RUNNING' | 'SUCCEEDED' = 'SUCCEEDED') {
  getProjectBuildValidationLatest.mockResolvedValue({
    context: { runtimeEnvironmentVersion: 1, sourceRevisionId: 'source-1' },
    execution: {
      createdAt: '2026-09-07T00:00:00Z',
      errorCode: null,
      errorMessage: null,
      finishedAt: status === 'SUCCEEDED' ? '2026-09-07T00:01:00Z' : null,
      id: 'build-latest',
      message: null,
      progress: status === 'SUCCEEDED' ? 100 : 50,
      startedAt: '2026-09-07T00:00:10Z',
      status,
      taskId: 'task-latest'
    },
    freshness: 'CURRENT',
    result: {
      buildScope: 'BOTH',
      command: 'cmake --build build --parallel 4',
      createdAt: '2026-09-07T00:00:00Z',
      currentEnvironmentConfigVersion: 1,
      currentRuntimeEnvironment: true,
      currentSourceRevision: true,
      environment: { arch: 'riscv64', id: 'env-target', name: 'RISC-V 目标环境' },
      environmentConfigVersion: 1,
      environmentId: 'env-target',
      environmentVariables: {},
      exitCode: status === 'SUCCEEDED' ? 0 : null,
      failureCategory: null,
      finishedAt: status === 'SUCCEEDED' ? '2026-09-07T00:01:00Z' : null,
      id: 'build-latest',
      mergedLogId: 'log-latest',
      projectId: 'project-1',
      sourceDeploymentId: 'deployment-1',
      sourceRevisionId: 'source-1',
      startedAt: '2026-09-07T00:00:10Z',
      status,
      stderrLogId: null,
      stdoutLogId: 'stdout-latest',
      taskId: 'task-latest',
      timeoutSeconds: 3600,
      workingDirectory: '/workspace/target'
    }
  })
}

describe('Build stage drawer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getProjectTaskLogIncrements.mockResolvedValue({
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
    tableFilters.status = undefined
  })

  it('reports configuration edits and clears dirty state when reverted', async () => {
    mockBaseRequests({ ready: true })
    const report = vi.fn()
    renderWithProjectOverview(
      <Build projectId="project-1" visible onClose={vi.fn()} onDirtyChange={report} />
    )
    const input = await screen.findByRole('textbox', { name: '环境变量（每行 KEY=VALUE，可选）' })
    await userEvent.type(input, 'DRAFT=1')
    await waitFor(() => expect(report).toHaveBeenLastCalledWith(true))
    await userEvent.clear(input)
    await waitFor(() => expect(report).toHaveBeenLastCalledWith(false))
  })

  it('guides the user to environment management when runtime environments are incomplete', async () => {
    const onStageChange = vi.fn()
    mockBaseRequests()
    renderBuild(onStageChange)

    expect(await screen.findByText('当前源码')).toBeInTheDocument()
    expect(screen.getByText('x86 源环境')).toBeInTheDocument()
    const environmentActions = screen.getAllByRole('button', { name: '前往环境管理' })
    expect(environmentActions).toHaveLength(2)
    await userEvent.click(environmentActions[0])
    expect(onStageChange).toHaveBeenCalledWith('env')
  })

  it('lets the build tasks prepare both source deployments internally', async () => {
    mockBaseRequests({ ready: true })
    createProjectBuildValidations.mockResolvedValue([])
    renderBuild()

    const startBuild = await screen.findByRole('button', { name: '开始构建' })
    await waitFor(() => expect(startBuild).toBeEnabled())
    expect(screen.queryByRole('button', { name: '检查环境' })).not.toBeInTheDocument()
    expect(screen.getByText('可以开始构建验证')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'RvPort' })).toHaveAttribute(
      'href',
      'https://github.com/ISCAS/RvPort.git'
    )
    expect(screen.getByText('env-source / x86_64')).toBeInTheDocument()
    expect(screen.getByText('env-target / riscv64')).toBeInTheDocument()
    await userEvent.click(startBuild)
    await waitFor(() =>
      expect(createProjectBuildValidations).toHaveBeenCalledWith(
        'project-1',
        expect.objectContaining({ buildScope: 'BOTH', sourceRevisionId: 'source-1' })
      )
    )
  })

  it('uses readiness and VALIDATION latest alongside the visible build records', async () => {
    mockBaseRequests({ ready: true })
    renderBuild()

    expect(await screen.findByRole('heading', { name: '构建记录' })).toBeInTheDocument()
    expect(screen.queryByText('当前构建结果')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '查看历史记录' })).not.toBeInTheDocument()
    expect(getProjectStageReadiness).toHaveBeenCalledWith('project-1', 'build')
    expect(getProjectBuildValidationLatest).toHaveBeenCalledWith('project-1', 'VALIDATION')
    await userEvent.click(screen.getByRole('button', { name: '加载构建记录' }))
    expect(getProjectBuildValidations).toHaveBeenCalledWith(
      'project-1',
      expect.objectContaining({ buildScope: 'BOTH', page: 1, pageSize: 20 })
    )
  })

  it('initializes the build command from the current latest run', async () => {
    mockBaseRequests({ ready: true })
    mockLatestBuild()
    renderBuild()

    expect(await screen.findByRole('textbox', { name: '构建命令' })).toHaveValue(
      'cmake --build build --parallel 4'
    )
    expect(screen.getByText('RISC-V 目标环境')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'ID' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '环境' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '状态' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '创建时间' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '操作' })).toBeInTheDocument()
    expect(screen.getByText('当前')).toBeInTheDocument()
    expect(screen.getByText('RISC-V 目标环境 / riscv64')).toBeInTheDocument()
    expect(screen.queryByText('/workspace/target')).not.toBeInTheDocument()
  })

  it('leaves build refresh ownership to SSE after create and cancel', async () => {
    const user = userEvent.setup()
    mockBaseRequests({ ready: true })
    mockLatestBuild('RUNNING')
    createProjectBuildValidations.mockResolvedValue([])
    cancelProjectBuildValidation.mockResolvedValue({ id: 'task-latest', status: 'CANCELLED' })
    const { queryClient } = renderBuild()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')

    await user.click(await screen.findByRole('button', { name: '开始构建' }))
    await waitFor(() => expect(createProjectBuildValidations).toHaveBeenCalled())
    expect(invalidateQueries).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '取消' }))
    await user.click(await screen.findByRole('button', { name: 'OK' }))
    await waitFor(() => expect(cancelProjectBuildValidation).toHaveBeenCalled())
    expect(cancelProjectBuildValidation.mock.calls[0]?.[0]).toBe('build-latest')
    expect(invalidateQueries).not.toHaveBeenCalled()
  })

  it('offers the same status filter as the x86 build records', async () => {
    const user = userEvent.setup()
    mockBaseRequests({ ready: true })
    renderBuild()

    const filter = await screen.findByRole('combobox', { name: '筛选构建状态' })
    expect(filter).toHaveTextContent('全部状态')
    expect(filter).toHaveTextContent('进行中')
    expect(filter).toHaveTextContent('成功')
    await user.selectOptions(filter, 'QUEUED')
    await user.click(screen.getByRole('button', { name: '加载构建记录' }))

    expect(getProjectBuildValidations).toHaveBeenCalledWith(
      'project-1',
      expect.objectContaining({ buildScope: 'BOTH', status: 'QUEUED' })
    )
  })

  it('does not restart current queries while closing the log view', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    mockBaseRequests({ ready: true })
    mockLatestBuild()
    renderWithProjectOverview(
      <Build
        projectId="project-1"
        stage={stage}
        visible
        onClose={onClose}
        onStageChange={vi.fn()}
      />
    )

    await user.click(await screen.findByRole('button', { name: '日志' }))
    const latestCalls = getProjectBuildValidationLatest.mock.calls.length
    const readinessCalls = getProjectStageReadiness.mock.calls.length
    await user.click(screen.getByRole('button', { name: '关闭测试 Drawer' }))

    expect(onClose).toHaveBeenCalledOnce()
    expect(getProjectBuildValidationLatest).toHaveBeenCalledTimes(latestCalls)
    expect(getProjectStageReadiness).toHaveBeenCalledTimes(readinessCalls)
  })

  it('allows the preconditions and build configuration sections to collapse independently', async () => {
    mockBaseRequests({ ready: true })
    renderBuild()

    await screen.findByRole('button', { name: '收起构建前置条件' })
    await userEvent.click(screen.getByRole('button', { name: '收起构建前置条件' }))
    await userEvent.click(screen.getByRole('button', { name: '收起构建配置' }))

    expect(screen.getByRole('button', { name: '展开构建前置条件' })).toHaveAttribute(
      'aria-expanded',
      'false'
    )
    expect(screen.getByRole('button', { name: '展开构建配置' })).toHaveAttribute(
      'aria-expanded',
      'false'
    )
  })

  it('gives standalone build inputs stable browser semantics without stealing focus', async () => {
    mockBaseRequests({ ready: true })
    renderBuild()

    const timeoutInput = await screen.findByRole('spinbutton', { name: '超时时间（秒）' })
    const variablesInput = screen.getByRole('textbox', {
      name: '环境变量（每行 KEY=VALUE，可选）'
    })

    expect(timeoutInput).toHaveAttribute('name', 'timeoutSeconds')
    expect(timeoutInput).toHaveAttribute('autocomplete', 'off')
    expect(variablesInput).toHaveAttribute('name', 'environmentVariables')
    expect(variablesInput).toHaveAttribute('autocomplete', 'off')
    expect(variablesInput).toHaveAttribute('rows', '8')
    expect(screen.getByRole('textbox', { name: '构建命令' })).toHaveAttribute('data-height', '256')
    expect(timeoutInput).not.toHaveFocus()
    expect(variablesInput).not.toHaveFocus()
  })

  it('refreshes the selected build instead of retaining its running list snapshot', async () => {
    mockBaseRequests({ ready: true })
    getProjectBuildValidation.mockResolvedValue({
      id: 'build-latest',
      status: 'SUCCEEDED',
      taskId: 'task-latest',
      exitCode: 0,
      environment: { name: 'RISC-V 目标环境' },
      workingDirectory: '/workspace/target'
    })
    renderBuild()
    await userEvent.click(await screen.findByRole('button', { name: '日志' }))
    await waitFor(() => expect(getProjectBuildValidation).toHaveBeenCalledWith('build-latest'))
    expect(await screen.findByText('0')).toBeInTheDocument()
  })

  it('opens build logs in a secondary drawer view', async () => {
    mockBaseRequests({ ready: true })
    getProjectTaskLogIncrements.mockResolvedValue({
      taskId: 'task-latest',
      fromEventId: 1,
      latestEventId: 1,
      hasMore: false,
      logs: {
        merged: { content: '[STDERR] make: *** failed', returnedEvents: 1 },
        stdout: { content: '', returnedEvents: 0 },
        stderr: { content: 'make: *** failed', returnedEvents: 1 }
      }
    })
    renderBuild()

    await userEvent.click(await screen.findByRole('button', { name: '日志' }))

    expect(await screen.findByText('RISC-V 目标环境构建日志')).toBeInTheDocument()
    expect(screen.getByText('[STDERR] make: *** failed')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '返回构建记录' }))
    expect(await screen.findByTestId('build-table')).toBeInTheDocument()
  })
})
