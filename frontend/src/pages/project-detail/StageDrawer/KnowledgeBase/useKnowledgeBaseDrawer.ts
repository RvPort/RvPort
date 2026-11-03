import { useState } from 'react'
import { useIsFetching, useQuery, useQueryClient } from '@tanstack/react-query'
import { Form } from 'antd'

import {
  createProjectKnowledgeBaseAiExtension,
  createProjectKnowledgeBaseScan,
  getProjectKnowledgeBaseAiExtensionReadiness,
  getProjectKnowledgeBaseAiExtensions,
  getProjectKnowledgeBaseFinding,
  getProjectKnowledgeBaseFindingCodeContext,
  getProjectKnowledgeBaseScan,
  getProjectKnowledgeBaseScanArtifacts,
  getProjectKnowledgeBaseScanLatest
} from '@/api/project/knowledge-base'
import { getProjectStageReadiness } from '@/api/project/workflow'
import { readAllPages } from '@/api/readAllPages'
import { useSessionMutation } from '@/hooks/useSessionMutation'
import { useSubmissionSession } from '@/hooks/useSubmissionSession'
import type { PageResponse } from '@/types'
import type {
  KnowledgeBaseAiExtensionRun,
  ProjectKnowledgeBaseScanConfig
} from '@/types/project/knowledge-base'
import { feedback } from '@/utils/feedback'

import { projectQueryKeys } from '../../queryKeys'
import { useStageDraft } from '../components/useStageDraft'
import { useStageSubmission } from '../components/useStageSubmission'
import { isStageActive } from '../stagePresentation'
import { initialScanConfig, type KnowledgeBaseView } from './types'

