import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createAgentProbeResult, createAgentRuntime } from '@/test/model-profile-fixtures'
import type { AgentRuntime, ModelProfile } from '@/types/admin/models'

const { getModelProfiles } = vi.hoisted(() => ({ getModelProfiles: vi.fn() }))
vi.mock('@/api/admin/models', () => ({ getModelProfiles }))

let rows: ModelProfile[] = []
vi.mock('@/components/BaseTable', () => ({
  default: ({
    columns,
    request
  }: {
    columns: Array<{
      dataIndex?: keyof ModelProfile
      key?: string
      render?: (value: unknown, profile: ModelProfile) => ReactNode
    }>
    request: (params: { keyword?: string; page: number; pageSize: number }) => Promise<unknown>
  }) => (
    <div>
      <button onClick={() => void request({ keyword: 'test', page: 2, pageSize: 10 })}>加载</button>
      {rows.map((profile) => (
        <section key={profile.id}>
          {columns.map((column, index) => (
            <div key={column.key ?? column.dataIndex ?? index}>
              {column.render?.(column.dataIndex ? profile[column.dataIndex] : undefined, profile)}
            </div>
          ))}
        </section>
      ))}
    </div>
  )
}))

import ModelProfileTable from './ModelProfileTable'

const profile: ModelProfile = {
  apiKeyConfigured: false,
  apiKeyMasked: null,
  baseUrl: null,
  createdAt: '2026-10-27T08:00:00Z',
  enabled: false,
  executablePath: 'codex',
  id: 'profile-1',
  lastConnectivityDetails: {},
  lastConnectivityDurationMs: null,
  lastConnectivityMessage: null,
  lastConnectivityStatus: null,
  lastConnectivityTestedAt: null,
  agentProbeResults: [],
  activeAgentProbeRun: null,
  maxRuntimeSeconds: 300,
  model: null,
  modelProviderType: 'OPENAI_COMPATIBLE',
  name: 'Demo Profile',
  timeoutSeconds: 30,
  updatedAt: '2026-10-27T08:00:00Z',
  wireApi: 'RESPONSES'
}

function createActions() {
  return {
    agentRuntimesQuery: { data: [] as AgentRuntime[] | undefined },
    connectivityMutation: {
      isPending: false,
      variables: undefined as string | undefined,
      mutate: vi.fn()
    },
    setTestModalProfile: vi.fn(),
    setModalProfile: vi.fn(),
    updateStatusMutation: {
      isPending: false,
      variables: undefined as { enabled: boolean; profileId: string } | undefined,
      mutate: vi.fn()
    },
    deleteMutation: {
      isPending: false,
      variables: undefined as string | undefined,
      mutate: vi.fn()
    }
  }
}
let actions = createActions()

function showTable() {
  // The table reads only these action/query fields from the larger page model.
  return render(
    <ModelProfileTable
      model={actions as unknown as Parameters<typeof ModelProfileTable>[0]['model']}
      tableRef={{ current: null }}
    />
  )
}

