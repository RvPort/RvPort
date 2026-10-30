import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import type { ProjectEvaluationReportSummary } from '@/types/project'

import MemoryOrderReportNotice from './MemoryOrderReportNotice'

const unrelatedReports: ProjectEvaluationReportSummary[] = [
  {},
  { sourceTypeCounts: { KB_FINDING: 2 } },
  {
    sourceTypeCounts: { FLOATING_POINT_FINDING: 2 },
    memoryOrderAnalysis: { analysisStatus: 'UNAVAILABLE' },
    pendingVerification: [],
    pendingVerificationCount: 0
  }
]

describe('报告中的内存序待核实项', () => {
  it('纳入报告的候选仍在待核实区，不按原始 MEDIUM 显示正式风险', async () => {
    render(
      <MemoryOrderReportNotice
        summary={{
          pendingVerificationCount: 1,
          pendingVerification: [
            {
              id: 'candidate',
              issueTitle: '原子源码候选',
              locationLabel: 'a.cpp:10',
              detectedLevel: 'MEDIUM',
              status: 'CONFIRMED'
            }
          ],
          memoryOrderAnalysis: { analysisStatus: 'PARTIAL', limitations: '缺少线程执行轨迹' }
        }}
      />
    )
    expect(screen.getByText('部分分析完成')).toBeVisible()
    expect(screen.getByText('缺少线程执行轨迹')).toBeVisible()
    await userEvent.click(screen.getByText('查看待核实项'))
    expect(screen.getByText(/原子源码候选.*待核实/)).toBeVisible()
    expect(screen.queryByText('中风险')).not.toBeInTheDocument()
    expect(screen.getByText(/人工纳入不代表分析已证实/)).toBeVisible()
  })
  it('历史报告缺字段显示未知，明确零风险也不能证明迁移安全', () => {
    render(<MemoryOrderReportNotice summary={{ sourceTypeCounts: { MEMORY_ORDER_FINDING: 1 } }} />)
    expect(screen.getByText(/待核实项：未知/)).toBeVisible()
    expect(screen.getByText(/零正式风险不代表迁移安全/)).toBeVisible()
  })
  it.each(unrelatedReports)('没有内存序输入与执行记录的报告不显示内存序警告：%j', (summary) => {
    render(<MemoryOrderReportNotice summary={summary} />)
    expect(
      screen.queryByRole('region', { name: '内存序分析局限与待核实项' })
    ).not.toBeInTheDocument()
  })
  it('有内存序输入但分析不可用时，与历史未知明确区分', () => {
    render(
      <MemoryOrderReportNotice
        summary={{
          sourceTypeCounts: { MEMORY_ORDER_FINDING: 1 },
          memoryOrderAnalysis: { analysisStatus: 'UNAVAILABLE' }
        }}
      />
    )
    expect(screen.getByText('内存序分析信息不可用')).toBeVisible()
    expect(screen.queryByText('历史内存序分析完整性未知')).not.toBeInTheDocument()
  })
})
