import { act, fireEvent, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { renderWithQueryClient } from '@/test/test-utils'

import TestMailModal from '.'

describe('test mail modal', () => {
  it('uses the native email input type and autocomplete hint', () => {
    renderWithQueryClient(
      <TestMailModal loading={false} visible onCancel={vi.fn()} onSubmit={vi.fn()} />
    )

    expect(screen.getByLabelText('收件人邮箱')).toHaveAttribute('type', 'email')
    expect(screen.getByLabelText('收件人邮箱')).toHaveAttribute('autocomplete', 'email')
  })

  it('does not send a queued form submission if sending begins during validation, and preserves the recipient for retry', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    const props = { visible: true, onCancel: vi.fn(), onSubmit }
    const { rerender } = renderWithQueryClient(<TestMailModal {...props} loading={false} />)
    const actor = userEvent.setup()
    await actor.type(screen.getByLabelText('收件人邮箱'), 'recipient@example.com')
    // Native submission starts validation before the parent marks the request pending.
    fireEvent.submit(screen.getByLabelText('收件人邮箱').closest('form')!)
    rerender(<TestMailModal {...props} loading />)
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByLabelText('收件人邮箱')).toHaveValue('recipient@example.com')
    rerender(<TestMailModal {...props} loading={false} />)
    await actor.click(screen.getByRole('button', { name: /发送测试邮件/ }))
    expect(onSubmit).toHaveBeenCalledExactlyOnceWith({ to: 'recipient@example.com' })
  })
})
