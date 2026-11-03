import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import * as api from '@/api/project/specialized-analysis'
import { cancelProjectTask } from '@/api/project/task'
import { getProjectStageReadiness } from '@/api/project/workflow'
import { renderWithStrictQueryClient as renderWithQueryClient } from '@/test/strict-mode-utils'

import { StagePresentationContext } from '../components/StagePresentationContext'
import { finding, latest, readiness, run } from './fixtures.test-support'
import RiskAnalysis from './index'
vi.mock('@/api/project/specialized-analysis')
vi.mock('@/api/project/workflow')
vi.mock('@/api/project/task')
vi.mock('@/components/CodeMirrorEditor', () => ({
  default: ({ value }: { value: string }) => <pre>{value}</pre>
}))
const sourceEditor = vi.hoisted(() => vi.fn())
vi.mock('@/components/MonacoEditor', () => ({
  default: (props: Record<string, unknown>) => {
    sourceEditor(props)
    return <pre aria-label="源码证据">{String(props.value)}</pre>
  }
}))
vi.mock('../components/TaskLogPanel', () => ({
  TaskLogPanel: ({ taskId }: { taskId: string }) => <div>任务日志 {taskId}</div>
}))
vi.mock('../components/MigrationInputActions', () => ({
  MigrationInputDetailFooter: ({ sourceType }: { sourceType: string }) => (
    <button>纳入报告 {sourceType}</button>
  )
}))
function surface(
  id = 'memory-order-analysis',
  presentation: 'drawer' | 'panel' = 'panel',
  visible = true,
  onStageChange = vi.fn()
) {
  return (
    <StagePresentationContext.Provider value={presentation}>
      <RiskAnalysis
        projectId="project"
        visible={visible}
        stage={{
          id,
          title: id === 'memory-order-analysis' ? '强弱内存序分析' : '浮点精度扫描',
          order: 8,
          subtitle: '',
          status: 'pending'
        }}
        onClose={vi.fn()}
        onStageChange={onStageChange}
      />
    </StagePresentationContext.Provider>
  )
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getProjectStageReadiness).mockResolvedValue(readiness)
  vi.mocked(api.getSpecializedAnalysisLatest).mockResolvedValue(latest())
  vi.mocked(api.getSpecializedAnalyses).mockResolvedValue({
    items: [run],
    total: 1,
    page: 1,
    pageSize: 20,
    hasMore: false
  })
  vi.mocked(api.getSpecializedAnalysis).mockResolvedValue(run)
  vi.mocked(api.getSpecializedFindings).mockResolvedValue({
    items: [finding],
    total: 1,
    page: 1,
    pageSize: 20,
    hasMore: false
  })
  vi.mocked(api.getSpecializedFinding).mockResolvedValue(finding)
  vi.mocked(api.getSpecializedArtifactDownloadUrl).mockImplementation(
    (_p, _s, id, format) => `/api/${id}?format=${format}`
  )
})

function reductionFinding(id: string, line: number, confidence: string, resultStatus: string) {
  const location = {
    file: 'src/two-loops.c',
    line,
    context: { lines: [{ line, text: `${id} += values[i];` }] }
  }
  return {
    ...finding,
    id,
    externalFindingId: id,
    analyzerType: 'FLOATING_POINT' as const,
    findingCode: 'reduction-reassociation',
    title: '同函数归约',
    filePath: location.file,
    startLine: line,
    confidence,
    resultStatus,
    detail: {
      schemaVersion: '2.0',
      ruleVersion: '1.1',
      fact: { symbol: 'two_loops', physicalTuId: 'same-tu', primaryLocation: location },
      primaryOccurrenceId: `${id}-occurrence`,
      occurrences: [{ id: `${id}-occurrence`, location }],
      evidenceChain: {
        status: 'partial',
        steps: [
          {
            id: `${id}-step`,
            kind: 'sourceObservation',
            title: `${id} 的归约证据`,
            source: location
          }
        ]
      }
    }
  }
}

