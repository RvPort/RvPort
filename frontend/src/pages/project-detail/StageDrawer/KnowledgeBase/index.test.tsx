import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { renderWithStrictQueryClient as renderWithQueryClient } from '@/test/strict-mode-utils'
import type { ProjectDomainLatest } from '@/types/project'
import type {
  ProjectKnowledgeBaseFinding,
  ProjectKnowledgeBaseScan
} from '@/types/project/knowledge-base'

import { StagePresentationContext } from '../components/StagePresentationContext'
import KnowledgeBase from '.'

const {
  getProjectKnowledgeBaseScans,
  getProjectKnowledgeBaseScan,
  getProjectKnowledgeBaseScanLatest,
  getProjectKnowledgeBaseScanArtifacts,
  getProjectKnowledgeBaseFindings,
  getProjectKnowledgeBaseFinding,
  getProjectKnowledgeBaseFindingCodeContext,
  getProjectKnowledgeBaseAiExtensionReadiness,
  getProjectKnowledgeBaseAiExtensions,
  getProjectStageReadiness,
  getProjectMigrationInputs,
  createProjectKnowledgeBaseScan,
  createProjectKnowledgeBaseAiExtension,
  importProjectMigrationInputs,
  updateProjectMigrationInput
} = vi.hoisted(() => ({
  getProjectKnowledgeBaseScans: vi.fn(),
  getProjectKnowledgeBaseScan: vi.fn(),
  getProjectKnowledgeBaseScanLatest: vi.fn(),
  getProjectKnowledgeBaseScanArtifacts: vi.fn(),
  getProjectKnowledgeBaseFindings: vi.fn(),
  getProjectKnowledgeBaseFinding: vi.fn(),
  getProjectKnowledgeBaseFindingCodeContext: vi.fn(),
  getProjectKnowledgeBaseAiExtensionReadiness: vi.fn(),
  getProjectKnowledgeBaseAiExtensions: vi.fn(),
  getProjectStageReadiness: vi.fn(),
  getProjectMigrationInputs: vi.fn(),
  createProjectKnowledgeBaseScan: vi.fn(),
  createProjectKnowledgeBaseAiExtension: vi.fn(),
  importProjectMigrationInputs: vi.fn(),
  updateProjectMigrationInput: vi.fn()
}))

vi.mock('@/api/project/knowledge-base', () => ({
  createProjectKnowledgeBaseAiExtension,
  createProjectKnowledgeBaseScan,
  getProjectKnowledgeBaseFinding,
  getProjectKnowledgeBaseFindingCodeContext,
  getProjectKnowledgeBaseAiExtensionReadiness,
  getProjectKnowledgeBaseAiExtensions,
  getProjectKnowledgeBaseFindings,
  getProjectKnowledgeBaseScan,
  getProjectKnowledgeBaseScanArtifactDownloadUrl: vi.fn(),
  getProjectKnowledgeBaseScanArtifacts,
  getProjectKnowledgeBaseScanLatest,
  getProjectKnowledgeBaseScans
}))

vi.mock('@/components/MonacoEditor', () => ({ default: () => <div aria-label="源码编辑器" /> }))

vi.mock('@/api/project/workflow', () => ({ getProjectStageReadiness }))
vi.mock('@/api/project/migration-input', () => ({
  getProjectMigrationInputs,
  importProjectMigrationInputs,
  updateProjectMigrationInput
}))

vi.mock('@/components/BaseDrawer', () => ({
  default: ({
    children,
    extra,
    footer,
    title
  }: {
    children: React.ReactNode
    extra?: React.ReactNode
    footer?: React.ReactNode
    title: React.ReactNode
  }) => (
    <div>
      {title}
      {extra}
      {children}
      {footer}
    </div>
  )
}))

const stage = { id: 'kb', order: 6, title: '知识库扫描', subtitle: '', status: 'done' as const }
const scan: ProjectKnowledgeBaseScan = {
  id: 'scan-1',
  taskId: 'task-1',
  sourceRevisionId: 'source-1',
  kbVersion: '2026.07',
  scope: { mode: 'ALL', paths: [] },
  status: 'SUCCEEDED',
  summary: {
    diagnosticCount: 1,
    findingCount: 6,
    schemaVersion: '2.0',
    scan: {
      tool: { name: 'kb-scanner', version: '2.0.0' },
      scope: { staticAnalysisOnly: true, targetArchitecture: 'riscv64' },
      knowledgeSources: [{ id: 'llvm-x86', kind: 'compiler-derived', sha256: 'abc123' }]
    },
    diagnostics: [],
    summary: {
      files: 4,
      findings: 6,
      hints: 2,
      inactiveOrGuarded: 0,
      issues: 4,
      blocking: 4,
      portabilityGaps: 2,
      references: 0,
      readiness: 'x86_only',
      status: 'complete'
    }
  },
  startedAt: '2026-07-22T00:00:00Z',
  finishedAt: '2026-07-22T00:01:00Z'
}

const findingSummary: ProjectKnowledgeBaseFinding = {
  id: 'finding-1',
  category: 'x86_intrinsic',
  description: '被选中的源码调用了 AVX intrinsic。',
  filePath: 'src/simd.cpp',
  lineStart: 42,
  lineEnd: 42,
  ruleId: 'finding-intrinsic-call',
  severity: 'critical',
  status: 'DETECTED',
  suggestion: '改用 portable fallback',
  symbol: '_mm256_add_ps',
  title: 'AVX intrinsic 被 RISC-V 构建路径选中',
  fact: {
    kind: 'intrinsic_call',
    symbol: '_mm256_add_ps',
    matchedText: '_mm256_add_ps',
    primaryLocation: {
      file: 'src/simd.cpp',
      span: { start: { line: 42, column: 12 }, end: { line: 42, column: 25 } },
      context: {
        status: 'complete',
        lines: [{ line: 42, text: 'return _mm256_add_ps(a, b);' }],
        highlights: [{ line: 42, startColumn: 12, endColumn: 25 }]
      }
    }
  },
  conclusion: {
    title: 'AVX intrinsic 被 RISC-V 构建路径选中',
    severity: 'critical',
    confidence: 1,
    findingType: 'x86_intrinsic',
    actionable: true,
    blocking: true,
    archReachability: 'selected',
    status: 'complete',
    reason: '被选中的源码调用了 AVX intrinsic。',
    suggestion: '改用 portable fallback'
  }
}

