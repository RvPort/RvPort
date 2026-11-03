import { type ReactNode, StrictMode } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import { describe, expect, it, vi } from 'vitest'

import { createAgentRuntime, createModelProfile } from '@/test/model-profile-fixtures'
import type { ModelProfile } from '@/types/admin/models'

import ModelProfileModal from './ModelProfileModal'
import TestProfileModal from './TestProfileModal'

const NoMotion = ({ children }: { children: ReactNode }) => (
  <ConfigProvider theme={{ token: { motion: false } }}>{children}</ConfigProvider>
)

describe('profile form ownership', () => {
  it('preserves a model edit during unrelated parent updates under StrictMode and saves through the latest callback', async () => {
    const profile = createModelProfile(),
      cancel = vi.fn(),
      initial = vi.fn(async () => {}),
      latest = vi.fn(async () => {})
    const draw = (onSubmit: typeof initial) => (
      <StrictMode>
        <ModelProfileModal
          profile={profile}
          loading={false}
          visible
          onCancel={cancel}
          onSubmit={onSubmit}
        />
      </StrictMode>
    )
    const view = render(draw(initial), { wrapper: NoMotion })
    const actor = userEvent.setup()
    await actor.clear(screen.getByLabelText('模型名称'))
    await actor.type(screen.getByLabelText('模型名称'), 'unsaved-model')
    await actor.type(screen.getByLabelText('API Key'), 'new-secret')
    view.rerender(draw(latest))
    expect(screen.getByLabelText('模型名称')).toHaveValue('unsaved-model')
    expect(screen.getByLabelText('API Key')).toHaveValue('new-secret')
    await actor.click(screen.getByRole('button', { name: '保存配置' }))
    await waitFor(() =>
      expect(latest).toHaveBeenCalledExactlyOnceWith(
        expect.objectContaining({ model: 'unsaved-model', apiKey: 'new-secret' })
      )
    )
    expect(initial).not.toHaveBeenCalled()
  })
  it('does not start a queued agent test if another submission becomes pending during validation', async () => {
    const props = {
      agentRuntimes: [createAgentRuntime()],
      defaultAgentRuntimeId: 'runtime-1',
      profile: createModelProfile(),
      loading: false,
      onCancel: vi.fn(),
      onSubmit: vi.fn(async () => {})
    }
    const view = render(<TestProfileModal {...props} />, { wrapper: NoMotion })
    expect(screen.getByRole('checkbox', { name: '测试运行时 CODEX' })).toBeChecked()
    fireEvent.submit(document.getElementById('test-model-profile-form')!)
    view.rerender(<TestProfileModal {...props} loading />)
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0))
    })
    expect(props.onSubmit).not.toHaveBeenCalled()
    expect(screen.getByRole('checkbox', { name: '测试运行时 CODEX' })).toBeChecked()
    view.rerender(<TestProfileModal {...props} />)
    await userEvent.click(screen.getByRole('button', { name: '开始测试' }))
    expect(props.onSubmit).toHaveBeenCalledExactlyOnceWith({ agentRuntimeIds: ['runtime-1'] })
  })
  it('loads independent profile updates, never echoes a stored API key and submits current values and callbacks', async () => {
    const cancel = vi.fn(),
      submit = vi.fn(async () => {})
    let profile = createModelProfile()
    const props = { profile, loading: false, visible: true, onCancel: cancel, onSubmit: submit }
    const view = render(<ModelProfileModal {...props} />, { wrapper: NoMotion })
    const updates: Array<[Partial<ModelProfile>, string, string | number]> = [
      [{ name: '新模型名称' }, 'Profile 名称', '新模型名称'],
      [{ baseUrl: 'https://next.invalid/v1' }, '模型 API 地址', 'https://next.invalid/v1'],
      [{ model: 'model-v2' }, '模型名称', 'model-v2'],
      [{ executablePath: '/usr/bin/codex' }, 'Codex CLI 路径', '/usr/bin/codex'],
      [{ timeoutSeconds: 60 }, '连接超时（秒）', '60'],
      [{ maxRuntimeSeconds: 600 }, '最大执行时长（秒）', '600']
    ]
    for (const [patch, label, expected] of updates) {
      profile = { ...profile, ...patch }
      view.rerender(<ModelProfileModal {...props} profile={profile} />)
      await waitFor(() => expect(screen.getByLabelText(label)).toHaveValue(expected))
      expect(screen.getByLabelText('API Key')).toHaveValue('')
    }
    const nextSubmit = vi.fn(async () => {})
    view.rerender(<ModelProfileModal {...props} profile={profile} onSubmit={nextSubmit} />)
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }))
    await waitFor(() =>
      expect(nextSubmit).toHaveBeenCalledWith(
        expect.objectContaining({
          name: '新模型名称',
          model: 'model-v2',
          executablePath: '/usr/bin/codex',
          timeoutSeconds: 60,
          maxRuntimeSeconds: 600,
          apiKey: undefined
        })
      )
    )
    expect(submit).not.toHaveBeenCalled()
    view.rerender(<ModelProfileModal {...props} profile={profile} loading />)
    expect(screen.getByLabelText('模型名称')).toBeDisabled()
    fireEvent.submit(document.getElementById('model-profile-form')!)
    await waitFor(() => expect(screen.getByRole('button', { name: /保存配置/ })).toBeDisabled())
    expect(submit).not.toHaveBeenCalled()
    view.rerender(<ModelProfileModal {...props} profile={null} />)
    expect(screen.getByText('新增模型 Profile')).toBeInTheDocument()
    expect(screen.getByLabelText('Profile 名称')).toHaveValue('')
    expect(screen.getByLabelText('Codex CLI 路径')).toHaveValue('codex')
    expect(screen.queryByText('已配置密钥，留空则保持不变。')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /取\s*消/ }))
    expect(cancel).toHaveBeenCalledOnce()
    view.rerender(<ModelProfileModal {...props} visible={false} />)
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument(), {
      timeout: 3000
    })
  })

  it('limits bulk agent selection to ten, excludes disabled runtimes and resets on profile/default changes', async () => {
    const runtimes = Array.from({ length: 11 }, (_, index) =>
      createAgentRuntime({ id: `agent-${index}`, name: `Agent ${index}` })
    )
    const disabled = createAgentRuntime({ id: 'disabled', name: '停用运行时', enabled: false })
    const props = {
      agentRuntimes: [...runtimes, disabled],
      profile: createModelProfile(),
      defaultAgentRuntimeId: 'disabled',
      loading: false,
      onCancel: vi.fn(),
      onSubmit: vi.fn(async () => {})
    }
    const view = render(<TestProfileModal {...props} />, { wrapper: NoMotion })
    expect(screen.getByText('已选 0 个')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: /停用运行时/ })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: '全选前 10 个' }))
    expect(screen.getByText('已选 10 个')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Agent 10 CODEX' })).toBeDisabled()
    const nextSubmit = vi.fn(async () => {})
    view.rerender(<TestProfileModal {...props} onSubmit={nextSubmit} />)
    await userEvent.click(screen.getByRole('button', { name: '开始测试' }))
    await waitFor(() =>
      expect(nextSubmit).toHaveBeenCalledWith({
        agentRuntimeIds: runtimes.slice(0, 10).map((item) => item.id)
      })
    )
    expect(props.onSubmit).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('checkbox', { name: '全选前 10 个' }))
    expect(screen.getByText('已选 0 个')).toBeInTheDocument()
    view.rerender(<TestProfileModal {...props} defaultAgentRuntimeId="agent-2" />)
    await waitFor(() => expect(screen.getByText('已选 1 个')).toBeInTheDocument())
    expect(screen.getByRole('checkbox', { name: 'Agent 2 CODEX' })).toBeChecked()
    view.rerender(<TestProfileModal {...props} defaultAgentRuntimeId="agent-2" loading />)
    expect(screen.getByRole('checkbox', { name: '全选前 10 个' })).toBeDisabled()
    fireEvent.submit(document.getElementById('test-model-profile-form')!)
    expect(props.onSubmit).not.toHaveBeenCalled()
    view.rerender(<TestProfileModal {...props} agentRuntimes={[]} />)
    expect(screen.getByRole('button', { name: /开始测试/ })).toBeDisabled()
    expect(screen.getByRole('checkbox', { name: '全选' })).toBeDisabled()
    view.rerender(<TestProfileModal {...props} profile={null} />)
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument(), {
      timeout: 3000
    })
  })
})