it.each([false, true])('诊断在独立二级页查看并返回原概览：历史=%s', async (historical) => {
  const user = userEvent.setup()
  const selected = {
    ...run,
    id: historical ? 'historical-run' : run.id,
    status: 'PARTIAL' as const,
    summary: {
      diagnosticsAggregated: true,
      frontierCount: 22,
      primaryDiagnostic: { code: 'adapter_dependency_incomplete', detail: '统一适配器执行失败' },
      diagnostics: [
        {
          code: 'adapter_dependency_incomplete',
          detail: '统一适配器执行失败',
          suggestion: '补充构建证据'
        }
      ]
    }
  }
  vi.mocked(api.getSpecializedAnalysisLatest).mockResolvedValue(latest(historical ? run : selected))
  vi.mocked(api.getSpecializedAnalysis).mockResolvedValue(selected)
  vi.mocked(api.getSpecializedAnalyses).mockResolvedValue({
    items: [selected],
    total: 1,
    page: 1,
    pageSize: 20,
    hasMore: false
  })
  renderWithQueryClient(surface())
  if (historical) {
    await user.click(await screen.findByRole('button', { name: '查看历史记录' }))
    await user.click(await screen.findByRole('button', { name: /^查看$/ }))
  }
  await user.click(await screen.findByRole('button', { name: '查看诊断明细' }))
  expect(await screen.findByRole('region', { name: '诊断记录' })).toBeVisible()
  expect(screen.getByText('adapter_dependency_incomplete')).toBeVisible()
  expect(screen.getByText('建议：补充构建证据')).toBeVisible()
  expect(screen.queryByRole('button', { name: finding.title! })).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '下载JSON' })).toHaveAttribute(
    'href',
    `/api/${selected.id}?format=json`
  )
  await user.click(screen.getByRole('button', { name: '返回分析概览' }))
  expect(await screen.findByRole('button', { name: '查看诊断明细' })).toBeVisible()
  expect(screen.queryByText('adapter_dependency_incomplete')).not.toBeInTheDocument()
  expect(api.getSpecializedAnalysis).toHaveBeenCalledWith(
    'project',
    'memory-order-analysis',
    selected.id
  )
  if (historical) expect(screen.getByRole('button', { name: '返回历史记录' })).toBeVisible()
})

it('诊断二级页加载失败提供重试，历史未知诊断不显示为零', async () => {
  const user = userEvent.setup()
  vi.mocked(api.getSpecializedAnalysis).mockRejectedValue(new Error('Unavailable'))
  renderWithQueryClient(surface())
  await user.click(await screen.findByRole('button', { name: '查看诊断明细' }))
  expect(await screen.findByText('分析诊断加载失败')).toBeVisible()
  expect(screen.queryByRole('region', { name: '诊断记录' })).not.toBeInTheDocument()
  vi.mocked(api.getSpecializedAnalysis).mockResolvedValue({
    ...run,
    diagnosticCount: null,
    summary: { diagnosticsAggregated: false }
  })
  await user.click(screen.getByRole('button', { name: '重新加载' }))
  expect(await screen.findByText(/未知不代表零诊断/)).toBeVisible()
  expect(within(screen.getByText('分析诊断').parentElement!).getByText('未知')).toBeVisible()
  expect(screen.getByRole('button', { name: '返回分析概览' })).toBeVisible()
})

it('浮点 v2 同函数同规则同文件的两个循环维持两条 Finding，并独立展示证据、可信度与状态', async () => {
  const user = userEvent.setup()
  const records = [
    reductionFinding('loop-a', 10, '0.8', 'COMPLETE'),
    reductionFinding('loop-b', 30, '0.3', 'PARTIAL')
  ]
  vi.mocked(api.getSpecializedAnalysisLatest).mockResolvedValue(
    latest({ ...run, analyzerType: 'FLOATING_POINT', findingCount: 2 })
  )
  vi.mocked(api.getSpecializedFindings).mockResolvedValue({
    items: records,
    total: 2,
    page: 1,
    pageSize: 20,
    hasMore: false
  })
  vi.mocked(api.getSpecializedFinding).mockImplementation(async (_project, _stage, id) =>
    records.find((item) => item.id === id)!
  )
  renderWithQueryClient(surface('floating-point-analysis'))
  expect(await screen.findAllByRole('button', { name: '同函数归约' })).toHaveLength(2)
  for (const [i, item] of records.entries()) {
    await user.click(screen.getAllByRole('button', { name: '同函数归约' })[i])
    expect(await screen.findByText(`src/two-loops.c:${item.startLine}`)).toBeVisible()
    expect(screen.queryByRole('combobox', { name: '选择分析命中位置' })).not.toBeInTheDocument()
    expect(screen.getByText(i === 0 ? '分析完成' : '部分分析完成')).toBeVisible()
    const evidence = screen.getByRole('button', { name: `源码观察：${item.id} 的归约证据` })
    await user.click(evidence)
    expect(screen.getByLabelText('源码证据')).toHaveTextContent(`${item.id} += values[i];`)
    expect(screen.getByLabelText('源码证据')).not.toHaveTextContent(
      `${records[1 - i].id} += values[i];`
    )
    await user.click(screen.getByText('扫描事实'))
    expect(await screen.findByText(item.confidence)).toBeVisible()
    await user.click(screen.getByRole('button', { name: '返回风险列表' }))
    await screen.findAllByRole('button', { name: '同函数归约' })
  }
  expect(api.getSpecializedFinding).toHaveBeenCalledWith(
    'project',
    'floating-point-analysis',
    'loop-a'
  )
  expect(api.getSpecializedFinding).toHaveBeenCalledWith(
    'project',
    'floating-point-analysis',
    'loop-b'
  )
})

