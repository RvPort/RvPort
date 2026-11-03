import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { finding, run } from '../fixtures.test-support'
import { presentFinding } from '../presentation'
import AnalysisDiagnostics from './AnalysisDiagnostics'
import FindingDetail from './FindingDetail'
import ResultSummary from './ResultSummary'

vi.mock('@/components/MonacoEditor', () => ({
  default: ({ value, ariaLabel }: { value: string; ariaLabel: string }) => (
    <pre aria-label={ariaLabel}>{value}</pre>
  )
}))
vi.mock('@/components/CodeMirrorEditor', () => ({ default: () => <div /> }))
vi.mock('@/api/project/specialized-analysis', () => ({
  getSpecializedArtifactDownloadUrl: (
    _project: string,
    _stage: string,
    id: string,
    format: string
  ) => `/artifact/${id}/${format}`
}))

describe('内存序诊断与完整性', () => {
  it('诊断二级页显示独立数量、原因、明细、截断和下载入口', () => {
    const diagnostic = { code: 'missing-ir', detail: '缺少 IR 绑定', suggestion: '补充编译信息' }
    render(
      <AnalysisDiagnostics
        run={{
          ...run,
          status: 'PARTIAL',
          findingCount: 0,
          diagnosticCount: 0,
          summary: {
            diagnosticsAggregated: true,
            frontierCount: 2,
            primaryDiagnostic: diagnostic,
            diagnostics: [diagnostic],
            diagnosticsTruncated: true
          }
        }}
        projectId="project"
        stageId="memory-order-analysis"
      />
    )
    const section = screen.getByRole('region', { name: '分析诊断详情' })
    expect(within(section).getByText('当前证据不足以形成完整结论')).toBeVisible()
    expect(within(section).getByText('主要阻碍')).toBeVisible()
    expect(
      within(within(section).getByText('分析诊断').parentElement!).getByText('0')
    ).toBeVisible()
    expect(
      within(within(section).getByText('分析前沿').parentElement!).getByText('2')
    ).toBeVisible()
    expect(
      within(screen.getByRole('region', { name: '诊断记录' })).getByText('缺少 IR 绑定')
    ).toBeVisible()
    expect(screen.getByText('missing-ir')).toBeVisible()
    expect(screen.getByText('建议：补充编译信息')).toBeVisible()
    expect(screen.getByText(/诊断明细已截断/)).toBeVisible()
    expect(screen.getByRole('link', { name: '下载JSON' })).toHaveAttribute(
      'href',
      '/artifact/run/json'
    )
    expect(screen.getByRole('link', { name: '下载审计包' })).toHaveAttribute(
      'href',
      '/artifact/run/audit'
    )
  })
  it('概览只显示主要诊断摘要，通过入口查看完整明细', async () => {
    const open = vi.fn()
    render(
      <ResultSummary
        run={{
          ...run,
          status: 'PARTIAL',
          diagnosticCount: 19,
          summary: {
            diagnosticsAggregated: true,
            frontierCount: 22,
            primaryDiagnostic: {
              code: 'adapter_dependency_incomplete',
              detail: '统一适配器执行失败'
            },
            diagnostics: [
              {
                code: 'adapter_dependency_incomplete',
                detail: '统一适配器执行失败',
                suggestion: '补充构建证据'
              }
            ]
          }
        }}
        stageId="memory-order-analysis"
        summary="部分范围未完成"
        showLogs={false}
        onToggleLogs={vi.fn()}
        onOpenDiagnostics={open}
      />
    )
    expect(screen.getByText(/主要诊断：统一适配器执行失败/)).toBeVisible()
    expect(screen.queryByText('adapter_dependency_incomplete')).not.toBeInTheDocument()
    expect(screen.queryByText('补充构建证据')).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '诊断记录' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '查看诊断明细' }))
    expect(open).toHaveBeenCalledOnce()
  })
  it.each([
    {},
    { diagnosticsAggregated: false },
    { diagnosticsAggregated: true, diagnosticCount: null }
  ])('历史未汇总或 null 显示未知：%j', (summary) => {
    render(<AnalysisDiagnostics run={{ ...run, diagnosticCount: null, summary }} />)
    expect(within(screen.getByText('分析诊断').parentElement!).getByText('未知')).toBeVisible()
    expect(within(screen.getByText('分析前沿').parentElement!).getByText('未知')).toBeVisible()
    expect(screen.getByText(/未知不代表零诊断/)).toBeVisible()
  })
  it('终态 COMPLETE 但 analysisComplete=false 仍提示部分分析完成', () => {
    render(
      <ResultSummary
        run={{ ...run, status: 'COMPLETE', findingCount: 0, analysisComplete: false }}
        stageId="memory-order-analysis"
        summary="零正式风险不代表迁移安全"
        showLogs={false}
        onToggleLogs={vi.fn()}
      />
    )
    expect(screen.getByText('部分分析完成')).toBeVisible()
    expect(screen.getByText('零正式风险不代表迁移安全')).toBeVisible()
  })
})

