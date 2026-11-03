import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { createModelProfile } from '@/test/model-profile-fixtures'
import { createTestQueryClient } from '@/test/test-utils'
import type { ModelProfile, ModelProfileAgentProbeRun } from '@/types/admin/models'

const api = vi.hoisted(() => ({
  getModelProfiles: vi.fn(),
  getAgentRuntimes: vi.fn(),
  getModelStageAssignments: vi.fn(),
  getModelProfileAgentTestRun: vi.fn(),
  createModelProfile: vi.fn(),
  deleteModelProfile: vi.fn(),
  testModelProfileAgents: vi.fn(),
  testModelProfileConnectivity: vi.fn(),
  updateModelProfile: vi.fn(),
  updateModelStageAssignments: vi.fn()
}))
const { success, error } = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/api/admin/models', () => api)
vi.mock('@/utils/feedback', () => ({ feedback: { success, error } }))

import { modelQueryKeys } from './queryKeys'
import { useModelProfiles } from './useModelProfiles'

function setup() {
  const client = createTestQueryClient()
  const reload = vi.fn().mockResolvedValue(undefined)
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return {
    client,
    reload,
    ...renderHook(() => useModelProfiles({ current: { reload } }), { wrapper })
  }
}

describe('model profile actions', () => {
  beforeEach(() => {
    api.getModelProfiles.mockResolvedValue({
      items: [],
      total: 0,
      page: 1,
      pageSize: 20,
      hasMore: false
    })
    api.getAgentRuntimes.mockResolvedValue([])
    api.getModelStageAssignments.mockResolvedValue([])
  })

  it('creates a new profile with defaults and then updates an existing profile', async () => {
    api.createModelProfile.mockResolvedValue({ id: 'profile-1' })
    api.updateModelProfile.mockResolvedValue({ id: 'profile-1' })
    const { result, reload } = setup()
    act(() => result.current.setModalProfile(null))
    await act(async () => {
      await result.current.saveProfileMutation.mutateAsync({ name: 'New Profile' })
    })
    expect(api.createModelProfile.mock.calls[0][0]).toMatchObject({
      name: 'New Profile',
      enabled: true,
      executablePath: 'codex',
      wireApi: 'RESPONSES',
      baseUrl: '',
      model: '',
      apiKey: ''
    })
    expect(reload).toHaveBeenCalledOnce()
    expect(success).toHaveBeenCalledWith('模型 Profile 已保存')

    act(() => result.current.setModalProfile({ id: 'profile-1' } as ModelProfile))
    await act(async () => {
      await result.current.saveProfileMutation.mutateAsync({ name: 'Renamed Profile' })
    })
    expect(api.updateModelProfile.mock.calls[0].slice(0, 2)).toEqual([
      'profile-1',
      { name: 'Renamed Profile' }
    ])
  })

  it('binds a model and agent, starts a probe, and reports connectivity failures', async () => {
    api.updateModelStageAssignments.mockResolvedValue({})
    api.testModelProfileAgents.mockResolvedValue({ id: 'probe-1' })
    api.testModelProfileConnectivity.mockResolvedValue({ success: false, message: 'unreachable' })
    const { result } = setup()

    act(() => result.current.setEditingStage('KB_AI_EXTENSION'))
    await act(async () => {
      await result.current.bindingMutation.mutateAsync({
        stage: 'KB_AI_EXTENSION',
        modelProfileId: 'profile-1',
        agentRuntimeId: 'agent-1'
      })
    })
    expect(api.updateModelStageAssignments).toHaveBeenCalled()
    expect(success).toHaveBeenCalledWith('知识库 AI 扩展模型与 Agent 已绑定')

    act(() => result.current.setTestModalProfile({ id: 'profile-1' } as ModelProfile))
    await act(async () => {
      await result.current.agentProbeMutation.mutateAsync({
        profileId: 'profile-1',
        agentRuntimeIds: ['agent-1']
      })
    })
    expect(api.testModelProfileAgents.mock.calls[0].slice(0, 2)).toEqual(['profile-1', ['agent-1']])
    expect(success).toHaveBeenCalledWith('Agent 兼容性测试已开始')

    await act(async () => {
      await result.current.connectivityMutation.mutateAsync('profile-1')
    })
    expect(error).toHaveBeenCalledWith('unreachable')
    api.testModelProfileConnectivity.mockResolvedValue({ success: true, message: 'ok' })
    await act(async () => {
      await result.current.connectivityMutation.mutateAsync('profile-1')
    })
    expect(success).toHaveBeenCalledWith('模型 Provider 连通性测试通过')
  })

  it('updates status and deletes a profile with table refresh', async () => {
    api.updateModelProfile.mockResolvedValue({ id: 'profile-1' })
    api.deleteModelProfile.mockResolvedValue(undefined)
    const { result, reload } = setup()
    await waitFor(() => expect(result.current.modelProfilesStateQuery.isSuccess).toBe(true))
    await act(async () => {
      await result.current.updateStatusMutation.mutateAsync({
        profileId: 'profile-1',
        enabled: false
      })
      await result.current.deleteMutation.mutateAsync('profile-1')
    })
    expect(api.updateModelProfile.mock.calls[0].slice(0, 2)).toEqual([
      'profile-1',
      { enabled: false }
    ])
    expect(api.deleteModelProfile.mock.calls[0][0]).toBe('profile-1')
    expect(reload).toHaveBeenCalledTimes(2)
  })
  afterEach(() => vi.useRealTimers())
  it('polls active probes, refreshes dependent caches once per completed run and stops terminal polling', async () => {
    vi.useFakeTimers()
    const profile = createModelProfile({
      activeAgentProbeRun: {
        runId: 'probe-1',
        status: 'RUNNING',
        agentRuntimes: [],
        createdAt: '2026-10-28T00:00:00Z'
      }
    })
    api.getModelProfiles.mockResolvedValue({
      items: [profile],
      page: 1,
      pageSize: 200,
      total: 1,
      hasMore: false
    })
    const run: ModelProfileAgentProbeRun = {
      agentRuntimes: [],
      createdAt: '',
      finishedAt: null,
      id: 'probe-1',
      message: null,
      modelProfileId: profile.id,
      results: [],
      selectedAgentRuntimeIds: [],
      startedAt: null,
      status: 'RUNNING',
      updatedAt: ''
    }
    api.getModelProfileAgentTestRun
      .mockResolvedValueOnce(run)
      .mockResolvedValue({ ...run, status: 'SUCCESS' })
    const { client, result } = setup()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    await vi.waitFor(() => expect(api.getModelProfileAgentTestRun).toHaveBeenCalledOnce())
    expect(invalidate).not.toHaveBeenCalled()
    await act(async () => vi.advanceTimersByTimeAsync(2100))
    await vi.waitFor(() => expect(invalidate).toHaveBeenCalledTimes(3))
    for (const key of [
      modelQueryKeys.profilesState(),
      modelQueryKeys.profiles(),
      modelQueryKeys.assignments()
    ])
      expect(invalidate).toHaveBeenCalledWith({ queryKey: key })
    expect(result.current.modelProfilesStateQuery.data?.items[0].id).toBe(profile.id)
    const requests = api.getModelProfileAgentTestRun.mock.calls.length
    act(() =>
      client.setQueryData(modelQueryKeys.probeRun('probe-1'), {
        ...run,
        status: 'SUCCESS',
        updatedAt: 'new metadata'
      })
    )
    await act(async () => vi.advanceTimersByTimeAsync(6000))
    expect(invalidate).toHaveBeenCalledTimes(3)
    expect(api.getModelProfileAgentTestRun).toHaveBeenCalledTimes(requests)
  })
})
