import { fireEvent, screen, waitFor } from '@testing-library/react'
import { ConfigProvider, Form } from 'antd'
import type { ComponentProps, ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import { createEnvironment, renderWithQueryClient } from '@/test/test-utils'

import { StagePresentationContext } from '../components/StagePresentationContext'
import Env from '.'
import type { EnvironmentView } from './model'

const hook = vi.hoisted(() => ({ useEnvironmentDrawer: vi.fn() }))
vi.mock('./useEnvironmentDrawer', () => hook)
vi.mock('../components/StageSurface', () => ({
  default: ({
    children,
    title,
    footer
  }: {
    children: ReactNode
    title: ReactNode
    footer: ReactNode
  }) => (
    <div>
      {title}
      {children}
      {footer}
    </div>
  )
}))
vi.mock('./components/List', () => ({
  default: ({ actions, projectId }: { actions: Record<string, unknown>; projectId: string }) => (
    <div>
      项目列表 {projectId}
      <span>
        检查中 {String(actions.checkingEnvironmentId)} 设置中{' '}
        {String(actions.settingRuntimeEnvironmentId)}
      </span>
      {[
        'onCheck',
        'onCreate',
        'onEdit',
        'onHistory',
        'onRefresh',
        'onRunScript',
        'onSetCurrent'
      ].map((key) => (
        <button
          key={key}
          onClick={() =>
            (actions[key] as (environment: ReturnType<typeof createEnvironment>) => void)(
              createEnvironment()
            )
          }
        >
          {key}
        </button>
      ))}
    </div>
  )
}))
vi.mock('./components/Form', () => ({
  default: ({
    mode,
    busy,
    isTestingConnection,
    onTestConnection,
    onValuesChange
  }: {
    mode: string
    busy: boolean
    isTestingConnection: boolean
    onTestConnection: () => void
    onValuesChange: (values: Record<string, string>) => void
  }) => (
    <div>
      环境表单 {mode} 忙碌 {String(busy)} 检查 {String(isTestingConnection)}
      <button onClick={onTestConnection}>测试连接</button>
      <button onClick={() => onValuesChange({ name: 'edited' })}>修改连接字段</button>
    </div>
  )
}))
vi.mock('./components/ScriptForm', () => ({ default: () => <div>脚本表单</div> }))
vi.mock('./components/Executions', () => ({
  default: ({
    projectId,
    onViewLog
  }: {
    projectId: string
    onViewLog: (execution: { id: string }) => void
  }) => <button onClick={() => onViewLog({ id: 'execution-1' })}>执行记录 {projectId}</button>
}))
vi.mock('./components/views', () => ({
  LogView: ({
    loading,
    error,
    onRetry,
    onTaskCompleted
  }: {
    loading: boolean
    error: boolean
    onRetry: () => void
    onTaskCompleted: () => void
  }) => (
    <div>
      执行详情 {String(loading)} {String(error)}
      <button onClick={onRetry}>重试详情</button>
      <button onClick={onTaskCompleted}>完成执行</button>
    </div>
  )
}))

function fixture() {
  return {
    view: { type: 'list' } as EnvironmentView,
    hasUnsavedChanges: false,
    busy: false,
    isListReady: true,
    runtimeEnvironments: undefined,
    checkingEnvironmentId: null as string | null,
    settingRuntimeEnvironmentId: null as string | null,
    isRunningScript: false,
    isSaving: false,
    isTestingConnection: false,
    isExecutionDetailLoading: false,
    isExecutionDetailError: false,
    executionDetail: undefined,
    jumpHostOptions: [],
    isBusy: vi.fn(() => false),
    showList: vi.fn(),
    showCreate: vi.fn(),
    showEdit: vi.fn(),
    showHistory: vi.fn(),
    showRunScript: vi.fn(),
    showLog: vi.fn(),
    handleCheck: vi.fn(),
    handleRefresh: vi.fn(),
    handleSetRuntimeEnvironment: vi.fn(),
    handleRunScript: vi.fn(),
    handleSave: vi.fn(),
    handleClose: vi.fn(),
    handleAfterOpenChange: vi.fn(),
    handleEnvironmentFormValuesChange: vi.fn(),
    handleScriptFormValuesChange: vi.fn(),
    handleTestConnection: vi.fn(),
    refetchExecutionDetail: vi.fn()
  }
}
const environment = createEnvironment()

describe('environment view coordination', () => {
  it('forwards current form actions as independent busy and connection states change', async () => {
    let state = { ...fixture(), view: { type: 'create' } as EnvironmentView }
    hook.useEnvironmentDrawer.mockImplementation(function useDrawer() {
      const [form] = Form.useForm()
      const [scriptForm] = Form.useForm()
      return { ...state, form, scriptForm }
    })
    const tree = () => (
      <ConfigProvider theme={{ token: { motion: false } }}>
        <Env projectId="project-1" visible onClose={vi.fn()} />
      </ConfigProvider>
    )
    const view = renderWithQueryClient(tree())
    await screen.findByRole('button', { name: /保\s*存/ })
    state = { ...state, busy: true }
    view.rerender(tree())
    expect(screen.getByText('环境表单 create 忙碌 true 检查 false')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '返回' })).toBeDisabled()
    state = { ...state, busy: false, isTestingConnection: true }
    view.rerender(tree())
    expect(screen.getByText('环境表单 create 忙碌 false 检查 true')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /保\s*存/ })).toBeDisabled()
    state = { ...state, isTestingConnection: false, isSaving: true }
    view.rerender(tree())
    expect(screen.getByRole('button', { name: /保\s*存/ })).toBeDisabled()
    const save = vi.fn(),
      test = vi.fn(),
      change = vi.fn()
    state = {
      ...state,
      isSaving: false,
      handleSave: save,
      handleTestConnection: test,
      handleEnvironmentFormValuesChange: change
    }
    view.rerender(tree())
    fireEvent.click(screen.getByRole('button', { name: '测试连接' }))
    fireEvent.click(screen.getByRole('button', { name: '修改连接字段' }))
    expect(test).toHaveBeenCalledOnce()
    expect(change).toHaveBeenCalledWith({ name: 'edited' })
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }))
    fireEvent.click(await screen.findByRole('button', { name: '确认保存' }))
    expect(save).toHaveBeenCalledOnce()
    state = {
      ...state,
      view: { type: 'log', environment, executionId: 'exec' },
      isExecutionDetailLoading: true
    }
    view.rerender(tree())
    expect(await screen.findByText('执行详情 true false')).toBeInTheDocument()
    const read = vi.fn()
    state = { ...state, isExecutionDetailLoading: false, refetchExecutionDetail: read }
    view.rerender(tree())
    fireEvent.click(screen.getByRole('button', { name: '重试详情' }))
    expect(read).toHaveBeenCalledOnce()
    state = { ...state, view: { type: 'list' }, isListReady: false }
    view.rerender(tree())
    expect(screen.queryByText('项目列表 project-1')).not.toBeInTheDocument()
  })
  it('forwards independently replaced list actions and project identity to the current list', () => {
    let state = fixture()
    hook.useEnvironmentDrawer.mockImplementation(function useDrawer() {
      const [form] = Form.useForm()
      const [scriptForm] = Form.useForm()
      return { ...state, form, scriptForm }
    })
    let props: ComponentProps<typeof Env> = {
      projectId: 'project-1',
      visible: true,
      onClose: vi.fn(),
      onDirtyChange: vi.fn()
    }
    const view = renderWithQueryClient(<Env {...props} />)
    const mappings = {
      onCheck: 'handleCheck',
      onCreate: 'showCreate',
      onEdit: 'showEdit',
      onHistory: 'showHistory',
      onRefresh: 'handleRefresh',
      onRunScript: 'showRunScript',
      onSetCurrent: 'handleSetRuntimeEnvironment'
    } as const
    for (const [action, key] of Object.entries(mappings)) {
      const next = vi.fn()
      const previous = state[key]
      state = { ...state, [key]: next }
      view.rerender(<Env {...props} />)
      fireEvent.click(screen.getByRole('button', { name: action }))
      expect(next).toHaveBeenCalledWith(environment)
      expect(previous).not.toHaveBeenCalled()
    }
    state = { ...state, checkingEnvironmentId: 'env-1' }
    view.rerender(<Env {...props} />)
    expect(screen.getByText('检查中 env-1 设置中 null')).toBeInTheDocument()
    state = { ...state, settingRuntimeEnvironmentId: 'env-2' }
    view.rerender(<Env {...props} />)
    expect(screen.getByText('检查中 env-1 设置中 env-2')).toBeInTheDocument()
    for (const patch of [
      { projectId: 'project-2' },
      { visible: false },
      { onClose: vi.fn() },
      { onDirtyChange: vi.fn() }
    ]) {
      props = { ...props, ...patch }
      view.rerender(<Env {...props} />)
      expect(hook.useEnvironmentDrawer).toHaveBeenLastCalledWith(
        props.projectId,
        props.visible,
        props.onClose
      )
    }
    expect(screen.getByText('项目列表 project-2')).toBeInTheDocument()
  })

  it('requires an explicit discard for dirty forms and ignores close attempts while busy', async () => {
    let state = {
      ...fixture(),
      view: { type: 'create' } as EnvironmentView,
      hasUnsavedChanges: true
    }
    hook.useEnvironmentDrawer.mockImplementation(function useDrawer() {
      const [form] = Form.useForm()
      const [scriptForm] = Form.useForm()
      return { ...state, form, scriptForm }
    })
    const dirty = vi.fn()
    const tree = () => (
      <ConfigProvider theme={{ token: { motion: false } }}>
        <Env projectId="project-1" visible onClose={vi.fn()} onDirtyChange={dirty} />
      </ConfigProvider>
    )
    const view = renderWithQueryClient(tree())
    await screen.findByText('环境表单 create 忙碌 false 检查 false')
    await screen.findByRole('button', { name: /保\s*存/ })
    expect(dirty).toHaveBeenCalledWith(true)
    state = { ...state, isBusy: vi.fn(() => true) }
    view.rerender(tree())
    fireEvent.click(screen.getByRole('button', { name: '返回' }))
    expect(state.showList).not.toHaveBeenCalled()
    expect(screen.queryByText('放弃未保存的更改？')).not.toBeInTheDocument()
    state = { ...state, isBusy: vi.fn(() => false) }
    view.rerender(tree())
    fireEvent.click(screen.getByRole('button', { name: '返回' }))
    fireEvent.click(await screen.findByRole('button', { name: '继续编辑' }))
    expect(state.showList).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '返回' }))
    fireEvent.click(await screen.findByRole('button', { name: '放弃更改' }))
    expect(state.showList).toHaveBeenCalledOnce()
    state = { ...state, hasUnsavedChanges: false, view: { type: 'edit', environment } }
    view.rerender(tree())
    expect(await screen.findByText('环境表单 edit 忙碌 false 检查 false')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '测试连接' }))
    expect(state.handleTestConnection).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '返回' }))
    expect(state.showList).toHaveBeenCalledTimes(2)
    expect(dirty).toHaveBeenLastCalledWith(false)
  })

  it('keeps execution retries and log back navigation bound to the selected environment in panel mode', async () => {
    let state = { ...fixture(), view: { type: 'history', environment } as EnvironmentView }
    hook.useEnvironmentDrawer.mockImplementation(function useDrawer() {
      const [form] = Form.useForm()
      const [scriptForm] = Form.useForm()
      return { ...state, form, scriptForm }
    })
    const tree = () => (
      <ConfigProvider theme={{ token: { motion: false } }}>
        <StagePresentationContext.Provider value="panel">
          <Env visible projectId="project-1" onClose={vi.fn()} />
        </StagePresentationContext.Provider>
      </ConfigProvider>
    )
    const view = renderWithQueryClient(tree())
    fireEvent.click(await screen.findByRole('button', { name: '执行记录 project-1' }))
    expect(state.showLog).toHaveBeenCalledWith({ id: 'execution-1' })
    state = {
      ...state,
      view: { type: 'log', environment, executionId: 'execution-1' },
      isExecutionDetailError: true
    }
    view.rerender(tree())
    expect(await screen.findByText('执行详情 false true')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重试详情' }))
    fireEvent.click(screen.getByRole('button', { name: '完成执行' }))
    expect(state.refetchExecutionDetail).toHaveBeenCalledTimes(2)
    fireEvent.click(screen.getByRole('button', { name: '返回' }))
    expect(state.showHistory).toHaveBeenCalledWith(environment)
    state = { ...state, view: { type: 'script', environment } }
    view.rerender(tree())
    expect(await screen.findByText('脚本表单')).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '运行脚本' })).toBeInTheDocument()
    )
  })
})
