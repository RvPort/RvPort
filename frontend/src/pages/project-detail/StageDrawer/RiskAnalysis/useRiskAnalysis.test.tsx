import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import * as api from '@/api/project/specialized-analysis'
import { getProjectStageReadiness } from '@/api/project/workflow'
import { createTestQueryClient } from '@/test/test-utils'

import { projectQueryKeys } from '../../queryKeys'
import { latest, readiness, run } from './fixtures.test-support'
import { useRiskAnalysis } from './useRiskAnalysis'

vi.mock('@/api/project/specialized-analysis')
vi.mock('@/api/project/workflow')

beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  vi.mocked(getProjectStageReadiness).mockResolvedValue(readiness)
  vi.mocked(api.getSpecializedAnalysisLatest).mockResolvedValue(latest())
})
afterEach(() => vi.useRealTimers())

function setup() {
  const client = createTestQueryClient()
  const hook = renderHook(() => useRiskAnalysis('project', 'memory-order-analysis', true), {
    wrapper: ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
  })
  return {
    ...hook,
    client,
    dispose: () => {
      hook.unmount()
      client.clear()
    }
  }
}
async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

describe('specialized analysis authoritative event refresh', () => {
  it.each([false, true])('诊断绑定原执行，返回保留筛选与历史选择：历史=%s', async (historical) => {
    vi.mocked(api.getSpecializedAnalysis).mockImplementation(async (_project, _stage, id) => ({
      ...run,
      id
    }))
    const hook = setup()
    await advance(1)
    const originalId = historical ? 'historical-run' : run.id
    if (historical) {
      act(() => hook.result.current.selectRun(originalId))
      await advance(1)
    }
    act(() => hook.result.current.setSearch('publication'))
    act(() => hook.result.current.openDiagnostics())
    await advance(1)
    expect(hook.result.current.diagnosticRunId).toBe(originalId)
    expect(hook.result.current.canStart).toBe(false)
    const next = { ...run, id: 'next-run' }
    act(() =>
      hook.client.setQueryData(
        projectQueryKeys.specializedLatest('project', 'memory-order-analysis'),
        latest(next)
      )
    )
    await advance(1)
    expect(hook.result.current.run?.id).toBe(originalId)
    vi.mocked(api.getSpecializedAnalysisLatest).mockResolvedValue(latest(next))
    act(() => hook.result.current.closeDiagnostics())
    await advance(1)
    expect(hook.result.current.runId).toBe(historical ? originalId : undefined)
    expect(hook.result.current.run?.id).toBe(historical ? originalId : next.id)
    expect(hook.result.current.search).toBe('publication')
    expect(hook.result.current.diagnosticRunId).toBeUndefined()
    hook.dispose()
  })
  it('updates history through domain invalidation without polling current observers', async () => {
    vi.mocked(api.getSpecializedAnalysis)
      .mockResolvedValueOnce({ ...run, status: 'RUNNING' })
      .mockResolvedValue(run)
    const hook = setup()
    await advance(1)
    act(() => hook.result.current.selectRun(run.id))
    await advance(1)
    expect(hook.result.current.run?.status).toBe('RUNNING')
    await act(async () => {
      await hook.client.invalidateQueries({
        queryKey: ['project-specialized-analysis', 'project', 'memory-order-analysis']
      })
    })
    await advance(1)
    expect(hook.result.current.run?.status).toBe('COMPLETE_WITH_RISK')
    expect(api.getSpecializedAnalysis).toHaveBeenCalledTimes(2)
    await advance(6000)
    expect(api.getSpecializedAnalysis).toHaveBeenCalledTimes(2)
    hook.dispose()
  })
  it('keeps observing an execution before its result becomes available', async () => {
    vi.mocked(api.getSpecializedAnalysisLatest)
      .mockResolvedValueOnce({
        ...latest(null),
        execution: {
          id: 'execution',
          taskId: 'task',
          status: 'RUNNING',
          progress: 10,
          message: null,
          errorCode: null,
          errorMessage: null,
          createdAt: run.createdAt,
          startedAt: null,
          finishedAt: null
        }
      })
      .mockResolvedValue(latest())
    const hook = setup()
    await advance(1)
    expect(hook.result.current.active).toBe(true)
    expect(hook.result.current.run).toBeNull()
    await act(async () => {
      await hook.client.invalidateQueries({
        queryKey: ['project-specialized-analysis', 'project', 'memory-order-analysis']
      })
    })
    await advance(1)
    expect(hook.result.current.run?.id).toBe(run.id)
    expect(api.getSpecializedAnalysisLatest).toHaveBeenCalledTimes(2)
    hook.dispose()
  })
})
