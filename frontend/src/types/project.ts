import type { ProjectTaskStatus } from './project/task'

export type ProjectArchitecture = 'x86_64' | 'riscv64'

export type ProjectStage =
  | 'PROJECT_CREATED'
  | 'SOURCE_IMPORTED'
  | 'ENV_CONFIGURED'
  | 'SCANNING'
  | 'REPORT_GENERATED'
  | 'WAITING_FIX'
  | 'BUILD_PASSED'
  | 'BUILD_FAILED'
  | 'TEST_PASSED'
  | 'TEST_FAILED'
  | 'ARCHIVED'

export type ProjectStageTone = 'primary' | 'warning'

export type ProjectRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH'

export type ProjectPipelineStageStatus = 'DONE' | 'RUNNING' | 'BLOCKED' | 'PENDING'
export type ProjectStageDetailStatus = 'done' | 'running' | 'blocked' | 'pending'

export type ProjectEnvironmentRole = 'SOURCE' | 'TARGET' | 'JUMP_HOST'
export type ProjectEnvironmentAuthType = 'PASSWORD' | 'KEY'
export type ProjectEnvironmentConnectionStatus = 'UNKNOWN' | 'SUCCESS' | 'FAILED'
export type ProjectEnvironmentScriptType = 'COLLECTION' | 'PRECHECK' | 'CUSTOM'
export type ProjectEnvironmentScriptStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export type ProjectBuildStatus =
  'NOT_STARTED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMEOUT'

export type ProjectTestStatus =
  'NOT_STARTED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMEOUT'

export type ProjectStagePrerequisiteStatus =
  'SATISFIED' | 'MISSING' | 'RUNNING' | 'FAILED' | 'STALE' | 'UNAVAILABLE'

export interface ProjectContextSnapshot {
  sourceRevisionId: string | null
  runtimeEnvironmentVersion: number | null
}

export interface ProjectStagePrerequisite {
  key: string
  status: ProjectStagePrerequisiteStatus
  referenceId: string | null
  reasonCode: string
  details: Record<string, unknown>
}

export interface ProjectStageReadiness {
  stageId: string
  context: ProjectContextSnapshot
  prerequisites: ProjectStagePrerequisite[]
}

export interface ProjectDomainLatestExecution {
  id: string
  taskId: string
  status: ProjectTaskStatus
  progress: number | null
  message: string | null
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
}

export interface ProjectDomainLatest<T> {
  context: ProjectContextSnapshot
  freshness: 'CURRENT' | 'STALE' | 'EMPTY'
  execution: ProjectDomainLatestExecution | null
  result: T | null
}

export type ProjectResultFreshness = 'CURRENT' | 'STALE' | 'HISTORICAL'

export const PROJECT_PANEL_IDS = [
  'project',
  'env',
  'assets',
  'x86-build',
  'rv-dependency-analysis',
  'kb',
  'memory-order-analysis',
  'floating-point-analysis',
  'report',
  'manual',
  'build',
  'test',
  'optimize',
  'archive'
] as const

export type ProjectPanelId = (typeof PROJECT_PANEL_IDS)[number]

export interface ProjectListBaselineSummary {
  id: string
  label: string
  type: 'ARCHIVE' | 'GIT'
}

export interface ProjectListResultSummary {
  completedAt: string | null
  freshness: ProjectResultFreshness | null
  status: ProjectTaskStatus | null
}

export interface ProjectListRiskSummary extends ProjectListResultSummary {
  blockingCount: number
  criticalCount: number
  highCount: number
}

export interface ProjectListActiveTask {
  label: string
  message: string | null
  progress: number | null
  status: ProjectTaskStatus
  targetPanelId: ProjectPanelId | null
  type: string
}

export interface ProjectListAttention {
  level: 'BLOCKING' | 'WARNING' | 'INFO'
  message: string
}

