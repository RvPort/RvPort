import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { createAutomatedTest, createTestComparison } from '@/test/automated-test-fixtures'

import TestBatchResults from './TestBatchResults'

function actions() {
  return {
    onShowHistory: vi.fn(),
    onShowTestLog: vi.fn(),
    onShowComparisonLog: vi.fn(),
    onCancel: vi.fn()
  }
}
const roleCard = (role: 'x86' | 'RISC-V') =>
  within(screen.getByText(role, { selector: 'strong' }).closest('article')!)

describe('paired test results', () => {
  it('distinguishes an absent peer and unknown counts from a completed zero count', () => {
    const callbacks = actions()
    const view = render(
      <TestBatchResults {...callbacks} readOnly={false} tests={[createAutomatedTest()]} />
    )
    expect(roleCard('RISC-V').getByText('尚未创建')).toBeInTheDocument()
    expect(roleCard('RISC-V').queryByRole('button', { name: '日志' })).not.toBeInTheDocument()
    expect(roleCard('x86').getAllByText('--')).toHaveLength(3)
    view.rerender(
      <TestBatchResults
        {...callbacks}
        readOnly={false}
        tests={[
          createAutomatedTest({
            status: 'SUCCEEDED',
            exitCode: 0,
            passedCount: 0,
            failedCount: 0,
            skippedCount: 0
          })
        ]}
      />
    )
    expect(roleCard('x86').getAllByText('0')).toHaveLength(3)
    expect(roleCard('x86').queryByText('--')).not.toBeInTheDocument()
    expect(roleCard('x86').queryByRole('button', { name: '取消' })).not.toBeInTheDocument()
  })

  it('cancels only the confirmed side and leaves the other task untouched', async () => {
    const actor = userEvent.setup()
    const callbacks = actions()
    const source = createAutomatedTest()
    const target = createAutomatedTest({
      id: 'test-target',
      testBatchRole: 'TARGET',
      status: 'QUEUED',
      environmentName: '目标环境'
    })
    render(<TestBatchResults {...callbacks} readOnly={false} tests={[target, source]} />)
    await actor.click(roleCard('RISC-V').getByRole('button', { name: '取消' }))
    await actor.click(await screen.findByRole('button', { name: 'Cancel' }))
    expect(callbacks.onCancel).not.toHaveBeenCalled()
    await actor.click(roleCard('RISC-V').getByRole('button', { name: '取消' }))
    await actor.click(await screen.findByRole('button', { name: 'OK' }))
    expect(callbacks.onCancel).toHaveBeenCalledExactlyOnceWith('test-target')
    await actor.click(roleCard('x86').getByRole('button', { name: '日志' }))
    expect(callbacks.onShowTestLog).toHaveBeenCalledExactlyOnceWith(source)
  })

  it.each(['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT'] as const)(
    'retains logs but removes cancellation for %s executions',
    async (status) => {
      const callbacks = actions()
      const test = createAutomatedTest({ status })
      render(<TestBatchResults {...callbacks} readOnly={false} tests={[test]} />)
      expect(screen.queryByRole('button', { name: '取消' })).not.toBeInTheDocument()
      await userEvent.click(roleCard('x86').getByRole('button', { name: '日志' }))
      expect(callbacks.onShowTestLog).toHaveBeenCalledWith(test)
    }
  )

  it('replaces the batch data and action targets when moving to a historical batch', async () => {
    const actor = userEvent.setup()
    const oldCallbacks = actions()
    const currentCallbacks = actions()
    const oldComparison = createTestComparison({ summary: '旧对比摘要', recommendation: '旧建议' })
    const view = render(
      <TestBatchResults
        {...oldCallbacks}
        readOnly={false}
        tests={[createAutomatedTest()]}
        comparison={oldComparison}
      />
    )
    await actor.click(screen.getByRole('button', { name: '查看历史记录' }))
    expect(oldCallbacks.onShowHistory).toHaveBeenCalledOnce()
    const historical = createAutomatedTest({
      id: 'historical-target',
      testBatchRole: 'TARGET',
      environmentName: '历史目标环境',
      status: 'RUNNING',
      passedCount: 19,
      failedCount: 3,
      skippedCount: 2
    })
    const comparison = createTestComparison({
      id: 'historical-comparison',
      testBatchId: 'old-batch',
      status: 'SUCCEEDED',
      overallStatus: 'DIFFERENT_BLOCKING',
      summary: '新的阻断差异',
      recommendation: '检查输出协议',
      riskLevel: 'HIGH'
    })
    view.rerender(
      <TestBatchResults
        {...currentCallbacks}
        readOnly
        tests={[historical]}
        comparison={comparison}
      />
    )
    expect(screen.getByText('只读记录')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '取消' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '查看历史记录' })).not.toBeInTheDocument()
    expect(screen.queryByText('旧对比摘要')).not.toBeInTheDocument()
    expect(screen.queryByText('旧建议')).not.toBeInTheDocument()
    expect(roleCard('x86').getByText('尚未创建')).toBeInTheDocument()
    expect(roleCard('RISC-V').getByText('19')).toBeInTheDocument()
    expect(screen.getByText('检查输出协议')).toBeInTheDocument()
    await actor.click(roleCard('RISC-V').getByRole('button', { name: '日志' }))
    await actor.click(screen.getByRole('button', { name: '查看对比日志' }))
    expect(currentCallbacks.onShowTestLog).toHaveBeenCalledExactlyOnceWith(historical)
    expect(currentCallbacks.onShowComparisonLog).toHaveBeenCalledExactlyOnceWith(comparison)
    expect(oldCallbacks.onShowComparisonLog).not.toHaveBeenCalled()
    expect(oldCallbacks.onShowTestLog).not.toHaveBeenCalled()
  })

  it('updates the comparison from waiting to running, failed and recovered without retaining failure text', () => {
    const callbacks = actions()
    const props = { ...callbacks, readOnly: false, tests: [] }
    const view = render(<TestBatchResults {...props} />)
    expect(screen.getByText('等待双端测试结果')).toBeInTheDocument()
    view.rerender(<TestBatchResults {...props} comparison={createTestComparison()} />)
    expect(screen.getByText('正在生成对比结论')).toBeInTheDocument()
    view.rerender(
      <TestBatchResults
        {...props}
        comparison={createTestComparison({ status: 'FAILED', summary: '原始内部输出' })}
      />
    )
    expect(screen.getByText('测试结果对比失败')).toBeInTheDocument()
    expect(screen.queryByText('原始内部输出')).not.toBeInTheDocument()
    view.rerender(
      <TestBatchResults
        {...props}
        comparison={createTestComparison({
          status: 'SUCCEEDED',
          overallStatus: 'EQUIVALENT',
          summary: '恢复后结果一致',
          riskLevel: 'NONE'
        })}
      />
    )
    expect(screen.getByText('双端测试结果等价')).toBeInTheDocument()
    expect(screen.getByText('恢复后结果一致')).toBeInTheDocument()
    expect(screen.queryByText('测试结果对比失败')).not.toBeInTheDocument()
  })
})
