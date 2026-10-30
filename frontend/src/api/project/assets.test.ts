import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  type BackendSourceRevision,
  toProjectSourceArtifact,
  updateProjectSourceFromGit,
  uploadProjectSourceArtifact
} from './assets'

const post = vi.hoisted(() => vi.fn())
vi.mock('../request', () => ({ default: { post }, UPLOAD_REQUEST_TIMEOUT_MS: 300_000 }))

const versionCases: Array<[Partial<BackendSourceRevision>, string]> = [
  [{ versionLabel: 'release' }, 'release'],
  [{ versionLabel: null }, 'commit-1'],
  [{ versionLabel: null, gitCommit: null }, 'main'],
  [{ versionLabel: null, gitCommit: null, gitBranch: null }, 'source-1']
]

const revision: BackendSourceRevision = {
  id: 'source-1',
  projectId: 'project-1',
  revisionType: 'GIT',
  versionLabel: 'v1',
  sourceUri: null,
  gitUrl: 'https://example.invalid/repo',
  gitBranch: 'main',
  gitCommit: 'commit-1',
  versionNote: null,
  active: true,
  createdAt: '2026-10-28T00:00:00Z',
  createdBy: 'user-1'
}
beforeEach(() => post.mockReset())

describe('source asset API contracts', () => {
  it.each(versionCases)(
    'uses the first available source version identifier for %j',
    (patch, version) => {
      expect(toProjectSourceArtifact({ ...revision, ...patch })).toMatchObject({
        version,
        versionNote: '',
        repositoryUrl: revision.gitUrl,
        branch: patch.gitBranch === null ? undefined : 'main',
        ref: patch.gitCommit === null ? undefined : 'commit-1'
      })
    }
  )

  it('extracts archive filenames and tolerates absent optional Git metadata', () => {
    expect(
      toProjectSourceArtifact({
        ...revision,
        revisionType: 'ARCHIVE',
        sourceUri: '/sources/archive.zip/'
      })
    ).toMatchObject({
      fileName: 'archive.zip',
      repositoryUrl: undefined,
      branch: undefined,
      ref: undefined
    })
    expect(
      toProjectSourceArtifact({ ...revision, revisionType: 'ARCHIVE', sourceUri: '/' }).fileName
    ).toBeUndefined()
    expect(
      toProjectSourceArtifact({ ...revision, gitUrl: null, gitBranch: null, gitCommit: null })
    ).toMatchObject({ repositoryUrl: undefined, branch: undefined, ref: undefined })
  })

  it.each([undefined, '上传说明'])(
    'uploads the original archive with activation and an optional note: %s',
    async (versionNote) => {
      post.mockResolvedValue({
        sourceRevision: { ...revision, revisionType: 'ARCHIVE', sourceUri: '/archives/source.zip' },
        sourceImport: {}
      })
      const file = new File(['source'], 'source.zip', { type: 'application/zip' })
      const artifact = await uploadProjectSourceArtifact('project-1', { file, versionNote })
      const [url, body, options] = post.mock.calls[0] as [string, FormData, Record<string, unknown>]
      expect(url).toBe('/projects/project-1/sources/archive')
      expect(body.get('file')).toBe(file)
      expect(body.get('activate')).toBe('true')
      expect(body.get('versionNote')).toBe(versionNote ?? null)
      expect(options).toEqual({
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 300_000
      })
      expect(artifact.fileName).toBe('source.zip')
    }
  )

  it.each([undefined, 'test-only-token'])(
    'maps Git credentials without inventing a credential kind when token=%s',
    async (accessToken) => {
      post.mockResolvedValue({ id: 'task-1' })
      expect(
        await updateProjectSourceFromGit('project-1', {
          repositoryUrl: 'https://example.invalid/repo',
          ref: 'dev',
          accessToken,
          username: 'developer'
        })
      ).toEqual({ id: 'task-1' })
      expect(post).toHaveBeenCalledWith('/projects/project-1/sources/git-sync', {
        gitUrl: 'https://example.invalid/repo',
        gitBranch: 'dev',
        username: 'developer',
        token: accessToken,
        credentialKind: accessToken ? 'GIT_TOKEN' : undefined,
        activate: true
      })
    }
  )
})
