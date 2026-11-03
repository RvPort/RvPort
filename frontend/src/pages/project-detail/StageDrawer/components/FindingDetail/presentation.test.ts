import { expect, it } from 'vitest'

import { createKnowledgeFinding } from '@/test/knowledge-base-fixtures'

import { findingTitle } from './presentation'

it('prefers the top-level V2 conclusion when legacy and nested titles disagree', () => {
  const finding = createKnowledgeFinding({
    title: '旧字段标题',
    conclusion: { title: '当前结构化结论' },
    evidence: { finding: { conclusion: { title: '旧证据副本' } } }
  })
  expect(findingTitle(finding)).toBe('当前结构化结论')
})
it('reads the nested V2 conclusion when the top-level structured field is empty', () => {
  const finding = createKnowledgeFinding({
    title: '旧字段标题',
    conclusion: {},
    evidence: { finding: { conclusion: { title: '兼容结构化结论' } } }
  })
  expect(findingTitle(finding)).toBe('兼容结构化结论')
})
it('keeps legacy findings identifiable by their title or rule ID without V2 data', () => {
  expect(findingTitle(createKnowledgeFinding({ title: '旧版判断', ruleId: 'rule-x86' }))).toBe(
    '旧版判断'
  )
  expect(findingTitle(createKnowledgeFinding({ ruleId: 'rule-x86' }))).toBe('rule-x86')
})