export interface ProjectListPrimaryAction {
  disabledReason: string | null
  label: string
  targetPanelId: ProjectPanelId | null
  type: string
}

export interface ProjectListItem {
  activeTask: ProjectListActiveTask | null
  attention: ProjectListAttention | null
  buildSummary: ProjectListResultSummary | null
  createdAt: string
  currentBaseline: ProjectListBaselineSummary | null
  id: string
  lastActivityAt: string | null
  latestBuildStatus: ProjectBuildStatus | null
  latestScanAt: string | null
  latestTestStatus: ProjectTaskStatus | null
  name: string
  primaryAction: ProjectListPrimaryAction | null
  reportSummary: ProjectListResultSummary | null
  riskSummary: ProjectListRiskSummary | null
  sourceArch: ProjectArchitecture
  targetArch: ProjectArchitecture
  stage: ProjectStage
  status: 'ACTIVE' | 'ARCHIVED'
  summaryAvailable: boolean
  testSummary: ProjectListResultSummary | null
}

export interface CreateProjectRequest {
  name: string
  description?: string
  sourceArch: ProjectArchitecture
  targetArch: ProjectArchitecture
}

export interface ProjectOverviewTask {
  createdAt?: string
  finishedAt?: string | null
  id: string
  message: string | null
  progress: number | null
  queuedAt?: string | null
  startedAt?: string | null
  status: ProjectTaskStatus
  type: string
}

export interface ProjectWorkflowStageStatus {
  id: string
  status: ProjectPipelineStageStatus
  reasonCode?: string
  analysisStatus?: string | null
  analysisComplete?: boolean | null
  message?: string | null
  messageEn?: string | null
}

export interface ProjectWorkflowStatus {
  projectId: string
  currentStageId: string
  currentStageLabel?: string
  focusGroupId?: string
  stages: ProjectWorkflowStageStatus[]
  activeEdgeIds: string[]
  updatedAt: string
}

export type ProjectSourceArtifactType = 'ARCHIVE' | 'GIT'

export interface ProjectSourceArtifact {
  active: boolean
  id: string
  type: ProjectSourceArtifactType
  version: string
  versionNote: string
  updatedAt: string
  fileName?: string
  repositoryUrl?: string
  branch?: string
  ref?: string
}

export type ProjectEvaluationReportStatus = ProjectTaskStatus
export type ProjectEvaluationReportDownloadFormat = 'HTML' | 'JSON'

export interface ProjectEvaluationReportWorkload {
  max?: number
  min?: number
  unit?: string
}

export interface ProjectEvaluationReportIssue {
  detectedLevel?: string
  id?: string
  issueTitle?: string
  locationLabel?: string
  note?: string
  recommendationSummary?: string
  sourceType?: string
  targetMatchStatus?: string
}

export interface ProjectEvaluationReportInputSnapshot extends ProjectEvaluationReportIssue {
  sourceDetail?: Record<string, unknown>
  sourceRefId?: string
}

export interface ProjectEvaluationReportInput {
  createdAt: string
  id: string
  inputId: string
  inputSnapshot: ProjectEvaluationReportInputSnapshot
  inputType: 'MIGRATION_INPUT_REF'
  reportId: string
}

export interface ProjectEvaluationReportInputListParams {
  inputType?: 'MIGRATION_INPUT_REF'
  keyword?: string
  page: number
  pageSize: number
  sortBy?: string
  sortDirection?: 'asc' | 'desc'
}

export interface ProjectEvaluationReportSummary {
  pendingVerificationCount?: number | null
  pendingVerification?: Record<string, unknown>[] | null
  memoryOrderAnalysis?: Record<string, unknown> | null
  codeFileCount?: number
  codeFindingCount?: number
  codeModuleCount?: number
  confirmedInputCount?: number
  dependencyAnalysisInputCount?: number
  dependencyTargetMatchStatusCounts?: Record<string, number>
  dependencySubstitutionNotes?: ProjectEvaluationReportIssue[]
  detectedLevelCounts?: Record<string, number>
  estimatedWorkload?: ProjectEvaluationReportWorkload
  highRiskIssueCount?: number
  priorityIssues?: ProjectEvaluationReportIssue[]
  sourceTypeCounts?: Record<string, number>
}

