import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type {
  createProjectEvaluationReport,
  getProjectEvaluationReport,
  getProjectEvaluationReportDownloadUrl,
  getProjectEvaluationReportLatest
} from '@/api/project/assessment-report'
import type { getProjectStageReadiness } from '@/api/project/workflow'
import { advanceAuthSession } from '@/store/authSession'
import {
  createEvaluationReport,
  createLatestReport,
  createReportReadiness,
  createReportTask
} from '@/test/evaluation-report-fixtures'
import { createTestQueryClient } from '@/test/test-utils'
import type { ProjectEvaluationReport } from '@/types/project'

import { projectQueryKeys } from '../../queryKeys'
import { useAssessmentReportDrawer } from './useAssessmentReportDrawer'

const api = vi.hoisted(() => ({
  createProjectEvaluationReport: vi.fn<typeof createProjectEvaluationReport>(),
  getProjectEvaluationReport: vi.fn<typeof getProjectEvaluationReport>(),
  getProjectEvaluationReportDownloadUrl: vi.fn<typeof getProjectEvaluationReportDownloadUrl>(),
  getProjectEvaluationReportLatest: vi.fn<typeof getProjectEvaluationReportLatest>(),
  getProjectStageReadiness: vi.fn<typeof getProjectStageReadiness>()
}))
const feedback = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/api/project/assessment-report', () => api)
vi.mock('@/api/project/workflow', () => ({
  getProjectStageReadiness: api.getProjectStageReadiness
}))
vi.mock('@/utils/feedback', () => ({ feedback }))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}

interface Props {
  projectId?: string
  enabled: boolean
  recordId?: string
}
function setup(props: Partial<Props> = {}, client = createTestQueryClient()) {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  const initialProps: Props = { projectId: 'project-1', enabled: true, ...props }
  return {
    client,
    ...renderHook(
      ({ projectId, enabled, recordId }: Props) =>
        useAssessmentReportDrawer(projectId, enabled, recordId),
      { initialProps, wrapper }
    )
  }
}
const task = createReportTask()

