import { beforeEach, describe, expect, it, vi } from 'vitest'

const { request } = vi.hoisted(() => ({
  request: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn()
  }
}))

vi.mock('./request', () => ({ default: request, UPLOAD_REQUEST_TIMEOUT_MS: 300_000 }))

import {
  createProjectArchive,
  getProjectArchive,
  getProjectArchiveDownloadUrl,
  getProjectArchiveItemDownloadUrl,
  getProjectArchiveLatest,
  getProjectArchives
} from './project/archive'
import {
  createProjectEvaluationReport,
  getProjectEvaluationReportCodeFiles,
  getProjectEvaluationReportCodeFindings,
  getProjectEvaluationReportCodeModules,
  getProjectEvaluationReportDownloadUrl,
  getProjectEvaluationReportFileContext,
  getProjectEvaluationReportFindingTypes,
  getProjectEvaluationReportInputs,
  getProjectEvaluationReportLatest,
  getProjectEvaluationReports
} from './project/assessment-report'
import { updateProjectSourceFromGit, uploadProjectSourceArtifact } from './project/assets'
import { getProjectAutomatedTestLatest } from './project/automated-test'
import {
  createProjectBuildValidations,
  getProjectBuildValidationLatest,
  getProjectBuildValidations
} from './project/build-validation'
import {
  createProjectDependencyAnalysis,
  createProjectDependencyAutoFix,
  getProjectDependencyAnalyses,
  getProjectDependencyAnalysisArtifacts,
  getProjectDependencyAnalysisLatest,
  getProjectDependencyAnalysisResult,
  getProjectDependencyAnalysisResults,
  getProjectDependencyAutoFixCandidates
} from './project/dependency-analysis'
import {
  createProjectEnvironment,
  getProjectEnvironments,
  getProjectEnvironmentScriptExecutions,
  testProjectEnvironmentConnection,
  testSavedProjectEnvironmentConnection
} from './project/environments'
import {
  createProjectKnowledgeBaseScan,
  getProjectKnowledgeBaseFinding,
  getProjectKnowledgeBaseFindingCodeContext,
  getProjectKnowledgeBaseFindings,
  getProjectKnowledgeBaseScan,
  getProjectKnowledgeBaseScanLatest,
  getProjectKnowledgeBaseScans
} from './project/knowledge-base'
import {
  autoMergeProjectMigrationInputs,
  getProjectMigrationInput,
  getProjectMigrationInputs,
  importProjectMigrationInputs,
  updateProjectMigrationInput
} from './project/migration-input'
import {
  getProjectOptimizationCycle,
  getProjectOptimizationCycleLatest,
  getProjectOptimizationCycles
} from './project/optimization-cycle'
import {
  createProject,
  getProject,
  getProjectOverview,
  getProjects,
  toProjectListItem,
  toProjectOverview,
  updateProject
} from './project/projects'
import { updateProjectRuntimeEnvironments } from './project/runtime-environments'
import { getProjectStageReadiness, getProjectWorkflowStatus } from './project/workflow'

