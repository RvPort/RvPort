import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import { renderWithQueryClient } from '@/test/test-utils'
import { createWorker, createWorkerTask } from '@/test/worker-fixtures'
import type { WorkerInstance } from '@/types/admin/workers'
import { feedback } from '@/utils/feedback'

import { adminQueryKeys } from '../../queryKeys'

const { cancelAdminWorkerTask, getAdminWorker } = vi.hoisted(() => ({
  cancelAdminWorkerTask: vi.fn(),
  getAdminWorker: vi.fn()
}))

vi.mock('@/api/admin/workers', () => ({ cancelAdminWorkerTask, getAdminWorker }))
vi.mock('@/components/BaseDrawer', () => ({
  default: ({
    children,
    footer,
    title,
    visible
  }: {
    children: ReactNode
    footer?: ReactNode
    title?: ReactNode
    visible?: boolean
  }) =>
    visible ? (
      <section>
        <h2>{title}</h2>
        {children}
        {footer}
      </section>
    ) : null
}))

import WorkerDetailDrawer from './WorkerDetailDrawer'

describe('WorkerDetailDrawer', () => {
  it('updates independent worker fields and configuration callbacks after refresh without losing identity', async () => {
    let worker: WorkerInstance = {
      instanceId: 'worker-1',
      hostname: 'host-1',
      processId: 10,
      appVersion: '1.0',
      status: 'ONLINE',
      concurrencyLimit: 4,
      activeConcurrency: 1,
      activeTaskCount: 0,
      startedAt: '2026-10-28T00:00:00Z',
      createdAt: '2026-10-28T00:00:00Z',
      updatedAt: '2026-10-28T00:00:00Z',
      lastHeartbeatAt: '2026-10-28T00:00:00Z'
    }
    getAdminWorker.mockResolvedValue({ worker, runningTasks: [] })
    const configure = vi.fn()
    const view = renderWithQueryClient(
      <WorkerDetailDrawer workerInstanceId="worker-1" onClose={vi.fn()} onConfigure={configure} />
    )
    await screen.findByText('host-1')
    const patches: Array<[Partial<WorkerInstance>, string]> = [
      [{ hostname: 'host-2' }, 'host-2'],
      [{ processId: 20 }, '20'],
      [{ appVersion: '2.0' }, '2.0'],
      [{ status: 'OFFLINE' }, '离线'],
      [{ concurrencyLimit: 0 }, '已暂停接单'],
      [{ activeConcurrency: 2 }, '期望 0 / 实际 2'],
      [{ activeTaskCount: 3 }, '3'],
      [{ startedAt: '2026-10-28T00:01:00Z' }, '2026-10-28 08:01'],
      [{ lastHeartbeatAt: '2026-10-28T00:02:00Z' }, '2026-10-28 08:02'],
      [{ createdAt: '2026-10-28T00:03:00Z' }, '2026-10-28 08:03'],
      [{ updatedAt: '2026-10-28T00:04:00Z' }, '2026-10-28 08:04']
    ]
    for (const [patch, expected] of patches) {
      worker = { ...worker, ...patch }
      act(() =>
        view.queryClient.setQueryData(adminQueryKeys.worker('worker-1'), {
          worker,
          runningTasks: []
        })
      )
      await waitFor(() => expect(screen.getByText(expected)).toBeInTheDocument())
      expect(screen.getByText('worker-1')).toBeInTheDocument()
    }
    const next = vi.fn()
    view.rerender(
      <WorkerDetailDrawer workerInstanceId="worker-1" onClose={vi.fn()} onConfigure={next} />
    )
    fireEvent.click(screen.getByRole('button', { name: /设置并发/ }))
    expect(next).toHaveBeenCalledWith(worker)
    expect(configure).not.toHaveBeenCalled()
    view.rerender(
      <WorkerDetailDrawer workerInstanceId={null} onClose={vi.fn()} onConfigure={next} />
    )
    expect(screen.queryByText('worker-1')).not.toBeInTheDocument()
  })
  it('shows full instance information and current running tasks', async () => {
    getAdminWorker.mockResolvedValue({
      runningTasks: [
        {
          message: '正在执行测试',
          progress: 35,
          projectId: 'project-1',
          projectName: 'RISC-V 迁移项目',
          startedAt: '2026-09-02T08:05:00Z',
          status: 'RUNNING',
          taskId: 'task-1',
          type: 'AUTOMATED_TEST'
        }
      ],
      worker: {
        activeConcurrency: 1,
        activeTaskCount: 1,
        appVersion: '1.2.0',
        concurrencyLimit: 4,
        createdAt: '2026-09-01T08:00:00Z',
        hostname: 'build-host',
        instanceId: 'worker-1-full-instance-id',
        lastHeartbeatAt: '2026-09-02T08:10:00Z',
        processId: 1024,
        startedAt: '2026-09-01T08:00:00Z',
        status: 'ONLINE',
        updatedAt: '2026-09-02T08:10:00Z'
      }
    })

    renderWithQueryClient(
      <WorkerDetailDrawer
        workerInstanceId="worker-1-full-instance-id"
        onClose={vi.fn()}
        onConfigure={vi.fn()}
      />
    )

    expect(await screen.findByText('worker-1-full-instance-id')).toBeInTheDocument()
    expect(screen.getByText('build-host')).toBeInTheDocument()
    expect(screen.getByText('期望 4 / 实际 1')).toBeInTheDocument()
    expect(screen.getByText('RISC-V 迁移项目')).toBeInTheDocument()
    expect(screen.getByText('正在执行测试')).toBeInTheDocument()
    expect(getAdminWorker).toHaveBeenCalledWith('worker-1-full-instance-id')
  })

  it('cancels a running task and refreshes Worker details', async () => {
    const user = userEvent.setup()
    getAdminWorker.mockResolvedValue({
      runningTasks: [
        {
          message: '正在执行测试',
          progress: 35,
          projectId: 'project-1',
          projectName: 'RISC-V 迁移项目',
          startedAt: '2026-09-02T08:05:00Z',
          status: 'RUNNING',
          taskId: 'task-1',
          type: 'AUTOMATED_TEST'
        }
      ],
      worker: {
        activeConcurrency: 1,
        activeTaskCount: 1,
        appVersion: '1.2.0',
        concurrencyLimit: 4,
        createdAt: '2026-09-01T08:00:00Z',
        hostname: 'build-host',
        instanceId: 'worker-1-full-instance-id',
        lastHeartbeatAt: '2026-09-02T08:10:00Z',
        processId: 1024,
        startedAt: '2026-09-01T08:00:00Z',
        status: 'ONLINE',
        updatedAt: '2026-09-02T08:10:00Z'
      }
    })
    cancelAdminWorkerTask.mockResolvedValue({ id: 'task-1', status: 'CANCELLED' })

    renderWithQueryClient(
      <WorkerDetailDrawer
        workerInstanceId="worker-1-full-instance-id"
        onClose={vi.fn()}
        onConfigure={vi.fn()}
      />
    )

    await user.click(await screen.findByRole('button', { name: '取消' }))
    await user.click(await screen.findByRole('button', { name: '确认取消' }))

    expect(cancelAdminWorkerTask.mock.calls[0]?.[0]).toBe('task-1')
    await waitFor(() => expect(getAdminWorker).toHaveBeenCalledTimes(2))
  })
})

