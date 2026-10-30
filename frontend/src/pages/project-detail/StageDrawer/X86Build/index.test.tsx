import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createBuild } from '@/test/build-fixtures'
import { renderWithStrictQueryClient as renderWithQueryClient } from '@/test/strict-mode-utils'
import type { ProjectBuildValidation } from '@/types/project'

import { composeTaskLogContent, getTaskLogLineNumberOffset } from '../utils/taskLog'
import X86Build from '.'

const {
  buildContext,
  createProjectBuildValidations,
  getProjectBuildValidation,
  getProjectBuildValidationLatest,
  getProjectBuildValidations,
  getProjectStageReadiness,
  getProjectTaskLogContent,
  getProjectTaskLogIncrements,
  refetch,
  tableRecord,
  tableFilters
} = vi.hoisted(() => ({
  buildContext: {
    activeSourceRevisionId: 'source-1' as string | null,
    activeSourceName: 'rvport-demo' as string | null,
    activeSourceRepositoryUrl: 'https://github.com/ISCAS/rvport-demo.git' as string | null,
    currentSourceBuild: null,
    runtimeEnvironmentReady: true,
    runtimeEnvironmentVersion: 3,
    sourceEnvironment: {
      architecture: 'x86_64',
      id: 'env-source',
      name: 'x86 源环境'
    } as { architecture: string; id: string; name: string } | null,
    targetEnvironment: null
  },
  createProjectBuildValidations: vi.fn(),
  getProjectBuildValidation: vi.fn(),
  getProjectBuildValidationLatest: vi.fn(),
  getProjectBuildValidations: vi.fn(),
  getProjectStageReadiness: vi.fn(),
  getProjectTaskLogContent: vi.fn(),
  getProjectTaskLogIncrements: vi.fn(),
  refetch: vi.fn(),
  tableRecord: { current: null as ProjectBuildValidation | null },
  tableFilters: { status: undefined as string | undefined }
}))

const contextState = vi.hoisted(() => ({ hasData: true, isError: false, isLoading: false }))
let activeBuildContext = buildContext

vi.mock('@/api/project/build-validation', () => ({
  cancelProjectBuildValidation: vi.fn(),
  createProjectBuildValidations,
  getProjectBuildValidation,
  getProjectBuildValidationLatest,
  getProjectBuildValidations,
  getProjectTaskLogContent,
  getProjectTaskLogDownloadUrl: vi.fn(() => '/download'),
  getProjectTaskLogIncrements
}))
vi.mock('@/api/project/workflow', () => ({ getProjectStageReadiness }))

vi.mock('../buildReadinessContext', () => ({
  isCurrentBuildReadinessRecord: vi.fn(() => true),
  isTerminalBuildReadinessStatus: vi.fn(() => true),
  useBuildReadinessContext: () => ({
    context: activeBuildContext,
    ...contextState,
    refetch
  })
}))

vi.mock('@/components/BaseDrawer', () => ({
  default: ({
    children,
    extra,
    title,
    afterOpenChange
  }: {
    children: React.ReactNode
    extra?: React.ReactNode
    title: React.ReactNode
    afterOpenChange?: (open: boolean) => void
  }) => (
    <div>
      <h2>{title}</h2>
      {extra}
      {children}
      <button onClick={() => afterOpenChange?.(false)}>结束关闭构建</button>
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
      render?: (value: unknown, record: ProjectBuildValidation) => React.ReactNode
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
    const statusField = toolbar?.filters?.fields[0]

    return (
      <>
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
        {tableRecord.current ? (
          <div role="row">
            {columns?.map((column, index) => {
              const record = tableRecord.current!
              const value = column.dataIndex
                ? record[column.dataIndex as keyof ProjectBuildValidation]
                : undefined
              return <div key={index}>{column.render?.(value, record)}</div>
            })}
          </div>
        ) : null}
      </>
    )
  }
}))

vi.mock('@/components/CodeMirrorEditor', () => ({
  default: ({
    height,
    onChange,
    value
  }: {
    height?: number
    onChange?: (value: string) => void
    value?: string
  }) => (
    <textarea
      aria-label="x86 构建命令"
      data-height={height}
      value={value}
      onChange={(event) => onChange?.(event.target.value)}
    />
  )
}))

