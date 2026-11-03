import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'

import { getProjectOptimizationCycles } from '@/api/project/optimization-cycle'
import { createOptimizationCycle } from '@/test/optimization-cycle-fixtures'
import { renderWithQueryClient } from '@/test/test-utils'
import type { ProjectOptimizationCycle } from '@/types/project'

import { CycleHistory } from './CycleHistory'

vi.mock('@/api/project/optimization-cycle', () => ({ getProjectOptimizationCycles: vi.fn() }))
function page(item: ProjectOptimizationCycle) {
  return { items: [item], total: 1, page: 1, pageSize: 10, hasMore: false }
}
it('filters historical cycles by status and opens the selected immutable snapshot including an old source', async () => {
  const current = createOptimizationCycle()
  const archived: ProjectOptimizationCycle = {
    ...current,
    id: 'archived-cycle',
    status: 'ARCHIVED',
    cycleNumber: 7,
    sourceRevisionId: 'source-old',
    sourceRevision: {
      ...current.sourceRevision!,
      id: 'source-old',
      active: false,
      versionLabel: 'v-old'
    }
  }
  vi.mocked(getProjectOptimizationCycles).mockImplementation(async (_project, query) =>
    page(query?.status === 'ARCHIVED' ? archived : current)
  )
  const open = vi.fn()
  renderWithQueryClient(<CycleHistory projectId="project-1" onOpen={open} />)
  await screen.findByRole('button', { name: '查看' })
  expect(getProjectOptimizationCycles).toHaveBeenCalledWith('project-1', {
    page: 1,
    pageSize: 10,
    sortBy: 'createdAt',
    sortDirection: 'desc',
    status: undefined
  })
  const actor = userEvent.setup()
  await actor.click(screen.getByRole('combobox', { name: '轮次状态' }))
  await actor.click(
    await screen.findByText('已归档', { selector: '.ant-select-item-option-content' })
  )
  await screen.findByText('v-old')
  expect(getProjectOptimizationCycles).toHaveBeenLastCalledWith('project-1', {
    page: 1,
    pageSize: 10,
    sortBy: 'createdAt',
    sortDirection: 'desc',
    status: 'ARCHIVED'
  })
  await actor.click(screen.getByRole('button', { name: '查看' }))
  expect(open).toHaveBeenCalledExactlyOnceWith(archived)
})
it('loads the new project history and binds selection to the new parent callback', async () => {
  const a = createOptimizationCycle()
  const b: ProjectOptimizationCycle = {
    ...a,
    id: 'cycle-B',
    projectId: 'project-B',
    cycleNumber: 3,
    sourceRevision: { ...a.sourceRevision!, projectId: 'project-B', versionLabel: 'v-B' }
  }
  vi.mocked(getProjectOptimizationCycles).mockImplementation(async (project) =>
    page(project === 'project-B' ? b : a)
  )
  const first = vi.fn(),
    latest = vi.fn()
  const view = renderWithQueryClient(<CycleHistory projectId="project-1" onOpen={first} />)
  await screen.findByText('v1')
  view.rerender(<CycleHistory projectId="project-B" onOpen={latest} />)
  await screen.findByText('v-B')
  await waitFor(() => expect(screen.queryByText('v1')).not.toBeInTheDocument())
  expect(getProjectOptimizationCycles).toHaveBeenLastCalledWith('project-B', {
    page: 1,
    pageSize: 10,
    sortBy: 'createdAt',
    sortDirection: 'desc',
    status: undefined
  })
  await userEvent.setup().click(screen.getByRole('button', { name: '查看' }))
  expect(latest).toHaveBeenCalledExactlyOnceWith(b)
  expect(first).not.toHaveBeenCalled()
})
