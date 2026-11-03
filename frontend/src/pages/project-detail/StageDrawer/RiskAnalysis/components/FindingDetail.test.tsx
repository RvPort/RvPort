import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { finding as baseFinding } from '../fixtures.test-support'
import { presentFinding } from '../presentation'
import FindingDetail from './FindingDetail'

const finding = { ...baseFinding, analyzerType: 'FLOATING_POINT' as const }

const editor = vi.hoisted(() => vi.fn())
vi.mock('@/components/MonacoEditor', () => ({
  default: (props: Record<string, unknown>) => {
    editor(props)
    return <pre aria-label="源码证据">{String(props.value)}</pre>
  }
}))
vi.mock('@/components/CodeMirrorEditor', () => ({ default: () => <div>原始 JSON</div> }))

describe('专项分析证据工作区', () => {
  it('切换位置复用覆盖范围内的同文件源码并重新高亮，范围外或不同文件不复用', async () => {
    const user = userEvent.setup()
    const model = presentFinding({
      ...finding,
      analyzerType: 'FLOATING_POINT',
      detail: {
        primaryOccurrenceId: 'main',
        occurrences: [
          { id: 'main', location: { file: 'a.cpp', line: 101 } },
          { id: 'related', location: { file: 'a.cpp', line: 104 } },
          { id: 'outside', location: { file: 'a.cpp', line: 200 } },
          { id: 'other', location: { file: 'b.cpp', line: 104 } }
        ]
      }
    })
    render(
      <FindingDetail
        finding={model}
        source={{
          data: {
            findingId: finding.id,
            filePath: 'a.cpp',
            lines: [
              { lineNumber: 101, text: 'result += lane;', highlighted: true },
              { lineNumber: 104, text: 'result += left[i] * right[i];', highlighted: false }
            ]
          }
        }}
      />
    )
    const select = async (label: string) => {
      await user.click(screen.getByRole('combobox', { name: '选择分析命中位置' }))
      await user.click(screen.getByText(label, { selector: '.ant-select-item-option-content' }))
    }
    await select('位置 2 · a.cpp:104')
    expect(screen.getByLabelText('源码证据')).toHaveTextContent('result += left[i] * right[i];')
    expect(editor).toHaveBeenLastCalledWith(
      expect.objectContaining({
        lineNumbers: [101, 104],
        highlightedLineNumbers: [2],
        revealLineNumber: 2
      })
    )
    await select('位置 3 · a.cpp:200')
    expect(screen.queryByLabelText('源码证据')).not.toBeInTheDocument()
    await select('位置 4 · b.cpp:104')
    expect(screen.queryByLabelText('源码证据')).not.toBeInTheDocument()
    await select('位置 1 · a.cpp:101')
    expect(editor).toHaveBeenLastCalledWith(
      expect.objectContaining({ highlightedLineNumbers: [1] })
    )
  })
  it.each(['FLOATING_POINT'] as const)('%s 支持证据步骤联动且不丢失有效源码', (analyzerType) => {
    const model = presentFinding({
      ...finding,
      analyzerType,
      detail: {
        evidenceChain: {
          steps: [
            {
              id: 'source',
              kind: 'sourceObservation',
              title: '命中表达式',
              source: {
                file: 'test.cpp',
                context: {
                  lines: [{ line: 80, text: 'sum += x;' }],
                  highlights: [{ line: 80, startColumn: 1, endColumn: 9 }]
                }
              }
            },
            { id: 'conclusion', kind: 'conclusion', title: '分析判断' }
          ]
        }
      }
    })
    const { rerender } = render(<FindingDetail finding={model} />)
    expect(screen.getByRole('region', { name: '证据工作区' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '源码观察：命中表达式' }))
    expect(screen.getByLabelText('源码证据')).toHaveTextContent('sum += x;')
    fireEvent.click(screen.getByRole('button', { name: '分析结论：分析判断' }))
    expect(screen.getByLabelText('源码证据')).toHaveTextContent('sum += x;')
    expect(screen.getByRole('button', { name: '分析结论：分析判断' })).toHaveAttribute(
      'aria-pressed',
      'true'
    )
    expect(
      within(screen.getByRole('region', { name: '建议下一步' })).getByText(finding.suggestion!)
    ).toBeVisible()
    rerender(<FindingDetail finding={{ ...model, id: 'another' }} />)
    expect(screen.queryByLabelText('源码证据')).not.toBeInTheDocument()
  })
  it('没有结构化证据时提供真实空态，原始数据按需展开', async () => {
    render(<FindingDetail finding={presentFinding({ ...finding, detail: {} })} />)
    expect(screen.getByText('暂无结构化证据路径')).toBeVisible()
    expect(screen.queryByText('原始 JSON')).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('原始扫描数据'))
    expect(await screen.findByText('原始 JSON')).toBeVisible()
    const rawPanel = screen.getByText('原始扫描数据').closest('details')!
    expect(within(rawPanel).getByText('收起详情')).toBeVisible()
    fireEvent.click(screen.getByText('原始扫描数据'))
    await waitFor(() => expect(screen.queryByText('原始 JSON')).not.toBeInTheDocument())
    expect(within(rawPanel).getByText('展开详情')).toBeVisible()
    fireEvent.click(screen.getByText('扫描事实'))
    await waitFor(() => expect(screen.getByText('判断可信度')).toBeVisible())
    expect(
      within(screen.getByText('扫描事实').closest('details')!).getByText('收起详情')
    ).toBeVisible()
  })
})

