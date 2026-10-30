import { type ReactNode } from 'react'
import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'

import * as knowledgeApi from '@/api/project/knowledge-base'
import { getProjectStageReadiness } from '@/api/project/workflow'
import { createKnowledgeScan } from '@/test/knowledge-base-fixtures'
import { createTestQueryClient } from '@/test/test-utils'
import type { ProjectStageReadiness } from '@/types/project'
import type {
  KnowledgeBaseAiExtensionCreateResponse,
  KnowledgeBaseAiExtensionRun
} from '@/types/project/knowledge-base'
import { feedback } from '@/utils/feedback'

import { projectQueryKeys } from '../../queryKeys'
import { initialScanConfig } from './types'
import { useKnowledgeBaseDrawer } from './useKnowledgeBaseDrawer'

vi.mock('@/api/project/knowledge-base', async (original) => ({
  ...(await original<typeof knowledgeApi>()),
  createProjectKnowledgeBaseScan: vi.fn(),
  createProjectKnowledgeBaseAiExtension: vi.fn(),
  getProjectKnowledgeBaseScanLatest: vi.fn(),
  getProjectKnowledgeBaseAiExtensionReadiness: vi.fn(),
  getProjectKnowledgeBaseAiExtensions: vi.fn()
}))
vi.mock('@/api/project/workflow', () => ({ getProjectStageReadiness: vi.fn() }))
vi.mock('@/utils/feedback', () => ({ feedback: { success: vi.fn(), error: vi.fn() } }))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
function readiness(
  sourceRevisionId = 'source-1',
  runtimeEnvironmentVersion = 1
): ProjectStageReadiness {
  return {
    stageId: 'kb',
    context: { sourceRevisionId, runtimeEnvironmentVersion },
    prerequisites: ['ACTIVE_SOURCE', 'X86_BUILD'].map((key) => ({
      key,
      status: 'SATISFIED',
      referenceId: null,
      reasonCode: 'READY',
      details: {}
    }))
  }
}
function latest(scanId?: string) {
  return {
    context: readiness().context,
    freshness: scanId ? ('CURRENT' as const) : ('EMPTY' as const),
    execution: null,
    result: scanId ? createKnowledgeScan({ id: scanId }) : null
  }
}
function aiResponse(scanId: string): KnowledgeBaseAiExtensionCreateResponse {
  return {
    task: { id: `task-${scanId}`, status: 'QUEUED' },
    run: {
      id: `ai-${scanId}`,
      projectId: 'project-1',
      sourceRevisionId: 'source-1',
      kbScanRunId: scanId,
      taskId: `task-${scanId}`,
      modelProfileId: 'model-1',
      agentRuntimeId: 'runtime-1',
      status: 'QUEUED',
      summary: {},
      inputArtifactId: null,
      outputArtifactId: null,
      agentOutputArtifactId: null,
      startedAt: null,
      finishedAt: null,
      createdAt: '2026-10-29T00:00:00Z',
      updatedAt: '2026-10-29T00:00:00Z'
    }
  }
}
function setup() {
  const client = createTestQueryClient()
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return { client, ...renderHook(() => useKnowledgeBaseDrawer('project-1', true), { wrapper }) }
}
beforeEach(() => {
  vi.mocked(getProjectStageReadiness).mockResolvedValue(readiness())
  vi.mocked(knowledgeApi.getProjectKnowledgeBaseScanLatest).mockResolvedValue(latest())
  vi.mocked(knowledgeApi.getProjectKnowledgeBaseAiExtensionReadiness).mockImplementation(
    async (_project, scan) => ({
      ready: true,
      reason: null,
      kbScanRunId: scan,
      findingCount: 1,
      modelConfigured: true,
      model: {}
    })
  )
  vi.mocked(knowledgeApi.getProjectKnowledgeBaseAiExtensions).mockResolvedValue({
    items: [],
    hasMore: false,
    page: 1,
    pageSize: 1,
    total: 0
  })
})

it.each(['source', 'environment'] as const)(
  'isolates old and new scan submissions when the %s context changes',
  async (change) => {
    const old = deferred<void>()
    vi.mocked(knowledgeApi.createProjectKnowledgeBaseScan).mockReturnValueOnce(old.promise)
    const { result, client } = setup()
    act(() => result.current.showConfig())
    await waitFor(() => expect(result.current.x86BuildReady).toBe(true))
    vi.spyOn(result.current.form, 'validateFields').mockResolvedValue(initialScanConfig)
    let pending!: Promise<unknown>
    act(() => {
      pending = result.current.handleCreateScan()
    })
    await waitFor(() => expect(knowledgeApi.createProjectKnowledgeBaseScan).toHaveBeenCalledOnce())
    act(() =>
      client.setQueryData(
        projectQueryKeys.readiness('project-1', 'kb'),
        change === 'source' ? readiness('source-2') : readiness('source-1', 2)
      )
    )
    await waitFor(() =>
      expect(result.current.readinessQuery.data?.context).toEqual(
        change === 'source' ? readiness('source-2').context : readiness('source-1', 2).context
      )
    )
    expect(result.current.isCreating).toBe(false)
    const current = deferred<void>()
    vi.mocked(knowledgeApi.createProjectKnowledgeBaseScan).mockReturnValueOnce(current.promise)
    let next!: Promise<unknown>
    act(() => {
      next = result.current.handleCreateScan()
    })
    await waitFor(() =>
      expect(knowledgeApi.createProjectKnowledgeBaseScan).toHaveBeenCalledTimes(2)
    )
    await act(async () => {
      old.resolve()
      await pending
    })
    expect(feedback.success).not.toHaveBeenCalled()
    expect(result.current.view.type).toBe('config')
    expect(result.current.submitted).toBe(false)
    expect(result.current.isCreating).toBe(true)
    await act(async () => {
      current.resolve()
      await next
    })
    expect(feedback.success).toHaveBeenCalledExactlyOnceWith('知识库扫描已启动')
    expect(result.current.submitted).toBe(true)
    expect(result.current.isCreating).toBe(false)
    expect(result.current.view.type).toBe('results')
    client.clear()
  }
)

