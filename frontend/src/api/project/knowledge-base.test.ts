import { beforeEach, describe, expect, it, vi } from 'vitest'

const { request } = vi.hoisted(() => ({
  request: {
    get: vi.fn(),
    post: vi.fn()
  }
}))

vi.mock('../request', () => ({ default: request }))

import {
  createProjectKnowledgeBaseAiExtension,
  createProjectKnowledgeBaseScan,
  getProjectKnowledgeBaseAiExtensionReadiness,
  getProjectKnowledgeBaseAiExtensions
} from './knowledge-base'

describe('knowledge base AI extension API contract', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    request.get.mockResolvedValue({})
    request.post.mockResolvedValue({})
  })

  it('reads readiness and latest runs for one scan', () => {
    getProjectKnowledgeBaseAiExtensionReadiness('project-1', 'scan-1')
    getProjectKnowledgeBaseAiExtensions('project-1', {
      kbScanRunId: 'scan-1',
      page: 1,
      pageSize: 1,
      sortBy: 'createdAt',
      sortDirection: 'desc'
    })

    expect(request.get).toHaveBeenNthCalledWith(
      1,
      '/projects/project-1/kb-scans/scan-1/ai-extension-readiness'
    )
    expect(request.get).toHaveBeenNthCalledWith(2, '/projects/project-1/kb-ai-extensions', {
      params: {
        kbScanRunId: 'scan-1',
        page: 1,
        pageSize: 1,
        sortBy: 'createdAt',
        sortDirection: 'desc'
      }
    })
  })

  it('starts an AI extension for the selected scan', () => {
    createProjectKnowledgeBaseAiExtension('project-1', 'scan-1')
    createProjectKnowledgeBaseAiExtension('project-1', 'scan-1', '重点检查构建条件')

    expect(request.post).toHaveBeenNthCalledWith(
      1,
      '/projects/project-1/kb-scans/scan-1/ai-extensions',
      {}
    )
    expect(request.post).toHaveBeenNthCalledWith(
      2,
      '/projects/project-1/kb-scans/scan-1/ai-extensions',
      { note: '重点检查构建条件' }
    )
  })
})

it('maps a scoped C scan and removes blank exclusion entries without changing path meaning', async () => {
  await createProjectKnowledgeBaseScan('project', {
    language: 'c',
    scope: ' src/core ',
    excludedDirectoryNames: ' build, ,vendor ',
    excludedPaths: '\n third_party/x86 \n\n generated/config \n'
  })
  expect(request.post).toHaveBeenCalledExactlyOnceWith('/projects/project/kb-scans', {
    language: 'c',
    scanScope: { mode: 'PATHS', paths: ['src/core'] },
    excludePaths: ['build', 'vendor', 'third_party/x86', 'generated/config']
  })
})
