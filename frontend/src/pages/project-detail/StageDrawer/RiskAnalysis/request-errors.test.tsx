import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createTestQueryClient } from '@/test/test-utils'
import { feedback } from '@/utils/feedback'

import { latest, readiness, run } from './fixtures.test-support'
import { useRiskAnalysis } from './useRiskAnalysis'

const api = vi.hoisted(() => ({
  getSpecializedAnalysisLatest: vi.fn(),
  getSpecializedAnalysis: vi.fn(),
  getSpecializedFinding: vi.fn(),
  createSpecializedAnalysis: vi.fn(),
  getProjectStageReadiness: vi.fn(),
  cancelProjectTask: vi.fn()
}))
vi.mock('@/api/project/specialized-analysis', () => api)
vi.mock('@/api/project/workflow', () => ({
  getProjectStageReadiness: api.getProjectStageReadiness
}))
vi.mock('@/api/project/task', () => ({ cancelProjectTask: api.cancelProjectTask }))
vi.mock('@/utils/feedback', () => ({ feedback: { success: vi.fn(), error: vi.fn() } }))

function setup(projectId: string | null = 'project') {
  const client = createTestQueryClient()
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return {
    client,
    ...renderHook(() => useRiskAnalysis(projectId ?? undefined, 'memory-order-analysis', true), {
      wrapper
    })
  }
}

beforeEach(() => {
  api.getProjectStageReadiness.mockResolvedValue(readiness)
  api.getSpecializedAnalysisLatest.mockResolvedValue(latest(null))
})

describe('analysis request failures and recovery', () => {
  it.each([
    [new Error('本地失败说明'), '本地失败说明', false],
    ['opaque failure', '启动失败，请重试', false],
    [{ isAxiosError: true, message: '连接失败' }, '连接失败', false],
    [
      {
        isAxiosError: true,
        response: { data: { message: '旧格式说明', code: 'COMPILE_DATABASE_REQUIRED' } }
      },
      '旧格式说明',
      true
    ],
    [
      {
        isAxiosError: true,
        message: 'transport',
        response: {
          data: {
            error: { message: '缺少共享产物', code: 'SHARED_BITCODE_BUNDLE_REQUIRED' },
            message: '低优先级说明'
          }
        }
      },
      '缺少共享产物',
      true
    ]
  ])('preserves the actionable error and rebuild gate for %j', async (error, message, rejected) => {
    api.createSpecializedAnalysis.mockRejectedValue(error)
    const { result } = setup()
    await waitFor(() => expect(result.current.canStart).toBe(true))
    act(() => result.current.create.mutate())
    await waitFor(() => expect(result.current.creationError).toBe(message))
    expect(result.current.buildPrerequisiteRejected).toBe(rejected)
    expect(api.getProjectStageReadiness).toHaveBeenCalledTimes(2)
    await act(async () => {
      await result.current.refresh()
    })
    await waitFor(() => expect(result.current.canStart).toBe(true))
    expect(result.current.creationError).toBeUndefined()
    expect(result.current.buildPrerequisiteRejected).toBe(false)
  })

  it('rejects cancellation of a completed run before calling the task endpoint', async () => {
    api.getSpecializedAnalysisLatest.mockResolvedValue(latest(run))
    const { result } = setup()
    await waitFor(() => expect(result.current.run?.id).toBe(run.id))
    await act(async () => {
      await result.current.cancel.mutateAsync()
    })
    expect(api.cancelProjectTask).not.toHaveBeenCalled()
    expect(feedback.error).toHaveBeenCalledWith('该分析已经结束，请刷新状态')
  })

  it('does not fetch data before project identity is known', async () => {
    const { result } = setup(null)
    await act(async () => {})
    expect(result.current.canStart).toBe(false)
    expect(api.getProjectStageReadiness).not.toHaveBeenCalled()
    expect(api.getSpecializedAnalysisLatest).not.toHaveBeenCalled()
  })
})
