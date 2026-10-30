import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import Assets from '.'

const { drawer, useAssetsDrawer } = vi.hoisted(() => {
  const state = {
    artifact: undefined,
    gitForm: {},
    uploadForm: {},
    isEditing: false,
    isFetching: false,
    isLoading: false,
    isError: false,
    isUploading: false,
    isUpdatingGit: false,
    lockedSourceMode: null as string | null,
    sourceTask: undefined,
    view: 'git',
    refetch: vi.fn(),
    resetSourceInput: vi.fn(),
    handleGitUpdate: vi.fn(),
    handleUpload: vi.fn(),
    setSourceMode: vi.fn(),
    startSourceUpdate: vi.fn()
  }
  return { drawer: state, useAssetsDrawer: vi.fn(() => state) }
})

vi.mock('./useAssetsDrawer', () => ({ useAssetsDrawer }))
vi.mock('../components/StageSurface', () => ({
  default: ({
    afterOpenChange,
    children,
    extra,
    loading,
    onClose,
    title
  }: {
    afterOpenChange?: (open: boolean) => void
    children: React.ReactNode
    extra: React.ReactNode
    loading: boolean
    onClose: () => void
    title: React.ReactNode
  }) => (
    <section aria-label="源码管理" data-loading={loading}>
      {title}
      {extra}
      {children}
      <button onClick={() => afterOpenChange?.(false)}>关闭后</button>
      <button onClick={onClose}>关闭</button>
    </section>
  )
}))
vi.mock('./components/content', () => ({
  AssetsOverview: ({
    isSaving,
    sourceModeLocked,
    onStartUpdate
  }: {
    isSaving: boolean
    sourceModeLocked: boolean
    onStartUpdate: () => void
  }) => (
    <div data-testid="assets-overview" data-saving={isSaving} data-mode-locked={sourceModeLocked}>
      <button onClick={onStartUpdate}>更新源码</button>
    </div>
  )
}))

const stage = {
  id: 'assets',
  order: 2,
  title: '源码',
  subtitle: '当前源码',
  status: 'pending' as const
}

beforeEach(() => {
  vi.clearAllMocks()
  Object.assign(drawer, {
    isEditing: false,
    isFetching: false,
    isLoading: false,
    isError: false,
    isUploading: false,
    isUpdatingGit: false,
    lockedSourceMode: null,
    view: 'git'
  })
})

describe('source stage composition', () => {
  it('shows loading without editable source content and resets after closing', async () => {
    const user = userEvent.setup()
    drawer.isLoading = true
    const onClose = vi.fn()
    render(<Assets projectId="project-1" stage={stage} visible onClose={onClose} />)
    expect(screen.getByRole('region', { name: '源码管理' })).toHaveAttribute('data-loading', 'true')
    expect(screen.queryByTestId('assets-overview')).not.toBeInTheDocument()
    expect(useAssetsDrawer).toHaveBeenCalledWith('project-1', true, undefined)
    await user.click(screen.getByRole('button', { name: '关闭后' }))
    expect(drawer.resetSourceInput).toHaveBeenCalledOnce()
    await user.click(screen.getByRole('button', { name: /^关闭$/ }))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('offers retry when the source request fails', async () => {
    const user = userEvent.setup()
    drawer.isError = true
    render(<Assets stage={stage} visible onClose={vi.fn()} />)
    expect(screen.getByText('源码制品详情加载失败')).toBeInTheDocument()
    expect(screen.queryByTestId('assets-overview')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /重\s*试/ }))
    expect(drawer.refetch).toHaveBeenCalledOnce()
  })

  it('passes source editing and saving state to the overview and refreshes it', async () => {
    const user = userEvent.setup()
    const onDirtyChange = vi.fn()
    Object.assign(drawer, { isFetching: true, isUploading: true, lockedSourceMode: 'git' })
    const view = render(
      <Assets
        projectId="project-1"
        stage={stage}
        visible
        onClose={vi.fn()}
        onDirtyChange={onDirtyChange}
      />
    )
    expect(useAssetsDrawer).toHaveBeenCalledWith('project-1', true, onDirtyChange)
    expect(screen.getByTestId('assets-overview')).toHaveAttribute('data-saving', 'true')
    expect(screen.getByTestId('assets-overview')).toHaveAttribute('data-mode-locked', 'true')
    expect(screen.getByRole('button', { name: '刷新' })).toHaveClass('ant-btn-loading')
    await user.click(screen.getByRole('button', { name: '更新源码' }))
    expect(drawer.startSourceUpdate).toHaveBeenCalledOnce()
    drawer.isFetching = false
    view.rerender(
      <Assets
        projectId="project-1"
        stage={stage}
        visible
        onClose={vi.fn()}
        onDirtyChange={onDirtyChange}
      />
    )
    await user.click(screen.getByRole('button', { name: '刷新' }))
    expect(drawer.refetch).toHaveBeenCalledOnce()
  })
})
