import type { PageResponse } from '@/types'
import type {
  ProjectDomainLatest,
  ProjectEvaluationReport,
  ProjectEvaluationReportCodeFile,
  ProjectEvaluationReportCodeFinding,
  ProjectEvaluationReportCodeModule,
  ProjectEvaluationReportDownloadFormat,
  ProjectEvaluationReportFileContext,
  ProjectEvaluationReportFindingType,
  ProjectEvaluationReportInput,
  ProjectEvaluationReportInputListParams,
  ProjectEvaluationReportListParams,
  ProjectEvaluationReportOrganizationListParams
} from '@/types/project'

import request from '../request'
import type { BackendTask } from './shared'

export type ProjectEvaluationReportMode = 'STANDARD' | 'AI_ENHANCED'

export interface ProjectEvaluationReportGenerationReadiness {
  sourceRevisionId: string | null
  selectionToken: string
  canGenerate: boolean
  canGenerateStandard: boolean
  canGenerateAi: boolean
  recommendedMode: ProjectEvaluationReportMode
  aiBlockers: Array<{ code: string; message: string; messageEn?: string; recoveryAction?: string }>
  generationInProgress: boolean
  sources: Array<{
    type: string
    available: boolean
    rawFindingCount: number
    candidateCount: number
  }>
}

export interface ProjectEvaluationReportGeneratePayload {
  sourceRevisionId?: string
  mode: ProjectEvaluationReportMode
  selectionToken: string
  baseReportId?: string
  expectedDraftVersion?: number
}

export function getProjectEvaluationReportGenerationReadiness(projectId: string) {
  return request.get<ProjectEvaluationReportGenerationReadiness>(
    `/projects/${projectId}/reports/evaluation/readiness`
  )
}

export function createProjectEvaluationReport(
  projectId: string,
  payload: ProjectEvaluationReportGeneratePayload
) {
  return request.post<BackendTask & { reportId?: string }>(
    `/projects/${projectId}/reports/evaluation`,
    payload,
    { headers: { 'Idempotency-Key': crypto.randomUUID() } }
  )
}

export function getProjectEvaluationReports(
  projectId: string,
  params: ProjectEvaluationReportListParams
) {
  return request.get<PageResponse<ProjectEvaluationReport>>(
    `/projects/${projectId}/reports/evaluation`,
    { params }
  )
}

export function getProjectEvaluationReportLatest(projectId: string) {
  return request.get<ProjectDomainLatest<ProjectEvaluationReport>>(
    `/projects/${projectId}/reports/evaluation/latest`
  )
}

export function getProjectEvaluationReportInputs(
  reportId: string,
  params: ProjectEvaluationReportInputListParams
) {
  return request.get<PageResponse<ProjectEvaluationReportInput>>(`/reports/${reportId}/inputs`, {
    params
  })
}

export function getProjectEvaluationReportCodeModules(
  reportId: string,
  params: ProjectEvaluationReportOrganizationListParams = {}
) {
  return request.get<PageResponse<ProjectEvaluationReportCodeModule>>(
    `/reports/${reportId}/code-modules`,
    { params }
  )
}

export function getProjectEvaluationReportCodeFiles(
  reportId: string,
  moduleKey: string,
  params: ProjectEvaluationReportOrganizationListParams = {}
) {
  return request.get<PageResponse<ProjectEvaluationReportCodeFile>>(
    `/reports/${reportId}/code-files`,
    { params: { ...params, moduleKey } }
  )
}

export function getProjectEvaluationReportFindingTypes(
  reportId: string,
  moduleKey: string,
  fileKey: string,
  params: ProjectEvaluationReportOrganizationListParams = {}
) {
  return request.get<PageResponse<ProjectEvaluationReportFindingType>>(
    `/reports/${reportId}/code-finding-types`,
    { params: { ...params, fileKey, moduleKey } }
  )
}

export function getProjectEvaluationReportCodeFindings(
  reportId: string,
  moduleKey: string,
  fileKey: string,
  findingType?: string,
  params: ProjectEvaluationReportOrganizationListParams = {}
) {
  return request.get<PageResponse<ProjectEvaluationReportCodeFinding>>(
    `/reports/${reportId}/code-findings`,
    { params: { ...params, fileKey, findingType, moduleKey } }
  )
}

export function getProjectEvaluationReportFileContext(
  reportId: string,
  fileKey: string,
  params?: { fromLine?: number; lines?: number }
) {
  return request.get<ProjectEvaluationReportFileContext>(
    `/reports/${reportId}/code-files/${encodeURIComponent(fileKey)}/context`,
    { params }
  )
}

export function getProjectEvaluationReportDownloadUrl(
  reportId: string,
  format: ProjectEvaluationReportDownloadFormat
) {
  return `/api/reports/${reportId}/download?format=${format}`
}

export function getProjectEvaluationReport(reportId: string) {
  return request.get<ProjectEvaluationReport>(`/reports/${reportId}`)
}
