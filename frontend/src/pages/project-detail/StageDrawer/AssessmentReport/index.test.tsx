import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { renderWithStrictQueryClient as renderWithQueryClient } from '@/test/strict-mode-utils'

import AssessmentReport from '.'

const {
  createProjectEvaluationReport,
  getProjectEvaluationReportCodeFiles,
  getProjectEvaluationReportCodeFindings,
  getProjectEvaluationReportCodeModules,
  getProjectEvaluationReportFileContext,
  getProjectEvaluationReportFindingTypes,
  getProjectEvaluationReportInputs,
  getProjectEvaluationReportLatest,
  getProjectStageReadiness,
  monacoEditor
} = vi.hoisted(() => ({
  createProjectEvaluationReport: vi.fn(),
  getProjectEvaluationReportCodeFiles: vi.fn(),
  getProjectEvaluationReportCodeFindings: vi.fn(),
  getProjectEvaluationReportCodeModules: vi.fn(),
  getProjectEvaluationReportFileContext: vi.fn(),
  getProjectEvaluationReportFindingTypes: vi.fn(),
  getProjectEvaluationReportInputs: vi.fn(),
  getProjectEvaluationReportLatest: vi.fn(),
  getProjectStageReadiness: vi.fn(),
  monacoEditor: vi.fn()
}))

vi.mock('@/api/project/assessment-report', () => ({
  createProjectEvaluationReport,
  getProjectEvaluationReportDownloadUrl: (reportId: string, format: string) =>
    `/api/reports/${reportId}/download?format=${format}`,
  getProjectEvaluationReportCodeFiles,
  getProjectEvaluationReportCodeFindings,
  getProjectEvaluationReportCodeModules,
  getProjectEvaluationReportFileContext,
  getProjectEvaluationReportFindingTypes,
  getProjectEvaluationReportInputs,
  getProjectEvaluationReportLatest
}))
vi.mock('@/api/project/workflow', () => ({ getProjectStageReadiness }))

vi.mock('@/components/BaseDrawer', () => ({
  default: ({
    children,
    extra,
    footer,
    title,
    onClose
  }: {
    children: React.ReactNode
    extra?: React.ReactNode
    footer?: React.ReactNode
    title: React.ReactNode
    onClose: () => void
  }) => (
    <div>
      {title}
      {extra}
      {children}
      {footer}
      <button onClick={onClose}>关闭测试 Drawer</button>
    </div>
  )
}))

vi.mock('./components/ReportIssueDetail', () => ({
  default: ({ migrationInputId }: { migrationInputId: string }) => (
    <div>只读报告问题 {migrationInputId}</div>
  )
}))

vi.mock('@/components/MonacoEditor', () => ({
  default: (props: {
    ariaLabel?: string
    highlightedLineNumbers?: number[]
    highlightedRanges?: unknown[]
    lineNumbers?: number[]
    sourceGaps?: Array<{
      endLine: number
      onLoad?: () => void
      startLine: number
    }>
    value?: string
  }) => {
    monacoEditor(props)
    return (
      <div>
        <pre aria-label={props.ariaLabel}>{props.value}</pre>
        {props.sourceGaps?.map((gap) => (
          <button
            key={`${gap.startLine}-${gap.endLine}`}
            aria-label={`加载第 ${gap.startLine}–${gap.endLine} 行`}
            type="button"
            onClick={gap.onLoad}
          >
            加载此段
          </button>
        ))}
      </div>
    )
  }
}))

const stage = {
  id: 'report',
  order: 11,
  title: '评估报告',
  subtitle: '',
  status: 'running' as const
}

