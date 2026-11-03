import { fireEvent, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { createEnvironment, renderWithQueryClient } from '@/test/test-utils'
import type { ProjectEnvironmentScriptExecution } from '@/types/project'

import { projectQueryKeys } from '../../../queryKeys'
import Executions from './Executions'

const api = vi.hoisted(() => ({ getProjectEnvironmentScriptExecutions: vi.fn() }))
vi.mock('@/api/project/environments', () => api)

const execution: ProjectEnvironmentScriptExecution = {
  id: 'execution-1',
  environmentId: 'env-1',
  scriptType: 'CUSTOM',
  scriptName: '检查',
  submittedAt: '2026-10-28T00:00:00Z',
  startedAt: null,
  finishedAt: null,
  progress: null,
  message: null,
  scriptSha256: null,
  scriptLength: 10,
  logEventCount: 0,
  exitCode: null,
  status: 'RUNNING',
  stdout: '',
  stderr: ''
}
const page = (item: ProjectEnvironmentScriptExecution) => ({
  items: [item],
  hasMore: false,
  page: 1,
  pageSize: 20,
  total: 1
})

describe('environment execution records', () => {
  it('updates audit fields and duration boundaries while preserving the selected execution identity', async () => {
    api.getProjectEnvironmentScriptExecutions.mockResolvedValue(page(execution))
    const environment = createEnvironment()
    const onViewLog = vi.fn()
    const view = renderWithQueryClient(
      <Executions projectId="project-1" environment={environment} onViewLog={onViewLog} />
    )
    expect(await screen.findByText('等待执行')).toBeInTheDocument()
    expect(api.getProjectEnvironmentScriptExecutions).toHaveBeenCalledWith('project-1', {
      environmentId: 'env-1',
      page: 1,
      pageSize: 20
    })
    let current = execution
    const patches: Array<[Partial<ProjectEnvironmentScriptExecution>, string]> = [
      [{ status: 'RUNNING' }, '0%'],
      [{ progress: 40 }, '40%'],
      [{ message: '工具检查中' }, '工具检查中'],
      [{ startedAt: '2026-10-28T00:00:00Z' }, '执行中'],
      [{ finishedAt: '2026-10-28T00:00:59Z' }, '59 秒'],
      [{ finishedAt: '2026-10-28T00:01:00Z' }, '1 分'],
      [{ finishedAt: '2026-10-28T00:01:01Z' }, '1 分 1 秒'],
      [{ finishedAt: '2026-10-27T23:59:59Z' }, '0 秒'],
      [{ exitCode: 0 }, '0'],
      [{ logEventCount: 17 }, '17 条'],
      [{ status: 'SUCCEEDED' }, '成功']
    ]
    for (const [patch, expected] of patches) {
      current = { ...current, ...patch }
      view.queryClient.setQueriesData(
        { queryKey: projectQueryKeys.environmentScriptExecutions('project-1', environment.id) },
        page(current)
      )
      await waitFor(() => expect(screen.getByText(expected)).toBeInTheDocument())
    }
    const nextHandler = vi.fn()
    view.rerender(
      <Executions
        projectId="project-1"
        environment={{ ...environment, name: '新名称' }}
        onViewLog={nextHandler}
      />
    )
    expect(screen.getByRole('heading', { name: '新名称 的执行记录' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '查看日志' }))
    expect(nextHandler).toHaveBeenCalledWith(current)
    expect(onViewLog).not.toHaveBeenCalled()
    api.getProjectEnvironmentScriptExecutions.mockResolvedValue(
      page({ ...current, environmentId: 'env-2', id: 'execution-2', message: '第二环境记录' })
    )
    view.rerender(
      <Executions
        projectId="project-2"
        environment={{ ...environment, id: 'env-2' }}
        onViewLog={nextHandler}
      />
    )
    expect(await screen.findByText('第二环境记录')).toBeInTheDocument()
    expect(api.getProjectEnvironmentScriptExecutions).toHaveBeenLastCalledWith('project-2', {
      environmentId: 'env-2',
      page: 1,
      pageSize: 20
    })
  })
})