it('浮点历史 v2 单条 Finding 多位置不拆成多条记录，按 primaryOccurrenceId 展示并允许切换', async () => {
  const user = userEvent.setup()
  const base = reductionFinding('historical', 10, '0.5', 'PARTIAL')
  const second = {
    id: 'second',
    location: {
      file: 'src/two-loops.c',
      line: 30,
      context: { lines: [{ line: 30, text: 'historical_second += x;' }] }
    }
  }
  const legacy = {
    ...base,
    detail: {
      ...base.detail,
      primaryOccurrenceId: 'second',
      occurrences: [...base.detail.occurrences, second]
    }
  }
  vi.mocked(api.getSpecializedFindings).mockResolvedValue({
    items: [legacy],
    total: 1,
    page: 1,
    pageSize: 20,
    hasMore: false
  })
  vi.mocked(api.getSpecializedFinding).mockResolvedValue(legacy)
  renderWithQueryClient(surface('floating-point-analysis'))
  expect(await screen.findAllByRole('button', { name: '同函数归约' })).toHaveLength(1)
  await user.click(screen.getByRole('button', { name: '同函数归约' }))
  expect(await screen.findByText('src/two-loops.c:30')).toBeVisible()
  expect(screen.getByLabelText('源码证据')).toHaveTextContent('historical_second += x;')
  await user.click(screen.getByRole('combobox', { name: '选择分析命中位置' }))
  await user.click(screen.getByText('位置 1 · src/two-loops.c:10'))
  expect(screen.getByLabelText('源码证据')).toHaveTextContent('historical += values[i];')
  expect(screen.getByText('src/two-loops.c:10')).toBeVisible()
})

