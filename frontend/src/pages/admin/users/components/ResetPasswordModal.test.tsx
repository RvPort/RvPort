import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { renderWithQueryClient } from '@/test/test-utils'
import type { User } from '@/types'

import ResetPasswordModal from './ResetPasswordModal'

const user: User = {
  id: 'user-1',
  username: 'developer',
  displayName: 'Developer',
  email: 'developer@example.com',
  isSuperAdmin: false,
  mustChangePassword: false,
  status: 'ACTIVE',
  lastLoginAt: null,
  createdAt: '2026-07-01T09:00:00+08:00',
  updatedAt: null
}

describe('reset password modal', () => {
  it('clears a password draft when the target user changes', async () => {
    const props = { loading: false, visible: true, onCancel: vi.fn(), onSubmit: vi.fn() }
    const { rerender } = renderWithQueryClient(<ResetPasswordModal {...props} user={user} />)
    await userEvent.type(screen.getByLabelText('新密码'), 'NewPassword1')
    rerender(<ResetPasswordModal {...props} user={{ ...user, id: 'user-2', username: 'other' }} />)
    expect(screen.getByLabelText('新密码')).toHaveValue('')
  })

  it('submits a temporary password after confirmation', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    const userEventInstance = userEvent.setup()

    renderWithQueryClient(
      <ResetPasswordModal
        loading={false}
        user={user}
        visible
        onCancel={vi.fn()}
        onSubmit={onSubmit}
      />
    )

    await userEventInstance.type(screen.getByLabelText('新密码'), 'NewPassword1')
    await userEventInstance.type(screen.getByLabelText('确认新密码'), 'NewPassword1')
    await userEventInstance.click(screen.getByRole('button', { name: '重置密码' }))
    await userEventInstance.click(screen.getByRole('button', { name: '确认重置' }))

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledWith({
        newPassword: 'NewPassword1'
      })
    })
  })
})

it('validates account equality, whitespace and confirmation before calling the current submission handler', async () => {
  const actor = userEvent.setup()
  let onSubmit = vi.fn(async () => {})
  let onCancel = vi.fn()
  let loading = false
  const target = { ...user, username: 'AccountName1' }
  const draw = () => (
    <ResetPasswordModal
      visible
      loading={loading}
      user={target}
      onSubmit={onSubmit}
      onCancel={onCancel}
    />
  )
  const view = renderWithQueryClient(draw())
  await actor.type(screen.getByLabelText('新密码'), 'aCCOUNTnAME1')
  await actor.tab()
  expect(await screen.findByText('新密码不能与账号相同')).toBeInTheDocument()
  await actor.clear(screen.getByLabelText('新密码'))
  await actor.type(screen.getByLabelText('新密码'), '   ')
  await actor.tab()
  expect(await screen.findByText('新密码不能只包含空白字符')).toBeInTheDocument()
  await actor.clear(screen.getByLabelText('新密码'))
  await actor.type(screen.getByLabelText('新密码'), 'ValidPassword1')
  await actor.type(screen.getByLabelText('确认新密码'), 'Different1')
  await actor.tab()
  expect(await screen.findByText('两次输入的新密码不一致')).toBeInTheDocument()
  expect(onSubmit).not.toHaveBeenCalled()
  await actor.clear(screen.getByLabelText('确认新密码'))
  await actor.type(screen.getByLabelText('确认新密码'), 'ValidPassword1')
  loading = true
  view.rerender(draw())
  expect(screen.getByLabelText('新密码')).toBeDisabled()
  expect(screen.getByRole('button', { name: /取\s*消/ })).toBeDisabled()
  loading = false
  const old = onSubmit
  let resolve!: () => void
  onSubmit = vi.fn(
    () =>
      new Promise<void>((done) => {
        resolve = done
      })
  )
  view.rerender(draw())
  await waitFor(() => expect(screen.getByRole('button', { name: /重置密码/ })).toBeEnabled())
  await actor.click(screen.getByRole('button', { name: /重置密码/ }))
  await actor.click(screen.getByRole('button', { name: '确认重置' }))
  expect(onSubmit).toHaveBeenCalledWith({ newPassword: 'ValidPassword1' })
  expect(old).not.toHaveBeenCalled()
  await waitFor(() => expect(screen.getByLabelText('新密码')).toBeDisabled())
  await import('@testing-library/react').then(({ act }) => act(async () => resolve()))
  const oldCancel = onCancel
  onCancel = vi.fn()
  view.rerender(draw())
  await actor.click(within(screen.getByRole('dialog')).getByRole('button', { name: /取\s*消/ }))
  expect(onCancel).toHaveBeenCalledOnce()
  expect(oldCancel).not.toHaveBeenCalled()
})
