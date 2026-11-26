import { describe, expect, it, vi } from 'vitest'

const { request } = vi.hoisted(() => ({
  request: {
    get: vi.fn(),
    patch: vi.fn(),
    post: vi.fn(),
    put: vi.fn()
  }
}))

vi.mock('../request', () => ({
  default: request,
  LONG_RUNNING_REQUEST_TIMEOUT_MS: 3_600_000
}))

import {
  getAgentRuntimes,
  getModelProfileAgentTestRun,
  testModelProfileAgents,
  testModelProfileConnectivity,
  updateModelStageAssignments
} from './models'

describe('model profile API contract', () => {
  it('uses the split connectivity and multi-Agent test endpoints', () => {
    getAgentRuntimes()
    updateModelStageAssignments('TEST_LOG_COMPARE', 'profile-1', 'runtime-1')
    updateModelStageAssignments('KB_AI_EXTENSION', 'profile-1', 'runtime-1')
    testModelProfileConnectivity('profile-1')
    testModelProfileAgents('profile-1', ['runtime-1', 'runtime-2'])
    getModelProfileAgentTestRun('run-1')

    expect(request.get).toHaveBeenCalledWith('/me/agent-runtimes')
    expect(request.patch).toHaveBeenNthCalledWith(1, '/me/model-stage-assignments', {
      assignments: [
        { agentRuntimeId: 'runtime-1', modelProfileId: 'profile-1', stage: 'TEST_LOG_COMPARE' }
      ]
    })
    expect(request.patch).toHaveBeenNthCalledWith(2, '/me/model-stage-assignments', {
      assignments: [
        { agentRuntimeId: 'runtime-1', modelProfileId: 'profile-1', stage: 'KB_AI_EXTENSION' }
      ]
    })
    expect(request.post).toHaveBeenCalledWith(
      '/me/model-profiles/profile-1:test-connectivity',
      undefined,
      { timeout: 3_600_000 }
    )
    expect(request.post).toHaveBeenCalledWith('/me/model-profiles/profile-1:test-agents', {
      agentRuntimeIds: ['runtime-1', 'runtime-2']
    })
    expect(request.get).toHaveBeenCalledWith('/me/model-profile-agent-test-runs/run-1')
  })
})
