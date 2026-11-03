import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { renderWithQueryClient } from '@/test/test-utils'
import type { ProjectEnvironmentScriptExecution } from '@/types/project'

const { taskLogPanel } = vi.hoisted(() => ({ taskLogPanel: vi.fn() }))

vi.mock('../../components/TaskLogPanel', () => ({
  TaskLogPanel: (props: Record<string, unknown>) => {
    taskLogPanel(props)
    return <div>统一任务日志面板</div>
  }
}))

import { LogView } from './views'

describe('environment execution log view', () => {
  it('delegates running and completed log behavior to the shared task log panel', () => {
    const onTaskCompleted = vi.fn()
    renderWithQueryClient(
      <LogView
        execution={{
          id: 'task-env-1',
          environmentId: 'env-1',
          scriptType: 'CUSTOM',
          scriptName: '检查工具链',
          submittedAt: '2026-09-11T00:00:00Z',
          startedAt: '2026-09-11T00:00:01Z',
          finishedAt: null,
          progress: 50,
          message: '执行中',
          scriptSha256: null,
          scriptLength: 10,
          logEventCount: 2,
          exitCode: null,
          status: 'RUNNING',
          stdout: '',
          stderr: ''
        }}
        onTaskCompleted={onTaskCompleted}
      />
    )

    expect(screen.getByText('统一任务日志面板')).toBeInTheDocument()
    expect(taskLogPanel).toHaveBeenCalledWith(
      expect.objectContaining({
        status: 'RUNNING',
        taskId: 'task-env-1',
        onTaskCompleted
      })
    )
  })

  it('moves through loading, error, retry and absent execution states', async () => {
    const onRetry = vi.fn()
    const view = renderWithQueryClient(<LogView loading onRetry={onRetry} />)
    expect(screen.getByText('正在加载执行详情')).toBeInTheDocument()
    view.rerender(<LogView error onRetry={onRetry} />)
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }))
    expect(onRetry).toHaveBeenCalledOnce()
    view.rerender(<LogView />)
    expect(screen.getByText('暂无执行记录')).toBeInTheDocument()
  })

  it('refreshes independent execution metadata and rebinds the completion handler without losing log context', () => {
    let execution: ProjectEnvironmentScriptExecution = {
      id: 'execution-1',
      environmentId: 'env-1',
      scriptType: 'CUSTOM',
      scriptName: '脚本',
      submittedAt: '2026-10-28T00:00:00Z',
      startedAt: null,
      finishedAt: null,
      progress: 0,
      message: '准备中',
      scriptSha256: null,
      scriptLength: 10,
      logEventCount: 0,
      exitCode: null,
      status: 'RUNNING',
      stdout: '',
      stderr: ''
    }
    const completed = vi.fn()
    const view = renderWithQueryClient(
      <LogView execution={execution} onTaskCompleted={completed} />
    )
    const updates: Array<[Partial<ProjectEnvironmentScriptExecution>, string]> = [
      [{ scriptName: '更新脚本' }, '更新脚本'],
      [{ message: '已采集环境' }, '已采集环境'],
      [{ submittedAt: '2026-10-28T00:01:00Z' }, '2026-10-28 08:01'],
      [{ logEventCount: 7 }, '7 条'],
      [{ exitCode: 0 }, '0'],
      [{ status: 'SUCCEEDED' }, '成功'],
      [{ id: 'execution-2' }, '统一任务日志面板']
    ]
    for (const [patch, expected] of updates) {
      execution = { ...execution, ...patch }
      view.rerender(<LogView execution={execution} onTaskCompleted={completed} />)
      expect(screen.getByText(expected)).toBeInTheDocument()
      expect(taskLogPanel).toHaveBeenLastCalledWith(
        expect.objectContaining({
          taskId: execution.id,
          status: execution.status,
          onTaskCompleted: completed
        })
      )
    }
    const nextCompleted = vi.fn()
    view.rerender(<LogView execution={execution} onTaskCompleted={nextCompleted} />)
    expect(taskLogPanel).toHaveBeenLastCalledWith(
      expect.objectContaining({ onTaskCompleted: nextCompleted })
    )
    view.rerender(<LogView execution={{ ...execution, message: null, exitCode: null }} />)
    expect(screen.queryByText('已采集环境')).not.toBeInTheDocument()
    expect(screen.getAllByText('--').length).toBeGreaterThan(0)
  })
})