vi.mock('@/utils/feedback', () => ({ feedback: { success: vi.fn() } }))

describe('X86Build', () => {
  it('refreshes context fields independently, preserves an edited command and restores the latest command after close', async () => {
    const close = vi.fn()
    const view = renderWithQueryClient(<X86Build projectId="project-1" visible onClose={close} />)
    await screen.findByText('可以开始构建')
    for (const [patch, expected] of [
      [{ activeSourceName: 'new-source' }, 'new-source'],
      [{ activeSourceRepositoryUrl: 'https://next.invalid/repo.git' }, 'new-source'],
      [
        { sourceEnvironment: { architecture: 'x86_64', id: 'env-source', name: '新源环境' } },
        '新源环境 / x86_64'
      ],
      [{ sourceEnvironment: null }, '-- / --'],
      [{ activeSourceName: null }, '--']
    ] as const) {
      activeBuildContext = { ...activeBuildContext, ...patch }
      view.rerender(<X86Build projectId="project-1" visible onClose={close} />)
      expect(screen.getByText(expected)).toBeInTheDocument()
    }
    const latestKey = ['project-build-validations', 'project-1', 'SOURCE_ONLY', 'latest']
    act(() =>
      view.queryClient.setQueryData(latestKey, {
        freshness: 'CURRENT',
        result: createBuild({ command: 'make latest' }),
        execution: null
      })
    )
    await waitFor(() =>
      expect(screen.getByRole('textbox', { name: 'x86 构建命令' })).toHaveValue('make latest')
    )
    fireEvent.change(screen.getByRole('textbox', { name: 'x86 构建命令' }), {
      target: { value: 'make draft' }
    })
    act(() =>
      view.queryClient.setQueryData(latestKey, {
        freshness: 'CURRENT',
        result: createBuild({ command: 'make newer' }),
        execution: null
      })
    )
    expect(screen.getByRole('textbox', { name: 'x86 构建命令' })).toHaveValue('make draft')
    fireEvent.click(screen.getByRole('button', { name: '结束关闭构建' }))
    expect(screen.getByRole('textbox', { name: 'x86 构建命令' })).toHaveValue('make newer')
    view.rerender(
      <X86Build
        projectId="project-1"
        visible
        onClose={close}
        stage={{ id: 'x86-build', title: '新 x86 标题', order: 3, subtitle: '', status: 'pending' }}
      />
    )
    expect(screen.getByText('新 x86 标题')).toBeInTheDocument()
  })

  it('retains cached configuration after a context refresh failure and removes it when no context was read', async () => {
    const view = renderWithQueryClient(<X86Build projectId="project-1" visible onClose={vi.fn()} />)
    await screen.findByText('可以开始构建')
    contextState.isLoading = true
    view.rerender(<X86Build projectId="project-1" visible onClose={vi.fn()} />)
    expect(screen.getByText('正在读取前置条件')).toBeInTheDocument()
    Object.assign(contextState, { isLoading: false, isError: true })
    view.rerender(<X86Build projectId="project-1" visible onClose={vi.fn()} />)
    expect(
      screen.getByText('前置条件刷新失败，保留已读取内容；暂不能开始构建。')
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '开始 x86 构建' })).toBeDisabled()
    contextState.hasData = false
    view.rerender(<X86Build projectId="project-1" visible onClose={vi.fn()} />)
    expect(screen.getByText('x86 构建前置数据加载失败')).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'x86 构建命令' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }))
    expect(refetch).toHaveBeenCalledOnce()
  })
  beforeEach(() => {
    vi.clearAllMocks()
    Object.assign(contextState, { hasData: true, isError: false, isLoading: false })
    activeBuildContext = buildContext
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
    tableRecord.current = null
    Object.assign(buildContext, {
      activeSourceRevisionId: 'source-1',
      activeSourceName: 'rvport-demo',
      activeSourceRepositoryUrl: 'https://github.com/ISCAS/rvport-demo.git',
      runtimeEnvironmentReady: true,
      sourceEnvironment: { architecture: 'x86_64', id: 'env-source', name: 'x86 源环境' }
    })
    createProjectBuildValidations.mockResolvedValue([])
    getProjectBuildValidationLatest.mockResolvedValue({
      context: { runtimeEnvironmentVersion: 3, sourceRevisionId: 'source-1' },
      execution: null,
      freshness: 'EMPTY',
      result: null
    })
    getProjectBuildValidations.mockResolvedValue({ items: [], page: 1, pageSize: 20, total: 0 })
    getProjectBuildValidation.mockResolvedValue(null)
    getProjectStageReadiness.mockResolvedValue({
      context: { runtimeEnvironmentVersion: 3, sourceRevisionId: 'source-1' },
      prerequisites: [
        {
          details: {},
          key: 'ACTIVE_SOURCE',
          reasonCode: 'ACTIVE_SOURCE_READY',
          referenceId: 'source-1',
          status: 'SATISFIED'
        },
        {
          details: {},
          key: 'SOURCE_ENVIRONMENT',
          reasonCode: 'SOURCE_ENVIRONMENT_READY',
          referenceId: 'env-source',
          status: 'SATISFIED'
        }
      ],
      stageId: 'x86-build'
    })
    getProjectTaskLogContent.mockResolvedValue({ logs: {} })
  })

  it('reports configuration edits and clears dirty state when reverted', async () => {
    const report = vi.fn()
    renderWithQueryClient(
      <X86Build projectId="project-1" visible onClose={vi.fn()} onDirtyChange={report} />
    )
    const input = await screen.findByRole('textbox', { name: '环境变量（可选）' })
    await userEvent.type(input, 'DRAFT=1')
    await waitFor(() => expect(report).toHaveBeenLastCalledWith(true))
    await userEvent.clear(input)
    await waitFor(() => expect(report).toHaveBeenLastCalledWith(false))
  })

  it('always submits a source-only build for the active source revision', async () => {
    buildContext.activeSourceRevisionId = 'stale-overview-source'

    const { queryClient } = renderWithQueryClient(
      <X86Build
        projectId="project-1"
        stage={{ id: 'x86-build', order: 8, status: 'pending', subtitle: '', title: 'x86 构建' }}
        visible
        onClose={vi.fn()}
      />
    )
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')

    expect(await screen.findByPlaceholderText('每行一个 KEY=VALUE')).toHaveAttribute('rows', '8')
    expect(await screen.findByRole('textbox', { name: 'x86 构建命令' })).toHaveAttribute(
      'data-height',
      '256'
    )
    await userEvent.click(screen.getByRole('button', { name: '开始 x86 构建' }))

    await waitFor(() =>
      expect(createProjectBuildValidations).toHaveBeenCalledWith(
        'project-1',
        expect.objectContaining({
          buildScope: 'SOURCE_ONLY',
          environmentVariables: {},
          script: '#!/usr/bin/env bash\nset -eu\nmake -j',
          sourceRevisionId: 'source-1',
          timeoutSeconds: 3600
        })
      )
    )
    expect(invalidateQueries).not.toHaveBeenCalled()
  })

  it('initializes the build command from the latest current build record', async () => {
    getProjectBuildValidationLatest.mockResolvedValue({
      context: { runtimeEnvironmentVersion: 3, sourceRevisionId: 'source-1' },
      execution: null,
      freshness: 'CURRENT',
      result: { command: 'cmake --build build -j8' }
    })

    renderWithQueryClient(
      <X86Build
        projectId="project-1"
        stage={{ id: 'x86-build', order: 8, status: 'pending', subtitle: '', title: 'x86 构建' }}
        visible
        onClose={vi.fn()}
      />
    )

    expect(await screen.findByRole('textbox', { name: 'x86 构建命令' })).toHaveValue(
      'cmake --build build -j8'
    )
  })

  it('does not fall back to the default command when loading the latest build fails', async () => {
    getProjectBuildValidationLatest.mockRejectedValue(new Error('latest unavailable'))

    renderWithQueryClient(
      <X86Build
        projectId="project-1"
        stage={{ id: 'x86-build', order: 8, status: 'pending', subtitle: '', title: 'x86 构建' }}
        visible
        onClose={vi.fn()}
      />
    )

    expect(await screen.findByText('x86 构建前置数据加载失败')).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'x86 构建命令' })).not.toBeInTheDocument()
  })

  it('uses readiness for the execution gate and presents build records in the main view', async () => {
    renderWithQueryClient(
      <X86Build
        projectId="project-1"
        stage={{ id: 'x86-build', order: 8, status: 'pending', subtitle: '', title: 'x86 构建' }}
        visible
        onClose={vi.fn()}
      />
    )

    await waitFor(() =>
      expect(getProjectStageReadiness).toHaveBeenCalledWith('project-1', 'x86-build')
    )
    expect(getProjectBuildValidations).not.toHaveBeenCalled()
    expect(await screen.findByRole('button', { name: '开始 x86 构建' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '构建记录' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '加载构建记录' })).toBeInTheDocument()
  })

  it('shows Bitcode status in the list and refreshes its detail when a record opens', async () => {
    const record: ProjectBuildValidation = {
      id: 'build-1',
      projectId: 'project-1',
      taskId: 'task-1',
      sourceRevisionId: 'source-1',
      environmentId: 'env-source',
      buildScope: 'SOURCE_ONLY',
      sourceDeploymentId: 'deployment-1',
      environmentConfigVersion: 3,
      currentEnvironmentConfigVersion: 3,
      currentRuntimeEnvironment: true,
      command: 'make -j8',
      workingDirectory: '/src',
      timeoutSeconds: 3600,
      exitCode: 0,
      status: 'SUCCEEDED',
      failureCategory: null,
      mergedLogId: null,
      stdoutLogId: null,
      stderrLogId: null,
      startedAt: '2026-09-24T00:00:00Z',
      finishedAt: '2026-09-24T00:01:00Z',
      createdAt: '2026-09-24T00:00:00Z',
      environmentVariables: {},
      environment: { id: 'env-source', name: 'x86 源环境', arch: 'x86_64' },
      bitcode: {
        available: true,
        legacy: false,
        sourceIdentityDigest: null,
        compileContextDigest: null,
        bundleSha256: null,
        status: 'PARTIAL',
        reasonCode: 'TRANSLATION_UNIT_FAILED',
        schemaVersion: 'llvm-bitcode-artifact-set.v1',
        artifactSetId: 'artifact-set-1',
        toolchainId: 'llvm-21.1.8-linux-x64',
        toolchainVersion: '21.1.8',
        plannedCount: 10,
        succeededCount: 8,
        failedCount: 2,
        skippedCount: 0,
        warningCount: 2,
        totalBytes: 1024,
        manifestArtifactId: 'manifest-1',
        bundleArtifactId: 'bundle-1'
      }
    }
    tableRecord.current = record
    getProjectBuildValidation.mockResolvedValue({
      ...record,
      bitcode: {
        ...record.bitcode,
        status: 'READY',
        reasonCode: 'NONE',
        succeededCount: 10,
        failedCount: 0,
        warningCount: 0
      }
    })

    renderWithQueryClient(
      <X86Build
        projectId="project-1"
        stage={{ id: 'x86-build', order: 5, status: 'done', subtitle: '', title: 'x86 构建' }}
        visible
        onClose={vi.fn()}
      />
    )

    expect(await screen.findByText('部分生成')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '详情' }))
    await waitFor(() => expect(getProjectBuildValidation).toHaveBeenCalledWith('build-1'))
    expect(await screen.findByRole('region', { name: 'LLVM Bitcode' })).toHaveTextContent(
      '10/10 个编译单元成功'
    )
    expect(screen.getByRole('region', { name: '构建概要' })).toHaveTextContent('成功')
  })

  it('shows the actionable build context instead of the raw source revision ID', async () => {
    renderWithQueryClient(
      <X86Build
        projectId="project-1"
        stage={{ id: 'x86-build', order: 8, status: 'pending', subtitle: '', title: 'x86 构建' }}
        visible
        onClose={vi.fn()}
      />
    )

    expect(await screen.findByText('可以开始构建')).toBeInTheDocument()
    expect(
      screen.queryByText('构建任务会在 x86 源环境中自动部署或复用当前源码。')
    ).not.toBeInTheDocument()
    expect(screen.getByText('当前源码')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'rvport-demo' })).toHaveAttribute(
      'href',
      'https://github.com/ISCAS/rvport-demo.git'
    )
    expect(screen.getByText('当前环境')).toBeInTheDocument()
    expect(screen.getByText('x86 源环境 / x86_64')).toBeInTheDocument()
    expect(screen.queryByText('部署目录')).not.toBeInTheDocument()
  })

  it('submits the latest command entered in the editor', async () => {
    renderWithQueryClient(
      <X86Build
        projectId="project-1"
        stage={{ id: 'x86-build', order: 8, status: 'pending', subtitle: '', title: 'x86 构建' }}
        visible
        onClose={vi.fn()}
      />
    )

    const commandEditor = await screen.findByRole('textbox', { name: 'x86 构建命令' })
    await userEvent.clear(commandEditor)
    await userEvent.type(commandEditor, 'make clean{enter}make -j8')
    await userEvent.click(screen.getByRole('button', { name: '开始 x86 构建' }))

    await waitFor(() =>
      expect(createProjectBuildValidations).toHaveBeenCalledWith(
        'project-1',
        expect.objectContaining({ script: 'make clean\nmake -j8' })
      )
    )
  })

  it('refreshes preconditions only on the explicit refresh action', async () => {
    renderWithQueryClient(
      <X86Build
        projectId="project-1"
        stage={{ id: 'x86-build', order: 8, status: 'pending', subtitle: '', title: 'x86 构建' }}
        visible
        onClose={vi.fn()}
      />
    )

    await userEvent.click(screen.getByRole('button', { name: '刷新构建信息' }))
    expect(refetch).toHaveBeenCalledTimes(1)
  })

  it('filters build records by the selected task status', async () => {
    renderWithQueryClient(
      <X86Build
        projectId="project-1"
        stage={{ id: 'x86-build', order: 8, status: 'pending', subtitle: '', title: 'x86 构建' }}
        visible
        onClose={vi.fn()}
      />
    )

    await userEvent.selectOptions(
      await screen.findByRole('combobox', { name: '筛选构建状态' }),
      'QUEUED'
    )
    await userEvent.click(screen.getByRole('button', { name: '加载构建记录' }))

    expect(getProjectBuildValidations).toHaveBeenCalledWith(
      'project-1',
      expect.objectContaining({
        buildScope: 'SOURCE_ONLY',
        includeStale: true,
        status: 'QUEUED'
      })
    )
  })

  it('collapses and expands build configuration', async () => {
    renderWithQueryClient(
      <X86Build
        projectId="project-1"
        stage={{ id: 'x86-build', order: 8, status: 'pending', subtitle: '', title: 'x86 构建' }}
        visible
        onClose={vi.fn()}
      />
    )

    await userEvent.click(await screen.findByRole('button', { name: '收起构建配置' }))
    expect(screen.queryByRole('button', { name: '开始 x86 构建' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '展开构建配置' })).toHaveAttribute(
      'aria-expanded',
      'false'
    )

    await userEvent.click(screen.getByRole('button', { name: '展开构建配置' }))
    expect(screen.getByRole('button', { name: '开始 x86 构建' })).toBeInTheDocument()
  })

  it('lets the build task prepare source deployment internally', async () => {
    renderWithQueryClient(
      <X86Build
        projectId="project-1"
        stage={{ id: 'x86-build', order: 8, status: 'pending', subtitle: '', title: 'x86 构建' }}
        visible
        onClose={vi.fn()}
      />
    )

    expect(await screen.findByRole('button', { name: '开始 x86 构建' })).toBeInTheDocument()
    expect(screen.getByText('可以开始构建')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '部署当前源码' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新部署当前源码' })).not.toBeInTheDocument()
  })

  it('requires a configured x86 source environment', async () => {
    buildContext.sourceEnvironment = null
    getProjectStageReadiness.mockResolvedValueOnce({
      context: { runtimeEnvironmentVersion: 3, sourceRevisionId: 'source-1' },
      prerequisites: [
        {
          details: {},
          key: 'ACTIVE_SOURCE',
          reasonCode: 'ACTIVE_SOURCE_READY',
          referenceId: 'source-1',
          status: 'SATISFIED'
        },
        {
          details: {},
          key: 'SOURCE_ENVIRONMENT',
          reasonCode: 'SOURCE_ENVIRONMENT_MISSING',
          referenceId: null,
          status: 'MISSING'
        }
      ],
      stageId: 'x86-build'
    })

    renderWithQueryClient(
      <X86Build
        projectId="project-1"
        stage={{ id: 'x86-build', order: 8, status: 'pending', subtitle: '', title: 'x86 构建' }}
        visible
        onClose={vi.fn()}
      />
    )

    expect(screen.queryByRole('heading', { name: '构建配置' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '开始 x86 构建' })).not.toBeInTheDocument()
    expect(await screen.findByRole('button', { name: '前往环境管理' })).toBeInTheDocument()
  })

  it('shows both recovery actions when source and environment prerequisites are missing', async () => {
    buildContext.activeSourceRevisionId = null
    buildContext.activeSourceName = null
    buildContext.activeSourceRepositoryUrl = null
    buildContext.runtimeEnvironmentReady = false
    buildContext.sourceEnvironment = null
    getProjectStageReadiness.mockResolvedValueOnce({
      context: { runtimeEnvironmentVersion: 3, sourceRevisionId: null },
      prerequisites: [
        {
          details: {},
          key: 'ACTIVE_SOURCE',
          reasonCode: 'ACTIVE_SOURCE_MISSING',
          referenceId: null,
          status: 'MISSING'
        },
        {
          details: {},
          key: 'SOURCE_ENVIRONMENT',
          reasonCode: 'SOURCE_ENVIRONMENT_MISSING',
          referenceId: null,
          status: 'MISSING'
        }
      ],
      stageId: 'x86-build'
    })
    const onStageChange = vi.fn()

    renderWithQueryClient(
      <X86Build
        projectId="project-1"
        stage={{ id: 'x86-build', order: 8, status: 'pending', subtitle: '', title: 'x86 构建' }}
        visible
        onClose={vi.fn()}
        onStageChange={onStageChange}
      />
    )

    expect((await screen.findAllByText('尚未配置')).length).toBeGreaterThan(0)
    await userEvent.click(screen.getByRole('button', { name: '前往源码管理' }))
    await userEvent.click(screen.getByRole('button', { name: '前往环境管理' }))
    expect(onStageChange).toHaveBeenNthCalledWith(1, 'assets')
    expect(onStageChange).toHaveBeenNthCalledWith(2, 'env')
  })

  it('preserves chronological lines while prepending older log pages', () => {
    const content = composeTaskLogContent([
      {
        content: 'link-complete\nbuild-finished',
        hasMore: true,
        lines: 100,
        logId: 'log-1',
        offset: 0,
        returnedLines: 2,
        totalLines: 4,
        type: 'merged'
      },
      {
        content: 'configure\ncompile-start',
        hasMore: false,
        lines: 100,
        logId: 'log-1',
        offset: 2,
        returnedLines: 2,
        totalLines: 4,
        type: 'merged'
      }
    ])

    expect(content).toBe('configure\ncompile-start\nlink-complete\nbuild-finished')
  })

  it('derives the visible log line offset from backend totals', () => {
    expect(
      getTaskLogLineNumberOffset([
        {
          content: 'latest lines',
          hasMore: true,
          lines: 100,
          logId: 'log-1',
          offset: 0,
          returnedLines: 100,
          totalLines: 1472,
          type: 'merged'
        }
      ])
    ).toBe(1373)
  })
})