function renderKnowledgeBase(
  latestScan: typeof scan | null = scan,
  freshness: 'CURRENT' | 'STALE' | 'EMPTY' = latestScan ? 'CURRENT' : 'EMPTY',
  presentation: 'panel' | 'drawer' = 'drawer',
  onDirtyChange?: (dirty: boolean) => void
) {
  getProjectKnowledgeBaseScanLatest.mockResolvedValue({
    context: {
      runtimeEnvironmentVersion: null,
      sourceRevisionId: latestScan?.sourceRevisionId ?? 'source-1'
    },
    execution: latestScan
      ? {
          createdAt: latestScan.startedAt,
          errorCode: null,
          errorMessage: null,
          finishedAt: latestScan.finishedAt,
          id: latestScan.id,
          message: null,
          progress: latestScan.status === 'SUCCEEDED' ? 100 : null,
          startedAt: latestScan.startedAt,
          status: latestScan.status,
          taskId: latestScan.taskId
        }
      : null,
    freshness,
    result: latestScan
  })
  getProjectKnowledgeBaseScans.mockResolvedValue({
    hasMore: false,
    items: [],
    page: 1,
    pageSize: 20,
    total: 0
  })
  getProjectKnowledgeBaseScan.mockResolvedValue(latestScan ?? undefined)
  getProjectKnowledgeBaseScanArtifacts.mockResolvedValue({
    hasMore: false,
    items: [],
    page: 1,
    pageSize: 100,
    total: 0
  })
  getProjectMigrationInputs.mockResolvedValue({
    hasMore: false,
    items: [],
    page: 1,
    pageSize: 200,
    total: 0
  })
  getProjectKnowledgeBaseFindings.mockResolvedValue({
    hasMore: false,
    items: [findingSummary],
    page: 1,
    pageSize: 20,
    total: 1
  })
  return renderWithQueryClient(
    <StagePresentationContext.Provider value={presentation}>
      <KnowledgeBase
        onStageChange={vi.fn()}
        projectId="project-1"
        stage={stage}
        visible
        onClose={vi.fn()}
        onDirtyChange={onDirtyChange}
      />
    </StagePresentationContext.Provider>
  )
}

