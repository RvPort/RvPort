import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createTestQueryClient } from '@/test/test-utils'

import { projectQueryKeys } from '../../queryKeys'
import { isTerminalTestStatus, useAutomatedTestDrawer } from './useAutomatedTestDrawer'

const api = vi.hoisted(() => ({
  cancelProjectAutomatedTest: vi.fn(),
  createProjectAutomatedTest: vi.fn(),
  getProjectAutomatedTestLatest: vi.fn(),
  getProjectAutomatedTests: vi.fn(),
  getProjectBuildValidations: vi.fn(),
  getProjectStageReadiness: vi.fn(),
  getProjectTestComparisons: vi.fn()
}))
vi.mock('@/api/project/automated-test', () => api)
vi.mock('@/api/project/build-validation', () => ({
  getProjectBuildValidations: api.getProjectBuildValidations
}))
vi.mock('@/api/project/workflow', () => ({
  getProjectStageReadiness: api.getProjectStageReadiness
}))
vi.mock('@/utils/feedback', () => ({
  feedback: { success: vi.fn(), warning: vi.fn(), error: vi.fn() }
}))

const page = <T,>(items: T[]) => ({
  items,
  page: 1,
  pageSize: 100,
  total: items.length,
  hasMore: false
})
const build = (id: string, arch: string, overrides = {}) => ({
  id,
  environment: { arch },
  status: 'SUCCEEDED',
  currentRuntimeEnvironment: true,
  workingDirectory: `/workspace/${id}`,
  ...overrides
})
const ready = (details: Record<string, unknown> = {}) => ({
  context: { sourceRevisionId: 'source-current' },
  prerequisites: [{ key: 'BUILD_VALIDATION', status: 'SATISFIED', details }]
})
const latest = (freshness = 'CURRENT', status = 'RUNNING') => ({
  freshness,
  result: { id: 'current-test', status: 'QUEUED', testBatchId: 'current-batch' },
  execution: { status }
})
const scope = { currentEnabled: true, recordsEnabled: true, submissionEnabled: true }

function setup(initialScope = scope, projectId: string | undefined = 'project-1') {
  const client = createTestQueryClient()
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return {
    client,
    ...renderHook(
      ({ queryScope, project }: { queryScope: typeof scope; project: string | undefined }) =>
        useAutomatedTestDrawer(project, queryScope),
      {
        initialProps: { queryScope: initialScope, project: projectId as string | undefined },
        wrapper
      }
    )
  }
}

