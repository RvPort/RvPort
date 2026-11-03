import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createTestQueryClient } from '@/test/test-utils'
import type { ProjectOverview } from '@/types/project'

const { updateProjectSourceFromGit, uploadProjectSourceArtifact, overviewState } = vi.hoisted(
  () => ({
    updateProjectSourceFromGit: vi.fn(),
    uploadProjectSourceArtifact: vi.fn(),
    overviewState: { data: undefined as ProjectOverview | undefined }
  })
)
const { feedbackError } = vi.hoisted(() => ({ feedbackError: vi.fn() }))
vi.mock('@/api/project/assets', () => ({ updateProjectSourceFromGit, uploadProjectSourceArtifact }))
vi.mock('../../ProjectOverviewContext', () => ({
  useProjectOverviewData: () => ({
    ...overviewState,
    isError: false,
    isLoading: false,
    isFetching: false,
    refetch: vi.fn()
  })
}))
vi.mock('@/utils/feedback', () => ({ feedback: { success: vi.fn(), error: feedbackError } }))

import { projectQueryKeys } from '../../queryKeys'
import { useAssetsDrawer } from './useAssetsDrawer'

function setup(sourceInputType: 'GIT' | 'ARCHIVE' | null) {
  overviewState.data = {
    project: { sourceInputType },
    currentContext: { activeSourceRevision: null },
    recentTasks: []
  } as unknown as ProjectOverview
  const client = createTestQueryClient()
  client.setQueryData(projectQueryKeys.overview('project-1'), overviewState.data)
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return {
    client,
    ...renderHook(({ projectId }) => useAssetsDrawer(projectId, true), {
      wrapper,
      initialProps: { projectId: 'project-1' }
    })
  }
}

