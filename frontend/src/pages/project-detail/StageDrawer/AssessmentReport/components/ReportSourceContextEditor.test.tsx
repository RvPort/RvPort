import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'

import type { MonacoEditorHighlightRange, MonacoEditorSourceGap } from '@/components/MonacoEditor'
import { renderWithQueryClient } from '@/test/test-utils'
import type {
  ProjectEvaluationReportCodeFinding,
  ProjectEvaluationReportFileContext
} from '@/types/project'

import ReportSourceContextEditor from './ReportSourceContextEditor'

const { getContext } = vi.hoisted(() => ({ getContext: vi.fn() }))
let editorGaps: MonacoEditorSourceGap[] = []
vi.mock('@/api/project/assessment-report', () => ({
  getProjectEvaluationReportFileContext: getContext
}))
vi.mock('@/components/MonacoEditor', () => ({
  default: ({
    sourceGaps,
    value,
    highlightedRanges,
    ...metadata
  }: {
    sourceGaps: MonacoEditorSourceGap[]
    value: string
    highlightedRanges: MonacoEditorHighlightRange[]
    language: string
    lineNumbers: number[]
    highlightedLineNumbers: number[]
    revealLineKey: number
    revealLineNumber?: number
    totalLines?: number
  }) => {
    editorGaps = sourceGaps
    return (
      <div>
        <pre>{value}</pre>
        <output data-testid="editor-metadata">{JSON.stringify(metadata)}</output>
        <output data-testid="highlight-ranges">{JSON.stringify(highlightedRanges)}</output>
        {sourceGaps.map((gap) => (
          <button
            key={gap.id}
            data-error={String(Boolean(gap.error))}
            disabled={gap.loading}
            onClick={gap.onLoad}
          >
            {gap.id}
          </button>
        ))}
      </div>
    )
  }
}))

it('coalesces repeated gap activation before the editor rerenders and caps each range request at 2000 lines', async () => {
  const context: ProjectEvaluationReportFileContext = {
    blocks: [
      {
        startLine: 1,
        endLine: 5000,
        lineCount: 5000,
        kind: 'OMITTED',
        lines: [],
        loadable: true,
        reason: null
      }
    ],
    contentHash: 'hash',
    contextAvailable: true,
    fileKey: 'file',
    findings: [],
    relativePath: 'large.c',
    reportId: 'report',
    sourceRevisionId: 'source',
    totalLines: 5000,
    unavailableReason: null
  }
  let complete!: (value: ProjectEvaluationReportFileContext) => void
  getContext.mockImplementation(
    () =>
      new Promise<ProjectEvaluationReportFileContext>((resolve) => {
        complete = resolve
      })
  )
  renderWithQueryClient(
    <ReportSourceContextEditor
      context={context}
      fileKey="file"
      reportId="report"
      revealFindingKey={0}
    />
  )
  const activate = editorGaps[0].onLoad!
  act(() => {
    activate()
    activate()
  })
  await waitFor(() =>
    expect(getContext).toHaveBeenCalledExactlyOnceWith('report', 'file', {
      fromLine: 1,
      lines: 2000
    })
  )
  expect(screen.getByRole('button', { name: '1-5000' })).toBeDisabled()
  await act(async () =>
    complete({
      ...context,
      blocks: [
        {
          kind: 'CODE',
          startLine: 1,
          endLine: 1,
          lineCount: 1,
          loadable: false,
          reason: null,
          lines: [{ lineNumber: 1, text: 'first line', highlights: [] }]
        }
      ]
    })
  )
  expect(await screen.findByText('first line', { selector: 'pre' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '2-5000' })).toBeEnabled()
})

