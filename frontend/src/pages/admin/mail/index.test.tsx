import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import { describe, expect, it, vi } from 'vitest'

import { renderWithStrictQueryClient as renderWithQueryClient } from '@/test/strict-mode-utils'
import type { MailSettings } from '@/types/admin/mail'

const { getMailSettings, testMailSettings, updateMailSettings, successFeedback, errorFeedback } =
  vi.hoisted(() => ({
    getMailSettings: vi.fn(),
    testMailSettings: vi.fn(),
    updateMailSettings: vi.fn(),
    successFeedback: vi.fn(),
    errorFeedback: vi.fn()
  }))

vi.mock('@/api/admin/mail', () => ({ getMailSettings, testMailSettings, updateMailSettings }))
vi.mock('@/utils/feedback', () => ({
  feedback: { success: successFeedback, error: errorFeedback }
}))

import AdminMailPage from '.'

describe('admin mail page', () => {
  it('refreshes independently changed SMTP and sender settings without exposing a stored password', async () => {
    let current: MailSettings = { ...settings }
    getMailSettings.mockResolvedValue(current)
    const view = renderWithQueryClient(<AdminMailPage />)
    await screen.findByRole('button', { name: '编辑配置' })
    const changes: Array<[Partial<MailSettings>, string]> = [
      [{ host: 'smtp-next.invalid' }, 'smtp-next.invalid'],
      [{ port: 587 }, '587'],
      [{ username: 'next-user' }, 'next-user'],
      [{ fromAddress: 'next@example.com' }, 'next@example.com'],
      [{ connectionTimeoutMillis: 7000 }, '7000 ms'],
      [{ readTimeoutMillis: 15000 }, '15000 ms'],
      [{ frontendBaseUrl: 'https://next.invalid' }, 'https://next.invalid'],
      [{ passwordConfigured: false }, '未配置'],
      [{ ssl: false, startTls: false }, '不加密'],
      [{ testMode: true }, '测试模式（仅记录日志）'],
      [{ enabled: false }, '邮件服务未启用']
    ]
    for (const [patch, expected] of changes) {
      current = { ...current, ...patch }
      act(() => view.queryClient.setQueryData(['admin-mail-settings'], current))
      await waitFor(() => expect(screen.getByText(expected)).toBeInTheDocument())
    }
    await userEvent.click(screen.getByRole('button', { name: '编辑配置' }))
    expect(screen.getByLabelText('SMTP 密码 / 授权码')).toHaveValue('')
    expect(screen.getByLabelText('SMTP 主机')).toHaveValue('smtp-next.invalid')
    expect(screen.getByRole('radio', { name: '不加密' })).toBeChecked()
  })
  it('shows only the configured-password state and leaves the password input empty', async () => {
    getMailSettings.mockResolvedValue({
      connectionTimeoutMillis: 5000,
      enabled: true,
      frontendBaseUrl: 'https://rvport.example.com',
      fromAddress: 'noreply@example.com',
      host: 'smtp.example.com',
      passwordConfigured: true,
      port: 465,
      readTimeoutMillis: 10000,
      ssl: true,
      startTls: false,
      testMode: false,
      username: 'noreply@example.com'
    })
    const userEventInstance = userEvent.setup()

    renderWithQueryClient(<AdminMailPage />)

    expect(await screen.findByText('已配置')).toBeInTheDocument()
    await userEventInstance.click(screen.getByRole('button', { name: '编辑配置' }))

    expect(screen.getByLabelText('SMTP 密码 / 授权码')).toHaveValue('')
  })
  it('recovers from initial failure through retry', async () => {
    getMailSettings.mockRejectedValueOnce(new Error('offline')).mockResolvedValue(settings)
    const user = userEvent.setup()
    renderWithQueryClient(<AdminMailPage />)
    expect(await screen.findByText('邮箱配置加载失败')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /重\s*试/ }))
    expect(await screen.findByRole('button', { name: '编辑配置' })).toBeInTheDocument()
  })

  it.each(['success', 'failure'])(
    'preserves all editing drafts across background refresh %s',
    async (outcome) => {
      getMailSettings.mockResolvedValue(settings)
      const user = userEvent.setup()
      const { queryClient } = renderWithQueryClient(<AdminMailPage />)
      await user.click(await screen.findByRole('button', { name: '编辑配置' }))
      await user.clear(screen.getByLabelText('SMTP 主机'))
      await user.type(screen.getByLabelText('SMTP 主机'), 'draft-host')
      await user.type(screen.getByLabelText('SMTP 密码 / 授权码'), 'draft-secret')
      if (outcome === 'failure') getMailSettings.mockRejectedValue(new Error('offline'))
      else getMailSettings.mockResolvedValue({ ...settings, host: 'refreshed-host' })
      await act(async () => {
        await queryClient.invalidateQueries({ queryKey: ['admin-mail-settings'] })
      })
      expect(screen.getByLabelText('SMTP 主机')).toHaveValue('draft-host')
      expect(screen.getByLabelText('SMTP 密码 / 授权码')).toHaveValue('draft-secret')
      if (outcome === 'failure')
        expect(await screen.findByText('邮箱配置刷新失败，当前内容已保留')).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: /取\s*消/ }))
      await user.click(screen.getByRole('button', { name: '编辑配置' }))
      expect(screen.getByLabelText('SMTP 密码 / 授权码')).toHaveValue('')
      expect(screen.getByLabelText('SMTP 主机')).toHaveValue(
        outcome === 'success' ? 'refreshed-host' : settings.host
      )
    }
  )

  it('shows disabled settings and empty optional values', async () => {
    getMailSettings.mockResolvedValue({
      ...settings,
      enabled: false,
      host: null,
      port: null,
      username: null,
      fromAddress: null,
      passwordConfigured: false,
      ssl: false,
      startTls: true,
      testMode: true
    })

    renderWithQueryClient(<AdminMailPage />)

    expect(await screen.findByText('邮件服务未启用')).toBeInTheDocument()
    expect(screen.getByText('未配置')).toBeInTheDocument()
    expect(screen.getByText('STARTTLS')).toBeInTheDocument()
    expect(screen.getByText('测试模式（仅记录日志）')).toBeInTheDocument()
    expect(screen.getAllByText('--')).toHaveLength(5)
  })

  it('saves trimmed settings and a new password', async () => {
    getMailSettings.mockResolvedValue(settings)
    updateMailSettings.mockResolvedValue({
      ...settings,
      host: 'new-host',
      ssl: false,
      startTls: true
    })
    const user = userEvent.setup()
    renderWithQueryClient(<AdminMailPage />)

    await user.click(await screen.findByRole('button', { name: '编辑配置' }))
    await user.clear(screen.getByLabelText('SMTP 主机'))
    await user.type(screen.getByLabelText('SMTP 主机'), '  new-host  ')
    await user.type(screen.getByLabelText('SMTP 密码 / 授权码'), 'new-secret')
    await user.click(screen.getByRole('radio', { name: 'STARTTLS' }))
    await user.click(screen.getByRole('button', { name: /保\s*存\s*配\s*置/ }))

    await waitFor(() => expect(updateMailSettings).toHaveBeenCalledOnce())
    expect(updateMailSettings.mock.calls[0][0]).toEqual(
      expect.objectContaining({
        host: 'new-host',
        password: 'new-secret',
        ssl: false,
        startTls: true,
        enabled: true,
        port: 465
      })
    )
    expect(await screen.findByText('邮件服务已启用')).toBeInTheDocument()
    expect(screen.getByText('new-host')).toBeInTheDocument()
  })

  it('requires SMTP host, port, and sender when real mail is enabled', async () => {
    getMailSettings.mockResolvedValue({
      ...settings,
      host: null,
      port: null,
      fromAddress: null,
      ssl: false
    })
    const user = userEvent.setup()
    renderWithQueryClient(<AdminMailPage />)

    await user.click(await screen.findByRole('button', { name: '编辑配置' }))
    await user.click(screen.getByRole('button', { name: /保\s*存\s*配\s*置/ }))

    expect(await screen.findByText('请输入 SMTP 主机')).toBeInTheDocument()
    expect(screen.getByText('请输入端口')).toBeInTheDocument()
    expect(screen.getByText('请输入发件人地址')).toBeInTheDocument()
    expect(updateMailSettings).not.toHaveBeenCalled()

    await user.click(screen.getByRole('switch', { name: '测试模式' }))
    await user.click(screen.getByRole('button', { name: /保\s*存\s*配\s*置/ }))
    await waitFor(() => expect(updateMailSettings).toHaveBeenCalledOnce())
    expect(updateMailSettings.mock.calls[0][0]).toEqual(
      expect.objectContaining({ host: null, port: null, fromAddress: null, testMode: true })
    )
  })

  it.each([true, false])('handles test mail result success=%s', async (success) => {
    getMailSettings.mockResolvedValue(settings)
    testMailSettings.mockResolvedValue({ success, message: success ? 'sent' : 'rejected' })
    const user = userEvent.setup()
    renderWithQueryClient(<AdminMailPage />)

    await user.click(await screen.findByRole('button', { name: '发送测试邮件' }))
    await user.type(screen.getByLabelText('收件人邮箱'), 'receiver@example.com')
    await user.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: '发送测试邮件' })
    )

    await waitFor(() => expect(testMailSettings).toHaveBeenCalledOnce())
    expect(testMailSettings.mock.calls[0][0]).toEqual({ to: 'receiver@example.com' })
    if (success) {
      await waitFor(() => expect(successFeedback).toHaveBeenCalledWith('sent'))
    } else {
      await waitFor(() => expect(errorFeedback).toHaveBeenCalledWith('rejected'))
      expect(screen.getByLabelText('收件人邮箱')).toBeInTheDocument()
    }
  })
})

