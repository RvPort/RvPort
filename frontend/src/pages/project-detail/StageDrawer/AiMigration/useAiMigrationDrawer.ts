import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'

import {
  type AiMigrationArtifactKind,
  createProjectAiMigration,
  getAiMigrationArtifactContent,
  getProjectAiMigrations
} from '@/api/project/ai-migration'
import { getProjectKnowledgeBaseScanLatest } from '@/api/project/knowledge-base'
import { useSessionMutation } from '@/hooks/useSessionMutation'
import { feedback } from '@/utils/feedback'

import { projectQueryKeys } from '../../queryKeys'
import type { AiMigrationView } from './types'

function isRunning(status: string) {
  return status === 'RUNNING' || status === 'PENDING'
}

export function useAiMigrationDrawer(projectId: string | undefined, visible: boolean) {
  const [view, setView] = useState<AiMigrationView>({ type: 'runs' })
  const queryClient = useQueryClient()

  const latestScanQuery = useQuery({
    queryKey: projectQueryKeys.knowledgeBaseLatestScan(projectId),
    queryFn: () => getProjectKnowledgeBaseScanLatest(projectId ?? ''),
    enabled: visible && Boolean(projectId)
  })

  const runsQuery = useQuery({
    queryKey: projectQueryKeys.aiMigrations(projectId),
    queryFn: () => getProjectAiMigrations(projectId ?? '', { page: 0, pageSize: 50 }),
    enabled: visible && Boolean(projectId),
    // 运行中的条目结论只在任务结束时一次性写入，按固定间隔刷新即可看到终态。
    refetchInterval: (query) =>
      query.state.data?.items.some((run) => isRunning(run.status)) ? 5000 : false
  })

  const diffQuery = useQuery({
    queryKey: projectQueryKeys.aiMigrationArtifact(
      view.type === 'diff' ? view.runId : undefined,
      'diff'
    ),
    queryFn: () =>
      getAiMigrationArtifactContent(
        view.type === 'diff' ? view.runId : '',
        'diff' satisfies AiMigrationArtifactKind
      ),
    enabled: visible && view.type === 'diff'
  })

  // 与知识库阶段共用最新扫描的订阅，创建迁移后一并失效，保证阶段状态同步刷新。
  const invalidateAll = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: projectQueryKeys.aiMigrations(projectId) }),
      queryClient.invalidateQueries({ queryKey: projectQueryKeys.overview(projectId) })
    ])
  }

  // 请求层拦截器已对 HTTP 错误统一提示（如未绑定阶段模型、没有可迁移条目），这里只补非 HTTP 失败。
  const { isPending: isCreating, mutate: createMigration } = useSessionMutation({
    sessionKey: 'ai-migration-create',
    active: visible,
    mutationFn: () => createProjectAiMigration(projectId ?? ''),
    onSuccess: async (_data, _variables, isCurrent) => {
      if (!isCurrent()) return
      feedback.success('AI 迁移任务已创建')
      await invalidateAll()
    },
    onError: (error) => {
      if (isAxiosError(error)) return
      feedback.error(error instanceof Error ? error.message : '创建失败，请稍后重试')
    }
  })

  const runs = runsQuery.data?.items ?? []
  const scan = latestScanQuery.data?.result ?? null
  const scanReady = scan?.status === 'SUCCEEDED'

  return {
    view,
    showRuns: () => setView({ type: 'runs' }),
    showDiff: (runId: string) => setView({ type: 'diff', runId }),
    scanReady,
    isScanLoading: latestScanQuery.isLoading,
    runs,
    runsQuery,
    diffQuery,
    createMigration: () => createMigration(undefined),
    isCreating
  }
}