function mockReport(status = 'SUCCEEDED', hasCodeFindings = true, dependencyFindingCount = 1) {
  getProjectStageReadiness.mockResolvedValue({
    context: { runtimeEnvironmentVersion: 1, sourceRevisionId: 'source-1' },
    prerequisites: [
      {
        details: {},
        key: 'KB_SCAN',
        reasonCode: 'KB_SCAN_READY',
        referenceId: 'scan-1',
        status: 'SATISFIED'
      },
      {
        details: {},
        key: 'DEPENDENCY_ANALYSIS',
        reasonCode: 'DEPENDENCY_ANALYSIS_READY',
        referenceId: 'analysis-1',
        status: 'SATISFIED'
      },
      {
        details: { count: 12 },
        key: 'INCLUDED_FINDINGS',
        reasonCode: 'INCLUDED_FINDINGS_READY',
        referenceId: null,
        status: 'SATISFIED'
      },
      {
        details: {},
        key: 'COMPARABLE_REPORT',
        reasonCode: 'COMPARABLE_REPORT_MISSING',
        referenceId: null,
        status: 'MISSING'
      }
    ],
    stageId: 'report'
  })
  getProjectEvaluationReportInputs.mockResolvedValue({
    hasMore: false,
    items: [
      {
        createdAt: '2026-07-24T00:01:00Z',
        id: 'report-input-1',
        inputId: 'kb-input-1',
        inputSnapshot: {
          detectedLevel: 'HIGH',
          issueTitle: 'x86 intrinsic 迁移',
          locationLabel: 'src/simd.cpp:24',
          sourceType: 'KB_FINDING'
        },
        inputType: 'MIGRATION_INPUT_REF',
        reportId: 'report-1'
      },
      {
        createdAt: '2026-07-24T00:01:00Z',
        id: 'report-input-2',
        inputId: 'dependency-input-1',
        inputSnapshot: {
          issueTitle: 'libfoo 迁移替代性分析',
          note: '目标环境已有可用替代，优先确认版本、ABI 和调用方式。',
          sourceType: 'DEPENDENCY_RESULT',
          targetMatchStatus: 'TARGET_PRESENT'
        },
        inputType: 'MIGRATION_INPUT_REF',
        reportId: 'report-1'
      }
    ],
    page: 1,
    pageSize: 200,
    total: 2
  })
  getProjectEvaluationReportCodeModules.mockResolvedValue({
    hasMore: false,
    items: [
      {
        basis: 'BUILD_TARGET',
        confidence: 'HIGH',
        fileCount: 2,
        findingCount: 27,
        findingTypeCount: 2,
        highestRisk: 'HIGH',
        moduleKey: 'module-simd',
        moduleName: 'SIMD 核心模块',
        relatedModuleKeys: ['lib-simd'],
        riskCounts: { HIGH: 20, MEDIUM: 7 },
        typeCounts: { INTRINSIC: 16, HEADER: 11 }
      }
    ],
    page: 1,
    pageSize: 10,
    total: 1
  })
  getProjectEvaluationReportCodeFiles.mockResolvedValue({
    hasMore: false,
    items: [
      {
        contextAvailable: true,
        fileKey: 'file-fmadd',
        findingCount: 27,
        findingTypeCount: 2,
        highestRisk: 'HIGH',
        relativePath: 'src/simd/fmadd.c',
        riskCounts: { HIGH: 20, MEDIUM: 7 },
        sourceContentHash: 'hash-1',
        typeCounts: { INTRINSIC: 16, HEADER: 11 }
      }
    ],
    page: 1,
    pageSize: 10,
    total: 1
  })
  getProjectEvaluationReportFindingTypes.mockResolvedValue({
    hasMore: false,
    items: [
      {
        findingCount: 16,
        findingType: 'INTRINSIC',
        firstLine: 44,
        highestRisk: 'HIGH',
        lastLine: 90,
        symbolCount: 4
      },
      {
        findingCount: 11,
        findingType: 'HEADER',
        firstLine: 2,
        highestRisk: 'HIGH',
        lastLine: 2,
        symbolCount: 1
      }
    ],
    page: 1,
    pageSize: 200,
    total: 2
  })
  getProjectEvaluationReportCodeFindings.mockResolvedValue({
    hasMore: false,
    items: [
      {
        columnEnd: null,
        columnStart: null,
        findingType: 'INTRINSIC',
        id: 'finding-44',
        issueTitle: 'x86 Intrinsic 调用',
        lineEnd: 44,
        lineStart: 44,
        locationAvailable: true,
        recommendation: '替换为目标架构实现。',
        relativePath: 'src/simd/fmadd.c',
        riskLevel: 'HIGH',
        sourceDetail: { evidence: { matchedText: '_mm_fmadd_pd' } },
        sourceRefId: 'source-finding-44',
        symbol: '_mm_fmadd_pd'
      }
    ],
    page: 1,
    pageSize: 20,
    total: 1
  })
  getProjectEvaluationReportFileContext.mockResolvedValue({
    blocks: [
      {
        endLine: 45,
        kind: 'CODE',
        lineCount: 3,
        lines: [
          { highlights: [], lineNumber: 43, text: '__m256d value;' },
          {
            highlights: [{ columnEnd: null, columnStart: null, findingId: 'finding-44' }],
            lineNumber: 44,
            text: '_mm_fmadd_pd(a, b, c);'
          },
          { highlights: [], lineNumber: 45, text: 'return value;' }
        ],
        loadable: false,
        reason: null,
        startLine: 43
      }
    ],
    contentHash: 'hash-1',
    contextAvailable: true,
    fileKey: 'file-fmadd',
    findings: [
      {
        columnEnd: null,
        columnStart: null,
        findingType: 'INTRINSIC',
        id: 'finding-44',
        issueTitle: 'x86 Intrinsic 调用',
        lineEnd: 44,
        lineStart: 44,
        locationAvailable: true,
        recommendation: '替换为目标架构实现。',
        relativePath: 'src/simd/fmadd.c',
        riskLevel: 'HIGH',
        sourceDetail: {},
        sourceRefId: 'source-finding-44',
        symbol: '_mm_fmadd_pd'
      }
    ],
    relativePath: 'src/simd/fmadd.c',
    reportId: 'report-1',
    sourceRevisionId: 'source-1',
    totalLines: 120,
    unavailableReason: null
  })
  getProjectEvaluationReportLatest.mockResolvedValue({
    context: { runtimeEnvironmentVersion: 1, sourceRevisionId: 'source-1' },
    execution: {
      createdAt: '2026-07-24T00:00:00Z',
      errorCode: null,
      errorMessage: null,
      finishedAt: '2026-07-24T00:01:00Z',
      id: 'report-1',
      message: null,
      progress: 100,
      startedAt: '2026-07-24T00:00:10Z',
      status,
      taskId: 'task-1'
    },
    freshness: 'CURRENT',
    result: {
      artifacts: [],
      createdAt: '2026-07-24T00:00:00Z',
      generatedAt: '2026-07-24T00:01:00Z',
      id: 'report-1',
      organizationAvailable: true,
      organizationSchemaVersion: 'migration-report/v2',
      projectId: 'project-1',
      reportType: 'EVALUATION',
      riskLevel: hasCodeFindings ? 'HIGH' : dependencyFindingCount > 0 ? 'MEDIUM' : 'INFO',
      sourceRevisionId: 'source-1',
      status,
      summary: {
        codeFileCount: hasCodeFindings ? 2 : 0,
        codeFindingCount: hasCodeFindings ? 27 : 0,
        codeModuleCount: hasCodeFindings ? 1 : 0,
        confirmedInputCount: (hasCodeFindings ? 11 : 0) + dependencyFindingCount,
        dependencyAnalysisInputCount: dependencyFindingCount,
        dependencySubstitutionNotes: [
          {
            id: 'dependency-input-1',
            issueTitle: 'libfoo 迁移替代性分析',
            note: '目标环境已有可用替代，优先确认版本、ABI 和调用方式。',
            sourceType: 'DEPENDENCY_RESULT',
            targetMatchStatus: 'TARGET_PRESENT'
          }
        ],
        detectedLevelCounts: { 'HIGH/TARGET_PRESENT': 3, MEDIUM: 9 },
        estimatedWorkload: { min: 16, max: 28, unit: 'person_hour' },
        highRiskIssueCount: hasCodeFindings ? 3 : 0,
        priorityIssues: [
          {
            detectedLevel: 'HIGH',
            id: 'kb-input-1',
            issueTitle: 'x86 intrinsic 迁移',
            locationLabel: 'src/simd.cpp:24',
            sourceType: 'KB_FINDING'
          }
        ],
        sourceTypeCounts: { KB_FINDING: 8, DEPENDENCY_RESULT: 4 }
      },
      taskId: 'task-1',
      title: '迁移评估报告 - v1'
    }
  })
}

