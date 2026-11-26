import { expect, it, vi } from 'vitest'

import { createProjectEvaluationReport } from './assessment-report'
const post = vi.hoisted(() => vi.fn())
vi.mock('../request', () => ({ default: { post } }))
it('sends the generation contract with an idempotency key header', async () => {
  const payload = {
    sourceRevisionId: 'selected-source',
    mode: 'STANDARD' as const,
    selectionToken: 'selection-token-1'
  }
  await createProjectEvaluationReport('project', payload)
  expect(post).toHaveBeenLastCalledWith('/projects/project/reports/evaluation', payload, {
    headers: { 'Idempotency-Key': expect.any(String) }
  })
  await createProjectEvaluationReport('project', {
    mode: 'AI_ENHANCED',
    selectionToken: 'selection-token-2',
    baseReportId: 'base-1',
    expectedDraftVersion: 3
  })
  expect(post).toHaveBeenLastCalledWith(
    '/projects/project/reports/evaluation',
    { mode: 'AI_ENHANCED', selectionToken: 'selection-token-2', baseReportId: 'base-1', expectedDraftVersion: 3 },
    { headers: { 'Idempotency-Key': expect.any(String) } }
  )
})
