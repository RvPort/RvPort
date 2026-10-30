import { Fragment, type ReactNode } from 'react'
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { renderWithStrictQueryClient as renderWithQueryClient } from '@/test/strict-mode-utils'
import type { User } from '@/types'

const {
  createAdminUser,
  getAdminUsers,
  resetAdminUserPassword,
  sendAdminUserInvitation,
  updateAdminUser
} = vi.hoisted(() => ({
  createAdminUser: vi.fn(),
  getAdminUsers: vi.fn(),
  resetAdminUserPassword: vi.fn(),
  sendAdminUserInvitation: vi.fn(),
  updateAdminUser: vi.fn()
}))

vi.mock('@/api/admin/users', () => ({
  createAdminUser,
  getAdminUsers,
  resetAdminUserPassword,
  sendAdminUserInvitation,
  updateAdminUser
}))

vi.mock('@/components/BaseTable', () => ({
  default: ({
    columns,
    toolbar,
    request
  }: {
    columns: Array<{
      key?: string
      dataIndex?: keyof User
      render?: (value: unknown, record: User) => ReactNode
    }>
    toolbar: { actions: ReactNode }
    request: (params: {
      keyword: string
      page: number
      pageSize: number
      status: string
    }) => unknown
  }) => {
    return (
      <Fragment>
        {toolbar.actions}
        <button
          onClick={() =>
            void request({ keyword: 'developer', page: 2, pageSize: 10, status: 'DISABLED' })
          }
        >
          查询账户
        </button>
        {columns.map((column, index) => (
          <div key={column.key ?? column.dataIndex ?? index}>
            {column.render
              ? column.render(
                  column.dataIndex ? currentUser[column.dataIndex] : undefined,
                  currentUser
                )
              : String(column.dataIndex ? (currentUser[column.dataIndex] ?? '') : '')}
          </div>
        ))}
      </Fragment>
    )
  }
}))

import AdminUsersPage from '.'

const user: User = {
  id: 'user-1',
  username: 'developer',
  displayName: '开发人员',
  email: 'developer@example.com',
  isSuperAdmin: false,
  mustChangePassword: false,
  status: 'ACTIVE',
  lastLoginAt: null,
  createdAt: '2026-08-01T09:00:00+08:00',
  updatedAt: null
}
let currentUser = user