export interface ProjectEvaluationReportArtifact {
  artifactType: string
  contentType: string
  createdAt: string
  id: string
  sha256: string
  sizeBytes: number
  summary: Record<string, unknown>
}

export interface ProjectEvaluationReport {
  artifacts: ProjectEvaluationReportArtifact[]
  createdAt: string
  generatedAt: string | null
  id: string
  organizationAvailable?: boolean
  organizationSchemaVersion?: string | null
  projectId: string
  reportType: 'EVALUATION'
  riskLevel: string | null
  sourceRevisionId: string
  status: ProjectEvaluationReportStatus
  summary: ProjectEvaluationReportSummary
  reportUpToDate?: boolean
  reportDiffSummary?: ProjectEvaluationReportUpdateSummary | null
  taskId: string
  title: string
}

export interface ProjectEvaluationReportOrganizationListParams {
  keyword?: string
  page?: number
  pageSize?: number
  riskLevel?: string
  sortBy?: string
  sortDirection?: 'asc' | 'desc'
}

export interface ProjectEvaluationReportCodeModule {
  basis: string | null
  confidence: string | null
  fileCount: number
  findingCount: number
  findingTypeCount: number
  highestRisk: string | null
  moduleKey: string
  moduleName: string
  relatedModuleKeys: string[]
  riskCounts: Record<string, number>
  typeCounts: Record<string, number>
}

export interface ProjectEvaluationReportCodeFile {
  contextAvailable: boolean
  fileKey: string
  findingCount: number
  findingTypeCount: number
  highestRisk: string | null
  relativePath: string
  riskCounts: Record<string, number>
  sourceContentHash: string | null
  typeCounts: Record<string, number>
}

export interface ProjectEvaluationReportFindingType {
  findingCount: number
  findingType: string
  firstLine: number | null
  highestRisk: string | null
  lastLine: number | null
  symbolCount: number
}

export interface ProjectEvaluationReportCodeFinding {
  columnEnd: number | null
  columnStart: number | null
  findingType: string
  id: string
  issueTitle: string
  lineEnd: number | null
  lineStart: number | null
  locationAvailable: boolean
  recommendation: string | null
  relativePath: string
  riskLevel: string | null
  sourceDetail: Record<string, unknown>
  sourceRefId: string
  symbol: string | null
}

export interface ProjectEvaluationReportFileContextHighlight {
  columnEnd: number | null
  columnStart: number | null
  findingId: string
}

export interface ProjectEvaluationReportFileContextLine {
  highlights: ProjectEvaluationReportFileContextHighlight[]
  lineNumber: number
  text: string
}

export interface ProjectEvaluationReportFileContextBlock {
  endLine: number
  kind: 'CODE' | 'OMITTED'
  lineCount: number
  lines: ProjectEvaluationReportFileContextLine[]
  loadable: boolean
  reason: string | null
  startLine: number
}

export interface ProjectEvaluationReportFileContext {
  blocks: ProjectEvaluationReportFileContextBlock[]
  contentHash: string | null
  contextAvailable: boolean
  fileKey: string
  findings: ProjectEvaluationReportCodeFinding[]
  relativePath: string
  reportId: string
  sourceRevisionId: string
  totalLines: number | null
  unavailableReason: string | null
}

export interface ProjectEvaluationReportUpdateSummary {
  latestReportMissing: boolean
  added: number
  removed: number
  changed: number
  currentConfirmedInputCount: number
  latestReportInputCount: number
  addedSamples?: ProjectEvaluationReportUpdateItem[]
  removedSamples?: ProjectEvaluationReportUpdateItem[]
  changedSamples?: ProjectEvaluationReportUpdateItem[]
}