describe('model profile table', () => {
  beforeEach(() => {
    rows = [profile]
    actions = createActions()
  })

  it('keeps completed probe results accessible while the runtime catalogue is unavailable, then resolves runtime names after recovery', async () => {
    const result = createAgentProbeResult()
    rows = [{ ...profile, agentProbeResults: [result] }]
    actions.agentRuntimesQuery.data = undefined
    const view = showTable()
    const actor = userEvent.setup()
    await actor.click(screen.getByRole('button', { name: '查看 1 个 Agent 的兼容性测试结果' }))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(screen.getAllByText('CODEX').length).toBeGreaterThan(0)
    await actor.click(screen.getByRole('button', { name: 'Close' }))
    actions.agentRuntimesQuery.data = [createAgentRuntime({ name: '恢复后的运行时' })]
    view.rerender(
      <ModelProfileTable
        model={actions as unknown as Parameters<typeof ModelProfileTable>[0]['model']}
        tableRef={{ current: null }}
      />
    )
    await actor.click(screen.getByRole('button', { name: '查看 1 个 Agent 的兼容性测试结果' }))
    expect(await screen.findByText('恢复后的运行时')).toBeInTheDocument()
  })

  it('shows untested values and confirms status/delete actions', async () => {
    const actor = userEvent.setup()
    showTable()
    expect(screen.getByText('Demo Profile')).toBeInTheDocument()
    expect(screen.getAllByText('--')).toHaveLength(3)
    expect(screen.getAllByText('未测试')).toHaveLength(2)
    expect(screen.getByRole('button', { name: '测试 Agent 兼容性' })).toBeDisabled()

    await actor.click(screen.getByRole('button', { name: '加载' }))
    expect(getModelProfiles).toHaveBeenCalledWith({ keyword: 'test', page: 2, pageSize: 10 })
    await actor.click(screen.getByRole('button', { name: '测试模型连通性' }))
    expect(actions.connectivityMutation.mutate).toHaveBeenCalledWith(profile.id)
    await actor.click(screen.getByRole('button', { name: '编辑 Profile' }))
    expect(actions.setModalProfile).toHaveBeenCalledWith(profile)
    await actor.click(screen.getByRole('button', { name: '启用 Profile' }))
    await actor.click(screen.getAllByRole('button', { name: 'OK' })[0])
    expect(actions.updateStatusMutation.mutate).toHaveBeenCalledWith({
      enabled: true,
      profileId: profile.id
    })
    await actor.click(screen.getByRole('button', { name: '删除 Profile' }))
    await actor.click(screen.getAllByRole('button', { name: 'OK' }).at(-1)!)
    expect(actions.deleteMutation.mutate).toHaveBeenCalledWith(profile.id)
  })

  it('enables agent tests after successful connectivity and shows a running probe', async () => {
    const actor = userEvent.setup()
    rows = [
      {
        ...profile,
        enabled: true,
        lastConnectivityStatus: 'SUCCESS',
        lastConnectivityTestedAt: '2026-10-27T08:00:00Z',
        model: 'test-model',
        baseUrl: 'https://example.com'
      }
    ]
    const { rerender } = showTable()
    expect(screen.getByText('可用')).toBeInTheDocument()
    expect(screen.getByText('test-model')).toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: '测试 Agent 兼容性' }))
    expect(actions.setTestModalProfile).toHaveBeenCalledWith(rows[0])

    rows = [
      {
        ...rows[0],
        activeAgentProbeRun: {
          runId: 'run-1',
          status: 'RUNNING',
          createdAt: '2026-10-27T08:00:00Z',
          agentRuntimes: [{ id: 'agent-1', name: 'Codex', agentType: 'CODEX', enabled: true }]
        }
      }
    ]
    rerender(
      <ModelProfileTable
        model={actions as unknown as Parameters<typeof ModelProfileTable>[0]['model']}
        tableRef={{ current: null }}
      />
    )
    expect(screen.getByText('正在测试 1 个 Agent')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '测试 Agent 兼容性' })).toBeDisabled()
  })
})

it.each([
  ['TIMEOUT', '超时'],
  ['FAILED', '失败']
] as const)(
  'shows a completed %s connectivity check with its outcome instead of treating it as untested',
  (status, text) => {
    rows = [
      {
        ...profile,
        lastConnectivityStatus: status,
        lastConnectivityTestedAt: '2026-10-28T00:00:00Z'
      }
    ]
    actions = createActions()
    showTable()
    expect(screen.getByText(text)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '测试 Agent 兼容性' })).toBeDisabled()
  }
)
it('assigns busy indicators to the submitted profile rather than another row', () => {
  rows = [
    { ...profile, id: 'first', name: 'first' },
    { ...profile, id: 'second', name: 'second' }
  ]
  actions = createActions()
  actions = {
    ...actions,
    connectivityMutation: { ...actions.connectivityMutation, isPending: true, variables: 'first' },
    updateStatusMutation: {
      ...actions.updateStatusMutation,
      isPending: true,
      variables: { enabled: true, profileId: 'second' }
    },
    deleteMutation: { ...actions.deleteMutation, isPending: true, variables: 'first' }
  }
  showTable()
  const first = screen.getByText('first').closest('section')!,
    second = screen.getByText('second').closest('section')!
  for (const [container, label, loading] of [
    [first, '测试模型连通性', true],
    [second, '测试模型连通性', false],
    [first, '启用 Profile', false],
    [second, '启用 Profile', true],
    [first, '删除 Profile', true],
    [second, '删除 Profile', false]
  ] as const) {
    const button = within(container).getByRole('button', { name: new RegExp(label) })
    if (loading) expect(button).toHaveClass('ant-btn-loading')
    else expect(button).not.toHaveClass('ant-btn-loading')
  }
})
