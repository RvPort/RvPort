import { fireEvent, render, screen } from '@testing-library/react'
import type { ComponentProps } from 'react'
import { describe, expect, it, vi } from 'vitest'

import PrerequisiteNotice from '.'

describe('prerequisite notice updates', () => {
  it('updates readiness and recovery details independently and dispatches only the latest action', () => {
    const original = vi.fn()
    let props: ComponentProps<typeof PrerequisiteNotice> = {
      title: '条件未满足',
      description: '缺少构建',
      actionLabel: '前往构建',
      onAction: original
    }
    const view = render(<PrerequisiteNotice {...props} />)
    const changes: Partial<ComponentProps<typeof PrerequisiteNotice>>[] = [
      { title: '条件已更新' },
      { description: '重新检查构建' },
      { actionLabel: '重新检查' },
      { tone: 'ready' },
      { embedded: true },
      { className: 'custom-notice' },
      { actionLoading: true }
    ]
    for (const patch of changes) {
      props = { ...props, ...patch }
      view.rerender(<PrerequisiteNotice {...props} />)
      expect(screen.getByRole('status')).toHaveAttribute('data-tone', props.tone ?? 'blocked')
      expect(screen.getByText(props.title)).toBeInTheDocument()
      expect(screen.getByText(props.description!)).toBeInTheDocument()
      expect(screen.getByText(props.actionLabel!).closest('button')).toBeInTheDocument()
    }
    expect(screen.getByRole('status')).toHaveClass('custom-notice')
    fireEvent.click(screen.getByText('重新检查').closest('button')!)
    expect(original).not.toHaveBeenCalled()
    const next = vi.fn()
    view.rerender(<PrerequisiteNotice {...props} actionLoading={false} onAction={next} />)
    fireEvent.click(screen.getByText('重新检查').closest('button')!)
    expect(next).toHaveBeenCalledOnce()
    expect(original).not.toHaveBeenCalled()
    view.rerender(<PrerequisiteNotice {...props} actionLabel={undefined} />)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    view.rerender(<PrerequisiteNotice {...props} onAction={undefined} description={undefined} />)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByText('重新检查构建')).not.toBeInTheDocument()
  })
})