export interface ProjectEvaluationReportUpdateItem {
  migrationInputRefId: string
  issueTitle: string
  sourceType: string
  detectedLevel: string
  locationLabel: string | null
}

export interface ProjectEvaluationReportListParams {
  page: number
  pageSize: number
  sortBy?: 'createdAt' | 'generatedAt' | 'riskLevel' | 'status'
  sortDirection?: 'asc' | 'desc'
  sourceRevisionId?: string
  status?: ProjectEvaluationReportStatus
}

export interface UploadProjectSourceArtifactRequest {
  file: File
  versionNote?: string
}

export interface UpdateProjectSourceFromGitRequest {
  repositoryUrl: string
  ref: string
  username?: string
  accessToken?: string
}

export interface ProjectStageDetailInfoItem {
  label: string
  value: string | number
  status?: ProjectStageDetailStatus
}

export interface ProjectCreateStageBasicInfo {
  name: string
  description: string
  sourceArch: string
  targetArch: string
  sourceType: string
  languages: string[]
}

export interface ProjectCreateStageData {
  basicInfo: ProjectCreateStageBasicInfo
  statusInfo: ProjectStageDetailInfoItem[]
}

export interface UpdateProjectCreateStageRequest {
  description: string
}

export interface ProjectEnvironment {
  id: string
  name: string
  role: ProjectEnvironmentRole
  architecture: ProjectArchitecture
  os: string | null
  host: string
  jumpHostEnvironmentId: string | null
  sshPort: number
  workdir: string
  authType: ProjectEnvironmentAuthType
  basicInfo: ProjectEnvironmentBasicInfo | null
  username?: string
  credentialId?: string | null
  credentialsConfigured: boolean
  connectionStatus: ProjectEnvironmentConnectionStatus
  enabled?: boolean
}

export interface ProjectRuntimeEnvironmentSummary {
  id: string
  name: string
  role: ProjectEnvironmentRole
  architecture: ProjectArchitecture
  connectionStatus: ProjectEnvironmentConnectionStatus
  enabled: boolean
  configVersion: number
}

export interface ProjectRuntimeEnvironments {
  projectId: string
  sourceEnvironment: ProjectRuntimeEnvironmentSummary | null
  targetEnvironment: ProjectRuntimeEnvironmentSummary | null
  version: number
  ready: boolean
  missing: string[]
  updatedBy: string | null
  updatedAt: string | null
}

export interface UpdateProjectRuntimeEnvironmentsRequest {
  sourceEnvironmentId?: string
  targetEnvironmentId?: string
}

export interface ProjectEnvironmentBasicInfo {
  arch: string | null
  basicTools: Record<string, unknown>
  checkedAt: string
  kernelVersion: string | null
  osName: string | null
  osVersion: string | null
  packageManager: string | null
  permissions: Record<string, unknown>
  remoteUser: string | null
  snapshotId: string
  snapshotKind: string
  toolVersions: Record<string, unknown>
  workingDirectory: Record<string, unknown>
}

export interface ProjectEnvironmentListParams {
  page: number
  pageSize: number
  role?: ProjectEnvironmentRole
  status?: 'UNKNOWN' | 'AVAILABLE' | 'UNAVAILABLE' | 'DISABLED'
}

export interface ProjectEnvironmentInput {
  name: string
  role: ProjectEnvironmentRole
  architecture: ProjectArchitecture
  host: string
  jumpHostEnvironmentId?: string
  sshPort: number
  workdir: string
  authType: ProjectEnvironmentAuthType
  username?: string
  password?: string
  privateKey?: string
}

export type UpdateProjectEnvironmentRequest = Partial<Omit<ProjectEnvironmentInput, 'name'>>

export interface ProjectEnvironmentCheckItem {
  label: string
  status: 'SUCCESS' | 'FAILED'
  detail: string
}

