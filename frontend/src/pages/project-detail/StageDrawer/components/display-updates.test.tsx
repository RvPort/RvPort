import { fireEvent, render, screen } from '@testing-library/react'
import { Form } from 'antd'
import type { ComponentProps, ReactNode } from 'react'
import { expect, it, vi } from 'vitest'

import { AiSuggestionStatus } from '../KnowledgeBase/components/AiSuggestionStatus'
import { ScanConfig } from '../KnowledgeBase/components/ScanConfig'
import CountDistribution from './CountDistribution'
import CurrentExecutionResult, { CurrentExecutionResultGrid } from './CurrentExecutionResult'

it('refreshes independent execution summary fields and removes optional content', () => {
  let title: ReactNode = '执行一',
    status: ReactNode = '成功',
    subtitle: ReactNode = '版本一',
    children: ReactNode = '详细结果',
    actions: ReactNode = <button>下载</button>
  const draw = () => (
    <CurrentExecutionResultGrid>
      <CurrentExecutionResult title={title} status={status} subtitle={subtitle} actions={actions}>
        {children}
      </CurrentExecutionResult>
    </CurrentExecutionResultGrid>
  )
  const view = render(draw())
  title = '执行二'
  view.rerender(draw())
  expect(screen.getByText('执行二')).toBeInTheDocument()
  status = '失败'
  view.rerender(draw())
  expect(screen.getByText('失败')).toBeInTheDocument()
  subtitle = '版本二'
  view.rerender(draw())
  expect(screen.getByText('版本二')).toBeInTheDocument()
  children = '新的结果'
  view.rerender(draw())
  expect(screen.getByText('新的结果')).toBeInTheDocument()
  actions = <button>重试</button>
  view.rerender(draw())
  expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument()
  subtitle = undefined
  view.rerender(draw())
  expect(screen.queryByText('版本二')).not.toBeInTheDocument()
  actions = undefined
  view.rerender(draw())
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  children = undefined
  view.rerender(draw())
  expect(screen.queryByText('新的结果')).not.toBeInTheDocument()
})

it('updates a distribution title independently from its entries and clears zero-count lists', () => {
  let title = '风险统计'
  let entries = [{ key: 'high', label: '高风险', value: 3, tone: 'red' }]
  const draw = () => <CountDistribution title={title} entries={entries} />
  const view = render(draw())
  title = '问题统计'
  view.rerender(draw())
  expect(screen.getByText('问题统计')).toBeInTheDocument()
  entries = [
    { key: 'low', label: '低风险', value: 2, tone: 'blue' },
    { key: 'medium', label: '中风险', value: 1, tone: 'yellow' }
  ]
  view.rerender(draw())
  expect(screen.getByText('低风险')).toBeInTheDocument()
  expect(screen.getByText('3')).toBeInTheDocument()
  entries = []
  view.rerender(draw())
  expect(screen.getByText('0')).toBeInTheDocument()
  expect(screen.queryByText('低风险')).not.toBeInTheDocument()
})

it('shows only actionable prerequisite navigation and keeps the current readiness callback', () => {
  let props: ComponentProps<typeof ScanConfig> = { sourceReady: false, x86BuildReady: false }
  const draw = () => (
    <Form>
      <ScanConfig {...props} />
    </Form>
  )
  const view = render(draw())
  expect(screen.getByRole('alert')).toHaveTextContent('没有可用源码版本')
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  const source = vi.fn(),
    build = vi.fn(),
    ready = vi.fn()
  props = { ...props, onManageSource: source }
  view.rerender(draw())
  fireEvent.click(screen.getByText('前往源码管理'))
  expect(source).toHaveBeenCalledOnce()
  props = { ...props, sourceReady: true }
  view.rerender(draw())
  expect(screen.getByRole('alert')).toHaveTextContent('x86 构建')
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  props = { ...props, onManageX86Build: build }
  view.rerender(draw())
  fireEvent.click(screen.getByText('前往 x86 构建'))
  expect(build).toHaveBeenCalledOnce()
  props = { ...props, onReady: ready }
  view.rerender(draw())
  expect(ready).toHaveBeenCalledOnce()
  props = { ...props, x86BuildReady: true }
  view.rerender(draw())
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(ready).toHaveBeenCalledOnce()
})

it('updates AI retry, generation and progress controls independently', async () => {
  let props: ComponentProps<typeof AiSuggestionStatus> = {
    creating: false,
    error: true,
    loading: false,
    onGenerate: vi.fn(),
    onRetry: vi.fn()
  }
  const draw = () => <AiSuggestionStatus {...props} />
  const view = render(draw())
  const oldRetry = props.onRetry
  props = { ...props, onRetry: vi.fn() }
  view.rerender(draw())
  fireEvent.click(screen.getByText('重新读取'))
  expect(props.onRetry).toHaveBeenCalledOnce()
  expect(oldRetry).not.toHaveBeenCalled()
  props = { ...props, loading: true }
  view.rerender(draw())
  expect(screen.getByLabelText('正在读取 AI 建议状态')).toBeInTheDocument()
  props = { ...props, error: false }
  view.rerender(draw())
  expect(screen.getByRole('button', { name: '生成 AI 建议' })).toBeDisabled()
  props = {
    ...props,
    readiness: {
      ready: true,
      reason: null,
      kbScanRunId: 'scan',
      findingCount: 1,
      modelConfigured: true,
      model: {}
    }
  }
  view.rerender(draw())
  expect(screen.getByRole('button', { name: '生成 AI 建议' })).toBeEnabled()
  const oldGenerate = props.onGenerate
  props = { ...props, onGenerate: vi.fn() }
  view.rerender(draw())
  fireEvent.click(screen.getByRole('button', { name: '生成 AI 建议' }))
  fireEvent.click(await screen.findByRole('button', { name: '开始生成' }))
  expect(props.onGenerate).toHaveBeenCalledOnce()
  expect(oldGenerate).not.toHaveBeenCalled()
  props = { ...props, creating: true }
  view.rerender(draw())
  expect(screen.getByRole('button', { name: /生成中/ })).toBeDisabled()
})
