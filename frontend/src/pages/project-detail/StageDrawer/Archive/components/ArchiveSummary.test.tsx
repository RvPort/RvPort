import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import type { ProjectArchive } from '@/types/project'

import { ArchiveSummary } from './ArchiveSummary'

const archive: ProjectArchive = {
  id: 'archive-1',
  projectId: 'project-1',
  sourceRevisionId: 'source-1',
  sourceRevision: null,
  taskId: 'task-1',
  storedArtifactId: null,
  name: 'release-package',
  status: 'RUNNING',
  summary: {},
  sizeBytes: null,
  sha256: null,
  items: [],
  createdAt: '2026-10-27T08:00:00Z',
  updatedAt: '2026-10-27T08:01:00Z'
}

describe('archive delivery summary', () => {
  it('updates delivery readiness and provenance when a task completes or source changes', () => {
    const view = render(<ArchiveSummary archive={archive} />)
    expect(screen.getByText('正在生成归档')).toBeInTheDocument()
    view.rerender(<ArchiveSummary archive={archive} />)

    const completed = {
      ...archive,
      status: 'SUCCEEDED' as const,
      storedArtifactId: 'artifact-1',
      sizeBytes: 2048,
      sha256: 'verified-hash'
    }
    view.rerender(<ArchiveSummary archive={completed} />)
    expect(screen.getByText('归档已就绪')).toBeInTheDocument()
    expect(screen.getByText('2.0 KB')).toBeInTheDocument()
    fireEvent.click(screen.getByText('校验与追溯'))
    expect(screen.getByText('verified-hash')).toBeInTheDocument()

    view.rerender(
      <ArchiveSummary archive={completed} isCurrentSource={false} currentSourceLabel="new-source" />
    )
    expect(screen.getByText('当前源码尚未归档')).toBeInTheDocument()
    expect(screen.getByText(/new-source 尚未生成交付归档/)).toBeInTheDocument()
    expect(screen.queryByText('归档已就绪')).not.toBeInTheDocument()

    view.rerender(<ArchiveSummary archive={{ ...archive, status: 'FAILED' }} />)
    expect(screen.getByText('归档创建失败')).toBeInTheDocument()
    expect(screen.queryByText('当前源码尚未归档')).not.toBeInTheDocument()
  })

  it('distinguishes a completed task with a missing artifact from a downloadable archive', () => {
    render(<ArchiveSummary archive={{ ...archive, status: 'SUCCEEDED' }} />)
    expect(screen.getByText('归档包数据缺失')).toBeInTheDocument()
    expect(screen.getByText('缺失归档包大小数据')).toBeInTheDocument()
  })
})

it('keeps provenance placeholders accurate and refreshes source labels and actions independently', () => {
  let value = { ...archive }
  let current = false
  let label: string | undefined = undefined
  let actions: React.ReactNode = <button>重新生成</button>
  const draw = () => (
    <ArchiveSummary
      archive={value}
      isCurrentSource={current}
      currentSourceLabel={label}
      actions={actions}
    />
  )
  const view = render(draw())
  expect(screen.getByText(/当前源码尚未生成交付归档/)).toBeInTheDocument()
  for (const [patch, text] of [
    [{ id: '' }, '缺失归档记录 ID 数据'],
    [{ taskId: '' }, '缺失归档任务 ID 数据'],
    [{ sourceRevisionId: '' }, '缺失源码记录 ID 数据'],
    [{ name: '' }, '缺失归档名称数据']
  ] as Array<[Partial<ProjectArchive>, string]>) {
    value = { ...value, ...patch }
    view.rerender(draw())
    expect(screen.getByText(text)).toBeInTheDocument()
  }
  current = true
  view.rerender(draw())
  expect(screen.getByText('源码版本')).toBeInTheDocument()
  actions = <button>查看任务</button>
  view.rerender(draw())
  expect(screen.getByRole('button', { name: '查看任务' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '重新生成' })).not.toBeInTheDocument()
  actions = undefined
  view.rerender(draw())
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  current = false
  label = '当前源码版本'
  view.rerender(draw())
  expect(screen.getByText(/当前激活源码 当前源码版本/)).toBeInTheDocument()
})
