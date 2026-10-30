import { describe, expect, it } from 'vitest'

import { finding } from './fixtures.test-support'
import { analysisTaskStatus, isAnalysisActive, presentFinding } from './presentation'

describe('analysis result fallback contracts', () => {
  it('uses the supplied English fields when Chinese fields are absent and preserves unknown enumerations', () => {
    const result = presentFinding({
      ...finding,
      analyzerType: 'FLOATING_POINT',
      title: null,
      titleEn: 'English title',
      message: null,
      messageEn: 'English reason',
      suggestion: null,
      suggestionEn: 'English suggestion',
      testSuggestion: null,
      testSuggestionEn: 'English validation',
      filePath: null,
      confidence: null,
      severity: 'CUSTOM',
      detail: {
        conclusion: {
          impact: null,
          impactEn: 'English impact',
          evidenceOrigin: 'CUSTOM',
          assumptions: ['known condition', 2, null],
          validationPlan: {
            inputClasses: ['', ' ', 'custom-input'],
            errorMetrics: ['classification', 'errno', 'exceptions'],
            thresholdSource: 'user-provided'
          }
        }
      }
    })
    expect(result).toMatchObject({
      title: 'English title',
      detail: 'English reason',
      suggestion: 'English suggestion',
      validation: 'English validation',
      impact: 'English impact',
      location: '未提供位置',
      confidence: '未提供',
      severity: 'unknown',
      evidenceOrigin: 'CUSTOM',
      assumptions: ['known condition']
    })
    expect(result.validationPlan).toEqual([
      { label: '验证输入', value: 'custom-input' },
      { label: '误差指标', value: '浮点分类、错误码（errno）、浮点异常' },
      { label: '阈值来源', value: '用户提供' }
    ])
  })

  it.each([null, [], 'unstructured'])(
    'keeps missing or legacy conclusion data neutral: %j',
    (conclusion) => {
      const result = presentFinding({
        ...finding,
        analyzerType: 'FLOATING_POINT',
        title: null,
        titleEn: null,
        findingCode: null,
        message: null,
        messageEn: null,
        suggestion: null,
        suggestionEn: null,
        testSuggestion: null,
        testSuggestionEn: null,
        severity: null,
        resultStatus: null,
        detail: { conclusion }
      })
      expect(result).toMatchObject({
        code: '--',
        title: '未命名分析结果',
        detail: '未提供说明',
        suggestion: '未提供修复建议',
        validation: '',
        validationPlan: [],
        assumptions: [],
        resultStatus: '状态未知',
        severity: 'unknown'
      })
    }
  )

  it.each(['COMPLETE', 'COMPLETE_WITH_RISK', 'PARTIAL'] as const)(
    'maps finished analysis status %s to the task-log terminal state',
    (status) => {
      expect(analysisTaskStatus(status)).toBe('SUCCEEDED')
      expect(isAnalysisActive(status)).toBe(false)
    }
  )
  it.each(['PENDING', 'QUEUED', 'RUNNING'] as const)(
    'keeps active task status %s while determining the execution gate',
    (status) => {
      expect(analysisTaskStatus(status)).toBe(status)
      expect(isAnalysisActive(status)).toBe(true)
    }
  )
})
