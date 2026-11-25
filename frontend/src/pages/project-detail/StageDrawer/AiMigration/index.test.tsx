import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { AiMigrationRun } from '@/api/project/ai-migration'
import { renderWithStrictQueryClient } from '@/test/strict-mode-utils'

import { StagePresentationContext } from '../components/StagePresentationContext'
import AiMigration from '.'

const {
  createProjectAiMigration,
  getAiMigrationArtifactContent,
  getProjectAiMigrations,
  getProjectKnowledgeBaseScanLatest
} = vi.hoisted(() => ({
  createProjectAiMigration: vi.fn(),
  getAiMigrationArtifactContent: vi.fn(),
  getProjectAiMigrations: vi.fn(),
  getProjectKnowledgeBaseScanLatest: vi.fn()
}))

vi.mock('@/api/project/ai-migration', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/project/ai-migration')>()),
  createProjectAiMigration,
  getAiMigrationArtifactContent,
  getProjectAiMigrations
}))

vi.mock('@/api/project/knowledge-base', () => ({
  getProjectKnowledgeBaseScanLatest
}))

vi.mock('@/components/BaseDrawer', () => ({
  default: ({
    children,
    extra,
    title
  }: {
    children: React.ReactNode
    extra?: React.ReactNode
    title: React.ReactNode
  }) => (
    <div>
      {title}
      {extra}
      {children}
    </div>
  )
}))

// 保留传入的属性：高度由 height 属性交给编辑器，桩若丢弃属性就测不到它的缺失。
vi.mock('@/components/MonacoEditor', () => ({
  default: ({ height, value }: { height?: string | number; value?: string }) => (
    <pre aria-label="迁移补丁内容" data-editor-height={height}>
      {value}
    </pre>
  )
}))

const stage = {
  id: 'ai-migration',
  order: 8,
  title: 'AI 迁移',
  subtitle: '',
  status: 'done' as const
}

function migrationRun(overrides: Partial<AiMigrationRun> = {}): AiMigrationRun {
  return {
    artifacts: [
      { artifactType: 'MIGRATION_DIFF', contentType: 'text/x-diff', id: 'diff-1', sizeBytes: 120 },
      {
        artifactType: 'MIGRATION_RESULT',
        contentType: 'application/json',
        id: 'result-1',
        sizeBytes: 80
      }
    ],
    createdAt: '2026-11-22T06:13:21Z',
    doneCount: 16,
    falsePositiveCount: 0,
    id: 'run-1',
    itemCount: 20,
    pendingCount: 4,
    projectId: 'project-1',
    scanRunId: 'scan-run-1',
    sourceRevisionId: 'source-1',
    status: 'SUCCEEDED',
    taskId: 'task-1',
    ...overrides
  }
}

function latestScan(status: string) {
  return {
    context: {},
    execution: null,
    freshness: 'CURRENT',
    result: { id: 'scan-1', status }
  }
}

function renderDrawer() {
  return renderWithStrictQueryClient(
    <StagePresentationContext.Provider value={{ presentation: 'drawer' } as never}>
      <AiMigration onClose={vi.fn()} projectId="project-1" stage={stage} visible />
    </StagePresentationContext.Provider>
  )
}

