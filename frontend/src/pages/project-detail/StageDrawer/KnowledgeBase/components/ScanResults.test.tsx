import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ComponentProps, ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import { createKnowledgeFinding, createKnowledgeScan } from '@/test/knowledge-base-fixtures'
import { renderWithQueryClient } from '@/test/test-utils'
import type { ProjectKnowledgeBaseFinding } from '@/types/project/knowledge-base'

import { ScanResults } from './ScanResults'

const api = vi.hoisted(() => ({ getProjectKnowledgeBaseFindings: vi.fn() }))
vi.mock('@/api/project/knowledge-base', () => api)
let row = createKnowledgeFinding({ migrationInputRefId: 'input-1' })
vi.mock('@/components/BaseTable', () => ({
  default: ({
    columns,
    request
  }: {
    columns: Array<{
      title: string
      dataIndex?: keyof ProjectKnowledgeBaseFinding
      render?: (value: unknown, row: ProjectKnowledgeBaseFinding) => ReactNode
    }>
    request: (query: Record<string, unknown>) => Promise<unknown>
  }) => (
    <div>
      <button
        onClick={() =>
          void request({
            page: 2,
            pageSize: 10,
            filePath: 'src',
            severity: 'HIGH',
            status: 'DETECTED'
          })
        }
      >
        请求结果
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
                {column.render?.(column.dataIndex ? row[column.dataIndex] : undefined, row) ??
                  String(column.dataIndex ? row[column.dataIndex] : '')}
              </td>
            ))}
          </tr>
        </tbody>
      </table>
    </div>
  )
}))

describe('knowledge scan result presentation', () => {
  it('updates execution metadata and callback ownership without changing the finding identity', async () => {
    api.getProjectKnowledgeBaseFindings.mockResolvedValue({ items: [] })
    row = createKnowledgeFinding({ migrationInputRefId: 'input-1' })
    let props: ComponentProps<typeof ScanResults> = {
      projectId: 'project-1',
      scan: createKnowledgeScan(),
      onOpenFinding: vi.fn(),
      onShowScanInfo: vi.fn(),
      onRescan: vi.fn(),
      onShowHistory: vi.fn()
    }
    const view = renderWithQueryClient(<ScanResults {...props} />)
    fireEvent.click(screen.getByRole('button', { name: '请求结果' }))
    expect(api.getProjectKnowledgeBaseFindings).toHaveBeenCalledWith('scan-1', {
      page: 2,
      pageSize: 10,
      filePath: 'src',
      severity: 'HIGH',
      status: 'DETECTED'
    })
    for (const [patch, expected] of [
      [{ finishedAt: '2026-10-28T00:02:00Z' }, '2026-10-28 08:02'],
      [{ status: 'FAILED' }, '执行失败'],
      [{ id: 'scan-next' }, '扫描 ID']
    ] as const) {
      props = { ...props, scan: { ...props.scan, ...patch } }
      view.rerender(<ScanResults {...props} />)
      expect(screen.getByText(expected)).toBeInTheDocument()
    }
    const open = vi.fn(),
      info = vi.fn(),
      history = vi.fn(),
      rescan = vi.fn()
    for (const patch of [
      { onOpenFinding: open },
      { onShowScanInfo: info },
      { onShowHistory: history },
      { onRescan: rescan },
      { projectId: 'project-2' }
    ]) {
      props = { ...props, ...patch }
      view.rerender(<ScanResults {...props} />)
    }
    fireEvent.click(screen.getByRole('button', { name: 'src/main.cpp' }))
    fireEvent.click(screen.getByRole('button', { name: '查看扫描信息' }))
    fireEvent.click(screen.getByRole('button', { name: '查看历史记录' }))
    await userEvent.click(screen.getByRole('button', { name: '重新扫描' }))
    await userEvent.click(await screen.findByRole('button', { name: /继\s*续/ }))
    expect(open).toHaveBeenCalledWith('finding-1')
    expect(info).toHaveBeenCalledOnce()
    expect(history).toHaveBeenCalledOnce()
    expect(rescan).toHaveBeenCalledOnce()
    props = { ...props, readOnly: true, onRescan: undefined, onShowHistory: undefined }
    view.rerender(<ScanResults {...props} />)
    expect(screen.getByText('只读记录')).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '操作' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '纳入报告' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新扫描' })).not.toBeInTheDocument()
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /继\s*续/ })).not.toBeInTheDocument()
    )
  })

  it('distinguishes incomplete and missing path analysis and retains finding navigation for a missing file path', () => {
    row = createKnowledgeFinding({ filePath: null, migrationInputRefId: 'input-1' })
    const open = vi.fn()
    const view = renderWithQueryClient(
      <ScanResults
        projectId="project-1"
        scan={createKnowledgeScan({
          summary: {
            summary: {
              findings: 0,
              files: 0,
              issues: 0,
              hints: 2,
              blocking: 0,
              inactiveOrGuarded: 2,
              status: 'partial'
            }
          }
        })}
        onOpenFinding={open}
        onShowScanInfo={vi.fn()}
      />
    )
    expect(screen.getByText('部分输入未完成，当前数据仅反映已分析范围。')).toBeInTheDocument()
    expect(screen.getByTitle('当前扫描未完成此项分析')).toHaveTextContent('--')
    fireEvent.click(screen.getByRole('button', { name: '后端待提供' }))
    expect(open).toHaveBeenCalledWith('finding-1')
    view.rerender(
      <ScanResults
        projectId="project-1"
        scan={createKnowledgeScan()}
        onOpenFinding={open}
        onShowScanInfo={vi.fn()}
      />
    )
    expect(screen.queryByText('部分输入未完成，当前数据仅反映已分析范围。')).not.toBeInTheDocument()
  })
})
