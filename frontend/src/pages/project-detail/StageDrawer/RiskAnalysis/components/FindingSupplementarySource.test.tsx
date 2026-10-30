import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { finding } from '../fixtures.test-support'
import { presentFinding } from '../presentation'
import type { FindingSourceState } from './FindingSource'
import FindingSupplementarySource from './FindingSupplementarySource'

vi.mock('@/components/MonacoEditor', () => ({
  default: ({ value, ariaLabel }: { value: string; ariaLabel: string }) => (
    <pre aria-label={ariaLabel}>{value}</pre>
  )
}))

const model = { ...presentFinding(finding), location: 'a.cpp', line: 10 }
const location = { file: 'a.cpp', span: { start: { line: 10 }, end: { line: 11 } } }
const source: FindingSourceState = {
  expectedSourceRevisionId: 'source',
  data: {
    findingId: finding.id,
    runId: finding.runId,
    sourceRevisionId: 'source',
    filePath: 'a.cpp',
    lines: [
      { lineNumber: 10, text: 'sum += x;', highlighted: true },
      { lineNumber: 11, text: 'return sum;', highlighted: false }
    ]
  }
}

describe('真实接口补充源码与分析快照分开', () => {
  it('真实 API 没有 digest，匹配身份后仍可按需阅读，但不能作为源码证据', async () => {
    render(<FindingSupplementarySource finding={model} location={location} source={source} />)
    expect(screen.queryByLabelText('补充源码')).not.toBeInTheDocument()
    await userEvent.click(screen.getByText('查看补充源码（未校验分析 digest）'))
    expect(await screen.findByLabelText('补充源码')).toHaveTextContent('sum += x;')
    expect(screen.queryByLabelText('源码证据')).not.toBeInTheDocument()
    expect(screen.getByText(/不能替代分析时源码证据/)).toBeVisible()
  })
  it.each([
    { findingId: 'other-finding' },
    { runId: 'other-run' },
    { sourceRevisionId: 'other-source' },
    { filePath: 'other.cpp' },
    { lines: [source.data!.lines[0]] }
  ])('身份或位置范围不符不展示补充入口：%j', (patch) => {
    render(
      <FindingSupplementarySource
        finding={model}
        location={location}
        source={{ ...source, data: { ...source.data!, ...patch } }}
      />
    )
    expect(screen.queryByText('查看补充源码（未校验分析 digest）')).not.toBeInTheDocument()
  })
  it('缺少当前执行绑定的源码版本时，不仅凭 Finding ID 展示补充源码', () => {
    render(
      <FindingSupplementarySource
        finding={model}
        location={location}
        source={{ data: source.data }}
      />
    )
    expect(screen.queryByText('查看补充源码（未校验分析 digest）')).not.toBeInTheDocument()
  })
})
