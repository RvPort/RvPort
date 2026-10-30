import { QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { AxiosError } from 'axios'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

import { advanceAuthSession, SessionChangedError } from '@/store/authSession'
import { createTestQueryClient } from '@/test/test-utils'
import { feedback } from '@/utils/feedback'

import { useSessionMutation } from './useSessionMutation'

vi.mock('@/utils/feedback', () => ({ feedback: { error: vi.fn() } }))
function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={createTestQueryClient()}>{children}</QueryClientProvider>
}

describe('owned mutation', () => {
  it.each(['success', 'failure'])('ignores late %s after unmount', async (outcome) => {
    let resolve!: (value: string) => void
    let reject!: (error: Error) => void
    const mutationFn = vi.fn(
      () =>
        new Promise<string>((yes, no) => {
          resolve = yes
          reject = no
        })
    )
    const onSuccess = vi.fn(),
      onError = vi.fn(),
      onSettled = vi.fn()
    const { result, unmount } = renderHook(
      () =>
        useSessionMutation({
          sessionKey: 'A',
          active: true,
          mutationFn,
          onSuccess,
          onError,
          onSettled
        }),
      { wrapper }
    )
    let pending!: Promise<unknown>
    act(() => {
      pending = result.current.mutateAsync(undefined)
    })
    await waitFor(() => expect(mutationFn).toHaveBeenCalledOnce())
    unmount()
    await act(async () => {
      if (outcome === 'success') resolve('old')
      else reject(new SessionChangedError())
      await pending
    })
    expect(onSuccess).not.toHaveBeenCalled()
    expect(onError).not.toHaveBeenCalled()
    expect(onSettled).not.toHaveBeenCalled()
    expect(feedback.error).not.toHaveBeenCalled()
  })
  it('does not start a queued write after auth changes and locks repeated calls', async () => {
    const mutationFn = vi.fn(async () => 'saved')
    const { result } = renderHook(
      () => useSessionMutation({ sessionKey: 'A', active: true, mutationFn }),
      { wrapper }
    )
    let pending!: Promise<unknown>
    act(() => {
      pending = result.current.mutateAsync(undefined)
      result.current.mutate(undefined)
      advanceAuthSession()
    })
    await act(async () => {
      await pending
    })
    expect(mutationFn).not.toHaveBeenCalled()
    expect(feedback.error).not.toHaveBeenCalled()
  })
  it('keeps the lock through success processing and unlocks after a failed attempt', async () => {
    const mutationFn = vi.fn().mockRejectedValueOnce(new Error('retryable')).mockResolvedValue('ok')
    let finish!: () => void
    const onSuccess = vi.fn(
      () =>
        new Promise<void>((yes) => {
          finish = yes
        })
    )
    const { result } = renderHook(
      () => useSessionMutation({ sessionKey: 'A', active: true, mutationFn, onSuccess }),
      { wrapper }
    )
    await act(async () => {
      await result.current.mutateAsync(undefined)
    })
    expect(result.current.isPending).toBe(false)
    expect(feedback.error).toHaveBeenCalledWith('retryable')
    let pending!: Promise<unknown>
    act(() => {
      pending = result.current.mutateAsync(undefined)
    })
    await waitFor(() => expect(onSuccess).toHaveBeenCalledOnce())
    act(() => result.current.mutate(undefined))
    expect(mutationFn).toHaveBeenCalledTimes(2)
    expect(result.current.isPending).toBe(true)
    await act(async () => {
      finish()
      await pending
    })
    expect(result.current.isPending).toBe(false)
  })
  it.each([
    ['opaque failure', '提交失败，请重试'],
    [new AxiosError('already reported'), undefined]
  ])(
    'handles nonstandard request errors without duplicating transport feedback: %s',
    async (error, expected) => {
      const { result } = renderHook(
        () =>
          useSessionMutation({
            sessionKey: 'error-shape',
            active: true,
            mutationFn: async () => {
              throw error
            }
          }),
        { wrapper }
      )
      await act(async () => {
        await result.current.mutateAsync(undefined)
      })
      if (expected) expect(feedback.error).toHaveBeenCalledExactlyOnceWith(expected)
      else expect(feedback.error).not.toHaveBeenCalled()
      expect(result.current.isPending).toBe(false)
    }
  )

  it('delegates current request failures to the caller and passes the failed variables to settlement', async () => {
    const failure = new Error('profile rejected'),
      onError = vi.fn(),
      onSettled = vi.fn()
    const mutationFn = vi.fn<(id: string) => Promise<never>>().mockRejectedValue(failure)
    const { result } = renderHook(
      () =>
        useSessionMutation({
          sessionKey: 'caller-error',
          active: true,
          mutationFn,
          onError,
          onSettled
        }),
      { wrapper }
    )
    await act(async () => {
      await result.current.mutateAsync('profile-7')
    })
    expect(onError).toHaveBeenCalledExactlyOnceWith(failure, 'profile-7')
    expect(onSettled).toHaveBeenCalledExactlyOnceWith(undefined, failure, 'profile-7')
    expect(feedback.error).not.toHaveBeenCalled()
    expect(result.current.isPending).toBe(false)
  })
})
