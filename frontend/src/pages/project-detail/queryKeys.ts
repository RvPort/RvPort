import type { QueryKey } from '@tanstack/react-query'

import type { ProjectEvaluationReportOrganizationListParams } from '@/types/project'
import type { ProjectTaskStatus } from '@/types/project/task'
import type { ProjectTaskLogContentType } from '@/types/project/task-log'

export const projectQueryKeys = {
  specializedAnalyses: (projectId?: string, stageId?: string) =>
    stageId
      ? (['project-specialized-analysis', projectId, stageId] as const)
      : (['project-specialized-analysis', projectId] as const),
  specializedLatest: (projectId?: string, stageId?: string) =>
    [...projectQueryKeys.specializedAnalyses(projectId, stageId), 'latest'] as const,
  specializedRun: (projectId?: string, stageId?: string, runId?: string) =>
    [...projectQueryKeys.specializedAnalyses(projectId, stageId), 'run', runId] as const,
  specializedFindings: (projectId?: string, stageId?: string, runId?: string) =>
    [...projectQueryKeys.specializedAnalyses(projectId, stageId), 'findings', runId] as const,
  specializedFinding: (projectId?: string, stageId?: string, findingId?: string) =>
    [...projectQueryKeys.specializedAnalyses(projectId, stageId), 'finding', findingId] as const,
  detail: (projectId?: string) => ['project', projectId] as const,
  overview: (projectId?: string) => ['project-overview', projectId] as const,
  workflow: (projectId?: string) => ['project-workflow', projectId] as const,
  readiness: (projectId?: string, stageId?: string) =>
    ['project-stage-readiness', projectId, stageId] as const,
  migrationInput: (inputId?: string) => ['project-migration-input', inputId] as const,
  environmentScriptExecution: (executionId?: string) =>
    ['project-environment-script-execution', executionId] as const,
  environmentScriptExecutions: (projectId?: string, environmentId?: string) =>
    ['project-environment-script-executions', projectId, environmentId] as const,
  environments: (projectId?: string) => ['project-environments', projectId] as const,
  environmentOptions: (projectId?: string) => ['project-environment-options', projectId] as const,
  knowledgeBaseScans: (projectId?: string) => ['project-knowledge-base-scans', projectId] as const,
  knowledgeBaseScan: (scanId?: string) => ['knowledge-base-scan', scanId] as const,
  knowledgeBaseScanArtifacts: (scanId?: string) =>
    ['knowledge-base-scan-artifacts', scanId] as const,
  knowledgeBaseFindings: (scanId?: string) => ['project-knowledge-base-findings', scanId] as const,
  knowledgeBaseFinding: (findingId?: string) =>
    ['project-knowledge-base-finding', findingId] as const,
  knowledgeBaseFindingCodeContext: (findingId?: string) =>
    [...projectQueryKeys.knowledgeBaseFinding(findingId), 'code-context'] as const,
  knowledgeBaseLatestScan: (projectId?: string) =>
    [...projectQueryKeys.knowledgeBaseScans(projectId), 'latest'] as const,
  aiMigrations: (projectId?: string) => ['project-ai-migrations', projectId] as const,
  aiMigrationArtifact: (runId?: string, kind?: string) =>
    ['project-ai-migration-artifact', runId, kind] as const,
  knowledgeBaseAiExtensionReadiness: (projectId?: string, scanId?: string) =>
    scanId
      ? (['project-knowledge-base-ai-extension-readiness', projectId, scanId] as const)
      : (['project-knowledge-base-ai-extension-readiness', projectId] as const),
  knowledgeBaseAiExtensions: (projectId?: string, scanId?: string) =>
    scanId
      ? (['project-knowledge-base-ai-extensions', projectId, scanId] as const)
      : (['project-knowledge-base-ai-extensions', projectId] as const),
  evaluationReport: (reportId?: string) => ['project-evaluation-report', reportId] as const,
  evaluationReports: (projectId?: string, sourceRevisionId?: string) =>
    sourceRevisionId
      ? (['project-evaluation-reports', projectId, sourceRevisionId] as const)
      : (['project-evaluation-reports', projectId] as const),
  evaluationReportCodeModules: (
    reportId?: string,
    params?: ProjectEvaluationReportOrganizationListParams
  ) => ['project-evaluation-report', reportId, 'code-modules', params] as const,
  evaluationReportInputs: (reportId?: string) =>
    ['project-evaluation-report-inputs', reportId] as const,
  evaluationReportModuleSearchDraft: (reportId?: string) =>
    ['project-evaluation-report', reportId, 'module-search-draft'] as const,
  evaluationReportDependencyListState: (reportId?: string) =>
    ['evaluation-report-dependency-list', reportId] as const,
  evaluationReportListSectionState: (reportId?: string) =>
    ['evaluation-report-list-section', reportId] as const,
  evaluationReportCodeFiles: (
    reportId?: string,
    moduleKey?: string,
    params?: ProjectEvaluationReportOrganizationListParams
  ) => ['project-evaluation-report', reportId, 'code-files', moduleKey, params] as const,
  evaluationReportFindingTypes: (reportId?: string, moduleKey?: string, fileKey?: string) =>
    ['project-evaluation-report', reportId, 'finding-types', moduleKey, fileKey] as const,
  evaluationReportCodeFindings: (
    reportId?: string,
    moduleKey?: string,
    fileKey?: string,
    findingType?: string,
    params?: ProjectEvaluationReportOrganizationListParams
  ) =>
    [
      'project-evaluation-report',
      reportId,
      'code-findings',
      moduleKey,
      fileKey,
      findingType,
      params
    ] as const,
  evaluationReportFileContext: (
    reportId?: string,
    fileKey?: string,
    params?: { fromLine?: number; lines?: number }
  ) => ['project-evaluation-report', reportId, 'file-context', fileKey, params] as const,
  buildValidations: (projectId?: string) => ['project-build-validations', projectId] as const,
  automatedTestBuildInputs: (projectId?: string) =>
    [...projectQueryKeys.buildValidations(projectId), 'automated-test-inputs'] as const,
  buildValidation: (buildId?: string) => ['project-build-validation', buildId] as const,
  automatedTests: (projectId?: string, sourceRevisionId?: string) =>
    sourceRevisionId
      ? (['project-automated-tests', projectId, sourceRevisionId] as const)
      : (['project-automated-tests', projectId] as const),
  testComparisons: (projectId?: string, testBatchId?: string) =>
    testBatchId
      ? (['project-test-comparisons', projectId, testBatchId] as const)
      : (['project-test-comparisons', projectId] as const),
  optimizationCycles: (projectId?: string) => ['project-optimization-cycles', projectId] as const,
  optimizationLatest: (projectId?: string) =>
    [...projectQueryKeys.optimizationCycles(projectId), 'latest'] as const,
  optimizationCycle: (projectId?: string, cycleId?: string) =>
    ['project-optimization-cycle', projectId, cycleId] as const,
  archives: (projectId?: string) => ['project-archives', projectId] as const,
  archiveLatest: (projectId?: string) =>
    [...projectQueryKeys.archives(projectId), 'latest'] as const,
  archive: (archiveId?: string) => ['project-archive', archiveId] as const,
  x86Builds: (projectId?: string, sourceRevisionId?: string, status?: ProjectTaskStatus) =>
    sourceRevisionId
      ? (['project-build-validations', projectId, 'SOURCE_ONLY', sourceRevisionId, status] as const)
      : (['project-build-validations', projectId, 'SOURCE_ONLY', status] as const),
  dependencyAnalyses: (projectId?: string, sourceRevisionId?: string) =>
    sourceRevisionId
      ? (['project-dependency-analyses', projectId, sourceRevisionId] as const)
      : (['project-dependency-analyses', projectId] as const),
  dependencyAnalysisLatest: (projectId?: string) =>
    [...projectQueryKeys.dependencyAnalyses(projectId), 'latest'] as const,
  dependencyAnalysisResults: (analysisId?: string) =>
    ['dependency-analysis-results', analysisId] as const,
  dependencyAnalysisResult: (resultId?: string) =>
    ['dependency-analysis-result', resultId] as const,
  dependencyAnalysisArtifacts: (analysisId?: string) =>
    ['dependency-analysis-artifacts', analysisId] as const,
  dependencyAutoFixCandidates: (analysisId?: string) =>
    ['dependency-auto-fix-candidates', analysisId] as const,
  taskLog: (taskId?: string, contentType?: ProjectTaskLogContentType) =>
    ['project-task-log-content', taskId, contentType] as const,
  taskLogIncrements: (taskId?: string) => ['project-task-log-increments', taskId] as const
}

