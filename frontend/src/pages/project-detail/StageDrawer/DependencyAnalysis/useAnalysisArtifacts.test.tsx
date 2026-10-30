import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

import { advanceAuthSession } from '@/store/authSession'
import { createTestQueryClient } from '@/test/test-utils'
import type {
  ProjectDependencyAnalysis,
  ProjectDependencyAnalysisArtifact
} from '@/types/project/dependency-analysis'

import { useAnalysisArtifacts } from './useAnalysisArtifacts'

const api = vi.hoisted(() => ({
  getProjectDependencyAnalysisArtifact: vi.fn(),
  getProjectDependencyAnalysisArtifacts: vi.fn()
}))
const feedback = vi.hoisted(() => ({ error: vi.fn() }))
vi.mock('@/api/project/dependency-analysis', () => api)
vi.mock('@/utils/feedback', () => ({ feedback }))
const analysis: ProjectDependencyAnalysis = {
  id: 'analysis',
  projectId: 'project',
  taskId: 'task',
  sourceRevisionId: 'source',
  sourceBuildRunId: 'build',
  targetEnvironmentId: 'target',
  environmentConfigVersion: 1,
  currentEnvironmentConfigVersion: 1,
  currentRuntimeEnvironment: true,
  status: 'SUCCEEDED',
  summary: {},
  warnings: [],
  createdAt: '',
  startedAt: null,
  finishedAt: null
}
const artifact: ProjectDependencyAnalysisArtifact = {
  id: 'artifact',
  artifactType: 'SUMMARY',
  contentType: 'application/json',
  sizeBytes: null,
  sha256: null,
  summary: {},
  createdAt: ''
}
function setup() {
  const client = createTestQueryClient()
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return renderHook(({ id }) => useAnalysisArtifacts({ ...analysis, id }), {
    initialProps: { id: analysis.id },
    wrapper
  })
}
beforeEach(() => {
  api.getProjectDependencyAnalysisArtifacts.mockResolvedValue([artifact])
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL = vi.fn(() => 'blob:download')
      static revokeObjectURL = vi.fn()
    }
  )
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

it.each(['replacement analysis', 'auth session'])(
  'ignores a download completed after changing %s',
  async (kind) => {
    let finish!: (value: { artifact: ProjectDependencyAnalysisArtifact; content: object }) => void
    api.getProjectDependencyAnalysisArtifact.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const { result, rerender } = setup()
    let pending: Promise<unknown>
    act(() => {
      pending = result.current.downloadArtifactMutation.mutateAsync(artifact)
    })
    await waitFor(() =>
      expect(api.getProjectDependencyAnalysisArtifact).toHaveBeenCalledWith('analysis', 'SUMMARY')
    )
    act(() => {
      if (kind === 'auth session') advanceAuthSession()
      else rerender({ id: 'new-analysis' })
    })
    await act(async () => {
      finish({ artifact, content: { stale: true } })
      await pending
    })
    expect(URL.createObjectURL).not.toHaveBeenCalled()
    expect(HTMLAnchorElement.prototype.click).not.toHaveBeenCalled()
    expect(feedback.error).not.toHaveBeenCalled()
  }
)
it('releases a failed download, permits retry, and revokes its temporary URL', async () => {
  api.getProjectDependencyAnalysisArtifact
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValue({ artifact, content: { count: 3 } })
  const { result } = setup()
  await act(async () => {
    await result.current.downloadArtifactMutation.mutateAsync(artifact)
  })
  expect(feedback.error).toHaveBeenCalledWith('分析制品下载失败')
  vi.useFakeTimers()
  const click = vi.mocked(HTMLAnchorElement.prototype.click)
  await act(async () => {
    await result.current.downloadArtifactMutation.mutateAsync(artifact)
  })
  expect(click).toHaveBeenCalledOnce()
  const anchor = click.mock.instances[0] as HTMLAnchorElement
  expect(anchor.download).toBe('summary.json')
  expect(document.body.contains(anchor)).toBe(false)
  expect(URL.createObjectURL).toHaveBeenCalledWith(
    expect.objectContaining({ type: 'application/json' })
  )
  expect(URL.revokeObjectURL).not.toHaveBeenCalled()
  await act(async () => vi.advanceTimersByTimeAsync(0))
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:download')
})