it('tracks overlapping source range loads independently until each settles', async () => {
  const context: ProjectEvaluationReportFileContext = {
    blocks: [10, 20].map((line) => ({
      startLine: line,
      endLine: line,
      lineCount: 1,
      kind: 'OMITTED',
      lines: [],
      loadable: true,
      reason: null
    })),
    contentHash: 'hash',
    contextAvailable: true,
    fileKey: 'file-1',
    findings: [],
    relativePath: 'example.c',
    reportId: 'report-1',
    sourceRevisionId: 'source-1',
    totalLines: 30,
    unavailableReason: null
  }
  const complete = new Map<number, (value: ProjectEvaluationReportFileContext) => void>()
  getContext.mockImplementation(
    (_report, _file, { fromLine }: { fromLine: number }) =>
      new Promise((resolve) => complete.set(fromLine, resolve))
  )
  renderWithQueryClient(
    <ReportSourceContextEditor
      context={context}
      fileKey="file-1"
      reportId="report-1"
      revealFindingKey={0}
    />
  )
  fireEvent.click(screen.getByRole('button', { name: '10-10' }))
  fireEvent.click(screen.getByRole('button', { name: '20-20' }))
  expect(screen.getByRole('button', { name: '10-10' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '20-20' })).toBeDisabled()
  await waitFor(() => expect(getContext).toHaveBeenCalledTimes(2))
  const result = (line: number): ProjectEvaluationReportFileContext => ({
    ...context,
    blocks: [
      {
        startLine: line,
        endLine: line,
        lineCount: 1,
        kind: 'CODE',
        lines: [{ lineNumber: line, text: `line ${line}`, highlights: [] }],
        loadable: false,
        reason: null
      }
    ]
  })
  await act(async () => complete.get(10)!(result(10)))
  expect(screen.getByRole('button', { name: '20-20' })).toBeDisabled()
  expect(screen.getByText('line 10')).toBeInTheDocument()
  await act(async () => complete.get(20)!(result(20)))
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  expect(screen.getByText(/line 10\s+line 20/)).toBeInTheDocument()
})

it('marks failed and empty source ranges retryable, then merges a successful retry', async () => {
  const context: ProjectEvaluationReportFileContext = {
    blocks: [
      {
        startLine: 10,
        endLine: 12,
        lineCount: 3,
        kind: 'OMITTED',
        lines: [],
        loadable: true,
        reason: null
      }
    ],
    contentHash: 'hash',
    contextAvailable: true,
    fileKey: 'file-1',
    findings: [],
    relativePath: 'example.c',
    reportId: 'report-1',
    sourceRevisionId: 'source-1',
    totalLines: 30,
    unavailableReason: null
  }
  getContext
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValueOnce({ ...context, blocks: [] })
    .mockResolvedValueOnce({
      ...context,
      blocks: [
        {
          startLine: 10,
          endLine: 10,
          lineCount: 1,
          kind: 'CODE',
          loadable: false,
          reason: null,
          lines: [{ lineNumber: 10, text: 'int value = 1;', highlights: [] }]
        }
      ]
    })
  renderWithQueryClient(
    <ReportSourceContextEditor
      context={context}
      fileKey="file-1"
      reportId="report-1"
      revealFindingKey={0}
    />
  )
  fireEvent.click(screen.getByRole('button', { name: '10-12' }))
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '10-12' })).toHaveAttribute('data-error', 'true')
  )
  fireEvent.click(screen.getByRole('button', { name: '10-12' }))
  await waitFor(() => expect(getContext).toHaveBeenCalledTimes(2))
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '10-12' })).toHaveAttribute('data-error', 'true')
  )
  fireEvent.click(screen.getByRole('button', { name: '10-12' }))
  expect(await screen.findByText('int value = 1;')).toBeInTheDocument()
  expect(getContext.mock.calls[2].slice(0, 3)).toEqual([
    'report-1',
    'file-1',
    { fromLine: 10, lines: 3 }
  ])
})

it('highlights explicit columns and matched evidence without including unrelated lines', () => {
  const context: ProjectEvaluationReportFileContext = {
    blocks: [
      {
        startLine: 10,
        endLine: 12,
        lineCount: 3,
        kind: 'CODE',
        loadable: false,
        reason: null,
        lines: [10, 11, 12].map((lineNumber) => ({
          lineNumber,
          text:
            lineNumber === 10
              ? 'int value = 1;'
              : lineNumber === 11
                ? 'return value;'
                : 'unrelated;',
          highlights: []
        }))
      }
    ],
    contentHash: 'hash',
    contextAvailable: true,
    fileKey: 'file-1',
    findings: [],
    relativePath: 'example.c',
    reportId: 'report-1',
    sourceRevisionId: 'source-1',
    totalLines: 30,
    unavailableReason: null
  }
  const finding: ProjectEvaluationReportCodeFinding = {
    id: 'finding-1',
    findingType: 'PORTABILITY',
    issueTitle: 'Unsupported call',
    relativePath: 'example.c',
    sourceRefId: 'source-1',
    recommendation: null,
    locationAvailable: true,
    lineStart: 10,
    lineEnd: 11,
    columnStart: 5,
    columnEnd: 7,
    riskLevel: 'HIGH',
    symbol: 'value',
    sourceDetail: { evidence: { matchedText: 'value\nvalue' } }
  }
  const view = renderWithQueryClient(
    <ReportSourceContextEditor
      context={context}
      fileKey="file-1"
      reportId="report-1"
      revealFindingKey={0}
      selectedFinding={finding}
    />
  )
  expect(JSON.parse(screen.getByTestId('highlight-ranges').textContent ?? '')).toEqual([
    { startColumn: 5, endColumn: 15, startLineNumber: 1, endLineNumber: 1 },
    { startColumn: 1, endColumn: 7, startLineNumber: 2, endLineNumber: 2 }
  ])
  view.rerender(
    <ReportSourceContextEditor
      context={context}
      fileKey="file-1"
      reportId="report-1"
      revealFindingKey={1}
      selectedFinding={{ ...finding, columnStart: null, columnEnd: null }}
    />
  )
  expect(JSON.parse(screen.getByTestId('highlight-ranges').textContent ?? '')).toEqual([
    { startColumn: 5, endColumn: 10, startLineNumber: 1, endLineNumber: 1 },
    { startColumn: 8, endColumn: 13, startLineNumber: 2, endLineNumber: 2 }
  ])
})

