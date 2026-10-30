import { render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import DefaultStage from '.'

vi.mock('@/components/BaseDrawer', () => ({
  default: ({ children, visible }: { children: ReactNode; visible: boolean }) =>
    visible ? <section aria-label="阶段信息">{children}</section> : null
}))

describe('unmapped stage fallback', () => {
  it('explains a blocked stage without offering an unsupported action and updates when it becomes available', () => {
    const stage = {
      id: 'future-stage',
      order: 20,
      title: '扩展阶段',
      subtitle: '需要先完成源码准备',
      status: 'blocked' as const
    }
    const view = render(<DefaultStage stage={stage} visible onClose={vi.fn()} />)
    expect(screen.getByText('当前模块仅可查看')).toBeInTheDocument()
    expect(screen.getByText('阻塞')).toBeInTheDocument()
    expect(screen.getByText('需要先完成源码准备')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    view.rerender(
      <DefaultStage
        stage={{ ...stage, status: 'done', subtitle: '该扩展阶段已完成' }}
        visible
        onClose={vi.fn()}
      />
    )
    expect(screen.queryByText('当前模块仅可查看')).not.toBeInTheDocument()
    expect(screen.queryByText('需要先完成源码准备')).not.toBeInTheDocument()
    expect(screen.getByText('已完成')).toBeInTheDocument()
    expect(screen.getByText('该扩展阶段已完成')).toBeInTheDocument()
  })

  it('handles an absent selection and remains empty after closing', () => {
    const view = render(<DefaultStage visible onClose={vi.fn()} />)
    expect(screen.queryByText('阶段状态')).not.toBeInTheDocument()
    view.rerender(<DefaultStage visible={false} stage={null} onClose={vi.fn()} />)
    expect(screen.queryByRole('region', { name: '阶段信息' })).not.toBeInTheDocument()
  })
})
