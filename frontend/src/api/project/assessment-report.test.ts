import { expect, it, vi } from 'vitest'

import { createProjectEvaluationReport } from './assessment-report'
const post = vi.hoisted(() => vi.fn())
vi.mock('../request', () => ({ default: { post } }))
it('allows a report request without an explicit source override and binds one when supplied', async () => {
  await createProjectEvaluationReport('project')
  expect(post).toHaveBeenLastCalledWith('/projects/project/reports/evaluation', undefined)
  await createProjectEvaluationReport('project', 'selected-source')
  expect(post).toHaveBeenLastCalledWith('/projects/project/reports/evaluation', {
    sourceRevisionId: 'selected-source'
  })
})
