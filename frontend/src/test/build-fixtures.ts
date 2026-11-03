import type { ProjectBuildValidation } from '@/types/project'

export function createBuild(
  overrides: Partial<ProjectBuildValidation> = {}
): ProjectBuildValidation {
  return {
    id: 'build-1',
    projectId: 'project-1',
    taskId: 'task-1',
    sourceRevisionId: 'source-1',
    environmentId: 'env-1',
    buildScope: 'BOTH',
    sourceDeploymentId: 'deployment-1',
    environmentConfigVersion: 1,
    currentEnvironmentConfigVersion: 1,
    currentRuntimeEnvironment: true,
    command: 'make test',
    workingDirectory: '/workspace',
    timeoutSeconds: 3600,
    exitCode: null,
    status: 'RUNNING',
    failureCategory: null,
    mergedLogId: null,
    stdoutLogId: null,
    stderrLogId: null,
    startedAt: null,
    finishedAt: null,
    createdAt: '2026-10-28T00:00:00Z',
    environmentVariables: {},
    environment: { id: 'env-1', name: '构建环境', arch: 'riscv64' },
    bitcode: {
      legacy: false,
      sourceIdentityDigest: null,
      compileContextDigest: null,
      bundleSha256: null,
      available: false,
      status: 'NOT_ATTEMPTED',
      reasonCode: 'NONE',
      schemaVersion: '1',
      artifactSetId: null,
      toolchainId: null,
      toolchainVersion: null,
      plannedCount: 0,
      succeededCount: 0,
      failedCount: 0,
      skippedCount: 0,
      warningCount: 0,
      totalBytes: 0,
      manifestArtifactId: null,
      bundleArtifactId: null
    },
    ...overrides
  }
}
