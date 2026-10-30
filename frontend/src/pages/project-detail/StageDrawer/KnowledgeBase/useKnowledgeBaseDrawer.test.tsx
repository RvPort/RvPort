import { type ReactNode, useState } from 'react'
import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { createProjectKnowledgeBaseScan } from '@/api/project/knowledge-base'
import { advanceAuthSession } from '@/store/authSession'
import { createKnowledgeScan } from '@/test/knowledge-base-fixtures'
import { createTestQueryClient } from '@/test/test-utils'
import { feedback } from '@/utils/feedback'

import { projectQueryKeys } from '../../queryKeys'
import { initialScanConfig } from './types'
import { useKnowledgeBaseDrawer } from './useKnowledgeBaseDrawer'

vi.mock('@/api/project/knowledge-base', async (original) => ({
  ...(await original<typeof import('@/api/project/knowledge-base')>()),
  createProjectKnowledgeBaseScan: vi.fn(),
  getProjectKnowledgeBaseScan: async (id: string) => createKnowledgeScan({ id }),
  getProjectKnowledgeBaseScanLatest: async () => ({
    freshness: 'EMPTY',
    result: null,
    execution: null,
    context: { sourceRevisionId: 'source', runtimeEnvironmentVersion: 1 }
  })
}))
vi.mock('@/api/project/workflow', () => ({
  getProjectStageReadiness: async () => ({
    context: { sourceRevisionId: 'source', runtimeEnvironmentVersion: 1 },
    prerequisites: [
      { key: 'ACTIVE_SOURCE', status: 'SATISFIED' },
      { key: 'X86_BUILD', status: 'SATISFIED' }
    ]
  })
}))
vi.mock('@/utils/feedback', () => ({ feedback: { success: vi.fn(), error: vi.fn() } }))

function Wrapper({ children }: { children: ReactNode }) {
  const [client] = useState(createTestQueryClient)
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

describe('knowledge base validation ownership', () => {
  it.each(['auth', 'project', 'close', 'view'])(
    'does not create a scan after %s changes during validation',
    async (change) => {
      const { result, rerender } = renderHook(
        ({ project, visible }) => useKnowledgeBaseDrawer(project, visible),
        { initialProps: { project: 'A', visible: true }, wrapper: Wrapper }
      )
      act(() => result.current.showConfig())
      await waitFor(() => expect(result.current.x86BuildReady).toBe(true))
      let finish!: (value: typeof initialScanConfig) => void
      vi.spyOn(result.current.form, 'validateFields').mockImplementation(
        () =>
          new Promise((resolve) => {
            finish = resolve
          })
      )
      let pending!: Promise<unknown>
      act(() => {
        pending = result.current.handleCreateScan()
        void result.current.handleCreateScan()
      })
      expect(result.current.isCreating).toBe(true)
      act(() => {
        if (change === 'auth') advanceAuthSession()
        else if (change === 'view') result.current.showResults()
        else rerender({ project: change === 'project' ? 'B' : 'A', visible: change !== 'close' })
      })
      await act(async () => {
        finish(initialScanConfig)
        await pending
      })
      expect(createProjectKnowledgeBaseScan).not.toHaveBeenCalled()
      expect(feedback.success).not.toHaveBeenCalled()
    }
  )
  it('preserves the draft and unlocks when creation fails', async () => {
    vi.mocked(createProjectKnowledgeBaseScan).mockRejectedValue(new Error('unavailable'))
    const { result } = renderHook(() => useKnowledgeBaseDrawer('A', true), { wrapper: Wrapper })
    act(() => {
      result.current.showConfig()
      result.current.form.setFieldsValue({ ...initialScanConfig, scope: 'draft' })
    })
    await waitFor(() => expect(result.current.x86BuildReady).toBe(true))
    await act(async () => {
      await result.current.handleCreateScan()
    })
    expect(result.current.form.getFieldValue('scope')).toBe('draft')
    expect(result.current.isCreating).toBe(false)
    expect(result.current.view.type).toBe('config')
  })
})

it.each(['auth', 'project', 'close', 'view'] as const)(
  'ignores a scan accepted after %s changes and permits the next submission',
  async (change) => {
    let resolveOld!: () => void
    vi.mocked(createProjectKnowledgeBaseScan).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          resolveOld = resolve
        })
    )
    const { result, rerender } = renderHook(
      ({ project, visible }) => useKnowledgeBaseDrawer(project, visible),
      { initialProps: { project: 'A', visible: true }, wrapper: Wrapper }
    )
    act(() => result.current.showConfig())
    await waitFor(() => expect(result.current.x86BuildReady).toBe(true))
    vi.spyOn(result.current.form, 'validateFields').mockResolvedValue(initialScanConfig)
    let pending!: Promise<unknown>
    act(() => {
      pending = result.current.handleCreateScan()
    })
    await waitFor(() => expect(createProjectKnowledgeBaseScan).toHaveBeenCalledOnce())
    act(() => {
      if (change === 'auth') advanceAuthSession()
      else if (change === 'view') result.current.showResults()
      else rerender({ project: change === 'project' ? 'B' : 'A', visible: change !== 'close' })
    })
    expect(result.current.submitted).toBe(false)
    expect(result.current.isCreating).toBe(false)
    act(() => {
      if (change === 'close') rerender({ project: 'A', visible: true })
      result.current.showConfig()
    })
    await waitFor(() => expect(result.current.x86BuildReady).toBe(true))
    let resolveNew!: () => void
    vi.mocked(createProjectKnowledgeBaseScan).mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          resolveNew = resolve
        })
    )
    let next!: Promise<unknown>
    act(() => {
      next = result.current.handleCreateScan()
    })
    await waitFor(() => expect(createProjectKnowledgeBaseScan).toHaveBeenCalledTimes(2))
    await act(async () => {
      resolveOld()
      await pending
    })
    expect(feedback.success).not.toHaveBeenCalled()
    expect(result.current.submitted).toBe(false)
    expect(result.current.isCreating).toBe(true)
    await act(async () => {
      resolveNew()
      await next
    })
    expect(createProjectKnowledgeBaseScan).toHaveBeenLastCalledWith(
      change === 'project' ? 'B' : 'A',
      initialScanConfig
    )
    expect(createProjectKnowledgeBaseScan).toHaveBeenCalledTimes(2)
    expect(feedback.success).toHaveBeenCalledExactlyOnceWith('知识库扫描已启动')
    expect(result.current.submitted).toBe(true)
  }
)