it('refreshes independent conclusions and supporting facts without retaining another result source', async () => {
  let model = presentFinding({ ...finding, detail: {} })
  let source: import('./FindingSource').FindingSourceState | undefined
  let reportActions: React.ReactNode = <button>加入报告</button>
  const draw = () => <FindingDetail finding={model} source={source} reportActions={reportActions} />
  const view = render(draw())
  const changes: Array<[Partial<typeof model>, string]> = [
    [{ title: '新的迁移判断' }, '新的迁移判断'],
    [{ detail: '更新判断说明' }, '更新判断说明'],
    [{ impact: '影响新目标架构' }, '迁移影响：影响新目标架构'],
    [{ validation: '复核运行结果' }, '验证建议：复核运行结果'],
    [{ validationPlan: [{ label: '编译验证', value: '重新构建' }] }, '编译验证：重新构建'],
    [{ requiresHumanConfirmation: true }, '需人工确认'],
    [{ resultStatus: '已复核' }, '已复核']
  ]
  for (const [patch, text] of changes) {
    model = { ...model, ...patch }
    view.rerender(draw())
    expect(screen.getByText(text)).toBeInTheDocument()
  }
  fireEvent.click(screen.getByText('扫描事实'))
  for (const [patch, text] of [
    [{ code: 'RULE_NEXT' }, 'RULE_NEXT'],
    [{ confidence: '新的可信度说明' }, '新的可信度说明'],
    [{ evidenceOrigin: '补充静态证据' }, '补充静态证据'],
    [{ assumptions: ['依赖编译选项'] }, '依赖编译选项']
  ] as Array<[Partial<typeof model>, string]>) {
    model = { ...model, ...patch }
    view.rerender(draw())
    expect(await screen.findByText(text)).toBeInTheDocument()
  }
  source = {
    data: {
      findingId: model.id,
      filePath: 'new.cpp',
      lines: [{ lineNumber: 3, text: 'current result', highlighted: false }]
    }
  }
  view.rerender(draw())
  expect(screen.getByLabelText('源码证据')).toHaveTextContent('current result')
  expect(screen.getByText('new.cpp')).toBeInTheDocument()
  source = { data: { ...source.data!, findingId: 'different-result' } }
  view.rerender(draw())
  expect(screen.queryByLabelText('源码证据')).not.toBeInTheDocument()
  reportActions = <button>从报告移除</button>
  view.rerender(draw())
  expect(screen.getByRole('button', { name: '从报告移除' })).toBeInTheDocument()
})

it('rejects incomplete cached multiline source when switching occurrences', async () => {
  const model = presentFinding({
    ...finding,
    detail: {
      primaryOccurrenceId: 'main',
      occurrences: [
        { id: 'main', location: { file: 'a.cpp', line: 10 } },
        {
          id: 'range',
          location: {
            file: 'a.cpp',
            span: { start: { line: 20, column: 1 }, end: { line: 22, column: 2 } }
          }
        }
      ]
    }
  })
  const user = userEvent.setup()
  const view = render(
    <FindingDetail
      finding={model}
      source={{
        data: {
          findingId: model.id,
          filePath: 'a.cpp',
          lines: [20, 22].map((lineNumber) => ({
            lineNumber,
            text: `line ${lineNumber}`,
            highlighted: false
          }))
        }
      }}
    />
  )
  await user.click(screen.getByRole('combobox', { name: '选择分析命中位置' }))
  await user.click(
    screen.getByText(/位置 2 · a.cpp:20/, { selector: '.ant-select-item-option-content' })
  )
  expect(screen.queryByLabelText('源码证据')).not.toBeInTheDocument()
  view.rerender(
    <FindingDetail
      finding={model}
      source={{
        data: {
          findingId: model.id,
          filePath: 'a.cpp',
          lines: [20, 21, 22].map((lineNumber) => ({
            lineNumber,
            text: `line ${lineNumber}`,
            highlighted: false
          }))
        }
      }}
    />
  )
  expect(screen.getByLabelText('源码证据')).toHaveTextContent('line 21')
  expect(editor).toHaveBeenLastCalledWith(
    expect.objectContaining({ highlightedLineNumbers: [1, 2, 3] })
  )
})
