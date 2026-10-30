import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ProjectOverviewContext } from '@/pages/project-detail/ProjectOverviewContext'
import { advanceAuthSession } from '@/store/authSession'
import { createEnvironment, createTestQueryClient } from '@/test/test-utils'

import { projectQueryKeys } from '../../queryKeys'
const api = vi.hoisted(() => ({
  createProjectEnvironment: vi.fn(),
  updateProjectEnvironment: vi.fn(),
  testProjectEnvironmentConnection: vi.fn(),
  testSavedProjectEnvironmentConnection: vi.fn(),
  getProjectEnvironments: vi.fn(),
  getProjectEnvironmentScriptExecution: vi.fn(),
  checkProjectEnvironment: vi.fn(),
  runProjectEnvironmentScript: vi.fn()
}))
const { updateProjectRuntimeEnvironments } = vi.hoisted(() => ({
  updateProjectRuntimeEnvironments: vi.fn()
}))
const { form, feedback } = vi.hoisted(() => ({
  form: {
    validateFields: vi.fn(),
    resetFields: vi.fn(),
    setFieldsValue: vi.fn(),
    setFields: vi.fn()
  },
  feedback: { success: vi.fn(), warning: vi.fn(), error: vi.fn() }
}))
vi.mock('antd', () => ({ Form: { useForm: () => [form] } }))
vi.mock('@/api/project/environments', () => api)
vi.mock('@/api/project/runtime-environments', () => ({ updateProjectRuntimeEnvironments }))
vi.mock('@/utils/feedback', () => ({ feedback }))
import { createEnvironmentFormValues } from './model'
import { useEnvironmentDrawer } from './useEnvironmentDrawer'

const environment = createEnvironment({ credentialId: 'credential-1', username: 'root' })
function setup() {
  const client = createTestQueryClient()
  const onClose = vi.fn()
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <ProjectOverviewContext.Provider
        value={{
          projectId: 'project-1',
          isLoading: false,
          isError: false,
          isFetching: false,
          refetch: vi.fn()
        }}
      >
        {children}
      </ProjectOverviewContext.Provider>
    </QueryClientProvider>
  )
  return {
    client,
    onClose,
    ...renderHook(({ visible }) => useEnvironmentDrawer('project-1', visible, onClose), {
      initialProps: { visible: true },
      wrapper
    })
  }
}