describe('real specialized analysis', () => {
  it.each(['memory-order-analysis', 'floating-point-analysis'])(
    'renders embedded historical source from the detail response: %s',
    async (id) => {
      vi.mocked(api.getSpecializedFinding).mockResolvedValue({
        ...finding,
        detail: {
          ...finding.detail,
          fact: {
            primaryLocation: {
              file: 'src/old.cpp',
              sourceSha256: 'snapshot-digest',
              span: { start: { line: 43 }, end: { line: 44 } },
              context: {
                lines: [
                  { line: 42, text: '' },
                  { line: 43, text: 'sum += old_value;' },
                  { line: 44, text: 'return sum;' }
                ],
                highlights: [
                  { line: 43, startColumn: 1, endColumn: 18 },
                  { line: 44, startColumn: 1, endColumn: 12 }
                ]
              }
            }
          }
        },
        codeContext: {
          findingId: finding.id,
          runId: finding.runId,
          sourceRevisionId: 'historical-source',
          filePath: 'src/old.cpp',
          contextBefore: 20,
          contextAfter: 20,
          startLine: 42,
          endLine: 44,
          highlightStartLine: 43,
          highlightEndLine: 44,
          lines: [
            { lineNumber: 42, text: '', highlighted: false },
            { lineNumber: 43, text: 'sum += old_value;', highlighted: true },
            { lineNumber: 44, text: 'return sum;', highlighted: true }
          ]
        },
        codeContextUnavailableReason: null
      })
      renderWithQueryClient(surface(id))
      fireEvent.click(await screen.findByRole('button', { name: finding.title! }))
      expect(await screen.findByLabelText('源码证据')).toHaveTextContent('sum += old_value;')
      expect(screen.getByText('src/old.cpp:43')).toBeVisible()
      expect(sourceEditor).toHaveBeenLastCalledWith(
        expect.objectContaining({
          value: '\nsum += old_value;\nreturn sum;',
          lineNumbers: [42, 43, 44],
          highlightedLineNumbers: [2, 3],
          revealLineNumber: 2,
          readOnly: true
        })
      )
      expect(api.getSpecializedFinding).toHaveBeenCalledWith('project', id, finding.id)
    }
  )
  it('keeps findings accessible and explains unavailable historical source', async () => {
    vi.mocked(api.getSpecializedFinding).mockResolvedValue({
      ...finding,
      codeContext: null,
      codeContextUnavailableReason: 'FINDING_SOURCE_FILE_NOT_FOUND'
    })
    renderWithQueryClient(surface())
    fireEvent.click(await screen.findByRole('button', { name: finding.title! }))
    expect(await screen.findByText('本次分析对应的源码文件或工作副本已不可用。')).toBeVisible()
    expect(screen.getByRole('region', { name: '建议下一步' })).toBeVisible()
    expect(screen.queryByLabelText('源码证据')).not.toBeInTheDocument()
  })
  it.each(['drawer', 'panel'] as const)(
    'prioritizes current prerequisites and keeps failed records accessible with %s',
    async (presentation) => {
      const onStageChange = vi.fn()
      vi.mocked(getProjectStageReadiness).mockResolvedValue({
        ...readiness,
        prerequisites: readiness.prerequisites.map((item) =>
          item.key === 'X86_BUILD'
            ? { ...item, status: 'FAILED', reasonCode: 'X86_BUILD_FAILED' }
            : item
        )
      })
      const failedRun = {
        ...run,
        status: 'FAILED' as const,
        summary: { error: '分析请求适配失败，exitCode=1' }
      }
      vi.mocked(api.getSpecializedAnalysisLatest).mockResolvedValue(latest(failedRun))
      vi.mocked(api.getSpecializedAnalysis).mockResolvedValue(failedRun)
      renderWithQueryClient(surface('floating-point-analysis', presentation, true, onStageChange))
      expect(await screen.findByText(/暂不能执行(强弱内存序分析|浮点精度扫描)/)).toBeVisible()
      expect(screen.getByText('x86 构建失败')).toBeVisible()
      expect(screen.queryByText('分析请求适配失败，exitCode=1')).not.toBeInTheDocument()
      expect(screen.getAllByRole('status')).toHaveLength(1)
      expect(screen.queryByRole('button', { name: '重新分析' })).not.toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: '前往 x86 构建' }))
      expect(onStageChange).toHaveBeenCalledWith('x86-build')
      expect(screen.queryByRole('button', { name: '查看日志' })).not.toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: '查看历史记录' }))
      expect(await screen.findByText('分析记录')).toBeVisible()
      fireEvent.click(await screen.findByRole('button', { name: '查看' }))
      expect(await screen.findByText('分析请求适配失败，exitCode=1')).toBeVisible()
      expect(screen.queryByText(/暂不能执行(强弱内存序分析|浮点精度扫描)/)).not.toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: '查看日志' }))
      expect(await screen.findByText('任务日志 task')).toBeVisible()
    }
  )
  it.each(['memory-order-analysis', 'floating-point-analysis'])(
    'queries official endpoints without starting a task: %s',
    async (id) => {
      renderWithQueryClient(surface(id))
      expect(await screen.findByRole('button', { name: finding.title! })).toBeVisible()
      expect(api.getSpecializedAnalysisLatest).toHaveBeenCalledWith('project', id)
      expect(api.createSpecializedAnalysis).not.toHaveBeenCalled()
      expect(screen.queryByText('示例数据')).not.toBeInTheDocument()
      expect(await screen.findByRole('button', { name: '重新分析' })).toBeEnabled()
      expect(screen.getByRole('link', { name: '下载JSON' })).toHaveAttribute(
        'href',
        '/api/run?format=json'
      )
    }
  )
  it.each(['drawer', 'panel'] as const)(
    'preserves filters and renders actual evidence with %s',
    async (presentation) => {
      const user = userEvent.setup()
      renderWithQueryClient(surface('memory-order-analysis', presentation))
      await screen.findByRole('button', { name: finding.title! })
      fireEvent.change(screen.getByRole('textbox', { name: '搜索风险' }), {
        target: { value: '发布' }
      })
      await waitFor(() =>
        expect(api.getSpecializedFindings).toHaveBeenLastCalledWith(
          'project',
          'memory-order-analysis',
          'run',
          expect.objectContaining({ keyword: '发布' })
        )
      )
      await user.click(await screen.findByRole('button', { name: finding.title! }))
      expect(await screen.findByText(/源码片段的 Finding、文件、digest/)).toBeVisible()
      expect(
        within(screen.getByRole('region', { name: '建议下一步' })).getByRole('button', {
          name: '纳入报告 MEMORY_ORDER_FINDING'
        })
      ).toBeVisible()
      expect(screen.getByText('状态未知')).toBeVisible()
      await user.click(screen.getByText('原始扫描数据'))
      expect(await screen.findByText(/primaryLocation/)).toBeVisible()
      await user.click(screen.getByRole('button', { name: '返回风险列表' }))
      expect(await screen.findByRole('textbox', { name: '搜索风险' })).toHaveValue('发布')
    }
  )
  it('blocks starting when analyzer unavailable', async () => {
    vi.mocked(getProjectStageReadiness).mockResolvedValue({
      ...readiness,
      prerequisites: [
        {
          ...readiness.prerequisites[2],
          status: 'UNAVAILABLE',
          reasonCode: 'SPECIALIZED_ANALYZER_UNAVAILABLE'
        }
      ]
    })
    renderWithQueryClient(surface())
    expect(await screen.findByText(/暂不能执行(强弱内存序分析|浮点精度扫描)/)).toBeVisible()
    expect(screen.queryByRole('button', { name: '重新分析' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: finding.title! })).not.toBeInTheDocument()
  })
  it('starts only on click and waits for authoritative execution after submission', async () => {
    let resolve:
      ((data: Awaited<ReturnType<typeof api.createSpecializedAnalysis>>) => void) | undefined
    vi.mocked(api.createSpecializedAnalysis).mockImplementation(
      () =>
        new Promise((r) => {
          resolve = r
        })
    )
    renderWithQueryClient(surface())
    const button = await screen.findByRole('button', { name: '重新分析' })
    await waitFor(() => expect(button).toBeEnabled())
    fireEvent.click(button)
    fireEvent.click(button)
    await waitFor(() => expect(api.createSpecializedAnalysis).toHaveBeenCalledTimes(1))
    resolve?.({
      id: 'new-task',
      projectId: 'project',
      type: 'MEMORY_ORDER_ANALYSIS',
      status: 'PENDING',
      progress: 0,
      message: null,
      createdAt: run.createdAt,
      queuedAt: null,
      startedAt: null,
      finishedAt: null,
      errorCode: null,
      errorMessage: null,
      resource: {}
    })
    expect(await screen.findByText('已提交，正在同步状态')).toBeVisible()
    expect(api.getSpecializedAnalysisLatest).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: '重新分析' })).not.toBeInTheDocument()
  })
  it.each(['PARTIAL', 'FAILED', 'CANCELLED', 'TIMEOUT'] as const)(
    'preserves %s status and does not show a full success',
    async (status) => {
      vi.mocked(api.getSpecializedAnalysisLatest).mockResolvedValue(
        latest({ ...run, status, findingCount: 0, candidateCount: 0, diagnosticCount: 2 })
      )
      vi.mocked(api.getSpecializedFindings).mockResolvedValue({
        items: [],
        total: 0,
        page: 1,
        pageSize: 20,
        hasMore: false
      })
      renderWithQueryClient(surface())
      if (status === 'PARTIAL') {
        expect(
          within((await screen.findByText('分析诊断')).parentElement!).getByText('2')
        ).toBeVisible()
      } else expect(await screen.findByText(/2 项分析诊断/)).toBeVisible()
      expect(screen.queryByRole('heading', { name: '分析完成' })).not.toBeInTheDocument()
      if (status === 'PARTIAL')
        expect(screen.getByText('部分范围未完成，零正式风险不代表迁移安全。')).toBeVisible()
      else expect(await screen.findByText(/本次执行未完成/)).toBeVisible()
    }
  )
  it('forbids importing stale source results', async () => {
    vi.mocked(api.getSpecializedAnalysisLatest).mockResolvedValue(
      latest({
        ...run,
        currentSourceRevision: false,
        sourceRevisionId: 'old',
        staleReasons: ['SOURCE_REVISION_CHANGED']
      })
    )
    vi.mocked(api.getSpecializedAnalysis).mockResolvedValue({
      ...run,
      currentSourceRevision: false,
      sourceRevisionId: 'old'
    })
    renderWithQueryClient(surface())
    expect(await screen.findByText('当前版本暂无有效结果，旧结果可从历史记录查看。')).toBeVisible()
    expect(screen.queryByRole('button', { name: finding.title! })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '查看历史记录' }))
    fireEvent.click(await screen.findByRole('button', { name: '查看' }))
    expect(await screen.findByText('该分析基于旧源码')).toBeVisible()
    fireEvent.click(await screen.findByRole('button', { name: finding.title! }))
    expect(await screen.findByText('历史源码结果仅供查看，不能纳入当前源码报告。')).toBeVisible()
    expect(screen.queryByRole('button', { name: /纳入报告/ })).not.toBeInTheDocument()
  })
  it('shows API failures and retries without examples', async () => {
    vi.mocked(api.getSpecializedAnalysisLatest).mockRejectedValue(new Error('denied'))
    renderWithQueryClient(surface())
    expect(await screen.findByText('强弱内存序分析加载失败')).toBeVisible()
    expect(screen.queryByRole('button', { name: '启动分析' })).not.toBeInTheDocument()
    vi.mocked(api.getSpecializedAnalysisLatest).mockResolvedValue(latest(null))
    fireEvent.click(screen.getByRole('button', { name: '重新加载' }))
    expect(await screen.findByText('已满足强弱内存序分析条件')).toBeVisible()
  })
  it('clears local navigation on close and switching project stages', async () => {
    const { rerender } = renderWithQueryClient(surface())
    await screen.findByRole('textbox', { name: '搜索风险' })
    fireEvent.change(screen.getByRole('textbox', { name: '搜索风险' }), {
      target: { value: 'anything' }
    })
    rerender(surface('memory-order-analysis', 'panel', false))
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    rerender(surface('floating-point-analysis'))
    expect(await screen.findByRole('textbox', { name: '搜索风险' })).toHaveValue('')
  })
})
it('recovers from a business creation failure and refreshes readiness', async () => {
  vi.mocked(api.createSpecializedAnalysis).mockRejectedValue(new Error('共享 Bitcode v2 尚未就绪'))
  renderWithQueryClient(surface())
  const button = await screen.findByRole('button', { name: '重新分析' })
  await waitFor(() => expect(button).toBeEnabled())
  fireEvent.click(button)
  expect(await screen.findByText('共享 Bitcode v2 尚未就绪')).toBeVisible()
  await waitFor(() => expect(getProjectStageReadiness).toHaveBeenCalledTimes(2))
  await waitFor(() => expect(button).toBeEnabled())
})
it('replaces ready state with the backend build prerequisite rejection', async () => {
  vi.mocked(api.getSpecializedAnalysisLatest).mockResolvedValue(latest(null))
  vi.mocked(api.getSpecializedAnalyses).mockResolvedValue({
    items: [],
    total: 0,
    page: 1,
    pageSize: 20,
    hasMore: false
  })
  vi.mocked(api.createSpecializedAnalysis).mockRejectedValue({
    isAxiosError: true,
    response: {
      data: {
        code: 'SUCCESSFUL_X86_BUILD_WITH_COMPILE_DATABASE_REQUIRED',
        message: '需要当前版本的 compile_commands.json'
      }
    }
  })
  renderWithQueryClient(surface())
  const button = await screen.findByRole('button', { name: '启动分析' })
  await waitFor(() => expect(button).toBeEnabled())
  fireEvent.click(button)
  expect(await screen.findByText(/暂不能执行(强弱内存序分析|浮点精度扫描)/)).toBeVisible()
  expect(screen.getByText('需要当前版本的 compile_commands.json')).toBeVisible()
  expect(screen.queryByText('已满足强弱内存序分析条件')).not.toBeInTheDocument()
  expect(screen.queryByText('启动分析失败')).not.toBeInTheDocument()
  await waitFor(() => expect(getProjectStageReadiness).toHaveBeenCalledTimes(2))
  expect(screen.queryByRole('button', { name: '启动分析' })).not.toBeInTheDocument()
})
it('blocks running tasks and exposes shared task logs', async () => {
  vi.mocked(api.getSpecializedAnalysisLatest).mockResolvedValue(
    latest({ ...run, status: 'RUNNING' })
  )
  renderWithQueryClient(surface())
  expect(await screen.findByText('强弱内存序分析正在执行')).toBeVisible()
  expect(screen.queryByRole('button', { name: '启动分析' })).not.toBeInTheDocument()
  expect(screen.queryByText(/0 项分析结果/)).not.toBeInTheDocument()
  expect(screen.queryByText(`构建记录：${run.buildRunId}`)).not.toBeInTheDocument()
  expect(api.getSpecializedFindings).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '查看日志' }))
  expect(await screen.findByText('任务日志 task')).toBeVisible()
})
it('paginates findings on the server rather than slicing a partial page twice', async () => {
  vi.mocked(api.getSpecializedFindings).mockResolvedValue({
    items: [finding],
    total: 47,
    page: 1,
    pageSize: 20,
    hasMore: true
  })
  renderWithQueryClient(surface())
  await screen.findByRole('button', { name: finding.title! })
  fireEvent.click(screen.getByTitle('2'))
  await waitFor(() =>
    expect(api.getSpecializedFindings).toHaveBeenLastCalledWith(
      'project',
      'memory-order-analysis',
      'run',
      expect.objectContaining({ page: 2, pageSize: 20 })
    )
  )
  expect(await screen.findByRole('button', { name: finding.title! })).toBeVisible()
})
it('loads historical records and keeps report operations on the selected source', async () => {
  const user = userEvent.setup()
  const oldRun = {
    ...run,
    id: 'old-run',
    currentSourceRevision: false,
    sourceRevisionId: 'old-source'
  }
  vi.mocked(api.getSpecializedAnalyses).mockResolvedValue({
    items: [oldRun],
    total: 1,
    page: 1,
    pageSize: 20,
    hasMore: false
  })
  vi.mocked(api.getSpecializedAnalysis).mockResolvedValue(oldRun)
  renderWithQueryClient(surface())
  await screen.findByRole('button', { name: finding.title! })
  await user.click(screen.getByRole('button', { name: '查看历史记录' }))
  await user.click(await screen.findByRole('button', { name: /^查看$/ }))
  await waitFor(() =>
    expect(api.getSpecializedAnalysis).toHaveBeenCalledWith(
      'project',
      'memory-order-analysis',
      'old-run'
    )
  )
  expect(await screen.findByText('该分析基于旧源码')).toBeVisible()
})

