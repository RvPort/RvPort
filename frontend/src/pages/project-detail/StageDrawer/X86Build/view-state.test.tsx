import { fireEvent, screen } from '@testing-library/react'
import type { ComponentProps, ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import { createBuild } from '@/test/build-fixtures'
import { renderWithQueryClient } from '@/test/test-utils'
import type { ProjectBuildValidation } from '@/types/project'

import X86Build from '.'

const boundary = vi.hoisted(() => ({
  useQuery: vi.fn(),
  useBuildReadinessContext: vi.fn(),
  useSessionMutation: vi.fn()
}))
vi.mock('@tanstack/react-query', async (original) => ({
  ...(await original<typeof import('@tanstack/react-query')>()),
  useQuery: boundary.useQuery
}))
vi.mock('../buildReadinessContext', () => ({
  useBuildReadinessContext: boundary.useBuildReadinessContext
}))
vi.mock('@/hooks/useSessionMutation', () => ({ useSessionMutation: boundary.useSessionMutation }))
vi.mock('@/components/CodeMirrorEditor', () => ({
  default: ({ value, onChange }: { value: string; onChange: (value: string) => void }) => (
    <textarea
      aria-label="构建脚本"
      value={value}
      onChange={(event) => onChange(event.target.value)}
    />
  )
}))
vi.mock('../components/StageSurface', () => ({
  default: ({
    children,
    extra,
    title,
    onClose,
    afterOpenChange
  }: {
    children: ReactNode
    extra: ReactNode
    title: ReactNode
    onClose: () => void
    afterOpenChange: (open: boolean) => void
  }) => (
    <div>
      {title}
      {extra}
      {children}
      <button onClick={onClose}>关闭入口</button>
      <button onClick={() => afterOpenChange(false)}>关闭结束</button>
    </div>
  )
}))
vi.mock('./components/X86BuildRecords', () => ({
  default: ({
    currentBuildId,
    onSelect,
    onCancel
  }: {
    currentBuildId?: string
    onSelect: (build: ProjectBuildValidation) => void
    onCancel: (id: string) => void
  }) => (
    <div>
      当前构建 {currentBuildId}
      <button onClick={() => onSelect(createBuild())}>查看构建</button>
      <button onClick={() => onCancel('build-1')}>取消构建</button>
    </div>
  )
}))
vi.mock('./components/BuildLogView', () => ({
  default: ({ onTaskCompleted }: { onTaskCompleted: () => void }) => (
    <button onClick={onTaskCompleted}>日志完成</button>
  )
}))

function setup() {
  let context = {
    context: {
      activeSourceName: '源项目',
      activeSourceRepositoryUrl: 'https://example.invalid/source',
      sourceEnvironment: { id: 'env-1', name: '源环境', architecture: 'x86_64' }
    },
    hasData: true,
    isLoading: false,
    isError: false,
    refetch: vi.fn(async () => [])
  }
  let readiness = {
    data: {
      context: { sourceRevisionId: 'source-1' },
      prerequisites: [
        { key: 'ACTIVE_SOURCE', status: 'SATISFIED' },
        { key: 'SOURCE_ENVIRONMENT', status: 'SATISFIED' }
      ]
    },
    isLoading: false,
    isError: false,
    refetch: vi.fn(async () => ({}))
  }
  let latest = {
    data: { freshness: 'CURRENT', result: createBuild({ command: 'make initial' }) },
    isLoading: false,
    isError: false,
    refetch: vi.fn(async () => ({}))
  }
  const selected = { data: undefined, refetch: vi.fn(async () => ({})) }
  let create = { isPending: false, mutate: vi.fn() }
  const cancel = { isPending: false, mutate: vi.fn() }
  boundary.useBuildReadinessContext.mockImplementation(() => context)
  boundary.useQuery.mockImplementation(({ queryKey }: { queryKey: unknown[] }) =>
    queryKey[0] === 'project-stage-readiness'
      ? readiness
      : queryKey.at(-1) === 'latest'
        ? latest
        : selected
  )
  boundary.useSessionMutation.mockImplementation(({ sessionKey }: { sessionKey: string }) =>
    sessionKey.startsWith('cancel-') ? cancel : create
  )
  return {
    get context() {
      return context
    },
    set context(value) {
      context = value
    },
    get readiness() {
      return readiness
    },
    set readiness(value) {
      readiness = value
    },
    get latest() {
      return latest
    },
    set latest(value) {
      latest = value
    },
    get create() {
      return create
    },
    set create(value) {
      create = value
    },
    selected,
    cancel
  }
}

describe('x86 build view state transitions', () => {
  it('independently renders loading, cached failure and pending submission while keeping the current script', () => {
    const model = setup()
    const close = vi.fn()
    const view = renderWithQueryClient(<X86Build visible projectId="project-1" onClose={close} />)
    model.context = { ...model.context, isLoading: true }
    view.rerender(<X86Build visible projectId="project-1" onClose={close} />)
    expect(screen.getByText('正在读取前置条件')).toBeInTheDocument()
    model.context = { ...model.context, isLoading: false, isError: true }
    view.rerender(<X86Build visible projectId="project-1" onClose={close} />)
    expect(screen.getByRole('button', { name: '开始 x86 构建' })).toBeDisabled()
    expect(
      screen.getByText('前置条件刷新失败，保留已读取内容；暂不能开始构建。')
    ).toBeInTheDocument()
    model.context = { ...model.context, isError: false }
    view.rerender(<X86Build visible projectId="project-1" onClose={close} />)
    fireEvent.change(screen.getByRole('textbox', { name: '构建脚本' }), {
      target: { value: 'make changed' }
    })
    expect(screen.getByRole('textbox', { name: '构建脚本' })).toHaveValue('make changed')
    model.create = { ...model.create, isPending: true }
    view.rerender(<X86Build visible projectId="project-1" onClose={close} />)
    expect(screen.getByRole('button', { name: /开始 x86 构建/ })).toHaveClass('ant-btn-loading')
    model.create = { ...model.create, isPending: false }
    view.rerender(<X86Build visible projectId="project-1" onClose={close} />)
    fireEvent.click(screen.getByRole('button', { name: '取消构建' }))
    expect(model.cancel.mutate).toHaveBeenCalledWith('build-1')
    fireEvent.click(screen.getByRole('button', { name: '查看构建' }))
    fireEvent.click(screen.getByRole('button', { name: '日志完成' }))
    expect(model.selected.refetch).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '返回构建记录' }))
    fireEvent.click(screen.getByRole('button', { name: '关闭结束' }))
    expect(screen.getByRole('textbox', { name: '构建脚本' })).toHaveValue('make initial')
  })

  it('keeps metadata, navigation handlers and current build identity synchronized across independent updates', () => {
    const model = setup()
    let props: ComponentProps<typeof X86Build> = {
      visible: true,
      projectId: 'project-1',
      onClose: vi.fn(),
      onStageChange: vi.fn(),
      onDirtyChange: vi.fn()
    }
    const view = renderWithQueryClient(<X86Build {...props} />)
    for (const patch of [
      { onClose: vi.fn() },
      {
        stage: {
          id: 'x86-build',
          title: '新构建入口',
          subtitle: '',
          order: 3,
          status: 'pending' as const
        }
      },
      { visible: false },
      { visible: true },
      { onDirtyChange: vi.fn() },
      { onStageChange: vi.fn() }
    ]) {
      props = { ...props, ...patch }
      view.rerender(<X86Build {...props} />)
      expect(screen.getByRole('textbox', { name: '构建脚本' })).toHaveValue('make initial')
    }
    expect(screen.getByText('新构建入口')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '关闭入口' }))
    expect(props.onClose).toHaveBeenCalledOnce()
    model.latest = {
      ...model.latest,
      data: {
        ...model.latest.data,
        result: createBuild({ id: 'build-next', command: 'make initial' })
      }
    }
    view.rerender(<X86Build {...props} />)
    expect(screen.getByText('当前构建 build-next')).toBeInTheDocument()
    model.readiness = {
      ...model.readiness,
      data: {
        ...model.readiness.data,
        prerequisites: [
          { key: 'ACTIVE_SOURCE', status: 'MISSING' },
          { key: 'SOURCE_ENVIRONMENT', status: 'MISSING' }
        ]
      }
    }
    view.rerender(<X86Build {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '前往源码管理' }))
    fireEvent.click(screen.getByRole('button', { name: '前往环境管理' }))
    expect(props.onStageChange).toHaveBeenCalledWith('assets')
    expect(props.onStageChange).toHaveBeenCalledWith('env')
  })
})
