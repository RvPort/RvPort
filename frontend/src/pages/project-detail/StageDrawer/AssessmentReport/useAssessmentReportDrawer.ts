import { useEffect, useMemo, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'

import type { ProjectEvaluationReportGeneratePayload } from '@/api/project/assessment-report'
import {
  createProjectEvaluationReport,
  getProjectEvaluationReport,
  getProjectEvaluationReportDownloadUrl,
  getProjectEvaluationReportGenerationReadiness,
  getProjectEvaluationReportLatest
} from '@/api/project/assessment-report'
import { getProjectStageReadiness } from '@/api/project/workflow'
import { useSessionMutation } from '@/hooks/useSessionMutation'
import type { ProjectEvaluationReportDownloadFormat } from '@/types/project'
import { feedback } from '@/utils/feedback'

import { projectQueryKeys } from '../../queryKeys'
import { useStageSubmission } from '../components/useStageSubmission'
export function useAssessmentReportDrawer(
  projectId: string | undefined,
  enabled: boolean,
  recordId?: string
) {
  const reportQueryKey = projectQueryKeys.evaluationReports(projectId)
  const readinessQuery = useQuery({
    queryKey: projectQueryKeys.readiness(projectId, 'report'),
    queryFn: () => getProjectStageReadiness(projectId ?? '', 'report'),
    enabled: enabled && Boolean(projectId)
  })
  // 生成提交后以 4s 轮询兜底刷新 latest，直到权威执行身份更新（任务事件偶发丢失时页面不卡在"正在同步"）。
  const syncPendingRef = useRef(false)
  const latestQuery = useQuery({
    queryKey: [...reportQueryKey, 'latest'],
    queryFn: () => getProjectEvaluationReportLatest(projectId ?? ''),
    enabled: enabled && Boolean(projectId),
    // 函数式读取：请求调度期取值，不在渲染期触碰 ref（react-hooks 规则）。
    refetchInterval: () => (syncPendingRef.current ? 4000 : false)
  })
  // 上下文（源码修订/运行环境）变化时重新获取生成令牌，避免拿旧上下文的 selectionToken 提交。
  const generationContext = readinessQuery.data?.context
  const generationReadinessQuery = useQuery({
    queryKey: [
      ...reportQueryKey,
      'generation-readiness',
      generationContext?.sourceRevisionId,
      generationContext?.runtimeEnvironmentVersion
    ],
    queryFn: () => getProjectEvaluationReportGenerationReadiness(projectId ?? ''),
    enabled: enabled && Boolean(projectId) && Boolean(generationContext?.sourceRevisionId)
  })
  const selected = useQuery({
    queryKey: projectQueryKeys.evaluationReport(recordId),
    queryFn: () => getProjectEvaluationReport(recordId ?? ''),
    enabled: Boolean(recordId)
  })
  // 新后端的执行身份是 reportId（无 id 字段）；身份变化（新报告落定）即视为同步完成。
  const submission = useStageSubmission(
    latestQuery.data?.execution?.reportId ??
      latestQuery.data?.execution?.id ??
      latestQuery.data?.result?.reportId ??
      latestQuery.data?.result?.id,
    `${projectId}:${readinessQuery.data?.context.sourceRevisionId}:${readinessQuery.data?.context.runtimeEnvironmentVersion}`
  )
  useEffect(() => {
    if (!submission.pending && syncPendingRef.current) syncPendingRef.current = false
  }, [submission.pending])
  const sourceRevisionId = readinessQuery.data?.context.sourceRevisionId ?? undefined
  const generationPrerequisites = ['KB_SCAN', 'DEPENDENCY_ANALYSIS', 'INCLUDED_FINDINGS'].map(
    (key) => readinessQuery.data?.prerequisites.find((item) => item.key === key)
  )
  const canGenerate = generationPrerequisites.every(
    (prerequisite) => prerequisite?.status === 'SATISFIED'
  )
  // latest.result 形态兼容：新后端返回"报告指针"（仅 reportId/状态，无 summary），
  // 完整内容须取详情端点；旧形态（result 即完整报告）直接使用。
  const latestPointer = latestQuery.data?.freshness === 'CURRENT' ? latestQuery.data.result : null
  const pointerNeedsDetail =
    latestPointer != null && (latestPointer as { summary?: unknown }).summary == null
  const latestReportId = pointerNeedsDetail
    ? (latestPointer as { reportId?: string }).reportId ?? undefined
    : undefined
  const latestDetailQuery = useQuery({
    queryKey: projectQueryKeys.evaluationReport(latestReportId),
    queryFn: () => getProjectEvaluationReport(latestReportId ?? ''),
    enabled: enabled && !recordId && Boolean(latestReportId)
  })
  const latestReport = useMemo(() => {
    if (latestQuery.data?.freshness !== 'CURRENT') return null
    const pointer = latestQuery.data.result
    if (!pointer) return null
    if (pointerNeedsDetail) {
      // 详情未到达前不把缺 summary 的指针递给展示层（OrganizedReport 解构会崩）。
      return latestDetailQuery.data
        ? {
            ...latestDetailQuery.data,
            status: latestQuery.data?.execution?.status ?? latestDetailQuery.data.status
          }
        : null
    }
    return latestQuery.data?.execution
      ? { ...pointer, status: latestQuery.data.execution.status }
      : pointer
  }, [latestQuery.data, latestDetailQuery.data, pointerNeedsDetail])
  const report = recordId ? selected.data : latestReport
  const { mutateAsync: createReport, isPending: isCreating } = useSessionMutation({
    sessionKey: `report:${projectId}:${sourceRevisionId}`,
    active: enabled,
    mutationFn: (payload: ProjectEvaluationReportGeneratePayload) =>
      createProjectEvaluationReport(projectId ?? '', payload),
    onSuccess: () => {
      syncPendingRef.current = true
      submission.markSubmitted()
      feedback.success('评估报告生成任务已创建')
    }
  })

  const handleCreate = async () => {
    if (!canGenerate) {
      feedback.error('请先完成评估报告的前置条件')
      return
    }
    const readiness = generationReadinessQuery.data
    if (!readiness?.canGenerate || !readiness.selectionToken) {
      feedback.error('评估报告生成条件已变化，请刷新后重试')
      return
    }
    await createReport({
      sourceRevisionId: readiness.sourceRevisionId ?? sourceRevisionId ?? undefined,
      mode: readiness.recommendedMode,
      selectionToken: readiness.selectionToken,
      baseReportId: latestQuery.data?.regenerationBaseReportId ?? undefined,
      expectedDraftVersion: latestQuery.data?.regenerationBaseDraftVersion ?? undefined
    })
  }
  const handleDownload = (format: ProjectEvaluationReportDownloadFormat) => {
    if (!report) return
    window.open(
      getProjectEvaluationReportDownloadUrl(report.id, format),
      '_blank',
      'noopener,noreferrer'
    )
  }

  return {
    canGenerate:
      canGenerate &&
      generationReadinessQuery.data?.canGenerate !== false &&
      !readinessQuery.isError &&
      !latestQuery.isError,
    readinessQuery,
    generationReadinessQuery,
    latestQuery,
    selected,
    submitted: submission.pending,
    generationPrerequisites,
    handleCreate,
    handleDownload,
    isCreating,
    isError: enabled && (readinessQuery.isError || latestQuery.isError),
    isLoading:
      enabled && (readinessQuery.isLoading || latestQuery.isLoading || generationReadinessQuery.isLoading),
    latestFreshness: latestQuery.data?.freshness,
    refetch: () =>
      Promise.all([readinessQuery.refetch(), latestQuery.refetch(), generationReadinessQuery.refetch()]),
    report,
    sourceRevisionId
  }
}
