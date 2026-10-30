import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ComponentProps } from 'react'
import { describe, expect, it, vi } from 'vitest'

import type { KnowledgeBaseEvidenceStep } from '@/types/project/knowledge-base'

import type { FindingDetailModel } from './findingDetailPresentation'
import { EvidencePath, SourceEvidence } from './FindingEvidence'

const monacoProps = vi.hoisted(() => vi.fn())

vi.mock('@/components/MonacoEditor', () => ({
  default: (props: Record<string, unknown>) => {
    monacoProps(props)
    return <div data-testid="finding-source-editor" />
  }
}))

const steps: KnowledgeBaseEvidenceStep[] = [
  { id: 'first', title: '服务端步骤一', reason: '服务端说明一', status: 'partial' },
  { id: 'second', title: '服务端步骤二', reason: '服务端说明二', status: 'complete' }
]

function model(): FindingDetailModel {
  return {
    conclusion: {},
    fact: {},
    occurrences: [],
    defaultOccurrenceIndex: 0,
    evidenceChain: {
      steps,
      primaryPath: ['missing'],
      breaks: [{ stage: 'source-upstream', reasonCode: 'missing_input' }],
      budget: { truncated: true }
    },
    evidencePath: { steps }
  }
}

describe('backend evidence steps', () => {
  it('shows every step and its explanation without additional chain warnings', async () => {
    const onSelect = vi.fn()
    render(<EvidencePath model={model()} selectedIndex={null} onSelect={onSelect} />)

    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    expect(screen.getByText('服务端说明一')).toBeInTheDocument()
    expect(screen.getByText('服务端说明二')).toBeInTheDocument()
    expect(screen.getByText('分析不完整')).toBeInTheDocument()
    expect(screen.queryByText(/证据链存在断点/)).not.toBeInTheDocument()
    expect(screen.queryByText(/主证据路径未完整确认/)).not.toBeInTheDocument()
    expect(screen.queryByText(/证据搜索达到分析预算上限/)).not.toBeInTheDocument()
    expect(screen.queryByText('source-upstream')).not.toBeInTheDocument()
    expect(screen.queryByText('缺少完成判断所需的输入')).not.toBeInTheDocument()

    await userEvent.setup().click(screen.getByRole('button', { name: /服务端步骤二/ }))
    expect(onSelect).toHaveBeenCalledWith(1)
  })

  it('shows a neutral empty state when no steps are returned', () => {
    const empty = model()
    empty.evidencePath.steps = []
    render(<EvidencePath model={empty} selectedIndex={null} onSelect={vi.fn()} />)
    expect(screen.getByText('暂无结构化证据路径')).toBeInTheDocument()
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument()
  })
})

