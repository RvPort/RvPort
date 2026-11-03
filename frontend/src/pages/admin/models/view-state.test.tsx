import { fireEvent, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { createAgentRuntime, createModelProfile } from '@/test/model-profile-fixtures'
import { renderWithStrictQueryClient as renderWithQueryClient } from '@/test/strict-mode-utils'
import type { AgentRuntime, ModelProfile, ModelStageAssignment } from '@/types/admin/models'

import AdminModelsPage from '.'

const hook = vi.hoisted(() => ({ useModelProfiles: vi.fn() }))
vi.mock('./useModelProfiles', () => hook)
vi.mock('./components/ModelStageBindings', () => ({ default: () => <div>阶段绑定</div> }))
vi.mock('./components/ModelProfileTable', () => ({ default: () => <div>模型列表</div> }))
vi.mock('./components/ModelProfileModal', () => ({
  default: ({
    loading,
    profile,
    onSubmit,
    onCancel
  }: {
    loading: boolean
    profile: ModelProfile | null
    onSubmit: (value: { name: string }) => Promise<void>
    onCancel: () => void
  }) => (
    <div>
      编辑模型 {profile?.name ?? '新建'} 忙碌 {String(loading)}
      <button onClick={() => void onSubmit({ name: 'saved' })}>提交模型</button>
      <button onClick={onCancel}>取消模型</button>
    </div>
  )
}))
vi.mock('./components/TestProfileModal', () => ({
  default: ({
    agentRuntimes,
    defaultAgentRuntimeId,
    loading,
    profile,
    onSubmit,
    onCancel
  }: {
    agentRuntimes: AgentRuntime[]
    defaultAgentRuntimeId?: string
    loading: boolean
    profile: ModelProfile
    onSubmit: (value: { agentRuntimeIds: string[] }) => Promise<void>
    onCancel: () => void
  }) => (
    <div>
      测试模型 {profile.id} 默认 {defaultAgentRuntimeId ?? 'none'} 数量 {agentRuntimes.length} 忙碌{' '}
      {String(loading)}
      <button onClick={() => void onSubmit({ agentRuntimeIds: ['selected'] })}>提交测试</button>
      <button onClick={onCancel}>取消测试</button>
    </div>
  )
}))

function fixture() {
  return {
    modalProfile: undefined as ModelProfile | null | undefined,
    testModalProfile: null as ModelProfile | null,
    setModalProfile: vi.fn(),
    setTestModalProfile: vi.fn(),
    assignments: new Map<string, ModelStageAssignment>(),
    agentRuntimesQuery: { data: [createAgentRuntime()] as AgentRuntime[] | undefined },
    saveProfileMutation: { isPending: false, mutateAsync: vi.fn(async () => {}) },
    agentProbeMutation: { isPending: false, mutateAsync: vi.fn(async () => {}) }
  }
}
const assignment = (
  stage: ModelStageAssignment['stage'],
  agentRuntimeId: string
): ModelStageAssignment => ({
  stage,
  agentRuntimeId,
  agentRuntime: null,
  modelProfileId: null,
  modelProfile: null,
  updatedAt: null
})

describe('model page modal coordination', () => {
  it('creates and closes the editor and forwards the current save handler while its loading state changes', async () => {
    let model = fixture()
    hook.useModelProfiles.mockImplementation(() => model)
    const view = renderWithQueryClient(<AdminModelsPage />)
    fireEvent.click(screen.getByRole('button', { name: /新增 Profile/ }))
    expect(model.setModalProfile).toHaveBeenCalledWith(null)
    model = { ...model, modalProfile: null }
    view.rerender(<AdminModelsPage />)
    expect(await screen.findByText('编辑模型 新建 忙碌 false')).toBeInTheDocument()
    model = { ...model, saveProfileMutation: { ...model.saveProfileMutation, isPending: true } }
    view.rerender(<AdminModelsPage />)
    expect(screen.getByText('编辑模型 新建 忙碌 true')).toBeInTheDocument()
    const next = vi.fn(async () => {})
    model = {
      ...model,
      modalProfile: createModelProfile(),
      saveProfileMutation: { isPending: false, mutateAsync: next }
    }
    view.rerender(<AdminModelsPage />)
    fireEvent.click(screen.getByRole('button', { name: '提交模型' }))
    await waitFor(() => expect(next).toHaveBeenCalledWith({ name: 'saved' }))
    fireEvent.click(screen.getByRole('button', { name: '取消模型' }))
    expect(model.setModalProfile).toHaveBeenLastCalledWith(undefined)
  })

  it('uses the comparison runtime before the knowledge runtime and binds each submission to the selected profile', async () => {
    let model = fixture()
    model.testModalProfile = createModelProfile()
    model.assignments.set('KB_AI_EXTENSION', assignment('KB_AI_EXTENSION', 'kb-runtime'))
    model.assignments.set('TEST_LOG_COMPARE', assignment('TEST_LOG_COMPARE', 'compare-runtime'))
    hook.useModelProfiles.mockImplementation(() => model)
    const view = renderWithQueryClient(<AdminModelsPage />)
    expect(
      await screen.findByText('测试模型 profile-1 默认 compare-runtime 数量 1 忙碌 false')
    ).toBeInTheDocument()
    const updatedAssignments = new Map(model.assignments)
    updatedAssignments.delete('TEST_LOG_COMPARE')
    model = { ...model, assignments: updatedAssignments }
    view.rerender(<AdminModelsPage />)
    expect(
      screen.getByText('测试模型 profile-1 默认 kb-runtime 数量 1 忙碌 false')
    ).toBeInTheDocument()
    model = { ...model, assignments: new Map() }
    view.rerender(<AdminModelsPage />)
    expect(screen.getByText('测试模型 profile-1 默认 none 数量 1 忙碌 false')).toBeInTheDocument()
    model = { ...model, agentRuntimesQuery: { data: undefined } }
    view.rerender(<AdminModelsPage />)
    expect(screen.getByText('测试模型 profile-1 默认 none 数量 0 忙碌 false')).toBeInTheDocument()
    model = { ...model, agentProbeMutation: { ...model.agentProbeMutation, isPending: true } }
    view.rerender(<AdminModelsPage />)
    expect(screen.getByText('测试模型 profile-1 默认 none 数量 0 忙碌 true')).toBeInTheDocument()
    model = { ...model, testModalProfile: createModelProfile({ id: 'profile-2' }) }
    view.rerender(<AdminModelsPage />)
    fireEvent.click(screen.getByRole('button', { name: '提交测试' }))
    await waitFor(() =>
      expect(model.agentProbeMutation.mutateAsync).toHaveBeenCalledWith({
        profileId: 'profile-2',
        agentRuntimeIds: ['selected']
      })
    )
    fireEvent.click(screen.getByRole('button', { name: '取消测试' }))
    expect(model.setTestModalProfile).toHaveBeenCalledWith(null)
  })
})