describe('project API contract', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    request.get.mockResolvedValue({
      items: [],
      page: 1,
      pageSize: 20,
      total: 0,
      hasMore: false,
      project: {
        id: 'project-1',
        name: 'demo',
        sourceArch: 'x86_64',
        targetArch: 'riscv64',
        status: 'ACTIVE',
        stage: 'PROJECT_CREATED',
        sourceInputType: null,
        createdAt: '2026-07-16T00:00:00Z'
      },
      currentContext: {
        activeSourceRevision: null,
        runtimeEnvironments: {
          projectId: 'project-1',
          sourceEnvironment: null,
          targetEnvironment: null,
          version: 1,
          ready: false,
          missing: ['sourceEnvironment', 'targetEnvironment'],
          updatedBy: null,
          updatedAt: null
        }
      },
      workflow: { recommendedStageId: 'env', stages: [] },
      recentTasks: [],
      latestTaskEventId: 0
    })
    request.post.mockResolvedValue({})
    request.patch.mockResolvedValue({})
  })

  it('uses the project overview and workflow endpoints', () => {
    getProjectOverview('project-1')
    getProjectWorkflowStatus('project-1')

    expect(request.get).toHaveBeenNthCalledWith(1, '/projects/project-1/overview')
    expect(request.get).toHaveBeenNthCalledWith(2, '/projects/project-1/workflow/status')
  })

  it('uses the optimization cycle latest, history, and detail endpoints', () => {
    getProjectOptimizationCycleLatest('project-1')
    getProjectOptimizationCycles('project-1', {
      page: 2,
      pageSize: 10,
      sortBy: 'createdAt',
      sortDirection: 'desc',
      status: 'ACTIVE'
    })
    getProjectOptimizationCycle('project-1', 'cycle-1')

    expect(request.get).toHaveBeenNthCalledWith(1, '/projects/project-1/optimization-cycles/latest')
    expect(request.get).toHaveBeenNthCalledWith(2, '/projects/project-1/optimization/cycles', {
      params: {
        page: 2,
        pageSize: 10,
        sortBy: 'createdAt',
        sortDirection: 'desc',
        status: 'ACTIVE'
      }
    })
    expect(request.get).toHaveBeenNthCalledWith(
      3,
      '/projects/project-1/optimization/cycles/cycle-1'
    )
  })

  it('uses the project archive latest, create, history, detail, and download endpoints', () => {
    getProjectArchiveLatest('project-1')
    createProjectArchive('project-1')
    getProjectArchives('project-1', { page: 2, pageSize: 10, status: 'SUCCEEDED' })
    getProjectArchive('archive-1')

    expect(request.get).toHaveBeenNthCalledWith(1, '/projects/project-1/archives/latest')
    expect(request.post).toHaveBeenNthCalledWith(1, '/projects/project-1/archives')
    expect(request.get).toHaveBeenNthCalledWith(2, '/projects/project-1/archives', {
      params: { page: 2, pageSize: 10, status: 'SUCCEEDED' }
    })
    expect(request.get).toHaveBeenNthCalledWith(3, '/archives/archive-1')
    expect(getProjectArchiveDownloadUrl('archive-1')).toBe('/api/archives/archive-1/download')
    expect(getProjectArchiveItemDownloadUrl('archive-1', 'item-1', 'HTML')).toBe(
      '/api/archives/archive-1/items/item-1?format=HTML'
    )
  })

  it('maps implemented overview source and environment summaries', () => {
    expect(
      toProjectOverview({
        project: {
          id: 'project-1',
          name: 'demo',
          description: null,
          sourceArch: 'x86_64',
          targetArch: 'riscv64',
          stage: 'PROJECT_CREATED',
          status: 'ACTIVE',
          sourceInputType: 'GIT',
          createdBy: 'user-1',
          createdAt: '2026-07-20T00:00:00Z',
          updatedAt: null,
          highRiskCount: 0,
          latestScanAt: null,
          latestBuildStatus: null,
          latestTestStatus: null
        },
        currentContext: {
          activeSourceRevision: {
            id: 'source-1',
            projectId: 'project-1',
            revisionType: 'GIT',
            versionLabel: null,
            sourceUri: null,
            gitUrl: 'git@example.com:team/demo.git',
            gitBranch: 'main',
            gitCommit: 'abc123',
            versionNote: 'current source',
            active: true,
            createdBy: 'user-1',
            createdAt: '2026-07-20T00:00:00Z'
          },
          runtimeEnvironments: {
            projectId: 'project-1',
            sourceEnvironment: {
              id: 'env-x86',
              name: 'source-x86',
              role: 'SOURCE_X86',
              arch: 'x86_64',
              status: 'AVAILABLE',
              enabled: true,
              configVersion: 2
            },
            targetEnvironment: {
              id: 'env-rv',
              name: 'target-riscv',
              role: 'TARGET_RISCV',
              arch: 'riscv64',
              status: 'AVAILABLE',
              enabled: true,
              configVersion: 4
            },
            version: 3,
            ready: true,
            missing: [],
            updatedBy: 'user-1',
            updatedAt: '2026-07-20T00:00:00Z'
          }
        },
        workflow: {
          recommendedStageId: 'kb',
          stages: [
            { id: 'assets', status: 'DONE', reasonCode: 'ACTIVE_SOURCE_READY' },
            { id: 'kb', status: 'RUNNING', reasonCode: 'KB_SCAN_RUNNING' }
          ]
        },
        recentTasks: [
          {
            id: 'task-1',
            message: '执行 KB Scanner',
            progress: 45,
            status: 'RUNNING',
            type: 'KB_SCAN'
          }
        ],
        latestTaskEventId: 42
      })
    ).toMatchObject({
      currentContext: {
        activeSourceRevision: {
          id: 'source-1',
          repositoryUrl: 'git@example.com:team/demo.git',
          type: 'GIT',
          version: 'abc123'
        },
        runtimeEnvironments: {
          ready: true,
          sourceEnvironment: { id: 'env-x86', role: 'SOURCE', configVersion: 2 },
          targetEnvironment: { id: 'env-rv', role: 'TARGET', configVersion: 4 },
          version: 3
        }
      },
      workflow: {
        recommendedStageId: 'kb',
        stages: [
          { id: 'assets', status: 'DONE' },
          { id: 'kb', status: 'RUNNING' }
        ]
      },
      recentTasks: [{ id: 'task-1', status: 'RUNNING', progress: 45 }],
      latestTaskEventId: 42
    })
  })

  it('uses the paginated project list endpoint', () => {
    getProjects({ page: 2, pageSize: 10, status: 'ACTIVE', keyword: 'demo' })

    expect(request.get).toHaveBeenCalledWith('/projects', {
      params: { page: 2, pageSize: 10, status: 'ACTIVE', keyword: 'demo' }
    })
  })

  it('maps the current-baseline list summary and directed action returned by the backend', () => {
    expect(
      toProjectListItem({
        activeTask: {
          label: '依赖重新分析',
          message: '正在核对替代组件',
          progress: 68,
          status: 'RUNNING',
          targetPanelId: 'rv-dependency-analysis',
          type: 'DEPENDENCY_REANALYSIS'
        },
        attention: {
          level: 'BLOCKING',
          message: '仍有关键依赖未解决'
        },
        buildSummary: {
          completedAt: '2026-08-30T00:00:00Z',
          freshness: 'CURRENT',
          status: 'SUCCEEDED'
        },
        id: 'project-1',
        name: 'demo',
        description: null,
        sourceArch: 'x86_64',
        targetArch: 'riscv64',
        stage: 'BUILD_FAILED',
        status: 'ACTIVE',
        sourceInputType: 'GIT',
        createdBy: 'user-1',
        createdAt: '2026-08-01T00:00:00Z',
        updatedAt: '2026-08-31T00:00:00Z',
        highRiskCount: 4,
        latestScanAt: '2026-08-30T00:00:00Z',
        latestBuildStatus: 'FAILED',
        latestTestStatus: null,
        currentBaseline: { id: 'revision-1', label: 'v1.4.2', type: 'GIT' },
        lastActivityAt: '2026-08-31T01:00:00Z',
        primaryAction: {
          disabledReason: null,
          label: '处理阻断风险',
          targetPanelId: 'manual',
          type: 'RESOLVE_BLOCKING_RISK'
        },
        reportSummary: null,
        riskSummary: {
          blockingCount: 2,
          completedAt: '2026-08-30T00:00:00Z',
          criticalCount: 1,
          freshness: 'CURRENT',
          highCount: 3,
          status: 'SUCCEEDED'
        },
        testSummary: {
          completedAt: '2026-08-29T00:00:00Z',
          freshness: 'STALE',
          status: 'SUCCEEDED'
        }
      })
    ).toMatchObject({
      activeTask: { progress: 68, status: 'RUNNING' },
      currentBaseline: { id: 'revision-1', label: 'v1.4.2' },
      lastActivityAt: '2026-08-31T01:00:00Z',
      latestBuildStatus: 'FAILED',
      latestScanAt: '2026-08-30T00:00:00Z',
      latestTestStatus: null,
      primaryAction: { label: '处理阻断风险', targetPanelId: 'manual' },
      riskSummary: { blockingCount: 2, criticalCount: 1, highCount: 3 },
      status: 'ACTIVE',
      summaryAvailable: true,
      testSummary: { freshness: 'STALE', status: 'SUCCEEDED' }
    })
  })

  it('marks legacy project payloads as unsynchronized instead of deriving conclusions', () => {
    expect(
      toProjectListItem({
        id: 'project-1',
        name: 'demo',
        description: null,
        sourceArch: 'x86_64',
        targetArch: 'riscv64',
        stage: 'SOURCE_IMPORTED',
        status: 'ACTIVE',
        sourceInputType: 'GIT',
        createdBy: 'user-1',
        createdAt: '2026-08-01T00:00:00Z',
        updatedAt: '2026-08-31T00:00:00Z',
        highRiskCount: 293,
        latestScanAt: '2026-08-30T00:00:00Z',
        latestBuildStatus: 'SUCCEEDED',
        latestTestStatus: 'SUCCEEDED'
      })
    ).toMatchObject({
      buildSummary: null,
      currentBaseline: null,
      lastActivityAt: null,
      latestBuildStatus: 'SUCCEEDED',
      latestScanAt: '2026-08-30T00:00:00Z',
      latestTestStatus: 'SUCCEEDED',
      riskSummary: null,
      summaryAvailable: false,
      testSummary: null
    })
  })

  it('uses the project detail and update endpoints', () => {
    getProject('project-1')
    updateProject('project-1', { description: 'updated description' })

    expect(request.get).toHaveBeenCalledWith('/projects/project-1')
    expect(request.patch).toHaveBeenCalledWith('/projects/project-1', {
      description: 'updated description'
    })
  })

  it('uses the project-create request shape supported by the backend', () => {
    createProject({
      name: 'demo',
      description: 'demo project',
      sourceArch: 'x86_64',
      targetArch: 'riscv64'
    })

    expect(request.post).toHaveBeenCalledWith('/projects', {
      name: 'demo',
      description: 'demo project',
      sourceArch: 'x86_64',
      targetArch: 'riscv64'
    })
  })

  it('uses the approved environment request shapes', () => {
    testProjectEnvironmentConnection('project-1', {
      name: 'target',
      role: 'TARGET',
      architecture: 'riscv64',
      host: '192.0.2.20',
      sshPort: 22,
      username: 'deploy',
      workdir: '/workspace',
      authType: 'KEY',
      privateKey: 'private-key',
      jumpHostEnvironmentId: 'jump-host-1'
    })

    expect(request.post).toHaveBeenNthCalledWith(
      1,
      '/projects/project-1/environments/test-connection',
      {
        role: 'TARGET_RISCV',
        arch: 'riscv64',
        host: '192.0.2.20',
        sshPort: 22,
        username: 'deploy',
        loginType: 'KEY',
        privateKey: 'private-key',
        jumpHostEnvironmentId: 'jump-host-1',
        workingDirectory: '/workspace',
        timeoutSeconds: 60
      }
    )

    testSavedProjectEnvironmentConnection('project-1', 'environment-1')

    expect(request.post).toHaveBeenNthCalledWith(
      2,
      '/projects/project-1/environments/environment-1/test-connection',
      { timeoutSeconds: 60 },
      { suppressErrorToast: true }
    )
  })

  it('maps a selected jump-host environment to the environment create request', async () => {
    request.post.mockResolvedValueOnce({
      arch: 'riscv64',
      basicInfo: null,
      credential: { configured: true, kind: 'SSH_KEY' },
      enabled: true,
      host: '192.0.2.20',
      id: 'env-1',
      jumpHost: { id: 'jump-host-1', name: 'jump-host' },
      name: 'target',
      projectId: 'project-1',
      role: 'TARGET_RISCV',
      sshPort: 22,
      status: 'UNKNOWN',
      username: 'root',
      workingDirectory: '/workspace'
    })

    await createProjectEnvironment('project-1', {
      architecture: 'riscv64',
      authType: 'KEY',
      host: '192.0.2.20',
      jumpHostEnvironmentId: 'jump-host-1',
      name: 'target',
      privateKey: 'private-key',
      role: 'TARGET',
      sshPort: 22,
      username: 'deploy',
      workdir: '/workspace'
    })

    expect(request.post).toHaveBeenCalledWith('/projects/project-1/environments', {
      arch: 'riscv64',
      host: '192.0.2.20',
      jumpHostEnvironmentId: 'jump-host-1',
      loginType: 'KEY',
      name: 'target',
      password: undefined,
      privateKey: 'private-key',
      role: 'TARGET_RISCV',
      sshPort: 22,
      username: 'deploy',
      workingDirectory: '/workspace'
    })
  })

  it('maps environment check basic information from the environment API', async () => {
    request.get.mockResolvedValueOnce({
      hasMore: false,
      items: [
        {
          arch: 'x86_64',
          basicInfo: {
            arch: 'x86_64',
            basicTools: { git: true },
            checkedAt: '2026-07-21T03:00:00Z',
            kernelVersion: '6.8.0',
            osName: 'Ubuntu',
            osVersion: '24.04',
            packageManager: 'apt',
            permissions: { writable: true },
            remoteUser: 'root',
            snapshotId: 'snapshot-1',
            snapshotKind: 'BASIC_CHECK',
            toolVersions: { git: '2.43.0' },
            workingDirectory: { exists: true }
          },
          credential: { configured: true, kind: 'SSH_KEY' },
          enabled: true,
          host: '192.0.2.10',
          id: 'env-1',
          name: 'source',
          projectId: 'project-1',
          role: 'SOURCE_X86',
          sshPort: 22,
          status: 'AVAILABLE',
          username: 'root',
          workingDirectory: '/workspace'
        }
      ],
      page: 1,
      pageSize: 20,
      total: 1
    })

    const response = await getProjectEnvironments('project-1', { page: 1, pageSize: 20 })

    expect(response.items[0]).toMatchObject({
      basicInfo: { packageManager: 'apt', snapshotKind: 'BASIC_CHECK' },
      os: 'Ubuntu 24.04'
    })
  })

  it('uses paginated environment execution requests', () => {
    getProjectEnvironmentScriptExecutions('project-1', {
      environmentId: 'environment-1',
      page: 2,
      pageSize: 10
    })

    expect(request.get).toHaveBeenCalledWith('/projects/project-1/script-executions', {
      params: { environmentId: 'environment-1', page: 2, pageSize: 10 }
    })
  })

  it('maps jump-host environment list filters to the backend role', () => {
    getProjectEnvironments('project-1', {
      page: 1,
      pageSize: 200,
      role: 'JUMP_HOST',
      status: 'AVAILABLE'
    })

    expect(request.get).toHaveBeenCalledWith('/projects/project-1/environments', {
      params: {
        page: 1,
        pageSize: 200,
        role: 'GENERAL_EXECUTION',
        status: 'AVAILABLE'
      }
    })
  })

  it('uses dedicated source artifact endpoints', () => {
    const file = new File(['source'], 'demo.zip', { type: 'application/zip' })
    request.post.mockResolvedValueOnce({
      sourceRevision: {
        id: 'source-archive',
        projectId: 'project-1',
        revisionType: 'ARCHIVE',
        versionLabel: 'v2',
        sourceUri: '/sources/demo.zip',
        gitUrl: null,
        gitBranch: null,
        gitCommit: null,
        versionNote: '修复后的源码',
        active: true,
        createdAt: '2026-07-20T00:00:00Z',
        createdBy: 'user-1'
      }
    })

    uploadProjectSourceArtifact('project-1', {
      file,
      versionNote: '修复后的源码'
    })
    updateProjectSourceFromGit('project-1', {
      repositoryUrl: 'git@example.com:team/demo.git',
      ref: 'main',
      username: 'git-user',
      accessToken: 'secret-token'
    })

    expect(request.post).toHaveBeenNthCalledWith(
      1,
      '/projects/project-1/sources/archive',
      expect.any(FormData),
      {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 300_000
      }
    )
    expect(request.post).toHaveBeenNthCalledWith(2, '/projects/project-1/sources/git-sync', {
      gitUrl: 'git@example.com:team/demo.git',
      gitBranch: 'main',
      username: 'git-user',
      token: 'secret-token',
      credentialKind: 'GIT_TOKEN',
      activate: true
    })
  })

  it('uses the project runtime environment write endpoint', () => {
    updateProjectRuntimeEnvironments('project-1', {
      sourceEnvironmentId: 'env-x86',
      targetEnvironmentId: 'env-rv'
    })

    expect(request.patch).toHaveBeenCalledWith('/projects/project-1/runtime-environments', {
      sourceEnvironmentId: 'env-x86',
      targetEnvironmentId: 'env-rv'
    })
  })

  it('uses stage readiness and domain latest endpoints', () => {
    getProjectStageReadiness('project-1', 'x86-build')
    getProjectBuildValidationLatest('project-1', 'SOURCE_ONLY')
    getProjectBuildValidationLatest('project-1', 'VALIDATION')
    getProjectDependencyAnalysisLatest('project-1')
    getProjectKnowledgeBaseScanLatest('project-1')
    getProjectEvaluationReportLatest('project-1')
    getProjectAutomatedTestLatest('project-1')

    expect(request.get).toHaveBeenNthCalledWith(1, '/projects/project-1/readiness', {
      params: { stageId: 'x86-build' }
    })
    expect(request.get).toHaveBeenNthCalledWith(2, '/projects/project-1/builds/latest', {
      params: { mode: 'SOURCE_ONLY' }
    })
    expect(request.get).toHaveBeenNthCalledWith(3, '/projects/project-1/builds/latest', {
      params: { mode: 'VALIDATION' }
    })
    expect(request.get).toHaveBeenNthCalledWith(4, '/projects/project-1/dependency-analyses/latest')
    expect(request.get).toHaveBeenNthCalledWith(5, '/projects/project-1/kb-scans/latest')
    expect(request.get).toHaveBeenNthCalledWith(6, '/projects/project-1/reports/evaluation/latest')
    expect(request.get).toHaveBeenNthCalledWith(7, '/projects/project-1/automated-tests/latest')
  })

  it('uses source-only build and dependency-analysis endpoints', () => {
    createProjectBuildValidations('project-1', {
      buildScope: 'SOURCE_ONLY',
      script: 'make -j',
      timeoutSeconds: 3600
    })
    getProjectBuildValidations('project-1', {
      buildScope: 'SOURCE_ONLY',
      page: 1,
      pageSize: 20,
      sourceRevisionId: 'source-1'
    })
    createProjectDependencyAnalysis('project-1', { sourceRevisionId: 'source-1' })
    getProjectDependencyAnalyses('project-1', {
      page: 1,
      pageSize: 20,
      sourceRevisionId: 'source-1'
    })
    getProjectDependencyAnalysisResults('dependency-1', { page: 1, pageSize: 20 })
    getProjectDependencyAnalysisResult('result-1')
    getProjectDependencyAnalysisArtifacts('dependency-1')
    getProjectDependencyAutoFixCandidates('dependency-1', {
      page: 1,
      pageSize: 200,
      selectable: true
    })
    createProjectDependencyAutoFix('dependency-1', {
      resultItemIds: ['result-1'],
      rerunDependencyAnalysis: true
    })

    expect(request.post).toHaveBeenNthCalledWith(1, '/projects/project-1/builds', {
      buildScope: 'SOURCE_ONLY',
      script: 'make -j',
      timeoutSeconds: 3600
    })
    expect(request.get).toHaveBeenNthCalledWith(1, '/projects/project-1/builds', {
      params: {
        buildScope: 'SOURCE_ONLY',
        page: 1,
        pageSize: 20,
        sourceRevisionId: 'source-1'
      }
    })
    expect(request.post).toHaveBeenNthCalledWith(2, '/projects/project-1/dependency-analyses', {
      sourceRevisionId: 'source-1'
    })
    expect(request.get).toHaveBeenNthCalledWith(2, '/projects/project-1/dependency-analyses', {
      params: { page: 1, pageSize: 20, sourceRevisionId: 'source-1' }
    })
    expect(request.get).toHaveBeenNthCalledWith(3, '/dependency-analyses/dependency-1/results', {
      params: { page: 1, pageSize: 20 }
    })
    expect(request.get).toHaveBeenNthCalledWith(4, '/dependency-analysis-results/result-1')
    expect(request.get).toHaveBeenNthCalledWith(5, '/dependency-analyses/dependency-1/artifacts')
    expect(request.get).toHaveBeenNthCalledWith(
      6,
      '/dependency-analyses/dependency-1/auto-fix-candidates',
      { params: { page: 1, pageSize: 200, selectable: true } }
    )
    expect(request.post).toHaveBeenNthCalledWith(
      3,
      '/dependency-analyses/dependency-1/auto-fixes',
      { resultItemIds: ['result-1'], rerunDependencyAnalysis: true }
    )
  })

  it('uses dedicated knowledge base scan endpoints', () => {
    getProjectKnowledgeBaseScans('project-1', { page: 1, pageSize: 20, includeStale: true })
    getProjectKnowledgeBaseScan('scan-1')
    getProjectKnowledgeBaseFindings('scan-1', { page: 1, pageSize: 20 })
    getProjectKnowledgeBaseFinding('finding-1')
    getProjectKnowledgeBaseFindingCodeContext('finding-1')
    createProjectKnowledgeBaseScan('project-1', {
      language: ['C/C++'],
      sourceArch: 'x86',
      scope: './',
      excludedDirectoryNames: 'build,node_modules,.git,target,__pycache__',
      excludedPaths: 'third_party/simd\nvendor/x86'
    })
    expect(request.get).toHaveBeenNthCalledWith(1, '/projects/project-1/kb-scans', {
      params: { page: 1, pageSize: 20, includeStale: true }
    })
    expect(request.get).toHaveBeenNthCalledWith(2, '/kb-scans/scan-1')
    expect(request.get).toHaveBeenNthCalledWith(3, '/kb-scans/scan-1/findings', {
      params: { page: 1, pageSize: 20 }
    })
    expect(request.get).toHaveBeenNthCalledWith(4, '/findings/finding-1')
    expect(request.get).toHaveBeenNthCalledWith(5, '/findings/finding-1/code-context')
    expect(request.post).toHaveBeenNthCalledWith(1, '/projects/project-1/kb-scans', {
      language: 'c,cpp',
      sourceArch: 'x86',
      scanScope: { mode: 'ALL', paths: [] },
      excludePaths: [
        'build',
        'node_modules',
        '.git',
        'target',
        '__pycache__',
        'third_party/simd',
        'vendor/x86'
      ]
    })
  })

  it('uses source-revision scoped migration input endpoints', () => {
    getProjectMigrationInputs('project-1', 'source-1', {
      page: 2,
      pageSize: 10,
      keyword: 'intrinsic',
      detectedLevel: 'HIGH',
      sourceType: 'KB_FINDING',
      status: 'CANDIDATE'
    })
    autoMergeProjectMigrationInputs('project-1', 'source-1', {
      sourceTypes: ['KB_FINDING', 'DEPENDENCY_RESULT']
    })
    importProjectMigrationInputs('project-1', 'source-1', {
      sourceType: 'KB_FINDING',
      sourceRefIds: ['finding-1']
    })
    getProjectMigrationInput('item-1')
    updateProjectMigrationInput('item-1', { status: 'CONFIRMED' })

    expect(request.get).toHaveBeenNthCalledWith(
      1,
      '/projects/project-1/source-revisions/source-1/migration-inputs',
      {
        params: {
          page: 2,
          pageSize: 10,
          keyword: 'intrinsic',
          detectedLevel: 'HIGH',
          sourceType: 'KB_FINDING',
          status: 'CANDIDATE'
        }
      }
    )
    expect(request.get).toHaveBeenNthCalledWith(2, '/migration-inputs/item-1')
    expect(request.post).toHaveBeenNthCalledWith(
      1,
      '/projects/project-1/source-revisions/source-1/migration-inputs:auto-merge',
      { sourceTypes: ['KB_FINDING', 'DEPENDENCY_RESULT'] }
    )
    expect(request.post).toHaveBeenNthCalledWith(
      2,
      '/projects/project-1/source-revisions/source-1/migration-inputs:import',
      { sourceType: 'KB_FINDING', sourceRefIds: ['finding-1'] }
    )
    expect(request.patch).toHaveBeenNthCalledWith(1, '/migration-inputs/item-1', {
      status: 'CONFIRMED'
    })
  })

  it('uses evaluation report endpoints', () => {
    createProjectEvaluationReport('project-1', 'source-1')
    getProjectEvaluationReports('project-1', {
      page: 1,
      pageSize: 20,
      sourceRevisionId: 'source-1',
      sortBy: 'createdAt',
      sortDirection: 'desc'
    })
    getProjectEvaluationReportInputs('report-1', {
      inputType: 'MIGRATION_INPUT_REF',
      page: 1,
      pageSize: 200
    })
    getProjectEvaluationReportCodeModules('report-1', {
      page: 1,
      pageSize: 10,
      riskLevel: 'HIGH',
      sortBy: 'highestRisk',
      sortDirection: 'desc'
    })
    getProjectEvaluationReportCodeFiles('report-1', 'module-1', {
      page: 1,
      pageSize: 10
    })
    getProjectEvaluationReportFindingTypes('report-1', 'module-1', 'file-1', {
      page: 1,
      pageSize: 200
    })
    getProjectEvaluationReportCodeFindings('report-1', 'module-1', 'file-1', 'INTRINSIC', {
      page: 2,
      pageSize: 20
    })
    getProjectEvaluationReportFileContext('report-1', 'file/1', { fromLine: 20, lines: 80 })

    expect(request.post).toHaveBeenCalledWith('/projects/project-1/reports/evaluation', {
      sourceRevisionId: 'source-1'
    })
    expect(request.get).toHaveBeenCalledWith('/projects/project-1/reports/evaluation', {
      params: {
        page: 1,
        pageSize: 20,
        sourceRevisionId: 'source-1',
        sortBy: 'createdAt',
        sortDirection: 'desc'
      }
    })
    expect(request.get).toHaveBeenCalledWith('/reports/report-1/inputs', {
      params: { inputType: 'MIGRATION_INPUT_REF', page: 1, pageSize: 200 }
    })
    expect(request.get).toHaveBeenCalledWith('/reports/report-1/code-modules', {
      params: {
        page: 1,
        pageSize: 10,
        riskLevel: 'HIGH',
        sortBy: 'highestRisk',
        sortDirection: 'desc'
      }
    })
    expect(request.get).toHaveBeenCalledWith('/reports/report-1/code-files', {
      params: { moduleKey: 'module-1', page: 1, pageSize: 10 }
    })
    expect(request.get).toHaveBeenCalledWith('/reports/report-1/code-finding-types', {
      params: { fileKey: 'file-1', moduleKey: 'module-1', page: 1, pageSize: 200 }
    })
    expect(request.get).toHaveBeenCalledWith('/reports/report-1/code-findings', {
      params: {
        fileKey: 'file-1',
        findingType: 'INTRINSIC',
        moduleKey: 'module-1',
        page: 2,
        pageSize: 20
      }
    })
    expect(request.get).toHaveBeenCalledWith('/reports/report-1/code-files/file%2F1/context', {
      params: { fromLine: 20, lines: 80 }
    })
    expect(getProjectEvaluationReportDownloadUrl('report-1', 'JSON')).toBe(
      '/api/reports/report-1/download?format=JSON'
    )
  })
})