it('locks only the submitted task row, releases a failed cancellation and refreshes after retry', async () => {
  const error = vi.spyOn(feedback, 'error').mockImplementation(() => {})
  let fail!: (reason: Error) => void
  cancelAdminWorkerTask.mockImplementationOnce(
    () =>
      new Promise((_yes, reject) => {
        fail = reject
      })
  )
  const detail = {
    worker: createWorker(),
    runningTasks: [createWorkerTask('task-a'), createWorkerTask('task-b')]
  }
  getAdminWorker.mockResolvedValue(detail)
  renderWithQueryClient(
    <WorkerDetailDrawer workerInstanceId="worker-1" onClose={vi.fn()} onConfigure={vi.fn()} />
  )
  const first = (await screen.findByText('task-a')).closest('tr')!
  const second = screen.getByText('task-b').closest('tr')!
  const cancel = (row: HTMLElement) => within(row).getByRole('button', { name: /取\s*消/ })
  fireEvent.click(cancel(first))
  fireEvent.click(await screen.findByRole('button', { name: '确认取消' }))
  await waitFor(() => expect(cancel(first)).toHaveClass('ant-btn-loading'))
  expect(cancel(second)).toBeDisabled()
  await act(async () => fail(new Error('offline')))
  await waitFor(() => expect(cancel(second)).toBeEnabled())
  expect(error).toHaveBeenCalledWith('offline')
  expect(getAdminWorker).toHaveBeenCalledOnce()
  cancelAdminWorkerTask.mockResolvedValueOnce({})
  await waitFor(() => expect(cancel(first)).not.toHaveClass('ant-btn-loading'))
  fireEvent.click(cancel(first))
  fireEvent.click(await screen.findByRole('button', { name: '确认取消' }))
  await waitFor(() => expect(getAdminWorker).toHaveBeenCalledTimes(2))
  expect(cancelAdminWorkerTask).toHaveBeenNthCalledWith(2, 'task-a', expect.any(Function))
})
it('ignores a cancellation completed after switching to a different worker', async () => {
  const success = vi.spyOn(feedback, 'success').mockImplementation(() => {})
  let finish!: (value: object) => void
  cancelAdminWorkerTask.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      })
  )
  getAdminWorker.mockImplementation(async (id: string) => ({
    worker: createWorker({ instanceId: id, hostname: id }),
    runningTasks: id === 'worker-a' ? [createWorkerTask('old-task')] : []
  }))
  const props = { onClose: vi.fn(), onConfigure: vi.fn() }
  const view = renderWithQueryClient(<WorkerDetailDrawer {...props} workerInstanceId="worker-a" />)
  await screen.findByText('old-task')
  fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }))
  fireEvent.click(await screen.findByRole('button', { name: '确认取消' }))
  await waitFor(() => expect(cancelAdminWorkerTask).toHaveBeenCalledOnce())
  view.rerender(<WorkerDetailDrawer {...props} workerInstanceId="worker-b" />)
  await screen.findAllByText('worker-b')
  const calls = getAdminWorker.mock.calls.length
  await act(async () => finish({}))
  expect(getAdminWorker).toHaveBeenCalledTimes(calls)
  expect(success).not.toHaveBeenCalled()
  expect(screen.queryByText('old-task')).not.toBeInTheDocument()
})
it('retries an initial worker detail failure and keeps the current configure target', async () => {
  getAdminWorker
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValue({ worker: createWorker(), runningTasks: [] })
  const configure = vi.fn()
  renderWithQueryClient(
    <WorkerDetailDrawer workerInstanceId="worker-1" onClose={vi.fn()} onConfigure={configure} />
  )
  fireEvent.click(await screen.findByRole('button', { name: /重\s*试/ }))
  await screen.findByText('build-host')
  fireEvent.click(screen.getByRole('button', { name: /设置并发/ }))
  expect(configure).toHaveBeenCalledWith(expect.objectContaining({ instanceId: 'worker-1' }))
})
