import type { ProjectOptimizationCycle, ProjectOptimizationStageRunSummary } from '@/types/project'

export function createStageRun(status: string | null = null): ProjectOptimizationStageRunSummary {
  return {
    total: status ? 1 : 0,
    latestStatus: status,
    latestRunId: status ? 'run-1' : null,
    latestStartedAt: null,
    latestFinishedAt: null,
    byStatus: status ? { [status]: 1 } : {}
  }
}

export function createOptimizationCycle(): ProjectOptimizationCycle {
  return {
    id: 'cycle-1',
    cycleNumber: 1,
    projectId: 'project-1',
    sourceRevisionId: 'source-1',
    status: 'ACTIVE',
    startedAt: '2026-10-28T00:00:00Z',
    completedAt: null,
    createdAt: '2026-10-28T00:00:00Z',
    updatedAt: '2026-10-28T00:00:00Z',
    sourceRevision: {
      id: 'source-1',
      projectId: 'project-1',
      active: true,
      revisionType: 'GIT',
      gitBranch: 'main',
      gitCommit: 'abc123',
      gitUrl: null,
      sourceUri: null,
      versionLabel: 'v1',
      versionNote: null,
      createdBy: 'user-1',
      createdAt: '2026-10-28T00:00:00Z'
    },
    summary: {
      migrationInputs: { total: 0, byStatus: {} },
      report: null,
      recheck: null,
      sourceDeploy: createStageRun(),
      build: createStageRun(),
      test: createStageRun(),
      testComparison: null
    }
  }
}