describe('AI migration drawer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getProjectKnowledgeBaseScanLatest.mockResolvedValue(latestScan('SUCCEEDED'))
    getProjectAiMigrations.mockResolvedValue({
      hasMore: false,
      items: [],
      page: 0,
      pageSize: 50,
      total: 0
    })
    getAiMigrationArtifactContent.mockResolvedValue({
      content: '+added line\n',
      filename: 'migration-diff.patch',
      kind: 'diff',
      truncated: false
    })
    createProjectAiMigration.mockResolvedValue({ id: 'task-new', status: 'PENDING' })
  })

  it('summarizes each run in words and offers the patch for finished runs', async () => {
    getProjectAiMigrations.mockResolvedValue({
      hasMore: false,
      items: [
        migrationRun(),
        migrationRun({
          artifacts: [],
          doneCount: 0,
          id: 'run-2',
          pendingCount: 0,
          status: 'RUNNING'
        })
      ],
      page: 0,
      pageSize: 50,
      total: 2
    })

    renderDrawer()

    expect(await screen.findByText('16 条已迁移，4 条待人工确认')).toBeInTheDocument()
    expect(screen.getByText(/迁移在隔离容器中执行/)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /查看补丁/ })).toHaveLength(1)
    expect(screen.getByRole('link', { name: '下载补丁文件' })).toHaveAttribute(
      'href',
      '/api/ai-migrations/run-1/artifacts/diff/download'
    )
    expect(screen.getByRole('link', { name: '下载逐条目结论' })).toHaveAttribute(
      'href',
      '/api/ai-migrations/run-1/artifacts/result/download'
    )
  })

  it('opens the patch view with the artifact content and returns to the run list', async () => {
    const user = userEvent.setup()
    getProjectAiMigrations.mockResolvedValue({
      hasMore: false,
      items: [migrationRun()],
      page: 0,
      pageSize: 50,
      total: 1
    })

    renderDrawer()

    await user.click(await screen.findByRole('button', { name: /查看补丁/ }))

    expect(await screen.findByRole('button', { name: '返回迁移运行' })).toBeInTheDocument()
    expect(screen.getByLabelText('迁移补丁内容')).toHaveTextContent('+added line')
    expect(getAiMigrationArtifactContent).toHaveBeenCalledWith('run-1', 'diff')

    await user.click(screen.getByRole('button', { name: '返回迁移运行' }))

    expect(await screen.findByText('16 条已迁移，4 条待人工确认')).toBeInTheDocument()
    expect(screen.queryByLabelText('迁移补丁内容')).not.toBeInTheDocument()
  })

  it('gives the patch editor an explicit height so the whole diff is visible', async () => {
    const user = userEvent.setup()
    getProjectAiMigrations.mockResolvedValue({
      hasMore: false,
      items: [migrationRun()],
      page: 0,
      pageSize: 50,
      total: 1
    })
    getAiMigrationArtifactContent.mockResolvedValue({
      content: Array.from({ length: 40 }, (_, index) => `+line ${index + 1}`).join('\n'),
      filename: 'migration-diff.patch',
      kind: 'diff',
      truncated: false
    })

    renderDrawer()
    await user.click(await screen.findByRole('button', { name: /查看补丁/ }))

    // 编辑器组件把 className 与内联 height 放在同一元素上，样式表里的高度会被默认的 100%
    // 覆盖并塌缩成一条缝；高度必须经 height 属性显式传入。
    expect(await screen.findByLabelText('迁移补丁内容')).toHaveAttribute(
      'data-editor-height',
      'clamp(320px, 55vh, 720px)'
    )
    expect(screen.getByLabelText('迁移补丁内容')).toHaveTextContent('+line 40')
  })

  it('explains a run that produced no code changes instead of showing an empty editor', async () => {
    const user = userEvent.setup()
    getProjectAiMigrations.mockResolvedValue({
      hasMore: false,
      items: [migrationRun()],
      page: 0,
      pageSize: 50,
      total: 1
    })
    getAiMigrationArtifactContent.mockResolvedValue({
      content: '   ',
      filename: 'migration-diff.patch',
      kind: 'diff',
      truncated: false
    })

    renderDrawer()
    await user.click(await screen.findByRole('button', { name: /查看补丁/ }))

    expect(await screen.findByText('本次运行没有产生代码改动')).toBeInTheDocument()
    expect(screen.queryByLabelText('迁移补丁内容')).not.toBeInTheDocument()
  })

  it('gives the patch editor an explicit height so the whole diff is visible', async () => {
    const user = userEvent.setup()
    getProjectAiMigrations.mockResolvedValue({
      hasMore: false,
      items: [migrationRun()],
      page: 0,
      pageSize: 50,
      total: 1
    })
    getAiMigrationArtifactContent.mockResolvedValue({
      content: Array.from({ length: 40 }, (_, index) => `+line ${index + 1}`).join('\n'),
      filename: 'migration-diff.patch',
      kind: 'diff',
      truncated: false
    })

    renderDrawer()
    await user.click(await screen.findByRole('button', { name: /查看补丁/ }))

    // 编辑器组件把 className 与内联 height 放在同一元素上，样式表里的高度会被默认的 100%
    // 覆盖并塌缩成一条缝；高度必须经 height 属性显式传入。
    expect(await screen.findByLabelText('迁移补丁内容')).toHaveAttribute(
      'data-editor-height',
      'clamp(320px, 55vh, 720px)'
    )
    expect(screen.getByLabelText('迁移补丁内容')).toHaveTextContent('+line 40')
  })

  it('requires a succeeded scan before a migration can be started', async () => {
    getProjectKnowledgeBaseScanLatest.mockResolvedValue(latestScan('RUNNING'))

    renderDrawer()

    // 最新扫描查询返回前不显示提示，等待扫描态落定后再断言。
    expect(await screen.findByText('需要一次有效的知识库扫描')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /发起迁移/ })).toBeDisabled()
    expect(await screen.findByText('还没有发起过 AI 迁移')).toBeInTheDocument()
    expect(createProjectAiMigration).not.toHaveBeenCalled()
  })

  it('starts a migration when a scan is ready', async () => {
    const user = userEvent.setup()

    renderDrawer()

    await user.click(await screen.findByRole('button', { name: /发起迁移/ }))

    await waitFor(() => expect(createProjectAiMigration).toHaveBeenCalledWith('project-1'))
  })
})
