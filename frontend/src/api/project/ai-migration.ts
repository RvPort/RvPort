import type { PageResponse } from '@/types'

import request from '../request'

export interface AiMigrationRun {
  id: string
  projectId: string
  taskId: string | null
  scanRunId: string
  sourceRevisionId: string | null
  status: string
  itemCount: number
  doneCount: number
  falsePositiveCount: number
  pendingCount: number
}

export interface AiMigrationRunListParams {
  page?: number
  pageSize?: number
}

export function getProjectAiMigrations(
  projectId: string,
  params: AiMigrationRunListParams = { page: 0, pageSize: 20 }
) {
  return request.get<PageResponse<AiMigrationRun>>(`/projects/${projectId}/ai-migrations`, { params })
}

export function getAiMigrationRun(runId: string) {
  return request.get<AiMigrationRun>(`/ai-migrations/${runId}`)
}

export function createProjectAiMigration(projectId: string, scanRunId?: string) {
  return request.post<{
    id: string
    projectId: string
    type: string
    status: string
    message: string | null
    errorCode: string | null
    errorMessage: string | null
  }>(`/projects/${projectId}/ai-migrations`, scanRunId ? { scanRunId } : {})
}
