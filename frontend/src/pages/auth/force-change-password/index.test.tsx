import { MemoryRouter } from 'react-router'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/store/authStore'
import { renderWithQueryClient } from '@/test/test-utils'

const { changePassword, logout } = vi.hoisted(() => ({ changePassword: vi.fn(), logout: vi.fn() }))

vi.mock('@/api/auth', () => ({ changePassword, logout }))
vi.mock('@/utils/feedback', () => ({ feedback: { success: vi.fn(), warning: vi.fn() } }))

import ForceChangePasswordPage from '.'

describe('force change password page', () => {
  it('submits the current and replacement passwords', async () => {
    changePassword.mockResolvedValue({})
    logout.mockResolvedValue({ loggedOut: true })
    useAuthStore.setState({
      user: {
        id: 'user-1',
        username: 'developer',
        displayName: 'Developer',
        email: 'developer@example.com',
        isSuperAdmin: false,
        mustChangePassword: true,
        status: 'ACTIVE',
        lastLoginAt: null,
        createdAt: '2026-07-01T09:00:00+08:00',
        updatedAt: null
      },
      logout: vi.fn()
    })
    const user = userEvent.setup()

    renderWithQueryClient(
      <MemoryRouter>
        <ForceChangePasswordPage />
      </MemoryRouter>
    )

    await user.type(screen.getByLabelText('当前密码'), 'Temporary1')
    await user.type(screen.getByLabelText('新密码'), 'NewPassword1')
    await user.type(screen.getByLabelText('确认新密码'), 'NewPassword1')
    await user.click(screen.getByRole('button', { name: '确认修改' }))

    await waitFor(() => {
      expect(changePassword).toHaveBeenCalledWith({
        currentPassword: 'Temporary1',
        newPassword: 'NewPassword1'
      })
    })
  })
})

it('blocks whitespace, reused and account-equivalent passwords before any request', async () => {
  useAuthStore.setState({
    user: {
      id: 'user',
      username: 'AccountName1',
      displayName: null,
      email: null,
      isSuperAdmin: false,
      mustChangePassword: true,
      status: 'ACTIVE',
      lastLoginAt: null,
      createdAt: '',
      updatedAt: null
    }
  })
  const actor = userEvent.setup()
  renderWithQueryClient(
    <MemoryRouter>
      <ForceChangePasswordPage />
    </MemoryRouter>
  )
  await actor.type(screen.getByLabelText('当前密码'), '   ')
  await actor.tab()
  expect(await screen.findByText('当前密码不能只包含空白字符')).toBeInTheDocument()
  await actor.clear(screen.getByLabelText('当前密码'))
  await actor.type(screen.getByLabelText('当前密码'), 'CurrentPass1')
  await actor.type(screen.getByLabelText('新密码'), '   ')
  await actor.tab()
  expect(await screen.findByText('新密码不能只包含空白字符')).toBeInTheDocument()
  await actor.clear(screen.getByLabelText('新密码'))
  await actor.type(screen.getByLabelText('新密码'), 'CurrentPass1')
  await actor.tab()
  expect(await screen.findByText('新密码不能与当前密码相同')).toBeInTheDocument()
  await actor.clear(screen.getByLabelText('新密码'))
  await actor.type(screen.getByLabelText('新密码'), 'aCCOUNTnAME1')
  await actor.tab()
  expect(await screen.findByText('新密码不能与账号相同')).toBeInTheDocument()
  expect(changePassword).not.toHaveBeenCalled()
})
