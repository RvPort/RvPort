import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { renderWithStrictQueryClient as renderWithQueryClient } from '@/test/strict-mode-utils'
import type { ProjectOverview } from '@/types/project'

import { AssetsOverview } from './components/content'
import { useAssetsDrawer } from './useAssetsDrawer'

const { updateProjectSourceFromGit, overviewState } = vi.hoisted(() => ({
  updateProjectSourceFromGit: vi.fn(),
  overviewState: { data: undefined as ProjectOverview | undefined }
}))
vi.mock('@/api/project/assets', () => ({
  updateProjectSourceFromGit,
  uploadProjectSourceArtifact: vi.fn()
}))
vi.mock('../../ProjectOverviewContext', () => ({
  useProjectOverviewData: () => ({
    ...overviewState,
    isError: false,
    isLoading: false,
    isFetching: false,
    refetch: vi.fn()
  })
}))
vi.mock('@/utils/feedback', () => ({ feedback: { success: vi.fn(), error: vi.fn() } }))

function Harness() {
  const drawer = useAssetsDrawer('project-1', true)
  return (
    <AssetsOverview
      artifact={drawer.artifact}
      gitForm={drawer.gitForm}
      uploadForm={drawer.uploadForm}
      isEditing={drawer.isEditing}
      isSaving={drawer.isUploading || drawer.isUpdatingGit}
      sourceMode={drawer.view}
      sourceModeLocked={Boolean(drawer.lockedSourceMode)}
      sourceTask={drawer.sourceTask}
      onCancel={drawer.resetSourceInput}
      onGitSubmit={drawer.handleGitUpdate}
      onUploadSubmit={drawer.handleUpload}
      onSourceModeChange={drawer.setSourceMode}
      onStartUpdate={drawer.startSourceUpdate}
    />
  )
}

describe('source import recovery', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    overviewState.data = {
      project: { sourceInputType: 'GIT' },
      currentContext: { activeSourceRevision: null },
      recentTasks: []
    } as unknown as ProjectOverview
  })

  it.each(['GIT', null])(
    'restores the initial Git form after a failed task with source mode %s',
    async (sourceInputType) => {
      overviewState.data!.project.sourceInputType = sourceInputType
      const task = {
        id: 'git-1',
        type: 'SOURCE_GIT_SYNC',
        status: 'QUEUED' as const,
        createdAt: '2026-09-30T00:00:00Z',
        message: null,
        progress: 0
      }
      updateProjectSourceFromGit.mockResolvedValue(task)
      const user = userEvent.setup()
      const { rerender } = renderWithQueryClient(<Harness />)
      if (!sourceInputType) await user.click(screen.getByText('从 Git 更新代码'))
      await user.type(screen.getByLabelText('仓库地址'), 'https://example.com/repo.git')
      await user.type(screen.getByLabelText('Ref'), 'main')
      await user.type(screen.getByLabelText('Access Token（可选）'), 'temporary-token')
      await user.click(screen.getByRole('button', { name: /更\s*新/ }))
      await waitFor(() => expect(screen.queryByLabelText('仓库地址')).not.toBeInTheDocument())
      expect(screen.getByText('Git 同步正在执行，请等待任务完成。')).toBeInTheDocument()
      act(() => {
        overviewState.data!.recentTasks = [{ ...task, status: 'FAILED', message: '认证失败' }]
      })
      rerender(<Harness />)
      expect(screen.getByLabelText('仓库地址')).toHaveValue('https://example.com/repo.git')
      expect(screen.getByLabelText('Ref')).toHaveValue('main')
      expect(screen.getByLabelText('Access Token（可选）')).toHaveValue('')
      expect(screen.getByRole('button', { name: /更\s*新/ })).toBeEnabled()
      expect(updateProjectSourceFromGit).toHaveBeenCalledOnce()
      overviewState.data!.recentTasks = []
      rerender(<Harness />)
      expect(screen.getByLabelText('仓库地址')).toBeEnabled()
    }
  )

  it('uses the server task state after reopening and restores the form after cancellation', () => {
    const task = {
      id: 'git-2',
      type: 'SOURCE_GIT_SYNC',
      status: 'RUNNING' as const,
      message: null,
      progress: 10
    }
    overviewState.data!.recentTasks = [task]
    const { rerender } = renderWithQueryClient(<Harness />)
    expect(screen.queryByLabelText('仓库地址')).not.toBeInTheDocument()
    overviewState.data!.recentTasks = [{ ...task, status: 'CANCELLED' }]
    rerender(<Harness />)
    expect(screen.getByLabelText('仓库地址')).toBeEnabled()
    expect(screen.getByText('Git 同步未完成，请检查输入后重新提交。')).toBeInTheDocument()
  })
})