export function useKnowledgeBaseDrawer(
  projectId: string | undefined,
  visible: boolean,
  onDirtyChange?: (dirty: boolean) => void
) {
  const [view, setView] = useState<KnowledgeBaseView>({ type: 'results' })
  const [form] = Form.useForm<ProjectKnowledgeBaseScanConfig>()
  const draft = Form.useWatch([], { form, preserve: true }) as
    Partial<ProjectKnowledgeBaseScanConfig> | undefined
  const { markSubmitted, resetSubmitted } = useStageDraft(
    {
      language: draft?.language ?? initialScanConfig.language,
      scope: draft?.scope ?? initialScanConfig.scope,
      excludedDirectoryNames:
        draft?.excludedDirectoryNames ?? initialScanConfig.excludedDirectoryNames,
      excludedPaths: draft?.excludedPaths ?? initialScanConfig.excludedPaths
    },
    initialScanConfig,
    visible && view.type === 'config',
    onDirtyChange
  )
  const queryClient = useQueryClient()
  const readinessQuery = useQuery({
    queryKey: projectQueryKeys.readiness(projectId, 'kb'),
    queryFn: () => getProjectStageReadiness(projectId ?? '', 'kb'),
    enabled: visible && Boolean(projectId) && (view.type === 'results' || view.type === 'config')
  })
  const sourceReady =
    readinessQuery.data?.prerequisites.find((item) => item.key === 'ACTIVE_SOURCE')?.status ===
    'SATISFIED'
  const x86BuildReady =
    readinessQuery.data?.prerequisites.find((item) => item.key === 'X86_BUILD')?.status ===
    'SATISFIED'
  const queryKey = projectQueryKeys.knowledgeBaseScans(projectId)
  const {
    data: latestScan,
    isError: isLatestScanError,
    isLoading: isLatestScanLoading,
    refetch: refetchLatestScan
  } = useQuery({
    queryKey: [...projectQueryKeys.knowledgeBaseScans(projectId), 'latest'],
    queryFn: () => getProjectKnowledgeBaseScanLatest(projectId ?? ''),
    enabled: visible && Boolean(projectId) && view.type === 'results'
  })
  // 当前结果详情直接复用 latest scan；历史详情保持历史结果页已建立的 scan 查询。
  const selectedScanId =
    view.type === 'record' ||
    view.type === 'scan-info' ||
    (view.type === 'detail' && view.returnTo === 'record')
      ? view.scanId
      : undefined
  const {
    data: selectedScan,
    isError: isScanError,
    isLoading: isScanLoading,
    refetch: refetchScan
  } = useQuery({
    queryKey: projectQueryKeys.knowledgeBaseScan(selectedScanId),
    queryFn: () => getProjectKnowledgeBaseScan(selectedScanId ?? ''),
    enabled: visible && Boolean(selectedScanId),
    gcTime: 0,
    refetchOnMount: 'always',
    staleTime: 0
  })
  const currentScan =
    latestScan?.freshness === 'CURRENT' ? (latestScan.result ?? undefined) : undefined
  const submissionContext = `${projectId}:${readinessQuery.data?.context.sourceRevisionId}:${readinessQuery.data?.context.runtimeEnvironmentVersion}`
  const submission = useStageSubmission(
    latestScan?.execution?.id ?? latestScan?.result?.id,
    submissionContext
  )
  const scan = selectedScanId ? selectedScan : currentScan
  const scanId = selectedScanId ?? currentScan?.id
  const isStageError =
    readinessQuery.isError || isLatestScanError || (view.type === 'results' && isScanError)
  const isStageLoading =
    isLatestScanLoading || (view.type === 'results' && Boolean(selectedScanId) && isScanLoading)
  const currentAiScanId = currentScan?.status === 'SUCCEEDED' ? currentScan.id : undefined
  const aiReadinessQuery = useQuery({
    queryKey: projectQueryKeys.knowledgeBaseAiExtensionReadiness(projectId, currentAiScanId),
    queryFn: () =>
      getProjectKnowledgeBaseAiExtensionReadiness(projectId ?? '', currentAiScanId ?? ''),
    enabled: visible && view.type === 'results' && Boolean(projectId && currentAiScanId)
  })
  const aiRunsQueryKey = projectQueryKeys.knowledgeBaseAiExtensions(projectId, currentAiScanId)
  const aiRunsQuery = useQuery({
    queryKey: aiRunsQueryKey,
    queryFn: () =>
      getProjectKnowledgeBaseAiExtensions(projectId ?? '', {
        kbScanRunId: currentAiScanId,
        page: 1,
        pageSize: 1,
        sortBy: 'createdAt',
        sortDirection: 'desc'
      }),
    enabled: visible && view.type === 'results' && Boolean(projectId && currentAiScanId)
  })
  const refetchStage = async () => {
    await Promise.all([readinessQuery.refetch(), refetchLatestScan()])
  }
  const findingId = view.type === 'detail' ? view.findingId : undefined
  const {
    data: scanArtifacts,
    isError: isScanArtifactsError,
    isLoading: isScanArtifactsLoading,
    refetch: refetchScanArtifacts
  } = useQuery({
    queryKey: projectQueryKeys.knowledgeBaseScanArtifacts(scanId),
    queryFn: ({ signal }) =>
      readAllPages(
        (page, pageSize) => getProjectKnowledgeBaseScanArtifacts(scanId ?? '', { page, pageSize }),
        { signal }
      ),
    enabled: visible && view.type === 'scan-info' && Boolean(scanId)
  })
  const {
    data: finding,
    isLoading: isFindingLoading,
    isError: isFindingError,
    refetch: refetchFinding
  } = useQuery({
    queryKey: projectQueryKeys.knowledgeBaseFinding(findingId),
    queryFn: () => getProjectKnowledgeBaseFinding(findingId ?? ''),
    enabled: visible && Boolean(findingId)
  })
  const hasProjectedSourceLocation = Boolean(finding?.filePath && finding.lineStart)
  const {
    data: codeContext,
    isLoading: isCodeContextLoading,
    isError: isCodeContextError
  } = useQuery({
    queryKey: projectQueryKeys.knowledgeBaseFindingCodeContext(findingId),
    queryFn: () => getProjectKnowledgeBaseFindingCodeContext(findingId ?? ''),
    enabled: visible && Boolean(findingId) && hasProjectedSourceLocation,
    retry: false
  })
  const findingDetail = finding ? { ...finding, codeContext } : undefined
  const scanSubmission = useSubmissionSession(
    `kb-scan:${submissionContext}`,
    visible && view.type === 'config'
  )
  const { isPending: isCreatingAiSuggestion, mutateAsync: createAiSuggestion } = useSessionMutation(
    {
      sessionKey: `ai-suggestion:${projectId}:${currentAiScanId}`,
      active: visible,
      mutationFn: () =>
        createProjectKnowledgeBaseAiExtension(projectId ?? '', currentAiScanId ?? ''),
      onSuccess: (response) => {
        feedback.success('AI 建议生成任务已启动')
        queryClient.setQueryData<PageResponse<KnowledgeBaseAiExtensionRun>>(aiRunsQueryKey, {
          hasMore: false,
          items: [response.run],
          page: 1,
          pageSize: 1,
          total: 1
        })
      }
    }
  )
  const showResults = () => setView({ type: 'results' })
  const showConfig = () => {
    resetSubmitted()
    setView({ type: 'config' })
  }
  const showHistory = () => setView({ type: 'history' })
  const showRecord = (selectedScanId: string) => setView({ scanId: selectedScanId, type: 'record' })
  const showScanInfo = (
    selectedScanId: string,
    returnTo: 'history' | 'results' | 'record' = 'results'
  ) => setView({ returnTo, scanId: selectedScanId, type: 'scan-info' })
  const isHistoryFetching = useIsFetching({ queryKey: [...queryKey, 'history'] }) > 0
  const handleCreateScan = () =>
    scanSubmission.run(async (isCurrent) => {
      if (
        readinessQuery.isError ||
        isLatestScanError ||
        submission.pending ||
        isStageActive(latestScan?.execution?.status ?? latestScan?.result?.status)
      )
        return
      if (!sourceReady) {
        feedback.error('请先完成源码导入并设置当前有效源码')
        return
      }
      if (!x86BuildReady) {
        feedback.error('请先完成当前源码的 x86 构建')
        return
      }
      try {
        const values = await form.validateFields()
        if (!isCurrent()) return
        await createProjectKnowledgeBaseScan(projectId ?? '', values)
        if (!isCurrent()) return
        submission.markSubmitted()
        markSubmitted()
        showResults()
        feedback.success('知识库扫描已启动')
      } catch {
        // Form validation and the API interceptor display errors; keep the draft for retry.
      }
    })
  const handleCreateAiSuggestion = async () => {
    if (!aiReadinessQuery.data?.ready || !currentAiScanId) return
    await createAiSuggestion()
  }
  return {
    latestScan,
    readinessQuery,
    submitted: submission.pending,
    isRecordLoading: isScanLoading,
    isRecordError: isScanError,
    refetchRecord: refetchScan,
    clearScanCache: () => {
      queryClient.removeQueries({ queryKey })
      queryClient.removeQueries({
        queryKey: projectQueryKeys.knowledgeBaseAiExtensions(projectId)
      })
      queryClient.removeQueries({
        queryKey: projectQueryKeys.knowledgeBaseAiExtensionReadiness(projectId)
      })
      for (const cachedScanId of new Set([currentScan?.id, scanId].filter(Boolean))) {
        queryClient.removeQueries({ queryKey: projectQueryKeys.knowledgeBaseScan(cachedScanId) })
        queryClient.removeQueries({
          queryKey: projectQueryKeys.knowledgeBaseScanArtifacts(cachedScanId)
        })
        queryClient.removeQueries({
          queryKey: projectQueryKeys.knowledgeBaseFindings(cachedScanId)
        })
      }
    },
    findingDetail,
    form,
    aiSuggestionReadiness: aiReadinessQuery.data,
    aiSuggestionRun: aiRunsQuery.data?.items[0],
    handleCreateAiSuggestion,
    handleCreateScan,
    isAiSuggestionError: aiReadinessQuery.isError || aiRunsQuery.isError,
    isAiSuggestionLoading: aiReadinessQuery.isLoading || aiRunsQuery.isLoading,
    isCreatingAiSuggestion,
    isCreating: scanSubmission.busy,
    isFindingError,
    isFindingLoading,
    isCodeContextError,
    isCodeContextLoading,
    isScanArtifactsError,
    isScanArtifactsLoading,
    isStageError,
    isStageLoading,
    refetchFinding,
    refetchAiSuggestion: () => Promise.all([aiReadinessQuery.refetch(), aiRunsQuery.refetch()]),
    refetchScanArtifacts,
    refetchScanInfo: () => Promise.all([refetchScan(), refetchScanArtifacts()]),
    refetchStage,
    refreshHistory: () =>
      queryClient.invalidateQueries({
        queryKey: [...queryKey, 'history']
      }),
    isHistoryFetching,
    scanId,
    setView,
    showConfig,
    showHistory,
    showRecord,
    showScanInfo,
    showResults,
    scan,
    scanArtifacts,
    latestScanFreshness: latestScan?.freshness,
    sourceReady,
    x86BuildReady,
    isReadinessLoading: readinessQuery.isLoading,
    view
  }
}
