import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import StageDrawer from '.'

vi.mock('./stageLoaders', () => ({
  loadEnvironmentStage: () => new Promise(() => {})
}))

const stage = {
  id: 'env',
  order: 2,
  title: '环境管理',
  subtitle: '配置迁移环境',
  status: 'pending' as const
}

describe('stage loading interactions', () => {
  it('supports keyboard, mask and close button while loading and releases keyboard handling when hidden', async () => {
    const onClose = vi.fn()
    const view = render(
      <StageDrawer projectId="project-1" stage={stage} visible onClose={onClose} />
    )
    expect(await screen.findByRole('complementary', { name: '正在打开环境管理' })).toHaveAttribute(
      'aria-busy',
      'true'
    )
    expect(screen.getByText('配置迁移环境')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '点击遮罩关闭阶段抽屉' }))
    fireEvent.click(screen.getByRole('button', { name: '关闭阶段抽屉' }))
    fireEvent.keyDown(document, { key: 'Enter' })
    expect(onClose).toHaveBeenCalledTimes(2)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(3)
    view.rerender(
      <StageDrawer projectId="project-1" stage={null} visible={false} onClose={onClose} />
    )
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(3)
  })

  it('uses the new close callback and stage label after the parent changes during loading', async () => {
    const oldClose = vi.fn()
    const nextClose = vi.fn()
    const view = render(
      <StageDrawer projectId="project-1" stage={stage} visible onClose={oldClose} />
    )
    await screen.findByRole('complementary', { name: '正在打开环境管理' })
    view.rerender(
      <StageDrawer
        projectId="project-2"
        stage={{ ...stage, title: '新项目环境', subtitle: '' }}
        visible
        onClose={nextClose}
      />
    )
    expect(
      await screen.findByRole('complementary', { name: '正在打开新项目环境' })
    ).toBeInTheDocument()
    expect(screen.queryByText('配置迁移环境')).not.toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(nextClose).toHaveBeenCalledOnce()
    expect(oldClose).not.toHaveBeenCalled()
  })

  it('removes drawer-only close controls and keyboard handling when changing to an inline panel', async () => {
    const onClose = vi.fn()
    const view = render(
      <StageDrawer projectId="project-1" stage={stage} visible onClose={onClose} />
    )
    await screen.findByRole('complementary', { name: '正在打开环境管理' })
    view.rerender(
      <StageDrawer
        projectId="project-1"
        stage={stage}
        visible
        presentation="panel"
        onClose={onClose}
      />
    )
    expect(await screen.findByRole('status', { name: '正在加载环境管理' })).toBeInTheDocument()
    expect(
      screen.queryByRole('complementary', { name: '正在打开环境管理' })
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '关闭阶段抽屉' })).not.toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()
  })
})
