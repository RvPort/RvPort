import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { PropsWithChildren } from 'react'
import { describe, expect, it, vi } from 'vitest'

const { getProjects } = vi.hoisted(() => ({ getProjects: vi.fn() }))

vi.mock('@/api/project/projects', () => ({ getProjects }))

import { projectListQueryKey, useProjectList } from './useProjectList'

function createWrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

describe('useProjectList', () => {
  it('fetches the next declared page and stops at the terminal page while preserving filters', async () => {
    getProjects
      .mockResolvedValueOnce({ items: [], page: 1, pageSize: 20, total: 21, hasMore: true })
      .mockResolvedValueOnce({ items: [], page: 2, pageSize: 20, total: 21, hasMore: false })
    const { result } = renderHook(
      () => useProjectList({ keyword: '', status: 'ARCHIVED', stage: 'ARCHIVED' }),
      { wrapper: createWrapper() }
    )
    await waitFor(() => expect(result.current.hasNextPage).toBe(true))
    await act(async () => {
      await result.current.fetchNextPage()
    })
    await waitFor(() => expect(result.current.hasNextPage).toBe(false))
    expect(getProjects).toHaveBeenNthCalledWith(2, {
      keyword: undefined,
      status: 'ARCHIVED',
      stage: 'ARCHIVED',
      page: 2,
      pageSize: 20
    })
    await act(async () => {
      await result.current.fetchNextPage()
    })
    expect(getProjects).toHaveBeenCalledTimes(2)
    expect(result.current.total).toBe(21)
  })
  it('includes supported filters in the infinite query identity', () => {
    expect(projectListQueryKey({ keyword: 'demo', stage: 'SCANNING', status: 'ACTIVE' })).toEqual([
      'projects',
      { keyword: 'demo', pageSize: 20, stage: 'SCANNING', status: 'ACTIVE' }
    ])
  })

  it('starts from page one when the search conditions change', async () => {
    getProjects.mockResolvedValue({ items: [], page: 1, pageSize: 20, total: 0, hasMore: false })
    const { rerender } = renderHook(({ keyword }) => useProjectList({ keyword }), {
      initialProps: { keyword: 'first' },
      wrapper: createWrapper()
    })

    await waitFor(() => expect(getProjects).toHaveBeenCalledTimes(1))
    expect(getProjects).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: 'first', page: 1 })
    )

    rerender({ keyword: 'second' })

    await waitFor(() => expect(getProjects).toHaveBeenCalledTimes(2))
    expect(getProjects).toHaveBeenLastCalledWith(
      expect.objectContaining({ keyword: 'second', page: 1 })
    )
  })
})
