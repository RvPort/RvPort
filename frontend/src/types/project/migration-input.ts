export type ProjectMigrationInputSourceType =
  'KB_FINDING' | 'DEPENDENCY_RESULT' | 'FLOATING_POINT_FINDING' | 'MEMORY_ORDER_FINDING'
export type ProjectMigrationInputStatus = 'CANDIDATE' | 'CONFIRMED' | 'EXCLUDED'

export interface ProjectMigrationInputImportRequest {
  sourceRefIds: string[]
  sourceType: ProjectMigrationInputSourceType
}

export interface ProjectMigrationInputImportResponse {
  created: number
  items: ProjectMigrationInputItem[]
  requested: number
  skipped: number
  sourceType: ProjectMigrationInputSourceType
}

export interface ProjectMigrationInputItem {
  id: string
  createdAt?: string
  detectedLevel?: string
  inputSetId?: string
  issueTitle?: string
  locationLabel?: string | null
  note?: string | null
  projectId?: string
  recommendationSummary?: string | null
  sourceRefId?: string
  sourceRevisionId?: string
  sourceType?: ProjectMigrationInputSourceType
  status?: ProjectMigrationInputStatus
  updatedAt?: string
}

export interface ProjectMigrationInputListParams {
  detectedLevel?: string
  keyword?: string
  page: number
  pageSize: number
  sortBy?: 'createdAt' | 'updatedAt' | 'sourceType' | 'detectedLevel' | 'status' | 'issueTitle'
  sortDirection?: 'asc' | 'desc'
  sourceType?: ProjectMigrationInputSourceType
  status?: ProjectMigrationInputStatus
}

export interface ProjectMigrationInputAutoMergeConfig {
  sourceTypes: ProjectMigrationInputSourceType[]
}

export interface ProjectMigrationInputAutoMergeResult {
  candidates: number
  created: number
  noResult: boolean
  skipped: number
  sourceType: ProjectMigrationInputSourceType
}

export interface ProjectMigrationInputAutoMergeResponse {
  created: number
  noResult: number
  skipped: number
  sources: ProjectMigrationInputAutoMergeResult[]
}

export interface ProjectMigrationInputDetail {
  reference: ProjectMigrationInputItem
  sourceDetail: Record<string, unknown>
}

export interface UpdateProjectMigrationInputRequest {
  note?: string
  status?: ProjectMigrationInputStatus
}