const settings = {
  connectionTimeoutMillis: 5000,
  enabled: true,
  frontendBaseUrl: null,
  fromAddress: 'test@example.com',
  host: 'saved-host',
  passwordConfigured: true,
  port: 465,
  readTimeoutMillis: 10000,
  ssl: true,
  startTls: false,
  testMode: false,
  username: 'saved-user'
}

it('saves disabled mail with optional fields cleared, preserves timeout defaults and leaves stored credentials untouched', async () => {
  getMailSettings.mockResolvedValue({
    ...settings,
    enabled: false,
    testMode: false,
    username: null,
    frontendBaseUrl: 'https://old.invalid'
  })
  updateMailSettings.mockResolvedValue({
    ...settings,
    enabled: false,
    host: null,
    port: null,
    ssl: false,
    startTls: false
  })
  const actor = userEvent.setup()
  renderWithQueryClient(<AdminMailPage />)
  await actor.click(await screen.findByRole('button', { name: '编辑配置' }))
  for (const label of [
    'SMTP 主机',
    '端口',
    '发件人地址',
    '前端地址',
    '连接超时（毫秒）',
    '读取超时（毫秒）'
  ]) {
    await actor.clear(screen.getByLabelText(label))
  }
  await actor.click(screen.getByRole('radio', { name: '不加密' }))
  await actor.click(screen.getByRole('button', { name: /保\s*存\s*配\s*置/ }))
  await waitFor(() => expect(updateMailSettings).toHaveBeenCalledOnce())
  expect(updateMailSettings).toHaveBeenCalledWith(
    expect.objectContaining({
      enabled: false,
      host: null,
      port: null,
      fromAddress: null,
      frontendBaseUrl: null,
      username: null,
      password: undefined,
      connectionTimeoutMillis: 5000,
      readTimeoutMillis: 10000,
      ssl: false,
      startTls: false
    }),
    expect.anything()
  )
})

it('cancels test mail without sending and clears its recipient on reopening', async () => {
  getMailSettings.mockResolvedValue(settings)
  const actor = userEvent.setup()
  renderWithQueryClient(
    <ConfigProvider theme={{ token: { motion: false } }}>
      <AdminMailPage />
    </ConfigProvider>
  )
  await actor.click(await screen.findByRole('button', { name: '发送测试邮件' }))
  await actor.type(screen.getByLabelText('收件人邮箱'), 'draft@example.com')
  await actor.click(within(screen.getByRole('dialog')).getByRole('button', { name: /取\s*消/ }))
  expect(testMailSettings).not.toHaveBeenCalled()
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  await actor.click(screen.getByRole('button', { name: '发送测试邮件' }))
  expect(screen.getByLabelText('收件人邮箱')).toHaveValue('')
})
