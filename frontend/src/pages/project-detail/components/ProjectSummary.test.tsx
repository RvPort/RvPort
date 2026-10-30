import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import type { ProjectOverview, ProjectOverviewTask } from '@/types/project'

import ProjectSummary from './ProjectSummary'

function task(overrides: Partial<ProjectOverviewTask> = {}): ProjectOverviewTask {
  return {
    id: 'task-1',
    type: 'KB_SCAN',
    status: 'RUNNING',
    message: '正在扫描',
    progress: 45,
    createdAt: '2026-10-27T08:00:00Z',
    ...overrides
  }
}

function source(
  overrides: Partial<NonNullable<ProjectOverview['currentContext']['activeSourceRevision']>> = {}
) {
  return {
    active: true,
    id: 'source-1',
    type: 'GIT' as const,
    version: 'main@abc',
    versionNote: '',
    updatedAt: '2026-10-27T08:00:00Z',
    ...overrides
  }
}

function overview(
  overrides: {
    source?: ProjectOverview['currentContext']['activeSourceRevision']
    tasks?: ProjectOverviewTask[]
    sourceEnvironment?: ProjectOverview['currentContext']['runtimeEnvironments']['sourceEnvironment']
    targetEnvironment?: ProjectOverview['currentContext']['runtimeEnvironments']['targetEnvironment']
  } = {}
): ProjectOverview {
  return {
    project: {
      id: 'project-1',
      name: 'RISC-V 移植',
      description: null,
      sourceArch: 'x86_64',
      targetArch: 'riscv64',
      stage: 'PROJECT_CREATED',
      status: 'ACTIVE',
      sourceInputType: null,
      createdBy: 'user-1',
      createdAt: '2026-10-27T08:00:00Z',
      updatedAt: null,
      highRiskCount: 0,
      latestScanAt: null,
      latestBuildStatus: null,
      latestTestStatus: null
    },
    currentContext: {
      activeSourceRevision: overrides.source ?? null,
      runtimeEnvironments: {
        projectId: 'project-1',
        version: 3,
        sourceEnvironment: overrides.sourceEnvironment ?? null,
        targetEnvironment: overrides.targetEnvironment ?? null,
        ready: false,
        missing: [],
        updatedBy: null,
        updatedAt: null
      }
    },
    recentTasks: overrides.tasks ?? [],
    workflow: { recommendedStageId: null, stages: [] },
    latestTaskEventId: 0
  }
}

