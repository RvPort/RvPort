import type { ProjectMigrationInputStatus } from './migration-input'

export type SpecializedAnalysisStageId = 'memory-order-analysis' | 'floating-point-analysis'
export type SpecializedAnalysisStatus =
  | 'PENDING'
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETE'
  | 'COMPLETE_WITH_RISK'
  | 'PARTIAL'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMEOUT'
export interface SpecializedAnalysis {
  id: string
  projectId: string
  taskId: string
  analyzerType: 'FLOATING_POINT' | 'MEMORY_ORDER'
  sourceRevisionId: string
  buildRunId: string
  artifactSetId: string
  environmentConfigVersion: number | null
  toolVersion: string | null
  status: SpecializedAnalysisStatus
  verdict: string | null
  exitCode: number | null
  summary: Record<string, unknown>
  findingCount: number
  candidateCount: number
  diagnosticCount?: number | null
  analysisStatus?: string | null
  analysisComplete?: boolean | null
  currentSourceRevision: boolean
  staleReasons: string[]
  requestArtifactId: string | null
  resultJsonArtifactId: string | null
  resultHtmlArtifactId: string | null
  auditBundleArtifactId: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
}
export interface SpecializedFindingCodeContext {
  findingId: string
  runId: string
  sourceRevisionId: string
  filePath: string
  contextBefore: number
  contextAfter: number
  startLine: number
  endLine: number
  highlightStartLine: number
  highlightEndLine: number
  lines: { lineNumber: number; text: string; highlighted: boolean }[]
}
export interface SpecializedFinding {
  id: string
  runId: string
  analyzerType: SpecializedAnalysis['analyzerType']
  externalFindingId: string
  findingKind: string
  findingCode: string | null
  category: string | null
  severity: string | null
  confidence: string | null
  resultStatus: string | null
  title: string | null
  titleEn: string | null
  message: string | null
  messageEn: string | null
  suggestion: string | null
  suggestionEn: string | null
  testSuggestion: string | null
  testSuggestionEn: string | null
  filePath: string | null
  startLine: number | null
  startColumn: number | null
  endLine: number | null
  endColumn: number | null
  requiresHumanConfirmation: boolean
  semanticDigest: string | null
  detail: Record<string, unknown>
  codeContext?: SpecializedFindingCodeContext | null
  codeContextUnavailableReason?: string | null
  reportInclusion: {
    migrationInputRefId: string | null
    migrationInputStatus: ProjectMigrationInputStatus | null
    confirmedForNextReport: boolean
    includedInLatestReport: boolean
    latestReportId: string | null
  }
  createdAt: string
}
export interface SpecializedFindingParams {
  page: number
  pageSize: number
  keyword?: string
  severity?: string
  status?: string
  category?: string
  code?: string
  filePath?: string
  sortBy?: 'createdAt' | 'severity' | 'filePath' | 'startLine' | 'code'
  sortDirection?: 'asc' | 'desc'
}
export interface SpecializedAnalysisParams {
  page: number
  pageSize: number
  status?: SpecializedAnalysisStatus
  verdict?: string
  sortBy?: 'createdAt' | 'finishedAt' | 'status' | 'findingCount'
  sortDirection?: 'asc' | 'desc'
}
