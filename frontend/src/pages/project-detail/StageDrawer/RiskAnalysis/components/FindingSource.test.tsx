import { StrictMode } from 'react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import FindingSource, { type FindingSourceState } from './FindingSource'

const editor = vi.hoisted(() => vi.fn())
vi.mock('@/components/MonacoEditor', () => ({
  default: (props: Record<string, unknown>) => {
    editor(props)
    return <pre aria-label="源码证据">{String(props.value)}</pre>
  }
}))
const finding = { id: 'finding-1', location: 'src/example.cpp' }
function source(): FindingSourceState {
  return {
    data: {
      findingId: finding.id,
      filePath: finding.location,
      lines: [
        { lineNumber: 80, text: 'float sum = 0;', highlighted: false },
        { lineNumber: 81, text: 'sum += value;', highlighted: true },
        { lineNumber: 85, text: 'return sum;', highlighted: true }
      ]
    }
  }
}
describe('专项源码展示', () => {
  it('keeps the bound source and editor mounted when the parent refreshes the same finding identity in StrictMode', () => {
    const state = source()
    const view = render(
      <StrictMode>
        <FindingSource finding={finding} source={state} />
      </StrictMode>
    )
    const snippet = screen.getByLabelText('源码证据')
    view.rerender(
      <StrictMode>
        <FindingSource finding={{ ...finding }} source={state} />
      </StrictMode>
    )
    expect(screen.getByLabelText('源码证据')).toBe(snippet)
    expect(snippet).toHaveTextContent('sum += value;')
    expect(editor).toHaveBeenLastCalledWith(
      expect.objectContaining({
        lineNumbers: [80, 81, 85],
        highlightedLineNumbers: [2, 3],
        revealLineNumber: 2
      })
    )
  })
  it.each([
    ['SOURCE_REVISION_NOT_FOUND', '本次分析绑定的源码版本不存在。'],
    [
      'FINDING_SOURCE_LOCATION_UNAVAILABLE',
      '本条结果没有有效的源码位置，或行号范围已超出文件。旧结果可尝试重新扫描。'
    ],
    ['FINDING_SOURCE_FILE_NOT_FOUND', '本次分析对应的源码文件或工作副本已不可用。'],
    ['FINDING_SOURCE_PATH_INVALID', '源码路径无效，无法读取上下文。'],
    ['FINDING_SOURCE_FILE_UNREADABLE', '源码文件无法读取，或不是有效的 UTF-8 文本。'],
    ['FINDING_SOURCE_FILE_TOO_LARGE', '源码文件超过 8 MiB，无法读取上下文。'],
    ['CUSTOM_REASON', '源码上下文暂不可用（CUSTOM_REASON）。']
  ])('解释源码不可用原因 %s，且不显示缓存源码', (unavailableReason, expected) => {
    render(<FindingSource finding={finding} source={{ unavailableReason }} />)
    expect(screen.getByText(expected)).toBeInTheDocument()
    expect(screen.queryByLabelText('源码证据')).not.toBeInTheDocument()
  })

  it('独立刷新路径、行高亮和内容后保留当前 finding 身份', () => {
    let current = source()
    const view = render(<FindingSource finding={finding} source={current} />)
    current = { data: { ...current.data!, filePath: 'README.md' } }
    view.rerender(<FindingSource finding={finding} source={current} />)
    expect(editor).toHaveBeenLastCalledWith(
      expect.objectContaining({ language: 'plaintext', lineNumbers: [80, 81, 85] })
    )
    current = {
      data: {
        ...current.data!,
        lines: [{ lineNumber: 5, text: 'updated source', highlighted: false }]
      }
    }
    view.rerender(<FindingSource finding={finding} source={current} />)
    expect(editor).toHaveBeenLastCalledWith(
      expect.objectContaining({
        value: 'updated source',
        highlightedLineNumbers: [],
        revealLineNumber: undefined
      })
    )
    view.rerender(<FindingSource finding={{ ...finding, id: 'new-finding' }} source={current} />)
    expect(screen.queryByLabelText('源码证据')).not.toBeInTheDocument()
    view.rerender(
      <FindingSource
        finding={{ ...finding, id: 'new-finding' }}
        source={{ data: { ...current.data!, findingId: 'new-finding' } }}
      />
    )
    expect(screen.getByLabelText('源码证据')).toHaveTextContent('updated source')
  })
  it('显示真实源码行号，高亮及滚动使用片段内部行号', () => {
    render(<FindingSource finding={finding} source={source()} />)
    expect(editor).toHaveBeenLastCalledWith(
      expect.objectContaining({
        value: 'float sum = 0;\nsum += value;\nreturn sum;',
        lineNumbers: [80, 81, 85],
        highlightedLineNumbers: [2, 3],
        revealLineNumber: 2,
        readOnly: true
      })
    )
  })
  it('切换结果不展示上一条缓存片段', () => {
    render(<FindingSource finding={{ ...finding, id: 'finding-2' }} source={source()} />)
    expect(screen.queryByLabelText('源码证据')).not.toBeInTheDocument()
    expect(screen.getByText('分析结果未提供源码片段')).toBeVisible()
  })
  it('区分空结果与尚未提供源码，缺少位置时不引导按位置查找', () => {
    const state = source()
    state.data!.lines = []
    const { rerender } = render(<FindingSource finding={finding} source={state} />)
    expect(screen.getByText('本条结果没有可用的源码上下文')).toBeVisible()
    rerender(<FindingSource finding={{ ...finding, location: '未提供位置' }} />)
    expect(screen.getByText('分析结果未提供源码片段')).toBeVisible()
    expect(screen.getByText(/本条结果未提供源码位置/)).toBeVisible()
  })
})
