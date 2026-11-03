import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { advanceAuthSession } from '@/store/authSession'
import { createTestQueryClient } from '@/test/test-utils'
import type { ProjectTaskLogIncrementResponse } from '@/types/project/task-log'

const { getProjectTaskLogIncrements } = vi.hoisted(() => ({
  getProjectTaskLogIncrements: vi.fn()
}))

vi.mock('@/api/project/build-validation', () => ({ getProjectTaskLogIncrements }))

import { useTaskLogStream } from './useTaskLogStream'

class MockEventSource {
  static instances: MockEventSource[] = []
  readonly listeners = new Map<string, EventListener>()
  readonly close = vi.fn()
  readonly url: string
  readonly options?: EventSourceInit
  onerror: ((event: Event) => void) | null = null
  onopen: ((event: Event) => void) | null = null

  constructor(url: string, options?: EventSourceInit) {
    this.url = url
    this.options = options
    MockEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: EventListener) {
    this.listeners.set(type, listener)
  }

  emit(type: string, data: unknown) {
    this.listeners.get(type)?.(
      new MessageEvent(type, { data: JSON.stringify(data) }) as unknown as Event
    )
  }
}

function response(
  latestEventId: number,
  content = '',
  hasMore = false
): ProjectTaskLogIncrementResponse {
  return {
    taskId: 'task-1',
    fromEventId: content ? latestEventId : null,
    latestEventId,
    hasMore,
    logs: {
      merged: { content, returnedEvents: content ? 1 : 0 },
      stdout: { content, returnedEvents: content ? 1 : 0 },
      stderr: { content: '', returnedEvents: 0 }
    }
  }
}

function createWrapper() {
  const queryClient = createTestQueryClient()
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
}

