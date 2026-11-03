import { MemoryRouter, useLocation } from 'react-router'
import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetAuthStoreForTests, useAuthStore } from '@/store/authStore'
import { renderWithStrictQueryClient as renderWithQueryClient } from '@/test/strict-mode-utils'
import type { User } from '@/types'

const { changePassword, logout, updateProfile } = vi.hoisted(() => ({
  changePassword: vi.fn(),
  logout: vi.fn(),
  updateProfile: vi.fn()
}))

vi.mock('@/api/auth', () => ({ changePassword, logout, updateProfile }))
vi.mock('@/utils/feedback', () => ({ feedback: { success: vi.fn() } }))

import ProfilePage from '.'

const user: User = {
  id: 'user-1',
  username: 'admin',
  displayName: 'Admin',
  email: 'admin@example.com',
  isSuperAdmin: true,
  mustChangePassword: false,
  status: 'ACTIVE',
  lastLoginAt: '2026-08-10T09:00:00+08:00',
  createdAt: '2026-07-01T09:00:00+08:00',
  updatedAt: null
}

function renderProfilePage() {
  function LocationDisplay() {
    const location = useLocation()
    return <output data-testid="location">{location.pathname}</output>
  }

  return renderWithQueryClient(
    <MemoryRouter>
      <ProfilePage />
      <LocationDisplay />
    </MemoryRouter>
  )
}

describe('profile page', () => {
  it('refreshes independent account facts and resets a cancelled nickname draft from the current account', async () => {
    let current = user
    renderProfilePage()
    const patches: Array<[Partial<User>, string]> = [
      [{ username: 'renamed-account' }, 'renamed-account'],
      [{ email: 'next@example.com' }, 'next@example.com'],
      [{ displayName: '新昵称' }, '新昵称'],
      [{ status: 'DISABLED' }, '已禁用'],
      [{ lastLoginAt: '2026-10-28T00:01:00Z' }, '2026-10-28 08:01'],
      [{ createdAt: '2026-10-28T00:02:00Z' }, '2026-10-28 08:02']
    ]
    for (const [patch, expected] of patches) {
      current = { ...current, ...patch }
      act(() => useAuthStore.getState().setUser(current))
      expect(screen.getByText(expected)).toBeInTheDocument()
    }
    await userEvent.click(screen.getByRole('button', { name: '编辑' }))
    await userEvent.clear(screen.getByLabelText('昵称'))
    await userEvent.type(screen.getByLabelText('昵称'), '未保存草稿')
    await userEvent.click(screen.getByRole('button', { name: /取\s*消/ }))
    expect(screen.getByText('新昵称')).toBeInTheDocument()
    expect(updateProfile).not.toHaveBeenCalled()
    act(() => useAuthStore.getState().setUser({ ...current, displayName: null, email: null }))
    await userEvent.click(screen.getByRole('button', { name: '编辑' }))
    expect(screen.getByLabelText('昵称')).toHaveValue('')
    await userEvent.click(screen.getByRole('button', { name: /取\s*消/ }))
    expect(screen.getAllByText('--')).toHaveLength(2)
    act(() => useAuthStore.getState().setUser(null))
    expect(screen.queryByText('个人中心')).not.toBeInTheDocument()
  })
  beforeEach(() => {
    resetAuthStoreForTests()
    useAuthStore.getState().setUser(user)
  })

  afterEach(() => {
    resetAuthStoreForTests()
  })

  it('updates only the display name and synchronizes the authenticated user', async () => {
    updateProfile.mockResolvedValue({ ...user, displayName: '迁移管理员' })
    const userEventInstance = userEvent.setup()

    renderProfilePage()

    expect(screen.getByText('admin@example.com')).toBeInTheDocument()
    await userEventInstance.click(screen.getByRole('button', { name: '编辑' }))
    const input = screen.getByLabelText('昵称')
    await userEventInstance.clear(input)
    await userEventInstance.type(input, '迁移管理员')
    await userEventInstance.click(screen.getByRole('button', { name: /保\s*存/ }))

    await waitFor(() => {
      expect(updateProfile).toHaveBeenCalled()
      expect(updateProfile.mock.calls[0]?.[0]).toEqual({ displayName: '迁移管理员' })
    })
    expect(useAuthStore.getState().user?.displayName).toBe('迁移管理员')
  })

  it('changes the password from the profile modal', async () => {
    changePassword.mockResolvedValue(user)
    logout.mockResolvedValue({ loggedOut: true })
    const userEventInstance = userEvent.setup()

    renderProfilePage()

    await userEventInstance.click(screen.getByRole('button', { name: '修改密码' }))
    await userEventInstance.type(screen.getByLabelText('当前密码'), 'OldPassword1')
    await userEventInstance.type(screen.getByLabelText('新密码'), 'NewPassword1')
    await userEventInstance.type(screen.getByLabelText('确认新密码'), 'NewPassword1')
    await userEventInstance.click(screen.getByRole('button', { name: '确认修改' }))

    await waitFor(() => {
      expect(changePassword).toHaveBeenCalled()
      expect(changePassword.mock.calls[0]?.[0]).toEqual({
        currentPassword: 'OldPassword1',
        newPassword: 'NewPassword1'
      })
      expect(logout).toHaveBeenCalledOnce()
    })
    expect(useAuthStore.getState().user).toBeNull()
    expect(screen.getByTestId('location')).toHaveTextContent('/login')
  })

  it('returns to the project list', async () => {
    const userEventInstance = userEvent.setup()

    renderProfilePage()

    await userEventInstance.click(screen.getByRole('button', { name: '返回首页' }))

    expect(screen.getByTestId('location')).toHaveTextContent('/home')
  })
})
