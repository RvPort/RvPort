import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  toEnvironmentConnectionRequest,
  toEnvironmentUpdateRequest
} from '@/pages/project-detail/StageDrawer/Environment/environment-input'
import { createEnvironmentFormValues } from '@/pages/project-detail/StageDrawer/Environment/model'
import { createEnvironment } from '@/test/test-utils'

const request = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), patch: vi.fn() }))
vi.mock('../request', () => ({ default: request }))
import {
  createProjectEnvironment,
  getProjectEnvironments,
  getProjectEnvironmentScriptExecution,
  getProjectEnvironmentScriptExecutions,
  runProjectEnvironmentScript,
  testProjectEnvironmentConnection,
  updateProjectEnvironment
} from './environments'

const fixture = {
  id: 'env-1',
  name: 'saved',
  role: 'SOURCE_X86',
  arch: 'x86_64',
  host: 'saved-host',
  sshPort: 22,
  username: 'saved-user',
  credential: { id: 'credential-1', configured: true, kind: 'SSH_PASSWORD' },
  status: 'AVAILABLE',
  basicInfo: null,
  enabled: true,
  workingDirectory: '/saved',
  jumpHost: null
}

describe('environment credential contract', () => {
  beforeEach(() => {
    request.post.mockResolvedValue({})
    request.patch.mockResolvedValue(fixture)
  })

  it.each(['SSH_PASSWORD', 'SSH_KEY'])(
    'maps username and credential id and retains %s without secrets',
    async (kind) => {
      request.get.mockResolvedValue({
        items: [{ ...fixture, credential: { ...fixture.credential, kind } }],
        total: 1
      })
      const page = await getProjectEnvironments('project', { page: 1, pageSize: 20 })
      const environment = page.items[0]
      expect(environment).toMatchObject({ username: 'saved-user', credentialId: 'credential-1' })
      const values = {
        ...createEnvironmentFormValues(environment),
        host: ' draft-host ',
        sshPort: 2222,
        username: ' draft-user ',
        workdir: '/draft',
        password: 'stale secret',
        privateKey: 'stale key'
      }
      await testProjectEnvironmentConnection(
        'project',
        toEnvironmentConnectionRequest(values, environment)
      )
      const payload = request.post.mock.calls[0][1]
      expect(payload).toMatchObject({
        host: 'draft-host',
        sshPort: 2222,
        username: 'draft-user',
        workingDirectory: '/draft',
        credentialId: 'credential-1'
      })
      for (const field of ['loginType', 'password', 'privateKey'])
        expect(payload).not.toHaveProperty(field)
      await updateProjectEnvironment(environment.id, toEnvironmentUpdateRequest(values))
      const update = request.patch.mock.calls[0][1]
      for (const field of ['loginType', 'password', 'privateKey'])
        expect(update).not.toHaveProperty(field)
    }
  )

  it.each(['PASSWORD', 'KEY'] as const)(
    'uses replacement %s instead of an old reference',
    async (authType) => {
      const environment = createEnvironment({ credentialId: 'old' })
      const values = {
        ...createEnvironmentFormValues(environment),
        credentialMode: 'replace' as const,
        authType,
        password: 'new-password',
        privateKey: 'new-key'
      }
      await testProjectEnvironmentConnection(
        'project',
        toEnvironmentConnectionRequest(values, environment)
      )
      const payload = request.post.mock.calls[0][1]
      expect(payload.loginType).toBe(authType)
      expect(payload).not.toHaveProperty('credentialId')
      expect(payload).toHaveProperty(
        authType === 'PASSWORD' ? 'password' : 'privateKey',
        authType === 'PASSWORD' ? 'new-password' : 'new-key'
      )
      expect(payload).not.toHaveProperty(authType === 'PASSWORD' ? 'privateKey' : 'password')
    }
  )

  it('permits normal updates without a credential reference but refuses retained precheck', () => {
    const environment = createEnvironment({ credentialId: null })
    const values = createEnvironmentFormValues(environment)
    expect(() => toEnvironmentConnectionRequest(values, environment)).toThrow('缺少引用')
    expect(toEnvironmentUpdateRequest(values)).not.toHaveProperty('authType')
  })

  it.each([
    ['SOURCE_X86', 'SOURCE', 'AVAILABLE', 'SUCCESS'],
    ['TARGET_RISCV', 'TARGET', 'UNAVAILABLE', 'FAILED'],
    ['GENERAL_EXECUTION', 'JUMP_HOST', 'UNKNOWN', 'UNKNOWN']
  ] as const)(
    'maps a %s environment and %s connection status',
    async (role, expectedRole, status, expectedStatus) => {
      request.get.mockResolvedValue({
        items: [
          {
            ...fixture,
            role,
            status,
            credential: { configured: false, kind: 'SSH_KEY' },
            workingDirectory: null,
            jumpHost: { id: 'jump-1', name: 'gateway' }
          }
        ],
        total: 1
      })
      const page = await getProjectEnvironments('project', {
        page: 2,
        pageSize: 10,
        role: expectedRole
      })
      expect(request.get).toHaveBeenCalledWith('/projects/project/environments', {
        params: { role, status: undefined, page: 2, pageSize: 10 }
      })
      expect(page.items[0]).toMatchObject({
        role: expectedRole,
        connectionStatus: expectedStatus,
        authType: 'KEY',
        workdir: '',
        jumpHostEnvironmentId: 'jump-1',
        credentialId: null
      })
    }
  )

  it('maps a created environment with collected operating-system details', async () => {
    request.post.mockResolvedValue({
      ...fixture,
      basicInfo: {
        arch: 'x86_64',
        basicTools: {},
        checkedAt: '2026-10-27T08:00:00Z',
        kernelVersion: null,
        osName: 'Ubuntu',
        osVersion: '24.04',
        packageManager: null,
        permissions: {},
        remoteUser: null,
        snapshotId: 'snapshot-1',
        snapshotKind: 'BASIC_CHECK',
        toolVersions: {},
        workingDirectory: {}
      }
    })
    const created = await createProjectEnvironment('project', {
      name: 'saved',
      role: 'SOURCE',
      architecture: 'x86_64',
      host: 'saved-host',
      sshPort: 22,
      workdir: '/saved',
      authType: 'PASSWORD',
      username: 'saved-user',
      password: 'secret'
    })
    expect(request.post.mock.calls[0][1]).toMatchObject({
      role: 'SOURCE_X86',
      loginType: 'PASSWORD',
      password: 'secret'
    })
    expect(created).toMatchObject({ os: 'Ubuntu 24.04', authType: 'PASSWORD' })
  })

  it.each([
    ['COLLECTION', 'ENV_COLLECT'],
    ['PRECHECK', 'ENV_CHECK'],
    ['CUSTOM', 'CUSTOM_BASH']
  ] as const)('submits a %s script as %s', async (scriptType, backendType) => {
    request.post.mockResolvedValue({
      id: 'task-1',
      projectId: 'project',
      type: 'ENV_SCRIPT',
      status: 'SUCCEEDED',
      progress: 100,
      message: 'done',
      createdAt: '2026-10-27T08:00:00Z',
      startedAt: '2026-10-27T08:01:00Z',
      finishedAt: '2026-10-27T08:02:00Z',
      resource: { id: 'env-1', type: 'ENVIRONMENT' }
    })
    const result = await runProjectEnvironmentScript('project', 'env-1', {
      scriptType,
      workdir: '/workspace',
      timeout: 0,
      content: 'pwd'
    })
    const payload = request.post.mock.calls[0][1]
    expect(payload).toMatchObject({ scriptType: backendType, timeoutSeconds: 600 })
    if (scriptType === 'CUSTOM') expect(payload).toHaveProperty('script', 'pwd')
    else expect(payload).not.toHaveProperty('script')
    expect(result).toMatchObject({ status: 'SUCCEEDED', exitCode: 0, environmentId: 'env-1' })
  })

  it.each([
    ['FAILED', 'FAILED', 1],
    ['CANCELLED', 'FAILED', null],
    ['TIMEOUT', 'FAILED', null],
    ['RUNNING', 'RUNNING', null]
  ] as const)('normalizes a %s task to %s', async (status, expectedStatus, exitCode) => {
    request.get.mockResolvedValue({
      id: 'task-1',
      projectId: 'project',
      type: 'ENV_SCRIPT',
      status,
      progress: 30,
      message: null,
      errorMessage: 'failure',
      createdAt: '2026-10-27T08:00:00Z',
      startedAt: null,
      finishedAt: null,
      resource: null
    })
    const result = await getProjectEnvironmentScriptExecution('task-1')
    expect(result).toMatchObject({
      status: expectedStatus,
      exitCode,
      environmentId: 'task-1',
      stdout: '',
      stderr: 'failure'
    })
  })

  it.each([
    ['SUCCEEDED', 'SUCCEEDED'],
    ['ERROR', 'FAILED'],
    ['RUNNING', 'RUNNING']
  ] as const)('maps a stored %s script execution', async (status, expectedStatus) => {
    request.get.mockResolvedValue({
      items: [
        {
          id: 'script-1',
          environmentId: 'env-1',
          status,
          progress: 30,
          message: null,
          scriptSha256: null,
          scriptLength: null,
          exitCode: null,
          logEventCount: 0,
          createdAt: '2026-10-27T08:00:00Z',
          startedAt: null,
          finishedAt: null
        }
      ],
      total: 1
    })
    const page = await getProjectEnvironmentScriptExecutions('project', {
      environmentId: 'env-1',
      page: 1,
      pageSize: 20
    })
    expect(page.items[0]).toMatchObject({
      status: expectedStatus,
      scriptType: 'CUSTOM',
      stdout: ''
    })
  })
})

it('supports partial environment updates without resending role or credentials', async () => {
  request.patch.mockResolvedValue(fixture)
  await updateProjectEnvironment('env-1', { host: 'new-host' })
  const payload = request.patch.mock.calls[0][1]
  expect(payload.host).toBe('new-host')
  expect(payload.role).toBeUndefined()
  for (const name of ['loginType', 'password', 'privateKey'])
    expect(payload).not.toHaveProperty(name)
})
it.each(['PASSWORD', 'KEY'] as const)(
  'sends only the selected %s replacement credential in an update',
  async (authType) => {
    request.patch.mockResolvedValue(fixture)
    await updateProjectEnvironment('env-1', {
      authType,
      password: 'new-password',
      privateKey: 'new-private-key'
    })
    const payload = request.patch.mock.calls[0][1]
    expect(payload.loginType).toBe(authType)
    expect(payload).toHaveProperty(
      authType === 'PASSWORD' ? 'password' : 'privateKey',
      authType === 'PASSWORD' ? 'new-password' : 'new-private-key'
    )
    expect(payload).not.toHaveProperty(authType === 'PASSWORD' ? 'privateKey' : 'password')
  }
)
