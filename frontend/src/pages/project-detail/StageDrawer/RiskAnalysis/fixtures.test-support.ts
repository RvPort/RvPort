import type { ProjectDomainLatest, ProjectStageReadiness } from '@/types/project'
import type { SpecializedAnalysis, SpecializedFinding } from '@/types/project/specialized-analysis'
export const readiness: ProjectStageReadiness = {
  stageId: 'memory-order-analysis',
  context: { sourceRevisionId: 'source', runtimeEnvironmentVersion: 1 },
  prerequisites: ['ACTIVE_SOURCE', 'X86_BUILD', 'SPECIALIZED_ANALYZER'].map((key) => ({
    key,
    status: 'SATISFIED',
    referenceId: null,
    reasonCode: 'READY',
    details: {}
  }))
}
export const run: SpecializedAnalysis = {
  id: 'run',
  projectId: 'project',
  taskId: 'task',
  analyzerType: 'MEMORY_ORDER',
  sourceRevisionId: 'source',
  buildRunId: 'build',
  artifactSetId: 'shared-v2',
  environmentConfigVersion: null,
  toolVersion: '1.0',
  status: 'COMPLETE_WITH_RISK',
  verdict: 'risks_found',
  exitCode: 3,
  summary: { diagnosticsAggregated: true },
  findingCount: 1,
  candidateCount: 0,
  diagnosticCount: 0,
  currentSourceRevision: true,
  staleReasons: [],
  requestArtifactId: 'request',
  resultJsonArtifactId: 'json',
  resultHtmlArtifactId: 'html',
  auditBundleArtifactId: 'audit',
  startedAt: null,
  finishedAt: null,
  createdAt: '2026-09-30T00:00:00Z'
}
export const finding: SpecializedFinding = {
  id: 'finding',
  runId: 'run',
  analyzerType: 'MEMORY_ORDER',
  externalFindingId: 'external',
  findingKind: 'FINDING',
  findingCode: 'PUBLICATION',
  category: 'publication',
  severity: 'HIGH',
  confidence: '0.9',
  resultStatus: 'UNKNOWN',
  title: '发布同步需要确认',
  titleEn: null,
  message: '请检查发布协议',
  messageEn: null,
  suggestion: '使用 release/acquire',
  suggestionEn: null,
  testSuggestion: '运行 litmus 测试',
  testSuggestionEn: null,
  filePath: 'src/main.cpp',
  startLine: 4,
  startColumn: 1,
  endLine: 4,
  endColumn: 8,
  requiresHumanConfirmation: true,
  semanticDigest: null,
  detail: {
    fact: { primaryLocation: { file: 'src/main.cpp' } },
    conclusion: {
      verdict: 'risk',
      reason: '请检查发布协议',
      detail: '可能读取旧数据',
      impact: '可能读取旧数据',
      assumptions: ['仅分析发布协议片段']
    }
  },
  reportInclusion: {
    migrationInputRefId: null,
    migrationInputStatus: null,
    confirmedForNextReport: false,
    includedInLatestReport: false,
    latestReportId: null
  },
  createdAt: run.createdAt
}
export function latest(
  result: SpecializedAnalysis | null = run
): ProjectDomainLatest<SpecializedAnalysis> {
  return {
    context: readiness.context,
    freshness: result ? (result.currentSourceRevision ? 'CURRENT' : 'STALE') : 'EMPTY',
    execution: null,
    result
  }
}
