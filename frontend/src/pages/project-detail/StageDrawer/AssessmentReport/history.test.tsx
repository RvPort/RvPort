import { type ReactNode, useEffect, useEffectEvent } from 'react'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfigProvider } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type {
  createProjectEvaluationReport,
  getProjectEvaluationReport,
  getProjectEvaluationReportDownloadUrl,
  getProjectEvaluationReportLatest,
  getProjectEvaluationReports
} from '@/api/project/assessment-report'
import type { getProjectStageReadiness } from '@/api/project/workflow'
import {
  createEvaluationReport,
  createLatestReport,
  createReportReadiness,
  createReportTask
} from '@/test/evaluation-report-fixtures'
import { renderWithQueryClient } from '@/test/test-utils'
import type { ProjectEvaluationReport } from '@/types/project'

import { projectQueryKeys } from '../../queryKeys'
import AssessmentReport from '.'

const api = vi.hoisted(() => ({
  createProjectEvaluationReport: vi.fn<typeof createProjectEvaluationReport>(),
  getProjectEvaluationReport: vi.fn<typeof getProjectEvaluationReport>(),
  getProjectEvaluationReportDownloadUrl: vi.fn<typeof getProjectEvaluationReportDownloadUrl>(),
  getProjectEvaluationReportLatest: vi.fn<typeof getProjectEvaluationReportLatest>(),
  getProjectEvaluationReports: vi.fn<typeof getProjectEvaluationReports>(),
  getProjectStageReadiness: vi.fn<typeof getProjectStageReadiness>()
}))
vi.mock('@/api/project/assessment-report', () => api)
vi.mock('@/api/project/workflow', () => ({
  getProjectStageReadiness: api.getProjectStageReadiness
}))
vi.mock('../components/TaskLogPanel', () => ({
  TaskLogPanel: ({ taskId }: { taskId: string }) => <div>报告日志 {taskId}</div>
}))
vi.mock('@/components/BaseDrawer', () => ({
  default: function MockDrawer({
    visible,
    title,
    children,
    extra,
    footer,
    onClose,
    afterOpenChange
  }: {
    visible: boolean
    title: ReactNode
    children: ReactNode
    extra?: ReactNode
    footer?: ReactNode
    onClose: () => void
    afterOpenChange?: (open: boolean) => void
  }) {
    const notify = useEffectEvent((open: boolean) => afterOpenChange?.(open))
    useEffect(() => {
      notify(visible)
    }, [visible])
    return visible ? (
      <div>
        {title}
        {extra}
        {children}
        {footer}
        <button onClick={onClose}>关闭报告</button>
      </div>
    ) : null
  }
}))

const historical = createEvaluationReport({
  id: 'report-old',
  sourceRevisionId: 'source-old',
  summary: { codeFindingCount: 7 }
})
function setup() {
  const onClose = vi.fn()
  const tree = (visible = true) => (
    <ConfigProvider theme={{ token: { motion: false } }}>
      <AssessmentReport projectId="project-1" visible={visible} onClose={onClose} />
    </ConfigProvider>
  )
  return { onClose, tree, ...renderWithQueryClient(tree()) }
}