it('cancels the selected live task only after user confirmation', async () => {
  const user = userEvent.setup()
  vi.mocked(api.getSpecializedAnalysisLatest).mockResolvedValue(
    latest({ ...run, status: 'RUNNING' })
  )
  vi.mocked(cancelProjectTask).mockResolvedValue({ id: run.taskId } as Awaited<
    ReturnType<typeof cancelProjectTask>
  >)
  renderWithQueryClient(surface())
  await user.click(await screen.findByRole('button', { name: '取消分析' }))
  expect(cancelProjectTask).not.toHaveBeenCalled()
  await user.click(await screen.findByRole('button', { name: '确认取消' }))
  await waitFor(() => expect(cancelProjectTask).toHaveBeenCalledWith('task'))
})

it('imports floating point findings with the official report source type', async () => {
  renderWithQueryClient(surface('floating-point-analysis'))
  fireEvent.click(await screen.findByRole('button', { name: finding.title! }))
  expect(
    await screen.findByRole('button', { name: '纳入报告 FLOATING_POINT_FINDING' })
  ).toBeVisible()
})
it('does not interpret zero findings as whole-program safety', async () => {
  vi.mocked(api.getSpecializedAnalysisLatest).mockResolvedValue(
    latest({ ...run, status: 'COMPLETE', findingCount: 0, candidateCount: 0 })
  )
  vi.mocked(api.getSpecializedFindings).mockResolvedValue({
    items: [],
    total: 0,
    page: 1,
    pageSize: 20,
    hasMore: false
  })
  renderWithQueryClient(surface())
  expect((await screen.findAllByText(/本次分析范围内未发现支持的风险/))[0]).toBeVisible()
  expect(screen.getByText(/结论限于本次分析的证据和模型范围/)).toBeVisible()
  expect(screen.getByText(/零正式风险不代表迁移安全/)).toBeVisible()
})