describe('automated test inputs and query recovery', () => {
  beforeEach(() => {
    api.getProjectStageReadiness.mockResolvedValue(ready())
    api.getProjectBuildValidations.mockResolvedValue(
      page([build('source', 'x86_64'), build('target', 'riscv64')])
    )
    api.getProjectAutomatedTestLatest.mockResolvedValue(latest())
    api.getProjectAutomatedTests.mockResolvedValue(page([]))
    api.getProjectTestComparisons.mockResolvedValue(page([]))
  })

  it('uses referenced build IDs even when architecture labels disagree', async () => {
    api.getProjectStageReadiness.mockResolvedValue(
      ready({ sourceBuildRunId: 'chosen-source', targetBuildRunId: 'chosen-target' })
    )
    api.getProjectBuildValidations.mockResolvedValue(
      page([
        build('other-source', 'x86_64'),
        build('chosen-source', 'legacy-label'),
        build('chosen-target', 'legacy-label')
      ])
    )
    const { result } = setup()
    await waitFor(() => expect(result.current.canCreate).toBe(true))
    expect(result.current.buildContexts.map(({ build }) => build?.id)).toEqual([
      'chosen-source',
      'chosen-target'
    ])
    expect(api.getProjectBuildValidations).toHaveBeenCalledWith('project-1', {
      buildScope: 'BOTH',
      page: 1,
      pageSize: 100,
      sourceRevisionId: 'source-current'
    })
  })

  it.each(['SOURCE', 'TARGET'])(
    'does not replace an unreadable referenced %s build with another record',
    async (role) => {
      api.getProjectStageReadiness.mockResolvedValue(
        ready(
          role === 'SOURCE'
            ? { sourceBuildRunId: 'missing-source' }
            : { targetBuildRunId: 'missing-target' }
        )
      )
      const { result } = setup()
      await waitFor(() => expect(result.current.currentHasData).toBe(true))
      expect(result.current.canCreate).toBe(false)
      expect(result.current.buildContexts.find((item) => item.role === role)).toMatchObject({
        build: null,
        reason: '无法读取当前构建输入'
      })
    }
  )

  it('falls back only to successful current builds with the matching architecture', async () => {
    api.getProjectBuildValidations.mockResolvedValue(
      page([
        build('failed', 'x86_64', { status: 'FAILED' }),
        build('stale', 'riscv64', { currentRuntimeEnvironment: false }),
        build('no-environment', '', { environment: null }),
        build('unrelated', 'aarch64'),
        build('source', 'AMD64'),
        build('target', 'RISCV64')
      ])
    )
    const { result } = setup()
    await waitFor(() => expect(result.current.canCreate).toBe(true))
    expect(result.current.buildContexts.map(({ build }) => build?.id)).toEqual(['source', 'target'])
  })

  it('keeps stale results in history and adds only a current latest result with its execution status', async () => {
    const historical = { id: 'old-test', status: 'SUCCEEDED' }
    api.getProjectAutomatedTestLatest.mockResolvedValue(latest('STALE'))
    api.getProjectAutomatedTests.mockResolvedValue(page([historical]))
    const { client, result } = setup()
    await waitFor(() => expect(result.current.recordsHasData).toBe(true))
    expect(result.current.latestTest).toBeNull()
    expect(result.current.tests).toEqual([historical])
    act(() =>
      client.setQueryData([...projectQueryKeys.automatedTests('project-1'), 'latest'], latest())
    )
    await waitFor(() => expect(result.current.latestTest?.status).toBe('RUNNING'))
    expect(result.current.tests.map(({ id }) => id)).toEqual(['current-test', 'old-test'])
    act(() =>
      client.setQueryData(
        [...projectQueryKeys.automatedTests('project-1'), 'records'],
        page([{ id: 'current-test', status: 'RUNNING' }, historical])
      )
    )
    await waitFor(() => expect(result.current.tests).toHaveLength(2))
    expect(result.current.tests.filter(({ id }) => id === 'current-test')).toHaveLength(1)
  })

  it.each(['readiness', 'latest', 'builds'])(
    'preserves readable cached data but blocks submissions after %s refresh fails',
    async (failure) => {
      const { result } = setup()
      await waitFor(() => expect(result.current.canCreate).toBe(true))
      const request =
        failure === 'readiness'
          ? api.getProjectStageReadiness
          : failure === 'latest'
            ? api.getProjectAutomatedTestLatest
            : api.getProjectBuildValidations
      request.mockRejectedValue(new Error('offline'))
      await act(async () => {
        await result.current.refetch()
      })
      await waitFor(() => expect(result.current.currentError).toBe(true))
      expect(result.current.currentHasData).toBe(true)
      expect(result.current.canCreate).toBe(false)
      expect(result.current.latestTest?.id).toBe('current-test')
      request.mockResolvedValue(
        failure === 'readiness'
          ? ready()
          : failure === 'latest'
            ? latest()
            : page([build('source', 'x86_64'), build('target', 'riscv64')])
      )
      await act(async () => {
        await result.current.refetch()
      })
      await waitFor(() => expect(result.current.canCreate).toBe(true))
      expect(result.current.currentError).toBe(false)
    }
  )

  it.each(['tests', 'comparisons'])(
    'isolates %s history failure from current submission readiness',
    async (failure) => {
      const request =
        failure === 'tests' ? api.getProjectAutomatedTests : api.getProjectTestComparisons
      request.mockRejectedValue(new Error('history offline'))
      const { result } = setup()
      await waitFor(() => expect(result.current.recordsError).toBe(true))
      await waitFor(() => expect(result.current.canCreate).toBe(true))
      expect(result.current.currentError).toBe(false)
      expect(result.current.recordsHasData).toBe(false)
    }
  )

  it('refreshes only history after entering records and performs no requests when all scopes close', async () => {
    const { result, rerender } = setup()
    await waitFor(() => expect(result.current.canCreate).toBe(true))
    rerender({ project: 'project-1', queryScope: { ...scope, currentEnabled: false } })
    const currentCalls = [
      api.getProjectStageReadiness.mock.calls.length,
      api.getProjectAutomatedTestLatest.mock.calls.length,
      api.getProjectBuildValidations.mock.calls.length
    ]
    const recordsCalls = api.getProjectAutomatedTests.mock.calls.length
    await act(async () => {
      await result.current.refetch()
    })
    expect(api.getProjectAutomatedTests).toHaveBeenCalledTimes(recordsCalls + 1)
    expect([
      api.getProjectStageReadiness.mock.calls.length,
      api.getProjectAutomatedTestLatest.mock.calls.length,
      api.getProjectBuildValidations.mock.calls.length
    ]).toEqual(currentCalls)
    rerender({
      project: 'project-1',
      queryScope: { currentEnabled: false, recordsEnabled: false, submissionEnabled: false }
    })
    const allCalls = Object.values(api).reduce((total, fn) => total + fn.mock.calls.length, 0)
    await act(async () => {
      await result.current.refetch()
    })
    expect(Object.values(api).reduce((total, fn) => total + fn.mock.calls.length, 0)).toBe(allCalls)
  })

  it('waits for a project identity before loading current or historical data', async () => {
    const { result, rerender } = setup({
      currentEnabled: false,
      recordsEnabled: false,
      submissionEnabled: false
    })
    rerender({ project: undefined, queryScope: scope })
    await act(async () => {
      await Promise.resolve()
    })
    expect(result.current.canCreate).toBe(false)
    for (const request of Object.values(api)) expect(request).not.toHaveBeenCalled()
  })

  it.each(['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT'])(
    'recognizes %s as a finished execution',
    (status) => expect(isTerminalTestStatus(status)).toBe(true)
  )
  it.each([undefined, '', 'QUEUED', 'RUNNING'])(
    'does not treat %s as a finished execution',
    (status) => expect(isTerminalTestStatus(status)).toBe(false)
  )

  it('shows current readiness without fetching builds until source identity is available', async () => {
    api.getProjectStageReadiness.mockResolvedValue({
      ...ready(),
      context: { sourceRevisionId: null }
    })
    const { result } = setup({ ...scope, recordsEnabled: false })
    await waitFor(() => expect(result.current.ready).toBe(true))
    expect(result.current.canCreate).toBe(false)
    expect(result.current.currentHasData).toBe(false)
    await act(async () => {
      await result.current.refetch()
    })
    expect(api.getProjectBuildValidations).not.toHaveBeenCalled()
    expect(api.getProjectAutomatedTests).not.toHaveBeenCalled()
    expect(api.getProjectTestComparisons).not.toHaveBeenCalled()
  })

  it('refreshes an unmet prerequisite without attempting build inputs or losing the latest result', async () => {
    api.getProjectStageReadiness.mockResolvedValue({
      ...ready(),
      prerequisites: [{ key: 'BUILD_VALIDATION', status: 'MISSING' }]
    })
    api.getProjectAutomatedTestLatest.mockResolvedValue({ ...latest(), execution: null })
    const { result } = setup({ ...scope, recordsEnabled: false })
    await waitFor(() => expect(result.current.currentHasData).toBe(true))
    expect(result.current.ready).toBe(false)
    expect(result.current.canCreate).toBe(false)
    expect(result.current.latestTest?.status).toBe('QUEUED')
    await act(async () => {
      await result.current.refetch()
    })
    expect(api.getProjectBuildValidations).not.toHaveBeenCalled()
  })

  it('reports each pending history request and its recovery when the current view is disabled', async () => {
    let finishTests!: (value: ReturnType<typeof page>) => void
    let finishComparisons!: (value: ReturnType<typeof page>) => void
    api.getProjectAutomatedTests.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishTests = resolve
        })
    )
    api.getProjectTestComparisons.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishComparisons = resolve
        })
    )
    const { result } = setup({ ...scope, currentEnabled: false })
    expect(result.current.currentLoading).toBe(false)
    expect(result.current.recordsLoading).toBe(true)
    expect(result.current.isLoading).toBe(true)
    await act(async () => {
      finishTests(page([]))
    })
    expect(result.current.recordsLoading).toBe(true)
    await act(async () => {
      finishComparisons(page([]))
    })
    await waitFor(() => expect(result.current.recordsHasData).toBe(true))
    expect(result.current.isLoading).toBe(false)
    api.getProjectTestComparisons.mockRejectedValue(new Error('offline'))
    await act(async () => {
      await result.current.refetch()
    })
    await waitFor(() => expect(result.current.recordsError).toBe(true))
    expect(result.current.isError).toBe(true)
    expect(result.current.recordsHasData).toBe(true)
    expect(result.current.currentError).toBe(false)
    api.getProjectTestComparisons.mockResolvedValue(page([]))
    await act(async () => {
      await result.current.refetch()
    })
    await waitFor(() => expect(result.current.isError).toBe(false))
    expect(api.getProjectStageReadiness).not.toHaveBeenCalled()
    expect(api.getProjectAutomatedTestLatest).not.toHaveBeenCalled()
  })
})
