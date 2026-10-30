import { StrictMode } from 'react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import BaseModal from '.'

describe('BaseModal', () => {
  it('keeps editable content mounted while the parent changes the title and sizing in StrictMode', async () => {
    const { default: userEvent } = await import('@testing-library/user-event')
    const content = <input aria-label="未提交草稿" />
    const draw = (title: string, width: number) => (
      <StrictMode>
        <BaseModal visible title={title} width={width}>
          {content}
        </BaseModal>
      </StrictMode>
    )
    const view = render(draw('编辑信息', 640))
    const input = screen.getByRole('textbox', { name: '未提交草稿' })
    await userEvent.type(input, '保留输入')
    view.rerender(draw('编辑信息（更新）', 760))
    expect(screen.getByRole('dialog')).toHaveAccessibleName('编辑信息（更新）')
    expect(screen.getByRole('textbox', { name: '未提交草稿' })).toBe(input)
    expect(input).toHaveValue('保留输入')
  })
  it('mounts form content while closed so form instances can connect', () => {
    render(
      <BaseModal forceRender visible={false}>
        <form aria-label="预挂载表单" />
      </BaseModal>
    )

    expect(screen.getByRole('form', { hidden: true, name: '预挂载表单' })).toBeInTheDocument()
  })
})

describe('busy close boundaries', () => {
  it('blocks close, keyboard, mask and default buttons while busy and restores them afterward', async () => {
    const { fireEvent } = await import('@testing-library/react')
    const { default: userEvent } = await import('@testing-library/user-event')
    const { vi } = await import('vitest')
    const onCancel = vi.fn(),
      onOk = vi.fn()
    const user = userEvent.setup()
    const { rerender } = render(
      <BaseModal busy visible title="提交事务" onCancel={onCancel} onOk={onOk}>
        <input aria-label="普通内容" />
      </BaseModal>
    )
    expect(screen.queryByRole('button', { name: 'Close' })).not.toBeInTheDocument()
    await user.keyboard('{Escape}')
    const wrap = document.querySelector('.ant-modal-wrap')!
    fireEvent.mouseDown(wrap)
    fireEvent.mouseUp(wrap)
    fireEvent.click(wrap)
    expect(onCancel).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()
    expect(screen.getByRole('button', { name: /O\s*K/ })).toBeDisabled()
    rerender(
      <BaseModal visible title="提交事务" onCancel={onCancel} onOk={onOk}>
        <input />
      </BaseModal>
    )
    await user.click(screen.getByRole('button', { name: 'Close' }))
    expect(onCancel).toHaveBeenCalledOnce()
    await user.click(screen.getByRole('button', { name: /O\s*K/ }))
    expect(onOk).toHaveBeenCalledOnce()
  })
})