describe('source asset submissions', () => {
  it('locks an initially unspecified mode after submission, observes the server task and releases stale task ownership on project switch', async () => {
    const submitted = {
      id: 'submitted',
      type: 'SOURCE_GIT_SYNC',
      status: 'QUEUED',
      message: null,
      progress: null,
      createdAt: '2026-10-28T00:00:00Z'
    }
    updateProjectSourceFromGit.mockResolvedValue(submitted)
    const { result, rerender } = setup(null)
    expect(result.current.lockedSourceMode).toBeUndefined()
    act(() => result.current.setSourceMode('git'))
    expect(result.current.view).toBe('git')
    await act(async () => {
      await result.current.handleGitUpdate({
        repositoryUrl: 'https://example.invalid/repo',
        ref: 'main'
      })
    })
    expect(result.current.lockedSourceMode).toBe('git')
    expect(result.current.sourceTask?.id).toBe('submitted')
    expect(result.current.isEditing).toBe(false)
    act(() => result.current.setSourceMode('upload'))
    expect(result.current.view).toBe('git')
    overviewState.data = {
      ...overviewState.data!,
      recentTasks: [{ ...submitted, status: 'FAILED', message: '同步失败' }]
    } as ProjectOverview
    rerender({ projectId: 'project-1' })
    expect(result.current.sourceTask?.status).toBe('FAILED')
    expect(result.current.isEditing).toBe(true)
    overviewState.data = { ...overviewState.data!, recentTasks: [] }
    rerender({ projectId: 'project-2' })
    expect(result.current.lockedSourceMode).toBeUndefined()
    expect(result.current.sourceTask).toBeUndefined()
    act(() => result.current.setSourceMode('upload'))
    expect(result.current.view).toBe('upload')
  })

  it('orders import tasks by creation time and excludes unrelated task types from edit blocking', () => {
    const { result, rerender } = setup(null)
    overviewState.data = {
      ...overviewState.data!,
      recentTasks: [
        {
          id: 'old',
          type: 'SOURCE_GIT_SYNC',
          status: 'RUNNING',
          createdAt: '2026-10-27T00:00:00Z',
          message: null,
          progress: 30
        },
        {
          id: 'new',
          type: 'SOURCE_GIT_SYNC',
          status: 'SUCCEEDED',
          createdAt: '2026-10-28T00:00:00Z',
          message: null,
          progress: 100
        },
        {
          id: 'unrelated',
          type: 'KB_SCAN',
          status: 'RUNNING',
          createdAt: '2026-10-29T00:00:00Z',
          message: null,
          progress: 10
        },
        {
          id: 'unknown-date',
          type: 'SOURCE_GIT_SYNC',
          status: 'FAILED',
          createdAt: 'invalid',
          message: null,
          progress: null
        }
      ]
    }
    rerender({ projectId: 'project-1' })
    expect(result.current.sourceTask?.id).toBe('new')
    expect(result.current.isEditing).toBe(true)
    overviewState.data = {
      ...overviewState.data!,
      currentContext: {
        ...overviewState.data!.currentContext,
        activeSourceRevision: {
          active: true,
          id: 'source-1',
          type: 'ARCHIVE',
          version: 'v1',
          versionNote: '',
          fileName: 'source.zip',
          updatedAt: '2026-10-28T00:00:00Z'
        }
      }
    }
    rerender({ projectId: 'project-1' })
    expect(result.current.lockedSourceMode).toBe('upload')
    expect(result.current.isEditing).toBe(false)
    act(() => result.current.startSourceUpdate())
    expect(result.current.isEditing).toBe(true)
    act(() => result.current.resetSourceInput())
    expect(result.current.isEditing).toBe(false)
    expect(result.current.view).toBe('upload')
  })
  beforeEach(() => {
    updateProjectSourceFromGit.mockReset()
    uploadProjectSourceArtifact.mockReset()
  })

  it('trims Git fields while preserving the submitted token and clearing its draft', async () => {
    updateProjectSourceFromGit.mockResolvedValue({
      id: 'task-1',
      type: 'SOURCE_GIT_SYNC',
      status: 'QUEUED',
      createdAt: '2026-10-27T08:00:00Z'
    })
    const { result } = setup('GIT')
    await act(async () => {
      await result.current.handleGitUpdate({
        repositoryUrl: ' https://example.com/repo.git ',
        ref: ' main ',
        username: ' user ',
        accessToken: 'temporary-token'
      })
    })
    expect(updateProjectSourceFromGit.mock.calls[0]).toEqual([
      'project-1',
      {
        repositoryUrl: 'https://example.com/repo.git',
        ref: 'main',
        username: 'user',
        accessToken: 'temporary-token'
      }
    ])
    expect(result.current.gitForm.getFieldValue('accessToken')).toBeUndefined()
    expect(result.current.sourceTask?.id).toBe('task-1')
    expect(result.current.view).toBe('git')
  })

  it('rejects an upload without a file before calling the API', async () => {
    const { result } = setup('ARCHIVE')
    await act(async () => {
      await result.current.handleUpload({ file: [] })
    })
    expect(uploadProjectSourceArtifact).not.toHaveBeenCalled()
    expect(feedbackError).toHaveBeenCalledWith('请选择源码压缩包')
  })

  it('updates the overview cache after a successful archive upload', async () => {
    const artifact = { id: 'source-2', type: 'ARCHIVE', version: 'v2' }
    uploadProjectSourceArtifact.mockResolvedValue(artifact)
    const file = Object.assign(new File(['source'], 'source.zip'), {
      uid: 'upload-1',
      lastModifiedDate: new Date()
    })
    const { client, result } = setup('ARCHIVE')
    await act(async () => {
      await result.current.handleUpload({
        file: [{ uid: file.uid, name: file.name, originFileObj: file }]
      })
    })
    expect(uploadProjectSourceArtifact.mock.calls[0][0]).toBe('project-1')
    expect(uploadProjectSourceArtifact.mock.calls[0][1].file).toBe(file)
    expect(
      client.getQueryData<ProjectOverview>(projectQueryKeys.overview('project-1'))?.currentContext
        .activeSourceRevision
    ).toEqual(artifact)
  })
})