describe('evaluation report history navigation', () => {
  beforeEach(() => {
    api.getProjectStageReadiness.mockResolvedValue(createReportReadiness())
    api.getProjectEvaluationReportLatest.mockResolvedValue(createLatestReport())
    api.getProjectEvaluationReport.mockResolvedValue(historical)
    api.createProjectEvaluationReport.mockResolvedValue(createReportTask())
    api.getProjectEvaluationReports.mockResolvedValue({
      items: [historical],
      total: 1,
      page: 1,
      pageSize: 20,
      hasMore: false
    })
    api.getProjectEvaluationReportDownloadUrl.mockImplementation(
      (id, format) => `/reports/${id}?format=${format}`
    )
    vi.spyOn(window, 'open').mockImplementation(() => null)
  })

  it('keeps historical results read-only and restores current queries and source-bound generation on return', async () => {
    const actor = userEvent.setup()
    const { queryClient } = setup()
    await screen.findByText('2 处')
    await actor.click(screen.getByRole('button', { name: '查看历史记录' }))
    await screen.findByText('source-old')
    await actor.click(screen.getByRole('button', { name: '查看' }))
    expect(await screen.findByText('7 处')).toBeInTheDocument()
    expect(api.getProjectEvaluationReport).toHaveBeenCalledWith('report-old')
    expect(screen.queryByRole('button', { name: '重新生成报告' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '进入人工修复' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '刷新' })).not.toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: '下载 JSON' }))
    expect(window.open).toHaveBeenCalledWith(
      '/reports/report-old?format=JSON',
      '_blank',
      'noopener,noreferrer'
    )
    const latestCalls = api.getProjectEvaluationReportLatest.mock.calls.length
    const readinessCalls = api.getProjectStageReadiness.mock.calls.length
    await act(async () => {
      await queryClient.invalidateQueries({
        queryKey: projectQueryKeys.evaluationReports('project-1')
      })
      await queryClient.invalidateQueries({
        queryKey: projectQueryKeys.readiness('project-1', 'report')
      })
    })
    expect(api.getProjectEvaluationReportLatest).toHaveBeenCalledTimes(latestCalls)
    expect(api.getProjectStageReadiness).toHaveBeenCalledTimes(readinessCalls)
    api.getProjectStageReadiness.mockResolvedValue(createReportReadiness('source-new'))
    api.getProjectEvaluationReportLatest.mockResolvedValue(
      createLatestReport(
        createEvaluationReport({
          id: 'report-new',
          sourceRevisionId: 'source-new',
          summary: { codeFindingCount: 4 }
        })
      )
    )
    await actor.click(screen.getByRole('button', { name: '返回历史记录' }))
    await actor.click(screen.getByRole('button', { name: '返回当前概览' }))
    expect(await screen.findByText('4 处')).toBeInTheDocument()
    expect(screen.queryByText('7 处')).not.toBeInTheDocument()
    await waitFor(() =>
      expect(api.getProjectStageReadiness.mock.calls.length).toBeGreaterThan(readinessCalls)
    )
    expect(screen.getByRole('button', { name: '进入人工修复' })).toBeInTheDocument()
    await actor.click(screen.getByRole('button', { name: '重新生成报告' }))
    await actor.click(screen.getByRole('button', { name: '确认重新生成' }))
    await waitFor(() =>
      expect(api.createProjectEvaluationReport).toHaveBeenCalledExactlyOnceWith(
        'project-1',
        'source-new'
      )
    )
  })

  it('retries a failed historical record without displaying or refetching the current report', async () => {
    api.getProjectEvaluationReport
      .mockRejectedValueOnce(new Error('record unavailable'))
      .mockResolvedValueOnce(historical)
    const actor = userEvent.setup()
    setup()
    await screen.findByText('2 处')
    await actor.click(screen.getByRole('button', { name: '查看历史记录' }))
    await actor.click(await screen.findByRole('button', { name: '查看' }))
    expect(await screen.findByText('报告记录加载失败')).toBeInTheDocument()
    expect(screen.queryByText('2 处')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '下载 JSON' })).not.toBeInTheDocument()
    const latestCalls = api.getProjectEvaluationReportLatest.mock.calls.length
    const readinessCalls = api.getProjectStageReadiness.mock.calls.length
    await actor.click(screen.getByRole('button', { name: '重新加载' }))
    expect(await screen.findByText('7 处')).toBeInTheDocument()
    expect(api.getProjectEvaluationReport.mock.calls).toEqual([['report-old'], ['report-old']])
    expect(api.getProjectEvaluationReportLatest).toHaveBeenCalledTimes(latestCalls)
    expect(api.getProjectStageReadiness).toHaveBeenCalledTimes(readinessCalls)
  })

  it.each(['RUNNING', 'FAILED'] as const)(
    'opens the selected %s record task log instead of the current successful report',
    async (status) => {
      const record = { ...historical, status, taskId: 'historical-task' }
      api.getProjectEvaluationReport.mockResolvedValue(record)
      api.getProjectEvaluationReports.mockResolvedValue({
        items: [record],
        total: 1,
        page: 1,
        pageSize: 20,
        hasMore: false
      })
      const actor = userEvent.setup()
      setup()
      await screen.findByText('2 处')
      await actor.click(screen.getByRole('button', { name: '查看历史记录' }))
      await actor.click(await screen.findByRole('button', { name: '查看' }))
      expect(await screen.findByText('报告日志 historical-task')).toBeInTheDocument()
      expect(screen.queryByText('报告日志 task-current')).not.toBeInTheDocument()
      expect(screen.queryByText('报告概览')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: '下载 JSON' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: '重新生成报告' })).not.toBeInTheDocument()
      await actor.click(screen.getByRole('button', { name: '返回历史记录' }))
      await actor.click(screen.getByRole('button', { name: '返回当前概览' }))
      expect(await screen.findByText('2 处')).toBeInTheDocument()
      expect(screen.queryByText('报告日志 historical-task')).not.toBeInTheDocument()
    }
  )

  it('preserves history page and page size through record details', async () => {
    const records = Array.from({ length: 100 }, (_, index) =>
      createEvaluationReport({ id: `record-${index + 1}`, sourceRevisionId: `source-${index + 1}` })
    )
    api.getProjectEvaluationReports.mockImplementation(async (_, query) => ({
      items: records.slice(
        ((query.page ?? 1) - 1) * (query.pageSize ?? 20),
        (query.page ?? 1) * (query.pageSize ?? 20)
      ),
      total: records.length,
      page: query.page ?? 1,
      pageSize: query.pageSize ?? 20,
      hasMore: (query.page ?? 1) * (query.pageSize ?? 20) < records.length
    }))
    api.getProjectEvaluationReport.mockImplementation(async (id) =>
      records.find((item) => item.id === id)!
    )
    const actor = userEvent.setup()
    setup()
    await actor.click(await screen.findByRole('button', { name: '查看历史记录' }))
    await screen.findByText('record-1')
    await actor.click(screen.getByRole('combobox'))
    await actor.click(await screen.findByText('50 / page'))
    await actor.click(screen.getByTitle('2'))
    const row = (await screen.findByText('record-51')).closest('tr')!
    await actor.click(within(row).getByRole('button', { name: '查看' }))
    await screen.findByRole('button', { name: '返回历史记录' })
    await actor.click(screen.getByRole('button', { name: '下载 HTML' }))
    expect(window.open).toHaveBeenCalledWith(
      '/reports/record-51?format=HTML',
      '_blank',
      'noopener,noreferrer'
    )
    await actor.click(screen.getByRole('button', { name: '返回历史记录' }))
    expect(await screen.findByText('record-51')).toBeInTheDocument()
    expect(screen.queryByText('record-1')).not.toBeInTheDocument()
    await waitFor(() =>
      expect(api.getProjectEvaluationReports).toHaveBeenLastCalledWith('project-1', {
        page: 2,
        pageSize: 50,
        sortBy: 'createdAt',
        sortDirection: 'desc'
      })
    )
  })

  it('does not resume current queries while closing a pending record and reopens at the current report', async () => {
    let finish!: (value: ProjectEvaluationReport) => void
    api.getProjectEvaluationReport.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    const actor = userEvent.setup()
    const { onClose, tree, rerender } = setup()
    await screen.findByText('2 处')
    await actor.click(screen.getByRole('button', { name: '查看历史记录' }))
    await actor.click(await screen.findByRole('button', { name: '查看' }))
    expect(await screen.findByText('正在读取报告记录')).toBeInTheDocument()
    const latestCalls = api.getProjectEvaluationReportLatest.mock.calls.length
    const readinessCalls = api.getProjectStageReadiness.mock.calls.length
    await actor.click(screen.getByRole('button', { name: '关闭报告' }))
    expect(onClose).toHaveBeenCalledOnce()
    expect(api.getProjectEvaluationReportLatest).toHaveBeenCalledTimes(latestCalls)
    rerender(tree(false))
    await act(async () => {
      finish(historical)
    })
    expect(screen.queryByText('7 处')).not.toBeInTheDocument()
    expect(api.getProjectEvaluationReportLatest).toHaveBeenCalledTimes(latestCalls)
    expect(api.getProjectStageReadiness).toHaveBeenCalledTimes(readinessCalls)
    rerender(tree())
    expect(await screen.findByText('2 处')).toBeInTheDocument()
    await waitFor(() =>
      expect(api.getProjectStageReadiness.mock.calls.length).toBeGreaterThan(readinessCalls)
    )
    expect(screen.queryByRole('button', { name: '返回历史记录' })).not.toBeInTheDocument()
    expect(api.getProjectEvaluationReport).toHaveBeenCalledOnce()
    expect(screen.getByRole('button', { name: '重新生成报告' })).toBeInTheDocument()
  })
})
