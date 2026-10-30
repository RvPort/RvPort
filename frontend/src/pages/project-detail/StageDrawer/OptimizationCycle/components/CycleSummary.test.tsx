import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { createOptimizationCycle, createStageRun } from '@/test/optimization-cycle-fixtures'

import { CycleSummary } from './CycleSummary'

describe('optimization convergence summary', () => {
  it('updates independently arriving verification checkpoints until archive readiness', () => {
    let cycle = createOptimizationCycle()
    const view = render(<CycleSummary cycle={cycle} />)
    expect(screen.getAllByText('等待构建验证').length).toBeGreaterThan(0)
    cycle = { ...cycle, summary: { ...cycle.summary, build: createStageRun('FAILED') } }
    view.rerender(<CycleSummary cycle={cycle} />)
    expect(screen.getAllByText(/RISC-V 构建当前为失败/).length).toBeGreaterThan(0)
    cycle = { ...cycle, summary: { ...cycle.summary, build: createStageRun('SUCCEEDED') } }
    view.rerender(<CycleSummary cycle={cycle} />)
    expect(screen.getAllByText('等待自动化测试').length).toBeGreaterThan(0)
    cycle = { ...cycle, summary: { ...cycle.summary, test: createStageRun('TIMEOUT') } }
    view.rerender(<CycleSummary cycle={cycle} />)
    expect(screen.getAllByText(/自动化测试当前为超时/).length).toBeGreaterThan(0)
    cycle = { ...cycle, summary: { ...cycle.summary, test: createStageRun('SUCCEEDED') } }
    view.rerender(<CycleSummary cycle={cycle} />)
    expect(screen.getAllByText('缺失测试结果对比数据').length).toBeGreaterThan(0)
    cycle = {
      ...cycle,
      summary: {
        ...cycle.summary,
        testComparison: {
          id: 'comparison',
          taskId: 'task',
          testBatchId: 'batch',
          status: 'SUCCEEDED',
          overallStatus: 'DIFFERENT_BLOCKING',
          riskLevel: 'HIGH',
          summary: '输出有差异',
          recommendation: '对齐输出格式',
          createdAt: '',
          finishedAt: ''
        }
      }
    }
    view.rerender(<CycleSummary cycle={cycle} />)
    expect(screen.getAllByText('对齐输出格式')).toHaveLength(2)
    expect(screen.queryByText('测试对比建议')).not.toBeInTheDocument()
    cycle = {
      ...cycle,
      summary: {
        ...cycle.summary,
        testComparison: {
          ...cycle.summary.testComparison!,
          overallStatus: 'EQUIVALENT',
          summary: '结果已一致'
        }
      }
    }
    view.rerender(<CycleSummary cycle={cycle} />)
    expect(screen.getByText('本轮验证通过')).toBeInTheDocument()
    expect(screen.getByText('当前没有阻塞归档的事项。')).toBeInTheDocument()
    expect(screen.queryByText('测试对比建议')).not.toBeInTheDocument()
  })

  it('refreshes issue counts, fallback risks and report workload without discarding revision metadata', () => {
    let cycle = createOptimizationCycle()
    const view = render(<CycleSummary cycle={cycle} />)
    const scope = () => within(screen.getByText('本轮问题范围').closest('section')!)
    const count = (label: string) => scope().getByText(label).parentElement!
    cycle = {
      ...cycle,
      summary: {
        ...cycle.summary,
        migrationInputs: { total: 12, byStatus: { confirmed: 7, candidate: 3, excluded: 2 } }
      }
    }
    view.rerender(<CycleSummary cycle={cycle} />)
    expect(count('已纳入处理')).toHaveTextContent('7')
    expect(count('待确认')).toHaveTextContent('3')
    expect(count('已排除')).toHaveTextContent('2')
    cycle = {
      ...cycle,
      summary: {
        ...cycle.summary,
        recheck: {
          id: 'recheck',
          taskId: 'task',
          baseSourceRevisionId: 'source-0',
          targetSourceRevisionId: 'source-1',
          status: 'SUCCEEDED',
          diffTotal: 4,
          byDiffStatus: {},
          bySeverity: { critical: 2, HIGH: 3 },
          createdAt: ''
        }
      }
    }
    view.rerender(<CycleSummary cycle={cycle} />)
    expect(scope().getByText('当前仍有 5 项高风险需要关注')).toBeInTheDocument()
    expect(count('复检差异')).toHaveTextContent('4')
    cycle = {
      ...cycle,
      summary: {
        ...cycle.summary,
        report: {
          id: 'report',
          taskId: 'task-report',
          status: 'SUCCEEDED',
          riskLevel: 'HIGH',
          highRiskCount: 1,
          workloadSummary: '需要半天处理风险',
          generatedAt: '',
          createdAt: ''
        }
      }
    }
    view.rerender(<CycleSummary cycle={cycle} />)
    expect(scope().getByText('当前仍有 1 项高风险需要关注')).toBeInTheDocument()
    expect(screen.getByText('需要半天处理风险')).toBeInTheDocument()
    cycle = {
      ...cycle,
      summary: { ...cycle.summary, report: { ...cycle.summary.report!, highRiskCount: 0 } }
    }
    view.rerender(<CycleSummary cycle={cycle} />)
    expect(scope().getByText('当前未发现高风险遗留项')).toBeInTheDocument()
    expect(screen.queryByText('需要半天处理风险')).not.toBeInTheDocument()
    cycle = { ...cycle, cycleNumber: 3 }
    view.rerender(<CycleSummary cycle={cycle} />)
    expect(screen.getByText('第 3 轮优化 · v1')).toBeInTheDocument()
    cycle = { ...cycle, sourceRevision: { ...cycle.sourceRevision, versionLabel: 'v2' } }
    view.rerender(<CycleSummary cycle={cycle} />)
    expect(screen.getByText('第 3 轮优化 · v2')).toBeInTheDocument()
    cycle = { ...cycle, startedAt: '2026-10-28T01:00:00Z' }
    view.rerender(<CycleSummary cycle={cycle} actions={<button>新轮次操作</button>} />)
    expect(screen.getByText('2026-10-28 09:00')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '新轮次操作' })).toBeInTheDocument()
  })
})
