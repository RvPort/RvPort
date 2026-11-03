import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { PageResponse } from '@/types'
import type {
  ProjectKnowledgeBaseScan,
  ProjectKnowledgeBaseScanArtifact
} from '@/types/project/knowledge-base'

import { ScanInfo } from './ScanInfo'

const { downloadUrl } = vi.hoisted(() => ({ downloadUrl: vi.fn() }))
vi.mock('@/api/project/knowledge-base', () => ({
  getProjectKnowledgeBaseScanArtifactDownloadUrl: downloadUrl
}))

const scan: ProjectKnowledgeBaseScan = {
  id: 'scan-1',
  taskId: 'task-1',
  sourceRevisionId: 'source-1',
  kbVersion: '2026.10',
  scope: { mode: 'ALL', paths: [] },
  status: 'SUCCEEDED',
  startedAt: '2026-10-27T08:00:00Z',
  finishedAt: '2026-10-27T08:01:00Z',
  summary: {
    schemaVersion: '2.0',
    scan: {
      tool: { name: 'KB Scanner', version: '2.1' },
      scope: { targetArchitecture: 'riscv64', staticAnalysisOnly: true },
      knowledgeSources: [{ id: 'llvm-rules', kind: 'rule-set', sha256: 'abc123' }]
    },
    diagnostics: [
      { id: 'diag-1', severity: 'WARNING', code: 'PARTIAL_SOURCE', stage: 'scan', status: 'open' }
    ],
    summary: { findings: 3, blocking: 1 }
  }
}

const artifacts: PageResponse<ProjectKnowledgeBaseScanArtifact> = {
  items: [
    {
      id: 'artifact-1',
      artifactType: 'UNKNOWN_TYPE',
      schemaVersion: '2.0',
      sha256: 'hash-1',
      sizeBytes: 2048,
      createdAt: '2026-10-27T08:01:00Z',
      summary: {}
    }
  ],
  total: 1,
  page: 1,
  pageSize: 20,
  hasMore: false
}

afterEach(() => vi.restoreAllMocks())

describe('knowledge-base scan information', () => {
  it('refreshes independent execution facts and rebinds artifact downloads to the current scan', async () => {
    let current = scan
    downloadUrl.mockImplementation(
      (scanId: string, artifactId: string) => `/${scanId}/${artifactId}`
    )
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const retry = vi.fn()
    const view = render(
      <ScanInfo
        artifacts={artifacts}
        scan={current}
        loading={false}
        error={false}
        onRetry={retry}
      />
    )
    const patches: Array<[Partial<ProjectKnowledgeBaseScan>, string]> = [
      [{ taskId: 'task-next' }, 'task-next'],
      [{ sourceRevisionId: 'source-next' }, 'source-next'],
      [{ kbVersion: '2027.1' }, '2027.1'],
      [{ startedAt: '2026-10-28T00:01:00Z' }, '2026-10-28 08:01'],
      [{ finishedAt: '2026-10-28T00:02:00Z' }, '2026-10-28 08:02'],
      [{ scope: { mode: 'SELECTED', paths: ['lib'] } }, 'lib'],
      [{ id: 'scan-next' }, 'scan-next']
    ]
    for (const [patch, expected] of patches) {
      current = { ...current, ...patch }
      view.rerender(
        <ScanInfo
          artifacts={artifacts}
          scan={current}
          loading={false}
          error={false}
          onRetry={retry}
        />
      )
      expect(screen.getByText(expected)).toBeInTheDocument()
    }
    await userEvent.click(screen.getByRole('button', { name: '下载 UNKNOWN_TYPE' }))
    expect(downloadUrl).toHaveBeenLastCalledWith('scan-next', 'artifact-1')
    expect(open).toHaveBeenLastCalledWith('/scan-next/artifact-1', '_blank', 'noopener,noreferrer')
  })
  it('shows loading and offers retry when the scan is unavailable', async () => {
    const user = userEvent.setup()
    const onRetry = vi.fn()
    const view = render(<ScanInfo loading error={false} onRetry={onRetry} />)
    expect(screen.getByText('正在加载扫描信息')).toBeInTheDocument()
    view.rerender(<ScanInfo loading={false} error onRetry={onRetry} />)
    expect(screen.getByText('扫描信息加载失败')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重新加载' }))
    expect(onRetry).toHaveBeenCalledOnce()
  })

  it('shows scan provenance, diagnostics, and a downloadable artifact', async () => {
    const user = userEvent.setup()
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    downloadUrl.mockReturnValue('/api/artifacts/artifact-1/download')
    render(
      <ScanInfo artifacts={artifacts} scan={scan} loading={false} error={false} onRetry={vi.fn()} />
    )
    expect(screen.getByText('scan-1')).toBeInTheDocument()
    expect(screen.getByText('KB Scanner')).toBeInTheDocument()
    expect(screen.getByText('仅静态分析')).toBeInTheDocument()
    expect(screen.getByText('全部路径')).toBeInTheDocument()
    expect(screen.getByText('llvm-rules')).toBeInTheDocument()
    expect(screen.getByText('PARTIAL_SOURCE')).toBeInTheDocument()
    expect(screen.getByText('hash-1')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '下载 UNKNOWN_TYPE' }))
    expect(downloadUrl).toHaveBeenCalledWith('scan-1', 'artifact-1')
    expect(open).toHaveBeenCalledWith(
      '/api/artifacts/artifact-1/download',
      '_blank',
      'noopener,noreferrer'
    )
  })

  it('keeps sparse and in-progress scan data understandable', () => {
    render(
      <ScanInfo
        scan={{
          ...scan,
          status: 'RUNNING',
          scope: { mode: 'SELECTED', paths: ['src/a', 'src/b'] },
          summary: {}
        }}
        loading={false}
        error={false}
        onRetry={vi.fn()}
      />
    )
    expect(screen.getByText('src/a, src/b')).toBeInTheDocument()
    expect(screen.getByText('没有结构化诊断')).toBeInTheDocument()
    expect(screen.getByText('暂无扫描产物')).toBeInTheDocument()
    expect(screen.getAllByText('后端待提供').length).toBeGreaterThan(0)
  })

  it('replaces stale scan details and artifacts after a server refresh', () => {
    const view = render(
      <ScanInfo artifacts={artifacts} scan={scan} loading={false} error={false} onRetry={vi.fn()} />
    )
    expect(screen.getByText('KB Scanner')).toBeInTheDocument()
    expect(screen.getByText('PARTIAL_SOURCE')).toBeInTheDocument()
    view.rerender(
      <ScanInfo artifacts={artifacts} scan={scan} loading={false} error={false} onRetry={vi.fn()} />
    )
    view.rerender(
      <ScanInfo
        artifacts={{ ...artifacts, items: [], total: 0 }}
        scan={{ ...scan, id: 'scan-2', status: 'RUNNING', summary: {} }}
        loading={false}
        error={false}
        onRetry={vi.fn()}
      />
    )
    expect(screen.getByText('scan-2')).toBeInTheDocument()
    expect(screen.queryByText('KB Scanner')).not.toBeInTheDocument()
    expect(screen.queryByText('PARTIAL_SOURCE')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '下载 UNKNOWN_TYPE' })).not.toBeInTheDocument()
    expect(screen.getByText('暂无扫描产物')).toBeInTheDocument()
  })
})
