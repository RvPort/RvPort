import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, expect, it, vi } from 'vitest'

import { getProjectOptimizationCycleLatest } from '@/api/project/optimization-cycle'
import { getProjectStageReadiness } from '@/api/project/workflow'
import { createOptimizationCycle } from '@/test/optimization-cycle-fixtures'
import { createTestQueryClient } from '@/test/test-utils'
import type { ProjectStageReadiness } from '@/types/project'

import { useOptimizationCycleDrawer } from './useOptimizationCycleDrawer'

vi.mock('@/api/project/optimization-cycle', () => ({ getProjectOptimizationCycleLatest: vi.fn() }))
vi.mock('@/api/project/workflow', () => ({ getProjectStageReadiness: vi.fn() }))
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}
function readiness(): ProjectStageReadiness {
  return {
    stageId: 'optimize',
    context: { sourceRevisionId: 'source-1', runtimeEnvironmentVersion: 1 },
    prerequisites: [
      {
        key: 'ACTIVE_SOURCE',
        status: 'SATISFIED',
        referenceId: 'source-1',
        reasonCode: 'SOURCE_READY',
        details: {}
      }
    ]
  }
}
function latest(
  projectId = 'project-1',
  id = 'cycle-1'
): Awaited<ReturnType<typeof getProjectOptimizationCycleLatest>> {
  return {
    context: readiness().context,
    freshness: 'CURRENT',
    execution: null,
    result: { ...createOptimizationCycle(), projectId, id }
  }
}
function setup(projectId = 'project-1') {
  const client = createTestQueryClient()
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return {
    client,
    ...renderHook(({ project }) => useOptimizationCycleDrawer(project, true), {
      initialProps: { project: projectId },
      wrapper
    })
  }
}
beforeEach(() => {
  vi.mocked(getProjectStageReadiness).mockResolvedValue(readiness())
  vi.mocked(getProjectOptimizationCycleLatest).mockImplementation(async (project) =>
    latest(project)
  )
})
it('does not consider a partial response a complete optimization overview', async () => {
  const conditions = deferred<ProjectStageReadiness>()
  const resultData = deferred<Awaited<ReturnType<typeof getProjectOptimizationCycleLatest>>>()
  vi.mocked(getProjectStageReadiness).mockReturnValueOnce(conditions.promise)
  vi.mocked(getProjectOptimizationCycleLatest).mockReturnValueOnce(resultData.promise)
  const { result, client } = setup()
  expect(result.current.hasData).toBe(false)
  await act(async () => {
    resultData.resolve(latest())
  })
  await waitFor(() => expect(result.current.currentCycle?.id).toBe('cycle-1'))
  expect(result.current.hasData).toBe(false)
  expect(result.current.ready).toBe(false)
  expect(result.current.isLoading).toBe(true)
  await act(async () => {
    conditions.resolve(readiness())
  })
  await waitFor(() => expect(result.current.hasData).toBe(true))
  expect(result.current.ready).toBe(true)
  expect(result.current.isLoading).toBe(false)
  expect(getProjectStageReadiness).toHaveBeenCalledExactlyOnceWith('project-1', 'optimize')
  expect(getProjectOptimizationCycleLatest).toHaveBeenCalledExactlyOnceWith('project-1')
  client.clear()
})
it('keeps a late cycle from the previous project out of the selected project', async () => {
  const old = deferred<Awaited<ReturnType<typeof getProjectOptimizationCycleLatest>>>()
  const current = deferred<Awaited<ReturnType<typeof getProjectOptimizationCycleLatest>>>()
  vi.mocked(getProjectOptimizationCycleLatest).mockImplementation((project) =>
    project === 'project-A' ? old.promise : current.promise
  )
  const { result, rerender, client } = setup('project-A')
  await waitFor(() => expect(getProjectOptimizationCycleLatest).toHaveBeenCalledWith('project-A'))
  rerender({ project: 'project-B' })
  await waitFor(() => expect(getProjectOptimizationCycleLatest).toHaveBeenCalledWith('project-B'))
  expect(result.current.currentCycle).toBeNull()
  expect(result.current.hasData).toBe(false)
  await act(async () => {
    current.resolve(latest('project-B', 'cycle-B'))
  })
  await waitFor(() => expect(result.current.currentCycle?.id).toBe('cycle-B'))
  await act(async () => {
    old.resolve(latest('project-A', 'cycle-A'))
  })
  expect(result.current.currentCycle?.projectId).toBe('project-B')
  expect(result.current.currentCycle?.id).toBe('cycle-B')
  expect(result.current.hasData).toBe(true)
  client.clear()
})
it('retains the current cycle on refresh failure and recovers both authoritative queries on retry', async () => {
  const { result, client } = setup()
  await waitFor(() => expect(result.current.hasData).toBe(true))
  vi.mocked(getProjectOptimizationCycleLatest).mockRejectedValueOnce(
    new Error('temporary unavailable')
  )
  await act(async () => {
    await result.current.refetch()
  })
  await waitFor(() => expect(result.current.isError).toBe(true))
  expect(result.current.currentCycle?.id).toBe('cycle-1')
  expect(result.current.hasData).toBe(true)
  expect(result.current.isLoading).toBe(false)
  vi.mocked(getProjectOptimizationCycleLatest).mockResolvedValueOnce(latest('project-1', 'cycle-2'))
  await act(async () => {
    await result.current.refetch()
  })
  await waitFor(() => expect(result.current.currentCycle?.id).toBe('cycle-2'))
  expect(result.current.isError).toBe(false)
  expect(getProjectStageReadiness).toHaveBeenCalledTimes(3)
  expect(getProjectOptimizationCycleLatest).toHaveBeenCalledTimes(3)
  client.clear()
})
