import type { PageResponse } from '@/types'

import request from '../request'

export type AiMigrationArtifactKind = 'diff' | 'result'

export interface AiMigrationArtifact {
  id: string
  artifactType: string
  contentType: string
  sizeBytes: number
}

export interface AiMigrationRun {
  id: string
  projectId: string
  taskId: string | null
  scanRunId: string
  sourceRevisionId: string | null
  status: string
  summary?: Record<string, unknown> | null
  itemCount: number
  doneCount: number
  falsePositiveCount: number
  pendingCount: number
  createdAt?: string | null
  artifacts: AiMigrationArtifact[]
}

export interface AiMigrationRunListParams {
  page?: number
  pageSize?: number
}

export function getProjectAiMigrations(
  projectId: string,
  params: AiMigrationRunListParams = { page: 0, pageSize: 20 }
) {
  return request.get<PageResponse<AiMigrationRun>>(`/projects/${projectId}/ai-migrations`, {
    params
  })
}

export function getAiMigrationRun(runId: string) {
  return request.get<AiMigrationRun>(`/ai-migrations/${runId}`)
}

/** 浏览器直接访问即可下载（与其它制品下载一致，由浏览器带上 cookie）。 */
export function getAiMigrationArtifactDownloadUrl(runId: string, kind: AiMigrationArtifactKind) {
  return `/api/ai-migrations/${runId}/artifacts/${kind}/download`
}

export interface AiMigrationArtifactContent {
  kind: AiMigrationArtifactKind
  filename: string
  content: string
  truncated: boolean
}

/** 读取产物文本用于页面内展示；超限内容会被截断并标记 truncated。 */
export function getAiMigrationArtifactContent(runId: string, kind: AiMigrationArtifactKind) {
  return request.get<AiMigrationArtifactContent>(`/ai-migrations/${runId}/artifacts/${kind}`)
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
