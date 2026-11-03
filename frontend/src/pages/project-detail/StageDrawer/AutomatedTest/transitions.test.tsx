import { act, fireEvent, screen } from '@testing-library/react'
import type { ComponentProps, ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import { createAutomatedTest, createTestComparison } from '@/test/automated-test-fixtures'
import { createBuild } from '@/test/build-fixtures'
import { renderWithQueryClient } from '@/test/test-utils'
import type { ProjectAutomatedTest, ProjectTestComparison } from '@/types/project'

import Test from '.'

const hook = vi.hoisted(() => ({ useAutomatedTestDrawer: vi.fn() }))
vi.mock('./useAutomatedTestDrawer', () => hook)
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
    afterOpenChange
  }: {
    children: ReactNode
    title: ReactNode
    extra: ReactNode
    afterOpenChange: (open: boolean) => void
  }) => (
    <div>
      {title}
      {extra}
      {children}
      <button onClick={() => afterOpenChange(false)}>关闭完成</button>
    </div>
  )
}))
vi.mock('./components/TestRecords', () => ({
  default: ({
    onOpenBatch,
    onOpenTest,
    onCancel
  }: {
    onOpenBatch: (id: string) => void
    onOpenTest: (test: ProjectAutomatedTest) => void
    onCancel: (id: string) => void
  }) => (
    <div>
      <button onClick={() => onOpenBatch('batch-1')}>打开批次</button>
      <button onClick={() => onOpenTest(createAutomatedTest())}>打开测试</button>
      <button onClick={() => onCancel('test-1')}>取消测试</button>
    </div>
  )
}))
vi.mock('./components/TestBatchResults', () => ({
  default: ({
    tests,
    comparison,
    readOnly,
    onShowTestLog,
    onShowComparisonLog,
    onCancel
  }: {
    tests: ProjectAutomatedTest[]
    comparison?: ProjectTestComparison
    readOnly: boolean
    onShowTestLog: (test: ProjectAutomatedTest) => void
    onShowComparisonLog: (comparison: ProjectTestComparison) => void
    onCancel: (id: string) => void
  }) => (
    <div>
      批次测试 {tests.map((test) => test.id).join(',')} 只读 {String(readOnly)}
      <button onClick={() => onShowTestLog(tests[0])}>批次测试日志</button>
      {comparison && <button onClick={() => onShowComparisonLog(comparison)}>批次比较日志</button>}
      <button onClick={() => onCancel('batch-test')}>取消批次任务</button>
    </div>
  )
}))
vi.mock('./components/TestRecordDetail', () => ({
  default: ({
    test,
    onTaskCompleted
  }: {
    test: ProjectAutomatedTest
    onTaskCompleted: () => void
  }) => <button onClick={onTaskCompleted}>测试任务 {test.id}</button>
}))
vi.mock('./components/ComparisonRecordDetail', () => ({
  default: ({
    comparison,
    onTaskCompleted
  }: {
    comparison: ProjectTestComparison
    onTaskCompleted: () => void
  }) => <button onClick={onTaskCompleted}>对比任务 {comparison.id}</button>
}))

function fixture() {
  return {
    ready: true,
    canCreate: true,
    currentError: false,
    currentLoading: false,
    currentHasData: true,
    recordsError: false,
    recordsLoading: false,
    recordsHasData: true,
    latestFreshness: 'CURRENT',
    latestTest: createAutomatedTest(),
    tests: [
      createAutomatedTest(),
      createAutomatedTest({ id: 'unrelated', testBatchId: 'batch-2' })
    ],
    comparisons: [createTestComparison()],
    buildValidationPrerequisite: { status: 'SATISFIED' },
    buildContexts: [
      { role: 'SOURCE', build: createBuild(), reason: '' },
      { role: 'TARGET', build: createBuild({ environment: null }), reason: '' }
    ],
    refetch: vi.fn(),
    cancelMutation: { mutate: vi.fn() },
    batchSubmission: {
      batch: undefined as
        { failed: Array<'SOURCE' | 'TARGET'>; created: Record<string, unknown> } | undefined,
      isPending: false,
      submit: vi.fn(),
      retry: vi.fn(),
      startNewBatch: vi.fn()
    }
  }
}