describe('project summary', () => {
  it.each([false, true])('refreshes independent context fields with compact=%s', (compact) => {
    let current = overview({
      source: source({ repositoryUrl: 'https://example.invalid/original.git' }),
      tasks: [task()]
    })
    const view = render(<ProjectSummary compact={compact} overview={current} />)
    const updates: Array<[Partial<ProjectOverview['project']>, string]> = [
      [{ name: '新项目名称' }, '新项目名称'],
      [{ sourceArch: 'riscv64' }, 'riscv64'],
      [{ targetArch: 'x86_64' }, 'x86_64']
    ]
    for (const [patch, expected] of updates) {
      current = { ...current, project: { ...current.project, ...patch } }
      view.rerender(<ProjectSummary compact={compact} overview={current} />)
      expect(screen.getAllByText(expected).length).toBeGreaterThan(0)
    }
    current = {
      ...current,
      currentContext: {
        ...current.currentContext,
        activeSourceRevision: source({ repositoryUrl: 'https://example.invalid/updated.git' })
      }
    }
    view.rerender(<ProjectSummary compact={compact} overview={current} />)
    expect(screen.getByRole('link', { name: 'updated' })).toHaveAttribute(
      'href',
      'https://example.invalid/updated.git'
    )
    const environment = {
      id: 'env-1',
      name: '刷新源环境',
      role: 'SOURCE' as const,
      architecture: 'x86_64' as const,
      connectionStatus: 'SUCCESS' as const,
      enabled: true,
      configVersion: 1
    }
    current = {
      ...current,
      currentContext: {
        ...current.currentContext,
        runtimeEnvironments: {
          ...current.currentContext.runtimeEnvironments,
          sourceEnvironment: environment
        }
      }
    }
    view.rerender(<ProjectSummary compact={compact} overview={current} />)
    expect(screen.getByText('刷新源环境')).toBeInTheDocument()
    current = {
      ...current,
      currentContext: {
        ...current.currentContext,
        runtimeEnvironments: {
          ...current.currentContext.runtimeEnvironments,
          targetEnvironment: { ...environment, id: 'env-2', name: '刷新目标环境', role: 'TARGET' }
        }
      }
    }
    view.rerender(<ProjectSummary compact={compact} overview={current} />)
    expect(screen.getByText('刷新目标环境')).toBeInTheDocument()
    current = {
      ...current,
      recentTasks: [task({ status: 'SUCCEEDED', progress: 100, message: '任务完成' })]
    }
    view.rerender(<ProjectSummary compact={compact} overview={current} />)
    expect(screen.queryByText('正在扫描')).not.toBeInTheDocument()
    expect(screen.getByText('已完成')).toBeInTheDocument()
  })
  it('explains an empty project context and shows no task trigger', () => {
    render(<ProjectSummary overview={overview()} />)
    const summary = screen.getByRole('region', { name: '项目流水线摘要' })
    expect(within(summary).getByText('源码待配置')).toBeInTheDocument()
    expect(within(summary).getByText('源环境待配置')).toBeInTheDocument()
    expect(within(summary).getByText('目标环境待配置')).toBeInTheDocument()
    expect(within(summary).getByText('暂无任务记录')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /查看最近/ })).not.toBeInTheDocument()
  })

  it('links a Git source and prioritizes an active task over a recent failure', async () => {
    const user = userEvent.setup()
    const running = task({
      id: 'running',
      progress: 125,
      message: null,
      startedAt: '2026-10-27T08:01:00Z'
    })
    render(
      <ProjectSummary
        overview={overview({
          source: source({ repositoryUrl: 'https://example.com/team/sample.git/' }),
          sourceEnvironment: {
            id: 'env-1',
            name: '源端机器',
            role: 'SOURCE',
            architecture: 'x86_64',
            connectionStatus: 'SUCCESS',
            enabled: true,
            configVersion: 1
          },
          targetEnvironment: {
            id: 'env-2',
            name: '目标机器',
            role: 'TARGET',
            architecture: 'riscv64',
            connectionStatus: 'SUCCESS',
            enabled: true,
            configVersion: 1
          },
          tasks: [task({ id: 'failed', status: 'FAILED', progress: null, message: null }), running]
        })}
      />
    )
    expect(screen.getByRole('link', { name: 'sample' })).toHaveAttribute(
      'href',
      'https://example.com/team/sample.git/'
    )
    expect(screen.getByLabelText('当前执行任务')).toHaveTextContent('知识库扫描')
    expect(screen.getByText('任务正在执行')).toBeInTheDocument()
    expect(screen.getByText('源端机器')).toBeInTheDocument()
    expect(screen.getByText('目标机器')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '查看最近 2 条任务' }))
    const list = await screen.findByRole('list', { name: '最近任务列表' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)
    expect(within(list).getByText('当前')).toBeInTheDocument()
    expect(within(list).getByRole('progressbar')).toHaveAttribute('value', '100')
    expect(within(list).getByText('任务已结束')).toBeInTheDocument()
  })

  it('uses archive names and completed task details without a progress bar', () => {
    render(
      <ProjectSummary
        overview={overview({
          source: source({ type: 'ARCHIVE', fileName: 'snapshot.tar.gz', version: 'archive-v1' }),
          tasks: [task({ status: 'SUCCEEDED', progress: null, message: '扫描完成' })]
        })}
      />
    )
    expect(screen.getByText('snapshot.tar.gz')).toBeInTheDocument()
    expect(screen.getByLabelText('最近任务')).toHaveTextContent('已完成')
    expect(screen.getByText('扫描完成')).toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('keeps the compact context readable with and without a configured source', () => {
    const view = render(<ProjectSummary compact overview={overview()} />)
    expect(screen.getByRole('region', { name: '项目紧凑摘要' })).toHaveTextContent('暂无任务')
    expect(screen.getAllByText('环境待配置')).toHaveLength(2)

    view.rerender(
      <ProjectSummary
        compact
        overview={overview({
          source: source({ type: 'ARCHIVE', version: 'archive-v2' }),
          tasks: [task({ status: 'TIMEOUT', message: null, progress: null })]
        })}
      />
    )
    expect(screen.getByText('archive-v2')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '项目紧凑摘要' })).toHaveTextContent('超时')
  })

  it('replaces previous source and task details after a project refresh', async () => {
    const user = userEvent.setup()
    const initial = overview({
      source: source({ type: 'ARCHIVE', fileName: 'old-snapshot.tar.gz' }),
      tasks: [task({ id: 'old-task', status: 'FAILED', message: 'old error' })]
    })
    const view = render(<ProjectSummary overview={initial} />)
    await user.click(screen.getByRole('button', { name: '查看最近 1 条任务' }))
    expect(screen.getAllByText('old error')).toHaveLength(2)
    view.rerender(<ProjectSummary overview={initial} />)

    view.rerender(
      <ProjectSummary
        overview={overview({
          source: source({ repositoryUrl: 'https://example.com/team/new.git', version: 'new@123' }),
          tasks: [task({ id: 'new-task', status: 'RUNNING', message: 'new progress' })]
        })}
      />
    )
    expect(screen.queryByText('old-snapshot.tar.gz')).not.toBeInTheDocument()
    expect(screen.queryAllByText('old error')).toHaveLength(0)
    expect(screen.getByRole('link', { name: 'new' })).toHaveAttribute(
      'href',
      'https://example.com/team/new.git'
    )
    expect(screen.getAllByText('new progress').length).toBeGreaterThan(0)
  })
})