describe('Finding source presentation', () => {
  it.each([
    ['config.json', 'json'],
    ['setup.sh', 'shell'],
    ['setup.bash', 'shell'],
    ['src/a.hxx', 'cpp'],
    ['README.md', 'plaintext'],
    [undefined, 'plaintext']
  ])(
    'selects the correct source language for %s and ignores an unmatched highlight',
    (file, language) => {
      render(
        <SourceEvidence
          location={{
            file,
            context: {
              lines: [{ line: 42, text: '  actual source' }],
              highlights: [{ line: 42, startColumn: 100, endColumn: -1 }]
            }
          }}
          highlightText="absent"
          preferCodeContext={false}
          showingEvidenceSource={false}
          onShowPrimaryContext={vi.fn()}
        />
      )
      expect(monacoProps).toHaveBeenLastCalledWith(
        expect.objectContaining({
          language,
          lineNumbers: [42],
          value: '  actual source',
          highlightedLineNumbers: [1],
          highlightedRanges: [
            { startColumn: 16, endColumn: 17, startLineNumber: 1, endLineNumber: 1 }
          ]
        })
      )
    }
  )

  it('retains the last useful source for explanation-only steps and restores primary source on request', async () => {
    const reset = vi.fn()
    const props: ComponentProps<typeof SourceEvidence> = {
      location: {
        file: 'src/a.c',
        context: {
          lines: [{ line: 5, text: 'value = source;' }],
          highlights: [{ line: 5, startColumn: 1, endColumn: 6 }],
          budget: { truncated: true }
        }
      },
      selectedStep: { kind: 'CONCLUSION', title: '判断说明', reason: '依赖调用条件' },
      sourceStep: { title: '保留代码' },
      preferCodeContext: false,
      showingEvidenceSource: true,
      onShowPrimaryContext: reset
    }
    const view = render(<SourceEvidence {...props} />)
    expect(screen.getByText('判断说明')).toBeInTheDocument()
    expect(screen.getByText('所选判断没有直接源码，继续保留最近一次有效代码。')).toBeInTheDocument()
    expect(screen.getByText('代码上下文已按扫描预算截断')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '返回主要代码' }))
    expect(reset).toHaveBeenCalledOnce()
    view.rerender(
      <SourceEvidence
        {...props}
        selectedStep={undefined}
        showingEvidenceSource={false}
        preferCodeContext
        codeContextLoading
      />
    )
    expect(screen.getByText('正在读取代码上下文')).toBeInTheDocument()
    view.rerender(
      <SourceEvidence
        {...props}
        selectedStep={undefined}
        showingEvidenceSource={false}
        preferCodeContext
        codeContextError
      />
    )
    expect(screen.getByText('当前展示扫描器保存的局部片段。')).toBeInTheDocument()
    expect(screen.getByTestId('finding-source-editor')).toBeInTheDocument()
    view.rerender(
      <SourceEvidence
        preferCodeContext
        codeContextError
        showingEvidenceSource={false}
        onShowPrimaryContext={reset}
      />
    )
    expect(screen.getByText('代码上下文读取失败，其余判断依据仍可继续查看。')).toBeInTheDocument()
    view.rerender(
      <SourceEvidence
        preferCodeContext={false}
        showingEvidenceSource={false}
        onShowPrimaryContext={reset}
      />
    )
    expect(screen.getByText('该命中位置没有可用的代码上下文。')).toBeInTheDocument()
  })

  it('does not invent exact ranges when multiline matched evidence is missing or absent from a highlighted line', () => {
    const props: ComponentProps<typeof SourceEvidence> = {
      codeContext: {
        startLine: 1,
        endLine: 3,
        highlightStartLine: 1,
        highlightEndLine: 3,
        filePath: 'a.c',
        lines: [
          { lineNumber: 1, text: 'one', highlighted: true },
          { lineNumber: 2, text: 'two', highlighted: false },
          { lineNumber: 3, text: 'three', highlighted: true }
        ]
      },
      highlightText: 'absent\n\n ',
      preferCodeContext: true,
      showingEvidenceSource: false,
      onShowPrimaryContext: vi.fn()
    }
    const view = render(<SourceEvidence {...props} />)
    expect(monacoProps).toHaveBeenLastCalledWith(
      expect.objectContaining({ highlightedLineNumbers: [1, 3], highlightedRanges: [] })
    )
    view.rerender(<SourceEvidence {...props} highlightText={undefined} />)
    expect(monacoProps).toHaveBeenLastCalledWith(expect.objectContaining({ highlightedRanges: [] }))
  })
  it('keeps the hit line highlighted and underlines the exact matched code', () => {
    render(
      <SourceEvidence
        codeContext={{
          endLine: 12,
          highlightEndLine: 11,
          highlightStartLine: 11,
          lines: [
            { highlighted: false, lineNumber: 10, text: 'function add() {' },
            {
              highlighted: true,
              lineNumber: 11,
              text: '  return _mm256_add_ps(a, b);'
            },
            { highlighted: false, lineNumber: 12, text: '}' }
          ],
          startLine: 10
        }}
        highlightText="_mm256_add_ps"
        location={{ file: 'src/simd.c', span: { start: { line: 11 }, end: { line: 11 } } }}
        preferCodeContext
        showingEvidenceSource={false}
        onShowPrimaryContext={vi.fn()}
      />
    )

    expect(screen.getByTestId('finding-source-editor')).toBeInTheDocument()
    expect(monacoProps).toHaveBeenLastCalledWith(
      expect.objectContaining({
        highlightedLineNumbers: [2],
        highlightedRanges: [
          {
            endColumn: 23,
            endLineNumber: 2,
            startColumn: 10,
            startLineNumber: 2
          }
        ],
        language: 'cpp',
        revealLineNumber: 2,
        wordWrap: 'off'
      })
    )
  })
})
