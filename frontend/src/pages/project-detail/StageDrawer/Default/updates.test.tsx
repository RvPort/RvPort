import { fireEvent, render, screen } from '@testing-library/react'
import type { ComponentProps, ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import DefaultStage from '.'

vi.mock('../components/StageSurface', () => ({
  default: ({
    visible,
    title,
    children,
    onClose
  }: {
    visible: boolean
    title?: { title: ReactNode }
    children: ReactNode
    onClose: () => void
  }) =>
    visible ? (
      <section>
        <h2>{title?.title}</h2>
        {children}
        <button onClick={onClose}>关闭阶段</button>
      </section>
    ) : null
}))

describe('fallback stage presentation updates', () => {
  it('updates stage status, explanation and title independently and forwards the latest close action', () => {
    let props: ComponentProps<typeof DefaultStage> = {
      visible: true,
      stage: {
        id: 'future',
        order: 20,
        title: '扩展入口',
        subtitle: '等待开始',
        status: 'pending'
      },
      onClose: vi.fn()
    }
    const view = render(<DefaultStage {...props} />)
    for (const [patch, expected] of [
      [{ status: 'running' }, '进行中'],
      [{ subtitle: '正在处理' }, '正在处理'],
      [{ title: '更新入口' }, '更新入口'],
      [{ status: 'blocked' }, '当前模块仅可查看']
    ] as const) {
      props = { ...props, stage: { ...props.stage!, ...patch } }
      view.rerender(<DefaultStage {...props} />)
      expect(screen.getByText(expected)).toBeInTheDocument()
    }
    const close = vi.fn()
    props = { ...props, onClose: close }
    view.rerender(<DefaultStage {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '关闭阶段' }))
    expect(close).toHaveBeenCalledOnce()
    props = { ...props, stage: null }
    view.rerender(<DefaultStage {...props} />)
    expect(screen.queryByText('阶段状态')).not.toBeInTheDocument()
    props = { ...props, visible: false }
    view.rerender(<DefaultStage {...props} />)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
