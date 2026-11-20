import type { PageResponse } from '@/types'
import type { ProjectDomainLatest } from '@/types/project'
import type {
  KnowledgeBaseAiExtensionCreateResponse,
  KnowledgeBaseAiExtensionListParams,
  KnowledgeBaseAiExtensionReadiness,
  KnowledgeBaseAiExtensionRun,
  ProjectKnowledgeBaseCodeContext,
  ProjectKnowledgeBaseFinding,
  ProjectKnowledgeBaseFindingDetail,
  ProjectKnowledgeBaseFindingListParams,
  ProjectKnowledgeBaseScan,
  ProjectKnowledgeBaseScanArtifact,
  ProjectKnowledgeBaseScanConfig,
  ProjectKnowledgeBaseScanListParams
} from '@/types/project/knowledge-base'

import request from '../request'
function toBackendScanRequest(data: ProjectKnowledgeBaseScanConfig) {
  const scope = data.scope.trim()
  const excludedDirectoryNames = data.excludedDirectoryNames
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
  const excludedPaths = data.excludedPaths
    .split('\n')
    .map((item) => item.trim())
    .filter(Boolean)

  return {
    language: data.language
      .map((item) => (item === 'C/C++' ? 'c,cpp' : item))
      .join(','),
    sourceArch: data.sourceArch ?? 'x86',
    scanScope: scope === './' ? { mode: 'ALL', paths: [] } : { mode: 'PATHS', paths: [scope] },
    excludePaths: [...excludedDirectoryNames, ...excludedPaths]
  }
}

export function getProjectKnowledgeBaseScans(
  projectId: string,
  params: ProjectKnowledgeBaseScanListParams = { page: 1, pageSize: 20 }
) {
  return request.get<PageResponse<ProjectKnowledgeBaseScan>>(`/projects/${projectId}/kb-scans`, {
    params
  })
}

export function getProjectKnowledgeBaseScanLatest(projectId: string) {
  return request.get<ProjectDomainLatest<ProjectKnowledgeBaseScan>>(
    `/projects/${projectId}/kb-scans/latest`
  )
}

export function getProjectKnowledgeBaseScan(scanId: string) {
  return request.get<ProjectKnowledgeBaseScan>(`/kb-scans/${scanId}`)
}

export function getProjectKnowledgeBaseScanArtifacts(
  scanId: string,
  params: { page: number; pageSize: number }
) {
  return request.get<PageResponse<ProjectKnowledgeBaseScanArtifact>>(
    `/kb-scans/${scanId}/artifacts`,
    {
      params
    }
  )
}

export function getProjectKnowledgeBaseScanArtifactDownloadUrl(scanId: string, artifactId: string) {
  return `/api/kb-scans/${scanId}/artifacts/${artifactId}/download`
}
export function getProjectKnowledgeBaseFindings(
  scanId: string,
  params: ProjectKnowledgeBaseFindingListParams
) {
  return request.get<PageResponse<ProjectKnowledgeBaseFinding>>(`/kb-scans/${scanId}/findings`, {
    params
  })
}
export function getProjectKnowledgeBaseFinding(findingId: string) {
  return request.get<ProjectKnowledgeBaseFindingDetail>(`/findings/${findingId}`)
}
export function getProjectKnowledgeBaseFindingCodeContext(findingId: string) {
  return request.get<ProjectKnowledgeBaseCodeContext>(`/findings/${findingId}/code-context`)
}
export function createProjectKnowledgeBaseScan(
  projectId: string,
  data: ProjectKnowledgeBaseScanConfig
) {
  return request.post(`/projects/${projectId}/kb-scans`, toBackendScanRequest(data))
}

export function getProjectKnowledgeBaseAiExtensionReadiness(projectId: string, scanId: string) {
  return request.get<KnowledgeBaseAiExtensionReadiness>(
    `/projects/${projectId}/kb-scans/${scanId}/ai-extension-readiness`
  )
}

export function getProjectKnowledgeBaseAiExtensions(
  projectId: string,
  params: KnowledgeBaseAiExtensionListParams
) {
  return request.get<PageResponse<KnowledgeBaseAiExtensionRun>>(
    `/projects/${projectId}/kb-ai-extensions`,
    { params }
  )
}

export function createProjectKnowledgeBaseAiExtension(
  projectId: string,
  scanId: string,
  note?: string
) {
  return request.post<KnowledgeBaseAiExtensionCreateResponse>(
    `/projects/${projectId}/kb-scans/${scanId}/ai-extensions`,
    note ? { note } : {}
  )
}
