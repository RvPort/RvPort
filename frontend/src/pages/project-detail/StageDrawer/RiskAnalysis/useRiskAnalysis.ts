import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'

import {
  createSpecializedAnalysis,
  getSpecializedAnalysis,
  getSpecializedAnalysisLatest,
  getSpecializedFinding
} from '@/api/project/specialized-analysis'
import { cancelProjectTask } from '@/api/project/task'
import { getProjectStageReadiness } from '@/api/project/workflow'
import { useSessionMutation } from '@/hooks/useSessionMutation'
import type { SpecializedAnalysisStageId } from '@/types/project/specialized-analysis'
import { feedback } from '@/utils/feedback'

import { projectQueryKeys } from '../../queryKeys'
import { useStageSubmission } from '../components/useStageSubmission'
import { isAnalysisActive } from './presentation'

const buildPrerequisiteErrorCodes = new Set([
  'SUCCESSFUL_X86_BUILD_WITH_COMPILE_DATABASE_REQUIRED',
  'COMPILE_DATABASE_REQUIRED',
  'SHARED_BITCODE_MANIFEST_REQUIRED',
  'SHARED_BITCODE_BUNDLE_REQUIRED',
  'SHARED_BITCODE_V2_NOT_READY',
  'SHARED_BITCODE_IDENTITY_INVALID'
])

export function useRiskAnalysis(
  projectId: string | undefined,
  stageId: SpecializedAnalysisStageId,
  visible: boolean
) {
  const client = useQueryClient()
  const [runId, setRunId] = useState<string>()
  const [showHistory, setShowHistory] = useState(false)
  const [search, setSearch] = useState('')
  const [severity, setSeverity] = useState('all')
  const [findingId, setFindingId] = useState<string>()
  const [diagnosticRunId, setDiagnosticRunId] = useState<string>()
  const [showLogs, setShowLogs] = useState(false)
  const [creationError, setCreationError] = useState<string>()
  const [creationErrorCode, setCreationErrorCode] = useState<string>()
  const buildPrerequisiteRejected = buildPrerequisiteErrorCodes.has(creationErrorCode ?? '')
  const enabled = visible && Boolean(projectId)
  const currentEnabled = enabled && !runId && !showHistory && !findingId && !diagnosticRunId
  const inspectedRunId = diagnosticRunId ?? runId
  const keys = projectQueryKeys.specializedAnalyses(projectId, stageId)
  const readiness = useQuery({
    queryKey: projectQueryKeys.readiness(projectId, stageId),
    queryFn: () => getProjectStageReadiness(projectId!, stageId),
    enabled: currentEnabled
  })
  const latest = useQuery({
    queryKey: projectQueryKeys.specializedLatest(projectId, stageId),
    queryFn: () => getSpecializedAnalysisLatest(projectId!, stageId),
    enabled: currentEnabled
  })
  const selectedRun = useQuery({
    queryKey: projectQueryKeys.specializedRun(projectId, stageId, inspectedRunId),
    queryFn: () => getSpecializedAnalysis(projectId!, stageId, inspectedRunId!),
    enabled: enabled && Boolean(inspectedRunId)
  })
  const run = inspectedRunId ? selectedRun.data : latest.data?.result
  const finding = useQuery({
    queryKey: projectQueryKeys.specializedFinding(projectId, stageId, findingId),
    queryFn: () => getSpecializedFinding(projectId!, stageId, findingId!),
    enabled: enabled && Boolean(findingId)
  })
  const refresh = () => {
    setCreationError(undefined)
    setCreationErrorCode(undefined)
    return Promise.all([
      client.invalidateQueries({ queryKey: keys }),
      client.invalidateQueries({ queryKey: projectQueryKeys.readiness(projectId, stageId) }),
      client.invalidateQueries({ queryKey: projectQueryKeys.overview(projectId) })
    ])
  }
  const submission = useStageSubmission(
    latest.data?.execution?.id ?? latest.data?.result?.id,
    `${projectId}:${stageId}:${readiness.data?.context.sourceRevisionId}:${readiness.data?.context.runtimeEnvironmentVersion}`
  )
  const active =
    isAnalysisActive(latest.data?.result?.status) ||
    ['PENDING', 'QUEUED', 'RUNNING'].includes(latest.data?.execution?.status ?? '')
  const canStart =
    currentEnabled &&
    !submission.pending &&
    !buildPrerequisiteRejected &&
    !active &&
    !latest.isLoading &&
    !latest.isError &&
    !readiness.isError &&
    !readiness.isFetching &&
    Boolean(readiness.data?.prerequisites.length) &&
    readiness.data!.prerequisites.every((item) => item.status === 'SATISFIED')
  const create = useSessionMutation({
    sessionKey: `specialized-analysis:${projectId}:${stageId}:${readiness.data?.context.sourceRevisionId}:${readiness.data?.context.runtimeEnvironmentVersion}`,
    active: enabled,
    mutationFn: () => {
      if (!canStart) throw new Error('当前条件不允许启动分析，请刷新状态')
      return createSpecializedAnalysis(projectId!, stageId)
    },
    onMutate: () => {
      setCreationError(undefined)
      setCreationErrorCode(undefined)
    },
    onError: (error) => {
      void readiness.refetch()
      const message = isAxiosError(error)
        ? (error.response?.data?.error?.message ?? error.response?.data?.message ?? error.message)
        : error instanceof Error
          ? error.message
          : '启动失败，请重试'
      setCreationError(message)
      setCreationErrorCode(
        isAxiosError(error)
          ? (error.response?.data?.error?.code ?? error.response?.data?.code)
          : undefined
      )
    },
    onSuccess: () => {
      submission.markSubmitted()
      selectRun(undefined)
      feedback.success('分析任务已创建')
    }
  })
  const cancel = useSessionMutation({
    sessionKey: `specialized-cancel:${projectId}:${stageId}:${run?.taskId}`,
    active: currentEnabled && Boolean(run),
    mutationFn: () => {
      if (!run || !isAnalysisActive(run.status)) throw new Error('该分析已经结束，请刷新状态')
      return cancelProjectTask(run.taskId)
    },
    onSuccess: () => {
      feedback.success('已请求取消分析')
    }
  })
  function selectRun(id: string | undefined) {
    setDiagnosticRunId(undefined)
    setRunId(id)
    setShowHistory(false)
    setFindingId(undefined)
    setShowLogs(false)
  }
  return {
    submitted: submission.pending,
    cancel,
    creationError,
    buildPrerequisiteRejected,
    readiness,
    latest,
    selectedRun,
    run,
    finding,
    findingId,
    setFindingId,
    diagnosticRunId,
    openDiagnostics: () => {
      if (run) setDiagnosticRunId(run.id)
    },
    closeDiagnostics: () => setDiagnosticRunId(undefined),
    runId,
    selectRun,
    showHistory,
    openHistory: () => {
      setDiagnosticRunId(undefined)
      setFindingId(undefined)
      setShowHistory(true)
    },
    search,
    setSearch,
    severity,
    setSeverity,
    showLogs,
    setShowLogs,
    refresh,
    canStart,
    create,
    active
  }
}