describe('useTaskLogStream', () => {
  beforeEach(() => {
    MockEventSource.instances = []
    vi.stubGlobal('EventSource', MockEventSource)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('subscribes with credentials and drains all available increments serially', async () => {
    getProjectTaskLogIncrements
      .mockResolvedValueOnce(response(5, 'first\n', true))
      .mockResolvedValueOnce(response(8, 'second\n'))

    const { result, unmount } = renderHook(
      () => useTaskLogStream({ enabled: true, taskId: 'task-1' }),
      { wrapper: createWrapper() }
    )

    expect(MockEventSource.instances[0]).toMatchObject({
      url: '/api/tasks/task-1/log-notifications?afterEventId=0',
      options: { withCredentials: true }
    })
    await waitFor(() => expect(result.current.cursor).toBe(8))
    expect(result.current.buffers.merged).toBe('first\nsecond\n')
    expect(getProjectTaskLogIncrements).toHaveBeenNthCalledWith(1, 'task-1', {
      afterEventId: 0,
      limit: 500
    })
    expect(getProjectTaskLogIncrements).toHaveBeenNthCalledWith(2, 'task-1', {
      afterEventId: 5,
      limit: 500
    })

    unmount()
    expect(MockEventSource.instances[0].close).toHaveBeenCalledOnce()
  })

  it('keeps the successful cursor when a later increment fails and exposes retry', async () => {
    getProjectTaskLogIncrements
      .mockResolvedValueOnce(response(4, 'kept\n'))
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(response(7, 'recovered\n'))

    const { result } = renderHook(() => useTaskLogStream({ enabled: true, taskId: 'task-1' }), {
      wrapper: createWrapper()
    })
    await waitFor(() => expect(result.current.cursor).toBe(4))

    act(() => {
      MockEventSource.instances[0].emit('task.log.updated', {
        taskId: 'task-1',
        latestEventId: 7,
        status: 'RUNNING'
      })
    })
    await waitFor(() => expect(result.current.error?.message).toBe('offline'))
    expect(result.current.cursor).toBe(4)

    act(() => result.current.retry())
    await waitFor(() => expect(result.current.cursor).toBe(7))
    expect(result.current.buffers.stdout).toBe('kept\nrecovered\n')
  })

  it('drains through the completed cursor before notifying the caller', async () => {
    const onCompleted = vi.fn()
    getProjectTaskLogIncrements
      .mockResolvedValueOnce(response(2, 'running\n'))
      .mockResolvedValueOnce(response(3, 'final\n'))

    const { result } = renderHook(
      () => useTaskLogStream({ enabled: true, taskId: 'task-1', onCompleted }),
      { wrapper: createWrapper() }
    )
    await waitFor(() => expect(result.current.cursor).toBe(2))

    act(() => {
      MockEventSource.instances[0].emit('task.log.completed', {
        taskId: 'task-1',
        latestEventId: 3,
        status: 'SUCCEEDED',
        message: 'done'
      })
    })

    await waitFor(() => expect(result.current.completed).toBe(true))
    expect(result.current.cursor).toBe(3)
    expect(result.current.buffers.merged).toBe('running\nfinal\n')
    expect(onCompleted).toHaveBeenCalledWith(
      expect.objectContaining({ latestEventId: 3, status: 'SUCCEEDED' })
    )
  })

  it('reconnects with the last successful cursor and checks for missed content', async () => {
    getProjectTaskLogIncrements
      .mockResolvedValueOnce(response(6, 'before disconnect\n'))
      .mockResolvedValueOnce(response(9, 'after reconnect\n'))

    const { result } = renderHook(() => useTaskLogStream({ enabled: true, taskId: 'task-1' }), {
      wrapper: createWrapper()
    })
    await waitFor(() => expect(result.current.cursor).toBe(6))

    vi.useFakeTimers()
    act(() => MockEventSource.instances[0].onerror?.(new Event('error')))
    await act(async () => vi.advanceTimersByTimeAsync(1_000))

    expect(MockEventSource.instances[1].url).toBe(
      '/api/tasks/task-1/log-notifications?afterEventId=6'
    )
    act(() => MockEventSource.instances[1].onopen?.(new Event('open')))
    await vi.waitFor(() => expect(result.current.cursor).toBe(9))
    expect(result.current.buffers.merged).toContain('after reconnect')
  })
  it.each(['success', 'failure'])(
    'isolates the old drain %s and cursor from a replacement task',
    async (outcome) => {
      let resolveOld!: (value: ProjectTaskLogIncrementResponse) => void
      let rejectOld!: (error: Error) => void
      getProjectTaskLogIncrements
        .mockImplementationOnce(
          () =>
            new Promise((yes, no) => {
              resolveOld = yes
              rejectOld = no
            })
        )
        .mockResolvedValueOnce(response(9, 'new task'))
        .mockResolvedValue(response(10, 'next'))
      const onCompleted = vi.fn()
      const { result, rerender } = renderHook(
        ({ id, enabled }) => useTaskLogStream({ enabled, taskId: id, onCompleted }),
        { initialProps: { id: 'A', enabled: true }, wrapper: createWrapper() }
      )
      await waitFor(() => expect(getProjectTaskLogIncrements).toHaveBeenCalledTimes(1))
      const oldSource = MockEventSource.instances[0]
      rerender({ id: 'B', enabled: true })
      await waitFor(() => expect(result.current.cursor).toBe(9))
      await act(async () => {
        if (outcome === 'success') resolveOld(response(50, 'old task'))
        else rejectOld(new Error('old error'))
      })
      expect(result.current.cursor).toBe(9)
      expect(result.current.buffers.merged).toBe('new task')
      act(() => oldSource.emit('task.log.completed', { taskId: 'A', latestEventId: 50 }))
      expect(onCompleted).not.toHaveBeenCalled()
      act(() =>
        MockEventSource.instances
          .at(-1)!
          .emit('task.log.updated', { taskId: 'B', latestEventId: 10 })
      )
      await waitFor(() =>
        expect(getProjectTaskLogIncrements).toHaveBeenLastCalledWith('B', {
          afterEventId: 9,
          limit: 500
        })
      )
      await waitFor(() => expect(result.current.cursor).toBe(10))
    }
  )
  it.each(['retry', 'reconnect'])(
    'cleans the %s timer and restarts the same task with a fresh cursor',
    async (kind) => {
      getProjectTaskLogIncrements.mockResolvedValue(response(4, 'old'))
      const completed = vi.fn()
      const { result, rerender } = renderHook(
        ({ enabled }) => useTaskLogStream({ enabled, taskId: 'task-1', onCompleted: completed }),
        { initialProps: { enabled: true }, wrapper: createWrapper() }
      )
      await waitFor(() => expect(result.current.cursor).toBe(4))
      const oldSource = MockEventSource.instances[0]
      if (kind === 'retry') {
        getProjectTaskLogIncrements.mockRejectedValueOnce(new Error('retry me'))
        act(() => oldSource.emit('task.log.updated', { taskId: 'task-1', latestEventId: 5 }))
        await waitFor(() => expect(result.current.error).not.toBeNull())
      }
      vi.useFakeTimers()
      if (kind === 'reconnect') act(() => oldSource.onerror?.(new Event('error')))
      rerender({ enabled: false })
      const count = getProjectTaskLogIncrements.mock.calls.length
      await act(async () => {
        await vi.advanceTimersByTimeAsync(3000)
      })
      expect(getProjectTaskLogIncrements).toHaveBeenCalledTimes(count)
      expect(MockEventSource.instances).toHaveLength(1)
      getProjectTaskLogIncrements.mockResolvedValue(response(7, 'fresh'))
      rerender({ enabled: true })
      await vi.waitFor(() => expect(result.current.cursor).toBe(7))
      expect(getProjectTaskLogIncrements).toHaveBeenLastCalledWith('task-1', {
        afterEventId: 0,
        limit: 500
      })
      expect(result.current.buffers.merged).toBe('fresh')
      act(() => oldSource.emit('task.log.completed', { taskId: 'task-1', latestEventId: 50 }))
      expect(completed).not.toHaveBeenCalled()
      act(() =>
        MockEventSource.instances
          .at(-1)!
          .emit('task.log.completed', { taskId: 'task-1', latestEventId: 7 })
      )
      await vi.waitFor(() => expect(completed).toHaveBeenCalledOnce())
    }
  )
  it('synchronously closes the old source and ignores old events across an auth change', async () => {
    getProjectTaskLogIncrements.mockResolvedValue(response(4, 'old'))
    const { result, unmount } = renderHook(
      () => useTaskLogStream({ enabled: true, taskId: 'task-1' }),
      { wrapper: createWrapper() }
    )
    await waitFor(() => expect(result.current.cursor).toBe(4))
    const old = MockEventSource.instances[0]
    await act(async () => {
      advanceAuthSession()
      expect(old.close).toHaveBeenCalledOnce()
      old.emit('task.log.updated', { taskId: 'task-1', latestEventId: 7 })
    })
    await waitFor(() => expect(getProjectTaskLogIncrements).toHaveBeenCalledTimes(2))
    expect(getProjectTaskLogIncrements).toHaveBeenLastCalledWith('task-1', {
      afterEventId: 0,
      limit: 500
    })
    unmount()
  })
  it('ignores malformed and foreign notifications and polls only when the document becomes visible', async () => {
    getProjectTaskLogIncrements.mockResolvedValue(response(2, 'current'))
    const { result } = renderHook(() => useTaskLogStream({ enabled: true, taskId: 'task-1' }), {
      wrapper: createWrapper()
    })
    await waitFor(() => expect(result.current.isFetching).toBe(false))
    const source = MockEventSource.instances[0]
    act(() => {
      source.onopen?.(new Event('open'))
      for (const type of ['task.log.updated', 'task.log.completed']) {
        source.listeners.get(type)!(new MessageEvent(type, { data: 'invalid json' }))
        source.emit(type, { taskId: 'foreign', latestEventId: 99 })
      }
    })
    expect(result.current.connectionStatus).toBe('connected')
    expect(getProjectTaskLogIncrements).toHaveBeenCalledOnce()
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    act(() => document.dispatchEvent(new Event('visibilitychange')))
    expect(getProjectTaskLogIncrements).toHaveBeenCalledOnce()
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    act(() => document.dispatchEvent(new Event('visibilitychange')))
    await waitFor(() => expect(getProjectTaskLogIncrements).toHaveBeenCalledTimes(2))
  })
  it('automatically retries non-Error read failures and uses the latest completion callback once', async () => {
    getProjectTaskLogIncrements
      .mockRejectedValueOnce('offline')
      .mockResolvedValue(response(3, 'recovered'))
    vi.useFakeTimers()
    let completed = vi.fn()
    const { result, rerender } = renderHook(
      () => useTaskLogStream({ enabled: true, taskId: 'task-1', onCompleted: completed }),
      { wrapper: createWrapper() }
    )
    await vi.waitFor(() => expect(result.current.error?.message).toBe('实时日志读取失败'))
    await act(async () => vi.advanceTimersByTimeAsync(1500))
    await vi.waitFor(() => expect(result.current.cursor).toBe(3))
    const old = completed
    completed = vi.fn()
    rerender()
    const source = MockEventSource.instances[0]
    act(() =>
      source.emit('task.log.completed', { taskId: 'task-1', latestEventId: 3, status: 'SUCCEEDED' })
    )
    await vi.waitFor(() => expect(completed).toHaveBeenCalledOnce())
    expect(old).not.toHaveBeenCalled()
    const calls = getProjectTaskLogIncrements.mock.calls.length
    act(() => {
      result.current.retry()
      source.emit('task.log.updated', { taskId: 'task-1', latestEventId: 4 })
    })
    expect(getProjectTaskLogIncrements).toHaveBeenCalledTimes(calls)
  })
  it('ignores browser callbacks queued before a task switch', async () => {
    getProjectTaskLogIncrements.mockResolvedValue(response(1))
    const { result, rerender } = renderHook(
      ({ taskId }) => useTaskLogStream({ enabled: true, taskId }),
      { initialProps: { taskId: 'task-1' }, wrapper: createWrapper() }
    )
    await waitFor(() => expect(result.current.isFetching).toBe(false))
    const old = MockEventSource.instances[0],
      open = old.onopen!,
      error = old.onerror!
    const completed = old.listeners.get('task.log.completed')!
    rerender({ taskId: 'task-2' })
    await waitFor(() => expect(getProjectTaskLogIncrements).toHaveBeenCalledTimes(2))
    act(() => {
      open(new Event('open'))
      error(new Event('error'))
      completed(
        new MessageEvent('task.log.completed', {
          data: JSON.stringify({ taskId: 'task-1', latestEventId: 99 })
        })
      )
    })
    expect(MockEventSource.instances).toHaveLength(2)
    expect(result.current.completed).toBe(false)
    expect(result.current.connectionStatus).toBe('connecting')
  })
  it('keeps disabled or unsupported stream sessions safe to retry', async () => {
    getProjectTaskLogIncrements.mockResolvedValue(response(1))
    vi.stubGlobal('EventSource', undefined)
    const { result, rerender } = renderHook(
      ({ enabled }) => useTaskLogStream({ enabled, taskId: 'task-1' }),
      { initialProps: { enabled: false }, wrapper: createWrapper() }
    )
    act(() => result.current.retry())
    expect(getProjectTaskLogIncrements).not.toHaveBeenCalled()
    rerender({ enabled: true })
    expect(result.current.connectionStatus).toBe('idle')
    await waitFor(() => expect(result.current.isFetching).toBe(false))
    expect(result.current.cursor).toBe(1)
  })
})
