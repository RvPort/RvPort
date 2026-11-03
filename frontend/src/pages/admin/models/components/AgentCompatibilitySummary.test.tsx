import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import type { AgentRuntime, ModelProfileAgentProbeResult } from '@/types/admin/models'

import AgentCompatibilitySummary from './AgentCompatibilitySummary'

vi.mock('@/components/BaseModal', () => ({
  default: ({ children, onCancel }: { children: ReactNode; onCancel: () => void }) => (
    <section role="dialog" aria-label="兼容性详情">
      {children}
      <button onClick={onCancel}>关闭详情</button>
    </section>
  )
}))

const runtime: AgentRuntime = {
  id: 'agent-1',
  agentType: 'CODEX',
  name: '开发 Agent',
  enabled: true,
  lastProbeAt: null,
  lastProbeMessage: null,
  lastProbeStatus: null,
  createdAt: '',
  updatedAt: ''
}
function probe(patch: Partial<ModelProfileAgentProbeResult> = {}): ModelProfileAgentProbeResult {
  return {
    id: 'probe-1',
    agentRuntimeId: 'agent-1',
    agentType: 'CODEX',
    modelProfileId: 'model-1',
    runId: 'run-1',
    basicAvailable: false,
    agentCompatible: null,
    checks: [],
    createdAt: '',
    updatedAt: '',
    startedAt: null,
    finishedAt: null,
    durationMs: null,
    details: {},
    errorCode: null,
    message: '连接被拒绝',
    status: 'FAILED',
    ...patch
  }
}

describe('Agent compatibility summary', () => {
  it('opens, refreshes and closes compatibility details while preserving runtime identity', async () => {
    const actor = userEvent.setup()
    let result = probe()
    let agent = runtime
    const view = render(<AgentCompatibilitySummary agentRuntimes={[agent]} results={[result]} />)
    await actor.click(screen.getByRole('button', { name: '查看 1 个 Agent 的兼容性测试结果' }))
    const dialog = await screen.findByRole('dialog', { name: '兼容性详情' })
    expect(within(dialog).getByText('开发 Agent')).toBeInTheDocument()
    expect(within(dialog).getByText('连接被拒绝')).toBeInTheDocument()
    result = { ...result, basicAvailable: true }
    view.rerender(<AgentCompatibilitySummary agentRuntimes={[agent]} results={[result]} />)
    expect(within(dialog).getAllByText('通过')).toHaveLength(1)
    expect(within(dialog).getAllByText('未通过')).toHaveLength(1)
    result = { ...result, agentCompatible: true, status: 'SUCCESS', message: null }
    view.rerender(<AgentCompatibilitySummary agentRuntimes={[agent]} results={[result]} />)
    expect(within(dialog).getAllByText('通过')).toHaveLength(2)
    expect(within(dialog).queryByText('连接被拒绝')).not.toBeInTheDocument()
    agent = { ...agent, name: '新名称' }
    view.rerender(<AgentCompatibilitySummary agentRuntimes={[agent]} results={[result]} />)
    expect(within(dialog).getByText('新名称')).toBeInTheDocument()
    expect(within(dialog).queryByText('开发 Agent')).not.toBeInTheDocument()
    agent = { ...agent, agentType: 'OPENCODE' }
    view.rerender(<AgentCompatibilitySummary agentRuntimes={[agent]} results={[result]} />)
    expect(within(dialog).getByText('OPENCODE')).toBeInTheDocument()
    await actor.click(within(dialog).getByRole('button', { name: '关闭详情' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('preserves unconfigured runtime results with fallback identities and an explicit missing explanation', async () => {
    const actor = userEvent.setup()
    const view = render(<AgentCompatibilitySummary agentRuntimes={[]} results={[]} />)
    expect(
      screen.getByRole('button', { name: '查看 0 个 Agent 的兼容性测试结果' })
    ).toBeInTheDocument()
    const result = probe({
      agentRuntimeId: 'removed-runtime',
      agentType: 'FUTURE_AGENT',
      message: null
    })
    view.rerender(<AgentCompatibilitySummary agentRuntimes={[runtime]} results={[result]} />)
    await actor.click(screen.getByRole('button', { name: '查看 1 个 Agent 的兼容性测试结果' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getAllByText('FUTURE_AGENT')).toHaveLength(2)
    expect(within(dialog).getByText('暂无测试说明')).toBeInTheDocument()
    expect(within(dialog).queryByText('开发 Agent')).not.toBeInTheDocument()
  })
})
