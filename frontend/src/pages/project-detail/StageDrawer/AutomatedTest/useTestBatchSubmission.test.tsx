import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { createProjectAutomatedTest } from '@/api/project/automated-test'
import { advanceAuthSession } from '@/store/authSession'
import type { ProjectBuildValidation } from '@/types/project'
import { feedback } from '@/utils/feedback'

import type { TestBuildContext } from './types'
import { useTestBatchSubmission } from './useTestBatchSubmission'

vi.mock('@/api/project/automated-test', () => ({ createProjectAutomatedTest: vi.fn() }))
vi.mock('@/utils/feedback', () => ({
  feedback: { success: vi.fn(), warning: vi.fn(), error: vi.fn() }
}))

const contexts: TestBuildContext[] = ['SOURCE', 'TARGET'].map((role) => ({
  role: role as 'SOURCE' | 'TARGET',
  environment: null,
  reason: null,
  build: { id: `${role}-build`, workingDirectory: `/${role}` } as ProjectBuildValidation
}))

describe('test batch submission', () => {
  it.each([1, 2])(
    'keeps the draft after %i failures and retries only failed roles in the original batch',
    async (failures) => {
      const complete = vi.fn()
      vi.mocked(createProjectAutomatedTest).mockImplementation(async (_project, input) => {
        if (failures === 2 || input.testBatchRole === 'TARGET') throw new Error('unavailable')
        return { id: 'source-task' } as Awaited<ReturnType<typeof createProjectAutomatedTest>>
      })
      const { result } = renderHook(() => useTestBatchSubmission('project-A', true, contexts))
      await act(async () => {
        await result.current.submit({ command: 'original-command' }, complete)
      })
      expect(complete).not.toHaveBeenCalled()
      expect(result.current.batch?.failed).toHaveLength(failures)
      expect(Object.keys(result.current.batch!.created)).toHaveLength(2 - failures)
      const batchId = result.current.batch!.id
      vi.mocked(createProjectAutomatedTest).mockResolvedValue({ id: 'retry-task' } as Awaited<
        ReturnType<typeof createProjectAutomatedTest>
      >)
      await act(async () => {
        await result.current.retry()
      })
      expect(createProjectAutomatedTest).toHaveBeenCalledTimes(2 + failures)
      const retries = vi.mocked(createProjectAutomatedTest).mock.calls.slice(2)
      expect(
        retries.every(
          ([, input]) => input.testBatchId === batchId && input.command === 'original-command'
        )
      ).toBe(true)
      expect(complete).toHaveBeenCalledOnce()
      expect(result.current.batch?.failed).toEqual([])
    }
  )
  it('checks both prerequisites before submitting either side', async () => {
    const { result } = renderHook(() =>
      useTestBatchSubmission('A', true, [contexts[0], { ...contexts[1], build: null }])
    )
    await act(async () => {
      await result.current.submit({ command: 'test' })
    })
    expect(createProjectAutomatedTest).not.toHaveBeenCalled()
  })
  it.each(['auth', 'project', 'close'])(
    'ignores an old batch after %s changes and removes its retry entry',
    async (change) => {
      let finish!: () => void
      const pending = new Promise<Awaited<ReturnType<typeof createProjectAutomatedTest>>>(
        (resolve) => {
          finish = () =>
            resolve({ id: 'old' } as Awaited<ReturnType<typeof createProjectAutomatedTest>>)
        }
      )
      vi.mocked(createProjectAutomatedTest).mockReturnValue(pending)
      const complete = vi.fn()
      const { result, rerender } = renderHook(
        ({ project, active }) => useTestBatchSubmission(project, active, contexts),
        { initialProps: { project: 'A', active: true } }
      )
      let old!: Promise<unknown>
      act(() => {
        old = result.current.submit({ command: 'test' }, complete)
        void result.current.submit({ command: 'duplicate' })
      })
      expect(createProjectAutomatedTest).toHaveBeenCalledTimes(2)
      act(() => {
        if (change === 'auth') advanceAuthSession()
        else rerender({ project: change === 'project' ? 'B' : 'A', active: change !== 'close' })
      })
      await act(async () => {
        finish()
        await old
      })
      expect(result.current.batch).toBeUndefined()
      expect(complete).not.toHaveBeenCalled()
      expect(feedback.success).not.toHaveBeenCalled()
      expect(feedback.warning).not.toHaveBeenCalled()
    }
  )
  it('retains recovery across inner views and repeated failed-side retries', async () => {
    vi.mocked(createProjectAutomatedTest).mockImplementation(async (_, input) => {
      if (input.testBatchRole === 'TARGET') throw new Error('offline')
      return { id: 'source' } as Awaited<ReturnType<typeof createProjectAutomatedTest>>
    })
    const { result, rerender } = renderHook(
      ({ view }: { view: string }) => {
        void view
        return useTestBatchSubmission('A', true, contexts)
      },
      { initialProps: { view: 'latest' } }
    )
    await act(async () => {
      await result.current.submit({ command: 'original' })
    })
    const id = result.current.batch!.id
    rerender({ view: 'history' })
    rerender({ view: 'latest' })
    await act(async () => {
      await result.current.retry()
    })
    expect(result.current.batch?.id).toBe(id)
    expect(result.current.batch?.failed).toEqual(['TARGET'])
    await act(async () => {
      await result.current.submit({ command: 'duplicate' })
    })
    expect(createProjectAutomatedTest).toHaveBeenCalledTimes(3)
    expect(vi.mocked(createProjectAutomatedTest).mock.calls[2][1]).toMatchObject({
      testBatchId: id,
      testBatchRole: 'TARGET'
    })
  })

  it('retries the original configuration after draft and build inputs change, then starts a fresh batch explicitly', async () => {
    const request = vi.mocked(createProjectAutomatedTest)
    request.mockImplementation(async (_, input) => {
      if (input.testBatchRole === 'TARGET') throw new Error('offline')
      return { id: 'source' } as Awaited<ReturnType<typeof createProjectAutomatedTest>>
    })
    const complete = vi.fn()
    const { result, rerender } = renderHook(
      ({ inputs }) => useTestBatchSubmission('A', true, inputs),
      { initialProps: { inputs: contexts } }
    )
    const variables = { MODE: 'original' }
    await act(async () => {
      await result.current.submit(
        { command: 'original-command', environmentVariables: variables },
        complete
      )
    })
    const originalBatch = result.current.batch!.id
    variables.MODE = 'edited'
    const changedContexts = contexts.map((context) => ({
      ...context,
      build: {
        ...context.build!,
        id: `${context.role}-new-build`,
        workingDirectory: '/new-workspace'
      }
    }))
    rerender({ inputs: changedContexts })
    request.mockResolvedValue({ id: 'recovered' } as Awaited<
      ReturnType<typeof createProjectAutomatedTest>
    >)
    await act(async () => {
      await result.current.retry()
    })
    expect(request.mock.calls[2][1]).toMatchObject({
      testBatchId: originalBatch,
      testBatchRole: 'TARGET',
      command: 'original-command',
      environmentVariables: { MODE: 'original' },
      buildRunId: 'TARGET-build',
      workingDirectory: '/TARGET'
    })
    expect(complete).toHaveBeenCalledOnce()
    act(() => result.current.startNewBatch())
    expect(result.current.batch).toBeUndefined()
    await act(async () => {
      await result.current.submit({ command: 'new-command' })
    })
    expect(request.mock.calls[3][1]).toMatchObject({
      command: 'new-command',
      buildRunId: 'SOURCE-new-build',
      workingDirectory: '/new-workspace'
    })
    expect(request.mock.calls[3][1].testBatchId).not.toBe(originalBatch)
    expect(request.mock.calls[3][1].environmentVariables).toBeUndefined()
    expect(request.mock.calls[4][1].testBatchId).toBe(request.mock.calls[3][1].testBatchId)
  })

  it('locks the batch identity during a pending retry and ignores duplicate retry requests', async () => {
    const request = vi.mocked(createProjectAutomatedTest)
    request.mockRejectedValue(new Error('offline'))
    const { result } = renderHook(() => useTestBatchSubmission('A', true, contexts))
    await act(async () => {
      await result.current.submit({ command: 'test' })
    })
    const batchId = result.current.batch!.id
    let finish!: (value: Awaited<ReturnType<typeof createProjectAutomatedTest>>) => void
    const pendingTask = new Promise<Awaited<ReturnType<typeof createProjectAutomatedTest>>>(
      (resolve) => {
        finish = resolve
      }
    )
    request.mockReturnValue(pendingTask)
    let pending!: Promise<unknown>
    act(() => {
      pending = result.current.retry()
      void result.current.retry()
      result.current.startNewBatch()
    })
    expect(result.current.isPending).toBe(true)
    expect(result.current.batch?.id).toBe(batchId)
    expect(request).toHaveBeenCalledTimes(4)
    await act(async () => {
      finish({ id: 'recovered' } as Awaited<ReturnType<typeof createProjectAutomatedTest>>)
      await pending
    })
    expect(result.current.isPending).toBe(false)
    expect(result.current.batch?.failed).toEqual([])
    expect(result.current.batch?.id).toBe(batchId)
    await act(async () => {
      await result.current.retry()
    })
    expect(request).toHaveBeenCalledTimes(4)
  })

  it.each([
    { project: undefined, inputs: contexts, active: true },
    { project: 'A', inputs: [], active: true },
    { project: 'A', inputs: [contexts[0]], active: true },
    { project: 'A', inputs: contexts, active: false }
  ])(
    'does not create a partial pair before project, inputs and submission scope are ready ($project, $active)',
    async ({ project, inputs, active }) => {
      const { result } = renderHook(() => useTestBatchSubmission(project, active, inputs))
      await act(async () => {
        await result.current.retry()
        await result.current.submit({ command: 'test' })
      })
      expect(createProjectAutomatedTest).not.toHaveBeenCalled()
      expect(result.current.batch).toBeUndefined()
    }
  )
})
