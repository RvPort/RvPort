import { beforeEach, describe, expect, it, vi } from 'vitest'

const { request } = vi.hoisted(() => ({
  request: {
    get: vi.fn(),
    post: vi.fn()
  }
}))

vi.mock('../request', () => ({ default: request }))

import {
  createProjectAiMigration,
  getAiMigrationArtifactContent,
  getAiMigrationArtifactDownloadUrl,
  getAiMigrationRun,
  getProjectAiMigrations
} from './ai-migration'

describe('ai migration API contract', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    request.get.mockResolvedValue({})
    request.post.mockResolvedValue({})
  })

  it('lists runs of a project with pagination', () => {
    getProjectAiMigrations('project-1', { page: 0, pageSize: 20 })

    expect(request.get).toHaveBeenCalledWith('/projects/project-1/ai-migrations', {
      params: { page: 0, pageSize: 20 }
    })
  })

  it('reads one run and its artifact content for in-page display', () => {
    getAiMigrationRun('run-1')
    getAiMigrationArtifactContent('run-1', 'diff')

    expect(request.get).toHaveBeenNthCalledWith(1, '/ai-migrations/run-1')
    expect(request.get).toHaveBeenNthCalledWith(2, '/ai-migrations/run-1/artifacts/diff')
  })

  it('builds browser-downloadable artifact URLs under the API prefix', () => {
    expect(getAiMigrationArtifactDownloadUrl('run-1', 'diff')).toBe(
      '/api/ai-migrations/run-1/artifacts/diff/download'
    )
    expect(getAiMigrationArtifactDownloadUrl('run-1', 'result')).toBe(
      '/api/ai-migrations/run-1/artifacts/result/download'
    )
  })

  it('creates a run without a body field when the scan is not pinned', () => {
    createProjectAiMigration('project-1')
    expect(request.post).toHaveBeenCalledWith('/projects/project-1/ai-migrations', {})
  })

  it('pins the scan run when one is given', () => {
    createProjectAiMigration('project-1', 'scan-1')
    expect(request.post).toHaveBeenCalledWith('/projects/project-1/ai-migrations', {
      scanRunId: 'scan-1'
    })
  })
})