describe('evaluation report source binding and record isolation', () => {
  beforeEach(() => {
    api.getProjectStageReadiness.mockResolvedValue(createReportReadiness())
    api.getProjectEvaluationReportLatest.mockImplementation(async (projectId) =>
      createLatestReport(createEvaluationReport({ projectId }))
    )
    api.createProjectEvaluationReport.mockResolvedValue(task)
    api.getProjectEvaluationReportDownloadUrl.mockImplementation(
      (id, format) => `/reports/${id}?format=${format}`
    )
    vi.spyOn(window, 'open').mockImplementation(() => null)
  })

  it('generates for the readiness source instead of reusing a stale report source', async () => {
    api.getProjectEvaluationReportLatest.mockResolvedValue(
      createLatestReport(createEvaluationReport({ sourceRevisionId: 'source-old' }), {
        freshness: 'STALE'
      })
    )
    const { result } = setup()
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.report).toBeNull()
    act(() => result.current.handleDownload('JSON'))
    expect(window.open).not.toHaveBeenCalled()
    await act(async () => {
      await result.current.handleCreate()
    })
    expect(api.createProjectEvaluationReport).toHaveBeenCalledExactlyOnceWith(
      'project-1',
      'source-current'
    )
    expect(result.current.submitted).toBe(true)
  })

  it('isolates in-flight submissions across source changes and retains the new source lock', async () => {
    const old = deferred<typeof task>()
    const current = deferred<typeof task>()
    api.createProjectEvaluationReport
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(current.promise)
    const { result, client } = setup()
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    let oldSubmission!: Promise<void>
    act(() => {
      oldSubmission = result.current.handleCreate()
      void result.current.handleCreate()
    })
    await waitFor(() => expect(api.createProjectEvaluationReport).toHaveBeenCalledOnce())
    act(() =>
      client.setQueryData(
        projectQueryKeys.readiness('project-1', 'report'),
        createReportReadiness('source-new')
      )
    )
    await waitFor(() => expect(result.current.sourceRevisionId).toBe('source-new'))
    expect(result.current.isCreating).toBe(false)
    let newSubmission!: Promise<void>
    act(() => {
      newSubmission = result.current.handleCreate()
    })
    await waitFor(() => expect(api.createProjectEvaluationReport).toHaveBeenCalledTimes(2))
    expect(api.createProjectEvaluationReport.mock.calls).toEqual([
      ['project-1', 'source-current'],
      ['project-1', 'source-new']
    ])
    await act(async () => {
      old.resolve(task)
      await oldSubmission
    })
    expect(result.current.isCreating).toBe(true)
    expect(result.current.submitted).toBe(false)
    expect(feedback.success).not.toHaveBeenCalled()
    await act(async () => {
      current.resolve({ ...task, id: 'new-task' })
      await newSubmission
    })
    expect(result.current.isCreating).toBe(false)
    expect(result.current.submitted).toBe(true)
    expect(feedback.success).toHaveBeenCalledExactlyOnceWith('评估报告生成任务已创建')
  })

  it.each(['close', 'project', 'auth'] as const)(
    'ignores an old accepted task after %s changes',
    async (change) => {
      const response = deferred<typeof task>()
      api.createProjectEvaluationReport.mockReturnValueOnce(response.promise)
      const { result, rerender } = setup()
      await waitFor(() => expect(result.current.isLoading).toBe(false))
      let pending!: Promise<void>
      act(() => {
        pending = result.current.handleCreate()
      })
      await waitFor(() => expect(api.createProjectEvaluationReport).toHaveBeenCalledOnce())
      if (change === 'auth') act(() => advanceAuthSession())
      else
        rerender({
          projectId: change === 'project' ? 'project-2' : 'project-1',
          enabled: change !== 'close'
        })
      await act(async () => {
        response.resolve(task)
        await pending
      })
      expect(feedback.success).not.toHaveBeenCalled()
      expect(result.current.submitted).toBe(false)
      expect(result.current.isCreating).toBe(false)
    }
  )

  it('releases a failed submission for retry and waits for authoritative execution after success', async () => {
    api.createProjectEvaluationReport
      .mockRejectedValueOnce(new Error('generation unavailable'))
      .mockResolvedValueOnce(task)
    const { result, client } = setup()
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    await act(async () => {
      await result.current.handleCreate()
    })
    expect(feedback.error).toHaveBeenCalledWith('generation unavailable')
    expect(result.current.submitted).toBe(false)
    expect(result.current.isCreating).toBe(false)
    await act(async () => {
      await result.current.handleCreate()
    })
    expect(api.createProjectEvaluationReport.mock.calls).toEqual([
      ['project-1', 'source-current'],
      ['project-1', 'source-current']
    ])
    expect(result.current.report?.status).toBe('SUCCEEDED')
    expect(result.current.submitted).toBe(true)
    expect(invalidate).not.toHaveBeenCalled()
    act(() =>
      client.setQueryData(
        [...projectQueryKeys.evaluationReports('project-1'), 'latest'],
        createLatestReport(createEvaluationReport({ id: 'accepted-report', status: 'RUNNING' }))
      )
    )
    await waitFor(() => expect(result.current.submitted).toBe(false))
    expect(result.current.report?.id).toBe('accepted-report')
    expect(result.current.report?.status).toBe('RUNNING')
  })

  it.each(['KB_SCAN', 'DEPENDENCY_ANALYSIS', 'INCLUDED_FINDINGS'])(
    'blocks generation when %s is missing',
    async (key) => {
      const readiness = createReportReadiness()
      readiness.prerequisites = readiness.prerequisites.filter((item) => item.key !== key)
      api.getProjectStageReadiness.mockResolvedValue(readiness)
      const { result } = setup()
      await waitFor(() => expect(result.current.isLoading).toBe(false))
      expect(result.current.canGenerate).toBe(false)
      await act(async () => {
        await result.current.handleCreate()
      })
      expect(api.createProjectEvaluationReport).not.toHaveBeenCalled()
      expect(feedback.error).toHaveBeenCalledWith('请先完成评估报告的前置条件')
    }
  )

  it('keeps historical status and downloads bound to that record while current queries and submissions are disabled', async () => {
    const client = createTestQueryClient()
    client.setQueryData(projectQueryKeys.readiness('project-1', 'report'), createReportReadiness())
    client.setQueryData(
      [...projectQueryKeys.evaluationReports('project-1'), 'latest'],
      createLatestReport(createEvaluationReport({ status: 'RUNNING' }))
    )
    const historical = createEvaluationReport({ id: 'report-old', sourceRevisionId: 'source-old' })
    api.getProjectEvaluationReport.mockResolvedValue(historical)
    const { result } = setup({ enabled: false, recordId: historical.id }, client)
    await waitFor(() => expect(result.current.report).toEqual(historical))
    expect(result.current.report?.status).toBe('SUCCEEDED')
    act(() => {
      result.current.handleDownload('HTML')
      result.current.handleDownload('JSON')
    })
    expect(window.open).toHaveBeenNthCalledWith(
      1,
      '/reports/report-old?format=HTML',
      '_blank',
      'noopener,noreferrer'
    )
    expect(window.open).toHaveBeenNthCalledWith(
      2,
      '/reports/report-old?format=JSON',
      '_blank',
      'noopener,noreferrer'
    )
    await act(async () => {
      await result.current.handleCreate()
    })
    expect(api.createProjectEvaluationReport).not.toHaveBeenCalled()
    await act(async () => {
      await client.invalidateQueries({
        queryKey: projectQueryKeys.readiness('project-1', 'report')
      })
      await client.invalidateQueries({ queryKey: projectQueryKeys.evaluationReports('project-1') })
    })
    expect(api.getProjectStageReadiness).not.toHaveBeenCalled()
    expect(api.getProjectEvaluationReportLatest).not.toHaveBeenCalled()
  })

  it('does not show a late response from the previous historical record', async () => {
    const first = deferred<ProjectEvaluationReport>()
    const second = deferred<ProjectEvaluationReport>()
    api.getProjectEvaluationReport.mockImplementation((id) =>
      id === 'record-A' ? first.promise : second.promise
    )
    const { result, rerender } = setup({ enabled: false, recordId: 'record-A' })
    await waitFor(() => expect(api.getProjectEvaluationReport).toHaveBeenCalledWith('record-A'))
    rerender({ projectId: 'project-1', enabled: false, recordId: 'record-B' })
    await waitFor(() => expect(api.getProjectEvaluationReport).toHaveBeenCalledWith('record-B'))
    await act(async () => {
      first.resolve(createEvaluationReport({ id: 'record-A', sourceRevisionId: 'source-A' }))
    })
    expect(result.current.report).toBeUndefined()
    expect(result.current.selected.isLoading).toBe(true)
    act(() => result.current.handleDownload('JSON'))
    expect(window.open).not.toHaveBeenCalled()
    await act(async () => {
      second.resolve(createEvaluationReport({ id: 'record-B', sourceRevisionId: 'source-B' }))
    })
    await waitFor(() => expect(result.current.report?.id).toBe('record-B'))
    expect(result.current.report?.sourceRevisionId).toBe('source-B')
  })

  it('does not fall back to the current report on historical failure and retries the selected identity', async () => {
    api.getProjectEvaluationReport
      .mockRejectedValueOnce(new Error('record offline'))
      .mockResolvedValueOnce(
        createEvaluationReport({ id: 'historical', sourceRevisionId: 'source-old' })
      )
    const { result } = setup({ recordId: 'historical' })
    await waitFor(() => expect(result.current.selected.isError).toBe(true))
    expect(result.current.report).toBeUndefined()
    expect(result.current.latestQuery.data?.result?.id).toBe('report-current')
    await act(async () => {
      await result.current.selected.refetch()
    })
    await waitFor(() => expect(result.current.report?.id).toBe('historical'))
    expect(api.getProjectEvaluationReport.mock.calls).toEqual([['historical'], ['historical']])
  })

  it('restores the current report after leaving history even if the old record arrives later', async () => {
    const historical = deferred<ProjectEvaluationReport>()
    api.getProjectEvaluationReport.mockReturnValue(historical.promise)
    const { result, rerender } = setup({ enabled: false, recordId: 'historical' })
    await waitFor(() => expect(api.getProjectEvaluationReport).toHaveBeenCalledOnce())
    rerender({ projectId: 'project-1', enabled: true })
    await waitFor(() => expect(result.current.report?.id).toBe('report-current'))
    await act(async () => {
      historical.resolve(
        createEvaluationReport({ id: 'historical', sourceRevisionId: 'source-old' })
      )
    })
    expect(result.current.report?.sourceRevisionId).toBe('source-current')
    expect(result.current.report?.id).toBe('report-current')
    act(() => result.current.handleDownload('JSON'))
    expect(window.open).toHaveBeenCalledWith(
      '/reports/report-current?format=JSON',
      '_blank',
      'noopener,noreferrer'
    )
  })
})