describe('admin user page', () => {
  it('shows nullable account facts and forwards the requested filters without losing row identity', () => {
    currentUser = {
      ...user,
      displayName: null,
      email: null,
      mustChangePassword: true,
      status: 'DISABLED'
    }
    renderWithQueryClient(<AdminUsersPage />)
    expect(screen.getAllByText('--')).toHaveLength(3)
    expect(screen.getByText('是')).toBeInTheDocument()
    expect(screen.getByText('禁用')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '查询账户' }))
    expect(getAdminUsers).toHaveBeenCalledWith({
      keyword: 'developer',
      page: 2,
      pageSize: 10,
      status: 'DISABLED'
    })
  })

  it.each(['status', 'invitation'] as const)(
    'blocks a second account action while the first %s request is pending and restores it afterward',
    async (kind) => {
      let finish!: (value: User) => void
      const request = kind === 'status' ? updateAdminUser : sendAdminUserInvitation
      request.mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve
          })
      )
      const view = renderWithQueryClient(<AdminUsersPage />)
      const action = kind === 'status' ? '禁用用户' : '重发邀请'
      await userEvent.click(screen.getByRole('button', { name: action }))
      await userEvent.click(
        screen.getByRole('button', { name: kind === 'status' ? 'OK' : '确认重发' })
      )
      await waitFor(() => expect(request).toHaveBeenCalledOnce())
      currentUser = { ...user, id: 'user-2', username: 'second-account' }
      view.rerender(<AdminUsersPage />)
      expect(screen.getByRole('button', { name: action })).toBeDisabled()
      await act(async () => finish(user))
      await waitFor(() => expect(screen.getByRole('button', { name: action })).toBeEnabled())
      expect(request.mock.calls[0][0]).toBe('user-1')
    }
  )
  beforeEach(() => {
    currentUser = user
  })

  it('requires confirmation before resending an invitation', async () => {
    sendAdminUserInvitation.mockResolvedValue(user)
    const userEventInstance = userEvent.setup()

    renderWithQueryClient(<AdminUsersPage />)

    await userEventInstance.click(screen.getByRole('button', { name: '重发邀请' }))

    expect(sendAdminUserInvitation).not.toHaveBeenCalled()
    await userEventInstance.click(screen.getByRole('button', { name: '确认重发' }))

    await waitFor(() => {
      expect(sendAdminUserInvitation.mock.calls[0]?.[0]).toBe('user-1')
    })
  })

  it('creates a user from the table action', async () => {
    createAdminUser.mockResolvedValue(user)
    const actor = userEvent.setup()
    renderWithQueryClient(<AdminUsersPage />)

    await actor.click(screen.getByRole('button', { name: /新增用户/ }))
    const dialog = screen.getByRole('dialog')
    await actor.type(within(dialog).getByLabelText('账号'), 'new-user')
    await actor.type(within(dialog).getByLabelText('邮箱'), 'new@example.com')
    await actor.type(within(dialog).getByLabelText('昵称'), '新用户')
    await actor.click(within(dialog).getByRole('button', { name: /保\s*存/ }))

    await waitFor(() => expect(createAdminUser).toHaveBeenCalledOnce())
    expect(createAdminUser.mock.calls[0][0]).toEqual({
      username: 'new-user',
      email: 'new@example.com',
      displayName: '新用户',
      status: 'ACTIVE'
    })
  })

  it('enables a disabled account only after confirmation', async () => {
    currentUser = { ...user, status: 'DISABLED' }
    updateAdminUser.mockResolvedValue(currentUser)
    const actor = userEvent.setup()
    renderWithQueryClient(<AdminUsersPage />)

    await actor.click(screen.getByRole('button', { name: '启用用户' }))
    expect(updateAdminUser).not.toHaveBeenCalled()
    await actor.click(screen.getByRole('button', { name: 'OK' }))
    await waitFor(() => expect(updateAdminUser).toHaveBeenCalledOnce())
    expect(updateAdminUser.mock.calls[0].slice(0, 2)).toEqual(['user-1', { status: 'ACTIVE' }])
  })

  it('edits account details and clears an empty display name', async () => {
    updateAdminUser.mockResolvedValue(user)
    const actor = userEvent.setup()
    renderWithQueryClient(<AdminUsersPage />)

    await actor.click(screen.getByRole('button', { name: '编辑用户' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByLabelText('账号')).toBeDisabled()
    await actor.clear(within(dialog).getByLabelText('昵称'))
    await actor.clear(within(dialog).getByLabelText('邮箱'))
    await actor.type(within(dialog).getByLabelText('邮箱'), 'new@example.com')
    await actor.click(within(dialog).getByRole('button', { name: /保\s*存/ }))

    await waitFor(() => expect(updateAdminUser).toHaveBeenCalledOnce())
    expect(updateAdminUser.mock.calls[0].slice(0, 2)).toEqual([
      'user-1',
      { displayName: null, email: 'new@example.com', status: 'ACTIVE' }
    ])
  })

  it('resets a password only after the second confirmation', async () => {
    resetAdminUserPassword.mockResolvedValue(user)
    const actor = userEvent.setup()
    renderWithQueryClient(<AdminUsersPage />)

    await actor.click(screen.getByRole('button', { name: '重置密码' }))
    const dialog = screen.getByRole('dialog')
    await actor.type(within(dialog).getByLabelText('新密码'), 'NewPassword1')
    await actor.type(within(dialog).getByLabelText('确认新密码'), 'NewPassword1')
    await actor.click(within(dialog).getByRole('button', { name: /重\s*置\s*密\s*码/ }))
    expect(resetAdminUserPassword).not.toHaveBeenCalled()
    await actor.click(screen.getByRole('button', { name: '确认重置' }))

    await waitFor(() => expect(resetAdminUserPassword).toHaveBeenCalledOnce())
    expect(resetAdminUserPassword.mock.calls[0].slice(0, 2)).toEqual([
      'user-1',
      { mustChangePassword: true, newPassword: 'NewPassword1' }
    ])
  })
})