describe('Knowledge base stage drawer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getProjectKnowledgeBaseAiExtensionReadiness.mockResolvedValue({
      findingCount: 6,
      kbScanRunId: 'scan-1',
      model: { model: 'gpt-test' },
      modelConfigured: true,
      ready: true,
      reason: null
    })
    getProjectKnowledgeBaseAiExtensions.mockResolvedValue({
      hasMore: false,
      items: [],
      page: 1,
      pageSize: 1,
      total: 0
    })
    getProjectStageReadiness.mockResolvedValue({
      context: { runtimeEnvironmentVersion: null, sourceRevisionId: 'source-1' },
      prerequisites: [
        {
          details: {},
          key: 'ACTIVE_SOURCE',
          reasonCode: 'ACTIVE_SOURCE_READY',
          referenceId: 'source-1',
          status: 'SATISFIED'
        },
        {
          details: {},
          key: 'X86_BUILD',
          reasonCode: 'X86_BUILD_SUCCEEDED',
          referenceId: 'build-1',
          status: 'SATISFIED'
        }
      ],
      stageId: 'kb'
    })
  })

  it('renders findings from the latest backend scan run', async () => {
    renderKnowledgeBase()
    expect(await screen.findByText('src/simd.cpp')).toBeInTheDocument()
    expect(screen.getByText('发现项')).toBeInTheDocument()
    expect(screen.getByText('扫描文件')).toBeInTheDocument()
    expect(screen.getByText('结果构成')).toBeInTheDocument()
    expect(screen.getByText('处理关注')).toBeInTheDocument()
    expect(screen.getByText('目标路径判断')).toBeInTheDocument()
    expect(screen.queryByText('结果属性')).not.toBeInTheDocument()
    expect(screen.getByText('问题')).toBeInTheDocument()
    expect(screen.getByText('阻塞项')).toBeInTheDocument()
    expect(screen.getByText('缺少可移植替代')).toBeInTheDocument()
    expect(screen.getByText('当前目标未选中')).toBeInTheDocument()
    expect(screen.queryByText('风险分布')).not.toBeInTheDocument()
    expect(screen.getByText('被选中的源码调用了 AVX intrinsic。')).toBeInTheDocument()
    expect(getProjectKnowledgeBaseScanLatest).toHaveBeenCalledWith('project-1')
    expect(getProjectKnowledgeBaseFindings).toHaveBeenCalledWith('scan-1', {
      filePath: undefined,
      page: 1,
      pageSize: 10,
      severity: undefined,
      status: undefined
    })
    expect(getProjectKnowledgeBaseScans).not.toHaveBeenCalled()
    expect(getProjectKnowledgeBaseScan).not.toHaveBeenCalled()
    expect(getProjectStageReadiness).toHaveBeenCalledWith('project-1', 'kb')
    expect(screen.getByRole('button', { name: '生成 AI 建议' })).toBeEnabled()
  })

  it('starts AI suggestions for the current successful scan', async () => {
    const user = userEvent.setup()
    createProjectKnowledgeBaseAiExtension.mockResolvedValue({
      run: {
        agentOutputArtifactId: null,
        agentRuntimeId: 'agent-1',
        createdAt: '2026-09-11T06:00:00Z',
        finishedAt: null,
        id: 'ai-run-1',
        inputArtifactId: null,
        kbScanRunId: 'scan-1',
        modelProfileId: 'model-1',
        outputArtifactId: null,
        projectId: 'project-1',
        sourceRevisionId: 'source-1',
        startedAt: null,
        status: 'QUEUED',
        summary: {},
        taskId: 'ai-task-1',
        updatedAt: '2026-09-11T06:00:00Z'
      },
      task: { id: 'ai-task-1', status: 'QUEUED' }
    })

    renderKnowledgeBase()
    await user.click(await screen.findByRole('button', { name: '生成 AI 建议' }))
    await user.click(screen.getByRole('button', { name: '开始生成' }))

    await waitFor(() =>
      expect(createProjectKnowledgeBaseAiExtension).toHaveBeenCalledWith('project-1', 'scan-1')
    )
    expect(await screen.findByText('排队中')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /生成中/ })).toBeDisabled()
  })

  it('explains why AI suggestions are unavailable', async () => {
    getProjectKnowledgeBaseAiExtensionReadiness.mockResolvedValue({
      findingCount: 6,
      kbScanRunId: 'scan-1',
      model: { errorCode: 'KB_AI_EXTENSION_MODEL_NOT_CONFIGURED' },
      modelConfigured: false,
      ready: false,
      reason: 'MODEL_NOT_CONFIGURED'
    })

    renderKnowledgeBase()

    expect(await screen.findByText('尚未配置可用的知识库 AI 扩展模型')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '生成 AI 建议' })).toBeDisabled()
  })

  it('distinguishes unavailable V2 metrics from a zero count', async () => {
    renderKnowledgeBase({
      ...scan,
      summary: {
        ...scan.summary,
        summary: {
          ...(scan.summary.summary as Record<string, unknown>),
          portabilityGaps: null,
          status: 'partial'
        }
      }
    })

    expect(await screen.findByTitle('当前扫描未完成此项分析')).toHaveTextContent('--')
    expect(screen.getByText('部分输入未完成，当前数据仅反映已分析范围。')).toBeInTheDocument()
  })

  it('looks up the source before confirming a finding when import omits its reference', async () => {
    importProjectMigrationInputs.mockResolvedValue({
      created: 1,
      items: [{ id: 'migration-input-1', status: 'CANDIDATE' }],
      requested: 1,
      skipped: 0,
      sourceType: 'KB_FINDING'
    })
    updateProjectMigrationInput.mockResolvedValue({ id: 'migration-input-1', status: 'CONFIRMED' })
    renderKnowledgeBase()
    getProjectMigrationInputs.mockResolvedValue({
      hasMore: false,
      items: [{ id: 'migration-input-1', sourceRefId: 'finding-1', status: 'CANDIDATE' }],
      page: 1,
      pageSize: 200,
      total: 1
    })
    fireEvent.click(await screen.findByRole('button', { name: '纳入报告' }))

    await waitFor(() => {
      expect(importProjectMigrationInputs).toHaveBeenCalledWith('project-1', 'source-1', {
        sourceRefIds: ['finding-1'],
        sourceType: 'KB_FINDING'
      })
      expect(updateProjectMigrationInput).toHaveBeenCalledWith('migration-input-1', {
        status: 'CONFIRMED'
      })
      expect(getProjectMigrationInputs).toHaveBeenCalledWith('project-1', 'source-1', {
        page: 1,
        pageSize: 200,
        sourceType: 'KB_FINDING'
      })
    })
  }, 20_000)

  it('opens current scan information from the scan ID', async () => {
    const user = userEvent.setup()
    renderKnowledgeBase()
    getProjectKnowledgeBaseScanArtifacts.mockResolvedValueOnce({
      hasMore: false,
      items: [
        {
          artifactType: 'SCAN_RESULT_JSON',
          createdAt: '2026-07-22T00:01:00Z',
          id: 'artifact-1',
          schemaVersion: '2.0',
          sha256: 'abc123',
          sizeBytes: 2048,
          summary: {}
        }
      ],
      page: 1,
      pageSize: 100,
      total: 1
    })

    await screen.findByText('src/simd.cpp')
    await user.click(screen.getByRole('button', { name: '查看扫描信息' }))
    expect(await screen.findByText('规则与扫描器')).toBeInTheDocument()
    expect(screen.getByText('kb-scanner')).toBeInTheDocument()
    expect(screen.getByText('llvm-x86')).toBeInTheDocument()
    expect(screen.getByText('扫描结果（JSON）')).toBeInTheDocument()
    expect(getProjectKnowledgeBaseScanArtifacts).toHaveBeenCalledWith('scan-1', {
      page: 1,
      pageSize: 200
    })
    expect(screen.queryByText('扫描记录')).not.toBeInTheDocument()
  })

  it('keeps a running scan in the execution state', async () => {
    renderKnowledgeBase({ ...scan, status: 'RUNNING' })
    expect(await screen.findByText('知识库扫描正在执行')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '查看历史记录' })).toBeInTheDocument()
  })

  it('keeps the latest failed scan visible and offers a business retry', async () => {
    renderKnowledgeBase({
      ...scan,
      status: 'FAILED',
      summary: { error: 'KB Scanner 执行失败', phase: 'SCAN' }
    })

    expect(await screen.findByText('知识库扫描执行失败')).toBeInTheDocument()
    expect(screen.getByText('KB Scanner 执行失败')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '查看日志' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '查看历史记录' })).toHaveClass('ant-btn-link')
    expect(screen.getByRole('button', { name: '重新执行知识库扫描' })).toHaveClass(
      'ant-btn-primary'
    )
    expect(screen.queryByRole('button', { name: '重试加载' })).not.toBeInTheDocument()
  })

  it('does not present a stale latest result as the current source scan', async () => {
    renderKnowledgeBase({ ...scan, currentSourceRevision: false }, 'STALE')

    expect(await screen.findByText('已满足知识库扫描条件')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveAttribute('data-variant', 'ready')
    expect(screen.getByText('当前源码')).toBeInTheDocument()
    expect(screen.getByText('已满足')).toBeInTheDocument()
    expect(screen.queryByText('src/simd.cpp')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '查看历史记录' })).toBeInTheDocument()
  })

  it('orders successful scan actions as rerun followed by the history link', async () => {
    renderKnowledgeBase()

    await screen.findByText('src/simd.cpp')
    const rescan = screen.getByRole('button', { name: '重新扫描' })
    const history = screen.getByRole('button', { name: '查看历史记录' })

    expect(rescan).toHaveClass('ant-btn-default')
    expect(history).toHaveClass('ant-btn-link')
    expect(history.compareDocumentPosition(rescan) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('opens the paginated scan history with stale source records included', async () => {
    const user = userEvent.setup()
    const { queryClient } = renderKnowledgeBase()
    await screen.findByText('src/simd.cpp')
    getProjectKnowledgeBaseScans.mockResolvedValueOnce({
      hasMore: false,
      items: [{ ...scan, currentSourceRevision: false, id: 'scan-history-1' }],
      page: 1,
      pageSize: 20,
      total: 1
    })

    await user.click(screen.getByRole('button', { name: '查看历史记录' }))

    expect(await screen.findByText('历史源码')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '返回最新结果' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '刷新扫描记录' })).toBeInTheDocument()

    getProjectKnowledgeBaseFinding.mockResolvedValue({ ...findingSummary, evidence: {} })
    getProjectKnowledgeBaseFindingCodeContext.mockResolvedValue({
      startLine: 41,
      endLine: 43,
      highlightStartLine: 42,
      highlightEndLine: 42,
      lines: []
    })

    getProjectKnowledgeBaseScans.mockResolvedValueOnce({
      hasMore: false,
      items: [{ ...scan, currentSourceRevision: false, id: 'scan-history-1' }],
      page: 1,
      pageSize: 20,
      total: 1
    })
    await queryClient.invalidateQueries({
      queryKey: ['project-knowledge-base-scans', 'project-1']
    })
    await waitFor(() => expect(getProjectKnowledgeBaseScans).toHaveBeenCalledTimes(2))
    expect(getProjectKnowledgeBaseScanLatest).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: '查看' }))
    expect(await screen.findByText('扫描记录概览')).toBeInTheDocument()
    expect(screen.getByText('只读记录')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新扫描' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '生成 AI 建议' })).not.toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'src/simd.cpp' }))
    expect(await screen.findByText('历史记录 · 只读')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '纳入报告' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /排\s*除/ })).not.toBeInTheDocument()
    expect(getProjectKnowledgeBaseScans).toHaveBeenCalledWith('project-1', {
      includeStale: true,
      sortBy: 'startedAt',
      sortDirection: 'desc',
      page: 1,
      pageSize: 20
    })
  })

  it('submits a backend-compatible scan request', async () => {
    const user = userEvent.setup()
    createProjectKnowledgeBaseScan.mockResolvedValue({ id: 'task-2' })
    renderKnowledgeBase(null)
    await user.click(await screen.findByRole('button', { name: '新建扫描' }))
    await user.click(await screen.findByRole('button', { name: '开始扫描' }))
    await waitFor(() =>
      expect(createProjectKnowledgeBaseScan).toHaveBeenCalledWith(
        'project-1',
        expect.objectContaining({ language: 'C/C++', scope: './' })
      )
    )
  })

  it.each(['panel', 'drawer'] as const)(
    'protects the same scan scope as a new draft after a successful submission in %s',
    async (presentation) => {
      const user = userEvent.setup()
      const onDirtyChange = vi.fn()
      createProjectKnowledgeBaseScan.mockResolvedValue({ id: 'task-2' })
      renderKnowledgeBase(null, 'EMPTY', presentation, onDirtyChange)
      await user.click(await screen.findByRole('button', { name: '新建扫描' }))
      const scope = await screen.findByLabelText('扫描范围')
      await user.clear(scope)
      await user.type(scope, 'src/')
      await waitFor(() => expect(onDirtyChange).toHaveBeenLastCalledWith(true))
      await user.click(screen.getByRole('button', { name: '开始扫描' }))
      expect(await screen.findByText('已提交，正在同步状态')).toBeInTheDocument()
      getProjectKnowledgeBaseScanLatest.mockResolvedValue({
        context: { sourceRevisionId: 'source-1', runtimeEnvironmentVersion: null },
        freshness: 'CURRENT',
        execution: null,
        result: { ...scan, id: 'scan-after-submit' }
      })
      await user.click(screen.getByRole('button', { name: '刷新知识库扫描' }))
      await screen.findByRole('button', { name: '重新扫描' })
      expect(createProjectKnowledgeBaseScan).toHaveBeenCalledWith(
        'project-1',
        expect.objectContaining({ scope: 'src/' })
      )
      await waitFor(() => expect(onDirtyChange).toHaveBeenLastCalledWith(false))
      await user.click(screen.getByRole('button', { name: '重新扫描' }))
      await user.click(await screen.findByRole('button', { name: /继\s*续/ }))
      const newScope = await screen.findByLabelText('扫描范围')
      expect(newScope).toHaveValue('./')
      await user.clear(newScope)
      await user.type(newScope, 'src/')
      await waitFor(() => expect(onDirtyChange).toHaveBeenLastCalledWith(true))
    }
  )

  it.each(['panel', 'drawer'] as const)(
    'restores findings filters and pagination after detail in %s',
    async (presentation) => {
      const user = userEvent.setup()
      getProjectKnowledgeBaseFinding.mockResolvedValue({ ...findingSummary, evidence: {} })
      getProjectKnowledgeBaseFindingCodeContext.mockResolvedValue({ lines: [] })
      renderKnowledgeBase(scan, 'CURRENT', presentation)
      await screen.findByRole('button', { name: 'src/simd.cpp' })
      getProjectKnowledgeBaseFindings.mockImplementation(async (_scanId, query) => ({
        hasMore: true,
        items: [findingSummary],
        page: query.page,
        pageSize: query.pageSize,
        total: 45
      }))
      await user.type(screen.getByRole('textbox', { name: '搜索文件路径' }), 'src/')
      await waitFor(() =>
        expect(getProjectKnowledgeBaseFindings).toHaveBeenLastCalledWith(
          'scan-1',
          expect.objectContaining({ filePath: 'src/', page: 1 })
        )
      )
      await user.click(await screen.findByTitle('2'))
      await waitFor(() =>
        expect(getProjectKnowledgeBaseFindings).toHaveBeenLastCalledWith(
          'scan-1',
          expect.objectContaining({ filePath: 'src/', page: 2, pageSize: 10 })
        )
      )
      await user.click(screen.getByRole('button', { name: 'src/simd.cpp' }))
      expect(screen.queryByRole('textbox', { name: '搜索文件路径' })).not.toBeInTheDocument()
      await user.click(await screen.findByRole('button', { name: '返回知识库扫描' }))
      expect(await screen.findByRole('textbox', { name: '搜索文件路径' })).toHaveValue('src/')
      await waitFor(() =>
        expect(getProjectKnowledgeBaseFindings).toHaveBeenLastCalledWith(
          'scan-1',
          expect.objectContaining({ filePath: 'src/', page: 2, pageSize: 10 })
        )
      )
      expect(screen.getByTitle('2')).toHaveClass('ant-pagination-item-active')
    }
  )

  it('locks scan inputs and local back/cancel while creation is pending and unlocks after failure', async () => {
    const user = userEvent.setup()
    let rejectRequest!: (reason: Error) => void
    createProjectKnowledgeBaseScan.mockImplementation(
      () =>
        new Promise((_, reject) => {
          rejectRequest = reject
        })
    )
    renderKnowledgeBase(null)
    await user.click(await screen.findByRole('button', { name: '新建扫描' }))
    const scope = await screen.findByLabelText('扫描范围')
    await user.clear(scope)
    await user.type(scope, 'src/')
    await user.click(await screen.findByRole('button', { name: '开始扫描' }))
    await waitFor(() => expect(scope).toBeDisabled())
    expect(screen.getByRole('button', { name: /取\s*消/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: '返回知识库扫描' })).toBeDisabled()
    rejectRequest(new Error('扫描请求失败'))
    await waitFor(() => expect(scope).toBeEnabled())
    expect(scope).toHaveValue('src/')
    expect(screen.getByRole('button', { name: /取\s*消/ })).toBeEnabled()
  })

  it('shows a ready state when the current source has no scan record', async () => {
    renderKnowledgeBase(null)

    expect(await screen.findByText('已满足知识库扫描条件')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveAttribute('data-variant', 'ready')
    expect(screen.getByText('当前源码')).toBeInTheDocument()
    expect(screen.getByText('已满足')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '新建扫描' })).toBeInTheDocument()
  })

  it('blocks creating a scan without an active source revision', async () => {
    getProjectStageReadiness.mockResolvedValue({
      context: { runtimeEnvironmentVersion: null, sourceRevisionId: null },
      prerequisites: [
        {
          details: {},
          key: 'ACTIVE_SOURCE',
          reasonCode: 'ACTIVE_SOURCE_MISSING',
          referenceId: null,
          status: 'MISSING'
        }
      ],
      stageId: 'kb'
    })
    renderKnowledgeBase(null)

    expect(await screen.findByText('暂不能执行知识库扫描')).toBeInTheDocument()
    expect(screen.getByText('请完成下方未满足的前置条件。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '前往源码管理' })).toBeInTheDocument()
    expect(screen.queryByText('尚未执行知识库扫描')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '新建扫描' })).not.toBeInTheDocument()
    expect(createProjectKnowledgeBaseScan).not.toHaveBeenCalled()
  })

  it('requires a successful x86 build before starting a scan', async () => {
    getProjectStageReadiness.mockResolvedValue({
      context: { runtimeEnvironmentVersion: 1, sourceRevisionId: 'source-1' },
      prerequisites: [
        {
          details: {},
          key: 'ACTIVE_SOURCE',
          reasonCode: 'ACTIVE_SOURCE_READY',
          referenceId: 'source-1',
          status: 'SATISFIED'
        },
        {
          details: {},
          key: 'X86_BUILD',
          reasonCode: 'X86_BUILD_MISSING',
          referenceId: null,
          status: 'MISSING'
        }
      ],
      stageId: 'kb'
    })
    renderKnowledgeBase(null)

    expect(await screen.findByText('暂不能执行知识库扫描')).toBeInTheDocument()
    expect(screen.getByText('请完成下方未满足的前置条件。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '前往 x86 构建' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '新建扫描' })).not.toBeInTheDocument()
    expect(createProjectKnowledgeBaseScan).not.toHaveBeenCalled()
  })

  it('shows a request error instead of inferring a missing source when readiness fails', async () => {
    getProjectStageReadiness.mockRejectedValue(new Error('readiness unavailable'))

    renderKnowledgeBase(null)

    expect(await screen.findByText('知识库扫描加载失败')).toBeInTheDocument()
    expect(screen.queryByText('暂不能执行知识库扫描')).not.toBeInTheDocument()
  })

  it('loads a finding and source context together', async () => {
    const user = userEvent.setup()
    const finding = {
      ...findingSummary,
      evidence: {
        finding: {
          primaryOccurrenceId: 'occurrence-1'
        }
      },
      occurrences: [
        {
          configuration: 'riscv64-release',
          id: 'occurrence-1',
          kind: 'source-call',
          location: findingSummary.fact?.primaryLocation,
          selectionStatus: 'selected',
          status: 'complete'
        },
        {
          configuration: 'fallback-build',
          id: 'occurrence-2',
          kind: 'build_argument',
          location: {
            file: 'CMakeLists.txt',
            span: { start: { line: 18 }, end: { line: 18 } }
          },
          reasonCodes: ['missing_input'],
          selectionStatus: 'not_selected',
          status: 'partial'
        }
      ],
      evidenceChain: {
        status: 'complete',
        primaryPath: ['step-source', 'step-assessment'],
        steps: [
          {
            id: 'step-source',
            kind: 'intrinsic_call',
            title: '命中 _mm256_add_ps',
            status: 'complete',
            reason: '源码中存在直接调用',
            source: {
              file: 'include/simd_defs.h',
              span: { start: { line: 42 }, end: { line: 42 } },
              context: {
                status: 'complete',
                lines: [{ line: 42, text: 'return _mm256_add_ps(a, b);' }],
                highlights: [{ line: 42, startColumn: 8, endColumn: 21 }]
              }
            }
          },
          {
            id: 'step-assessment',
            kind: 'assessment',
            title: '阻塞 RISC-V 迁移',
            status: 'complete',
            reason: '当前没有可移植实现'
          }
        ],
        edges: [
          {
            id: 'edge-1',
            fromStepId: 'step-source',
            toStepId: 'step-assessment',
            kind: 'impacts_assessment'
          }
        ],
        breaks: []
      },
      migrationInputRefId: 'migration-input-1',
      migrationInputStatus: 'CANDIDATE'
    }
    getProjectKnowledgeBaseFinding
      .mockResolvedValueOnce(finding)
      .mockResolvedValue({ ...finding, migrationInputStatus: 'CONFIRMED' })
    getProjectKnowledgeBaseFindingCodeContext.mockResolvedValue({
      startLine: 41,
      endLine: 43,
      highlightStartLine: 42,
      highlightEndLine: 42,
      lines: [
        { lineNumber: 41, text: 'function add() {', highlighted: false },
        { lineNumber: 42, text: '__m256 x;', highlighted: true },
        { lineNumber: 43, text: '}', highlighted: false }
      ]
    })
    renderKnowledgeBase()
    await user.click(await screen.findByRole('button', { name: 'src/simd.cpp' }))
    const workspaceHeading = await screen.findByRole('heading', { name: '证据工作区' })
    const evidenceHeading = screen.getByRole('heading', { name: '判断依据' })
    const nextHeading = screen.getByRole('heading', { name: '建议下一步' })
    const factsHeading = screen.getByRole('heading', { name: '扫描事实' })
    expect(
      workspaceHeading.compareDocumentPosition(evidenceHeading) & Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy()
    expect(
      evidenceHeading.compareDocumentPosition(nextHeading) & Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy()
    expect(
      nextHeading.compareDocumentPosition(factsHeading) & Node.DOCUMENT_POSITION_FOLLOWING
    ).toBeTruthy()
    expect(screen.getByText('命中 _mm256_add_ps')).toBeInTheDocument()
    expect(screen.getByText('严重').closest('section')).toHaveAttribute('data-severity', 'critical')
    expect(screen.queryByRole('heading', { name: '代码证据' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('判断摘要')).toHaveTextContent('严重阻塞迁移判断置信度100%')
    expect(screen.getByText('报告状态')).toBeInTheDocument()
    expect(screen.getAllByText('未纳入报告')).toHaveLength(1)
    expect(screen.getByRole('button', { name: '纳入报告' })).toHaveClass('ant-btn-primary')
    expect(screen.queryByRole('button', { name: /排\s*除/ })).not.toBeInTheDocument()
    expect(screen.getByText('已展示接口返回的 3 行上下文')).toBeInTheDocument()
    expect(screen.getByLabelText('位置摘要')).toHaveTextContent(
      'source-call · 当前目标已选中命中位置'
    )
    expect(getProjectKnowledgeBaseScan).not.toHaveBeenCalled()
    const conclusionPanel = screen
      .getByRole('heading', { name: findingSummary.title })
      .closest('section')
    expect(conclusionPanel).not.toHaveTextContent('可处理')
    expect(conclusionPanel).not.toHaveTextContent('分析完整')
    expect(conclusionPanel).not.toHaveTextContent('已发现')
    expect(evidenceHeading.closest('section')).not.toHaveTextContent('关系：')

    const factsPanel = screen.getByText('扫描事实').closest('details')
    expect(factsPanel).not.toHaveAttribute('open')
    await user.click(screen.getByText('扫描事实'))
    expect(factsPanel).toHaveAttribute('open')
    expect(screen.getByText('规则 ID')).toBeVisible()

    await user.click(screen.getByRole('button', { name: '源码调用：命中 _mm256_add_ps' }))
    expect(screen.getByLabelText('代码证据')).toHaveTextContent(
      '命中 _mm256_add_ps源码中存在直接调用'
    )
    expect(screen.queryByText('展示扫描器随证据保存的源码片段')).not.toBeInTheDocument()
    expect(screen.getByText('include/simd_defs.h:42')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '返回主要代码' })).toHaveClass('ant-btn-text')
    await user.click(screen.getByRole('button', { name: '迁移判断：阻塞 RISC-V 迁移' }))
    expect(screen.getByLabelText('代码证据')).toHaveTextContent(
      '阻塞 RISC-V 迁移当前没有可移植实现'
    )
    expect(screen.getByText('所选判断没有直接源码，继续保留最近一次有效代码。')).toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: '选择 Finding 命中位置' }))
    await user.click(screen.getByText('位置 2 · CMakeLists.txt:18'))
    expect(screen.getByText('fallback-build')).toBeInTheDocument()
    expect(screen.getAllByText('当前目标未选中').length).toBeGreaterThan(0)
    expect(screen.getByText('缺少完成判断所需的输入')).toBeInTheDocument()
    expect(screen.getByText('该命中位置没有可用的代码上下文。')).toBeInTheDocument()
    const rawEvidence = screen.getByRole('heading', { name: '原始扫描数据' }).closest('details')
    expect(rawEvidence).not.toHaveAttribute('open')
    expect(screen.queryByText('用于诊断与复核，主要迁移判断已在上方整理。')).not.toBeInTheDocument()
    await user.click(screen.getByRole('heading', { name: '原始扫描数据' }))
    expect(rawEvidence).toHaveAttribute('open')
    expect(rawEvidence).toHaveTextContent('收起详情')
    expect(screen.getByRole('region', { name: '原始扫描数据代码视口' })).toContainElement(
      screen.getByLabelText('原始扫描数据')
    )
    updateProjectMigrationInput.mockResolvedValue({
      id: 'migration-input-1',
      status: 'CONFIRMED'
    })
    await user.click(screen.getByRole('button', { name: '纳入报告' }))
    await waitFor(() => {
      expect(getProjectKnowledgeBaseFinding).toHaveBeenCalledWith('finding-1')
      expect(getProjectKnowledgeBaseFindingCodeContext).toHaveBeenCalledWith('finding-1')
      expect(updateProjectMigrationInput).toHaveBeenCalledWith('migration-input-1', {
        status: 'CONFIRMED'
      })
    })
    expect(await screen.findAllByText('已纳入报告')).toHaveLength(1)
  })

  it('keeps a V2 finding usable when the backend has no source location', async () => {
    const user = userEvent.setup()
    const findingWithoutLocation = {
      ...findingSummary,
      id: 'finding-without-location',
      filePath: null,
      lineStart: null,
      lineEnd: null,
      fact: {
        kind: 'build_argument',
        matchedText: '-mavx2',
        primaryLocation: null
      },
      conclusion: {
        ...findingSummary.conclusion,
        title: '构建参数来源位置不完整',
        status: 'partial'
      },
      evidence: {},
      occurrences: [],
      evidenceChain: {
        status: 'partial',
        reasonCodes: ['missing_input'],
        steps: [],
        breaks: [{ id: 'break-1', stage: 'build-selection', reasonCode: 'missing_input' }]
      }
    }
    getProjectKnowledgeBaseFindings.mockResolvedValueOnce({
      hasMore: false,
      items: [findingWithoutLocation],
      page: 1,
      pageSize: 20,
      total: 1
    })
    getProjectKnowledgeBaseFinding.mockResolvedValue(findingWithoutLocation)

    renderKnowledgeBase()
    await user.click(await screen.findByRole('button', { name: '后端待提供' }))

    expect(await screen.findByRole('heading', { name: '判断依据' })).toBeInTheDocument()
    expect(screen.getByText('暂无结构化证据路径')).toBeInTheDocument()
    expect(screen.getAllByText('未提供源码位置').length).toBeGreaterThan(0)
    expect(screen.queryByText(/后端待提供/)).not.toBeInTheDocument()
    expect(getProjectKnowledgeBaseFindingCodeContext).not.toHaveBeenCalled()
  })

  it('keeps the detail readable when code context loading fails', async () => {
    const user = userEvent.setup()
    getProjectKnowledgeBaseFinding.mockResolvedValue({
      ...findingSummary,
      evidence: {},
      fact: {
        ...findingSummary.fact,
        primaryLocation: {
          file: 'src/simd.cpp',
          span: { end: { line: 42 }, start: { line: 42 } }
        }
      }
    })
    getProjectKnowledgeBaseFindingCodeContext.mockRejectedValue(new Error('context unavailable'))

    renderKnowledgeBase()
    await user.click(await screen.findByRole('button', { name: 'src/simd.cpp' }))

    expect(await screen.findByRole('heading', { name: findingSummary.title })).toBeInTheDocument()
    expect(
      await screen.findByText('代码上下文读取失败，其余判断依据仍可继续查看。')
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '建议下一步' })).toBeInTheDocument()
  })

  it('marks and displays the latest AI supplement returned with a finding', async () => {
    const user = userEvent.setup()
    const findingWithAi = {
      ...findingSummary,
      aiExtension: {
        actionType: 'CHANGE_CODE',
        aiSuggestion: '改用 RVV intrinsic，并补充标量回退。',
        aiSummary: '当前实现依赖 AVX 指令集。',
        confidence: 0.82,
        createdAt: '2026-09-11T06:00:00Z',
        findingId: 'finding-1',
        id: 'ai-result-1',
        rawPayload: {},
        runId: 'ai-run-1'
      }
    }
    getProjectKnowledgeBaseFindings.mockResolvedValueOnce({
      hasMore: false,
      items: [findingWithAi],
      page: 1,
      pageSize: 20,
      total: 1
    })
    getProjectKnowledgeBaseFinding.mockResolvedValue({ ...findingWithAi, evidence: {} })
    getProjectKnowledgeBaseFindingCodeContext.mockResolvedValue({
      endLine: 42,
      highlightEndLine: 42,
      highlightStartLine: 42,
      lines: [{ highlighted: true, lineNumber: 42, text: 'return _mm256_add_ps(a, b);' }],
      startLine: 42
    })

    renderKnowledgeBase()
    expect(await screen.findByText('AI 补充')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'src/simd.cpp' }))

    expect(await screen.findByText('当前实现依赖 AVX 指令集。')).toBeInTheDocument()
    expect(screen.getByText('改用 RVV intrinsic，并补充标量回退。')).toBeInTheDocument()
    expect(screen.getByText(/置信度 82%/)).toBeInTheDocument()
  })

  it('offers retry and return paths when the finding request fails', async () => {
    const user = userEvent.setup()
    getProjectKnowledgeBaseFinding.mockRejectedValue(new Error('detail unavailable'))

    renderKnowledgeBase()
    await user.click(await screen.findByRole('button', { name: 'src/simd.cpp' }))

    expect(await screen.findByText('扫描结果详情加载失败')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新加载' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^返\s*回$/ })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '证据工作区' })).not.toBeInTheDocument()
  })

  async function openHistory(actor: ReturnType<typeof userEvent.setup>) {
    const view = renderKnowledgeBase()
    const historical = {
      ...scan,
      id: 'historical-scan',
      sourceRevisionId: 'source-old',
      currentSourceRevision: false
    }
    getProjectKnowledgeBaseScans.mockResolvedValue({
      hasMore: false,
      items: [historical],
      page: 1,
      pageSize: 20,
      total: 1
    })
    getProjectKnowledgeBaseScan.mockResolvedValue(historical)
    await actor.click(await screen.findByRole('button', { name: '查看历史记录' }))
    await screen.findByRole('button', { name: '查看' })
    return { ...view, historical }
  }

  it('retries the selected historical scan without loading the latest scan and restores the current view on return', async () => {
    const actor = userEvent.setup()
    const { historical } = await openHistory(actor)
    getProjectKnowledgeBaseScan
      .mockRejectedValueOnce(new Error('historical unavailable'))
      .mockResolvedValue(historical)
    const latestCalls = getProjectKnowledgeBaseScanLatest.mock.calls.length
    await actor.click(screen.getByRole('button', { name: '查看' }))
    expect(await screen.findByText('扫描记录加载失败')).toBeInTheDocument()
    expect(screen.queryByText('只读记录')).not.toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: '重新加载' }))
    expect(await screen.findByText('只读记录')).toBeInTheDocument()
    expect(getProjectKnowledgeBaseScan.mock.calls).toEqual([
      ['historical-scan'],
      ['historical-scan']
    ])
    expect(getProjectKnowledgeBaseScanLatest).toHaveBeenCalledTimes(latestCalls)
    expect(screen.queryByRole('button', { name: '重新扫描' })).not.toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: '返回扫描记录' }))
    await actor.click(screen.getByRole('button', { name: '返回最新结果' }))
    expect(await screen.findByRole('button', { name: '重新扫描' })).toBeInTheDocument()
    await waitFor(() =>
      expect(getProjectKnowledgeBaseScanLatest.mock.calls.length).toBeGreaterThan(latestCalls)
    )
  })

  it('retries historical artifact information and returns to the same read-only scan', async () => {
    const actor = userEvent.setup()
    await openHistory(actor)
    await actor.click(screen.getByRole('button', { name: '查看' }))
    await screen.findByText('只读记录')
    getProjectKnowledgeBaseScanArtifacts.mockRejectedValueOnce(new Error('artifacts unavailable'))
    const latestCalls = getProjectKnowledgeBaseScanLatest.mock.calls.length
    await actor.click(screen.getByRole('button', { name: '查看扫描信息' }))
    expect(await screen.findByText('扫描信息加载失败')).toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: '重新加载' }))
    expect(await screen.findByText('source-old')).toBeInTheDocument()
    expect(getProjectKnowledgeBaseScanArtifacts).toHaveBeenLastCalledWith('historical-scan', {
      page: 1,
      pageSize: 200
    })
    await actor.click(screen.getByRole('button', { name: '返回扫描记录概览' }))
    expect(await screen.findByText('只读记录')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '生成 AI 建议' })).not.toBeInTheDocument()
    expect(getProjectKnowledgeBaseScanLatest).toHaveBeenCalledTimes(latestCalls)
  })

  it('keeps historical findings read-only after a failed detail request is retried', async () => {
    const actor = userEvent.setup()
    await openHistory(actor)
    await actor.click(screen.getByRole('button', { name: '查看' }))
    await screen.findByText('只读记录')
    getProjectKnowledgeBaseFinding
      .mockRejectedValueOnce(new Error('finding unavailable'))
      .mockResolvedValue({ ...findingSummary, evidence: {} })
    getProjectKnowledgeBaseFindingCodeContext.mockResolvedValue({
      startLine: 42,
      endLine: 42,
      highlightStartLine: 42,
      highlightEndLine: 42,
      lines: []
    })
    await actor.click(screen.getByRole('button', { name: 'src/simd.cpp' }))
    expect(await screen.findByText('扫描结果详情加载失败')).toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: '重新加载' }))
    expect(await screen.findByText('历史记录 · 只读')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '纳入报告' })).not.toBeInTheDocument()
    expect(getProjectKnowledgeBaseFinding.mock.calls).toEqual([['finding-1'], ['finding-1']])
    await actor.click(screen.getByRole('button', { name: '返回扫描记录概览' }))
    expect(await screen.findByText('只读记录')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新扫描' })).not.toBeInTheDocument()
  })

  it('clears the previous project results while loading the new project and binds prerequisite navigation to the new parent', async () => {
    const actor = userEvent.setup()
    const { rerender } = renderKnowledgeBase()
    await screen.findByText('src/simd.cpp')
    let finish!: (value: ProjectDomainLatest<ProjectKnowledgeBaseScan>) => void
    getProjectKnowledgeBaseScanLatest.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    getProjectStageReadiness.mockResolvedValue({
      context: { runtimeEnvironmentVersion: null, sourceRevisionId: null },
      prerequisites: [
        {
          key: 'ACTIVE_SOURCE',
          status: 'MISSING',
          reasonCode: 'ACTIVE_SOURCE_MISSING',
          referenceId: null,
          details: {}
        }
      ],
      stageId: 'kb'
    })
    const onStageChange = vi.fn()
    rerender(
      <StagePresentationContext.Provider value="drawer">
        <KnowledgeBase
          projectId="project-2"
          visible
          stage={{ ...stage, title: '新项目扫描' }}
          onClose={vi.fn()}
          onStageChange={onStageChange}
        />
      </StagePresentationContext.Provider>
    )
    await waitFor(() =>
      expect(getProjectKnowledgeBaseScanLatest).toHaveBeenLastCalledWith('project-2')
    )
    expect(screen.queryByText('src/simd.cpp')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新扫描' })).not.toBeInTheDocument()
    await act(async () => {
      finish({
        context: { sourceRevisionId: null, runtimeEnvironmentVersion: null },
        freshness: 'EMPTY',
        execution: null,
        result: null
      })
    })
    expect(await screen.findByText('暂不能执行知识库扫描')).toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: '前往源码管理' }))
    expect(onStageChange).toHaveBeenCalledExactlyOnceWith('assets')
    expect(createProjectKnowledgeBaseScan).not.toHaveBeenCalled()
  })
})
