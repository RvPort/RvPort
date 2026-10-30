import { expect, it } from 'vitest'

import { createTestQueryClient } from '@/test/test-utils'

import { projectQueryKeys } from './queryKeys'

it.each([
  {
    name: 'reports',
    key: projectQueryKeys.evaluationReports,
    root: projectQueryKeys.evaluationReports
  },
  {
    name: 'automated tests',
    key: projectQueryKeys.automatedTests,
    root: projectQueryKeys.automatedTests
  },
  {
    name: 'test comparisons',
    key: projectQueryKeys.testComparisons,
    root: projectQueryKeys.testComparisons
  },
  { name: 'x86 builds', key: projectQueryKeys.x86Builds, root: projectQueryKeys.buildValidations },
  {
    name: 'dependency analyses',
    key: projectQueryKeys.dependencyAnalyses,
    root: projectQueryKeys.dependencyAnalyses
  }
])(
  'isolates $name by context and invalidates the entire selected project without touching another project',
  async ({ key, root }) => {
    const client = createTestQueryClient()
    const first = key('project', 'first'),
      next = key('project', 'next'),
      unrelated = key('other', 'first')
    client.setQueryData(first, { version: 'first' })
    client.setQueryData(next, { version: 'next' })
    client.setQueryData(unrelated, { version: 'other' })
    expect(client.getQueryData(first)).toEqual({ version: 'first' })
    expect(client.getQueryData(next)).toEqual({ version: 'next' })
    await client.invalidateQueries({ queryKey: root('project'), refetchType: 'none' })
    expect(client.getQueryState(first)?.isInvalidated).toBe(true)
    expect(client.getQueryState(next)?.isInvalidated).toBe(true)
    expect(client.getQueryState(unrelated)?.isInvalidated).toBe(false)
    client.clear()
  }
)