describe('内存序源码证据隔离', () => {
  it('历史片段缺少 digest 时不充当已验证源码，接口失败原因仍保留', () => {
    const model = presentFinding({
      ...finding,
      detail: {
        conclusion: { verdict: 'risk' },
        fact: {
          primaryLocation: {
            file: 'a.cpp',
            line: 10,
            context: { lines: [{ line: 10, text: 'unverified();' }] }
          }
        }
      }
    })
    const view = render(<FindingDetail finding={model} />)
    expect(screen.queryByLabelText('源码证据')).not.toBeInTheDocument()
    expect(screen.getByText(/源码片段的 Finding、文件、digest/)).toBeVisible()
    view.rerender(
      <FindingDetail finding={model} source={{ unavailableReason: 'SOURCE_REVISION_NOT_FOUND' }} />
    )
    expect(screen.getByText('本次分析绑定的源码版本不存在。')).toBeVisible()
  })
  const source = (line: number, digest: string, content: string) => ({
    file: 'same.cpp',
    sourceSha256: digest,
    span: { start: { line }, end: { line } },
    context: { lines: [{ line, text: content }] }
  })
  it('选择无源码推理或断点时清空代码与补充阅读区，保留明确缺失提示', async () => {
    render(
      <FindingDetail
        finding={presentFinding({
          ...finding,
          detail: {
            schemaVersion: '3.0',
            conclusion: { verdict: 'inconclusive', confidence: null },
            fact: { primaryLocation: source(10, 'digest-a', 'sum += x;') },
            evidenceChain: {
              status: 'partial',
              rootStepId: 'source',
              primaryPath: ['source', 'result'],
              steps: [
                {
                  id: 'source',
                  kind: 'source-fact',
                  title: '命中源码',
                  source: source(10, 'digest-a', 'sum += x;')
                },
                { id: 'result', kind: 'conclusion', title: '缺少执行证据' }
              ],
              edges: [{ fromStepId: 'source', toStepId: 'result', kind: 'supports' }],
              breaks: [
                {
                  id: 'gap',
                  reasonCode: 'missing-binding',
                  detail: '未提供绑定',
                  suggestion: '补充绑定'
                }
              ]
            }
          }
        })}
        source={{
          expectedSourceRevisionId: 'source',
          data: {
            findingId: finding.id,
            runId: finding.runId,
            sourceRevisionId: 'source',
            filePath: 'same.cpp',
            lines: [{ lineNumber: 10, text: 'supplementary_source();', highlighted: true }]
          }
        }}
      />
    )
    await userEvent.click(screen.getByText('查看补充源码（未校验分析 digest）'))
    expect(await screen.findByLabelText('补充源码')).toHaveTextContent('supplementary_source();')
    fireEvent.click(screen.getByRole('button', { name: /命中源码/ }))
    expect(screen.queryByLabelText('补充源码')).not.toBeInTheDocument()
    expect(screen.getByLabelText('源码证据')).toHaveTextContent('sum += x;')
    fireEvent.click(screen.getByRole('button', { name: /缺少执行证据/ }))
    expect(screen.queryByLabelText('源码证据')).not.toBeInTheDocument()
    expect(screen.getByText(/不借用其他位置片段/)).toBeVisible()
    fireEvent.click(screen.getByText('分支与断点'))
    fireEvent.click(screen.getByRole('button', { name: '证据断点：missing-binding' }))
    expect(screen.queryByLabelText('源码证据')).not.toBeInTheDocument()
    expect(screen.getByText(/未提供绑定；补充绑定/)).toBeVisible()
  })
  it('同文件同位置 digest 不同时，不复用主位置缓存；空位置不借用 fact', async () => {
    const user = userEvent.setup()
    const main = source(10, 'digest-a', 'old += x;')
    const other = { ...source(10, 'digest-b', ''), context: { lines: [] } }
    render(
      <FindingDetail
        finding={presentFinding({
          ...finding,
          detail: {
            conclusion: { verdict: 'risk' },
            primaryOccurrenceId: 'main',
            fact: { primaryLocation: main },
            occurrences: [
              { id: 'main', location: main },
              { id: 'other', location: other },
              { id: 'missing', location: null }
            ]
          }
        })}
        source={{
          expectedSourceRevisionId: 'source',
          data: {
            findingId: finding.id,
            runId: finding.runId,
            sourceRevisionId: 'source',
            filePath: 'same.cpp',
            lines: [{ lineNumber: 10, text: 'old += x;', highlighted: true }]
          }
        }}
      />
    )
    expect(screen.getByLabelText('源码证据')).toHaveTextContent('old += x;')
    await user.click(screen.getByRole('combobox', { name: '选择分析命中位置' }))
    await user.click(screen.getByText('位置 2 · same.cpp:10'))
    expect(screen.queryByLabelText('源码证据')).not.toBeInTheDocument()
    await user.click(screen.getByRole('combobox', { name: '选择分析命中位置' }))
    await user.click(screen.getByText('位置 3 · 未提供源码位置'))
    expect(screen.queryByLabelText('源码证据')).not.toBeInTheDocument()
  })
})
