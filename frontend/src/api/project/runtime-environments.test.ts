import { expect, it, vi } from 'vitest'

import {
  type BackendProjectRuntimeEnvironments,
  type BackendRuntimeEnvironmentSummary,
  toProjectRuntimeEnvironments,
  updateProjectRuntimeEnvironments
} from './runtime-environments'
const patch = vi.hoisted(() => vi.fn())
vi.mock('../request', () => ({ default: { patch } }))
const source: BackendRuntimeEnvironmentSummary = {
  id: 'source',
  name: '源环境',
  role: 'SOURCE_X86',
  arch: 'x86_64',
  status: 'AVAILABLE',
  enabled: true,
  configVersion: 2
}
const base: BackendProjectRuntimeEnvironments = {
  projectId: 'project',
  sourceEnvironment: source,
  targetEnvironment: null,
  version: 3,
  ready: false,
  missing: ['TARGET'],
  updatedBy: null,
  updatedAt: null
}

it.each([
  ['AVAILABLE', true, 'SUCCESS'],
  ['UNAVAILABLE', true, 'FAILED'],
  ['UNKNOWN', true, 'UNKNOWN'],
  ['DISABLED', false, 'UNKNOWN']
] as const)(
  'maps target connection %s independently from enablement and preserves source identity',
  (status, enabled, connectionStatus) => {
    const result = toProjectRuntimeEnvironments({
      ...base,
      targetEnvironment: {
        ...source,
        id: 'target',
        role: 'TARGET_RISCV',
        arch: 'riscv64',
        status,
        enabled
      }
    })
    expect(result.sourceEnvironment).toMatchObject({
      id: 'source',
      role: 'SOURCE',
      architecture: 'x86_64',
      configVersion: 2
    })
    expect(result.targetEnvironment).toMatchObject({
      id: 'target',
      role: 'TARGET',
      architecture: 'riscv64',
      enabled,
      connectionStatus
    })
    expect(result).toMatchObject({ version: 3, ready: false, missing: ['TARGET'] })
  }
)
it('maps a partial source binding response without inventing target readiness', async () => {
  patch.mockResolvedValue(base)
  const result = await updateProjectRuntimeEnvironments('project', {
    sourceEnvironmentId: 'source'
  })
  expect(patch).toHaveBeenCalledWith('/projects/project/runtime-environments', {
    sourceEnvironmentId: 'source'
  })
  expect(result.sourceEnvironment?.id).toBe('source')
  expect(result.targetEnvironment).toBeNull()
  expect(result.ready).toBe(false)
})
it('preserves an overview with neither runtime environment assigned', () => {
  const result = toProjectRuntimeEnvironments({ ...base, sourceEnvironment: null })
  expect(result.sourceEnvironment).toBeNull()
  expect(result.targetEnvironment).toBeNull()
  expect(result.ready).toBe(false)
})