export interface ProjectEnvironmentCheckResult {
  environment: ProjectEnvironment
  items: ProjectEnvironmentCheckItem[]
}

export interface ProjectEnvironmentScriptInput {
  scriptType: ProjectEnvironmentScriptType
  workdir: string
  timeout: number
  content: string
}

export interface ProjectEnvironmentScriptExecution {
  id: string
  environmentId: string
  scriptType: ProjectEnvironmentScriptType
  scriptName: string
  submittedAt: string
  startedAt: string | null
  finishedAt: string | null
  progress: number | null
  message: string | null
  scriptSha256: string | null
  scriptLength: number | null
  logEventCount: number
  exitCode: number | null
  status: ProjectEnvironmentScriptStatus
  stdout: string
  stderr: string
}

export type ProjectTaskLogType = 'STDOUT' | 'STDERR' | 'COMBINED' | 'TOOL'

export interface ProjectTaskLog {
  id: string
  taskId: string
  logType: ProjectTaskLogType
  sizeBytes: number | null
  sha256: string | null
  excerpt: string | null
  createdAt: string
}

export interface ProjectSourceDeployment {
  id: string
  projectId: string
  taskId: string
  sourceRevisionId: string
  environmentId: string
  remotePath: string
  status: ProjectTaskStatus
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
}

export type ProjectBuildBitcodeStatus =
  'NOT_ATTEMPTED' | 'UNSUPPORTED' | 'READY' | 'PARTIAL' | 'FAILED'

export type ProjectBuildBitcodeReasonCode =
  | 'NONE'
  | 'LEGACY_RECORD'
  | 'DISABLED'
  | 'BUILD_FAILED'
  | 'NOT_SOURCE_X86'
  | 'COMPILE_DATABASE_UNAVAILABLE'
  | 'NO_C_CPP_TRANSLATION_UNITS'
  | 'TOOLCHAIN_BUNDLE_UNAVAILABLE'
  | 'TOOLCHAIN_BUNDLE_INVALID'
  | 'TOOLCHAIN_CACHE_INVALID'
  | 'TOOLCHAIN_LOCK_TIMEOUT'
  | 'TOOLCHAIN_UNSUPPORTED_ARCH'
  | 'TOOLCHAIN_VERSION_MISMATCH'
  | 'TRANSLATION_UNIT_LIMIT_EXCEEDED'
  | 'DUPLICATE_CONTEXT'
  | 'TRANSLATION_UNIT_TIMEOUT'
  | 'TRANSLATION_UNIT_FAILED'
  | 'OUTPUT_SIZE_LIMIT_EXCEEDED'
  | 'TRANSFER_FAILED'
  | 'MANIFEST_INVALID'
  | 'BUNDLE_INVALID'
  | 'STORAGE_FAILED'
  | 'CANCELLED'
  | 'INTERNAL_ERROR'

export interface ProjectBuildBitcodeSummary {
  legacy: boolean
  sourceIdentityDigest: string | null
  compileContextDigest: string | null
  bundleSha256: string | null
  available: boolean
  status: ProjectBuildBitcodeStatus
  reasonCode: ProjectBuildBitcodeReasonCode
  schemaVersion: string
  artifactSetId: string | null
  toolchainId: string | null
  toolchainVersion: string | null
  plannedCount: number
  succeededCount: number
  failedCount: number
  skippedCount: number
  warningCount: number
  totalBytes: number
  manifestArtifactId: string | null
  bundleArtifactId: string | null
}

export interface ProjectBuildValidation {
  id: string
  projectId: string
  taskId: string
  sourceRevisionId: string
  environmentId: string
  buildScope: 'BOTH' | 'SOURCE_ONLY'
  sourceDeploymentId: string
  environmentConfigVersion: number | null
  currentEnvironmentConfigVersion: number
  currentRuntimeEnvironment: boolean
  currentSourceRevision?: boolean
  staleReasons?: string[]
  command: string
  workingDirectory: string
  timeoutSeconds: number | null
  exitCode: number | null
  status: ProjectTaskStatus
  failureCategory: string | null
  mergedLogId: string | null
  stdoutLogId: string | null
  stderrLogId: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
  environmentVariables: Record<string, string>
  bitcode: ProjectBuildBitcodeSummary
  environment: { id: string; name: string; arch: string } | null
}

