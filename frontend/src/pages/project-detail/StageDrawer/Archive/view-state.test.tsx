import { fireEvent, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { expect, it, vi } from 'vitest'

import { renderWithQueryClient } from '@/test/test-utils'

import Archive from '.'

const hook = vi.hoisted(() => ({ useArchiveDrawer: vi.fn() }))
vi.mock('./useArchiveDrawer', () => hook)
vi.mock('../components/StageSurface', () => ({
  default: ({
    title,
    children,
    extra,
    onClose,
    afterOpenChange
  }: {
    title: ReactNode
    children: ReactNode
    extra: ReactNode
    onClose: () => void
    afterOpenChange: (open: boolean) => void
  }) => (
    <div>
      {title}
      {children}
      {extra}
      <button onClick={onClose}>关闭</button>
      <button onClick={() => afterOpenChange(true)}>打开结束</button>
      <button onClick={() => afterOpenChange(false)}>关闭结束</button>
    </div>
  )
}))
vi.mock('./components/ArchiveHistory', () => ({
  ArchiveHistory: ({ projectId, onOpen }: { projectId: string; onOpen: (id: string) => void }) => (
    <button onClick={() => onOpen('old')}>历史列表 {projectId}</button>
  )
}))
vi.mock('./components/ArchiveItems', () => ({ ArchiveItems: () => null }))

it('uses current refresh, close and history callbacks and preserves a direct detail return route', async () => {
  let state = {
    view: { type: 'overview' } as {
      type: 'overview' | 'history' | 'detail'
      archiveId?: string
      returnTo?: 'overview' | 'history'
    },
    archive: null,
    latestArchive: null,
    latestQuery: { data: { execution: null } },
    readinessQuery: { data: {} },
    prerequisites: [],
    canCreate: true,
    isCreating: false,
    submitted: false,
    isLoading: false,
    isError: false,
    isDetailLoading: false,
    isDetailError: false,
    freshness: 'EMPTY',
    create: vi.fn(),
    close: vi.fn(),
    finishClose: vi.fn(),
    showHistory: vi.fn(),
    showOverview: vi.fn(),
    refreshCurrent: vi.fn(),
    refreshHistory: vi.fn(),
    showDetail: vi.fn(),
    refetchDetail: vi.fn()
  }
  hook.useArchiveDrawer.mockImplementation(() => state)
  let close = vi.fn(),
    title = '归档阶段'
  let projectId: string | undefined = 'project'
  const draw = () => (
    <Archive
      visible
      projectId={projectId}
      stage={{ id: 'archive', title, subtitle: '', order: 9, status: 'pending' }}
      onClose={close}
    />
  )
  const view = renderWithQueryClient(draw())
  expect(screen.getByText(title)).toBeInTheDocument()
  title = '交付归档'
  view.rerender(draw())
  expect(screen.getByText(title)).toBeInTheDocument()
  const oldRefresh = state.refreshCurrent
  state = { ...state, refreshCurrent: vi.fn() }
  view.rerender(draw())
  fireEvent.click(screen.getByRole('button', { name: '刷新归档信息' }))
  expect(state.refreshCurrent).toHaveBeenCalledOnce()
  expect(oldRefresh).not.toHaveBeenCalled()
  const oldClose = close
  close = vi.fn()
  view.rerender(draw())
  fireEvent.click(screen.getByText('关闭'))
  expect(close).toHaveBeenCalledOnce()
  expect(oldClose).not.toHaveBeenCalled()
  state = { ...state, finishClose: vi.fn() }
  view.rerender(draw())
  fireEvent.click(screen.getByText('打开结束'))
  expect(state.finishClose).not.toHaveBeenCalled()
  fireEvent.click(screen.getByText('关闭结束'))
  expect(state.finishClose).toHaveBeenCalledOnce()
  state = {
    ...state,
    view: { type: 'detail', archiveId: 'old', returnTo: 'overview' },
    isDetailLoading: true
  }
  view.rerender(draw())
  expect(await screen.findByRole('status', { name: '正在读取归档记录' })).toBeInTheDocument()
  state = { ...state, isDetailLoading: false, isDetailError: true }
  view.rerender(draw())
  expect(screen.getByText('归档详情加载失败')).toBeInTheDocument()
  const oldRead = state.refetchDetail
  state = { ...state, refetchDetail: vi.fn() }
  view.rerender(draw())
  fireEvent.click(screen.getByRole('button', { name: '重新加载' }))
  expect(state.refetchDetail).toHaveBeenCalledOnce()
  expect(oldRead).not.toHaveBeenCalled()
  const oldBack = state.showOverview
  state = { ...state, showOverview: vi.fn() }
  view.rerender(draw())
  fireEvent.click(screen.getByRole('button', { name: '返回当前归档' }))
  expect(state.showOverview).toHaveBeenCalledOnce()
  expect(oldBack).not.toHaveBeenCalled()
  state = { ...state, view: { type: 'history' }, refreshHistory: vi.fn() }
  projectId = undefined
  view.rerender(draw())
  fireEvent.click(await screen.findByRole('button', { name: '历史列表' }))
  expect(state.showDetail).toHaveBeenCalledWith('old', 'history')
  fireEvent.click(screen.getByRole('button', { name: '刷新归档记录' }))
  expect(state.refreshHistory).toHaveBeenCalledOnce()
})
