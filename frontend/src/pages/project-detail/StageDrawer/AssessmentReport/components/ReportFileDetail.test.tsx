import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { renderWithQueryClient } from '@/test/test-utils'
import type {
  ProjectEvaluationReportCodeFile,
  ProjectEvaluationReportCodeModule
} from '@/types/project'

import { projectQueryKeys } from '../../../queryKeys'
import { fileFixture, findingFixture, moduleFixture } from './report-list-fixtures'
import ReportFileDetail from './ReportFileDetail'

const api = vi.hoisted(() => ({
  getProjectEvaluationReportFindingTypes: vi.fn(),
  getProjectEvaluationReportCodeFindings: vi.fn(),
  getProjectEvaluationReportFileContext: vi.fn()
}))
vi.mock('@/api/project/assessment-report', () => api)
vi.mock('./ReportSourceContextEditor', () => ({
  default: ({
    fileKey,
    reportId,
    selectedFinding,
    revealFindingKey
  }: {
    fileKey: string
    reportId: string
    selectedFinding?: { id: string }
    revealFindingKey: number
  }) => (
    <output aria-label="上下文绑定">
      {reportId}:{fileKey}:{selectedFinding?.id ?? 'initial'}:{revealFindingKey}
    </output>
  )
}))
const page = <T,>(items: T[]) => ({
  items,
  page: 1,
  pageSize: 20,
  total: items.length,
  hasMore: false
})

beforeEach(() => {
  api.getProjectEvaluationReportFindingTypes.mockResolvedValue(page([]))
  api.getProjectEvaluationReportCodeFindings.mockResolvedValue(page([findingFixture]))
  api.getProjectEvaluationReportFileContext.mockResolvedValue({
    contextAvailable: false,
    unavailableReason: '未保存源码'
  })
})

describe('report file evidence workspace', () => {
  it('retries failed type, finding and source requests separately without replacing the other evidence', async () => {
    for (const method of Object.values(api)) method.mockRejectedValueOnce(new Error('unavailable'))
    renderWithQueryClient(
      <ReportFileDetail reportId="report-1" file={fileFixture} module={moduleFixture} />
    )
    for (const label of ['问题类型加载失败', '问题列表加载失败', '源码上下文加载失败']) {
      const notice = await screen.findByText(label)
      fireEvent.click(
        within(notice.closest('[role=alert]') as HTMLElement).getByRole('button', {
          name: /重\s*试/
        })
      )
      await waitFor(() => expect(screen.queryByText(label)).not.toBeInTheDocument())
    }
    expect(screen.getByText('Intrinsic call')).toBeInTheDocument()
    expect(screen.getByText('未保存源码')).toBeInTheDocument()
    for (const method of Object.values(api)) expect(method).toHaveBeenCalledTimes(2)
  })

  it('refreshes file facts independently and keeps selected findings bound to the report source', async () => {
    api.getProjectEvaluationReportFileContext.mockResolvedValue({
      contextAvailable: true,
      totalLines: 30
    })
    let file = fileFixture,
      module = moduleFixture
    const view = renderWithQueryClient(
      <ReportFileDetail reportId="report-1" file={file} module={module} />
    )
    await screen.findByText('Intrinsic call')
    const changes: Array<[Partial<ProjectEvaluationReportCodeFile>, string]> = [
      [{ relativePath: 'src/new.c' }, 'src/new.c'],
      [{ findingCount: 4 }, '4 处问题'],
      [{ findingTypeCount: 3 }, '3 种类型'],
      [{ relativePath: '' }, '无法定位的文件']
    ]
    for (const [patch, expected] of changes) {
      file = { ...file, ...patch }
      view.rerender(<ReportFileDetail reportId="report-1" file={file} module={module} />)
      expect(screen.getByText(expected)).toBeInTheDocument()
    }
    for (const patch of [
      { moduleName: '更新模块' },
      { moduleName: '' }
    ] satisfies Partial<ProjectEvaluationReportCodeModule>[]) {
      module = { ...module, ...patch }
      view.rerender(<ReportFileDetail reportId="report-1" file={file} module={module} />)
      expect(screen.getByText(module.moduleName || '未定位模块')).toBeInTheDocument()
    }
    fireEvent.click(screen.getByRole('button', { name: /第 12 行.*Intrinsic call/ }))
    expect(screen.getByLabelText('上下文绑定')).toHaveTextContent('report-1:simd-file:finding-1:1')
    fireEvent.click(screen.getByRole('button', { name: '返回初始位置' }))
    expect(screen.getByLabelText('上下文绑定')).toHaveTextContent('report-1:simd-file:initial:2')
    act(() =>
      view.queryClient.setQueriesData(
        {
          queryKey: projectQueryKeys
            .evaluationReportCodeFindings('report-1', module.moduleKey, file.fileKey)
            .slice(0, 5)
        },
        page([
          {
            ...findingFixture,
            symbol: ' ',
            recommendation: null,
            locationAvailable: false,
            lineStart: null
          }
        ])
      )
    )
    await screen.findByText('无法定位')
    expect(screen.queryByText('Replace intrinsic')).not.toBeInTheDocument()
    expect(screen.queryByText('_mm_add')).not.toBeInTheDocument()
    act(() =>
      view.queryClient.setQueriesData(
        {
          queryKey: projectQueryKeys
            .evaluationReportCodeFindings('report-1', module.moduleKey, file.fileKey)
            .slice(0, 5)
        },
        page([])
      )
    )
    expect(await screen.findByText('暂无问题')).toBeInTheDocument()
    view.rerender(
      <ReportFileDetail
        reportId="report-2"
        file={{ ...file, fileKey: 'new-file' }}
        module={module}
      />
    )
    await waitFor(() =>
      expect(screen.getByLabelText('上下文绑定')).toHaveTextContent('report-2:new-file:initial:2')
    )
    expect(api.getProjectEvaluationReportFileContext).toHaveBeenLastCalledWith(
      'report-2',
      'new-file'
    )
  })
})