export interface CreateProjectBuildValidationRequest {
  sourceRevisionId?: string
  buildScope?: 'BOTH' | 'SOURCE_ONLY'
  script: string
  timeoutSeconds?: number
  environmentVariables?: Record<string, string>
}

export type ProjectAutomatedTestBatchRole = 'SOURCE' | 'TARGET'

export interface CreateProjectAutomatedTestRequest {
  buildRunId: string
  testBatchId: string
  testBatchRole: ProjectAutomatedTestBatchRole
  command: string
  workingDirectory: string
  timeoutSeconds?: number
  environmentVariables?: Record<string, string>
}

export interface ProjectAutomatedTest {
  id: string
  taskId: string
  projectId: string
  buildRunId: string
  sourceRevisionId: string
  environmentId: string
  environmentConfigVersion: number | null
  currentEnvironmentConfigVersion: number
  currentRuntimeEnvironment: boolean
  testBatchId: string | null
  testBatchRole: ProjectAutomatedTestBatchRole | null
  environmentName: string
  command: string
  workingDirectory: string | null
  timeoutSeconds: number | null
  exitCode: number | null
  passedCount: number | null
  failedCount: number | null
  skippedCount: number | null
  status: ProjectTaskStatus
  stdoutLogId: string | null
  stderrLogId: string | null
  combinedLogId: string | null
  outputSummary: string | null
  comparisonId: string | null
  comparisonStatus: string | null
  comparisonUnavailableCode: string | null
  comparisonUnavailableMessage: string | null
  startedAt: string | null
  finishedAt: string | null
}

export interface ProjectTestComparison {
  id: string
  projectId: string
  taskId: string
  testBatchId: string
  sourceTestRunId: string
  targetTestRunId: string
  status: string
  overallStatus: string | null
  riskLevel: string | null
  confidence: number | null
  summary: string | null
  recommendation: string | null
  unavailableCode: string | null
  unavailableMessage: string | null
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  updatedAt: string
}

export interface ProjectEnvironmentConnectionTestRequest extends Omit<
  ProjectEnvironmentInput,
  'authType'
> {
  authType?: ProjectEnvironmentAuthType
  credentialId?: string
  environmentId?: string
}

export interface ProjectSourceRevision {
  active: boolean
  createdAt: string
  createdBy: string
  gitBranch: string | null
  gitCommit: string | null
  gitUrl: string | null
  id: string
  projectId: string
  revisionType: ProjectSourceArtifactType
  sourceUri: string | null
  versionLabel: string | null
  versionNote: string | null
}

export type ProjectOptimizationCycleStatus = 'ACTIVE' | 'SUPERSEDED' | 'ARCHIVED'

export interface ProjectOptimizationMigrationInputSummary {
  total: number
  byStatus: Record<string, number>
}

export interface ProjectOptimizationReportSummary {
  id: string
  taskId: string
  status: string
  riskLevel: string | null
  highRiskCount: number | null
  workloadSummary: string | null
  generatedAt: string | null
  createdAt: string
}

export interface ProjectOptimizationRecheckSummary {
  id: string
  taskId: string
  baseSourceRevisionId: string
  targetSourceRevisionId: string
  status: string
  diffTotal: number
  byDiffStatus: Record<string, number>
  bySeverity: Record<string, number>
  createdAt: string
}

export interface ProjectOptimizationStageRunSummary {
  total: number
  latestStatus: string | null
  latestRunId: string | null
  latestStartedAt: string | null
  latestFinishedAt: string | null
  byStatus: Record<string, number>
}