describe('environment submission transaction', () => {
  beforeEach(() => {
    api.getProjectEnvironments.mockResolvedValue({ items: [] })
    api.getProjectEnvironmentScriptExecution.mockResolvedValue({ id: 'execution-1' })
    api.createProjectEnvironment.mockResolvedValue(environment)
    api.updateProjectEnvironment.mockResolvedValue(environment)
    api.testProjectEnvironmentConnection.mockResolvedValue({})
    form.validateFields.mockResolvedValue(createEnvironmentFormValues(environment))
    updateProjectRuntimeEnvironments.mockResolvedValue({
      sourceEnvironmentId: environment.id,
      targetEnvironmentId: null
    })
  })

  it('releases row actions after opening and closing an editor while a check is pending', async () => {
    let finish!: () => void
    api.checkProjectEnvironment.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        })
    )
    const { result } = setup()
    let pending!: Promise<void>
    act(() => {
      pending = result.current.handleCheck(environment)
    })
    act(() => result.current.showEdit(environment))
    await act(async () => {
      finish()
      await pending
    })
    act(() => result.current.showList())
    expect(result.current.checkingEnvironmentId).toBeNull()
    await act(async () => {
      await result.current.handleCheck(environment)
    })
    expect(api.checkProjectEnvironment).toHaveBeenCalledTimes(2)
  })

  it.each(['success', 'failure'])(
    'keeps the save locked until verification submission %s, then refreshes and closes once',
    async (outcome) => {
      const { result, client } = setup()
      form.validateFields.mockResolvedValue({
        ...createEnvironmentFormValues(environment),
        credentialMode: 'replace',
        privateKey: 'new-key'
      })
      act(() => result.current.showCreate())
      await act(async () => {
        await result.current.handleTestConnection()
      })
      let finish!: () => void
      api.testSavedProjectEnvironmentConnection.mockImplementation(
        () =>
          new Promise<void>((yes, no) => {
            finish = () => (outcome === 'success' ? yes() : no(new Error('submit failed')))
          })
      )
      const refresh = vi.spyOn(client, 'invalidateQueries')
      let pending!: Promise<void>
      act(() => {
        pending = result.current.handleSave()
      })
      await waitFor(() => expect(api.testSavedProjectEnvironmentConnection).toHaveBeenCalledOnce())
      expect(result.current.isSaving).toBe(true)
      act(() => {
        result.current.showList()
        void result.current.handleSave()
        void result.current.handleTestConnection()
      })
      expect(result.current.view.type).toBe('create')
      expect(api.createProjectEnvironment).toHaveBeenCalledOnce()
      await act(async () => {
        finish()
        await pending
      })
      expect(result.current.busy).toBe(false)
      expect(result.current.view.type).toBe('list')
      expect(refresh).toHaveBeenCalled()
      if (outcome === 'failure') {
        expect(feedback.warning).toHaveBeenCalledOnce()
        expect(feedback.success).toHaveBeenCalledTimes(1)
      } else expect(feedback.success).toHaveBeenLastCalledWith('环境已保存，连接验证已提交')
    }
  )

  it.each(['edit', 'close', 'auth'])('ignores old save completion after %s', async (change) => {
    const { result, rerender } = setup()
    let finish!: (value: typeof environment) => void
    api.updateProjectEnvironment.mockImplementation(
      () =>
        new Promise((yes) => {
          finish = yes
        })
    )
    act(() => result.current.showEdit(environment))
    let pending!: Promise<void>
    act(() => {
      pending = result.current.handleSave()
    })
    await waitFor(() => expect(api.updateProjectEnvironment).toHaveBeenCalledOnce())
    if (change === 'edit') act(() => result.current.showEdit({ ...environment, id: 'env-2' }))
    else if (change === 'close') {
      rerender({ visible: false })
      rerender({ visible: true })
    } else act(() => advanceAuthSession())
    await act(async () => {
      finish(environment)
      await pending
    })
    expect(feedback.success).not.toHaveBeenCalled()
    expect(api.testSavedProjectEnvironmentConnection).not.toHaveBeenCalled()
    if (change === 'edit')
      expect(result.current.view).toMatchObject({ type: 'edit', environment: { id: 'env-2' } })
  })

  it.each(['validate', 'save', 'precheck', 'script'])(
    'keeps the input and unlocks when %s fails',
    async (failure) => {
      const { result } = setup()
      act(() => result.current.showEdit(environment))
      if (failure === 'validate') form.validateFields.mockRejectedValue(new Error('validation'))
      else if (failure === 'save') api.updateProjectEnvironment.mockRejectedValue(new Error('save'))
      else if (failure === 'precheck')
        api.testProjectEnvironmentConnection.mockRejectedValue(new Error('precheck'))
      else {
        act(() => result.current.showRunScript(environment))
        api.runProjectEnvironmentScript.mockRejectedValue(new Error('script'))
      }
      const resetCount = form.resetFields.mock.calls.length
      await act(async () => {
        await expect(
          failure === 'precheck'
            ? result.current.handleTestConnection()
            : failure === 'script'
              ? result.current.handleRunScript()
              : result.current.handleSave()
        ).rejects.toThrow()
      })
      expect(result.current.busy).toBe(false)
      expect(form.resetFields).toHaveBeenCalledTimes(resetCount)
      expect(result.current.view.type).toBe(failure === 'script' ? 'script' : 'edit')
    }
  )

  it('invalidates precheck after editing and rejects a precheck from an old form', async () => {
    const { result } = setup()
    act(() => result.current.showEdit(environment))
    await act(async () => {
      await result.current.handleTestConnection()
    })
    act(() => result.current.handleEnvironmentFormValuesChange())
    await act(async () => {
      await result.current.handleSave()
    })
    expect(api.testSavedProjectEnvironmentConnection).not.toHaveBeenCalled()
    act(() => result.current.showEdit(environment))
    let finish!: () => void
    api.testProjectEnvironmentConnection.mockImplementation(
      () =>
        new Promise<void>((yes) => {
          finish = yes
        })
    )
    let pending!: Promise<void>
    act(() => {
      pending = result.current.handleTestConnection()
    })
    await waitFor(() => expect(api.testProjectEnvironmentConnection).toHaveBeenCalledTimes(2))
    act(() => result.current.showEdit({ ...environment, id: 'env-2' }))
    await act(async () => {
      finish()
      await pending
    })
    await act(async () => {
      await result.current.handleSave()
    })
    expect(api.testSavedProjectEnvironmentConnection).not.toHaveBeenCalled()
  })

  it.each([
    ['COLLECTION', '环境信息采集任务已提交', 'list'],
    ['PRECHECK', '构建前检查任务已提交', 'list'],
    ['CUSTOM', '自定义脚本任务已提交', 'log']
  ] as const)(
    'submits a %s script and opens the %s view',
    async (scriptType, message, nextView) => {
      api.runProjectEnvironmentScript.mockResolvedValue({ id: 'execution-1' })
      form.validateFields.mockResolvedValue({
        scriptType,
        workdir: '/workspace',
        timeout: 60,
        content: 'pwd'
      })
      const { result } = setup()
      act(() => result.current.showRunScript(environment))
      await act(async () => {
        await result.current.handleRunScript()
      })
      expect(api.runProjectEnvironmentScript.mock.calls[0].slice(0, 2)).toEqual([
        'project-1',
        environment.id
      ])
      expect(feedback.success).toHaveBeenCalledWith(message)
      expect(result.current.view.type).toBe(nextView)
      if (nextView === 'log')
        expect(result.current.view).toMatchObject({ executionId: 'execution-1' })
    }
  )

  it('blocks a connection precheck without a login username', async () => {
    form.validateFields.mockResolvedValue({
      ...createEnvironmentFormValues(environment),
      username: ' '
    })
    const { result } = setup()
    act(() => result.current.showCreate())
    await act(async () => {
      await result.current.handleTestConnection()
    })
    expect(form.setFields).toHaveBeenCalledWith([
      { name: 'username', errors: ['请输入登录用户名'] }
    ])
    expect(api.testProjectEnvironmentConnection).not.toHaveBeenCalled()
    expect(result.current.view.type).toBe('create')
  })

  it.each(['SOURCE', 'TARGET', 'JUMP_HOST'] as const)(
    'only sets supported %s runtime environments',
    async (role) => {
      const selected = { ...environment, role }
      const { result } = setup()
      await act(async () => {
        await result.current.handleSetRuntimeEnvironment(selected)
      })
      if (role === 'JUMP_HOST') {
        expect(updateProjectRuntimeEnvironments).not.toHaveBeenCalled()
      } else {
        expect(updateProjectRuntimeEnvironments.mock.calls[0][1]).toEqual(
          role === 'SOURCE'
            ? { sourceEnvironmentId: selected.id }
            : { targetEnvironmentId: selected.id }
        )
        expect(result.current.settingRuntimeEnvironmentId).toBeNull()
      }
    }
  )

  it.each(['SOURCE', 'TARGET'] as const)(
    'updates the cached %s selection without losing the rest of the project overview',
    async (role) => {
      const { client, result } = setup()
      const runtimeEnvironments = {
        sourceEnvironmentId: 'new-source',
        targetEnvironmentId: 'new-target'
      }
      updateProjectRuntimeEnvironments.mockResolvedValue(runtimeEnvironments)
      const overviewKey = projectQueryKeys.overview('project-1')
      client.setQueryData(overviewKey, {
        project: { id: 'project-1', name: '保留的项目' },
        currentContext: { activeSourceRevision: { id: 'source-1' }, runtimeEnvironments: {} },
        recentTasks: [{ id: 'existing-task' }]
      })
      await act(async () => {
        await result.current.handleSetRuntimeEnvironment({ ...environment, role })
      })
      expect(client.getQueryData(overviewKey)).toEqual({
        project: { id: 'project-1', name: '保留的项目' },
        currentContext: { activeSourceRevision: { id: 'source-1' }, runtimeEnvironments },
        recentTasks: [{ id: 'existing-task' }]
      })
      expect(feedback.success).toHaveBeenLastCalledWith(
        role === 'SOURCE' ? '当前源环境已更新' : '当前目标环境已更新'
      )
    }
  )

  it('prevents duplicate runtime changes, preserves the cache on failure, and allows a retry', async () => {
    let reject!: (reason: Error) => void
    updateProjectRuntimeEnvironments.mockImplementationOnce(
      () =>
        new Promise((_, no) => {
          reject = no
        })
    )
    const { client, result } = setup()
    const overviewKey = projectQueryKeys.overview('project-1')
    const original = {
      currentContext: { runtimeEnvironments: { sourceEnvironmentId: 'original' } }
    }
    client.setQueryData(overviewKey, original)
    let pending!: Promise<void>
    act(() => {
      pending = result.current.handleSetRuntimeEnvironment(environment)
      void result.current.handleSetRuntimeEnvironment(environment)
    })
    expect(updateProjectRuntimeEnvironments).toHaveBeenCalledOnce()
    expect(result.current.settingRuntimeEnvironmentId).toBe(environment.id)
    await act(async () => {
      const rejection = expect(pending).rejects.toThrow('offline')
      reject(new Error('offline'))
      await rejection
    })
    expect(client.getQueryData(overviewKey)).toEqual(original)
    expect(result.current.settingRuntimeEnvironmentId).toBeNull()
    expect(feedback.success).not.toHaveBeenCalled()
    await act(async () => {
      await result.current.handleSetRuntimeEnvironment(environment)
    })
    expect(updateProjectRuntimeEnvironments).toHaveBeenCalledTimes(2)
    expect(feedback.success).toHaveBeenCalledOnce()
  })

  it('ignores a runtime selection response after the drawer closes', async () => {
    let finish!: (value: unknown) => void
    updateProjectRuntimeEnvironments.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const { client, result, rerender } = setup()
    const overviewKey = projectQueryKeys.overview('project-1')
    const original = {
      currentContext: { runtimeEnvironments: { sourceEnvironmentId: 'original' } }
    }
    client.setQueryData(overviewKey, original)
    let pending!: Promise<void>
    act(() => {
      pending = result.current.handleSetRuntimeEnvironment(environment)
    })
    rerender({ visible: false })
    act(() => result.current.handleAfterOpenChange(false))
    await act(async () => {
      finish({ sourceEnvironmentId: 'late-response' })
      await pending
    })
    expect(client.getQueryData(overviewKey)).toEqual(original)
    expect(feedback.success).not.toHaveBeenCalled()
    expect(result.current.settingRuntimeEnvironmentId).toBeNull()
  })

  it('does not precheck old values when validation finishes after changing the edited environment', async () => {
    let finish!: (values: ReturnType<typeof createEnvironmentFormValues>) => void
    form.validateFields.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const { result } = setup()
    act(() => result.current.showEdit(environment))
    let pending!: Promise<void>
    act(() => {
      pending = result.current.handleTestConnection()
    })
    act(() => result.current.showEdit({ ...environment, id: 'env-new' }))
    await act(async () => {
      finish(createEnvironmentFormValues(environment))
      await pending
    })
    expect(api.testProjectEnvironmentConnection).not.toHaveBeenCalled()
    expect(feedback.success).not.toHaveBeenCalled()
    expect(result.current.view).toMatchObject({ type: 'edit', environment: { id: 'env-new' } })
  })

  it('does not reopen script logs after a submitted script finishes in a closed drawer', async () => {
    let finish!: (value: unknown) => void
    api.runProjectEnvironmentScript.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    form.validateFields.mockResolvedValue({ scriptType: 'CUSTOM', content: 'pwd' })
    const { result, rerender } = setup()
    act(() => result.current.showRunScript(environment))
    let pending!: Promise<void>
    act(() => {
      pending = result.current.handleRunScript()
    })
    await waitFor(() => expect(api.runProjectEnvironmentScript).toHaveBeenCalledOnce())
    rerender({ visible: false })
    act(() => result.current.handleAfterOpenChange(false))
    await act(async () => {
      finish({ id: 'old-script' })
      await pending
    })
    expect(result.current.view).toEqual({ type: 'list' })
    expect(feedback.success).not.toHaveBeenCalled()
    expect(api.getProjectEnvironmentScriptExecution).not.toHaveBeenCalled()
  })

  it('excludes the edited environment from jump hosts and restores it for a new environment', async () => {
    const jumpHosts = [environment, createEnvironment({ id: 'other-jump', role: 'JUMP_HOST' })]
    api.getProjectEnvironments.mockResolvedValue({ items: jumpHosts })
    const { result } = setup()
    act(() => result.current.showEdit(environment))
    await waitFor(() => expect(result.current.jumpHostOptions).toEqual([jumpHosts[1]]))
    expect(api.getProjectEnvironments).toHaveBeenCalledWith(
      'project-1',
      expect.objectContaining({ role: 'JUMP_HOST', status: 'AVAILABLE' })
    )
    act(() => result.current.showCreate())
    await waitFor(() => expect(result.current.jumpHostOptions).toEqual(jumpHosts))
  })

  it('refreshes the active list, history or execution without invalidating unrelated data', async () => {
    const { client, result } = setup()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    await act(async () => {
      await result.current.handleRefresh()
    })
    expect(invalidate).toHaveBeenLastCalledWith({
      queryKey: projectQueryKeys.environments('project-1')
    })
    act(() => result.current.showHistory(environment))
    await act(async () => {
      await result.current.handleRefresh()
    })
    expect(invalidate).toHaveBeenLastCalledWith({
      queryKey: projectQueryKeys.environmentScriptExecutions('project-1', environment.id)
    })
    act(() =>
      result.current.showLog({ id: 'execution-1' } as Parameters<typeof result.current.showLog>[0])
    )
    await waitFor(() => expect(result.current.executionDetail?.id).toBe('execution-1'))
    const detailCalls = api.getProjectEnvironmentScriptExecution.mock.calls.length
    await act(async () => {
      await result.current.handleRefresh()
    })
    expect(api.getProjectEnvironmentScriptExecution).toHaveBeenCalledTimes(detailCalls + 1)
    expect(invalidate).toHaveBeenCalledTimes(2)
    act(() => result.current.showCreate())
    await act(async () => {
      await result.current.handleRefresh()
    })
    expect(invalidate).toHaveBeenCalledTimes(2)
  })

  it('keeps close locked during a save and releases it once the transaction ends', async () => {
    let finish!: (value: typeof environment) => void
    api.updateProjectEnvironment.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const { result, onClose } = setup()
    act(() => result.current.showEdit(environment))
    let pending!: Promise<void>
    act(() => {
      pending = result.current.handleSave()
    })
    await waitFor(() => expect(api.updateProjectEnvironment).toHaveBeenCalledOnce())
    act(() => result.current.handleClose())
    expect(onClose).not.toHaveBeenCalled()
    await act(async () => {
      finish(environment)
      await pending
    })
    act(() => result.current.handleClose())
    expect(onClose).toHaveBeenCalledOnce()
  })

  it.each(['edit', 'script'] as const)(
    'preserves a dirty %s form on an outdated animation callback and resets it on actual close',
    async (type) => {
      const { result, rerender } = setup()
      act(() => {
        result.current.handleAfterOpenChange(true)
        if (type === 'edit') result.current.showEdit(environment)
        else result.current.showRunScript(environment)
      })
      act(() => {
        if (type === 'edit') result.current.handleEnvironmentFormValuesChange()
        else result.current.handleScriptFormValuesChange()
      })
      expect(result.current.hasUnsavedChanges).toBe(true)
      const resetCount = form.resetFields.mock.calls.length
      act(() => result.current.handleAfterOpenChange(false))
      expect(result.current.hasUnsavedChanges).toBe(true)
      expect(form.resetFields).toHaveBeenCalledTimes(resetCount)
      rerender({ visible: false })
      act(() => result.current.handleAfterOpenChange(false))
      expect(result.current.hasUnsavedChanges).toBe(false)
      expect(result.current.isListReady).toBe(false)
      expect(result.current.view).toEqual({ type: 'list' })
      expect(form.resetFields).toHaveBeenCalledTimes(resetCount + 1)
    }
  )

  it.each(['save', 'script'] as const)(
    'does not submit a %s when validation finishes after closing',
    async (action) => {
      let finish!: (value: unknown) => void
      form.validateFields.mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve
          })
      )
      const { result, rerender } = setup()
      act(() =>
        action === 'save'
          ? result.current.showEdit(environment)
          : result.current.showRunScript(environment)
      )
      let pending!: Promise<void>
      act(() => {
        pending = action === 'save' ? result.current.handleSave() : result.current.handleRunScript()
      })
      rerender({ visible: false })
      act(() => result.current.handleAfterOpenChange(false))
      await act(async () => {
        finish(
          action === 'save'
            ? createEnvironmentFormValues(environment)
            : { scriptType: 'CUSTOM', content: 'pwd' }
        )
        await pending
      })
      expect(api.updateProjectEnvironment).not.toHaveBeenCalled()
      expect(api.runProjectEnvironmentScript).not.toHaveBeenCalled()
      expect(result.current.view).toEqual({ type: 'list' })
    }
  )

  it('does not clear another editor when saved-connection verification completes late', async () => {
    const { result } = setup()
    act(() => result.current.showEdit(environment))
    await act(async () => {
      await result.current.handleTestConnection()
    })
    feedback.success.mockClear()
    let finish!: () => void
    api.testSavedProjectEnvironmentConnection.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        })
    )
    let pending!: Promise<void>
    act(() => {
      pending = result.current.handleSave()
    })
    await waitFor(() => expect(api.testSavedProjectEnvironmentConnection).toHaveBeenCalledOnce())
    act(() => result.current.showEdit({ ...environment, id: 'new-editor' }))
    await act(async () => {
      finish()
      await pending
    })
    expect(result.current.view).toMatchObject({ type: 'edit', environment: { id: 'new-editor' } })
    expect(feedback.success).not.toHaveBeenCalled()
  })

  it('creates an environment without claiming a connection check was performed', async () => {
    const { result } = setup()
    act(() => result.current.showCreate())
    await act(async () => {
      await result.current.handleSave()
    })
    expect(api.createProjectEnvironment).toHaveBeenCalledOnce()
    expect(api.testSavedProjectEnvironmentConnection).not.toHaveBeenCalled()
    expect(feedback.success).toHaveBeenCalledWith('环境已创建')
    expect(result.current.view).toEqual({ type: 'list' })
  })

  it('releases a failed environment check and suppresses its late completion after close', async () => {
    api.checkProjectEnvironment.mockRejectedValueOnce(new Error('check unavailable'))
    const { result, rerender } = setup()
    await act(async () => {
      await expect(result.current.handleCheck(environment)).rejects.toThrow('check unavailable')
    })
    expect(result.current.checkingEnvironmentId).toBeNull()
    let finish!: () => void
    api.checkProjectEnvironment.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve
        })
    )
    let pending!: Promise<void>
    act(() => {
      pending = result.current.handleCheck(environment)
      void result.current.handleCheck(environment)
    })
    expect(api.checkProjectEnvironment).toHaveBeenCalledTimes(2)
    rerender({ visible: false })
    act(() => result.current.handleAfterOpenChange(false))
    await act(async () => {
      finish()
      await pending
    })
    expect(feedback.success).not.toHaveBeenCalled()
    expect(result.current.checkingEnvironmentId).toBeNull()
  })
})