it('uses file language and bounded columns, ignores unmatched evidence and retains line-only highlights', () => {
  const context: ProjectEvaluationReportFileContext = {
    blocks: [
      {
        kind: 'CODE',
        startLine: 10,
        endLine: 11,
        lineCount: 2,
        loadable: false,
        reason: null,
        lines: [
          { lineNumber: 10, text: 'abc', highlights: [] },
          { lineNumber: 11, text: 'def', highlights: [] }
        ]
      }
    ],
    contentHash: 'hash',
    contextAvailable: true,
    fileKey: 'file',
    findings: [],
    relativePath: 'config.json',
    reportId: 'report',
    sourceRevisionId: 'source',
    totalLines: null,
    unavailableReason: null
  }
  let selectedFinding: ProjectEvaluationReportCodeFinding | undefined = {
    id: 'finding',
    findingType: 'PORTABILITY',
    issueTitle: 'Issue',
    relativePath: 'config.json',
    sourceRefId: 'source',
    recommendation: null,
    locationAvailable: true,
    lineStart: 10,
    lineEnd: null,
    columnStart: 99,
    columnEnd: null,
    riskLevel: 'HIGH',
    symbol: null,
    sourceDetail: {}
  }
  let path = context.relativePath
  let reveal = 1
  const draw = () => (
    <ReportSourceContextEditor
      context={{ ...context, relativePath: path }}
      fileKey="file"
      reportId="report"
      revealFindingKey={reveal}
      selectedFinding={selectedFinding}
    />
  )
  const view = renderWithQueryClient(draw())
  const ranges = () => JSON.parse(screen.getByTestId('highlight-ranges').textContent!)
  const metadata = () => JSON.parse(screen.getByTestId('editor-metadata').textContent!)
  expect(ranges()).toEqual([{ startColumn: 4, endColumn: 5, startLineNumber: 1, endLineNumber: 1 }])
  expect(metadata()).toMatchObject({
    language: 'json',
    lineNumbers: [10, 11],
    highlightedLineNumbers: [1],
    revealLineKey: 1,
    revealLineNumber: 1
  })
  for (const [file, language] of [
    ['run.sh', 'shell'],
    ['run.bash', 'shell'],
    ['source.CXX', 'cpp'],
    ['README', 'plaintext']
  ]) {
    path = file
    view.rerender(draw())
    expect(metadata().language).toBe(language)
  }
  for (const evidence of [undefined, [], { matchedText: 42 }, { matchedText: 'not present' }]) {
    selectedFinding = { ...selectedFinding!, columnStart: null, sourceDetail: { evidence } }
    view.rerender(draw())
    expect(ranges()).toEqual([])
    expect(metadata().highlightedLineNumbers).toEqual([1])
  }
  selectedFinding = { ...selectedFinding!, symbol: 'abc', sourceDetail: {} }
  view.rerender(draw())
  expect(ranges()).toEqual([{ startColumn: 1, endColumn: 4, startLineNumber: 1, endLineNumber: 1 }])
  selectedFinding = { ...selectedFinding!, symbol: null, lineStart: null, lineEnd: null }
  view.rerender(draw())
  expect(ranges()).toEqual([])
  expect(metadata().highlightedLineNumbers).toEqual([])
  selectedFinding = undefined
  reveal = 2
  view.rerender(draw())
  expect(metadata()).toMatchObject({ revealLineKey: 2, revealLineNumber: 1 })
})
