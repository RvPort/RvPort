import { fireEvent, render, screen } from '@testing-library/react'
import type { ComponentProps, ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import ManualRepair from '.'

vi.mock('../components/StageSurface', () => ({
  default: ({
    visible,
    title,
    children,
    onClose
  }: {
    visible: boolean
    title: ReactNode
    children: ReactNode
    onClose: () => void
  }) =>
    visible ? (
      <section>
        <h2>{title}</h2>
        {children}
        <button onClick={onClose}>关闭修复入口</button>
      </section>
    ) : null
}))

describe('manual repair navigation updates', () => {
  it('rebinds all repair paths and close actions and remains safe without a navigation handler', () => {
    let props: ComponentProps<typeof ManualRepair> = {
      visible: true,
      onClose: vi.fn(),
      onStageChange: vi.fn()
    }
    const view = render(<ManualRepair {...props} />)
    expect(screen.getByRole('heading', { name: '人工修复' })).toBeInTheDocument()
    props = {
      ...props,
      stage: { id: 'manual', title: '项目修复入口', subtitle: '', order: 7, status: 'pending' }
    }
    view.rerender(<ManualRepair {...props} />)
    expect(screen.getByRole('heading', { name: '项目修复入口' })).toBeInTheDocument()
    const navigate = vi.fn()
    props = { ...props, onStageChange: navigate }
    view.rerender(<ManualRepair {...props} />)
    for (const label of ['查看依赖分析', '前往源码管理', '前往构建验证'])
      fireEvent.click(screen.getByRole('button', { name: label }))
    expect(navigate.mock.calls).toEqual([['rv-dependency-analysis'], ['assets'], ['build']])
    props = { ...props, onStageChange: undefined }
    view.rerender(<ManualRepair {...props} />)
    for (const label of ['查看依赖分析', '前往源码管理', '前往构建验证'])
      expect(() => fireEvent.click(screen.getByRole('button', { name: label }))).not.toThrow()
    const close = vi.fn()
    props = { ...props, onClose: close }
    view.rerender(<ManualRepair {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '关闭修复入口' }))
    expect(close).toHaveBeenCalledOnce()
    props = { ...props, visible: false }
    view.rerender(<ManualRepair {...props} />)
    expect(screen.queryByText('选择处理路径')).not.toBeInTheDocument()
  })
})
