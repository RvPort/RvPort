import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { advanceAuthSession } from '@/store/authSession'
import { createTestQueryClient } from '@/test/test-utils'
import { appNotification } from '@/utils/appNotification'

import { useProjectTaskEvents } from './useProjectTaskEvents'

class MockEventSource {
  static instances: MockEventSource[] = []

  readonly close = vi.fn()
  readonly addEventListener = vi.fn()
  readonly removeEventListener = vi.fn()
  readonly url: string
  readonly options?: EventSourceInit
  onerror: ((event: Event) => void) | null = null
  onopen: ((event: Event) => void) | null = null

  constructor(url: string, options?: EventSourceInit) {
    this.url = url
    this.options = options
    MockEventSource.instances.push(this)
  }
}

describe('useProjectTaskEvents', () => {
  it.each([
    ['task.failed', 'error', '任务执行失败', '请查看任务详情和执行日志'],
    ['task.timeout', 'error', '任务执行超时', '任务执行超时，请检查环境或重新执行'],
    ['task.cancelled', 'warning', '任务已取消', '任务已停止执行'],
    ['task.succeeded', 'success', '任务已完成', '执行结果已更新']
  ] as const)(
    'provides the correct fallback notification for %s without an event identifier',
    (name, kind, title, description) => {
      const notify = vi.spyOn(appNotification, kind).mockImplementation(() => undefined)
      renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper: createWrapper() })
      const handler = MockEventSource.instances[0].addEventListener.mock.calls.find(
        ([event]) => event === name
      )?.[1] as EventListener
      act(() => handler(new MessageEvent(name, { data: JSON.stringify({ stageId: 'archive' }) })))
      expect(notify).toHaveBeenCalledWith(
        expect.objectContaining({ title, description, key: undefined })
      )
    }
  )

  it('ignores malformed payloads, accepts invalid cursors without poisoning reconnection and prefers the SSE cursor', async () => {
    vi.useFakeTimers()
    const client = createTestQueryClient(),
      invalidate = vi.spyOn(client, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 10), { wrapper })
    const source = MockEventSource.instances[0]
    const handler = source.addEventListener.mock.calls.find(
      ([event]) => event === 'task.log'
    )?.[1] as EventListener
    handler(new MessageEvent('task.log', { data: '{invalid' }))
    for (const id of [-1, 1.5, Number.MAX_SAFE_INTEGER + 1, 'invalid'])
      handler(new MessageEvent('task.log', { data: JSON.stringify({ id }) }))
    handler(new MessageEvent('task.log', { data: JSON.stringify({ id: 99 }), lastEventId: '12' }))
    expect(invalidate).not.toHaveBeenCalled()
    source.onerror?.(new Event('error'))
    await act(() => vi.advanceTimersByTimeAsync(1000))
    expect(MockEventSource.instances[1].url).toContain('afterEventId=12')
  })

  it.each([
    { changedFields: ['environment.config'] },
    { changedFields: ['runtimeEnvironment.target'] },
    { resource: { type: 'project_runtime_environment' } },
    {}
  ])('refreshes context for a supported field signal or legacy fallback %j', (payload) => {
    const client = createTestQueryClient(),
      invalidate = vi.spyOn(client, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const handler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([event]) => event === 'project.context.changed'
    )?.[1] as EventListener
    act(() =>
      handler(
        new MessageEvent('project.context.changed', { data: JSON.stringify({ id: 1, ...payload }) })
      )
    )
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['project-environments', 'project-1'] })
    const keys = invalidate.mock.calls.map(([options]) => JSON.stringify(options?.queryKey))
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('resets reconnect backoff after open and ignores callbacks from an already detached connection', async () => {
    vi.useFakeTimers()
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper: createWrapper() })
    const first = MockEventSource.instances[0],
      staleOpen = first.onopen!,
      staleError = first.onerror!
    first.onerror?.(new Event('error'))
    await act(() => vi.advanceTimersByTimeAsync(1000))
    const second = MockEventSource.instances[1]
    staleError(new Event('error'))
    staleOpen(new Event('open'))
    expect(second.close).not.toHaveBeenCalled()
    second.onopen?.(new Event('open'))
    second.onerror?.(new Event('error'))
    await act(() => vi.advanceTimersByTimeAsync(999))
    expect(MockEventSource.instances).toHaveLength(2)
    await act(() => vi.advanceTimersByTimeAsync(1))
    expect(MockEventSource.instances).toHaveLength(3)
  })
  beforeEach(() => {
    MockEventSource.instances = []
    vi.stubGlobal('EventSource', MockEventSource)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  function createWrapper() {
    const queryClient = createTestQueryClient()
    return ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
  }

  it.each(['x86-build', 'build-validation'])('%s 完成时使自动化测试构建输入缓存失效', (stageId) => {
    const client = createTestQueryClient()
    const key = ['project-build-validations', 'project-1', 'automated-test-inputs', 'source-1']
    const otherKey = ['project-build-validations', 'project-2', 'automated-test-inputs', 'source-1']
    client.setQueryData(key, { items: [] })
    client.setQueryData(otherKey, { items: [] })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const handler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([name]) => name === 'task.succeeded'
    )?.[1] as EventListener
    act(() =>
      handler(
        new MessageEvent('task.succeeded', {
          data: JSON.stringify({ id: 1, projectId: 'project-1', stageId })
        })
      )
    )
    expect(client.getQueryState(key)?.isInvalidated).toBe(true)
    expect(client.getQueryState(otherKey)?.isInvalidated).toBe(false)
  })

  it('环境切换使 KB 当前条件缓存失效', () => {
    const client = createTestQueryClient()
    const key = ['project-stage-readiness', 'project-1', 'kb']
    client.setQueryData(key, { ready: true })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const handler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([name]) => name === 'project.context.changed'
    )?.[1] as EventListener
    act(() =>
      handler(
        new MessageEvent('project.context.changed', {
          data: JSON.stringify({
            id: 1,
            projectId: 'project-1',
            resource: { type: 'project_runtime_environment' }
          })
        })
      )
    )
    expect(client.getQueryState(key)?.isInvalidated).toBe(true)
  })

  it('subscribes to the project task-event stream and closes it on unmount', () => {
    const { unmount } = renderHook(() => useProjectTaskEvents('project-1', 42), {
      wrapper: createWrapper()
    })

    expect(MockEventSource.instances).toHaveLength(1)
    expect(MockEventSource.instances[0]).toMatchObject({
      options: { withCredentials: true },
      url: '/api/projects/project-1/task-events?afterEventId=42'
    })

    unmount()

    expect(MockEventSource.instances[0].close).toHaveBeenCalledOnce()
  })

  it.each(['memory-order-analysis', 'floating-point-analysis'])(
    'refreshes specialized data and overview after %s completes',
    async (stageId) => {
      const client = createTestQueryClient()
      const invalidate = vi.spyOn(client, 'invalidateQueries')
      const wrapper = ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      )
      renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
      const handler = MockEventSource.instances[0].addEventListener.mock.calls.find(
        ([name]) => name === 'task.succeeded'
      )?.[1] as EventListener
      act(() =>
        handler(
          new MessageEvent('task.succeeded', {
            data: JSON.stringify({
              stageId,
              taskId: 'task',
              taskType: 'MEMORY_ORDER_ANALYSIS',
              resource: { id: 'run', type: 'specialized_analysis_run' }
            })
          })
        )
      )
      await waitFor(() =>
        expect(invalidate).toHaveBeenCalledWith({
          queryKey: ['project-specialized-analysis', 'project-1', stageId]
        })
      )
      expect(invalidate).toHaveBeenCalledWith({
        queryKey: ['project-stage-readiness', 'project-1', stageId]
      })
      expect(invalidate).toHaveBeenCalledWith({ queryKey: ['project-overview', 'project-1'] })
    }
  )

  it('does not subscribe without a project id', () => {
    renderHook(() => useProjectTaskEvents(undefined, 42), { wrapper: createWrapper() })

    expect(MockEventSource.instances).toHaveLength(0)
  })

  it('waits for the overview event cursor before subscribing', () => {
    const { rerender } = renderHook(
      ({ cursor }: { cursor?: number }) => useProjectTaskEvents('project-1', cursor),
      { initialProps: { cursor: undefined as number | undefined }, wrapper: createWrapper() }
    )

    expect(MockEventSource.instances).toHaveLength(0)

    rerender({ cursor: 42 })

    expect(MockEventSource.instances).toHaveLength(1)
    expect(MockEventSource.instances[0].url).toBe(
      '/api/projects/project-1/task-events?afterEventId=42'
    )
  })

  it('refreshes environment data and overview after an environment task finishes', async () => {
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const succeededHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.succeeded'
    )?.[1] as EventListener

    succeededHandler(
      new MessageEvent('task.succeeded', {
        data: JSON.stringify({
          resource: { id: 'environment-1', type: 'environment' },
          stageId: 'environment',
          taskType: 'ENV_TEST_CONNECTION'
        })
      })
    )

    await waitFor(() => expect(invalidateQueries).toHaveBeenCalledTimes(3))
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-environments', 'project-1']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-environment-options', 'project-1']
    })
    await waitFor(() =>
      expect(invalidateQueries).toHaveBeenCalledWith({
        queryKey: ['project-overview', 'project-1']
      })
    )
  })

  it('refreshes overview and matching latest data for a non-terminal task event', async () => {
    vi.useFakeTimers()
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const startedHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.started'
    )?.[1] as EventListener

    startedHandler(
      new MessageEvent('task.started', {
        data: JSON.stringify({
          resource: { id: 'scan-1', type: 'kb_scan_run' },
          stageId: 'kb-scan',
          taskType: 'KB_SCAN'
        })
      })
    )

    await act(() => vi.advanceTimersByTimeAsync(250))
    expect(invalidateQueries).toHaveBeenCalledWith(
      {
        queryKey: ['project-knowledge-base-scans', 'project-1']
      },
      { cancelRefetch: false }
    )
    expect(invalidateQueries).not.toHaveBeenCalledWith({
      queryKey: ['project-overview', 'project-1']
    })

    await act(() => vi.advanceTimersByTimeAsync(4_749))
    expect(invalidateQueries).not.toHaveBeenCalledWith({
      queryKey: ['project-overview', 'project-1']
    })
    await act(() => vi.advanceTimersByTimeAsync(1))
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-overview', 'project-1']
    })
  })

  it('refreshes AI runs and finding data when a knowledge base AI task finishes', async () => {
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const succeededHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.succeeded'
    )?.[1] as EventListener

    succeededHandler(
      new MessageEvent('task.succeeded', {
        data: JSON.stringify({
          id: 1,
          projectId: 'project-1',
          resource: { id: 'ai-run-1', type: 'kb_ai_extension_run' },
          stageId: 'kb-scan',
          taskType: 'KB_AI_EXTENSION'
        })
      })
    )

    await waitFor(() =>
      expect(invalidateQueries).toHaveBeenCalledWith({
        queryKey: ['project-knowledge-base-ai-extensions', 'project-1']
      })
    )
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-knowledge-base-findings']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-knowledge-base-finding']
    })
    expect(invalidateQueries).not.toHaveBeenCalledWith({
      queryKey: ['project-knowledge-base-scans', 'project-1']
    })
  })

  it('batches consecutive non-terminal events into one domain refresh', async () => {
    vi.useFakeTimers()
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })

    for (const [index, eventName] of ['task.created', 'task.queued', 'task.started'].entries()) {
      const handler = MockEventSource.instances[0].addEventListener.mock.calls.find(
        ([registeredName]) => registeredName === eventName
      )?.[1] as EventListener
      handler(
        new MessageEvent(eventName, {
          data: JSON.stringify({
            id: index + 1,
            projectId: 'project-1',
            resource: { id: 'build-1', mode: 'SOURCE_ONLY', type: 'build_run' },
            stageId: 'x86-build',
            taskId: 'task-1',
            taskType: 'BUILD_VALIDATION'
          })
        })
      )
    }

    await act(() => vi.advanceTimersByTimeAsync(250))

    const buildRefreshes = invalidateQueries.mock.calls.filter(
      ([filters]) =>
        JSON.stringify(filters?.queryKey) ===
        JSON.stringify(['project-build-validations', 'project-1', 'SOURCE_ONLY'])
    )
    expect(buildRefreshes).toHaveLength(1)
    expect(buildRefreshes[0]?.[1]).toEqual({ cancelRefetch: false })
  })

  it('throttles adjacent task progress events into one overview refresh', async () => {
    vi.useFakeTimers()
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const progressHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.progress'
    )?.[1] as EventListener

    for (const [index, progress] of [20, 35, 50].entries()) {
      progressHandler(
        new MessageEvent('task.progress', {
          data: JSON.stringify({
            id: index + 1,
            progress,
            resource: { id: 'scan-1', type: 'kb_scan_run' },
            stageId: 'kb-scan',
            taskType: 'KB_SCAN'
          })
        })
      )
    }

    await act(() => vi.advanceTimersByTimeAsync(9_999))
    expect(invalidateQueries).not.toHaveBeenCalled()
    await act(() => vi.advanceTimersByTimeAsync(1))
    expect(invalidateQueries).toHaveBeenCalledTimes(1)
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-overview', 'project-1']
    })
  })

  it('uses a terminal event to supersede pending overview refresh timers', async () => {
    vi.useFakeTimers()
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const source = MockEventSource.instances[0]

    for (const [index, eventType] of ['task.created', 'task.queued', 'task.started'].entries()) {
      const handler = source.addEventListener.mock.calls.find(
        ([eventName]) => eventName === eventType
      )?.[1] as EventListener
      handler(
        new MessageEvent(eventType, {
          data: JSON.stringify({
            id: index + 1,
            projectId: 'project-1',
            resource: { id: 'source-1', type: 'source_revision' },
            stageId: 'source',
            taskId: 'task-1',
            taskType: 'SOURCE_GIT_SYNC'
          })
        })
      )
    }

    const progressHandler = source.addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.progress'
    )?.[1] as EventListener
    progressHandler(
      new MessageEvent('task.progress', {
        data: JSON.stringify({
          id: 4,
          projectId: 'project-1',
          resource: { id: 'source-1', type: 'source_revision' },
          stageId: 'source',
          taskId: 'task-1',
          taskType: 'SOURCE_GIT_SYNC'
        })
      })
    )
    const contextHandler = source.addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'project.context.changed'
    )?.[1] as EventListener
    contextHandler(
      new MessageEvent('project.context.changed', {
        data: JSON.stringify({
          changedFields: ['activeSourceRevision'],
          id: 5,
          projectId: 'project-1',
          resource: { id: 'source-2', type: 'source_revision' }
        })
      })
    )
    const succeededHandler = source.addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.succeeded'
    )?.[1] as EventListener
    succeededHandler(
      new MessageEvent('task.succeeded', {
        data: JSON.stringify({
          id: 6,
          projectId: 'project-1',
          resource: { id: 'source-2', type: 'source_revision' },
          stageId: 'source',
          taskId: 'task-1',
          taskType: 'SOURCE_GIT_SYNC'
        })
      })
    )

    expect(
      invalidateQueries.mock.calls.filter(
        ([options]) => JSON.stringify(options?.queryKey) === '["project-overview","project-1"]'
      )
    ).toHaveLength(1)

    await act(() => vi.advanceTimersByTimeAsync(10_000))
    expect(
      invalidateQueries.mock.calls.filter(
        ([options]) => JSON.stringify(options?.queryKey) === '["project-overview","project-1"]'
      )
    ).toHaveLength(1)
  })

  it('refreshes the matching task result after a terminal event', async () => {
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const succeededHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.succeeded'
    )?.[1] as EventListener

    succeededHandler(
      new MessageEvent('task.succeeded', {
        data: JSON.stringify({
          resource: { id: 'scan-1', type: 'kb_scan_run' },
          stageId: 'kb-scan',
          taskType: 'KB_SCAN'
        })
      })
    )

    await waitFor(() => expect(invalidateQueries).toHaveBeenCalledTimes(4))
    expect(invalidateQueries).toHaveBeenCalledWith({ queryKey: ['project-overview', 'project-1'] })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-knowledge-base-scans', 'project-1']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-stage-readiness', 'project-1', 'report']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-stage-readiness', 'project-1', 'archive']
    })
  })

  it('leaves source context refresh ownership to project.context.changed', () => {
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const succeededHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.succeeded'
    )?.[1] as EventListener

    succeededHandler(
      new MessageEvent('task.succeeded', {
        data: JSON.stringify({
          id: 1,
          projectId: 'project-1',
          resource: { id: 'source-import-1', type: 'source_import' },
          stageId: 'source',
          taskType: 'SOURCE_GIT_SYNC'
        })
      })
    )

    expect(invalidateQueries).toHaveBeenCalledTimes(1)
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-overview', 'project-1']
    })
    expect(invalidateQueries).not.toHaveBeenCalledWith({
      queryKey: ['project-knowledge-base-scans', 'project-1']
    })
  })

  it('routes custom environment script events to their execution history', () => {
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const succeededHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.succeeded'
    )?.[1] as EventListener

    succeededHandler(
      new MessageEvent('task.succeeded', {
        data: JSON.stringify({
          id: 1,
          projectId: 'project-1',
          resource: { id: 'environment-1', mode: 'CUSTOM_BASH', type: 'environment' },
          stageId: 'environment',
          taskType: 'ENV_RUN_SCRIPT'
        })
      })
    )

    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-environment-script-executions', 'project-1', 'environment-1']
    })
    expect(invalidateQueries).not.toHaveBeenCalledWith({
      queryKey: ['project-environments', 'project-1']
    })
  })

  it('does not refetch custom script history for non-terminal task events', async () => {
    vi.useFakeTimers()
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const startedHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.started'
    )?.[1] as EventListener

    startedHandler(
      new MessageEvent('task.started', {
        data: JSON.stringify({
          id: 1,
          projectId: 'project-1',
          resource: { id: 'environment-1', mode: 'CUSTOM_BASH', type: 'environment' },
          stageId: 'environment',
          taskType: 'ENV_RUN_SCRIPT'
        })
      })
    )

    await act(() => vi.advanceTimersByTimeAsync(250))
    expect(invalidateQueries).not.toHaveBeenCalledWith({
      queryKey: ['project-environment-script-executions', 'project-1', 'environment-1']
    })
  })

  it('shows a global notification when a task finishes', async () => {
    const successNotification = vi.spyOn(appNotification, 'success')
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper: createWrapper() })
    const succeededHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.succeeded'
    )?.[1] as EventListener

    succeededHandler(
      new MessageEvent('task.succeeded', {
        data: JSON.stringify({
          id: 1,
          message: '扫描结果已导入',
          resource: { id: 'scan-1', type: 'kb_scan_run' },
          stageId: 'kb-scan',
          taskType: 'KB_SCAN'
        })
      })
    )

    await waitFor(() =>
      expect(successNotification).toHaveBeenCalledWith(
        expect.objectContaining({
          description: '扫描结果已导入',
          title: '知识库扫描已完成'
        })
      )
    )
  })

  it('refreshes caches for the remaining project stages and resources', async () => {
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const succeededHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.succeeded'
    )?.[1] as EventListener

    for (const [index, taskEvent] of [
      {
        resource: { id: 'analysis-1', type: 'dependency_analysis_run' },
        stageId: 'dependency-analysis',
        taskType: 'DEPENDENCY_ANALYSIS'
      },
      {
        resource: { id: 'report-1', type: 'report' },
        stageId: 'evaluation-report',
        taskType: 'REPORT_GENERATE'
      },
      {
        resource: { id: 'comparison-2', type: 'test_comparison_run' },
        stageId: 'automated-test',
        taskType: 'TEST_LOG_COMPARE'
      },
      {
        resource: { id: 'build-1', mode: 'BOTH', type: 'build_run' },
        stageId: 'build-validation',
        taskType: 'BUILD_VALIDATION'
      },
      {
        resource: { id: 'archive-1', type: 'project_archive' },
        stageId: 'archive',
        taskType: 'ARCHIVE_PROJECT'
      }
    ].entries()) {
      succeededHandler(
        new MessageEvent('task.succeeded', {
          data: JSON.stringify({ id: index + 1, ...taskEvent })
        })
      )
    }

    await waitFor(() =>
      expect(invalidateQueries).toHaveBeenCalledWith({
        queryKey: ['project-archives', 'project-1']
      })
    )
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-dependency-analyses', 'project-1']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-evaluation-reports', 'project-1']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-test-comparisons', 'project-1']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-archives', 'project-1']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-stage-readiness', 'project-1', 'test']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-stage-readiness', 'project-1', 'archive']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-optimization-cycles', 'project-1']
    })
  })

  it('does not refresh the latest evaluation report for report comparison events', () => {
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const succeededHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.succeeded'
    )?.[1] as EventListener

    succeededHandler(
      new MessageEvent('task.succeeded', {
        data: JSON.stringify({
          id: 1,
          resource: { id: 'comparison-1', type: 'report_comparison_run' },
          stageId: 'evaluation-report',
          taskType: 'REPORT_COMPARE'
        })
      })
    )

    expect(invalidateQueries).not.toHaveBeenCalledWith({
      queryKey: ['project-evaluation-reports', 'project-1']
    })
    expect(invalidateQueries).not.toHaveBeenCalledWith({
      queryKey: ['project-optimization-cycles', 'project-1']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-stage-readiness', 'project-1', 'archive']
    })
  })

  it('refreshes the optimization aggregate after a source recheck finishes', () => {
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const succeededHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.succeeded'
    )?.[1] as EventListener

    succeededHandler(
      new MessageEvent('task.succeeded', {
        data: JSON.stringify({
          id: 1,
          resource: { id: 'recheck-1', type: 'recheck_run' },
          stageId: 'evaluation-report',
          taskType: 'RECHECK_DIFF'
        })
      })
    )

    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-optimization-cycles', 'project-1']
    })
    expect(invalidateQueries).not.toHaveBeenCalledWith({
      queryKey: ['project-evaluation-reports', 'project-1']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-stage-readiness', 'project-1', 'archive']
    })
  })

  it('does not refresh archive readiness when an upstream task fails', () => {
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const failedHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.failed'
    )?.[1] as EventListener

    failedHandler(
      new MessageEvent('task.failed', {
        data: JSON.stringify({
          id: 1,
          resource: { id: 'build-1', mode: 'BOTH', type: 'build_run' },
          stageId: 'build-validation',
          taskType: 'BUILD_VALIDATION'
        })
      })
    )

    expect(invalidateQueries).not.toHaveBeenCalledWith({
      queryKey: ['project-stage-readiness', 'project-1', 'archive']
    })
  })

  it('ignores incomplete task events without the new stage metadata', () => {
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const succeededHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.succeeded'
    )?.[1] as EventListener

    succeededHandler(
      new MessageEvent('task.succeeded', {
        data: JSON.stringify({ id: 1, taskType: 'KB_SCAN' })
      })
    )

    expect(invalidateQueries).not.toHaveBeenCalled()
    expect(invalidateQueries).not.toHaveBeenCalledWith({
      queryKey: ['project-knowledge-base-scans', 'project-1']
    })
  })

  it('uses stage and resource metadata to refresh the matching domain', async () => {
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const succeededHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.succeeded'
    )?.[1] as EventListener

    succeededHandler(
      new MessageEvent('task.succeeded', {
        data: JSON.stringify({
          id: 1,
          projectId: 'project-1',
          resource: { id: 'build-1', mode: 'SOURCE_ONLY', type: 'build_run' },
          stageId: 'x86-build',
          taskId: 'task-1',
          taskType: 'BUILD_VALIDATION'
        })
      })
    )

    await waitFor(() =>
      expect(invalidateQueries).toHaveBeenCalledWith({
        queryKey: ['project-build-validations', 'project-1', 'SOURCE_ONLY']
      })
    )
    expect(invalidateQueries).not.toHaveBeenCalledWith({
      queryKey: ['project-build-validations', 'project-1']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-stage-readiness', 'project-1', 'rv-dependency-analysis']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-stage-readiness', 'project-1', 'kb']
    })
  })

  it('does not treat dependency auto-fix execution as dependency latest', () => {
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const succeededHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.succeeded'
    )?.[1] as EventListener

    succeededHandler(
      new MessageEvent('task.succeeded', {
        data: JSON.stringify({
          id: 1,
          projectId: 'project-1',
          resource: { id: 'auto-fix-1', type: 'dependency_auto_fix_run' },
          stageId: 'dependency-analysis',
          taskType: 'DEPENDENCY_AUTO_FIX'
        })
      })
    )

    expect(invalidateQueries).toHaveBeenCalledTimes(1)
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-overview', 'project-1']
    })
    expect(invalidateQueries).not.toHaveBeenCalledWith({
      queryKey: ['project-dependency-analyses', 'project-1']
    })
  })

  it('refreshes source context and downstream data after a project context event', async () => {
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const contextHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'project.context.changed'
    )?.[1] as EventListener

    contextHandler(
      new MessageEvent('project.context.changed', {
        data: JSON.stringify({
          changedFields: ['activeSourceRevision'],
          id: 1,
          projectId: 'project-1',
          resource: { id: 'source-2', type: 'source_revision' },
          stageId: 'context',
          taskId: null,
          taskType: null
        })
      })
    )

    await waitFor(() =>
      expect(invalidateQueries).toHaveBeenCalledWith({
        queryKey: ['project-knowledge-base-scans', 'project-1']
      })
    )
    await waitFor(() =>
      expect(invalidateQueries).toHaveBeenCalledWith({
        queryKey: ['project-overview', 'project-1']
      })
    )
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-dependency-analyses', 'project-1']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-stage-readiness', 'project-1', 'rv-dependency-analysis']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-stage-readiness', 'project-1', 'kb']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-stage-readiness', 'project-1', 'x86-build']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-stage-readiness', 'project-1', 'test']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-stage-readiness', 'project-1', 'archive']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-stage-readiness', 'project-1', 'optimize']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-optimization-cycles', 'project-1']
    })
    expect(invalidateQueries).not.toHaveBeenCalledWith({
      queryKey: ['project-environments', 'project-1']
    })
  })

  it('refreshes environments and downstream data after runtime context changes', async () => {
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const contextHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'project.context.changed'
    )?.[1] as EventListener

    contextHandler(
      new MessageEvent('project.context.changed', {
        data: JSON.stringify({
          changedFields: ['runtimeEnvironment.version', 'environment.config'],
          id: 1,
          projectId: 'project-1',
          resource: { id: 'environment-1', type: 'environment' },
          stageId: 'context'
        })
      })
    )

    await waitFor(() =>
      expect(invalidateQueries).toHaveBeenCalledWith({
        queryKey: ['project-environments', 'project-1']
      })
    )
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-environment-options', 'project-1']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-build-validations', 'project-1']
    })
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-stage-readiness', 'project-1', 'x86-build']
    })
  })

  it('ignores an event carrying another project id', () => {
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const contextHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'project.context.changed'
    )?.[1] as EventListener

    contextHandler(
      new MessageEvent('project.context.changed', {
        data: JSON.stringify({
          changedFields: ['activeSourceRevision'],
          id: 1,
          projectId: 'project-2',
          resource: { id: 'source-2', type: 'source_revision' }
        })
      })
    )

    expect(invalidateQueries).not.toHaveBeenCalled()
  })

  it('reconnects from the last applied event cursor after a connection error', async () => {
    vi.useFakeTimers()
    renderHook(() => useProjectTaskEvents('project-1', 42), { wrapper: createWrapper() })
    const firstSource = MockEventSource.instances[0]
    const startedHandler = firstSource.addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.started'
    )?.[1] as EventListener

    startedHandler(
      new MessageEvent('task.started', {
        data: JSON.stringify({ id: 43, resource: { id: 'task-1', type: 'task' }, stageId: 'task' })
      })
    )
    firstSource.onerror?.(new Event('error'))

    expect(firstSource.close).toHaveBeenCalledOnce()
    await act(() => vi.advanceTimersByTimeAsync(1_000))

    expect(MockEventSource.instances).toHaveLength(2)
    expect(MockEventSource.instances[1].url).toBe(
      '/api/projects/project-1/task-events?afterEventId=43'
    )
  })

  it('does not poll the overview while the SSE connection is unavailable', async () => {
    vi.useFakeTimers()
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 42), { wrapper })

    MockEventSource.instances[0].onerror?.(new Event('error'))
    await act(() => vi.advanceTimersByTimeAsync(10_000))

    expect(invalidateQueries).not.toHaveBeenCalled()
  })

  it('uses log events to advance the reconnect cursor without refreshing page data', async () => {
    vi.useFakeTimers()
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 42), { wrapper })
    const firstSource = MockEventSource.instances[0]
    const logHandler = firstSource.addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.log'
    )?.[1] as EventListener

    logHandler(new MessageEvent('task.log', { data: JSON.stringify({ id: 44 }) }))

    expect(invalidateQueries).not.toHaveBeenCalled()
    firstSource.onerror?.(new Event('error'))
    await act(() => vi.advanceTimersByTimeAsync(1_000))
    expect(MockEventSource.instances[1].url).toBe(
      '/api/projects/project-1/task-events?afterEventId=44'
    )
  })

  it('ignores replayed events that do not advance the overview cursor', async () => {
    vi.useFakeTimers()
    const queryClient = createTestQueryClient()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 42), { wrapper })
    const startedHandler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([eventName]) => eventName === 'task.started'
    )?.[1] as EventListener

    startedHandler(
      new MessageEvent('task.started', {
        data: JSON.stringify({ id: 42, resource: { id: 'task-1', type: 'task' }, stageId: 'task' })
      })
    )
    startedHandler(
      new MessageEvent('task.started', {
        data: JSON.stringify({ id: 43, resource: { id: 'task-1', type: 'task' }, stageId: 'task' })
      })
    )

    await act(() => vi.advanceTimersByTimeAsync(5_000))
    expect(invalidateQueries).toHaveBeenCalledTimes(1)
    expect(invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['project-overview', 'project-1']
    })
  })
  it('closes old authentication streams synchronously and rejects late events and timers', async () => {
    vi.useFakeTimers()
    const client = createTestQueryClient()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    const notification = vi.spyOn(appNotification, 'success').mockImplementation(() => undefined)
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const old = MockEventSource.instances[0]
    const handler = (name: string) =>
      old.addEventListener.mock.calls.find(([event]) => event === name)?.[1] as EventListener
    const emit = (name: string, id: number) =>
      handler(name)(
        new MessageEvent(name, {
          data: JSON.stringify({
            id,
            projectId: 'project-1',
            stageId: 'environment',
            taskType: 'ENV_TEST_CONNECTION'
          })
        })
      )
    emit('task.started', 1)
    act(() => {
      advanceAuthSession()
      expect(old.close).toHaveBeenCalledOnce()
      emit('task.succeeded', 2)
    })
    await act(() => vi.advanceTimersByTimeAsync(30_000))
    expect(invalidate).not.toHaveBeenCalled()
    expect(notification).not.toHaveBeenCalled()
    expect(MockEventSource.instances).toHaveLength(2)
    expect(old.removeEventListener).toHaveBeenCalled()
  })
  it.each([
    { stageId: 'source', resource: undefined, expected: undefined },
    {
      stageId: 'environment',
      resource: { mode: 'CUSTOM_BASH' },
      expected: ['project-environments', 'project-1']
    },
    {
      stageId: 'dependency-analysis',
      resource: undefined,
      expected: ['project-dependency-analyses', 'project-1']
    },
    { stageId: 'evaluation-report', resource: undefined, expected: undefined },
    { stageId: 'future-stage', resource: undefined, expected: undefined }
  ])(
    'handles incomplete or future stage resources without invalidating unrelated domains: $stageId',
    ({ stageId, resource, expected }) => {
      const client = createTestQueryClient(),
        invalidate = vi.spyOn(client, 'invalidateQueries')
      const wrapper = ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      )
      renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
      const handler = MockEventSource.instances[0].addEventListener.mock.calls.find(
        ([name]) => name === 'task.failed'
      )![1] as EventListener
      act(() =>
        handler(
          new MessageEvent('task.failed', { data: JSON.stringify({ id: 1, stageId, resource }) })
        )
      )
      expect(invalidate).toHaveBeenCalledWith({ queryKey: ['project-overview', 'project-1'] })
      if (expected) expect(invalidate).toHaveBeenCalledWith({ queryKey: expected })
      else expect(invalidate).toHaveBeenCalledTimes(1)
      expect(invalidate).not.toHaveBeenCalledWith({
        queryKey: ['project-evaluation-reports', 'project-1']
      })
    }
  )
  it('refreshes AI run progress without refreshing finding results before completion', async () => {
    vi.useFakeTimers()
    const client = createTestQueryClient(),
      invalidate = vi.spyOn(client, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const handler = MockEventSource.instances[0].addEventListener.mock.calls.find(
      ([name]) => name === 'task.started'
    )![1] as EventListener
    act(() =>
      handler(
        new MessageEvent('task.started', {
          data: JSON.stringify({
            id: 1,
            stageId: 'kb-scan',
            resource: { type: 'kb_ai_extension_run' }
          })
        })
      )
    )
    await act(async () => vi.advanceTimersByTimeAsync(250))
    expect(invalidate).toHaveBeenCalledWith(
      { queryKey: ['project-knowledge-base-ai-extensions', 'project-1'] },
      { cancelRefetch: false }
    )
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ['project-knowledge-base-findings'] })
  })
  it('refreshes once on foreground return and coalesces lifecycle events while context refresh is pending', async () => {
    vi.useFakeTimers()
    const client = createTestQueryClient(),
      invalidate = vi.spyOn(client, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
    renderHook(() => useProjectTaskEvents('project-1', 0), { wrapper })
    const source = MockEventSource.instances[0]
    const emit = (type: string, payload: object) => {
      const handler = source.addEventListener.mock.calls.find(
        ([name]) => name === type
      )![1] as EventListener
      handler(new MessageEvent(type, { data: JSON.stringify(payload) }))
    }
    act(() => {
      emit('project.context.changed', { id: 1 })
      emit('task.started', { id: 2, stageId: 'source' })
      emit('task.queued', { id: 3, stageId: 'source' })
    })
    invalidate.mockClear()
    await act(async () => vi.advanceTimersByTimeAsync(250))
    expect(invalidate).toHaveBeenCalledTimes(1)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['project-overview', 'project-1'] })
    invalidate.mockClear()
    await act(async () => vi.advanceTimersByTimeAsync(5000))
    expect(invalidate).not.toHaveBeenCalled()
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    act(() => document.dispatchEvent(new Event('visibilitychange')))
    expect(invalidate).not.toHaveBeenCalled()
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    act(() => document.dispatchEvent(new Event('visibilitychange')))
    expect(invalidate).toHaveBeenCalledTimes(1)
  })
})
