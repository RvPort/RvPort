import type { ProjectMigrationInputStatus } from './migration-input'
import type { ProjectTaskStatus } from './task'

export type ProjectKnowledgeBaseScanStatus =
  'PENDING' | 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMEOUT'

export interface ProjectKnowledgeBaseScanConfig {
  /** 扫描语言（多选 label；提交时映射：'C/C++'→c,cpp，其余 label=value）*/
  language: string[]
  /** 源架构族 */
  sourceArch?: string
  scope: string
  excludedDirectoryNames: string
  excludedPaths: string
}

export interface ProjectKnowledgeBaseScan {
  id: string
  taskId: string
  sourceRevisionId: string
  kbVersion: string | null
  scope: {
    mode: string
    paths: string[]
  }
  status: ProjectKnowledgeBaseScanStatus
  currentSourceRevision?: boolean
  staleReasons?: string[]
  summary: Record<string, unknown>
  startedAt: string | null
  finishedAt: string | null
}

export interface ProjectKnowledgeBaseScanListParams {
  currentOnly?: boolean
  includeStale?: boolean
  kbVersion?: string
  page: number
  pageSize: number
  sortBy?: 'finishedAt' | 'kbVersion' | 'startedAt' | 'status'
  sortDirection?: 'asc' | 'desc'
  sourceRevisionId?: string
  status?: ProjectKnowledgeBaseScanStatus
}

export interface ProjectKnowledgeBaseScanArtifact {
  id: string
  artifactType: string
  schemaVersion: string | null
  sha256: string | null
  sizeBytes: number
  createdAt: string
  summary: Record<string, unknown>
}

export type KnowledgeBaseCompletionStatus =
  'complete' | 'partial' | 'ambiguous' | 'inactive' | 'unknown'

export type KnowledgeBaseSeverity = 'critical' | 'high' | 'medium' | 'low' | 'info'

export type KnowledgeBaseReachability = 'selected' | 'inactive' | 'not_selected' | 'unknown'

export interface KnowledgeBaseSourceContext {
  status?: 'complete' | 'partial' | 'unavailable'
  reasonCodes?: string[]
  lines?: Array<{ line: number; text: string }>
  highlights?: Array<{ line: number; startColumn: number; endColumn: number }>
  budget?: {
    beforeLines?: number
    afterLines?: number
    maxLines?: number
    maxCharacters?: number
    truncated?: boolean
  }
}

export interface KnowledgeBaseSourceLocation {
  file?: string | null
  span?: {
    start?: { line?: number; column?: number }
    end?: { line?: number; column?: number }
  }
  context?: KnowledgeBaseSourceContext
}

export interface KnowledgeBaseFact {
  kind?: string
  symbol?: string | null
  matchedText?: string
  primaryLocation?: KnowledgeBaseSourceLocation | null
  provenance?: Array<Record<string, unknown>>
  extensions?: Record<string, Record<string, unknown>>
}

export interface KnowledgeBaseConclusion {
  title?: string
  severity?: KnowledgeBaseSeverity | string
  confidence?: number
  findingType?: string
  actionable?: boolean
  blocking?: boolean
  archReachability?: KnowledgeBaseReachability | string
  status?: KnowledgeBaseCompletionStatus | string
  reason?: string
  suggestion?: string
  extensions?: Record<string, Record<string, unknown>>
}

export interface KnowledgeBaseOccurrence {
  id?: string
  kind?: string
  location?: KnowledgeBaseSourceLocation | null
  configuration?: string | null
  selectionStatus?: KnowledgeBaseReachability | string
  status?: KnowledgeBaseCompletionStatus | string
  reasonCodes?: string[]
  provenance?: Array<Record<string, unknown>>
  extensions?: Record<string, Record<string, unknown>>
}

export interface KnowledgeBaseEvidenceStep {
  id?: string
  kind?: string
  title?: string
  status?: KnowledgeBaseCompletionStatus | string
  reason?: string
  reasonCodes?: string[]
  symbol?: string | null
  configuration?: string | null
  selectionStatus?: KnowledgeBaseReachability | string
  source?: KnowledgeBaseSourceLocation
  knowledge?: Record<string, unknown>
  provenance?: Array<Record<string, unknown>>
}

