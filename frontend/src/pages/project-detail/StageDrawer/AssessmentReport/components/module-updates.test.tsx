import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'

import TableViewStateProvider from '@/components/BaseTable/TableViewStateProvider'
import { renderWithQueryClient } from '@/test/test-utils'

import { projectQueryKeys } from '../../../queryKeys'
import { fileFixture, moduleFixture } from './report-list-fixtures'
import ReportModuleDetail from './ReportModuleDetail'
import ReportModuleIndex from './ReportModuleIndex'

const api = vi.hoisted(() => ({ files: vi.fn(), modules: vi.fn() }))
vi.mock('@/api/project/assessment-report', () => ({
  getProjectEvaluationReportCodeFiles: api.files,
  getProjectEvaluationReportCodeModules: api.modules
}))

it('refreshes module facts independently and opens files with the current callback and metadata', async () => {
  let module = { ...moduleFixture }
  let onSelectFile = vi.fn()
  const page = { items: [fileFixture], total: 1, page: 1, pageSize: 10, hasMore: false }
  api.files.mockResolvedValue(page)
  const draw = () => (
    <TableViewStateProvider>
      <ReportModuleDetail reportId="report-1" module={module} onSelectFile={onSelectFile} />
    </TableViewStateProvider>
  )
  const view = renderWithQueryClient(draw())
  await screen.findByText(fileFixture.relativePath)
  module = {
    ...module,
    moduleName: '',
    fileCount: 8,
    findingCount: 12,
    findingTypeCount: 3,
    relatedModuleKeys: ['target-a', 'target-b']
  }
  view.rerender(draw())
  expect(screen.getByText('未定位模块')).toBeInTheDocument()
  expect(screen.getByText('8 个')).toBeInTheDocument()
  expect(screen.getByText('12 处')).toBeInTheDocument()
  expect(screen.getByText('3 种')).toBeInTheDocument()
  expect(screen.getByText('关联构建目标：target-a、target-b')).toBeInTheDocument()
  const old = onSelectFile
  onSelectFile = vi.fn()
  view.rerender(draw())
  fireEvent.click(screen.getByRole('button', { name: /src\/simd.c/ }))
  expect(onSelectFile).toHaveBeenCalledWith(fileFixture)
  expect(old).not.toHaveBeenCalled()
  const next = { ...fileFixture, relativePath: '', contextAvailable: true }
  act(() =>
    view.queryClient.setQueriesData(
      {
        queryKey: projectQueryKeys
          .evaluationReportCodeFiles('report-1', module.moduleKey)
          .slice(0, 4)
      },
      { ...page, items: [next] }
    )
  )
  expect(await screen.findByText('无法定位的文件')).toBeInTheDocument()
  expect(screen.queryByText('无源码上下文')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /无法定位的文件/ }))
  expect(onSelectFile).toHaveBeenLastCalledWith(next)
})

it('keeps the latest module-selection callback and refreshed module identity', async () => {
  api.modules.mockResolvedValue({
    items: [moduleFixture],
    total: 1,
    page: 1,
    pageSize: 10,
    hasMore: false
  })
  let onSelect = vi.fn()
  const draw = () => (
    <TableViewStateProvider>
      <ReportModuleIndex reportId="report-1" onSelect={onSelect} />
    </TableViewStateProvider>
  )
  const view = renderWithQueryClient(draw())
  await screen.findByText(moduleFixture.moduleName)
  const old = onSelect
  onSelect = vi.fn()
  view.rerender(draw())
  fireEvent.click(screen.getByRole('button', { name: /SIMD core/ }))
  expect(onSelect).toHaveBeenCalledWith(moduleFixture)
  expect(old).not.toHaveBeenCalled()
  const next = { ...moduleFixture, moduleName: '' }
  act(() =>
    view.queryClient.setQueriesData(
      { queryKey: projectQueryKeys.evaluationReportCodeModules('report-1').slice(0, 2) },
      { items: [next], total: 1, page: 1, pageSize: 10, hasMore: false }
    )
  )
  await waitFor(() => expect(screen.getByText('未定位模块')).toBeInTheDocument())
  fireEvent.click(screen.getByRole('button', { name: /未定位模块/ }))
  expect(onSelect).toHaveBeenLastCalledWith(next)
})
