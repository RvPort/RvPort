import { Fragment, type ReactNode } from 'react'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { renderWithStrictQueryClient as renderWithQueryClient } from '@/test/strict-mode-utils'
import type { WorkerInstance } from '@/types/admin/workers'

const { api, table } = vi.hoisted(() => ({
  api: {
    getAdminWorkers: vi.fn(),
    updateAdminWorkerConcurrency: vi.fn()
  },
  table: {
    props: null as Record<string, unknown> | null,
    reload: vi.fn()
  }
}))

const worker: WorkerInstance = {
  activeConcurrency: 2,
  activeTaskCount: 2,
  appVersion: '1.2.0',
  concurrencyLimit: 4,
  createdAt: '2026-09-01T08:00:00Z',
  hostname: 'build-host',
  instanceId: 'worker-1',
  lastHeartbeatAt: '2026-09-02T08:00:00Z',
  processId: 1024,
  startedAt: '2026-09-01T08:00:00Z',
  status: 'ONLINE',
  updatedAt: '2026-09-02T08:00:00Z'
}
let displayedWorkers = [worker]

vi.mock('@/api/admin/workers', () => api)
vi.mock('@/components/BaseTable', async () => {
  const React = await import('react')
  return {
    default: React.forwardRef((props: Record<string, unknown>, ref) => {
      table.props = props
      React.useImperativeHandle(ref, () => ({ reload: table.reload }))
      const columns = props.columns as Array<{
        key?: string
        dataIndex?: keyof WorkerInstance
        render?: (value: unknown, record: WorkerInstance) => ReactNode
      }>
      const toolbar = props.toolbar as { actions?: ReactNode }
      return (
        <Fragment>
          {toolbar.actions}
          {displayedWorkers.map((row) => (
            <section key={row.instanceId}>
              {columns.map((column, index) => (
                <div key={column.key ?? column.dataIndex ?? index}>
                  {column.render?.(column.dataIndex ? row[column.dataIndex] : undefined, row)}
                </div>
              ))}
            </section>
          ))}
        </Fragment>
      )
    })
  }
})
vi.mock('./components/WorkerDetailDrawer', () => ({
  default: ({
    workerInstanceId,
    onClose
  }: {
    workerInstanceId: string | null
    onClose: () => void
  }) =>
    workerInstanceId ? (
      <aside aria-label="Worker详情">
        {workerInstanceId}
        <button onClick={onClose}>关闭详情</button>
      </aside>
    ) : null
}))
vi.mock('./components/ConcurrencyModal', () => ({
  default: ({
    onSubmit,
    visible
  }: {
    onSubmit: (value: number) => Promise<void>
    visible: boolean
  }) =>
    visible ? (
      <>
        <button onClick={() => void onSubmit(0)}>确认并发为 0</button>
        <button onClick={() => void onSubmit(6)}>确认并发为 6</button>
      </>
    ) : null
}))

import AdminWorkersPage from '.'

describe('AdminWorkersPage', () => {
  beforeEach(() => {
    displayedWorkers = [worker]
  })
  it.each([
    [{ hasRunningTasks: undefined, sortOrder: undefined }, undefined, undefined],
    [{ hasRunningTasks: 'false', sortOrder: 'ascend' }, false, 'asc']
  ])(
    'preserves an unset filter or an explicit idle filter: %j',
    async (filters, hasRunningTasks, sortDirection) => {
      renderWithQueryClient(<AdminWorkersPage />)
      const request = table.props?.request as (query: Record<string, unknown>) => Promise<unknown>
      await request({ page: 1, pageSize: 10, ...filters })
      expect(api.getAdminWorkers).toHaveBeenCalledExactlyOnceWith(
        expect.objectContaining({ hasRunningTasks, sortDirection, page: 1, pageSize: 10 })
      )
    }
  )

  it('shows an offline paused worker, opens and closes its details, and resumes consumption with a positive concurrency', async () => {
    displayedWorkers = [{ ...worker, status: 'OFFLINE', concurrencyLimit: 0 }]
    api.updateAdminWorkerConcurrency.mockResolvedValue({ ...worker, concurrencyLimit: 6 })
    const actor = userEvent.setup()
    renderWithQueryClient(<AdminWorkersPage />)
    expect(screen.getByText('离线')).toBeInTheDocument()
    expect(screen.getByText('已暂停接单')).toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: 'worker-1' }))
    expect(screen.getByLabelText('Worker详情')).toHaveTextContent('worker-1')
    await actor.click(screen.getByRole('button', { name: '关闭详情' }))
    expect(screen.queryByLabelText('Worker详情')).not.toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: '设置 Worker 并发' }))
    await actor.click(screen.getByRole('button', { name: '确认并发为 6' }))
    await waitFor(() =>
      expect(api.updateAdminWorkerConcurrency).toHaveBeenCalledExactlyOnceWith('worker-1', 6)
    )
    await waitFor(() => expect(table.reload).toHaveBeenCalledOnce())
    await actor.click(screen.getByRole('button', { name: /刷\s*新/ }))
    expect(table.reload).toHaveBeenCalledTimes(2)
  })
  it('maps list filters to the backend contract and updates Worker concurrency', async () => {
    api.getAdminWorkers.mockResolvedValue({ items: [], page: 2, pageSize: 20, total: 0 })
    api.updateAdminWorkerConcurrency.mockResolvedValue({ ...worker, concurrencyLimit: 0 })
    table.reload.mockResolvedValue(undefined)
    const user = userEvent.setup()

    renderWithQueryClient(<AdminWorkersPage />)

    expect(table.props?.autoHeight).toBeUndefined()
    const request = table.props?.request as (query: Record<string, unknown>) => Promise<unknown>
    await request({
      hasRunningTasks: 'true',
      hostname: 'build-host',
      instanceId: 'worker-1',
      page: 2,
      pageSize: 20,
      sortBy: 'lastHeartbeatAt',
      sortOrder: 'descend',
      status: 'ONLINE'
    })
    expect(api.getAdminWorkers).toHaveBeenCalledWith({
      hasRunningTasks: true,
      hostname: 'build-host',
      instanceId: 'worker-1',
      page: 2,
      pageSize: 20,
      sortBy: 'lastHeartbeatAt',
      sortDirection: 'desc',
      status: 'ONLINE'
    })

    await user.hover(screen.getByRole('button', { name: '查看 Worker 详情' }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent('查看 Worker 详情')

    await user.click(screen.getByRole('button', { name: '设置 Worker 并发' }))
    await user.click(screen.getByRole('button', { name: '确认并发为 0' }))

    await waitFor(() => {
      expect(api.updateAdminWorkerConcurrency).toHaveBeenCalledWith('worker-1', 0)
      expect(table.reload).toHaveBeenCalled()
    })
  })
})