export interface KnowledgeBaseEvidenceEdge {
  id?: string
  fromStepId?: string
  toStepId?: string
  kind?: string
  status?: KnowledgeBaseCompletionStatus | string
  reasonCodes?: string[]
  provenance?: Array<Record<string, unknown>>
}

export interface KnowledgeBaseEvidenceBreak {
  id?: string
  status?: 'partial' | 'ambiguous' | 'unknown'
  reasonCode?: string
  stage?: string
  fromStepId?: string
  candidateStepIds?: string[]
  expectedKind?: string
  missingId?: string
}

export interface KnowledgeBaseEvidenceChain {
  status?: KnowledgeBaseCompletionStatus | string
  reasonCodes?: string[]
  rootStepId?: string
  primaryPath?: string[]
  steps?: KnowledgeBaseEvidenceStep[]
  edges?: KnowledgeBaseEvidenceEdge[]
  breaks?: KnowledgeBaseEvidenceBreak[]
  impactStepIds?: string[]
  budget?: {
    maxDepth?: number
    maxVisited?: number
    maxPaths?: number
    visited?: number
    paths?: number
    truncated?: boolean
  }
}

export interface KnowledgeBaseAiExtension {
  id: string
  runId: string
  findingId: string
  aiSummary: string | null
  aiSuggestion: string | null
  confidence: number | null
  actionType: string | null
  rawPayload: Record<string, unknown>
  createdAt: string
}

export type KnowledgeBaseAiExtensionStatus = ProjectTaskStatus | 'PARTIAL_SUCCESS'

export interface KnowledgeBaseAiExtensionReadiness {
  ready: boolean
  reason: string | null
  kbScanRunId: string
  findingCount: number
  modelConfigured: boolean
  model: Record<string, unknown>
}

export interface KnowledgeBaseAiExtensionRun {
  id: string
  projectId: string
  sourceRevisionId: string
  kbScanRunId: string
  taskId: string | null
  modelProfileId: string
  agentRuntimeId: string
  status: KnowledgeBaseAiExtensionStatus
  summary: Record<string, unknown>
  inputArtifactId: string | null
  outputArtifactId: string | null
  agentOutputArtifactId: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface KnowledgeBaseAiExtensionCreateResponse {
  task: {
    id: string
    status: ProjectTaskStatus
  }
  run: KnowledgeBaseAiExtensionRun
}

export interface KnowledgeBaseAiExtensionListParams {
  page?: number
  pageSize?: number
  status?: KnowledgeBaseAiExtensionStatus
  sourceRevisionId?: string
  kbScanRunId?: string
  sortBy?: 'createdAt' | 'startedAt' | 'finishedAt'
  sortDirection?: 'asc' | 'desc'
}

export interface ProjectKnowledgeBaseFinding {
  id: string
  category: string | null
  description: string | null
  filePath: string | null
  lineStart: number | null
  lineEnd: number | null
  ruleId: string
  severity: string | null
  status: string | null
  suggestion: string | null
  symbol: string | null
  title?: string
  fact?: KnowledgeBaseFact
  conclusion?: KnowledgeBaseConclusion
  occurrences?: KnowledgeBaseOccurrence[]
  evidenceChain?: KnowledgeBaseEvidenceChain
  aiExtension?: KnowledgeBaseAiExtension | null
  migrationInputRefId?: string | null
  migrationInputStatus?: ProjectMigrationInputStatus | null
  confirmedForNextReport?: boolean
  includedInLatestReport?: boolean
  latestReportId?: string | null
}

export interface ProjectKnowledgeBaseFindingListParams {
  category?: string
  filePath?: string
  page: number
  pageSize: number
  severity?: string
  status?: string
  symbol?: string
}

export interface ProjectKnowledgeBaseFindingDetail extends ProjectKnowledgeBaseFinding {
  createdAt?: string
  evidence: Record<string, unknown>
  /** Scanner V2 主命中位置；旧响应可能只在 evidence.finding 中携带。 */
  primaryOccurrenceId?: string
}

export interface ProjectKnowledgeBaseCodeContext {
  findingId?: string
  scanRunId?: string
  filePath?: string
  contextBefore?: number
  contextAfter?: number
  startLine: number
  endLine: number
  highlightStartLine: number
  highlightEndLine: number
  lines: Array<{
    lineNumber: number
    text: string
    highlighted: boolean
  }>
}

export type CreateProjectKnowledgeBaseScanRequest = ProjectKnowledgeBaseScanConfig