describe('automated test view coordination', () => {
  it('distinguishes current and record failures, retries them and preserves cached data', () => {
    let state = {
      ...fixture(),
      currentLoading: true,
      recordsLoading: true,
      currentHasData: false,
      recordsHasData: false
    }
    hook.useAutomatedTestDrawer.mockImplementation(() => state)
    const props = { projectId: 'project-1', visible: true, onClose: vi.fn() }
    const view = renderWithQueryClient(<Test {...props} />)
    expect(screen.getByText('正在读取测试条件')).toBeInTheDocument()
    expect(screen.getByText('正在读取测试记录')).toBeInTheDocument()
    state = {
      ...state,
      currentLoading: false,
      recordsLoading: false,
      currentError: true,
      recordsError: true
    }
    view.rerender(<Test {...props} />)
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }))
    fireEvent.click(screen.getByRole('button', { name: '重新加载记录' }))
    expect(state.refetch).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('button', { name: '打开批次' })).not.toBeInTheDocument()
    state = { ...state, currentHasData: true, recordsHasData: true }
    view.rerender(<Test {...props} />)
    expect(screen.getByText('测试条件刷新失败，保留已有内容；暂不能新建测试。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '打开批次' })).toBeInTheDocument()
  })

  it('preserves batch provenance through test and comparison logs and resets configuration after closing', () => {
    const state = fixture()
    hook.useAutomatedTestDrawer.mockReturnValue(state)
    let props: ComponentProps<typeof Test> = {
      visible: true,
      projectId: 'project-1',
      onClose: vi.fn(),
      onDirtyChange: vi.fn()
    }
    const view = renderWithQueryClient(<Test {...props} />)
    fireEvent.change(screen.getByRole('textbox', { name: '测试命令' }), {
      target: { value: 'make check' }
    })
    fireEvent.change(screen.getByRole('spinbutton', { name: '超时时间（秒）' }), {
      target: { value: '120' }
    })
    fireEvent.change(screen.getByRole('textbox', { name: '环境变量（每行 KEY=VALUE，可选）' }), {
      target: { value: 'MODE=test' }
    })
    fireEvent.click(screen.getByRole('button', { name: '运行自动化测试' }))
    expect(state.batchSubmission.submit).toHaveBeenCalledWith(
      { command: 'make check', timeoutSeconds: 120, environmentVariables: { MODE: 'test' } },
      expect.any(Function)
    )
    act(() => state.batchSubmission.submit.mock.calls[0][1]())
    expect(props.onDirtyChange).toHaveBeenLastCalledWith(false)
    fireEvent.click(screen.getByRole('button', { name: '取消测试' }))
    expect(state.cancelMutation.mutate).toHaveBeenCalledWith('test-1')
    fireEvent.click(screen.getByRole('button', { name: '打开测试' }))
    fireEvent.click(screen.getByRole('button', { name: '测试任务 test-source' }))
    expect(state.refetch).toHaveBeenCalledOnce()
    fireEvent.click(screen.getByRole('button', { name: '返回测试记录' }))
    fireEvent.click(screen.getByRole('button', { name: '打开批次' }))
    expect(screen.getByText('批次测试 test-source 只读 true')).toBeInTheDocument()
    expect(screen.queryByText(/unrelated/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '批次比较日志' }))
    expect(hook.useAutomatedTestDrawer).toHaveBeenLastCalledWith('project-1', {
      currentEnabled: false,
      recordsEnabled: false,
      submissionEnabled: true
    })
    fireEvent.click(screen.getByRole('button', { name: '对比任务 comparison-1' }))
    expect(state.refetch).toHaveBeenCalledTimes(2)
    fireEvent.click(screen.getByRole('button', { name: '返回测试记录概览' }))
    fireEvent.click(screen.getByRole('button', { name: '批次测试日志' }))
    fireEvent.click(screen.getByRole('button', { name: '返回测试记录概览' }))
    fireEvent.click(screen.getByRole('button', { name: '取消批次任务' }))
    expect(state.cancelMutation.mutate).toHaveBeenLastCalledWith('batch-test')
    fireEvent.click(screen.getByRole('button', { name: '关闭完成' }))
    expect(screen.getByRole('textbox', { name: '测试命令' })).toHaveValue('ctest')
    expect(screen.getByRole('spinbutton', { name: '超时时间（秒）' })).toHaveValue('3600')
    for (const patch of [
      { projectId: 'project-2' },
      { visible: false },
      { onDirtyChange: vi.fn() },
      {
        stage: {
          id: 'test',
          title: '新测试标题',
          subtitle: '',
          order: 9,
          status: 'pending' as const
        }
      }
    ]) {
      props = { ...props, ...patch }
      view.rerender(<Test {...props} />)
      expect(screen.getByRole('textbox', { name: '测试命令' })).toHaveValue('ctest')
    }
    expect(screen.getByText('新测试标题')).toBeInTheDocument()
  })

  it('keeps failed batch recovery separate from a new submission and explains each blocked build state', () => {
    let state = fixture()
    state.batchSubmission.batch = { failed: ['SOURCE', 'TARGET'], created: {} }
    hook.useAutomatedTestDrawer.mockImplementation(() => state)
    const navigate = vi.fn()
    const view = renderWithQueryClient(
      <Test visible projectId="project-1" onClose={vi.fn()} onStageChange={navigate} />
    )
    expect(screen.getByText(/失败侧：x86、RISC-V/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '运行自动化测试' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '重试失败侧' }))
    fireEvent.click(screen.getByRole('button', { name: '保留已提交任务，开始新批次' }))
    expect(state.batchSubmission.retry).toHaveBeenCalledOnce()
    expect(state.batchSubmission.startNewBatch).toHaveBeenCalledOnce()
    for (const [status, expected] of [
      ['RUNNING', '构建验证进行中'],
      ['FAILED', '最近构建验证未通过'],
      ['MISSING', '尚无有效双环境构建']
    ]) {
      state = { ...state, ready: false, buildValidationPrerequisite: { status } }
      view.rerender(
        <Test visible projectId="project-1" onClose={vi.fn()} onStageChange={navigate} />
      )
      expect(screen.getByText(expected)).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: '前往构建验证' }))
    }
    expect(navigate).toHaveBeenCalledTimes(3)
    expect(navigate).toHaveBeenLastCalledWith('build')
  })
})
