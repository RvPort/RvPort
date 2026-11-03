import { fireEvent, screen } from '@testing-library/react'
import type { ComponentProps, ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import { createOptimizationCycle } from '@/test/optimization-cycle-fixtures'
import { renderWithQueryClient } from '@/test/test-utils'

import Optimize from '.'
import type { useOptimizationCycleDrawer } from './useOptimizationCycleDrawer'

const hook = vi.hoisted(() => ({ useOptimizationCycleDrawer: vi.fn() }))
vi.mock('./useOptimizationCycleDrawer', () => hook)
vi.mock('../components/StageSurface', () => ({
  default: ({
    children,
    title,
    extra,
    footer,
    onHistory,
    onClose,
    afterOpenChange
  }: {
    children: ReactNode
    title: ReactNode
    extra: ReactNode
    footer: ReactNode
    onHistory?: () => void
    onClose: () => void
    afterOpenChange: (open: boolean) => void
  }) => (
    <div>
      {title}
      {extra}
      {children}
      {footer}
      {onHistory && <button onClick={onHistory}>历史</button>}
      <button onClick={onClose}>关闭</button>
      <button onClick={() => afterOpenChange(false)}>关闭动画完成</button>
    </div>
  )
}))
vi.mock('./components/CycleHistory', () => ({
  CycleHistory: ({
    projectId,
    onOpen
  }: {
    projectId: string
    onOpen: (cycle: ReturnType<typeof createOptimizationCycle>) => void
  }) => (
    <button
      onClick={() =>
        onOpen({ ...createOptimizationCycle(), id: 'historical-cycle', projectId, cycleNumber: 7 })
      }
    >
      打开历史轮次
    </button>
  )
}))

function drawer(overrides: Partial<ReturnType<typeof useOptimizationCycleDrawer>> = {}) {
  return {
    ready: true,
    hasData: true,
    isLoading: false,
    isFetching: false,
    isError: false,
    currentCycle: createOptimizationCycle(),
    freshness: 'CURRENT',
    refetch: vi.fn(async () => []),
    ...overrides
  } as ReturnType<typeof useOptimizationCycleDrawer>
}

describe('optimization view transitions', () => {
  it('recovers request failures and distinguishes missing source from an empty current cycle', () => {
    const change = vi.fn()
    let state = drawer({ isError: true, hasData: false })
    hook.useOptimizationCycleDrawer.mockImplementation(() => state)
    const view = renderWithQueryClient(
      <Optimize visible projectId="project-1" onClose={vi.fn()} onStageChange={change} />
    )
    expect(screen.getByText('优化轮次加载失败')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }))
    expect(state.refetch).toHaveBeenCalledOnce()
    state = { ...state, hasData: true }
    view.rerender(
      <Optimize visible projectId="project-1" onClose={vi.fn()} onStageChange={change} />
    )
    expect(screen.getByText('刷新失败，正在显示上次读取的内容。')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '验证路径' })).toBeInTheDocument()
    state = drawer({ ready: false, currentCycle: null })
    view.rerender(
      <Optimize visible projectId="project-1" onClose={vi.fn()} onStageChange={change} />
    )
    expect(screen.getByText('暂不能查看优化闭环')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '前往源码管理' }))
    expect(change).toHaveBeenCalledWith('assets')
    state = drawer({ currentCycle: null, freshness: 'STALE' })
    view.rerender(<Optimize visible projectId="project-1" onClose={vi.fn()} />)
    expect(
      screen.getByText('当前源码尚无优化轮次，历史记录中保留了旧源码的轮次。')
    ).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '前往源码管理' }))
    state = { ...state, freshness: 'EMPTY' }
    view.rerender(<Optimize visible projectId="project-1" onClose={vi.fn()} />)
    expect(screen.getByText('当前源码尚无优化轮次，请刷新或检查源码状态。')).toBeInTheDocument()
  })

  it('isolates historical selection, refreshes the proper scope and resets after closing', async () => {
    const state = drawer()
    hook.useOptimizationCycleDrawer.mockReturnValue(state)
    let props: ComponentProps<typeof Optimize> = {
      visible: true,
      projectId: 'project-1',
      onClose: vi.fn(),
      onStageChange: vi.fn()
    }
    const view = renderWithQueryClient(<Optimize {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '刷新' }))
    expect(state.refetch).toHaveBeenCalledOnce()
    const invalidate = vi.spyOn(view.queryClient, 'invalidateQueries')
    fireEvent.click(screen.getByRole('button', { name: '历史' }))
    fireEvent.click(await screen.findByRole('button', { name: '打开历史轮次' }))
    expect(screen.getByText('只读记录')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '刷新' })).not.toBeInTheDocument()
    expect(hook.useOptimizationCycleDrawer).toHaveBeenLastCalledWith('project-1', false)
    fireEvent.click(screen.getByRole('button', { name: '返回优化轮次记录' }))
    fireEvent.click(screen.getByRole('button', { name: '刷新优化轮次记录' }))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: expect.arrayContaining(['project-1']) })
    fireEvent.click(screen.getByRole('button', { name: '返回当前结果' }))
    for (const patch of [
      { presentation: 'panel' as const },
      {
        stage: {
          id: 'optimize',
          title: '优化工作区',
          subtitle: '',
          order: 10,
          status: 'pending' as const
        }
      },
      { onStageChange: vi.fn() },
      { projectId: 'project-2' },
      { visible: false },
      { onClose: vi.fn() }
    ]) {
      props = { ...props, ...patch }
      view.rerender(<Optimize {...props} />)
      expect(hook.useOptimizationCycleDrawer).toHaveBeenLastCalledWith(
        props.projectId,
        props.visible
      )
    }
    fireEvent.click(screen.getByRole('button', { name: '关闭' }))
    expect(props.onClose).toHaveBeenCalledOnce()
    expect(hook.useOptimizationCycleDrawer).toHaveBeenLastCalledWith('project-2', false)
    fireEvent.click(screen.getByRole('button', { name: '关闭动画完成' }))
    props = { ...props, visible: true }
    view.rerender(<Optimize {...props} />)
    expect(hook.useOptimizationCycleDrawer).toHaveBeenLastCalledWith('project-2', true)
    expect(screen.getByText('优化工作区')).toBeInTheDocument()
  })
})