export type ProjectCurrentContextChange = 'runtime-environments' | 'source'

export function projectCurrentContextDependentQueryKeys(
  projectId: string,
  change: ProjectCurrentContextChange
): QueryKey[] {
  const environmentDependentQueryKeys: QueryKey[] = [
    projectQueryKeys.specializedAnalyses(projectId),
    projectQueryKeys.readiness(projectId, 'memory-order-analysis'),
    projectQueryKeys.readiness(projectId, 'floating-point-analysis'),
    projectQueryKeys.readiness(projectId, 'x86-build'),
    projectQueryKeys.readiness(projectId, 'kb'),
    projectQueryKeys.readiness(projectId, 'rv-dependency-analysis'),
    projectQueryKeys.readiness(projectId, 'report'),
    projectQueryKeys.readiness(projectId, 'build'),
    projectQueryKeys.readiness(projectId, 'test'),
    projectQueryKeys.readiness(projectId, 'archive'),
    projectQueryKeys.buildValidations(projectId),
    projectQueryKeys.dependencyAnalyses(projectId),
    projectQueryKeys.evaluationReports(projectId),
    projectQueryKeys.automatedTests(projectId),
    projectQueryKeys.testComparisons(projectId),
    projectQueryKeys.optimizationCycles(projectId),
    projectQueryKeys.archives(projectId)
  ]

  return change === 'source'
    ? [
        projectQueryKeys.readiness(projectId, 'optimize'),
        projectQueryKeys.knowledgeBaseScans(projectId),
        ...environmentDependentQueryKeys
      ]
    : environmentDependentQueryKeys
}
