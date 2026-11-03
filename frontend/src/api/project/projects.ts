import type { PageResponse } from '@/types'
import type {
  CreateProjectRequest,
  ProjectArchitecture,
  ProjectBuildStatus,
  ProjectListItem,
  ProjectOverview,
  ProjectOverviewTask,
  ProjectPanelId,
  ProjectStage,
  ProjectWorkflowStageStatus
} from '@/types/project'
import type { ProjectTaskStatus } from '@/types/project/task'

import request from '../request'
import { type BackendSourceRevision, toProjectSourceArtifact } from './assets'
import {
  type BackendProjectRuntimeEnvironments,
  toProjectRuntimeEnvironments
} from './runtime-environments'

export interface BackendProject {
  id: string
  name: string
  description: string | null
  sourceArch: string
  targetArch: string
  stage: ProjectStage
  status: 'ACTIVE' | 'ARCHIVED'
  sourceInputType: string | null
  createdBy: string
  createdAt: string
  updatedAt: string | null
  highRiskCount?: number
  latestScanAt?: string | null
  latestBuildStatus?: ProjectBuildStatus | null
  latestTestStatus?: ProjectTaskStatus | null
}

export interface BackendProjectListItem extends BackendProject {
  activeTask: ProjectListItem['activeTask']
  attention: ProjectListItem['attention']
  buildSummary: ProjectListItem['buildSummary']
  currentBaseline: ProjectListItem['currentBaseline']
  lastActivityAt: ProjectListItem['lastActivityAt']
  primaryAction: ProjectListItem['primaryAction']
  reportSummary: ProjectListItem['reportSummary']
  riskSummary: ProjectListItem['riskSummary']
  testSummary: ProjectListItem['testSummary']
}

export interface ProjectListParams {
  status?: BackendProject['status']
  stage?: ProjectStage
  keyword?: string
  page: number
  pageSize: number
}

export interface UpdateProjectRequest {
  description?: string
  name?: string
  sourceArch?: ProjectArchitecture
  targetArch?: ProjectArchitecture
}

export interface BackendProjectOverview {
  project: BackendProject
  currentContext: {
    activeSourceRevision: BackendSourceRevision | null
    runtimeEnvironments: BackendProjectRuntimeEnvironments
  }
  workflow: {
    recommendedStageId: string | null
    stages: Array<ProjectWorkflowStageStatus & { reasonCode: string }>
  }
  recentTasks: ProjectOverviewTask[]
  latestTaskEventId: number
}

export function toProjectListItem(
  project: BackendProject | BackendProjectListItem
): ProjectListItem {
  const summary = project as Partial<BackendProjectListItem>
  const summaryAvailable =
    'riskSummary' in project && 'primaryAction' in project && 'lastActivityAt' in project

  return {
    activeTask: summary.activeTask ?? null,
    attention: summary.attention ?? null,
    buildSummary: summary.buildSummary ?? null,
    id: project.id,
    createdAt: project.createdAt,
    currentBaseline: summary.currentBaseline ?? null,
    lastActivityAt: summary.lastActivityAt ?? null,
    latestBuildStatus: project.latestBuildStatus ?? null,
    latestScanAt: project.latestScanAt ?? null,
    latestTestStatus: project.latestTestStatus ?? null,
    name: project.name,
    primaryAction: summary.primaryAction ?? null,
    reportSummary: summary.reportSummary ?? null,
    riskSummary: summary.riskSummary ?? null,
    sourceArch: project.sourceArch as ProjectListItem['sourceArch'],
    targetArch: project.targetArch as ProjectListItem['targetArch'],
    stage: project.stage,
    status: project.status,
    summaryAvailable,
    testSummary: summary.testSummary ?? null
  }
}

export function toProjectOverview(overview: BackendProjectOverview): ProjectOverview {
  const project = overview.project
  const activeRevision = overview.currentContext.activeSourceRevision
  return {
    project: {
      id: project.id,
      name: project.name,
      description: project.description,
      sourceArch: project.sourceArch as ProjectOverview['project']['sourceArch'],
      targetArch: project.targetArch as ProjectOverview['project']['targetArch'],
      stage: project.stage,
      status: project.status,
      sourceInputType: project.sourceInputType,
      createdBy: project.createdBy,
      createdAt: project.createdAt,
      updatedAt: project.updatedAt,
      highRiskCount: project.highRiskCount ?? 0,
      latestScanAt: project.latestScanAt ?? null,
      latestBuildStatus: project.latestBuildStatus ?? null,
      latestTestStatus: project.latestTestStatus ?? null
    },
    currentContext: {
      activeSourceRevision: activeRevision ? toProjectSourceArtifact(activeRevision) : null,
      runtimeEnvironments: toProjectRuntimeEnvironments(overview.currentContext.runtimeEnvironments)
    },
    workflow: {
      recommendedStageId: overview.workflow.recommendedStageId as ProjectPanelId | null,
      stages: overview.workflow.stages.map((stage) => ({
        ...stage,
        id: stage.id as ProjectPanelId
      }))
    },
    recentTasks: overview.recentTasks ?? [],
    latestTaskEventId: overview.latestTaskEventId
  }
}

export function getProjects(params: ProjectListParams) {
  return request
    .get<PageResponse<BackendProjectListItem>>('/projects', { params })
    .then((page): PageResponse<ProjectListItem> => ({
      ...page,
      items: page.items.map(toProjectListItem)
    }))
}

export function createProject(data: CreateProjectRequest) {
  return request
    .post<BackendProject>('/projects', {
      name: data.name,
      description: data.description,
      sourceArch: data.sourceArch,
      targetArch: data.targetArch
    })
    .then(toProjectListItem)
}

export function getProject(projectId: string) {
  return request.get<BackendProject>(`/projects/${projectId}`)
}

export function updateProject(projectId: string, data: UpdateProjectRequest) {
  return request.patch<BackendProject>(`/projects/${projectId}`, data)
}

export function getProjectOverview(projectId: string) {
  return request
    .get<BackendProjectOverview>(`/projects/${projectId}/overview`)
    .then(toProjectOverview)
}
