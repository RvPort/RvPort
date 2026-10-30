import { MemoryRouter, Route, Routes } from 'react-router'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { resetAuthStoreForTests, useAuthStore } from '@/store/authStore'
import type { User } from '@/types'

const { logoutApi, feedbackError, feedbackSuccess } = vi.hoisted(() => ({
  logoutApi: vi.fn(),
  feedbackError: vi.fn(),
  feedbackSuccess: vi.fn()
}))
vi.mock('@/api/auth', () => ({ logout: logoutApi }))
vi.mock('@/utils/feedback', () => ({
  feedback: { error: feedbackError, success: feedbackSuccess }
}))

import MainLayout from './index'

const user: User = {
  id: 'user-1',
  username: 'developer',
  displayName: '开发人员',
  email: 'developer@example.com',
  isSuperAdmin: false,
  mustChangePassword: false,
  status: 'ACTIVE',
  lastLoginAt: null,
  createdAt: '2026-10-27T08:00:00Z',
  updatedAt: null
}

function showLayout() {
  return render(
    <MemoryRouter initialEntries={['/home']}>
      <Routes>
        <Route element={<MainLayout />}>
          <Route path="/home" element={<h2>项目列表</h2>} />
          <Route path="/settings/profile" element={<h2>个人资料页</h2>} />
          <Route path="/admin/users" element={<h2>管理页</h2>} />
          <Route path="/login" element={<h2>登录页</h2>} />
        </Route>
      </Routes>
    </MemoryRouter>
  )
}

afterEach(() => resetAuthStoreForTests())

describe('main layout account menu', () => {
  it('shows profile navigation to regular users without an admin entry', async () => {
    useAuthStore.getState().setUser(user)
    const actor = userEvent.setup()
    showLayout()
    await actor.click(screen.getByRole('button', { name: '账户菜单' }))
    expect(screen.queryByText('管理中心')).not.toBeInTheDocument()
    await actor.click(screen.getByText('个人中心'))
    expect(await screen.findByText('个人资料页')).toBeInTheDocument()
  })

  it('shows the admin destination only to a super administrator', async () => {
    useAuthStore.getState().setUser({ ...user, isSuperAdmin: true })
    const actor = userEvent.setup()
    showLayout()
    await actor.click(screen.getByRole('button', { name: '账户菜单' }))
    await actor.click(screen.getByText('管理中心'))
    expect(await screen.findByText('管理页')).toBeInTheDocument()
  })

  it('keeps the session after rejected logout and clears it after success', async () => {
    useAuthStore.getState().setUser(user)
    logoutApi.mockResolvedValueOnce({ loggedOut: false }).mockResolvedValueOnce({ loggedOut: true })
    const actor = userEvent.setup()
    showLayout()
    await actor.click(screen.getByRole('button', { name: '账户菜单' }))
    await actor.click(screen.getByText('退出登录'))
    await waitFor(() => expect(feedbackError).toHaveBeenCalledWith('退出登录失败，请重试'))
    expect(useAuthStore.getState().user?.id).toBe(user.id)
    await actor.click(screen.getByRole('button', { name: '账户菜单' }))
    await actor.click(screen.getByText('退出登录'))
    await waitFor(() => expect(useAuthStore.getState().user).toBeNull())
    expect(feedbackSuccess).toHaveBeenCalledWith('已退出登录')
    expect(await screen.findByText('登录页')).toBeInTheDocument()
  })
})
