import { describe, expect, it } from 'vitest'

import { analysisDiagnostics, isPartialAnalysis, memoryOrderKind } from './analysisResult'

describe('分析结论与执行状态的边界', () => {
  it.each([
    ['FINDING', 'source-candidate', 'SOURCE_CANDIDATE'],
    ['SOURCE_CANDIDATE', 'risk', 'SOURCE_CANDIDATE'],
    ['FINDING', 'inconclusive', 'INCONCLUSIVE'],
    ['FINDING', 'discharged-within-bounded-model', 'DISCHARGED'],
    ['FINDING', 'risk', 'FINDING'],
    ['FINDING', undefined, 'INCONCLUSIVE']
  ])('以 kind/verdict 保守判定 %s / %s', (kind, verdict, expected) => {
    expect(memoryOrderKind(kind!, { conclusion: { verdict } })).toBe(expected)
  })
  it.each([
    { status: 'SUCCEEDED', analysisStatus: 'PARTIAL' },
    { status: 'DONE', analysisComplete: false },
    { status: 'COMPLETE', summary: { analysisComplete: false } },
    { status: 'PARTIAL' }
  ])('完成执行不能覆盖部分分析事实：%j', (value) => {
    expect(isPartialAnalysis(value)).toBe(true)
  })
  it.each([
    {},
    { diagnosticsAggregated: false },
    { diagnosticsAggregated: true, diagnosticCount: null }
  ])('未汇总、缺字段、显式 null 不推导为零：%j', (summary) => {
    expect(
      analysisDiagnostics({ analyzerType: 'MEMORY_ORDER', diagnosticCount: null, summary }).count
    ).toBeNull()
  })
  it('已汇总的明确零值与 frontier 独立保留', () => {
    expect(
      analysisDiagnostics({
        analyzerType: 'MEMORY_ORDER',
        diagnosticCount: 0,
        summary: { diagnosticsAggregated: true, frontierCount: 3 }
      })
    ).toMatchObject({ count: 0, frontierCount: 3 })
  })
  it('不把 summary 的 null 替换成旧的顶层数字', () => {
    expect(
      analysisDiagnostics({
        analyzerType: 'MEMORY_ORDER',
        diagnosticCount: 8,
        summary: { diagnosticsAggregated: true, diagnosticCount: null }
      }).count
    ).toBeNull()
  })
})