function renderAssessmentReport(onClose = vi.fn()) {
  mockReport()
  return renderWithQueryClient(
    <AssessmentReport projectId="project-1" stage={stage} visible onClose={onClose} />
  )
}

function renderOrganizedAssessmentReport() {
  mockReport('SUCCEEDED')
  return renderWithQueryClient(
    <AssessmentReport projectId="project-1" stage={stage} visible onClose={vi.fn()} />
  )
}

describe('Assessment report stage drawer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('open', vi.fn())
  })

  it('navigates from an organized report module to its file context', async () => {
    const user = userEvent.setup()
    renderOrganizedAssessmentReport()

    expect(await screen.findByText('SIMD 核心模块')).toBeInTheDocument()
    expect(screen.queryByText(/本次识别/)).not.toBeInTheDocument()
    expect(screen.queryByText('优先处理队列')).not.toBeInTheDocument()
    expect(getProjectEvaluationReportInputs).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: /SIMD 核心模块/ }))

    expect(screen.queryByText('报告概览')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新生成报告' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '刷新' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '进入人工修复' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /返回报告总览/ })).toBeInTheDocument()
    expect(await screen.findByText('src/simd/fmadd.c')).toBeInTheDocument()
    expect(screen.getByText('关联构建目标：lib-simd')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: '模块问题类型' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /src\/simd\/fmadd.c/ }))

    expect(screen.queryByText('报告概览')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /返回 SIMD 核心模块/ })).toBeInTheDocument()
    expect(await screen.findByText('联合源码上下文')).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: '文件问题类型' })).toBeInTheDocument()
    expect(screen.getByText('x86 Intrinsic 调用')).toBeInTheDocument()
    expect(screen.getByLabelText('src/simd/fmadd.c 联合源码上下文')).toHaveTextContent(
      '_mm_fmadd_pd(a, b, c);'
    )
    expect(monacoEditor.mock.calls.at(-1)?.[0]).toEqual(
      expect.objectContaining({
        highlightedLineNumbers: [2],
        highlightedRanges: [],
        lineNumbers: [43, 44, 45],
        revealLineNumber: 1
      })
    )

    const findingButton = screen.getByRole('button', { name: /x86 Intrinsic 调用/ })
    expect(findingButton).toHaveAttribute('aria-expanded', 'false')

    await user.click(findingButton)

    expect(findingButton).toHaveAttribute('aria-expanded', 'true')

    expect(monacoEditor.mock.calls.at(-1)?.[0]).toEqual(
      expect.objectContaining({
        highlightedLineNumbers: [2],
        highlightedRanges: [
          {
            endColumn: 13,
            endLineNumber: 2,
            startColumn: 1,
            startLineNumber: 2
          }
        ],
        revealLineKey: 1,
        revealLineNumber: 2
      })
    )

    await user.click(findingButton)
    expect(monacoEditor.mock.calls.at(-1)?.[0]).toEqual(
      expect.objectContaining({
        highlightedLineNumbers: [2],
        revealLineKey: 2,
        revealLineNumber: 2
      })
    )

    await user.click(screen.getByRole('button', { name: '返回初始位置' }))
    expect(monacoEditor.mock.calls.at(-1)?.[0]).toEqual(
      expect.objectContaining({
        highlightedLineNumbers: [2],
        highlightedRanges: [],
        revealLineKey: 3,
        revealLineNumber: 1
      })
    )
    expect(screen.queryByRole('button', { name: '返回初始位置' })).not.toBeInTheDocument()
    expect(getProjectEvaluationReportFindingTypes).toHaveBeenCalledWith(
      'report-1',
      'module-simd',
      'file-fmadd',
      expect.objectContaining({ pageSize: 200 })
    )

    await user.click(screen.getByRole('button', { name: /返回 SIMD 核心模块/ }))
    await user.click(screen.getByRole('button', { name: /返回报告总览/ }))
    expect(await screen.findByText('报告概览')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新生成报告' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '刷新' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '进入人工修复' })).toBeInTheDocument()
  })

  it('restores module search and pagination after returning from a module detail', async () => {
    const user = userEvent.setup()
    renderOrganizedAssessmentReport()
    await screen.findByText('SIMD 核心模块')
    const response = await getProjectEvaluationReportCodeModules.mock.results[0].value
    getProjectEvaluationReportCodeModules.mockImplementation(async (_reportId, params) => ({
      ...response,
      page: params.page,
      total: 35,
      hasMore: true
    }))
    await user.type(screen.getByRole('textbox', { name: '搜索模块、文件或问题' }), 'simd')
    await waitFor(() =>
      expect(getProjectEvaluationReportCodeModules).toHaveBeenLastCalledWith(
        'report-1',
        expect.objectContaining({ keyword: 'simd', page: 1 })
      )
    )
    await user.click(await screen.findByTitle('2'))
    await waitFor(() =>
      expect(getProjectEvaluationReportCodeModules).toHaveBeenLastCalledWith(
        'report-1',
        expect.objectContaining({ keyword: 'simd', page: 2 })
      )
    )
    await user.click(screen.getByRole('button', { name: /SIMD 核心模块/ }))
    await screen.findByText('src/simd/fmadd.c')
    await user.click(screen.getByRole('button', { name: /返回报告总览/ }))
    expect(await screen.findByRole('textbox', { name: '搜索模块、文件或问题' })).toHaveValue('simd')
    await waitFor(() =>
      expect(getProjectEvaluationReportCodeModules).toHaveBeenLastCalledWith(
        'report-1',
        expect.objectContaining({ keyword: 'simd', page: 2 })
      )
    )
    expect(await screen.findByTitle('2')).toHaveClass('ant-pagination-item-active')
  })

  it('keeps the type filter available when a module has only one finding type', async () => {
    const user = userEvent.setup()
    mockReport('SUCCEEDED')
    getProjectEvaluationReportCodeModules.mockResolvedValue({
      hasMore: false,
      items: [
        {
          basis: 'NEAREST_BUILD_DESCRIPTOR',
          confidence: 'HIGH',
          fileCount: 2,
          findingCount: 27,
          findingTypeCount: 1,
          highestRisk: 'HIGH',
          moduleKey: 'module-simd',
          moduleName: 'SIMD 核心模块',
          relatedModuleKeys: ['lib-simd'],
          riskCounts: { HIGH: 20, MEDIUM: 7 },
          typeCounts: { INTRINSIC: 27 }
        }
      ],
      page: 1,
      pageSize: 10,
      total: 1
    })
    renderWithQueryClient(
      <AssessmentReport projectId="project-1" stage={stage} visible onClose={vi.fn()} />
    )

    await user.click(await screen.findByRole('button', { name: /SIMD 核心模块/ }))

    expect(screen.getByRole('combobox', { name: '模块问题类型' })).toBeInTheDocument()
  })

  it('keeps dependency risks in the overview when the report has no code findings', async () => {
    mockReport('SUCCEEDED', false)
    renderWithQueryClient(
      <AssessmentReport projectId="project-1" stage={stage} visible onClose={vi.fn()} />
    )

    expect(await screen.findByText('总体风险')).toBeInTheDocument()
    expect(screen.getByText('中')).toBeInTheDocument()
    expect(screen.queryByText('未发现')).not.toBeInTheDocument()
    expect(screen.queryByText('未发现需要迁移的代码问题')).not.toBeInTheDocument()
    expect(screen.queryByText(/另有 1 项依赖迁移风险/)).not.toBeInTheDocument()
    expect(screen.getByText('依赖问题')).toBeInTheDocument()
    expect(screen.getByText('1 项')).toBeInTheDocument()
    expect(screen.queryByLabelText('搜索模块、文件或问题')).not.toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: '代码模块' })).not.toBeInTheDocument()
    expect(getProjectEvaluationReportCodeModules).not.toHaveBeenCalled()
    expect(screen.queryByText('校验与追溯')).not.toBeInTheDocument()
    expect(getProjectEvaluationReportInputs).not.toHaveBeenCalled()
  })

  it('renders overall risk directly from the report risk field when there are no issues', async () => {
    mockReport('SUCCEEDED', false, 0)
    renderWithQueryClient(
      <AssessmentReport projectId="project-1" stage={stage} visible onClose={vi.fn()} />
    )

    expect(await screen.findByText('提示')).toBeInTheDocument()
    expect(screen.queryByText('未发现')).not.toBeInTheDocument()
    expect(
      screen.queryByText('代码扫描已完成，本次报告没有可归入模块的 Finding')
    ).not.toBeInTheDocument()
    expect(screen.queryByText('依赖清单')).not.toBeInTheDocument()
  })

  it('loads an omitted source range only when the user expands it', async () => {
    const user = userEvent.setup()
    mockReport('SUCCEEDED')
    getProjectEvaluationReportFileContext
      .mockResolvedValueOnce({
        blocks: [
          {
            endLine: 70,
            kind: 'OMITTED',
            lineCount: 25,
            lines: [],
            loadable: true,
            reason: 'DISTANT_FINDING_GAP',
            startLine: 46
          }
        ],
        contentHash: 'hash-1',
        contextAvailable: true,
        fileKey: 'file-fmadd',
        findings: [],
        relativePath: 'src/simd/fmadd.c',
        reportId: 'report-1',
        sourceRevisionId: 'source-1',
        totalLines: 120,
        unavailableReason: null
      })
      .mockResolvedValueOnce({
        blocks: [
          {
            endLine: 46,
            kind: 'CODE',
            lineCount: 1,
            lines: [{ highlights: [], lineNumber: 46, text: 'expanded source line' }],
            loadable: false,
            reason: null,
            startLine: 46
          }
        ],
        contentHash: 'hash-1',
        contextAvailable: true,
        fileKey: 'file-fmadd',
        findings: [],
        relativePath: 'src/simd/fmadd.c',
        reportId: 'report-1',
        sourceRevisionId: 'source-1',
        totalLines: 120,
        unavailableReason: null
      })
    renderWithQueryClient(
      <AssessmentReport projectId="project-1" stage={stage} visible onClose={vi.fn()} />
    )

    await user.click(await screen.findByRole('button', { name: /SIMD 核心模块/ }))
    await user.click(await screen.findByRole('button', { name: /src\/simd\/fmadd.c/ }))
    await user.click(await screen.findByRole('button', { name: /加载第 46–70 行/ }))

    expect(await screen.findByText('expanded source line')).toBeInTheDocument()
    expect(getProjectEvaluationReportFileContext).toHaveBeenLastCalledWith(
      'report-1',
      'file-fmadd',
      { fromLine: 46, lines: 25 }
    )
  })

  it('closes the previous finding tooltip when selecting another finding', async () => {
    const user = userEvent.setup()
    mockReport('SUCCEEDED')
    getProjectEvaluationReportCodeFindings.mockResolvedValue({
      hasMore: false,
      items: [
        {
          columnEnd: 25,
          columnStart: 10,
          findingType: 'INTRINSIC',
          id: 'finding-44',
          issueTitle: 'x86 Intrinsic 调用',
          lineEnd: 44,
          lineStart: 44,
          locationAvailable: true,
          recommendation: '第一条较长的迁移建议。',
          relativePath: 'src/simd/fmadd.c',
          riskLevel: 'HIGH',
          sourceDetail: {},
          sourceRefId: 'source-finding-44',
          symbol: '_mm_fmadd_pd'
        },
        {
          columnEnd: 18,
          columnStart: 4,
          findingType: 'HEADER',
          id: 'finding-72',
          issueTitle: 'x86 Header 引用',
          lineEnd: 72,
          lineStart: 72,
          locationAvailable: true,
          recommendation: '第二条较长的迁移建议。',
          relativePath: 'src/simd/fmadd.c',
          riskLevel: 'MEDIUM',
          sourceDetail: {},
          sourceRefId: 'source-finding-72',
          symbol: '#include <immintrin.h>'
        }
      ],
      page: 1,
      pageSize: 20,
      total: 2
    })
    renderWithQueryClient(
      <AssessmentReport projectId="project-1" stage={stage} visible onClose={vi.fn()} />
    )

    await user.click(await screen.findByRole('button', { name: /SIMD 核心模块/ }))
    await user.click(await screen.findByRole('button', { name: /src\/simd\/fmadd.c/ }))

    const firstFinding = await screen.findByRole('button', { name: /第一条较长的迁移建议/ })
    const secondFinding = screen.getByRole('button', { name: /第二条较长的迁移建议/ })
    await user.hover(screen.getByText('第一条较长的迁移建议。'))
    expect(await screen.findByRole('tooltip')).toHaveTextContent('第一条较长的迁移建议。')

    await user.click(secondFinding)

    expect(firstFinding).toHaveAttribute('aria-expanded', 'false')
    expect(secondFinding).toHaveAttribute('aria-expanded', 'true')
    await waitFor(() => expect(screen.queryByRole('tooltip')).not.toBeInTheDocument())
  })

  it('uses an icon-only refresh action', async () => {
    renderAssessmentReport()

    const refreshButton = await screen.findByRole('button', { name: '刷新' })
    expect(refreshButton).not.toHaveTextContent('刷新')
  })

  it('renders a ready state when prerequisites are met and no report is available', async () => {
    mockReport()
    getProjectEvaluationReportLatest.mockResolvedValue({
      context: { runtimeEnvironmentVersion: 1, sourceRevisionId: 'source-1' },
      execution: null,
      freshness: 'EMPTY',
      result: null
    })
    renderWithQueryClient(
      <AssessmentReport projectId="project-1" stage={stage} visible onClose={vi.fn()} />
    )

    expect(await screen.findByText('已满足评估报告条件')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveAttribute('data-variant', 'ready')
    expect(screen.getByRole('button', { name: '生成评估报告' })).toBeInTheDocument()
  })

  it('shows every missing generation prerequisite and its navigation action', async () => {
    mockReport()
    getProjectStageReadiness.mockResolvedValue({
      context: { runtimeEnvironmentVersion: 1, sourceRevisionId: 'source-1' },
      prerequisites: [
        {
          details: {},
          key: 'KB_SCAN',
          reasonCode: 'KB_SCAN_MISSING',
          referenceId: null,
          status: 'MISSING'
        },
        {
          details: {},
          key: 'DEPENDENCY_ANALYSIS',
          reasonCode: 'DEPENDENCY_ANALYSIS_MISSING',
          referenceId: null,
          status: 'MISSING'
        },
        {
          details: { count: 0 },
          key: 'INCLUDED_FINDINGS',
          reasonCode: 'INCLUDED_FINDINGS_MISSING',
          referenceId: null,
          status: 'MISSING'
        }
      ],
      stageId: 'report'
    })
    getProjectEvaluationReportLatest.mockResolvedValue({
      context: { runtimeEnvironmentVersion: 1, sourceRevisionId: 'source-1' },
      execution: null,
      freshness: 'EMPTY',
      result: null
    })

    renderWithQueryClient(
      <AssessmentReport projectId="project-1" stage={stage} visible onClose={vi.fn()} />
    )

    expect(await screen.findByText('暂不能执行评估报告')).toBeInTheDocument()
    expect(screen.getByText('请完成下方未满足的前置条件。')).toBeInTheDocument()
    expect(screen.getByText('知识库扫描')).toBeInTheDocument()
    expect(screen.getByText('依赖分析')).toBeInTheDocument()
    expect(screen.getByText('报告输入')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '生成评估报告' })).not.toBeInTheDocument()
  })

  it('uses the latest execution notice when report generation fails', async () => {
    mockReport('FAILED')

    renderWithQueryClient(
      <AssessmentReport projectId="project-1" stage={stage} visible onClose={vi.fn()} />
    )

    expect(await screen.findByText('评估报告执行失败')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新执行评估报告' })).toHaveClass('ant-btn-primary')
    expect(screen.queryByText('报告概览')).not.toBeInTheDocument()
  })

  it('creates a new report task after confirming regeneration', async () => {
    const user = userEvent.setup()
    createProjectEvaluationReport.mockResolvedValue({ id: 'task-2', status: 'QUEUED' })
    const { queryClient } = renderAssessmentReport()
    const invalidateQueries = vi.spyOn(queryClient, 'invalidateQueries')

    const manualRepair = await screen.findByRole('button', { name: '进入人工修复' })
    const regenerate = screen.getByRole('button', { name: '重新生成报告' })
    expect(manualRepair).toHaveClass('ant-btn-primary')
    expect(regenerate).toHaveClass('ant-btn-default')

    await user.click(regenerate)
    await user.click(screen.getByRole('button', { name: '确认重新生成' }))

    await waitFor(() => {
      expect(createProjectEvaluationReport).toHaveBeenCalledWith('project-1', 'source-1')
    })
    expect(invalidateQueries).not.toHaveBeenCalled()
  })

  it('does not refetch current report queries while a report issue detail is visible', async () => {
    const user = userEvent.setup()
    const { queryClient } = renderAssessmentReport()

    await user.click(await screen.findByText('依赖清单'))
    await user.click(await screen.findByText('libfoo 迁移替代性分析'))
    expect(await screen.findByText('只读报告问题 dependency-input-1')).toBeInTheDocument()
    const latestCalls = getProjectEvaluationReportLatest.mock.calls.length
    const readinessCalls = getProjectStageReadiness.mock.calls.length

    await queryClient.invalidateQueries({ queryKey: ['project-evaluation-reports', 'project-1'] })
    await queryClient.invalidateQueries({
      queryKey: ['project-stage-readiness', 'project-1', 'report']
    })

    expect(getProjectEvaluationReportLatest).toHaveBeenCalledTimes(latestCalls)
    expect(getProjectStageReadiness).toHaveBeenCalledTimes(readinessCalls)
  })

  it('does not restart current report queries when closing from an issue detail', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderAssessmentReport(onClose)

    await user.click(await screen.findByText('依赖清单'))
    await user.click(await screen.findByText('libfoo 迁移替代性分析'))
    expect(await screen.findByText('只读报告问题 dependency-input-1')).toBeInTheDocument()
    const latestCalls = getProjectEvaluationReportLatest.mock.calls.length
    const readinessCalls = getProjectStageReadiness.mock.calls.length

    await user.click(screen.getByRole('button', { name: '关闭测试 Drawer' }))

    expect(onClose).toHaveBeenCalledOnce()
    expect(getProjectEvaluationReportLatest).toHaveBeenCalledTimes(latestCalls)
    expect(getProjectStageReadiness).toHaveBeenCalledTimes(readinessCalls)
  })

  it('downloads the report using the backend download endpoint', async () => {
    const user = userEvent.setup()
    renderAssessmentReport()

    await user.click(await screen.findByRole('button', { name: '下载 JSON' }))

    expect(window.open).toHaveBeenCalledWith(
      '/api/reports/report-1/download?format=JSON',
      '_blank',
      'noopener,noreferrer'
    )
  })
})