export interface ProjectOptimizationTestComparisonSummary {
  id: string
  taskId: string
  testBatchId: string
  status: string
  overallStatus: string | null
  riskLevel: string | null
  summary: string | null
  recommendation: string | null
  createdAt: string
  finishedAt: string | null
}

export interface ProjectOptimizationCycleSummary {
  migrationInputs: ProjectOptimizationMigrationInputSummary
  report: ProjectOptimizationReportSummary | null
  recheck: ProjectOptimizationRecheckSummary | null
  sourceDeploy: ProjectOptimizationStageRunSummary
  build: ProjectOptimizationStageRunSummary
  test: ProjectOptimizationStageRunSummary
  testComparison: ProjectOptimizationTestComparisonSummary | null
}

export interface ProjectOptimizationCycle {
  id: string
  projectId: string
  sourceRevisionId: string
  sourceRevision: ProjectSourceRevision
  cycleNumber: number
  status: ProjectOptimizationCycleStatus
  startedAt: string
  completedAt: string | null
  createdAt: string
  updatedAt: string
  summary: ProjectOptimizationCycleSummary
}

export interface ProjectOptimizationCycleListParams {
  page: number
  pageSize: number
  sourceRevisionId?: string
  status?: ProjectOptimizationCycleStatus
  createdFrom?: string
  createdTo?: string
  sortBy?: string
  sortDirection?: 'asc' | 'desc'
}

export type ProjectArchiveStatus = ProjectTaskStatus

export interface ProjectArchiveArtifactRef {
  format: string
  storedArtifactId: string | null
  artifactType: string | null
  contentType: string | null
  filename: string | null
}

export interface ProjectArchiveItem {
  id: string
  candidateId: string
  nodeType: string
  candidateType: string
  candidateKey: string
  resourceType: string
  resourceId: string | null
  title: string
  artifactRefs: ProjectArchiveArtifactRef[]
  metadata: Record<string, unknown>
  createdAt: string
}

export interface ProjectArchive {
  id: string
  projectId: string
  sourceRevisionId: string
  sourceRevision: ProjectSourceRevision | null
  taskId: string
  storedArtifactId: string | null
  name: string
  status: ProjectArchiveStatus
  summary: Record<string, unknown>
  sizeBytes: number | null
  sha256: string | null
  items: ProjectArchiveItem[]
  createdAt: string
  updatedAt: string
}

export interface ProjectArchiveCreateResponse {
  archiveId: string
  sourceRevisionId: string
  task: {
    id: string
    status: ProjectTaskStatus
    type: string
  }
}

export interface ProjectArchiveListParams {
  page: number
  pageSize: number
  sourceRevisionId?: string
  status?: ProjectArchiveStatus
  createdFrom?: string
  createdTo?: string
  sortBy?: string
  sortDirection?: 'asc' | 'desc'
}

export interface ProjectStageDetail<TData = unknown> {
  projectId: string
  stageId: string
  data?: TData
  updatedAt: string
}

export interface ProjectOverview {
  project: {
    id: string
    name: string
    description: string | null
    sourceArch: ProjectArchitecture
    targetArch: ProjectArchitecture
    stage: ProjectStage
    status: 'ACTIVE' | 'ARCHIVED'
    sourceInputType: string | null
    createdBy: string
    createdAt: string
    updatedAt: string | null
    highRiskCount: number
    latestScanAt: string | null
    latestBuildStatus: string | null
    latestTestStatus: string | null
  }
  currentContext: {
    activeSourceRevision: ProjectSourceArtifact | null
    runtimeEnvironments: ProjectRuntimeEnvironments
  }
  workflow: {
    recommendedStageId: ProjectPanelId | null
    stages: Array<ProjectWorkflowStageStatus & { id: ProjectPanelId; reasonCode: string }>
  }
  recentTasks: ProjectOverviewTask[]
  latestTaskEventId: number
}
