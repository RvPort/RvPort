import { fireEvent, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import { createKnowledgeScan } from '@/test/knowledge-base-fixtures'
import { renderWithQueryClient } from '@/test/test-utils'

import { ArchiveHistory } from '../Archive/components/ArchiveHistory'
import AnalysisHistory from '../DependencyAnalysis/components/AnalysisHistory'
import { ScanHistory } from '../KnowledgeBase/components/ScanHistory'

const api = vi.hoisted(() => ({
  getProjectArchives: vi.fn(async () => ({})),
  getProjectDependencyAnalyses: vi.fn(async () => ({})),
  getProjectKnowledgeBaseScans: vi.fn(async () => ({}))
}))
vi.mock('@/api/project/archive', () => ({ getProjectArchives: api.getProjectArchives }))
vi.mock('@/api/project/dependency-analysis', () => ({
  getProjectDependencyAnalyses: api.getProjectDependencyAnalyses
}))
vi.mock('@/api/project/knowledge-base', () => ({
  getProjectKnowledgeBaseScans: api.getProjectKnowledgeBaseScans
}))

let row: Record<string, unknown> = {}
vi.mock('@/components/BaseTable', () => ({
  default: ({
    columns,
    request
  }: {
    columns: Array<{
      title: string
      dataIndex?: string
      render?: (value: unknown, record: Record<string, unknown>) => ReactNode
    }>
    request: (query: { page: number; pageSize: number; status?: string }) => Promise<unknown>
  }) => (
    <div>
      <button onClick={() => void request({ page: 2, pageSize: 10, status: 'FAILED' })}>
        读取历史
      </button>
      <table>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.title}>{column.title}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          <tr>
            {columns.map((column) => (
              <td key={column.title}>
                {column.render
                  ? column.render(column.dataIndex ? row[column.dataIndex] : undefined, row)
                  : String(column.dataIndex ? (row[column.dataIndex] ?? '') : '')}
              </td>
            ))}
          </tr>
        </tbody>
      </table>
    </div>
  )
}))

describe('historical record tables', () => {
  it('uses recorded archive counts and formats and keeps detail actions bound to the selected snapshot', () => {
    row = {
      id: 'archive-1',
      sourceRevision: null,
      summary: { itemCount: 7, formats: ['ZIP', 1, 'JSON'] },
      items: [],
      sizeBytes: 2048,
      status: 'SUCCEEDED',
      updatedAt: '2026-10-28T00:00:00Z'
    }
    const open = vi.fn()
    const view = renderWithQueryClient(<ArchiveHistory projectId="project-1" onOpen={open} />)
    expect(screen.getByText('7 项')).toBeInTheDocument()
    expect(screen.getByText('ZIP / JSON')).toBeInTheDocument()
    expect(screen.getByText('2.0 KB')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '读取历史' }))
    expect(api.getProjectArchives).toHaveBeenCalledWith('project-1', {
      page: 2,
      pageSize: 10,
      status: 'FAILED',
      sortBy: 'createdAt',
      sortDirection: 'desc'
    })
    const next = vi.fn()
    view.rerender(<ArchiveHistory projectId="project-1" onOpen={next} />)
    fireEvent.click(screen.getByRole('button', { name: '查看详情' }))
    expect(next).toHaveBeenCalledWith('archive-1')
    expect(open).not.toHaveBeenCalled()
    row = { ...row, id: 'archive-next', summary: {}, sizeBytes: null }
    view.rerender(<ArchiveHistory projectId="project-2" onOpen={next} />)
    expect(screen.getByText('0 项')).toBeInTheDocument()
    expect(screen.getByText('格式信息未记录')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '查看详情' }))
    expect(next).toHaveBeenLastCalledWith('archive-next')
  })

  it('shows successful dependency result distributions and never labels an unfinished analysis as a completed result', () => {
    row = {
      id: 'analysis-1',
      sourceRevisionId: 'source-old',
      status: 'SUCCEEDED',
      summary: { total: 2, byRisk: { HIGH: 1, LOW: 1 } },
      createdAt: '2026-10-28T00:00:00Z'
    }
    const open = vi.fn()
    const view = renderWithQueryClient(
      <AnalysisHistory projectId="project-1" onOpenAnalysis={open} />
    )
    expect(screen.getByText(/共 2 项/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '读取历史' }))
    expect(api.getProjectDependencyAnalyses).toHaveBeenCalledWith('project-1', {
      page: 2,
      pageSize: 10,
      includeStale: true,
      sortBy: 'createdAt',
      sortDirection: 'desc'
    })
    row = { ...row, status: 'RUNNING' }
    const next = vi.fn()
    view.rerender(<AnalysisHistory onOpenAnalysis={next} />)
    expect(screen.getByText('--')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '查看' }))
    expect(next).toHaveBeenCalledWith(row)
    expect(open).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '读取历史' }))
    expect(api.getProjectDependencyAnalyses).toHaveBeenLastCalledWith(
      '',
      expect.objectContaining({ includeStale: true })
    )
  })

  it('refreshes scan source classification and dispatches the latest scan identifier and project query', () => {
    row = { ...createKnowledgeScan(), currentSourceRevision: true }
    const open = vi.fn()
    const view = renderWithQueryClient(<ScanHistory projectId="project-1" onOpenScan={open} />)
    expect(screen.getByText('当前源码')).toBeInTheDocument()
    row = { ...row, currentSourceRevision: false, kbVersion: '', id: 'scan-next' }
    const next = vi.fn()
    view.rerender(<ScanHistory projectId="project-2" onOpenScan={next} />)
    expect(screen.getByText('历史源码')).toBeInTheDocument()
    expect(screen.getByText('--')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '查看' }))
    expect(next).toHaveBeenCalledWith('scan-next')
    expect(open).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: '读取历史' }))
    expect(api.getProjectKnowledgeBaseScans).toHaveBeenCalledWith('project-2', {
      page: 2,
      pageSize: 10,
      includeStale: true,
      sortBy: 'startedAt',
      sortDirection: 'desc'
    })
  })
})
