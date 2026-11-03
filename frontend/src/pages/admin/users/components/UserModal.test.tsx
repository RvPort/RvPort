import { StrictMode } from 'react'
import { act, fireEvent, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { renderWithQueryClient } from '@/test/test-utils'
import type { User } from '@/types'

import UserModal from './UserModal'

describe('user modal', () => {
  it('initializes ACTIVE when a new-user modal opens after its initial closed render in StrictMode', async () => {
    const props = { loading: false, user: null, onCancel: vi.fn(), onSubmit: vi.fn(async () => {}) }
    const view = renderWithQueryClient(
      <StrictMode>
        <UserModal {...props} visible={false} />
      </StrictMode>
    )
    view.rerender(
      <StrictMode>
        <UserModal {...props} visible />
      </StrictMode>
    )
    await act(async () => {
      await Promise.resolve()
    })
    expect(screen.getByRole('radio', { name: '启用' })).toBeChecked()
    const actor = userEvent.setup()
    await actor.type(screen.getByLabelText('账号'), 'new-account')
    await actor.type(screen.getByLabelText('邮箱'), 'new@example.com')
    await actor.click(screen.getByRole('button', { name: /保\s*存/ }))
    expect(props.onSubmit).toHaveBeenCalledExactlyOnceWith({
      username: 'new-account',
      email: 'new@example.com',
      status: 'ACTIVE',
      displayName: undefined
    })
  })

  it('populates the immutable username when an edit modal opens after its initial closed render in StrictMode', async () => {
    const user: User = {
      id: 'user-1',
      username: 'developer',
      displayName: '开发人员',
      email: 'developer@example.com',
      isSuperAdmin: false,
      mustChangePassword: false,
      status: 'ACTIVE',
      lastLoginAt: null,
      createdAt: '2026-08-01T00:00:00Z',
      updatedAt: null
    }
    const props = {
      loading: false,
      user: null as User | null,
      onCancel: vi.fn(),
      onSubmit: vi.fn()
    }
    const view = renderWithQueryClient(
      <StrictMode>
        <UserModal {...props} visible={false} />
      </StrictMode>
    )
    view.rerender(
      <StrictMode>
        <UserModal {...props} user={user} visible />
      </StrictMode>
    )
    await act(async () => {
      await Promise.resolve()
    })
    expect(screen.getByLabelText('账号')).toHaveValue('developer')
    expect(screen.getByLabelText('账号')).toBeDisabled()
    await userEvent.setup().click(screen.getByRole('button', { name: /保\s*存/ }))
    expect(props.onSubmit).toHaveBeenCalledExactlyOnceWith({
      username: 'developer',
      email: 'developer@example.com',
      displayName: '开发人员',
      status: 'ACTIVE'
    })
  })
  it('preserves an unsaved account draft across parent updates under the application StrictMode lifecycle and uses the latest submit callback', async () => {
    const firstSubmit = vi.fn(async () => {}),
      latestSubmit = vi.fn(async () => {}),
      cancel = vi.fn()
    const draw = (onSubmit: typeof firstSubmit) => (
      <StrictMode>
        <UserModal loading={false} user={null} visible onCancel={cancel} onSubmit={onSubmit} />
      </StrictMode>
    )
    const view = renderWithQueryClient(draw(firstSubmit))
    const actor = userEvent.setup()
    await actor.type(screen.getByLabelText('账号'), 'draft-account')
    await actor.type(screen.getByLabelText('邮箱'), 'draft@example.com')
    view.rerender(draw(latestSubmit))
    expect(screen.getByLabelText('账号')).toHaveValue('draft-account')
    expect(screen.getByLabelText('邮箱')).toHaveValue('draft@example.com')
    await actor.click(screen.getByRole('button', { name: /保\s*存/ }))
    expect(latestSubmit).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ username: 'draft-account', email: 'draft@example.com' })
    )
    expect(firstSubmit).not.toHaveBeenCalled()
  })
  it('exposes native account and email input semantics', () => {
    renderWithQueryClient(
      <UserModal loading={false} user={null} visible onCancel={vi.fn()} onSubmit={vi.fn()} />
    )

    expect(screen.getByLabelText('账号')).toHaveAttribute('autocomplete', 'username')
    expect(screen.getByLabelText('邮箱')).toHaveAttribute('type', 'email')
    expect(screen.getByLabelText('邮箱')).toHaveAttribute('autocomplete', 'email')
  })

  it('does not save a queued submission once loading starts, and retains the draft for retry', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    const props = { user: null, visible: true, onCancel: vi.fn(), onSubmit }
    const { rerender } = renderWithQueryClient(<UserModal {...props} loading={false} />)
    const actor = userEvent.setup()
    await actor.type(screen.getByLabelText('账号'), 'new-user')
    await actor.type(screen.getByLabelText('邮箱'), 'user@example.com')
    fireEvent.submit(screen.getByLabelText('账号').closest('form')!)
    rerender(<UserModal {...props} loading />)
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByLabelText('账号')).toHaveValue('new-user')
    rerender(<UserModal {...props} loading={false} />)
    await actor.click(screen.getByRole('button', { name: /保\s*存/ }))
    expect(onSubmit).toHaveBeenCalledExactlyOnceWith({
      username: 'new-user',
      email: 'user@example.com',
      status: 'ACTIVE'
    })
  })
})
