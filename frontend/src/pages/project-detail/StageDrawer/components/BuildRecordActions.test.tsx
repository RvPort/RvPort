import { useState } from 'react'
import { act, fireEvent, screen } from '@testing-library/react'
import type { ColumnsType } from 'antd/es/table'
import { expect, it, vi } from 'vitest'

import { createBuild } from '@/test/build-fixtures'
import { renderWithQueryClient } from '@/test/test-utils'
import type { ProjectBuildValidation } from '@/types/project'

const state = vi.hoisted(() => ({
  row: {} as ProjectBuildValidation,
  setRow: vi.fn() as (row: ProjectBuildValidation) => void,
  getBuilds: vi.fn(),
  getReports: vi.fn()
}))
vi.mock('@/api/project/assessment-report', () => ({
  getProjectEvaluationReports: state.getReports
}))
vi.mock('@/api/project/build-validation', () => ({ getProjectBuildValidations: state.getBuilds }))
vi.mock('@/components/BaseTable', () => ({
  default: function MockTable({
    columns,
    request
  }: {
    columns: ColumnsType<ProjectBuildValidation>
    request: (query: { page: number; pageSize: number; status: string }) => void
  }) {
    const [row, setRow] = useState(state.row)
    state.setRow = setRow
    return (
      <div>
        <button onClick={() => request({ page: 2, pageSize: 20, status: 'RUNNING' })}>
          读取记录
        </button>
        {columns.map((column, index) => (
          <div key={index}>
            {'render' in column && column.render
              ? (column.render(
                  'dataIndex' in column
                    ? row[column.dataIndex as keyof ProjectBuildValidation]
                    : undefined,
                  row,
                  0
                ) as React.ReactNode)
              : null}
          </div>
        ))}
      </div>
    )
  }
}))
import ReportHistory from '../AssessmentReport/components/ReportHistory'
import BuildRecords from '../Build/components/BuildRecords'
import X86BuildRecords from '../X86Build/components/X86BuildRecords'

it.each([
  { Component: BuildRecords, label: '日志', scope: 'BOTH' },
  { Component: X86BuildRecords, label: '详情', scope: 'SOURCE_ONLY' }
])(
  'keeps $scope history actions, current marker and request identity current',
  async ({ Component, label, scope }) => {
    state.row = createBuild()
    let onSelect = vi.fn(),
      onCancel = vi.fn()
    let projectId: string | undefined = 'project-a',
      currentBuildId: string | undefined = undefined
    const queryKey = ['build-records']
    const draw = () => (
      <Component
        projectId={projectId}
        queryKey={queryKey}
        currentBuildId={currentBuildId}
        onSelect={onSelect}
        onCancel={onCancel}
      />
    )
    const view = renderWithQueryClient(draw())
    expect(screen.queryByText('当前')).not.toBeInTheDocument()
    currentBuildId = state.row.id
    view.rerender(draw())
    expect(screen.getByText('当前')).toBeInTheDocument()
    const oldSelect = onSelect
    onSelect = vi.fn()
    view.rerender(draw())
    fireEvent.click(screen.getByRole('button', { name: label }))
    expect(onSelect).toHaveBeenCalledWith(state.row)
    expect(oldSelect).not.toHaveBeenCalled()
    const oldCancel = onCancel
    onCancel = vi.fn()
    view.rerender(draw())
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }))
    fireEvent.click(await screen.findByRole('button', { name: 'OK' }))
    expect(onCancel).toHaveBeenCalledWith(state.row.id)
    expect(oldCancel).not.toHaveBeenCalled()
    projectId = 'project-b'
    view.rerender(draw())
    fireEvent.click(screen.getByText('读取记录'))
    expect(state.getBuilds).toHaveBeenLastCalledWith(
      'project-b',
      expect.objectContaining({ buildScope: scope, page: 2, status: 'RUNNING', includeStale: true })
    )
    projectId = undefined
    view.rerender(draw())
    fireEvent.click(screen.getByText('读取记录'))
    expect(state.getBuilds).toHaveBeenLastCalledWith(
      '',
      expect.objectContaining({ buildScope: scope })
    )
    state.row = { ...state.row, status: 'SUCCEEDED', environment: null }
    act(() => state.setRow(state.row))
    expect(screen.queryByRole('button', { name: /取\s*消/ })).not.toBeInTheDocument()
    if (scope === 'BOTH') expect(screen.getByText('env-1 / --')).toBeInTheDocument()
  }
)

it('opens historical reports with the current handler and requests the newly selected project', () => {
  state.row = createBuild({ status: 'SUCCEEDED' })
  let projectId = 'first-project',
    onOpen = vi.fn()
  const draw = () => <ReportHistory projectId={projectId} onOpen={onOpen} />
  const view = renderWithQueryClient(draw())
  const old = onOpen
  onOpen = vi.fn()
  view.rerender(draw())
  fireEvent.click(screen.getByRole('button', { name: /查\s*看/ }))
  expect(onOpen).toHaveBeenCalledWith(state.row.id)
  expect(old).not.toHaveBeenCalled()
  projectId = 'second-project'
  view.rerender(draw())
  fireEvent.click(screen.getByText('读取记录'))
  expect(state.getReports).toHaveBeenLastCalledWith('second-project', {
    page: 2,
    pageSize: 20,
    sortBy: 'createdAt',
    sortDirection: 'desc'
  })
})
