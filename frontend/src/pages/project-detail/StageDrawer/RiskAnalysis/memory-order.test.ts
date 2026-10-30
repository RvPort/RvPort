import { describe, expect, it } from 'vitest'

import { presentRiskEvidence } from './evidencePresentation'
import { finding } from './fixtures.test-support'
import { presentFinding } from './presentation'

describe('内存序 2.0/3.0 只读结果适配', () => {
  it.each(['2.0', '3.0'])(
    '历史 medium/0.5 候选不能成为正式风险或概率，协议 %s',
    (schemaVersion) => {
      const raw = {
        ...finding,
        findingKind: 'SOURCE_CANDIDATE',
        severity: 'MEDIUM',
        confidence: '0.5',
        detail: {
          schemaVersion,
          conclusion: {
            verdict: 'source-candidate',
            evidenceLevel: schemaVersion === '3.0' ? 'source-only' : undefined,
            reason: '缺少执行证据',
            detail: '仅源码模式',
            impact: '不能读取的浮点说明',
            confidence: schemaVersion === '3.0' ? null : 0.5
          }
        }
      }
      const snapshot = JSON.stringify(raw)
      expect(presentFinding(raw)).toMatchObject({
        findingKind: 'SOURCE_CANDIDATE',
        severity: 'info',
        confidence: '未评估',
        detail: '缺少执行证据',
        impact: '仅源码模式'
      })
      expect(JSON.stringify(raw)).toBe(snapshot)
    }
  )
  it.each([
    ['inconclusive', 'INCONCLUSIVE'],
    ['discharged-within-bounded-model', 'DISCHARGED']
  ])('区分 %s 与正式风险', (verdict, findingKind) => {
    expect(
      presentFinding({
        ...finding,
        detail: {
          schemaVersion: '3.0',
          conclusion: { verdict, evidenceLevel: 'incomplete', confidence: null }
        }
      })
    ).toMatchObject({ findingKind, severity: 'info', confidence: '未评估' })
  })
  it('正式风险的 null confidence 表示未评估，英文 reason/detail 可独立回退', () => {
    expect(
      presentFinding({
        ...finding,
        detail: {
          schemaVersion: '3.0',
          conclusion: {
            verdict: 'risk',
            confidence: null,
            reason_en: 'Race observed',
            detail_en: 'Limited to explored executions',
            evidenceLevel: 'bounded-model'
          }
        }
      })
    ).toMatchObject({
      findingKind: 'FINDING',
      severity: 'high',
      confidence: '未评估',
      detail: 'Race observed',
      impact: 'Limited to explored executions',
      evidenceLevel: '受限模型证据'
    })
  })
  it('人工确认纳入与新 run 均不升级候选的结论', () => {
    const candidate = {
      ...finding,
      findingKind: 'SOURCE_CANDIDATE',
      detail: { conclusion: { verdict: 'source-candidate' } }
    }
    const confirmed = presentFinding({
      ...candidate,
      reportInclusion: {
        ...finding.reportInclusion,
        migrationInputStatus: 'CONFIRMED',
        confirmedForNextReport: true
      }
    })
    const next = presentFinding({ ...candidate, id: 'new-finding', runId: 'new-run' })
    expect(confirmed).toMatchObject({
      findingKind: 'SOURCE_CANDIDATE',
      severity: 'info',
      confidence: '未评估'
    })
    expect(next).toMatchObject({ findingKind: confirmed.findingKind, severity: confirmed.severity })
  })
})

describe('内存序主链与图结构', () => {
  const chain = {
    rootStepId: 'source',
    primaryPath: ['source', 'result'],
    status: 'complete',
    steps: [
      { id: 'result', kind: 'conclusion' },
      { id: 'branch', kind: 'atomic-event' },
      { id: 'source', kind: 'source-fact' }
    ],
    edges: [
      { fromStepId: 'source', toStepId: 'result', kind: 'supports' },
      { fromStepId: 'source', toStepId: 'branch', kind: 'alternative-to' }
    ]
  }
  it.each(['source-context', 'runtime-evidence', 'runtime-object', 'future-evidence-name'])(
    '开放的期望证据名称 %s 不误报为未知步骤类型',
    (expectedKind) => {
      const model = presentRiskEvidence(
        {
          evidenceChain: { ...chain, breaks: [{ id: 'gap', expectedKind, detail: '缺少对应证据' }] }
        },
        true
      ).model
      expect(model.evidencePath.warnings).toEqual([])
      expect(model.evidencePath.steps.at(-1)?.reason).toContain(`期望证据：${expectedKind}`)
      expect(model.evidenceChain.status).toBe('partial')
    }
  )
  it('按 primaryPath 而非 steps 顺序展示主链，并保留旁支', () => {
    const model = presentRiskEvidence({ evidenceChain: chain }, true).model
    expect(
      model.evidencePath.steps.slice(0, model.evidencePath.primaryStepCount).map((step) => step.id)
    ).toEqual(['source', 'result'])
    expect(model.evidencePath.branches).toEqual([
      { from: 'source', to: 'branch', kind: 'alternative-to' }
    ])
    expect(model.evidencePath.warnings).toEqual([])
  })
  it.each([
    { primaryPath: ['source', 'missing'] },
    { edges: [...chain.edges, { fromStepId: 'result', toStepId: 'source', kind: 'supports' }] },
    {
      steps: [
        { id: 'source', kind: 'future-kind' },
        ...chain.steps.filter((step) => step.id !== 'source')
      ]
    },
    { primaryPath: ['source', 'source'] }
  ])('异常结构明确警告并降为不完整，不丢弃可验证片段：%j', (patch) => {
    const model = presentRiskEvidence({ evidenceChain: { ...chain, ...patch } }, true).model
    expect(model.evidencePath.warnings?.length).toBeGreaterThan(0)
    expect(model.evidenceChain.status).toBe('partial')
    expect(model.evidencePath.steps.some((step) => step.id === 'source')).toBe(true)
  })
  it('断点保留缺口、期望证据和建议，预算截断不能声称完整', () => {
    const model = presentRiskEvidence(
      {
        evidenceChain: {
          ...chain,
          budget: { truncated: true },
          breaks: [
            {
              id: 'gap',
              reasonCode: 'missing-binding',
              detail: '缺少绑定',
              expectedKind: 'reads-from',
              suggestion: '补充执行轨迹'
            }
          ]
        }
      },
      true
    ).model
    expect(model.evidencePath.steps.at(-1)).toMatchObject({
      kind: 'break',
      reason: '缺少绑定；期望证据：reads-from；补充执行轨迹'
    })
    expect(model.evidencePath.budgetTruncated).toBe(true)
    expect(model.evidenceChain.status).toBe('partial')
  })
  it('浮点 v2 保持既有步骤展示，不套用内存序图校验', () => {
    const model = presentRiskEvidence({ evidenceChain: chain }).model
    expect(model.evidencePath.steps.map((step) => step.id)).toEqual(['result', 'branch', 'source'])
    expect(model.evidencePath.warnings).toBeUndefined()
  })
})
