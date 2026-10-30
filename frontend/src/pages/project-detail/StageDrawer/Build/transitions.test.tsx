import { fireEvent, screen, waitFor } from '@testing-library/react'
import type { ComponentProps, ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import { createBuild } from '@/test/build-fixtures'
import { renderWithQueryClient } from '@/test/test-utils'
import type { ProjectBuildValidation } from '@/types/project'

import Build from '.'

const hooks = vi.hoisted(() => ({ useBuildDrawer: vi.fn(), getProjectBuildValidation: vi.fn() }))
vi.mock('./useBuildDrawer', () => ({
  useBuildDrawer: hooks.useBuildDrawer,
  projectBuildValidationQueryKey: (id?: string) => ['project-build-validations', id, 'VALIDATION']
}))
vi.mock('@/api/project/build-validation', () => ({
  getProjectBuildValidation: hooks.getProjectBuildValidation
}))
vi.mock('@/components/CodeMirrorEditor', () => ({
  default: ({
    value,
    onChange,
    ariaLabel
  }: {
    value: string
    onChange: (value: string) => void
    ariaLabel: string
  }) => (
    <textarea
      aria-label={ariaLabel}
      value={value}
      onChange={(event) => onChange(event.target.value)}
    />
  )
}))
vi.mock('../components/StageSurface', () => ({
  default: ({
    children,
    title,
    extra,
    afterOpenChange,
    onClose
  }: {
    children: ReactNode
    title: ReactNode
    extra: ReactNode
    afterOpenChange: (open: boolean) => void
    onClose: () => void
  }) => (
    <div>
      {title}
      {extra}
      {children}
      <button onClick={onClose}>关闭构建</button>
      <button onClick={() => afterOpenChange(false)}>关闭完成</button>
    </div>
  )
}))
vi.mock('./components/BuildRecords', () => ({
  default: ({
    projectId,
    currentBuildId,
    onSelect,
    onCancel
  }: {
    projectId?: string
    currentBuildId?: string
    onSelect: (record: ProjectBuildValidation) => void
    onCancel: (id: string) => void
  }) => (
    <div>
      记录 {projectId} 当前 {currentBuildId}
      <button onClick={() => onSelect(createBuild({ environment: null }))}>打开构建日志</button>
      <button onClick={() => onCancel('build-1')}>取消记录</button>
    </div>
  )
}))
vi.mock('./components/BuildRecordDetail', () => ({
  default: ({
    build,
    onTaskCompleted
  }: {
    build: ProjectBuildValidation
    onTaskCompleted: () => void
  }) => (
    <div>
      日志状态 {build.status}
      <button onClick={onTaskCompleted}>任务完成</button>
    </div>
  )
}))

function fixture() {
  const environment = {
    id: 'env-source',
    name: '源环境',
    role: 'SOURCE' as const,
    architecture: 'x86_64' as const,
    connectionStatus: 'SUCCESS' as const,
    enabled: true,
    configVersion: 1
  }
  return {
    buildReady: true,
    hasData: true,
    isError: false,
    isLoading: false,
    latestFreshness: 'CURRENT',
    latestBuild: createBuild(),
    generationPrerequisites: ['ACTIVE_SOURCE', 'SOURCE_ENVIRONMENT', 'TARGET_ENVIRONMENT'].map(
      (key) => ({ key, status: 'SATISFIED' })
    ),
    context: {
      activeSourceName: 'RvPort',
      activeSourceRepositoryUrl: 'https://example.invalid/rvport',
      sourceEnvironment: environment,
      targetEnvironment: {
        ...environment,
        id: 'env-target',
        name: '目标环境',
        role: 'TARGET' as const,
        architecture: 'riscv64' as const
      }
    },
    refetch: vi.fn(async () => []),
    buildMutation: { isPending: false, mutate: vi.fn() },
    cancelMutation: { mutate: vi.fn() }
  }
}

describe('build view state boundaries', () => {
  it('shows loading and request failure without losing records, and recovers cached readiness safely', () => {
    let state = fixture()
    state = { ...state, isLoading: true, hasData: false }
    hooks.useBuildDrawer.mockImplementation(() => state)
    const props = { projectId: 'project-1', visible: true, onClose: vi.fn() }
    const view = renderWithQueryClient(<Build {...props} />)
    expect(screen.getByText('正在读取前置条件')).toBeInTheDocument()
    expect(screen.getByText('记录 project-1 当前 build-1')).toBeInTheDocument()
    state = { ...state, isLoading: false, isError: true }
    view.rerender(<Build {...props} />)
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }))
    expect(state.refetch).toHaveBeenCalledOnce()
    state = { ...state, hasData: true }
    view.rerender(<Build {...props} />)
    expect(
      screen.getByText('前置条件刷新失败，保留已读取内容；暂不能开始构建。')
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '开始构建' })).toBeDisabled()
    state = { ...state, isError: false }
    view.rerender(<Build {...props} />)
    expect(screen.getByRole('button', { name: '开始构建' })).toBeEnabled()
  })

  it('updates prerequisite metadata independently, submits user configuration and resets drafts after log close', async () => {
    let state = fixture()
    hooks.useBuildDrawer.mockImplementation(() => state)
    hooks.getProjectBuildValidation.mockResolvedValue(createBuild({ status: 'SUCCEEDED' }))
    let props: ComponentProps<typeof Build> = {
      projectId: 'project-1',
      visible: true,
      onClose: vi.fn(),
      onDirtyChange: vi.fn(),
      onStageChange: vi.fn()
    }
    const view = renderWithQueryClient(<Build {...props} />)
    for (const [patch, expected] of [
      [{ activeSourceName: '新项目' }, '新项目'],
      [{ activeSourceRepositoryUrl: 'https://next.invalid/repo' }, '新项目'],
      [
        { sourceEnvironment: { ...state.context.sourceEnvironment, name: '新源环境' } },
        '新源环境 / x86_64'
      ],
      [
        { targetEnvironment: { ...state.context.targetEnvironment, name: '新目标环境' } },
        '新目标环境 / riscv64'
      ]
    ] as const) {
      state = { ...state, context: { ...state.context, ...patch } }
      view.rerender(<Build {...props} />)
      expect(screen.getByText(expected)).toBeInTheDocument()
    }
    expect(screen.getByRole('link', { name: '新项目' })).toHaveAttribute(
      'href',
      'https://next.invalid/repo'
    )
    fireEvent.change(screen.getByRole('textbox', { name: '构建命令' }), {
      target: { value: 'make check' }
    })
    fireEvent.change(screen.getByRole('spinbutton', { name: '超时时间（秒）' }), {
      target: { value: '120' }
    })
    fireEvent.change(screen.getByRole('textbox', { name: '环境变量（每行 KEY=VALUE，可选）' }), {
      target: { value: 'MODE=verify' }
    })
    fireEvent.click(screen.getByRole('button', { name: '开始构建' }))
    expect(state.buildMutation.mutate).toHaveBeenCalledWith(
      { script: 'make check', timeoutSeconds: 120, environmentVariables: { MODE: 'verify' } },
      expect.objectContaining({ onSuccess: expect.any(Function) })
    )
    fireEvent.click(screen.getByRole('button', { name: '取消记录' }))
    expect(state.cancelMutation.mutate).toHaveBeenCalledWith('build-1')
    fireEvent.click(screen.getByRole('button', { name: '打开构建日志' }))
    expect(screen.getByText('构建构建日志')).toBeInTheDocument()
    expect(hooks.useBuildDrawer).toHaveBeenLastCalledWith('project-1', false)
    await waitFor(() => expect(screen.getByText('日志状态 SUCCEEDED')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '任务完成' }))
    await waitFor(() => expect(hooks.getProjectBuildValidation).toHaveBeenCalledTimes(2))
    fireEvent.click(screen.getByRole('button', { name: '关闭完成' }))
    expect(screen.getByRole('textbox', { name: '构建命令' })).toHaveValue('make test')
    expect(screen.getByRole('spinbutton', { name: '超时时间（秒）' })).toHaveValue('3600')
    expect(screen.getByRole('textbox', { name: '环境变量（每行 KEY=VALUE，可选）' })).toHaveValue(
      ''
    )
    for (const patch of [
      { projectId: 'project-2' },
      { visible: false },
      { onClose: vi.fn() },
      { onDirtyChange: vi.fn() },
      {
        stage: {
          id: 'build',
          title: '新构建标题',
          subtitle: '',
          order: 8,
          status: 'pending' as const
        }
      }
    ]) {
      props = { ...props, ...patch }
      view.rerender(<Build {...props} />)
      expect(hooks.useBuildDrawer).toHaveBeenLastCalledWith(props.projectId, props.visible)
    }
    expect(screen.getByText('新构建标题')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '关闭构建' }))
    expect(props.onClose).toHaveBeenCalledOnce()
  })
})
