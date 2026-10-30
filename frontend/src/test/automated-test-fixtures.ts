import type { ProjectAutomatedTest, ProjectTestComparison } from '@/types/project'

export function createAutomatedTest(
  overrides: Partial<ProjectAutomatedTest> = {}
): ProjectAutomatedTest {
  return {
    id: 'test-source',
    taskId: 'task-source',
    projectId: 'project-1',
    buildRunId: 'build-source',
    sourceRevisionId: 'source-1',
    environmentId: 'environment-source',
    environmentConfigVersion: 1,
    currentEnvironmentConfigVersion: 1,
    currentRuntimeEnvironment: true,
    testBatchId: 'batch-1',
    testBatchRole: 'SOURCE',
    environmentName: 'x86 环境',
    command: 'ctest',
    workingDirectory: '/workspace/source',
    timeoutSeconds: 60,
    exitCode: null,
    passedCount: null,
    failedCount: null,
    skippedCount: null,
    status: 'RUNNING',
    stdoutLogId: null,
    stderrLogId: null,
    combinedLogId: null,
    outputSummary: null,
    comparisonId: null,
    comparisonStatus: null,
    comparisonUnavailableCode: null,
    comparisonUnavailableMessage: null,
    startedAt: '2026-10-28T00:00:00Z',
    finishedAt: null,
    ...overrides
  }
}

export function createTestComparison(
  overrides: Partial<ProjectTestComparison> = {}
): ProjectTestComparison {
  return {
    id: 'comparison-1',
    taskId: 'task-comparison',
    projectId: 'project-1',
    testBatchId: 'batch-1',
    sourceTestRunId: 'test-source',
    targetTestRunId: 'test-target',
    status: 'RUNNING',
    overallStatus: null,
    riskLevel: null,
    confidence: null,
    summary: null,
    recommendation: null,
    unavailableCode: null,
    unavailableMessage: null,
    errorCode: null,
    errorMessage: null,
    createdAt: '2026-10-28T00:00:00Z',
    startedAt: '2026-10-28T00:00:00Z',
    finishedAt: null,
    updatedAt: '2026-10-28T00:00:00Z',
    ...overrides
  }
}
