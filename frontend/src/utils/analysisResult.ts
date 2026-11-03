// 任务完成、分析完整性和人工报告选择是三个独立事实。
export function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {}
}

export function text(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

export function isPartialAnalysis(value: {
  status?: unknown
  analysisStatus?: unknown
  analysisComplete?: unknown
  summary?: unknown
}) {
  const summary = record(value.summary)
  return (
    value.status === 'PARTIAL' ||
    value.analysisStatus === 'PARTIAL' ||
    value.analysisComplete === false ||
    summary.analysisStatus === 'PARTIAL' ||
    summary.analysisComplete === false
  )
}

export function memoryOrderKind(declared: string, detail: Record<string, unknown>) {
  const verdict = record(detail.conclusion).verdict
  if (
    declared === 'SOURCE_CANDIDATE' ||
    verdict === 'source-candidate' ||
    record(detail.fact).kind === 'source-candidate'
  )
    return 'SOURCE_CANDIDATE'
  if (verdict === 'inconclusive' || declared === 'INCONCLUSIVE') return 'INCONCLUSIVE'
  if (verdict === 'discharged-within-bounded-model' || declared === 'DISCHARGED')
    return 'DISCHARGED'
  return verdict === 'risk' ? 'FINDING' : 'INCONCLUSIVE'
}

function count(value: unknown) {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 ? value : null
}

export function analysisDiagnostics(run: {
  analyzerType: string
  diagnosticCount?: number | null
  summary: Record<string, unknown>
}) {
  const summary = record(run.summary)
  const aggregated = run.analyzerType !== 'MEMORY_ORDER' || summary.diagnosticsAggregated === true
  return {
    count: aggregated
      ? count(
          Object.hasOwn(summary, 'diagnosticCount') ? summary.diagnosticCount : run.diagnosticCount
        )
      : null,
    frontierCount: aggregated ? count(summary.frontierCount) : null,
    primary: record(summary.primaryDiagnostic),
    items: Array.isArray(summary.diagnostics) ? summary.diagnostics.map(record) : [],
    truncated: summary.diagnosticsTruncated === true,
    message: text(summary.diagnosticsMessage) || text(summary.diagnosticsMessageEn)
  }
}
