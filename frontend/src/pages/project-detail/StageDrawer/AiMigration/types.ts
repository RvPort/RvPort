export type AiMigrationView = { type: 'runs' } | { type: 'diff'; runId: string }

export interface AiMigrationViewedRun {
  id: string
  status: string
  doneCount: number
  falsePositiveCount: number
  pendingCount: number
  scanRunId: string
  artifacts: { id: string; artifactType: string }[]
}
