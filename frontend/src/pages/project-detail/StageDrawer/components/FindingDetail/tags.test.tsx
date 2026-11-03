import { isValidElement } from 'react'
import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'

import { normalizeKnowledgeBaseSeverity, renderFindingStatusTag, renderSeverityTag } from './tags'

it.each([
  ['CRITICAL', '严重', 'critical', '#a8071a'],
  ['HIGH', '高', 'high', 'error'],
  ['MEDIUM', '中', 'medium', 'warning'],
  ['LOW', '低', 'low', 'success'],
  ['INFO', '提示', 'info', 'processing'],
  ['legacy', 'legacy', undefined, undefined],
  ['', '', undefined, undefined],
  [null, '--', undefined, undefined]
] as const)(
  'renders severity %s with its risk meaning and visual tone',
  (value, label, normalized, color) => {
    const tag = renderSeverityTag(value)
    const view = render(<div>{tag}</div>)
    expect(view.container.textContent).toBe(label)
    expect(normalizeKnowledgeBaseSeverity(value)).toBe(normalized)
    if (color) {
      expect(isValidElement<{ color: string }>(tag) && tag.props.color).toBe(color)
    } else {
      expect(view.container.querySelector('.ant-tag')).toBeNull()
    }
  }
)
it.each([
  ['DETECTED', '已发现'],
  ['SUPPRESSED', '已抑制'],
  ['UNKNOWN', 'UNKNOWN'],
  [null, '--']
] as const)('renders status %s preserving unknown states', (value, label) => {
  render(<div>{renderFindingStatusTag(value)}</div>)
  expect(screen.getByText(label)).toBeInTheDocument()
})
