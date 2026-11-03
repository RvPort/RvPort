import type { SpecializedAnalysisStageId } from '@/types/project/specialized-analysis'
export type RiskAnalysisStageId = SpecializedAnalysisStageId
export type RiskSeverity = 'critical' | 'high' | 'medium' | 'low' | 'info' | 'unknown'
export interface RiskFinding {
  runId?: string
  memoryOrder?: boolean
  evidenceLevel?: string
  protocolWarning?: string
  id: string
  code: string
  title: string
  severity: RiskSeverity
  confidence: string
  location: string
  line: number | null
  detail: string
  impact: string
  suggestion: string
  validation: string
  validationPlan: { label: string; value: string }[]
  evidenceOrigin: string
  assumptions: string[]
  resultStatus: string
  findingKind: string
  requiresHumanConfirmation: boolean
  rawDetail: Record<string, unknown>
}
