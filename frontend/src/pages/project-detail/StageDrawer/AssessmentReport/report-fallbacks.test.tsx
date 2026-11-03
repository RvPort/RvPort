import { render } from '@testing-library/react'
import { expect, it } from 'vitest'

import {
  reportConfidenceText,
  reportFindingTitleText,
  reportFindingTypeText,
  reportModuleBasisText,
  reportRiskTag,
  reportSourceTypeText
} from './reportPresentation'

it('preserves unfamiliar report tokens and supplies descriptions for absent optional metadata', () => {
  expect(reportFindingTypeText('UNCLASSIFIED')).toBe('未分类问题')
  expect(reportFindingTypeText('  New Finding  ')).toBe('New Finding')
  expect(reportFindingTypeText(null)).toBe('未分类问题')
  expect(reportFindingTitleText('  intrinsic ', 'INTRINSIC')).toBe('内建函数')
  expect(reportFindingTitleText(' Actual issue ', 'INTRINSIC')).toBe('Actual issue')
  expect(reportModuleBasisText(null)).toBe('识别依据未知')
  expect(reportModuleBasisText('  plugin basis  ')).toBe('plugin basis')
  expect(reportConfidenceText(null)).toBe('可信度未知')
  expect(reportConfidenceText('CUSTOM')).toBe('CUSTOM')
  expect(reportSourceTypeText(undefined)).toBe('来源待补充')
  expect(reportSourceTypeText('PLUGIN_RESULT')).toBe('PLUGIN_RESULT')
})
it.each([
  ['LOW', '低', 'success'],
  ['MEDIUM', '中', 'warning'],
  ['INFO', '提示', 'default'],
  [null, '--', 'default']
] as const)('renders %s report risk with its semantic color', (value, label, color) => {
  const view = render(<div>{reportRiskTag(value)}</div>)
  expect(view.container).toHaveTextContent(label)
  expect(view.container.querySelector('.ant-tag')).toHaveClass(`ant-tag-${color}`)
})