it.each(['source', 'environment'] as const)(
  'does not send a scan whose %s context changes during form validation',
  async (change) => {
    const validation = deferred<typeof initialScanConfig>()
    const { result, client } = setup()
    act(() => result.current.showConfig())
    await waitFor(() => expect(result.current.x86BuildReady).toBe(true))
    vi.spyOn(result.current.form, 'validateFields').mockReturnValueOnce(validation.promise)
    let pending!: Promise<unknown>
    act(() => {
      pending = result.current.handleCreateScan()
    })
    act(() =>
      client.setQueryData(
        projectQueryKeys.readiness('project-1', 'kb'),
        change === 'source' ? readiness('source-2') : readiness('source-1', 2)
      )
    )
    await waitFor(() =>
      expect(result.current.readinessQuery.data?.context).toEqual(
        change === 'source' ? readiness('source-2').context : readiness('source-1', 2).context
      )
    )
    await act(async () => {
      validation.resolve(initialScanConfig)
      await pending
    })
    expect(knowledgeApi.createProjectKnowledgeBaseScan).not.toHaveBeenCalled()
    expect(feedback.success).not.toHaveBeenCalled()
    expect(result.current.view.type).toBe('config')
    expect(result.current.isCreating).toBe(false)
    client.clear()
  }
)

it.each(['resolve', 'reject'] as const)(
  'isolates an old AI suggestion %s after the current scan changes and retains the new submission lock',
  async (outcome) => {
    const old = deferred<KnowledgeBaseAiExtensionCreateResponse>()
    const current = deferred<KnowledgeBaseAiExtensionCreateResponse>()
    vi.mocked(knowledgeApi.getProjectKnowledgeBaseScanLatest).mockResolvedValue(latest('scan-A'))
    vi.mocked(knowledgeApi.createProjectKnowledgeBaseAiExtension)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(current.promise)
    const { result, client } = setup()
    await waitFor(() => expect(result.current.aiSuggestionReadiness?.kbScanRunId).toBe('scan-A'))
    let pending!: Promise<void>
    act(() => {
      pending = result.current.handleCreateAiSuggestion()
    })
    await waitFor(() =>
      expect(knowledgeApi.createProjectKnowledgeBaseAiExtension).toHaveBeenCalledOnce()
    )
    act(() =>
      client.setQueryData(
        [...projectQueryKeys.knowledgeBaseScans('project-1'), 'latest'],
        latest('scan-B')
      )
    )
    await waitFor(() => expect(result.current.aiSuggestionReadiness?.kbScanRunId).toBe('scan-B'))
    let next!: Promise<void>
    act(() => {
      next = result.current.handleCreateAiSuggestion()
    })
    await waitFor(() =>
      expect(knowledgeApi.createProjectKnowledgeBaseAiExtension).toHaveBeenCalledTimes(2)
    )
    await act(async () => {
      if (outcome === 'resolve') old.resolve(aiResponse('scan-A'))
      else old.reject(new Error('old scan failed'))
      await pending
    })
    expect(feedback.success).not.toHaveBeenCalled()
    expect(feedback.error).not.toHaveBeenCalled()
    expect(result.current.aiSuggestionRun).toBeUndefined()
    expect(result.current.isCreatingAiSuggestion).toBe(true)
    expect(
      client.getQueryData<{ items: KnowledgeBaseAiExtensionRun[] }>(
        projectQueryKeys.knowledgeBaseAiExtensions('project-1', 'scan-A')
      )?.items
    ).toEqual([])
    await act(async () => {
      current.resolve(aiResponse('scan-B'))
      await next
    })
    expect(vi.mocked(knowledgeApi.createProjectKnowledgeBaseAiExtension).mock.calls).toEqual([
      ['project-1', 'scan-A'],
      ['project-1', 'scan-B']
    ])
    expect(result.current.aiSuggestionRun?.kbScanRunId).toBe('scan-B')
    expect(result.current.isCreatingAiSuggestion).toBe(false)
    expect(feedback.success).toHaveBeenCalledExactlyOnceWith('AI 建议生成任务已启动')
    client.clear()
  }
)
