import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import ChangePasswordModal from './ChangePasswordModal'

const NoMotion = ({ children }: { children: ReactNode }) => (
  <ConfigProvider theme={{ token: { motion: false } }}>{children}</ConfigProvider>
)
const fill = async (label: string, value: string) => {
  await userEvent.clear(screen.getByLabelText(label))
  await userEvent.type(screen.getByLabelText(label), value)
}

describe('password change form boundaries', () => {
  it('validates current, account and confirmation constraints before using the latest submit callback', async () => {
    const submit = vi.fn(async () => {})
    const props = {
      visible: true,
      username: 'account',
      loading: false,
      onCancel: vi.fn(),
      onSubmit: submit
    }
    const view = render(<ChangePasswordModal {...props} />, { wrapper: NoMotion })
    await userEvent.click(screen.getByRole('button', { name: '确认修改' }))
    expect(await screen.findByText('请输入当前密码')).toBeInTheDocument()
    expect(submit).not.toHaveBeenCalled()
    await fill('当前密码', 'OldPassword1')
    await fill('新密码', 'OldPassword1')
    await fill('确认新密码', 'OldPassword1')
    await userEvent.click(screen.getByRole('button', { name: '确认修改' }))
    expect(await screen.findByText('新密码不能与当前密码相同')).toBeInTheDocument()
    view.rerender(<ChangePasswordModal {...props} username="newpassword1" />)
    await fill('新密码', 'NewPassword1')
    await fill('确认新密码', 'Different123')
    await userEvent.click(screen.getByRole('button', { name: '确认修改' }))
    expect(await screen.findByText('新密码不能与账号相同')).toBeInTheDocument()
    expect(screen.getByText('两次输入的新密码不一致')).toBeInTheDocument()
    await fill('新密码', 'StrongPassword2')
    await fill('确认新密码', 'StrongPassword2')
    const next = vi.fn(async () => {})
    view.rerender(<ChangePasswordModal {...props} onSubmit={next} />)
    await userEvent.click(screen.getByRole('button', { name: '确认修改' }))
    await waitFor(() =>
      expect(next).toHaveBeenCalledWith({
        currentPassword: 'OldPassword1',
        newPassword: 'StrongPassword2',
        confirmPassword: 'StrongPassword2'
      })
    )
    expect(submit).not.toHaveBeenCalled()
    view.rerender(<ChangePasswordModal {...props} loading />)
    fireEvent.submit(document.getElementById('profile-change-password-form')!)
    expect(submit).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: /确认修改/ })).toBeDisabled()
  })

  it('retries logout without submitting a second password and clears secrets after closing', async () => {
    const props = {
      visible: true,
      username: 'account',
      loading: false,
      onCancel: vi.fn(),
      onSubmit: vi.fn(async () => {})
    }
    const view = render(<ChangePasswordModal {...props} />, { wrapper: NoMotion })
    await fill('当前密码', 'OldPassword1')
    view.rerender(<ChangePasswordModal {...props} visible={false} />)
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    view.rerender(<ChangePasswordModal {...props} />)
    expect(screen.getByLabelText('当前密码')).toHaveValue('')
    view.rerender(<ChangePasswordModal {...props} passwordChanged />)
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.getByText('密码已修改，请退出后重新登录')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重试退出登录' }))
    const retry = vi.fn(async () => {})
    view.rerender(<ChangePasswordModal {...props} passwordChanged onRetryLogout={retry} />)
    fireEvent.click(screen.getByRole('button', { name: '重试退出登录' }))
    expect(retry).toHaveBeenCalledOnce()
    expect(props.onSubmit).not.toHaveBeenCalled()
    const cancel = vi.fn()
    view.rerender(<ChangePasswordModal {...props} passwordChanged onCancel={cancel} />)
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }))
    expect(cancel).toHaveBeenCalledOnce()
  })
})
