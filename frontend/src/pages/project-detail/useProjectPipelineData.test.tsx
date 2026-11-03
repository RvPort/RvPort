import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { createTestQueryClient } from '@/test/test-utils'
import type { ProjectOverview } from '@/types/project'

const { getProjectOverview } = vi.hoisted(() => ({ getProjectOverview: vi.fn() }))

vi.mock('@/api/project/projects', () => ({ getProjectOverview }))

import { QueryClientProvider } from '@tanstack/react-query'

import { useProjectPipelineData } from './useProjectPipelineData'

const overview: ProjectOverview = {
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
      active: true,
      id: 'source-1',
      type: 'GIT',
      version: 'v1.0.0',
      versionNote: '',
      updatedAt: '2026-07-20T00:00:00Z'
    },
    runtimeEnvironments: {
      missing: ['sourceEnvironment', 'targetEnvironment'],
      projectId: 'project-1',
      ready: false,
      sourceEnvironment: null,
      targetEnvironment: null,
      updatedBy: null,
      updatedAt: null,
      version: 1
    }
  },
  workflow: {
    recommendedStageId: 'kb',
    stages: [{ id: 'kb', status: 'RUNNING', reasonCode: 'KB_SCAN_RUNNING' }]
  },
  recentTasks: [],
  latestTaskEventId: 0
}

describe('useProjectPipelineData', () => {
  let queryClient = createTestQueryClient()

  beforeEach(() => {
    queryClient = createTestQueryClient()
    getProjectOverview.mockResolvedValue(overview)
  })

  afterEach(() => {
    queryClient.clear()
  })

  it('does not request data without a project id', () => {
    const { result } = renderHook(() => useProjectPipelineData(), {
      wrapper: ({ children }) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      )
    })

    expect(result.current.isLoading).toBe(false)
    expect(getProjectOverview).not.toHaveBeenCalled()
  })

  it('uses workflow state embedded in the V2 project overview', async () => {
    const { result } = renderHook(() => useProjectPipelineData('project-1'), {
      wrapper: ({ children }) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      )
    })

    await waitFor(() => expect(result.current.overview).toEqual(overview))
    expect(result.current.workflowStatus).toMatchObject({
      currentStageId: 'kb',
      stages: [{ id: 'kb', status: 'RUNNING', reasonCode: 'KB_SCAN_RUNNING' }]
    })
    expect(result.current.currentStageTitle).toBe('知识库扫描')
  })

  it('keeps x86 build ahead of knowledge base scan when both need attention', async () => {
    getProjectOverview.mockResolvedValue({
      ...overview,
      workflow: {
        recommendedStageId: 'kb',
        stages: [
          { id: 'kb', status: 'PENDING', reasonCode: 'KB_SCAN_MISSING' },
          { id: 'x86-build', status: 'PENDING', reasonCode: 'X86_BUILD_MISSING' }
        ]
      }
    })
    const { result } = renderHook(() => useProjectPipelineData('project-1'), {
      wrapper: ({ children }) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      )
    })

    await waitFor(() => expect(result.current.workflowStatus?.currentStageId).toBe('x86-build'))
    expect(result.current.currentStageTitle).toBe('x86 构建')
  })
  it('falls back to the project stage when no workflow recommendation exists and uses refreshed timestamps', async () => {
    const current = {
      ...overview,
      project: { ...overview.project, updatedAt: '2026-10-28T00:00:00Z' },
      workflow: { recommendedStageId: null, stages: [] }
    }
    getProjectOverview.mockResolvedValue(current)
    const { result } = renderHook(() => useProjectPipelineData('project-1'), {
      wrapper: ({ children }) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      )
    })
    await waitFor(() => expect(result.current.overview).toEqual(current))
    expect(result.current.workflowStatus).toMatchObject({
      currentStageId: '',
      updatedAt: current.project.updatedAt
    })
    expect(result.current.currentStageTitle).toBe('项目创建')
  })
  it('preserves loaded overview data through a failed refresh and exposes a successful retry', async () => {
    const { result } = renderHook(() => useProjectPipelineData('project-1'), {
      wrapper: ({ children }) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      )
    })
    await waitFor(() => expect(result.current.overview).toEqual(overview))
    getProjectOverview.mockRejectedValueOnce(new Error('offline'))
    await act(async () => {
      await result.current.refetch()
    })
    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.overview).toEqual(overview)
    expect(result.current.currentStageTitle).toBe('知识库扫描')
    const refreshed = { ...overview, project: { ...overview.project, name: '新名称' } }
    getProjectOverview.mockResolvedValue(refreshed)
    await act(async () => {
      await result.current.refetch()
    })
    await waitFor(() => expect(result.current.isError).toBe(false))
    await waitFor(() => expect(result.current.overview).toEqual(refreshed))
  })
})
