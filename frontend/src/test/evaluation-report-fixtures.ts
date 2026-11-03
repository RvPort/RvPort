import type { BackendTask } from '@/api/project/shared'
import type {
  ProjectDomainLatest,
  ProjectEvaluationReport,
  ProjectStageReadiness
} from '@/types/project'

export function createReportTask(): BackendTask {
  return {
    id: 'created-task',
    projectId: 'project-1',
    type: 'EVALUATION_REPORT',
    status: 'QUEUED',
    createdAt: '2026-10-28T00:02:00Z',
    queuedAt: '2026-10-28T00:02:00Z',
    progress: 0,
    message: null,
    startedAt: null,
    finishedAt: null,
    errorCode: null,
    errorMessage: null,
    resource: { id: 'accepted-report', type: 'EVALUATION_REPORT' }
  }
}

export function createEvaluationReport(
  overrides: Partial<ProjectEvaluationReport> = {}
): ProjectEvaluationReport {
  return {
    id: 'report-current',
    projectId: 'project-1',
    sourceRevisionId: 'source-current',
    taskId: 'task-current',
    title: '当前源码评估报告',
    reportType: 'EVALUATION',
    status: 'SUCCEEDED',
    riskLevel: 'HIGH',
    createdAt: '2026-10-28T00:00:00Z',
    generatedAt: '2026-10-28T00:01:00Z',
    artifacts: [],
    summary: { codeFindingCount: 2 },
    ...overrides
  }
}

export function createReportReadiness(sourceRevisionId = 'source-current'): ProjectStageReadiness {
  return {
    stageId: 'report',
    context: { sourceRevisionId, runtimeEnvironmentVersion: 1 },
    prerequisites: ['KB_SCAN', 'DEPENDENCY_ANALYSIS', 'INCLUDED_FINDINGS'].map((key) => ({
      key,
      status: 'SATISFIED',
      reasonCode: `${key}_READY`,
      referenceId: null,
      details: {}
    }))
  }
}

export function createLatestReport(
  report = createEvaluationReport(),
  overrides: Partial<ProjectDomainLatest<ProjectEvaluationReport>> = {}
): ProjectDomainLatest<ProjectEvaluationReport> {
  return {
    context: { sourceRevisionId: report.sourceRevisionId, runtimeEnvironmentVersion: 1 },
    freshness: 'CURRENT',
    result: report,
    execution: {
      id: report.id,
      taskId: report.taskId,
      status: report.status,
      progress: 100,
      message: null,
      errorCode: null,
      errorMessage: null,
      createdAt: report.createdAt,
      startedAt: report.createdAt,
      finishedAt: report.generatedAt
    },
    ...overrides
  }
}
