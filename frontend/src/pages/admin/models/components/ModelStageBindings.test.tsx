import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { createAgentRuntime, createModelProfile } from '@/test/model-profile-fixtures'
import type { ModelStageAssignment } from '@/types/admin/models'

import ModelStageBindings from './ModelStageBindings'

const query = () => ({ isLoading: false, isError: false, refetch: vi.fn(async () => {}) })
function modelFixture() {
  return {
    assignments: new Map<string, ModelStageAssignment>(),
    assignmentsQuery: query(),
    modelProfilesStateQuery: {
      ...query(),
      data: {
        items: [
          createModelProfile(),
          createModelProfile({ id: 'disabled', name: '停用模型', enabled: false, model: null })
        ]
      }
    },
    agentRuntimesQuery: {
      ...query(),
      data: [
        createAgentRuntime(),
        createAgentRuntime({ id: 'disabled', name: '停用运行时', enabled: false })
      ]
    },
    editingStage: null as string | null,
    setEditingStage: vi.fn(),
    bindingMutation: {
      isPending: false,
      variables: undefined as { stage: string } | undefined,
      mutate: vi.fn()
    }
  }
}
function Binding({ model }: { model: ReturnType<typeof modelFixture> }) {
  // This view only consumes the binding queries and actions; query state itself is tested by the hook suite.
  return (
    <ModelStageBindings
      model={model as unknown as Parameters<typeof ModelStageBindings>[0]['model']}
    />
  )
}

describe('model stage binding view', () => {
  it.each(['assignmentsQuery', 'modelProfilesStateQuery', 'agentRuntimesQuery'] as const)(
    'retries all dependencies after %s fails and preserves loading priority',
    async (field) => {
      let model = modelFixture()
      model[field].isLoading = true
      const view = render(<Binding model={model} />)
      expect(screen.queryByRole('button', { name: /立即配置/ })).not.toBeInTheDocument()
      model = { ...model, [field]: { ...model[field], isLoading: false, isError: true } }
      view.rerender(<Binding model={model} />)
      expect(screen.getByText('无法加载模型绑定或可用选项')).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }))
      expect(model.assignmentsQuery.refetch).toHaveBeenCalledOnce()
      expect(model.modelProfilesStateQuery.refetch).toHaveBeenCalledOnce()
      expect(model.agentRuntimesQuery.refetch).toHaveBeenCalledOnce()
      model = { ...model, [field]: { ...model[field], isError: false } }
      view.rerender(<Binding model={model} />)
      expect(screen.getAllByRole('button', { name: /立即配置/ })).toHaveLength(2)
    }
  )

  it('edits a stage binding, submits its own stage and disables actions during persistence', async () => {
    let model = modelFixture()
    model.assignments.set('TEST_LOG_COMPARE', {
      stage: 'TEST_LOG_COMPARE',
      modelProfileId: 'profile-1',
      agentRuntimeId: 'runtime-1',
      modelProfile: createModelProfile(),
      agentRuntime: createAgentRuntime(),
      updatedAt: null
    })
    const view = render(<Binding model={model} />)
    const section = screen.getByRole('region', { name: '测试结果比较' })
    await userEvent.click(within(section).getByRole('button', { name: /修\s*改/ }))
    expect(model.setEditingStage).toHaveBeenCalledWith('TEST_LOG_COMPARE')
    model = { ...model, editingStage: 'TEST_LOG_COMPARE' }
    view.rerender(<Binding model={model} />)
    await userEvent.click(within(section).getByRole('button', { name: /保\s*存/ }))
    await waitFor(() =>
      expect(model.bindingMutation.mutate).toHaveBeenCalledWith({
        stage: 'TEST_LOG_COMPARE',
        modelProfileId: 'profile-1',
        agentRuntimeId: 'runtime-1'
      })
    )
    model = {
      ...model,
      bindingMutation: {
        ...model.bindingMutation,
        isPending: true,
        variables: { stage: 'TEST_LOG_COMPARE' }
      }
    }
    view.rerender(<Binding model={model} />)
    for (const input of within(section).getAllByRole('combobox')) expect(input).toBeDisabled()
    expect(screen.getByRole('button', { name: /立即配置/ })).toBeDisabled()
    model = { ...model, bindingMutation: { ...model.bindingMutation, isPending: false } }
    view.rerender(<Binding model={model} />)
    await userEvent.click(within(section).getByRole('button', { name: /取\s*消/ }))
    expect(model.setEditingStage).toHaveBeenLastCalledWith(null)
    const next = vi.fn()
    view.rerender(<Binding model={{ ...model, setEditingStage: next }} />)
    await userEvent.click(screen.getByRole('button', { name: /立即配置/ }))
    expect(next).toHaveBeenCalledWith('KB_AI_EXTENSION')
  })
})