it('clears current and selected scan caches while preserving other projects and unrelated scans', async () => {
  const client = createTestQueryClient()
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  const { result } = renderHook(() => useKnowledgeBaseDrawer('A', true), { wrapper })
  await waitFor(() => expect(result.current.x86BuildReady).toBe(true))
  act(() => {
    result.current.showConfig()
    client.setQueryData([...projectQueryKeys.knowledgeBaseScans('A'), 'latest'], {
      freshness: 'CURRENT',
      result: createKnowledgeScan(),
      execution: null
    })
  })
  const removed = [
    [...projectQueryKeys.knowledgeBaseScans('A'), 'history'],
    projectQueryKeys.knowledgeBaseAiExtensions('A', 'scan-1'),
    projectQueryKeys.knowledgeBaseAiExtensionReadiness('A', 'scan-1'),
    ...['scan-1', 'history-1'].flatMap((id) => [
      projectQueryKeys.knowledgeBaseScan(id),
      projectQueryKeys.knowledgeBaseScanArtifacts(id),
      projectQueryKeys.knowledgeBaseFindings(id)
    ])
  ]
  const retained = [
    [...projectQueryKeys.knowledgeBaseScans('B'), 'latest'],
    projectQueryKeys.knowledgeBaseAiExtensions('B', 'scan-other'),
    projectQueryKeys.knowledgeBaseAiExtensionReadiness('B', 'scan-other'),
    projectQueryKeys.knowledgeBaseScan('scan-other'),
    projectQueryKeys.knowledgeBaseScanArtifacts('scan-other'),
    projectQueryKeys.knowledgeBaseFindings('scan-other')
  ]
  act(() => {
    for (const key of [...removed, ...retained])
      client.setQueryData(key, { marker: 'cached', items: [] })
    result.current.showRecord('history-1')
  })
  await waitFor(() => expect(result.current.scan?.id).toBe('history-1'))
  act(() => result.current.clearScanCache())
  expect(
    client.getQueryState([...projectQueryKeys.knowledgeBaseScans('A'), 'latest'])
  ).toBeUndefined()
  for (const key of removed) expect(client.getQueryState(key)).toBeUndefined()
  for (const key of retained)
    expect(client.getQueryData(key)).toEqual({ marker: 'cached', items: [] })
  client.clear()
})
